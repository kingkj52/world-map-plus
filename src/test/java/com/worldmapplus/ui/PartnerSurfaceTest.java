package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.bridge.PartnerDouble;
import com.worldmapplus.bridge.Partners;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Following a party member from this map's own menu, and the clicks and menu entries a plugin's marker asks for, through the real
 * surface: the menu is opened with a right click, an entry is chosen with a left click, and what is said back is read from the bridge.
 */
public class PartnerSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final JPanel SOURCE = new JPanel();

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private long now = 5_000_000;
	private final BufferedImage canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
	private ExternalOverlays external;
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

	private PartnerDouble partner;

	@Test
	public void aPartnersPainterDrawsOnTheMapAtTheTileItAsksFor()
	{
		partner.register("painter", "dot", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (g, frame) ->
		{
			AffineTransform t = (AffineTransform) frame.get("transform");
			Point2D at = t.transform(new Point2D.Double(3200.5, 3200.5), null);
			g.setColor(new Color(1, 2, 3));
			g.fillRect((int) at.getX() - 3, (int) at.getY() - 3, 7, 7);
		});
		lookAt(3200.5, 3200.5);
		int[] at = screen(3200.5, 3200.5);
		assertEquals(new Color(1, 2, 3).getRGB(), canvas.getRGB(at[0], at[1]));
		partner.goodbye();
		frames(2);
		assertNotEquals(new Color(1, 2, 3).getRGB(), canvas.getRGB(at[0], at[1]));
	}

	@Test
	public void aPartnersThingHasACardTakesAClickAndGetsTheCardLinesOfOtherContributors()
	{
		List<Object> clicks = new ArrayList<>();
		partner.register("hit", "spot", 1, (Function<Map<String, Object>, Object>) frame ->
			Math.abs((Double) frame.get("worldX") - 3200.5) < 1 && Math.abs((Double) frame.get("worldY") - 3200.5) < 1
				? PartnerDouble.data("name", "Spot", "detail", "A partner spot", "clickable", true, "hint", "Click the spot",
				"point", new WorldPoint(3200, 3200, 0), "id", "s1") : null,
			"click", (Consumer<Map<String, Object>>) clicks::add);
		partner.register("card", "extra", 1, (Function<Map<String, Object>, Object>) thing ->
			Collections.singletonList("Seen: " + thing.get("name")));
		lookAt(3200.5, 3200.5);
		int[] at = screen(3200.5, 3200.5);
		mouseX = at[0];
		mouseY = at[1];
		frames(3);
		assertEquals("Spot", surface.lastCard().getTitle());
		assertEquals("Click the spot", surface.lastCard().getHint());
		assertEquals("Seen: Spot", surface.lastCard().visibleRows(true).get(0).getLabel());
		click(at[0], at[1], MouseEvent.BUTTON1);
		assertEquals(1, clicks.size());
		assertEquals("s1", ((Map<?, ?>) clicks.get(0)).get("id"));
		assertTrue(((Map<?, ?>) clicks.get(0)).get("tile") instanceof WorldPoint);
	}

	@Test
	public void aPartnersMenuEntryIsOfferedAtTheRightClickedPlaceAndToldWhichTile()
	{
		List<Map<String, Object>> chosen = new ArrayList<>();
		partner.register("menu", "mark", 1, (Consumer<Map<String, Object>>) chosen::add, "label", "Mark",
			"when", (Function<Map<String, Object>, Object>) context -> java.util.Arrays.asList("Mark here (helper)"));
		lookAt(3200.5, 3200.5);
		List<String> menu = menuAt(3200.5, 3200.5);
		assertTrue(menu.toString(), menu.contains("Mark here (helper)"));
		choose("Mark here (helper)");
		assertEquals(1, chosen.size());
		assertEquals(new WorldPoint(3200, 3200, 0), chosen.get(0).get("tile"));
		assertEquals(0, chosen.get(0).get("mapId"));
	}

	@Test
	public void withNoPartnerNothingExtraIsOnTheMenuOrTheMap()
	{
		lookAt(3200.5, 3200.5);
		for (String label : menuAt(3200.5, 3200.5))
		{
			assertFalse(label, label.contains("helper") || label.contains("Mark"));
		}
		assertFalse(external.partners().any(Partners.Kind.PAINTER));
	}

	@Test
	public void aPainterIsToldWhichPlacesBelongOnTheMapBeingDrawn()
	{
		java.util.List<String> answers = new java.util.ArrayList<>();
		partner.register("painter", "onmap", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (g, frame) ->
		{
			@SuppressWarnings("unchecked")
			java.util.function.BiPredicate<Double, Double> onMap = (java.util.function.BiPredicate<Double, Double>) frame.get("onMap");
			answers.clear();
			answers.add("lumbridge " + onMap.test(3222.5, 3218.5));
			answers.add("underground " + onMap.test(3222.5, 9618.5));
			answers.add("far " + onMap.test(100.5, 100.5));
		});
		frames(20);
		assertTrue(answers.toString(), answers.contains("lumbridge true"));
		assertTrue("a place on another map's imagery is not on this map", answers.contains("underground false"));
	}
}

