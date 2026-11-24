package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalMarker;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.bridge.NativePoints;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.party.PartyMember;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * RuneLite's own Party plugin puts each member on the game's world map as a {@code PartyWorldMapPoint} (read in the client jar). This
 * map draws the points other plugins put there, so a party member is drawn without anything party specific: this shows it with the real
 * class, a real party member, and the real manager call, and that its card names the member.
 */
public class PartyOnTheMapTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final WorldPoint AT = new WorldPoint(3222, 3218, 0);

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private long now = 3_000_000;
	private final BufferedImage canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);

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

	/**
	 * A real party member and the real point the Party plugin makes for them. Both classes keep their constructors and fields to their
	 * own package (and the signed client jar does not allow a helper in that package), so the test opens them with reflection. Test code
	 * only: the plugin itself uses none.
	 */
	private static WorldMapPoint partyPoint(String name, WorldPoint at, BufferedImage avatar)
	{
		try
		{
			Constructor<PartyMember> made = PartyMember.class.getDeclaredConstructor(long.class);
			made.setAccessible(true);
			PartyMember member = made.newInstance(7L);
			set(member, "displayName", name);
			set(member, "coord", at);
			set(member, "loggedIn", true);
			set(member, "avatar", avatar);
			Class<?> type = Class.forName("net.runelite.client.plugins.party.PartyWorldMapPoint");
			Constructor<?> point = type.getDeclaredConstructor(WorldPoint.class, PartyMember.class);
			point.setAccessible(true);
			return (WorldMapPoint) point.newInstance(at, member);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private static void set(Object target, String field, Object value) throws ReflectiveOperationException
	{
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private MapSurface surfaceShowing(List<WorldMapPoint> points, boolean showOtherPluginPoints)
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(mouseX, mouseY);
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? (Object) false : type.isPrimitive() ? (Object) 0 : null;
			});
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
		// The same call the plugin makes on the manager: each point is shown to a predicate that answers no.
		NativePoints nativePoints = new NativePoints(sink -> points.forEach(sink));
		SurfaceServices services = new SurfaceServices(quiet, memory, pins, external, new MapActions(new EventBus(), external),
			new Settings(null), new TrailTracker(), null, nativePoints, new Gson());
		return new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public boolean showOtherPluginPoints()
			{
				return showOtherPluginPoints;
			}

			@Override
			public String poiLayers()
			{
				return "";
			}
		}, services);
	}

	private void frames(MapSurface surface, int count)
	{
		Graphics2D g = canvas.createGraphics();
		for (int i = 0; i < count; i++)
		{
			now += 16;
			surface.render(g, BOUNDS, new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0)),
				new MapSurface.Options().followOnOpen(true).openZoom(8.0), now);
		}
		g.dispose();
	}

	@Test
	public void theRealPartyPointIsReadAsAMarkerNamedForTheMember()
	{
		WorldMapPoint point = partyPoint("Zezima", AT, new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB));
		assertEquals("net.runelite.client.plugins.party.PartyWorldMapPoint", point.getClass().getName());
		assertEquals("the party plugin names the point for the member", "Zezima", point.getName());
		List<ExternalMarker> markers = new NativePoints(sink -> sink.accept(point)).markers(1000);
		assertEquals("the point is not one of the World Map plugin's own, so it is not left out", 1, markers.size());
		assertEquals("Zezima", markers.get(0).getName());
		assertEquals(3222.5, markers.get(0).getX(), 0.01);
		assertEquals(3218.5, markers.get(0).getY(), 0.01);
		assertTrue("the point snaps to the edge of the game's map, as the party plugin asked", markers.get(0).isSnapToEdge());
	}

	@Test
	public void aPartyMemberIsDrawnAndHoveringThemShowsACardWithTheirName()
	{
		MapSurface surface = surfaceShowing(Collections.singletonList(partyPoint("Zezima", AT,
			new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB))), true);
		frames(surface, 12);
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(3222.5));
		mouseY = (int) Math.round(view.screenY(3218.5));
		frames(surface, 2);
		assertEquals("Zezima", surface.lastCardTitle());
		assertNotNull(surface.lastCard());
	}

	@Test
	public void aMemberWithoutAPictureYetIsStillShown()
	{
		// The party service has no avatar for a member until it has been downloaded.
		MapSurface surface = surfaceShowing(Collections.singletonList(partyPoint("Newcomer", AT, null)), true);
		frames(surface, 12);
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(3222.5));
		mouseY = (int) Math.round(view.screenY(3218.5));
		frames(surface, 2);
		assertEquals("Newcomer", surface.lastCardTitle());
	}

	@Test
	public void withTheSettingOffNoMemberIsShown()
	{
		MapSurface surface = surfaceShowing(Collections.singletonList(partyPoint("Zezima", AT,
			new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB))), false);
		frames(surface, 12);
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(3222.5));
		mouseY = (int) Math.round(view.screenY(3218.5));
		frames(surface, 2);
		assertFalse("Zezima".equals(surface.lastCardTitle()));
	}
}
