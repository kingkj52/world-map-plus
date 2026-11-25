package com.worldmapplus.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.theme.AnimationMode;
import com.worldmapplus.theme.Motion;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Animations end in the picture an instant change draws, do nothing while the map is still, and are all off in the Off mode.
 */
public class MotionSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(0, 0, 900, 600);
	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private long now = 3_000_000;
	private final BufferedImage canvas = new BufferedImage(BOUNDS.width, BOUNDS.height, BufferedImage.TYPE_INT_RGB);

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor);
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	@After
	public void backToOn()
	{
		Motion.use(AnimationMode.ON);
	}

	private MapSurface surface(AnimationMode mode)
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(-1, -1);
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? (Object) false : type.isPrimitive() ? (Object) 0 : null;
			});
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(new ViewMemory(null)
		{
			@Override
			public void save(String slot, int mapId, double x, double y, double ppt)
			{
			}

			@Override
			public Saved load(String slot)
			{
				return null;
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), routes, new PanelLauncher());
		return new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public AnimationMode animations()
			{
				return mode;
			}
		}, services);
	}

	private void frames(MapSurface surface, int count)
	{
		for (int i = 0; i < count; i++)
		{
			Graphics2D g = canvas.createGraphics();
			now += 16;
			surface.render(g, BOUNDS, new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0)),
				new MapSurface.Options().followOnOpen(false).openZoom(4.0), now);
			g.dispose();
		}
	}

	private SearchEntry far()
	{
		return new SearchEntry(SearchKind.PIN, "Far spot", null, assets.getCatalog().surface().getId(), 3500.5, 3600.5);
	}

	@Test
	public void aJumpGlidesWhenOnAndLandsWhereAnInstantJumpDoes()
	{
		MapSurface glide = surface(AnimationMode.ON);
		frames(glide, 5);
		double startX = glide.viewer().getCamera().getX();
		glide.activateForTest(far(), now);
		frames(glide, 3);
		double early = glide.viewer().getCamera().getX();
		assertTrue("on its way", early > startX && early < 3500.5);
		assertTrue(glide.isMoving());
		frames(glide, 60);

		MapSurface instant = surface(AnimationMode.OFF);
		frames(instant, 5);
		instant.activateForTest(far(), now);
		frames(instant, 1);
		assertEquals("off: there at once", 3500.5, instant.viewer().getCamera().getX(), 1e-9);
		frames(instant, 60);

		assertEquals(instant.viewer().getCamera().getX(), glide.viewer().getCamera().getX(), 0);
		assertEquals(instant.viewer().getCamera().getY(), glide.viewer().getCamera().getY(), 0);
		assertEquals(instant.viewer().getZoom().pixelsPerTile(), glide.viewer().getZoom().pixelsPerTile(), 0);
		assertFalse("settled", glide.isMoving());
	}

	@Test
	public void reducedKeepsTheFadesButJumpsAtOnce()
	{
		MapSurface reduced = surface(AnimationMode.REDUCED);
		frames(reduced, 5);
		reduced.activateForTest(far(), now);
		frames(reduced, 1);
		assertEquals(3500.5, reduced.viewer().getCamera().getX(), 1e-9);
		assertTrue(Motion.fades());
		assertFalse(Motion.full());
	}

	@Test
	public void aMapThatIsStillDoesNoWork()
	{
		MapSurface still = surface(AnimationMode.ON);
		frames(still, 100);
		assertFalse(still.isMoving());
		frames(still, 100);
		assertFalse(still.isMoving());
	}

	/** What the current thread has allocated so far, in bytes. */
	private static long allocated()
	{
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	private long bytesPerIdleFrame(AnimationMode mode)
	{
		MapSurface still = surface(mode);
		frames(still, 150);
		long before = allocated();
		frames(still, 200);
		return (allocated() - before) / 200;
	}

	/** The measured cost of animations at rest: an idle frame allocates no more with them on than with them off. */
	@Test
	public void anIdleFrameCostsNoMoreWithAnimationsOnThanOff()
	{
		long off = bytesPerIdleFrame(AnimationMode.OFF);
		long on = bytesPerIdleFrame(AnimationMode.ON);
		long off2 = bytesPerIdleFrame(AnimationMode.OFF);
		long on2 = bytesPerIdleFrame(AnimationMode.ON);
		System.out.println("idle frame allocation, bytes: off " + off + ", " + off2 + " on " + on + ", " + on2);
		assertTrue("on " + on + " off " + off, Math.min(on, on2) <= Math.max(off, off2) + 64);
	}

	@Test
	public void aMenuFadesInAndFinishesAsTheInstantOneIsDrawn()
	{
		List<ContextMenu.Item> items = new ArrayList<>(Collections.singletonList(new ContextMenu.Item(ContextMenu.Action.COPY_TILE, "Copy tile")));
		Rectangle limit = new Rectangle(0, 0, 400, 300);

		int[] instant = drawMenu(AnimationMode.OFF, items, limit, 0);
		int[] fading = drawMenu(AnimationMode.ON, items, limit, 0);
		int[] done = drawMenu(AnimationMode.ON, items, limit, Motion.FADE_MILLIS + 40);
		assertFalse("starts faint", java.util.Arrays.equals(instant, fading));
		assertArrayEquals("ends as an instant open", instant, done);
	}

	private int[] drawMenu(AnimationMode mode, List<ContextMenu.Item> items, Rectangle limit, long later)
	{
		Motion.use(mode);
		ContextMenu menu = new ContextMenu();
		BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setColor(java.awt.Color.GREEN);
		g.fillRect(0, 0, 400, 300);
		g.setFont(ContextMenu.font());
		menu.open(items, 100, 100, ContextMenu.widthFor(items, g.getFontMetrics()), limit, null);
		menu.paint(g, null, 50_000);
		if (later > 0)
		{
			assertTrue(menu.moving(50_000 + 10));
			menu.paint(g, null, 50_000 + later);
			assertFalse(menu.moving(50_000 + later));
		}
		g.dispose();
		return image.getRGB(0, 0, 400, 300, null, 0, 400);
	}

	@Test
	public void aFadeWithAnimationsOffNeverChangesTheOpacity()
	{
		Motion.use(AnimationMode.OFF);
		Appear appear = new Appear();
		assertEquals(1f, appear.alpha(10), 0);
		assertFalse(appear.moving(10));
		Motion.use(AnimationMode.ON);
		Appear fading = new Appear();
		assertTrue(fading.alpha(1000) < 0.5f);
		assertTrue(fading.alpha(1060) > 0 && fading.alpha(1060) < 1);
		assertEquals(1f, fading.alpha(1000 + Motion.FADE_MILLIS), 0);
		assertEquals("a late frame skips to the end", 1f, fading.alpha(900_000), 0);
		assertTrue(new Appear().alpha(5) < 1f);
	}
}
