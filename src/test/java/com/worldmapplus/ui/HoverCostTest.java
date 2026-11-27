package com.worldmapplus.ui;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.Assume;
import org.junit.Test;

/**
 * Measures what a frame costs with the mouse resting on a thing that shows a card. Runs only when WORLDMAPPLUS_PERF is set, since timings
 * mean nothing on a busy machine, and prints the average and the slowest frame for a few typical views.
 */
public class HoverCostTest
{
	private static final Rectangle BOUNDS = new Rectangle(0, 0, 1280, 720);

	/** WORLDMAPPLUS_PERF_OFF names the features to leave out, such as labels,hotspots,poi. */
	private static boolean on(String feature)
	{
		String off = System.getenv("WORLDMAPPLUS_PERF_OFF");
		return off == null || !java.util.Arrays.asList(off.split(",")).contains(feature);
	}

	private static volatile int mouseX = 640;
	private static volatile int mouseY = 360;

	private static Client client()
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(mouseX, mouseY);
				}
				Class<?> type = method.getReturnType();
				if (type == boolean.class)
				{
					return false;
				}
				if (type == int.class || type == long.class || type == double.class || type == float.class)
				{
					return 0;
				}
				return null;
			});
	}

	@Test
	public void framesWithTheMouseRestingOnACard() throws Exception
	{
		Assume.assumeTrue(System.getenv("WORLDMAPPLUS_PERF") != null);
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		MapAssets assets = new MapAssets(new Gson(), executor, new com.worldmapplus.tiles.SourceTiles(new Gson()));
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		SurfaceServices services = new SurfaceServices(new ViewMemory(null)
		{
			@Override
			public Saved load(String slot)
			{
				return null;
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), new com.google.gson.Gson());
		MapSurface surface = new MapSurface(client(), assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return "agility,minigame,bank,mining,fishing,farming,tree,port,teleport,altar";
			}
		}, services);
		MapInfo map = assets.getCatalog().surface();
		Hotspot withPreview = null;
		Hotspot plain = null;
		for (Hotspot h : assets.getHotspots().all())
		{
			if (h.getMapId() != map.getId())
			{
				continue;
			}
			if (withPreview == null && h.getCard().hasPreview())
			{
				withPreview = h;
			}
			if (plain == null && !h.getCard().hasPreview())
			{
				plain = h;
			}
		}
		BufferedImage canvas = new BufferedImage(BOUNDS.width, BOUNDS.height, BufferedImage.TYPE_INT_ARGB);
		Hotspot[] cases = {withPreview, plain};
		String[] names = {"card with a picture", "card without a picture"};
		for (int k = 0; k < cases.length; k++)
		{
			Hotspot h = cases[k];
			Assume.assumeTrue("no hotspot for case " + k, h != null);
			PlayerPosition at = new PlayerPosition(h.getX(), h.getY(), new WorldPoint((int) h.getX(), (int) h.getY(), 0));
			MapSurface.Options options = new MapSurface.Options().followOnOpen(true).openZoom(4.0);
			Graphics2D g = canvas.createGraphics();
			long now = 1_000_000;
			mouseX = 640;
			mouseY = 360;
			for (int i = 0; i < 80; i++)
			{
				now += 16;
				surface.render(g, BOUNDS, at, options, now);
				Thread.sleep(5);
			}
			surface.viewer().jumpZoom(4.0);
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
			long[] results = new long[2];
			double[] kb = new double[2];
			for (int phase = 0; phase < 2; phase++)
			{
				// Phase 0: mouse on empty ground in a corner. Phase 1: on the thing.
				mouseX = phase == 0 ? 20 : 640;
				mouseY = phase == 0 ? 700 : 360;
				for (int i = 0; i < 100; i++)
				{
					now += 16;
					surface.render(g, BOUNDS, at, options, now);
				}
				long allocated = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
				long total = 0;
				int frames = 400;
				for (int i = 0; i < frames; i++)
				{
					now += 16;
					long start = threads.getCurrentThreadCpuTime();
					surface.render(g, BOUNDS, at, options, now);
					total += threads.getCurrentThreadCpuTime() - start;
				}
				results[phase] = total / frames;
				kb[phase] = (threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated) / 1024.0 / frames;
			}
			g.dispose();
			System.out.printf("HOVER %s (%s): no card %.2f ms %.0f KB, card %.2f ms %.0f KB, card costs %.2f ms and %.0f KB a frame%n",
				names[k], h.getCard().getTitle(), results[0] / 1e6, kb[0], results[1] / 1e6, kb[1],
				(results[1] - results[0]) / 1e6, kb[1] - kb[0]);
		}
		executor.shutdownNow();
	}

	/** The card painter alone, on one thread's CPU time, so other work on the machine does not blur it. */
	@Test
	public void theCardPainterAlone() throws Exception
	{
		Assume.assumeTrue(System.getenv("WORLDMAPPLUS_PERF") != null);
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		MapAssets assets = new MapAssets(new Gson(), executor, new com.worldmapplus.tiles.SourceTiles(new Gson()));
		HoverCardPainter painter = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
		MapInfo dungeon = null;
		for (MapInfo m : assets.getCatalog().getMaps())
		{
			if (m.getId() != assets.getCatalog().surface().getId())
			{
				dungeon = m;
				break;
			}
		}
		Assume.assumeTrue(dungeon != null);
		double cx = (dungeon.getMinX() + dungeon.getMaxX()) / 2.0;
		double cy = (dungeon.getMinY() + dungeon.getMaxY()) / 2.0;
		HoverCard.Builder b = HoverCard.builder("A doorway to somewhere").subtitle("Dungeon entrance").hint("Click to go there");
		HoverCard.Builder c = HoverCard.builder("A bank").subtitle("Bank");
		for (int i = 0; i < 6; i++)
		{
			b.row("Row number " + i, "value " + i);
			c.row("Row number " + i, "value " + i);
		}
		HoverCard real = null;
		for (Hotspot h : assets.getHotspots().all())
		{
			if (real == null && h.getCard().hasPreview() && h.getCard().hasPreviewFocus())
			{
				real = h.getCard();
			}
		}
		HoverCard[] cards = {real != null ? real : b.preview(dungeon.getId(), cx, cy).build(), c.build()};
		String[] names = {"card with a picture", "card without a picture"};
		BufferedImage canvas = new BufferedImage(BOUNDS.width, BOUNDS.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		long id = Thread.currentThread().getId();
		for (int k = 0; k < 2; k++)
		{
			for (int i = 0; i < 300; i++)
			{
				painter.paint(g, cards[k], 400, 300, BOUNDS, false, new java.awt.Point(400, 300));
				Thread.sleep(2);
			}
			int frames = 2000;
			long bytes = threads.getThreadAllocatedBytes(id);
			long cpu = threads.getCurrentThreadCpuTime();
			for (int i = 0; i < frames; i++)
			{
				painter.paint(g, cards[k], 400, 300, BOUNDS, false, new java.awt.Point(400, 300));
			}
			cpu = threads.getCurrentThreadCpuTime() - cpu;
			bytes = threads.getThreadAllocatedBytes(id) - bytes;
			System.out.printf("PAINTER %s: %.3f ms cpu and %.0f bytes a frame%n", names[k], cpu / (double) frames / 1e6,
				bytes / (double) frames);
		}
		g.dispose();
		executor.shutdownNow();
	}
}
