package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.bridge.NativePoints;
import com.worldmapplus.compat.MapCensus;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The game's map following this one in normal mode, end to end with stand-ins: a plugin that, like Golems Don't Die, has
 * points only near where the game's map is looking, a game map that goes where it is sent, and the same per-frame steps
 * the viewer overlay takes (read, draw, drive).
 */
public class GameMapFollowTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);

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

	/** Everything one test needs: the surface, the game map stand-in, the plugin stand-in and the counters. */
	private static final class Rig
	{
		boolean sync = true;
		boolean show = true;
		boolean lineUp = false;
		boolean pluginPresent = true;
		boolean mapShowing = true;
		NativeMapSync.Spot gameAt = new NativeMapSync.Spot(3222, 3218);
		final List<int[]> sent = new ArrayList<>();
		final List<int[]> surfaceSent = new ArrayList<>();
		final List<WorldMapPoint> points = new ArrayList<>();
		final NativeMapSync nativeSync = new NativeMapSync();
		MapSurface surface;
		MapCensus census;
		long now = 5_000_000;
		BufferedImage canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);

		/** The plugin: points near where the game's map looks, none anywhere else. */
		void plugin()
		{
			points.clear();
			if (pluginPresent && Math.hypot(gameAt.getX() - 3222, gameAt.getY() - 3218) < 40)
			{
				for (int i = 0; i < 3; i++)
				{
					points.add(new WorldMapPoint(new WorldPoint(3222 + i, 3218, 0), null)
					{
					});
				}
			}
		}

		void frames(int count)
		{
			Graphics2D g = canvas.createGraphics();
			PlayerPosition player = new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
			for (int i = 0; i < count; i++)
			{
				now += 16;
				if (!mapShowing)
				{
					// Not drawn: the overlay forgets what it knew and does nothing else.
					surface.suspend();
					nativeSync.reset();
					continue;
				}
				plugin();
				nativeSync.update(false, gameAt, true);
				surface.render(g, BOUNDS, player, new MapSurface.Options().followOnOpen(true).openZoom(4.0), now);
				NativeMapSync.Spot send = nativeSync.drive(sync && !surface.isLinedUp() && surface.wantsGameMap(), gameAt,
					surface.centerX(), surface.centerY());
				if (send != null)
				{
					sent.add(new int[] {send.getX(), send.getY()});
					gameAt = send;
				}
			}
			g.dispose();
		}
	}

	private Rig rig(boolean sync, boolean show, boolean lineUp)
	{
		Rig rig = new Rig();
		rig.sync = sync;
		rig.show = show;
		rig.lineUp = lineUp;
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(-1, -1);
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
		NpcLayer quiet = new NpcLayer()
		{
			@Override
			public void paint(Graphics2D g, ViewTransform view, MapInfo viewed, PlayerPosition player)
			{
			}
		};
		NativePoints nativePoints = new NativePoints(rig.points::forEach);
		rig.census = new MapCensus(() -> Collections.singletonList(new MapCensus.PluginInfo("Fake Plugin",
			"com.worldmapplus.ui.FakePlugin", true)), sink ->
		{
		}, "com.example.own");
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		SurfaceServices services = new SurfaceServices(quiet, new ViewMemory(null)
		{
			@Override
			public Saved load(String slot)
			{
				return null;
			}

			@Override
			public void save(String slot, int mapId, double x, double y, double ppt)
			{
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), new TrailTracker(), null,
			nativePoints, new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins),
			new com.worldmapplus.pins.PanelLauncher(), rig.census);
		rig.surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public boolean syncGameMap()
			{
				return rig.sync;
			}

			@Override
			public boolean showOtherPluginPoints()
			{
				return rig.show;
			}

			@Override
			public boolean lineUpWithGameMap()
			{
				return rig.lineUp;
			}
		}, services);
		rig.surface.setNativeMap(new NativeMap()
		{
			@Override
			public View view()
			{
				return new View(rig.gameAt.getX(), rig.gameAt.getY(), 6.0);
			}

			@Override
			public void moveTo(int x, int y)
			{
				rig.surfaceSent.add(new int[] {x, y});
			}
		});
		return rig;
	}

	@Test
	public void theGameMapGoesToANewPlaceBecauseAPluginHadPointsElsewhere()
	{
		Rig rig = rig(true, true, false);
		rig.frames(30);
		assertTrue("the plugin's points were seen where the game's map first looked", rig.census.hasHadPoints());
		assertTrue(rig.sent.isEmpty());
		// The player looks somewhere new. Nothing of the plugin's is there, because the game's map is not.
		rig.surface.focusOn(3600, 3400);
		rig.frames(30);
		assertTrue("there are no points at the new place", rig.points.isEmpty());
		assertEquals(1, rig.sent.size());
		assertEquals(3600, rig.sent.get(0)[0], 1);
		assertEquals(3400, rig.sent.get(0)[1], 1);
	}

	@Test
	public void itAlsoWorksWhenThePointsAreNotShownOnThisMap()
	{
		Rig rig = rig(true, false, false);
		rig.frames(30);
		assertTrue(rig.census.hasHadPoints());
		rig.surface.focusOn(3600, 3400);
		rig.frames(30);
		assertEquals(1, rig.sent.size());
	}

	@Test
	public void nothingIsSentWhenNoPluginHasEverHadPoints()
	{
		Rig rig = rig(true, true, false);
		rig.pluginPresent = false;
		rig.frames(30);
		rig.surface.focusOn(3600, 3400);
		rig.frames(100);
		rig.surface.focusOn(3000, 3000);
		rig.frames(100);
		assertFalse(rig.census.hasHadPoints());
		assertTrue("the game's map is never asked to move", rig.sent.isEmpty());
	}

	@Test
	public void theSettingIsHonoured()
	{
		Rig rig = rig(false, true, false);
		rig.frames(30);
		rig.surface.focusOn(3600, 3400);
		rig.frames(100);
		assertTrue(rig.sent.isEmpty());
		assertFalse(rig.surface.wantsGameMap());
	}

	@Test
	public void itIsRateLimitedWhileThePlayerKeepsMovingAndPausing()
	{
		Rig rig = rig(true, true, false);
		rig.frames(30);
		int frames = 0;
		for (int i = 0; i < 20; i++)
		{
			rig.surface.focusOn(3400 + i * 30, 3300 + (i % 2) * 40);
			rig.frames(15);
			frames += 15;
		}
		assertFalse(rig.sent.isEmpty());
		assertTrue("sent " + rig.sent.size() + " times in " + frames + " frames",
			rig.sent.size() <= frames / NativeMapSync.MIN_SEND_FRAMES + 1);
	}

	@Test
	public void nothingIsSentWhileThisMapIsStillMoving()
	{
		Rig rig = rig(true, true, false);
		rig.frames(30);
		for (int i = 0; i < 60; i++)
		{
			rig.surface.focusOn(3300 + i * 6, 3300);
			rig.frames(1);
		}
		assertTrue(rig.sent.isEmpty());
	}

	@Test
	public void theSyncDoesNothingWhileLineUpDrivesTheMaps()
	{
		Rig rig = rig(true, true, true);
		rig.frames(30);
		assertTrue(rig.surface.isLinedUp());
		rig.surface.focusOn(3600, 3400);
		rig.frames(300);
		assertTrue("the sync sent nothing", rig.sent.isEmpty());
		assertFalse("line up keeps its own way of sending", rig.surfaceSent.isEmpty());
	}

	@Test
	public void itStopsWhenTheMapHides()
	{
		Rig rig = rig(true, true, false);
		rig.frames(30);
		rig.surface.focusOn(3600, 3400);
		rig.mapShowing = false;
		rig.frames(200);
		assertTrue("nothing is sent while the map is not showing", rig.sent.isEmpty());
		rig.mapShowing = true;
		rig.frames(1);
		// The game's map may be somewhere else when the map comes back, so the first position seen is only noted.
		assertTrue(rig.sent.isEmpty());
	}
}
