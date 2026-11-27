package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.bridge.NativePoints;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.party.PartyMember;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Following a party member from this map's own menu, and the clicks and menu entries a plugin's marker asks for, through the real
 * surface: the menu is opened with a right click, an entry is chosen with a left click, and what is said back is read from the bridge.
 */
public class FollowAndMarkerMenuTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final JPanel SOURCE = new JPanel();

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private long now = 5_000_000;
	private final BufferedImage canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
	private final List<WorldMapPoint> points = new CopyOnWriteArrayList<>();
	private ExternalOverlays external;
	private MapSurface surface;

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

	@Before
	public void setUp()
	{
		points.clear();
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
		external = new ExternalOverlays();
		NativePoints nativePoints = new NativePoints(sink -> points.forEach(sink));
		com.worldmapplus.pins.RouteStore routes = new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(memory, pins, external, new MapActions(new EventBus(), external),
			new Settings(null), nativePoints, routes, new com.worldmapplus.pins.PanelLauncher());
		surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return "";
			}

			@Override
			public boolean showHotspots()
			{
				return false;
			}

		}, services);
	}

	/** The real point RuneLite's Party plugin makes for a member (test code opens its package private constructors). */
	private static WorldMapPoint partyPoint(String name, WorldPoint at)
	{
		try
		{
			Constructor<PartyMember> made = PartyMember.class.getDeclaredConstructor(long.class);
			made.setAccessible(true);
			PartyMember member = made.newInstance(9L);
			set(member, "displayName", name);
			set(member, "coord", at);
			set(member, "loggedIn", true);
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

	private void frames(int count)
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

	private void lookAt(double x, double y)
	{
		frames(5);
		surface.viewer().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), x, y);
		surface.viewer().jumpZoom(4.0);
		frames(5);
	}

	private void click(int x, int y, int button)
	{
		surface.getInput().mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, button));
		now += 16;
		surface.getInput().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, button));
		frames(2);
	}

	private int[] screen(double wx, double wy)
	{
		ViewTransform view = surface.currentView();
		return new int[] {(int) Math.round(view.screenX(wx)), (int) Math.round(view.screenY(wy))};
	}

	private List<String> menuAt(double wx, double wy)
	{
		int[] at = screen(wx, wy);
		mouseX = at[0];
		mouseY = at[1];
		frames(1);
		click(at[0], at[1], MouseEvent.BUTTON3);
		List<String> labels = new ArrayList<>();
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			labels.add(item.getLabel());
		}
		return labels;
	}

	private void choose(String label)
	{
		ContextMenu.Item item = null;
		for (ContextMenu.Item candidate : surface.menu().getItems())
		{
			if (candidate.getLabel().equals(label))
			{
				item = candidate;
			}
		}
		assertNotNull("no entry " + label, item);
		Rectangle menu = surface.menu().getBounds();
		for (int py = menu.y; py < menu.y + menu.height; py++)
		{
			if (surface.menu().hit(menu.x + 10, py) == item)
			{
				click(menu.x + 10, py, MouseEvent.BUTTON1);
				return;
			}
		}
		throw new AssertionError("entry not on screen: " + label);
	}

	private void drag()
	{
		int[] from = {BOUNDS.x + 300, BOUNDS.y + 300};
		surface.getInput().mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, 0, from[0], from[1], 1, false,
			MouseEvent.BUTTON1));
		for (int i = 1; i <= 5; i++)
		{
			now += 16;
			surface.getInput().mouseDragged(new MouseEvent(SOURCE, MouseEvent.MOUSE_DRAGGED, now, 0, from[0] + i * 20, from[1], 1,
				false, MouseEvent.BUTTON1));
			frames(1);
		}
		surface.getInput().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now, 0, from[0] + 100, from[1], 1, false,
			MouseEvent.BUTTON1));
		frames(2);
	}

	@Test
	public void followingAPartyMemberKeepsTheMapOnThemAsTheyWalkUntilTheMapIsDragged()
	{
		WorldPoint start = new WorldPoint(3200, 3200, 0);
		points.add(partyPoint("Zezima", start));
		lookAt(3200.5, 3200.5);
		List<String> menu = menuAt(3200.5, 3200.5);
		assertTrue(menu.toString(), menu.contains("Follow Zezima"));
		choose("Follow Zezima");
		assertEquals("Zezima", surface.following());

		points.clear();
		points.add(partyPoint("Zezima", new WorldPoint(3240, 3230, 0)));
		frames(60);
		assertEquals(3240.5, surface.viewer().getX(), 0.5);
		assertEquals(3230.5, surface.viewer().getY(), 0.5);
		assertEquals("Zezima", surface.following());

		assertTrue(menuAt(3240.5, 3230.5).contains("Stop following Zezima"));
		surface.menu().close();
		frames(1);
		drag();
		assertNull("a drag ends following", surface.following());
		// The glide a released drag carries dies out first.
		frames(60);
		double x = surface.viewer().getX();
		points.clear();
		points.add(partyPoint("Zezima", new WorldPoint(3300, 3300, 0)));
		frames(30);
		assertEquals("and the map no longer goes after them", x, surface.viewer().getX(), 1.0);
	}

	@Test
	public void followingStopsCleanlyWhenTheMemberLeaves()
	{
		points.add(partyPoint("Zezima", new WorldPoint(3200, 3200, 0)));
		lookAt(3200.5, 3200.5);
		menuAt(3200.5, 3200.5);
		choose("Follow Zezima");
		points.clear();
		frames(20);
		assertEquals("a short gap is waited out", "Zezima", surface.following());
		frames((int) (MapSurface.FOLLOW_GRACE_MILLIS / 16) + 5);
		assertNull(surface.following());
		assertFalse(surface.viewer().isFollowing());
	}

	@Test
	public void aPlainMarkerOrAnotherPluginsPointOffersNoFollow()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("id", "m");
		data.put("source", "p");
		data.put("point", new WorldPoint(3200, 3200, 0));
		data.put("name", "Zezima");
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "marker", data));
		lookAt(3200.5, 3200.5);
		for (String label : menuAt(3200.5, 3200.5))
		{
			assertFalse(label, label.startsWith("Follow"));
		}
	}

	@Test
	public void aClickOnAClickableMarkerAndItsMenuEntriesAreSaidBackToItsPlugin()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("id", "spot-1");
		data.put("source", "myplugin");
		data.put("point", new WorldPoint(3200, 3200, 0));
		data.put("name", "Spot");
		data.put("clickable", true);
		data.put("menu", Arrays.asList("Route here", "Hide"));
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "marker", data));
		lookAt(3200.5, 3200.5);
		int[] at = screen(3200.5, 3200.5);
		mouseX = at[0];
		mouseY = at[1];
		frames(2);
		assertEquals("Click to choose", surface.lastCard().getHint());
		double before = surface.viewer().getX();
		click(at[0], at[1], MouseEvent.BUTTON1);
		List<ExternalOverlays.Reply> replies = external.drainReplies(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("marker_clicked", replies.get(0).getName());
		assertEquals("spot-1", replies.get(0).getData().get("id"));
		assertEquals("the map stays put", before, surface.viewer().getX(), 0.01);

		List<String> menu = menuAt(3200.5, 3200.5);
		assertTrue(menu.toString(), menu.contains("Route here") && menu.contains("Hide"));
		choose("Hide");
		replies = external.drainReplies(System.currentTimeMillis());
		assertEquals("marker_menu", replies.get(0).getName());
		assertEquals("Hide", replies.get(0).getData().get("option"));
		assertEquals("myplugin", replies.get(0).getData().get("source"));
	}
}
