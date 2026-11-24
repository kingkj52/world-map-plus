package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.game.TrailTracker;
import com.worldmapplus.layers.NpcLayer;
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
import net.runelite.client.eventbus.EventBus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A layer keeps what it laid out in its own fields. A map in a window paints on the Swing thread while the main map paints on the
 * client thread, so a window must never use the layers the main map uses.
 */
public class WindowLayersTest
{
	private static ScheduledExecutorService executor;
	private static MapAssets assets;

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

	private static MapSurface surface()
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) -> method.getReturnType() == boolean.class ? (Object) false
				: method.getReturnType().isPrimitive() ? (Object) 0 : null);
		NpcLayer quiet = new NpcLayer()
		{
			@Override
			public void paint(Graphics2D g, ViewTransform view, MapInfo viewed, PlayerPosition player)
			{
			}
		};
		ViewMemory memory = new ViewMemory(null)
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
		};
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		SurfaceServices services = new SurfaceServices(quiet, memory, pins, external, new MapActions(new EventBus(), external),
			new Settings(null), new TrailTracker(), null, null,
			new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins),
			new com.worldmapplus.pins.PanelLauncher());
		return new MapSurface(client, assets, new WorldMapPlusConfig() {}, services);
	}

	@Test
	public void theMainMapUsesTheSharedLayersAndEachWindowHasItsOwn()
	{
		MapSurface main = surface();
		MapSurface first = surface();
		MapSurface second = surface();
		first.asWindow(() -> null, Runnable::run);
		second.asWindow(() -> null, Runnable::run);

		assertSame(assets.getGameIconLayer(), main.icons());
		assertSame(assets.getLabels(), main.labelLayer());
		assertNotSame(assets.getGameIconLayer(), first.icons());
		assertNotSame(assets.getLabels(), first.labelLayer());
		assertNotSame(first.icons(), second.icons());
		assertNotSame(first.labelLayer(), second.labelLayer());
		// A window keeps its layer from one frame to the next, or its layout would be redone every frame.
		assertSame(first.icons(), first.icons());
		assertSame(first.labelLayer(), first.labelLayer());
	}

	@Test
	public void aWindowPaintsWithoutTouchingTheMainMapsLayers()
	{
		MapSurface window = surface();
		window.asWindow(() -> null, Runnable::run);
		window.startAt(assets.getCatalog().surface(), 3222.5, 3218.5, 4.0);
		BufferedImage canvas = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		window.render(g, new Rectangle(0, 0, 600, 400), null, new MapSurface.Options().memorySlot("window1").resume(true), 5_000_000);
		g.dispose();
		// Nothing was laid out on the shared layer, so its drawn count is still what it was before any frame.
		assertEquals(0, assets.getGameIconLayer().drawnCount());
	}
}
