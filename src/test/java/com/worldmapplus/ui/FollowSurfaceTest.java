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
import com.worldmapplus.bridge.PartnerDouble;
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
import java.util.function.Consumer;
import java.util.function.Function;
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
 * Following a point that moves (the Helper sends where a party member is), and the menu entries and clicks a partner asks for,
 * through the real surface: the menu is opened with a right click, an entry is chosen with a left click, and what is said back is
 * read from the bridge. The Helper itself is a partner double here; the tests with the real one are in its own repository.
 */
public class FollowSurfaceTest
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
	private PartnerDouble partner;
	private MapSurface surface;
	private volatile String hidden = "";

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
		partner = new PartnerDouble(external, "helper");
		com.worldmapplus.pins.RouteStore routes = new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(memory, pins, external, new MapActions(new EventBus(), external),
			new Settings(null), routes, new com.worldmapplus.pins.PanelLauncher());
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

			@Override
			public String hiddenMapSources()
			{
				return hidden;
			}
		}, services);
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
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), x, y);
		surface.viewer().getZoom().jumpTo(4.0);
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

	private void follow(String op, String name, WorldPoint at)
	{
		partner.ask("follow", "op", op, "name", name, "point", at);
	}

	/** Registers what the Helper registers: a hit test for a marker at one tile, and a menu that lists entries for it. */
	private void marker(WorldPoint at, String name, List<String> entries, List<String> chosen)
	{
		partner.register("hit", "marker0", 1, (Function<Map<String, Object>, Object>) frame ->
		{
			double dx = ((Number) frame.get("worldX")).doubleValue() - (at.getX() + 0.5);
			double dy = ((Number) frame.get("worldY")).doubleValue() - (at.getY() + 0.5);
			if (Math.hypot(dx, dy) > 1.2)
			{
				return null;
			}
			Map<String, Object> thing = new HashMap<>();
			thing.put("name", name);
			thing.put("kind", "marker");
			thing.put("id", "m1");
			thing.put("point", at);
			thing.put("hint", "Right-click for more");
			return thing;
		}, "click", (Consumer<Map<String, Object>>) thing -> chosen.add("clicked"));
		partner.register("menu", "markers", 1, (Consumer<Map<String, Object>>) context -> chosen.add((String) context.get("option")),
			"label", "Entries", "when", (Function<Map<String, Object>, Object>) context ->
			{
				Object things = context.get("things");
				return things instanceof List && !((List<?>) things).isEmpty() ? entries : new ArrayList<String>();
			});
	}

	@Test
	public void aFollowFromTheHelperKeepsTheMapOnThePointAsItMovesUntilTheMapIsDragged()
	{
		lookAt(3200.5, 3200.5);
		List<String> chosen = new ArrayList<>();
		marker(new WorldPoint(3200, 3200, 0), "Zezima", Arrays.asList("Follow Zezima"), chosen);
		List<String> menu = menuAt(3200.5, 3200.5);
		assertTrue(menu.toString(), menu.contains("Follow Zezima"));
		follow("start", "Zezima", new WorldPoint(3200, 3200, 0));
		frames(2);
		assertEquals("Zezima", surface.following());

		follow("update", "Zezima", new WorldPoint(3240, 3230, 0));
		frames(60);
		assertEquals(3240.5, surface.viewer().getCamera().getX(), 0.5);
		assertEquals(3230.5, surface.viewer().getCamera().getY(), 0.5);
		assertEquals("Zezima", surface.following());

		drag();
		assertNull("a drag ends following", surface.following());
		boolean told = false;
		for (ExternalOverlays.Reply reply : external.drainReplies(System.currentTimeMillis()))
		{
			told |= "follow_ended".equals(reply.getData().get("topic")) && "Zezima".equals(reply.getData().get("name"));
		}
		assertTrue("the Helper is told, so it stops sending", told);
		// The glide a released drag carries dies out first.
		frames(60);
		double x = surface.viewer().getCamera().getX();
		follow("update", "Zezima", new WorldPoint(3300, 3300, 0));
		frames(30);
		assertEquals("and the map no longer goes after them", x, surface.viewer().getCamera().getX(), 1.0);
	}

	@Test
	public void followingStopsCleanlyWhenTheHelperSaysThePointIsGone()
	{
		lookAt(3200.5, 3200.5);
		follow("start", "Zezima", new WorldPoint(3200, 3200, 0));
		frames(5);
		assertEquals("Zezima", surface.following());
		follow("gone", "Zezima", null);
		frames(2);
		assertNull(surface.following());
		assertFalse(surface.viewer().getCamera().isFollowing());
	}

	@Test
	public void anUpdateForAnotherNameOrWithoutAStartIsIgnored()
	{
		lookAt(3200.5, 3200.5);
		follow("update", "Zezima", new WorldPoint(3300, 3300, 0));
		frames(5);
		assertNull(surface.following());
		follow("start", "Zezima", new WorldPoint(3200, 3200, 0));
		frames(2);
		follow("update", "Somebody", new WorldPoint(3300, 3300, 0));
		frames(30);
		assertEquals(3200.5, surface.viewer().getCamera().getX(), 1.0);
		follow("stop", "Zezima", null);
		frames(2);
		assertNull(surface.following());
	}

	@Test
	public void aHelperMenuEntryListsEntriesForWhatIsUnderThePointerAndTheChoiceIsToldWithTheText()
	{
		lookAt(3200.5, 3200.5);
		List<String> chosen = new ArrayList<>();
		marker(new WorldPoint(3200, 3200, 0), "Spot", Arrays.asList("Route here", "Hide"), chosen);
		List<String> menu = menuAt(3200.5, 3200.5);
		assertTrue(menu.toString(), menu.contains("Route here") && menu.contains("Hide"));
		choose("Hide");
		external.partners().tick(System.currentTimeMillis());
		assertTrue(chosen.toString(), chosen.contains("Hide"));
	}

	@Test
	public void aThingThatIsOnlyReachedByTheMenuShowsItsHintAndAClickIsPassedOn()
	{
		lookAt(3200.5, 3200.5);
		List<String> chosen = new ArrayList<>();
		marker(new WorldPoint(3200, 3200, 0), "Spot", Arrays.asList("Hide"), chosen);
		int[] at = screen(3200.5, 3200.5);
		mouseX = at[0];
		mouseY = at[1];
		frames(2);
		assertEquals("Spot", surface.lastCardTitle());
		assertEquals("Right-click for more", surface.lastCard().getHint());
	}

	@Test
	public void theLayersTheHelperListedGetTickBoxesAndTheSettingHidesThem()
	{
		Map<String, Object> sent = new HashMap<>();
		sent.put("Spots", 1);
		Map<String, Object> plugins = new HashMap<>();
		plugins.put("Golems Don't Die", 12);
		partner.state("layers", "sent", sent, "plugins", plugins);
		lookAt(3200.5, 3200.5);
		boolean spots = false;
		boolean golems = false;
		for (LayerPanel.Row row : surface.layerPanel().getRows())
		{
			spots |= row.getLabel().equals("Spots (1)");
			golems |= row.getLabel().equals("Golems Don't Die (12)");
		}
		assertTrue("the sent layer has a tick box", spots);
		assertTrue("the plugin with points on the game's map has one", golems);
	}
}
