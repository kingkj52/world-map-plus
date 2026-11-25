package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.layers.GameIconLayer;
import com.worldmapplus.layers.GameIconScale;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * The game's icons drawn by the plugin, through a whole surface: the card and the click answer where the picture is, another
 * plugin's marker never takes the mouse from an icon, floors keep their own icons, and with the switch off none of it happens.
 */
public class GameIconSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final JPanel SOURCE = new JPanel();
	/** The Kalphite Cave entrance on the surface: the game links it to the Kharidian Desert Underground. */
	private static final double CAVE_X = 3319.5;
	private static final double CAVE_Y = 3122.5;

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private ExternalOverlays external;
	private MapSurface surface;
	private BufferedImage canvas;
	private long now = 2_000_000;

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
		canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
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
		Settings settings = new Settings(null);
		com.worldmapplus.pins.PanelLauncher launcher = new com.worldmapplus.pins.PanelLauncher();
		com.worldmapplus.pins.RouteStore routes = new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(memory, pins, external, new MapActions(new EventBus(), external),
			settings, routes, launcher);
		surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return "";
			}
		}, services);
	}

	private static PlayerPosition lumbridge()
	{
		return new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
	}

	private static MapSurface.Options options()
	{
		return new MapSurface.Options().followOnOpen(true).openZoom(4.0);
	}

	private void frame()
	{
		Graphics2D g = canvas.createGraphics();
		now += 16;
		surface.render(g, BOUNDS, lumbridge(), options(), now);
		g.dispose();
	}

	private void frames(int count)
	{
		for (int i = 0; i < count; i++)
		{
			frame();
			try
			{
				Thread.sleep(2);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	private void lookAt(double x, double y, double zoom)
	{
		frames(10);
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), x, y);
		surface.viewer().getZoom().jumpTo(zoom);
		frames(5);
	}

	private void click(int x, int y)
	{
		MouseEvent press = new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, MouseEvent.BUTTON1);
		surface.getInput().mousePressed(press);
		now += 16;
		surface.getInput().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
		frame();
	}

	private void hover(int x, int y)
	{
		mouseX = x;
		mouseY = y;
		frame();
	}

	/** A marker of another plugin, such as a group member's icon, one tile east of the cave entrance. */
	private void memberBesideTheCave()
	{
		TestMarker.show(external, new WorldPoint(3320, 3122, 0), "Member", new java.awt.Color(0, 255, 0));
		frames(2);
	}

	@Test
	public void anIconIsDrawnWhereTheDataPutsItAndItsCardAnswersThere()
	{
		lookAt(CAVE_X, CAVE_Y, 8.0);
		ViewTransform view = surface.currentView();
		GameIconLayer layer = assets.getGameIconLayer();
		layer.layout(view, 0, 0);
		int slot = layer.hit(view, 0, 0, view.screenX(CAVE_X), view.screenY(CAVE_Y));
		assertTrue("the cave entrance is drawn", slot >= 0);
		assertEquals(GameIconScale.MAX_SIDE, layer.drawnSize());
		hover((int) Math.round(view.screenX(CAVE_X)), (int) Math.round(view.screenY(CAVE_Y)));
		assertEquals("Kalphite Cave", surface.lastCardTitle());
		assertTrue("the card previews the map it leads to", surface.lastCard().hasPreview());
	}

	@Test
	public void clickingTheKalphiteCaveEntranceOpensTheMapBelowLikeTheLair()
	{
		lookAt(CAVE_X, CAVE_Y, 8.0);
		ViewTransform view = surface.currentView();
		click((int) Math.round(view.screenX(CAVE_X)), (int) Math.round(view.screenY(CAVE_Y)));
		assertEquals(42, surface.viewer().getMap().getId());

		// And the lair, which had its link before.
		surface.viewer().showMap(assets.getCatalog().surface(), 3226.5, 3108.5);
		surface.viewer().getZoom().jumpTo(8.0);
		frames(5);
		view = surface.currentView();
		click((int) Math.round(view.screenX(3226.5)), (int) Math.round(view.screenY(3108.5)));
		assertEquals(42, surface.viewer().getMap().getId());
	}

	@Test
	public void aMarkerBesideAnIconNeverTakesTheMouseFromTheUncoveredPart()
	{
		lookAt(CAVE_X, CAVE_Y, 8.0);
		memberBesideTheCave();
		ViewTransform view = surface.currentView();
		int cx = (int) Math.round(view.screenX(CAVE_X));
		int cy = (int) Math.round(view.screenY(CAVE_Y));
		// The marker is 8 pixels east of the icon's middle and answers within 12 pixels of its own. The west half of the icon is
		// more than 12 pixels from the marker.
		hover(cx - 7, cy);
		assertEquals("Kalphite Cave", surface.lastCardTitle());
		hover(cx - 5, cy + 4);
		assertEquals("Kalphite Cave", surface.lastCardTitle());
		// Within the marker's own 12 pixels, but the icon's middle is nearer: the icon.
		hover(cx - 2, cy);
		assertEquals("Kalphite Cave", surface.lastCardTitle());
		click(cx - 7, cy);
		assertEquals("the click opens the map below", 42, surface.viewer().getMap().getId());
	}

	@Test
	public void whereTheyOverlapTheNearestMiddleWinsAndTheCardNamesTheOther()
	{
		lookAt(CAVE_X, CAVE_Y, 8.0);
		memberBesideTheCave();
		ViewTransform view = surface.currentView();
		int cx = (int) Math.round(view.screenX(CAVE_X));
		int cy = (int) Math.round(view.screenY(CAVE_Y));

		hover(cx + 2, cy);
		assertEquals("the icon's middle is nearer", "Kalphite Cave", surface.lastCardTitle());
		assertTrue(says(surface.lastCard(), "Member"));

		hover(cx + 6, cy);
		assertEquals("the marker's middle is nearer", "Member", surface.lastCardTitle());
		assertTrue("and the card says the cave is there too", says(surface.lastCard(), "Kalphite Cave"));
	}

	@Test
	public void aClickWhereAMarkerIsNearestStillReachesTheIconUnderIt()
	{
		lookAt(CAVE_X, CAVE_Y, 8.0);
		memberBesideTheCave();
		ViewTransform view = surface.currentView();
		int cx = (int) Math.round(view.screenX(CAVE_X));
		int cy = (int) Math.round(view.screenY(CAVE_Y));
		// Nearest to the marker, which does nothing when clicked: the click goes to the icon.
		click(cx + 6, cy);
		assertEquals(42, surface.viewer().getMap().getId());
	}

	private static boolean says(HoverCard card, String text)
	{
		if (card == null)
		{
			return false;
		}
		for (HoverCard.Row row : card.getRows())
		{
			if (row.getLabel().equals("Also here") && row.getValue().contains(text))
			{
				return true;
			}
		}
		return false;
	}

	/** The index of the icon of a floor nearest a place, in the surface's icons, or -1. */
	private static int iconOnFloor(double x, double y, int floor)
	{
		com.worldmapplus.layers.GameIconSet.MapIcons icons = assets.getGameIcons().on(0);
		int best = -1;
		double bestDistance = 0.6;
		for (int i = 0; i < icons.size(); i++)
		{
			double d = Math.hypot(icons.x(i) - x, icons.y(i) - y);
			if (icons.floor(i) == floor && d < bestDistance)
			{
				best = i;
				bestDistance = d;
			}
		}
		return best;
	}

	@Test
	public void theCardOfAnUpperFloorIconSaysWhichFloorItIsOnAndAGroundFloorIconSaysNothing()
	{
		// The quest icon on the first floor of Lumbridge Castle, seen from the ground floor.
		int upper = iconOnFloor(3210.5, 3220.5, 1);
		assertTrue(upper >= 0);
		com.worldmapplus.layers.GameIconSet.MapIcons icons = assets.getGameIcons().on(0);
		assertTrue("the ground floor shows it", icons.shownOn(upper, 0));
		lookAt(icons.x(upper), icons.y(upper), 8.0);
		ViewTransform view = surface.currentView();
		hover((int) Math.round(view.screenX(icons.x(upper))), (int) Math.round(view.screenY(icons.y(upper))));
		assertNotNull(surface.lastCard());
		assertEquals("1st floor", rowValue(surface.lastCard(), "Floor"));

		// An icon on the ground floor has no floor row.
		int ground = iconOnFloor(CAVE_X, CAVE_Y, 0);
		assertTrue(ground >= 0);
		lookAt(CAVE_X, CAVE_Y, 8.0);
		view = surface.currentView();
		hover((int) Math.round(view.screenX(CAVE_X)), (int) Math.round(view.screenY(CAVE_Y)));
		assertEquals("Kalphite Cave", surface.lastCardTitle());
		assertEquals(null, rowValue(surface.lastCard(), "Floor"));
	}

	@Test
	public void theLinkOfAnUpperFloorIconWorksFromTheGroundFloorToo()
	{
		// A way down on the first floor of a building on the surface, with a link to the map below it.
		com.worldmapplus.layers.GameIconSet.MapIcons icons = assets.getGameIcons().on(0);
		int linked = -1;
		for (int i = 0; i < icons.size() && linked < 0; i++)
		{
			if (icons.floor(i) == 1 && icons.shownOn(i, 0) && icons.card(i) != null && icons.card(i).getTarget() != null)
			{
				linked = i;
			}
		}
		assertTrue("an upper floor icon with a link exists", linked >= 0);
		double x = icons.x(linked);
		double y = icons.y(linked);
		lookAt(x, y, 8.0);
		ViewTransform view = surface.currentView();
		hover((int) Math.round(view.screenX(x)), (int) Math.round(view.screenY(y)));
		assertTrue("the card previews where it leads", surface.lastCard().hasPreview());
		assertEquals("1st floor", rowValue(surface.lastCard(), "Floor"));
		click((int) Math.round(view.screenX(x)), (int) Math.round(view.screenY(y)));
		assertTrue("the click opens the other map", surface.viewer().getMap().getId() != assets.getCatalog().surface().getId());
	}

	private static String rowValue(HoverCard card, String label)
	{
		if (card == null)
		{
			return null;
		}
		for (HoverCard.Row row : card.visibleRows(true))
		{
			if (row.getLabel().equals(label))
			{
				return row.getValue();
			}
		}
		return null;
	}

	@Test
	public void anIconOfAFloorShowsOnThatFloorAndOnTheGroundFloor()
	{
		GameIconLayer layer = assets.getGameIconLayer();
		com.worldmapplus.layers.GameIconSet.MapIcons surfaceIcons = assets.getGameIcons().on(0);
		int upper = -1;
		for (int i = 0; i < surfaceIcons.size() && upper < 0; i++)
		{
			// One that is not a repeat of a ground floor icon, which the ground floor leaves out.
			if (surfaceIcons.floor(i) == 1 && surfaceIcons.shownOn(i, 0))
			{
				upper = i;
			}
		}
		assertTrue(upper >= 0);
		ViewTransform view = new ViewTransform(surfaceIcons.x(upper), surfaceIcons.y(upper), 8.0, 0, 0, 400, 300);
		for (int floor : new int[] {0, 1})
		{
			layer.layout(view, 0, floor);
			boolean shown = false;
			for (int slot = 0; slot < layer.drawnCount(); slot++)
			{
				shown |= layer.drawnIconIndex(slot) == upper;
			}
			assertTrue("on floor " + floor, shown);
		}
		layer.layout(view, 0, 2);
		for (int slot = 0; slot < layer.drawnCount(); slot++)
		{
			assertFalse("not on the second floor", layer.drawnIconIndex(slot) == upper);
		}
	}
}
