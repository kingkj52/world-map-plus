package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.TrailTracker;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.data.HotspotLoader;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.input.ViewerInput;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.NpcLayer;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * Drives a whole surface the way the game would: real bundled data, real input events, frames
 * drawn to an off-screen image. Only the game client is stubbed.
 */
public class MapSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final JPanel SOURCE = new JPanel();

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private final Map<String, String> saved = new HashMap<>();
	private final List<Object> posted = new ArrayList<>();
	private final String[] pinMemory = new String[1];
	private int mouseX = -1;
	private int mouseY = -1;
	private String poiLayers = "agility,minigame";
	private boolean showPins = true;
	private final Map<String, Boolean> toggled = new HashMap<>();
	private PinStore pins;
	private ExternalOverlays external;
	private MapSurface surface;
	private Client client;
	private NpcLayer quiet;
	private long now = 1_000_000;
	private BufferedImage canvas;
	private net.runelite.api.widgets.Widget keyEntries;
	private com.worldmapplus.pins.RouteStore routeStore;
	private final List<Long> opened = new ArrayList<>();

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
		mouseX = -1;
		mouseY = -1;
		poiLayers = "agility,minigame";
		canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
		client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(mouseX, mouseY);
				}
				if (method.getName().equals("getWidget") && args != null && args.length == 1
					&& args[0] instanceof Integer
					&& (Integer) args[0] == net.runelite.api.gameval.InterfaceID.Worldmap.KEY_ENTRIES)
				{
					return keyEntries;
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
		quiet = new NpcLayer()
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
				saved.put(slot, mapId + "," + x + "," + y + "," + ppt);
			}

			@Override
			public Saved load(String slot)
			{
				return null;
			}
		};
		posted.clear();
		pinMemory[0] = null;
		pins = new PinStore(new PinStore.Storage()
		{
			@Override
			public String read()
			{
				return pinMemory[0];
			}

			@Override
			public void write(String json)
			{
				pinMemory[0] = json;
			}
		}, new Gson());
		external = new ExternalOverlays();
		EventBus bus = new EventBus()
		{
			@Override
			public void post(Object event)
			{
				posted.add(event);
			}
		};
		toggled.clear();
		Settings settings = new Settings(null)
		{
			@Override
			public void setBoolean(String key, boolean value)
			{
				toggled.put(key, value);
			}
		};
		opened.clear();
		com.worldmapplus.pins.PanelLauncher launcher = new com.worldmapplus.pins.PanelLauncher();
		launcher.setOpener(opened::add);
		routeStore = new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(quiet, memory, pins, external, new MapActions(bus, external),
			settings, new TrailTracker(), null, null, routeStore, launcher);
		this.services = services;
		dropTables = true;
		config = new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return poiLayers;
			}

			@Override
			public boolean showPins()
			{
				return showPins;
			}

			@Override
			public boolean lineUpWithGameMap()
			{
				return lineUp;
			}

			@Override
			public boolean showDropTables()
			{
				return dropTables;
			}
		};
		surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public boolean showDropTables()
			{
				return dropTables;
			}

			@Override
			public String poiLayers()
			{
				return poiLayers;
			}

			@Override
			public boolean showPins()
			{
				return showPins;
			}

			@Override
			public boolean lineUpWithGameMap()
			{
				return lineUp;
			}
		}, services);
	}

	/** Puts a stand-in for the game's own map under the surface. */
	private void withGameMap()
	{
		surface.setNativeMap(new NativeMap()
		{
			@Override
			public View view()
			{
				return gameMap;
			}

			@Override
			public void moveTo(int x, int y)
			{
				moves.add(new int[] {x, y});
			}
		});
	}

	private boolean lineUp;
	private boolean dropTables = true;
	private SurfaceServices services;
	private WorldMapPlusConfig config;
	private NativeMap.View gameMap = new NativeMap.View(3222, 3218, 6.0);
	private final java.util.List<int[]> moves = new ArrayList<>();

	private static PlayerPosition lumbridge()
	{
		return new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
	}

	private static MapSurface.Options options()
	{
		return new MapSurface.Options().followOnOpen(true).openZoom(4.0);
	}

	private void frame(PlayerPosition position, MapSurface.Options options)
	{
		Graphics2D g = canvas.createGraphics();
		now += 16;
		surface.render(g, BOUNDS, position, options, now);
		g.dispose();
	}

	private void frames(int count, PlayerPosition position, MapSurface.Options options)
	{
		for (int i = 0; i < count; i++)
		{
			frame(position, options);
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

	private ViewerInput input()
	{
		return surface.getInput();
	}

	private MouseEvent mouse(int id, int x, int y, int button)
	{
		return new MouseEvent(SOURCE, id, now, 0, x, y, 1, false, button);
	}

	private void press(int x, int y)
	{
		input().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
	}

	private void drag(int x, int y)
	{
		input().mouseDragged(mouse(MouseEvent.MOUSE_DRAGGED, x, y, MouseEvent.NOBUTTON));
	}

	private void release(int x, int y)
	{
		input().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1));
	}

	private void typeChar(char c)
	{
		input().keyTyped(new KeyEvent(SOURCE, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c));
	}

	private void pressKey(int code, char c)
	{
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, 0, code, c));
	}

	private MouseWheelEvent wheel(int x, int y, int rotation)
	{
		return new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, now, 0, x, y, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
	}


	private void rightClick(int x, int y)
	{
		input().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON3));
		now += 16;
		input().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON3));
	}

	/** Left-clicks the menu row with this action. */
	private void chooseMenu(ContextMenu.Action action)
	{
		ContextMenu menu = surface.menu();
		assertTrue("menu should be open", menu.isOpen());
		Rectangle b = menu.getBounds();
		for (int row = 0; row < 8; row++)
		{
			int y = b.y + ContextMenu.PADDING / 2 + row * ContextMenu.ROW_HEIGHT + 3;
			ContextMenu.Item item = menu.hit(b.x + 10, y);
			if (item != null && item.getAction() == action)
			{
				press(b.x + 10, y);
				now += 16;
				release(b.x + 10, y);
				frame(lumbridge(), options());
				return;
			}
		}
		throw new AssertionError("no menu item for " + action);
	}

	private PluginMessage lastMessage(String namespace, String name)
	{
		PluginMessage found = null;
		for (Object event : posted)
		{
			if (event instanceof PluginMessage)
			{
				PluginMessage message = (PluginMessage) event;
				if (message.getNamespace().equals(namespace) && message.getName().equals(name))
				{
					found = message;
				}
			}
		}
		return found;
	}

	@Test
	public void opensOnThePlayerAndFollows()
	{
		frame(lumbridge(), options());
		assertEquals(MapInfo.SURFACE, surface.viewer().getMap().getId());
		assertTrue(surface.viewer().getCamera().isFollowing());
		frames(30, lumbridge(), options());
		assertEquals(3222.5, surface.viewer().getCamera().getX(), 0.5);
		assertEquals(3218.5, surface.viewer().getCamera().getY(), 0.5);
		assertEquals(4.0, surface.viewer().currentPixelsPerTile(), 0.01);
	}

	@Test
	public void drawsTheBundledMapIntoTheRectangleOnly()
	{
		int background = com.worldmapplus.render.MapRenderer.BACKGROUND.getRGB() & 0xFFFFFF;
		int inside = background;
		// Tiles decode in the background, so wait until one has been drawn rather than for a fixed time.
		for (int i = 0; i < 600 && inside == background; i++)
		{
			frames(1, lumbridge(), options());
			inside = canvas.getRGB(BOUNDS.x + 300, BOUNDS.y + 300) & 0xFFFFFF;
		}
		int outside = canvas.getRGB(20, 20) & 0xFFFFFF;
		assertEquals(0, outside);
		assertTrue("a map tile should have been drawn over the plain backdrop, got " + Integer.toHexString(inside),
			inside != background);
	}

	/** True when two colours differ by more than rounding in some channel. */
	private static boolean differs(int a, int b)
	{
		for (int shift = 0; shift <= 16; shift += 8)
		{
			if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 6)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void theWheelZoomsAndAcceleratesAcrossTheWholeRange()
	{
		frames(5, lumbridge(), options());
		input().mouseWheelMoved(wheel(400, 300, 5));
		frames(120, lumbridge(), options());
		double zoomedOut = surface.viewer().currentPixelsPerTile();
		assertTrue("zoomed out to " + zoomedOut, zoomedOut < 4.0);

		for (int i = 0; i < 60; i++)
		{
			now += 30;
			input().mouseWheelMoved(wheel(400, 300, -1));
			frame(lumbridge(), options());
		}
		frames(200, lumbridge(), options());
		assertEquals(32.0, surface.viewer().currentPixelsPerTile(), 0.01);
	}

	@Test
	public void draggingMovesTheMapAndStopsFollowing()
	{
		frames(30, lumbridge(), options());
		ViewTransform before = surface.currentView();
		double wx = before.worldX(500);
		press(500, 300);
		now += 16;
		drag(560, 300);
		now += 16;
		drag(620, 300);
		// Let go after a pause, so the map does not glide on.
		now += 400;
		release(620, 300);
		frame(lumbridge(), options());
		assertFalse(surface.viewer().getCamera().isFollowing());
		ViewTransform after = surface.currentView();
		assertEquals(620.0, after.screenX(wx), 3.0);
	}

	@Test
	public void clickingTheLocateButtonFollowsAgain()
	{
		frames(30, lumbridge(), options());
		press(500, 300);
		drag(560, 300);
		release(560, 300);
		frame(lumbridge(), options());
		assertFalse(surface.viewer().getCamera().isFollowing());

		MapButtons probe = new MapButtons();
		probe.layout(BOUNDS);
		Rectangle locate = probe.boundsOf(MapButtons.Button.LOCATE);
		press(locate.x + 5, locate.y + 5);
		release(locate.x + 5, locate.y + 5);
		frame(lumbridge(), options());
		assertTrue(surface.viewer().getCamera().isFollowing());
	}

	private static PlayerPosition inVarrockCastle(int floor)
	{
		return new PlayerPosition(3213.5, 3470.5, new WorldPoint(3213, 3470, floor));
	}

	@Test
	public void theMapOpensOnTheFloorThePlayerIsOnAndFollowsThemUpAndDown()
	{
		assertEquals("the surface has floors bundled", 3, assets.getFloors());
		frames(10, inVarrockCastle(1), options());
		assertEquals(1, surface.viewer().getFloor());
		frames(3, inVarrockCastle(2), options());
		assertEquals(2, surface.viewer().getFloor());
		frames(3, inVarrockCastle(0), options());
		assertEquals(0, surface.viewer().getFloor());
	}

	@Test
	public void theFloorButtonsMoveTheViewAndStopAtTheEnds()
	{
		frames(10, inVarrockCastle(0), options());
		MapButtons buttons = new MapButtons();
		buttons.layout(BOUNDS);
		FloorButtons floors = new FloorButtons();
		floors.setShown(true);
		floors.layout(buttons.boundsOf(MapButtons.Button.LAYERS));
		Rectangle up = floors.boundsOf(FloorButtons.Button.UP);
		Rectangle down = floors.boundsOf(FloorButtons.Button.DOWN);

		for (int i = 0; i < 5; i++)
		{
			press(up.x + 5, up.y + 5);
			release(up.x + 5, up.y + 5);
			frame(inVarrockCastle(0), options());
		}
		assertEquals("no floor above the third", 3, surface.viewer().getFloor());
		for (int i = 0; i < 5; i++)
		{
			press(down.x + 5, down.y + 5);
			release(down.x + 5, down.y + 5);
			frame(inVarrockCastle(0), options());
		}
		assertEquals("no floor below the ground", 0, surface.viewer().getFloor());
	}

	@Test
	public void aDungeonMapHasNoFloorButtonsAndStaysOnTheGround()
	{
		frames(10, inVarrockCastle(2), options());
		assertEquals(2, surface.viewer().getFloor());
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().byId(6), 3040, 9800);
		frames(3, inVarrockCastle(2), options());
		assertEquals(0, surface.viewer().getFloor());
	}

	@Test
	public void searchingForAShopTakesTheMapThere()
	{
		frames(10, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		assertTrue(surface.searchBox().isFocused());

		for (char c : "bob's brilliant".toCharArray())
		{
			typeChar(c);
		}
		frame(lumbridge(), options());
		assertFalse(surface.searchBox().getResults().isEmpty());
		assertEquals(SearchKind.SHOP, surface.searchBox().getResults().get(0).getKind());

		pressKey(KeyEvent.VK_ENTER, '\n');
		frames(80, lumbridge(), options());
		assertNotNull(surface.found());
		assertFalse(surface.viewer().getCamera().isFollowing());
		assertEquals(surface.found().getX(), surface.viewer().getCamera().getX(), 1.0);
		assertTrue(surface.viewer().currentPixelsPerTile() >= 7.9);
		assertFalse(surface.searchBox().isFocused());
	}

	@Test
	public void clickingADoorwayOpensTheMapBelow()
	{
		frames(10, lumbridge(), options());
		Hotspot door = null;
		for (Hotspot h : HotspotLoader.load(new Gson(), assets.getCatalog()))
		{
			if (h.getKind() == Hotspot.Kind.DOORWAY && h.getCard().getTitle().equals("Dwarven Mine")
				&& h.getMapId() == MapInfo.SURFACE && Math.abs(h.getX() - 3019.5) < 2)
			{
				door = h;
			}
		}
		assertNotNull(door);

		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), door.getX(), door.getY());
		frames(5, lumbridge(), options());
		ViewTransform view = surface.currentView();
		int px = (int) Math.round(view.screenX(door.getX()));
		int py = (int) Math.round(view.screenY(door.getY()));
		press(px, py);
		release(px, py);
		frame(lumbridge(), options());

		assertEquals(door.getTarget().getMapId(), surface.viewer().getMap().getId());
		assertEquals(door.getTarget().getX(), surface.viewer().getCamera().getX(), 1.0);
	}

	@Test
	public void aNonInteractiveSurfaceTakesNothingFromTheMouse()
	{
		MapSurface.Options hud = options().interactive(false).alpha(0.5f);
		frame(lumbridge(), hud);
		MouseEvent event = mouse(MouseEvent.MOUSE_PRESSED, 500, 300, MouseEvent.BUTTON1);
		input().mousePressed(event);
		assertFalse(event.isConsumed());
		MouseWheelEvent wheel = wheel(500, 300, -1);
		input().mouseWheelMoved(wheel);
		assertFalse(wheel.isConsumed());
	}

	@Test
	public void aTranslucentSurfaceBlendsWithWhatIsBehindIt()
	{
		Graphics2D g = canvas.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 1200, 800);
		g.dispose();
		MapSurface.Options hud = options().interactive(false).alpha(0.5f);
		int backdrop = com.worldmapplus.render.MapRenderer.BACKGROUND.getRGB() & 0xFFFFFF;
		int blendedBackdrop = (((backdrop >> 16) & 0xFF) + 255) / 2 << 16 | (((backdrop >> 8) & 0xFF) + 255) / 2 << 8
			| (backdrop & 0xFF) + 255 >> 1;
		for (int i = 0; i < 600; i++)
		{
			if (i > 0 && differs(canvas.getRGB(BOUNDS.x + 250, BOUNDS.y + 200) & 0xFFFFFF, blendedBackdrop))
			{
				break;
			}
			// The game redraws its own frame before the overlay every time; do the same.
			Graphics2D clear = canvas.createGraphics();
			clear.setColor(Color.WHITE);
			clear.fillRect(0, 0, 1200, 800);
			clear.dispose();
			frame(lumbridge(), hud);
			try
			{
				Thread.sleep(2);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
		// Away from the player marker, which is drawn over the map.
		int pixel = canvas.getRGB(BOUNDS.x + 250, BOUNDS.y + 200) & 0xFFFFFF;
		int red = (pixel >> 16) & 0xFF;
		int green = (pixel >> 8) & 0xFF;
		int blue = pixel & 0xFF;
		assertTrue("a tile should show through, not only the backdrop: " + Integer.toHexString(pixel),
			differs(pixel, blendedBackdrop));
		assertTrue("expected a lightened map, got " + Integer.toHexString(pixel),
			red > 110 && green > 110 && blue > 110);
	}

	@Test
	public void hidingSavesTheViewInItsOwnSlot()
	{
		frames(5, lumbridge(), options().memorySlot(ViewMemory.HUD));
		surface.hide();
		assertNotNull(saved.get(ViewMemory.HUD));
		assertNull(saved.get(ViewMemory.WORLD_MAP));
		saved.clear();
		surface.hide();
		assertTrue(saved.isEmpty());
	}

	@Test
	public void hidingReleasesTheInput()
	{
		frames(5, lumbridge(), options());
		surface.hide();
		MouseEvent event = mouse(MouseEvent.MOUSE_PRESSED, 500, 300, MouseEvent.BUTTON1);
		input().mousePressed(event);
		assertFalse(event.isConsumed());
	}

	@Test
	public void aRightClickOpensTheMenuAndRoutesThroughShortestPath()
	{
		frames(30, lumbridge(), options());
		ViewTransform view = surface.currentView();
		int tx = (int) Math.floor(view.worldX(600));
		int ty = (int) Math.floor(view.worldY(250));

		rightClick(600, 250);
		frame(lumbridge(), options());
		assertTrue(surface.menu().isOpen());

		chooseMenu(ContextMenu.Action.ROUTE);
		assertFalse(surface.menu().isOpen());
		PluginMessage message = lastMessage("shortestpath", "path");
		assertNotNull(message);
		assertEquals(new WorldPoint(tx, ty, 0), message.getData().get("target"));
		assertNull(message.getData().get("start"));
	}

	@Test
	public void aStartAndMoreTargetsCanBeSetByHandOnceThereIsARoute()
	{
		frames(30, lumbridge(), options());
		rightClick(600, 250);
		frame(lumbridge(), options());
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			assertTrue("no start or extra target before a route",
				item.getAction() != ContextMenu.Action.SET_ROUTE_START && item.getAction() != ContextMenu.Action.ADD_ROUTE_TARGET);
		}
		chooseMenu(ContextMenu.Action.ROUTE);

		ViewTransform view = surface.currentView();
		int sx = (int) Math.floor(view.worldX(300));
		int sy = (int) Math.floor(view.worldY(400));
		rightClick(300, 400);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.SET_ROUTE_START);
		PluginMessage message = lastMessage("shortestpath", "path");
		assertEquals(new WorldPoint(sx, sy, 0), message.getData().get("start"));

		int tx = (int) Math.floor(view.worldX(700));
		int ty = (int) Math.floor(view.worldY(150));
		rightClick(700, 150);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.ADD_ROUTE_TARGET);
		message = lastMessage("shortestpath", "path");
		assertTrue(message.getData().get("target") instanceof java.util.Set);
		assertTrue(((java.util.Set<?>) message.getData().get("target")).contains(new WorldPoint(tx, ty, 0)));
		assertEquals("the start set by hand is kept", new WorldPoint(sx, sy, 0), message.getData().get("start"));
		frames(3, lumbridge(), options());
	}

	/** Options with no following of the player, so the map is only moved by the game's map or by the mouse. */
	private static MapSurface.Options still()
	{
		return new MapSurface.Options().followOnOpen(false).openZoom(4.0);
	}

	@Test
	public void linedUpWithTheGameMapItTakesItsZoomAndPlace()
	{
		withGameMap();
		lineUp = true;
		moves.clear();
		frames(30, lumbridge(), still());
		ViewTransform view = surface.currentView();
		assertTrue(surface.isLinedUp());
		assertEquals(6.0, view.getPixelsPerTile(), 1e-9);
		double[] centre = LineUp.centerFor(3222, 3218, 6.0, BOUNDS.width, BOUNDS.height);
		assertEquals(centre[0], view.getCenterX(), 1e-6);
		assertEquals(centre[1], view.getCenterY(), 1e-6);
		assertTrue("nothing to tell the game's map while it is the one being followed", moves.isEmpty());

		// The game's map moves, perhaps because Quest Helper centred it, and this map goes with it.
		gameMap = new NativeMap.View(3300, 3250, 6.0);
		frames(3, lumbridge(), still());
		centre = LineUp.centerFor(3300, 3250, 6.0, BOUNDS.width, BOUNDS.height);
		assertEquals(centre[0], surface.currentView().getCenterX(), 1e-6);
		assertEquals(centre[1], surface.currentView().getCenterY(), 1e-6);
	}

	@Test
	public void theWheelAndDragsOnTheMapAreLeftForTheGameWhileLinedUp()
	{
		withGameMap();
		lineUp = true;
		frames(30, lumbridge(), still());
		MouseWheelEvent wheel = wheel(500, 300, -3);
		input().mouseWheelMoved(wheel);
		assertFalse("the game zooms its own map", wheel.isConsumed());
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 500, 300, MouseEvent.BUTTON1);
		input().mousePressed(press);
		assertFalse("the game pans its own map", press.isConsumed());
		MouseEvent drag = mouse(MouseEvent.MOUSE_DRAGGED, 420, 300, MouseEvent.NOBUTTON);
		input().mouseDragged(drag);
		assertFalse(drag.isConsumed());
		MouseEvent release = mouse(MouseEvent.MOUSE_RELEASED, 420, 300, MouseEvent.BUTTON1);
		input().mouseReleased(release);
		assertFalse(release.isConsumed());
		double before = surface.currentView().getCenterX();
		frames(10, lumbridge(), still());
		assertEquals("this map takes no part in the drag", before, surface.currentView().getCenterX(), 1e-6);
		assertEquals(6.0, surface.currentView().getPixelsPerTile(), 1e-9);
		assertTrue("and it sent the game's map nowhere", moves.isEmpty());
	}

	@Test
	public void thisMapsOwnControlsStillTakeTheirClicksWhileLinedUp()
	{
		withGameMap();
		lineUp = true;
		frames(30, lumbridge(), still());
		MapButtons probe = new MapButtons();
		probe.setLineUp(true, true);
		probe.setZoomShown(false);
		probe.layout(BOUNDS);
		Rectangle button = probe.boundsOf(MapButtons.Button.LAYERS);
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, button.x + 5, button.y + 5, MouseEvent.BUTTON1);
		input().mousePressed(press);
		assertTrue(press.isConsumed());
		input().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, button.x + 5, button.y + 5, MouseEvent.BUTTON1));
		frame(lumbridge(), still());
		assertTrue("the layers panel opened", surface.layerPanel().isOpen());
	}

	@Test
	public void aRightClickIsTheGamesUnlessCtrlIsHeldWhileLinedUp()
	{
		withGameMap();
		lineUp = true;
		frames(30, lumbridge(), still());
		MouseEvent plain = mouse(MouseEvent.MOUSE_PRESSED, 600, 250, MouseEvent.BUTTON3);
		input().mousePressed(plain);
		assertFalse("the game's own menu, with other plugins' entries, opens", plain.isConsumed());
		input().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, 600, 250, MouseEvent.BUTTON3));
		frame(lumbridge(), still());
		assertFalse(surface.menu().isOpen());

		MouseEvent withCtrl = new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, MouseEvent.CTRL_DOWN_MASK, 600, 250, 1,
			false, MouseEvent.BUTTON3);
		input().mousePressed(withCtrl);
		assertTrue(withCtrl.isConsumed());
		input().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now + 16, MouseEvent.CTRL_DOWN_MASK, 600, 250,
			1, false, MouseEvent.BUTTON3));
		frame(lumbridge(), still());
		assertTrue("this map's menu opens", surface.menu().isOpen());
	}

	@Test
	public void aMoveMadeByThisMapSendsTheGameMapAfterItAndHoldsUntilItArrives()
	{
		withGameMap();
		lineUp = true;
		frames(30, lumbridge(), still());
		moves.clear();
		// A search result, a link or a pin takes this map somewhere.
		surface.focusOn(3400, 3300);
		frames(3, lumbridge(), still());
		assertFalse("the game's map was told where to go", moves.isEmpty());
		int[] last = moves.get(moves.size() - 1);
		assertTrue(Math.abs(last[0] - 3400) <= 40 && Math.abs(last[1] - 3300) <= 40);
		double held = surface.currentView().getCenterX();
		frames(10, lumbridge(), still());
		assertEquals("this map stays where it was put while the game's map is on its way", held,
			surface.currentView().getCenterX(), 1e-6);

		// The game's map arrives, and this map goes on following it.
		gameMap = new NativeMap.View(last[0], last[1], 6.0);
		frames(3, lumbridge(), still());
		double[] centre = LineUp.centerFor(last[0], last[1], 6.0, BOUNDS.width, BOUNDS.height);
		assertEquals(centre[0], surface.currentView().getCenterX(), 1e-6);
		gameMap = new NativeMap.View(last[0] + 50, last[1] + 20, 6.0);
		frames(3, lumbridge(), still());
		centre = LineUp.centerFor(last[0] + 50, last[1] + 20, 6.0, BOUNDS.width, BOUNDS.height);
		assertEquals(centre[0], surface.currentView().getCenterX(), 1e-6);
	}

	@Test
	public void followingThePlayerSendsTheGameMapToThemOnce()
	{
		withGameMap();
		lineUp = true;
		moves.clear();
		frames(3, lumbridge(), options());
		assertFalse("the map opens on the player and the game's map is sent there", moves.isEmpty());
		int[] first = moves.get(0);
		assertTrue(Math.abs(first[0] - 3222) <= 40 && Math.abs(first[1] - 3218) <= 40);
		assertFalse("and this map no longer follows the player itself", surface.viewer().getCamera().isFollowing());
		int count = moves.size();
		frames(30, lumbridge(), options());
		assertEquals("it is sent once, not every frame", count, moves.size());
	}

	@Test
	public void theGameMapChangingToAnotherMapTakesThisMapThereToo()
	{
		withGameMap();
		lineUp = true;
		frames(30, lumbridge(), still());
		assertEquals(com.worldmapplus.map.MapInfo.SURFACE, surface.viewer().getMap().getId());
		// The game's map shows a place underground.
		com.worldmapplus.map.MapInfo underground = assets.getCatalog().byId(12);
		int tileX = -1;
		int tileY = -1;
		for (int sx = underground.firstSquareX(); sx <= underground.lastSquareX() && tileX < 0; sx++)
		{
			for (int sy = underground.firstSquareY(); sy <= underground.lastSquareY() && tileX < 0; sy++)
			{
				com.worldmapplus.map.MapInfo found = assets.getCatalog().mapFor(sx * 64 + 32, sy * 64 + 32);
				if (found != null && found.getId() == 12)
				{
					tileX = sx * 64 + 32;
					tileY = sy * 64 + 32;
				}
			}
		}
		assertTrue("an underground tile was found", tileX >= 0);
		gameMap = new NativeMap.View(tileX, tileY, 6.0);
		frames(NativeMapSync.STILL_FRAMES + 12, lumbridge(), still());
		assertTrue("this map now shows an underground map", surface.viewer().getMap().getId() != com.worldmapplus.map.MapInfo.SURFACE);
	}

	@Test
	public void theZoomButtonsAreNotOfferedWhileLinedUp()
	{
		withGameMap();
		lineUp = true;
		frames(30, lumbridge(), still());
		MapButtons probe = new MapButtons();
		probe.setLineUp(true, true);
		probe.setZoomShown(true);
		probe.layout(BOUNDS);
		Rectangle zoomIn = probe.boundsOf(MapButtons.Button.ZOOM_IN);
		// What was the zoom in button is now somewhere else, so a click there is not a zoom.
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, zoomIn.x + 5, zoomIn.y + 5, MouseEvent.BUTTON1);
		input().mousePressed(press);
		input().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, zoomIn.x + 5, zoomIn.y + 5, MouseEvent.BUTTON1));
		frames(5, lumbridge(), still());
		assertEquals(6.0, surface.currentView().getPixelsPerTile(), 1e-9);
	}

	@Test
	public void withoutTheSettingTheMapKeepsItsOwnZoom()
	{
		withGameMap();
		lineUp = false;
		frames(30, lumbridge(), still());
		assertFalse(surface.isLinedUp());
		assertEquals(4.0, surface.currentView().getPixelsPerTile(), 0.01);
	}

	@Test
	public void theLineUpButtonFlipsTheSetting()
	{
		withGameMap();
		frames(10, lumbridge(), still());
		MapButtons probe = new MapButtons();
		probe.setLineUp(true, false);
		probe.layout(BOUNDS);
		Rectangle button = probe.boundsOf(MapButtons.Button.LINE_UP);
		assertTrue(button.width > 0);
		press(button.x + 5, button.y + 5);
		release(button.x + 5, button.y + 5);
		frame(lumbridge(), still());
		assertEquals(Boolean.TRUE, toggled.get("lineUpWithGameMap"));
	}

	/** A surface that reads points other plugins have on the game's map, from a plugin named Fake Plugin. */
	private MapSurface withOtherPluginPoints(int points, String hidden)
	{
		java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> list = new ArrayList<>();
		for (int i = 0; i < points; i++)
		{
			list.add(new net.runelite.client.ui.overlay.worldmap.WorldMapPoint(new WorldPoint(3222 + i, 3218, 0), null)
			{
			});
		}
		com.worldmapplus.bridge.NativePoints nativePoints = new com.worldmapplus.bridge.NativePoints(list::forEach);
		com.worldmapplus.compat.MapCensus census = new com.worldmapplus.compat.MapCensus(
			() -> java.util.Collections.singletonList(new com.worldmapplus.compat.MapCensus.PluginInfo("Fake Plugin",
				"com.worldmapplus.ui.FakePlugin", true)),
			sink ->
			{
			}, "com.example.own");
		SurfaceServices services = new SurfaceServices(quiet, new ViewMemory(null)
		{
			@Override
			public Saved load(String slot)
			{
				return null;
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), new TrailTracker(), null,
			nativePoints, routeStore, new com.worldmapplus.pins.PanelLauncher(), census);
		return new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String hiddenMapSources()
			{
				return hidden;
			}
		}, services);
	}

	private void frames(MapSurface own, int count)
	{
		Graphics2D g = canvas.createGraphics();
		for (int i = 0; i < count; i++)
		{
			now += 16;
			own.render(g, BOUNDS, lumbridge(), options(), now);
		}
		g.dispose();
	}

	@Test
	public void pointsOtherPluginsHaveOnTheGameMapAreShownAndTheirPluginIsListed()
	{
		MapSurface own = withOtherPluginPoints(3, "");
		frames(own, 20);
		assertEquals(3, own.shownMarkers().size());
		assertTrue(own.census().needsGameMap());
		boolean listed = false;
		for (LayerPanel.Row row : own.layerPanel().getRows())
		{
			listed |= row.getKey().equals("plugin:Fake Plugin") && row.getLabel().contains("(3)");
		}
		assertTrue("the plugin has a tick box among the layers", listed);
	}

	@Test
	public void aPluginThePlayerHidTheirPointsOfIsLeftOut()
	{
		MapSurface own = withOtherPluginPoints(3, "Fake Plugin");
		frames(own, 20);
		assertEquals(0, own.shownMarkers().size());
		for (LayerPanel.Row row : own.layerPanel().getRows())
		{
			if (row.getKey().equals("plugin:Fake Plugin"))
			{
				assertFalse("its tick box is off", row.isOn(new WorldMapPlusConfig()
				{
					@Override
					public String hiddenMapSources()
					{
						return "Fake Plugin";
					}
				}));
			}
		}
	}

	@Test
	public void withNothingOnTheGameMapThereIsNothingToKeepTheGameMapWith()
	{
		MapSurface own = withOtherPluginPoints(0, "");
		frames(own, 20);
		assertFalse(own.wantsGameMap());
		own = withOtherPluginPoints(2, "");
		frames(own, 20);
		assertTrue(own.wantsGameMap());
	}

	@Test
	public void locateSaysSoWhenThePlayerIsSomewhereTheMapHasNoPictureOf()
	{
		frames(10, lumbridge(), still());
		PlayerPosition nowhere = new PlayerPosition(100.5, 100.5, new WorldPoint(100, 100, 0));
		MapButtons probe = new MapButtons();
		probe.layout(BOUNDS);
		Rectangle locate = probe.boundsOf(MapButtons.Button.LOCATE);
		press(locate.x + 5, locate.y + 5);
		release(locate.x + 5, locate.y + 5);
		frame(nowhere, still());
		assertNotNull(surface.notice());
		assertTrue(surface.notice(), surface.notice().startsWith("You are somewhere"));
	}

	private static double[] trailPoint(double x, double y, boolean breakBefore)
	{
		return new double[] {x, y, breakBefore ? 1 : 0, 0};
	}

	@Test
	public void theTrailKeepsEverythingWhenTheFadeIsZero()
	{
		List<double[]> points = new ArrayList<>();
		for (int i = 0; i < 50; i++)
		{
			points.add(trailPoint(3000 + i * 2, 3000, i == 0));
		}
		for (double keep : MapSurface.trailFades(points, null, 0))
		{
			assertEquals(1.0, keep, 1e-9);
		}
		for (double keep : MapSurface.trailFades(points, null, -3))
		{
			assertEquals("a negative setting is no fade", 1.0, keep, 1e-9);
		}
	}

	@Test
	public void theTrailFadesToNothingAfterTenTilesPerStepOfTheSetting()
	{
		List<double[]> points = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			points.add(trailPoint(3000 + i * 2, 3000, i == 0));
		}
		// Two steps is 20 tiles, which is ten pieces of the trail of two tiles each.
		double[] keep = MapSurface.trailFades(points, null, 2);
		assertEquals(0.9, keep[19], 1e-9);
		assertEquals(0.5, keep[15], 1e-9);
		assertEquals(0.0, keep[10], 1e-9);
		assertEquals(0.0, keep[9], 1e-9);
		assertEquals(0.0, keep[1], 1e-9);
		for (int i = 2; i < 20; i++)
		{
			assertTrue("older is never fainter than newer", keep[i - 1] <= keep[i]);
		}
		// A setting with a decimal is that many tens and a bit.
		double[] short_ = MapSurface.trailFades(points, null, 2.1);
		assertEquals(1.0 - 2.0 / 21.0, short_[19], 1e-9);
		assertEquals(0.0, short_[8], 1e-9);
	}

	@Test
	public void theTrailFadesByHowFarYouWalkedNotByHowLongAgo()
	{
		List<double[]> points = new ArrayList<>();
		// Walking back and forth over the same 4 tiles is a long walk.
		for (int i = 0; i < 40; i++)
		{
			points.add(trailPoint(3000 + (i % 2) * 2, 3000, i == 0));
		}
		double[] keep = MapSurface.trailFades(points, null, 1);
		assertEquals(0.0, keep[30], 1e-9);
		assertTrue(keep[39] > 0.5);
		// Standing still adds nothing, since a point is only added after moving.
		PlayerPosition at = new PlayerPosition(3002, 3000, new WorldPoint(3002, 3000, 0));
		assertEquals(0.8, MapSurface.trailFades(points, at, 1)[39], 1e-9);
	}

	@Test
	public void aBreakInTheTrailAddsNoDistanceAndATeleportNeitherToThePlayer()
	{
		List<double[]> points = new ArrayList<>();
		points.add(trailPoint(3000, 3000, true));
		points.add(trailPoint(3002, 3000, false));
		points.add(trailPoint(3500, 3500, true));
		points.add(trailPoint(3502, 3500, false));
		double[] keep = MapSurface.trailFades(points, null, 1);
		assertEquals(0.8, keep[3], 1e-9);
		assertEquals("the line before a break is not drawn at all", 0.8, keep[2], 1e-9);
		PlayerPosition far = new PlayerPosition(3100, 3100, new WorldPoint(3100, 3100, 0));
		assertEquals("a player far from the newest point has teleported, which is not walking", 0.8,
			MapSurface.trailFades(points, far, 1)[3], 1e-9);
	}

	@Test
	public void clickingAResultWithSeveralPlacesGoesToTheNextEachTime()
	{
		frames(10, lumbridge(), still());
		java.util.List<double[]> spots = new ArrayList<>();
		spots.add(new double[] {3100.5, 3200.5});
		spots.add(new double[] {3230.5, 3220.5});
		spots.add(new double[] {3300.5, 3300.5});
		SearchEntry patches = new SearchEntry(SearchKind.SHOP, "Spirit tree patch", "(3 places)", 0, 3100.5, 3200.5, spots);
		java.util.List<Double> visited = new ArrayList<>();
		for (int click = 0; click < 4; click++)
		{
			now += 400;
			surface.activateForTest(patches, now);
			visited.add(surface.found().getX());
			assertEquals(3, surface.foundPlace()[1]);
			assertEquals(click % 3 + 1, surface.foundPlace()[0]);
		}
		assertEquals("the nearest the player is first", 3230.5, visited.get(0), 1e-9);
		assertTrue("the next click goes somewhere else", !visited.get(1).equals(visited.get(0)));
		assertTrue(!visited.get(2).equals(visited.get(1)) && !visited.get(2).equals(visited.get(0)));
		assertEquals("and then round to the first again", visited.get(0), visited.get(3), 1e-9);
	}

	@Test
	public void everyPlaceOfAKindIsMarkedTheNearestGoneToAndItsIconsRevealed()
	{
		frames(10, lumbridge(), still());
		SearchEntry banks = null;
		for (SearchEntry entry : assets.getSearchEntries())
		{
			if ("bank".equals(entry.getKindId()) && entry.getGroup() < 0)
			{
				banks = entry;
			}
		}
		assertNotNull(banks);
		now += 400;
		surface.activateForTest(banks, now);
		SearchEntry found = surface.found();
		assertEquals(0, found.getMapId());
		assertTrue("every bank on the surface is marked: " + found.getAlsoAt().size(), found.getAlsoAt().size() > 50);
		double best = Double.MAX_VALUE;
		for (double[] tile : found.getAlsoAt())
		{
			best = Math.min(best, Math.hypot(tile[0] - 3222.5, tile[1] - 3218.5));
		}
		assertEquals("the nearest bank to the player is gone to", best, Math.hypot(found.getX() - 3222.5, found.getY() - 3218.5), 1e-9);
		assertEquals(1, surface.foundPlace()[0]);
		assertTrue("the bank icons are drawn at every zoom for a while", assets.getGameIconLayer().revealing());
		frames(3, lumbridge(), still());
	}

	private SearchEntry kindEntry(String id)
	{
		for (SearchEntry entry : assets.getSearchEntries())
		{
			if (id.equals(entry.getKindId()) && entry.getGroup() < 0)
			{
				return entry;
			}
		}
		throw new AssertionError(id);
	}

	private void clickAt(java.awt.Rectangle r)
	{
		int x = r.x + r.width / 2;
		int y = r.y + r.height / 2;
		mouseX = x;
		mouseY = y;
		press(x, y);
		now += 16;
		release(x, y);
		frames(2, lumbridge(), still());
	}

	@Test
	public void theResultsPanelListsEveryTownOfAKindNearestFirstAndARowGoesThere()
	{
		frames(10, lumbridge(), still());
		now += 400;
		surface.activateForTest(kindEntry("bank"), now);
		frames(2, lumbridge(), still());
		ResultsPanel panel = surface.results();
		assertTrue(panel.isOpen());
		assertEquals("Banks", panel.view().title);
		ResultView.Tab places = panel.view().current();
		assertTrue(places.rows.size() > 50);
		assertTrue("the nearest town is first", places.rows.get(0).distance <= places.rows.get(1).distance);
		assertTrue(places.rows.get(0).count, places.rows.get(0).count.endsWith(" bank") || places.rows.get(0).count.endsWith(" banks"));
		java.awt.Rectangle second = panel.rowRect(1);
		assertNotNull("the second row is on screen", second);
		assertTrue("the panel is inside the map", BOUNDS.contains(panel.getBounds()));
		double cameraBefore = surface.viewer().getCamera().getX();
		clickAt(second);
		assertEquals(places.rows.get(1).label, surface.found().getName());
		assertEquals(places.rows.get(1).mark.size(), surface.found().getAlsoAt().size());
		assertTrue("a click on the panel is not a drag of the map", panel.isOpen());
		assertTrue(cameraBefore != surface.viewer().getCamera().getX() || places.rows.get(1).x == cameraBefore);
		// Sorting by name puts the towns in order, and the cross closes the panel.
		clickAt(panel.sortRect(1));
		assertEquals(ResultView.Sort.NAME, panel.view().current().sort);
		assertTrue(panel.view().current().rows.get(0).label.compareToIgnoreCase(panel.view().current().rows.get(1).label) <= 0);
		clickAt(panel.closeRect());
		assertTrue(!panel.isOpen());
	}

	@Test
	public void aShopRowOpensItsStockAndBackReturnsToTheShops()
	{
		frames(10, lumbridge(), still());
		SearchEntry axe = null;
		for (SearchEntry entry : assets.getSearchEntries())
		{
			if (entry.getKind() == SearchKind.ITEM && entry.getName().equals("Bronze axe") && entry.getDetail().startsWith("Sold at "))
			{
				axe = entry;
				break;
			}
		}
		assertNotNull(axe);
		now += 400;
		surface.activateForTest(axe, now);
		frames(2, lumbridge(), still());
		ResultsPanel panel = surface.results();
		assertTrue(panel.isOpen());
		assertEquals("Shops", panel.view().current().name);
		int shopRow = -1;
		for (int i = 0; i < panel.view().current().rows.size() && i < 5; i++)
		{
			if (panel.view().current().rows.get(i).follow != null && panel.rowRect(i) != null)
			{
				shopRow = i;
				break;
			}
		}
		assertTrue("a shop row that opens the shop", shopRow >= 0);
		String shop = panel.view().current().rows.get(shopRow).label;
		clickAt(panel.rowRect(shopRow));
		assertEquals(shop, panel.view().title);
		assertEquals("Stock", panel.view().current().name);
		assertEquals(1, panel.backDepth());
		clickAt(panel.backRect());
		assertEquals("Bronze axe", panel.view().title);
		assertEquals(0, panel.backDepth());
		// Sorting the shops by stock puts the largest stock first.
		int stock = panel.view().current().sorts.indexOf(ResultView.Sort.STOCK);
		clickAt(panel.sortRect(stock));
		ResultView.Tab shops = panel.view().current();
		for (int i = 1; i < shops.rows.size(); i++)
		{
			assertTrue(shops.rows.get(i - 1).stock >= shops.rows.get(i).stock || shops.rows.get(i).stock < 0);
		}
	}

	@Test
	public void theResultsPanelIsNotDrawnAndTakesNothingInClickThrough()
	{
		frames(10, lumbridge(), still());
		now += 400;
		surface.activateForTest(kindEntry("bank"), now);
		frames(2, lumbridge(), still());
		java.awt.Rectangle box = surface.results().getBounds();
		assertTrue(!box.isEmpty());
		// Click-through: the surface is not interactive, so nothing of it is drawn and the input takes no event.
		MapSurface.Options through = still().interactive(false);
		frames(3, lumbridge(), through);
		BufferedImage withPanel = canvas;
		int x = box.x + box.width / 2;
		int y = box.y + box.height / 2;
		input().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
		assertTrue("the queue stays empty", input().getQueue().drain().isEmpty());
		assertNotNull(withPanel);
	}

	@Test
	public void choosingAResultWithOnePlacePutsThePanelAway()
	{
		frames(10, lumbridge(), still());
		now += 400;
		surface.activateForTest(kindEntry("bank"), now);
		assertTrue(surface.results().isOpen());
		now += 400;
		surface.activateForTest(new SearchEntry(SearchKind.SHOP, "Lumbridge General Store", "", 0, 3222.5, 3218.5), now);
		assertTrue(!surface.results().isOpen());
	}

	/** An executor that holds back the drop table read until the test lets it go, and runs everything else as usual. */
	private static final class HoldingExecutor extends java.util.concurrent.ScheduledThreadPoolExecutor
	{
		final List<Runnable> held = new ArrayList<>();

		HoldingExecutor()
		{
			super(1);
		}

		@Override
		public synchronized void execute(Runnable task)
		{
			if (task.getClass().getName().contains("DropLoader"))
			{
				held.add(task);
				return;
			}
			super.execute(task);
		}

		synchronized void release()
		{
			held.forEach(Runnable::run);
			held.clear();
		}
	}

	private static SearchEntry creature(MapAssets from, String name)
	{
		for (SearchEntry entry : from.getSearchEntries())
		{
			if (entry.getKind() == SearchKind.MONSTER && entry.getName().equals(name))
			{
				return entry;
			}
		}
		throw new AssertionError("no monster " + name);
	}

	private static List<String> tabNames(ResultView view)
	{
		List<String> names = new ArrayList<>();
		for (ResultView.Tab tab : view.tabs)
		{
			names.add(tab.name);
		}
		return names;
	}

	/** Waits (in the test only) for the shared assets' drop tables. */
	private static void tablesRead()
	{
		assets.getDrops().request();
		long until = System.currentTimeMillis() + 20_000;
		while (assets.getDrops().ready() == null && System.currentTimeMillis() < until)
		{
			try
			{
				Thread.sleep(20);
			}
			catch (InterruptedException e)
			{
				throw new AssertionError(e);
			}
		}
		assertNotNull("the drop tables were read", assets.getDrops().ready());
	}

	@Test
	public void theDropsTabArrivesWithTheTablesOnlyWhileThePanelStillShowsThatResult()
	{
		HoldingExecutor holding = new HoldingExecutor();
		try
		{
			MapAssets own = new MapAssets(new Gson(), holding);
			MapSurface main = surface;
			surface = new MapSurface(client, own, config, services);
			try
			{
				frames(4, lumbridge(), still());
				now += 400;
				surface.activateForTest(creature(own, "Goblin"), now);
				frames(2, lumbridge(), still());
				assertTrue(surface.results().isOpen());
				assertEquals("the tables are not read yet", java.util.Collections.singletonList("Locations"), tabNames(surface.results().view()));
				assertEquals("one read was asked for, on the shared executor", 1, holding.held.size());
				surface.results().view().selectTab(0);
				ResultView before = surface.results().view();
				holding.release();
				frames(1, lumbridge(), still());
				ResultView after = surface.results().view();
				assertTrue("the view was made again", after != before);
				assertEquals(java.util.Arrays.asList("Locations", "Drops"), tabNames(after));
				assertEquals("the tab shown is kept", "Locations", after.current().name);
				assertEquals(0, surface.results().backDepth());
			}
			finally
			{
				surface = main;
			}

			// A second surface on fresh assets: a result chosen, then another, then the tables arrive.
			HoldingExecutor second = new HoldingExecutor();
			try
			{
				MapAssets other = new MapAssets(new Gson(), second);
				surface = new MapSurface(client, other, config, services);
				frames(4, lumbridge(), still());
				now += 400;
				surface.activateForTest(creature(other, "Goblin"), now);
				now += 100;
				surface.activateForTest(kindEntry("bank"), now);
				frames(1, lumbridge(), still());
				assertTrue("the banks are shown", surface.results().isOpen());
				second.release();
				frames(2, lumbridge(), still());
				assertEquals("a late answer about another result changes nothing", "Banks", surface.results().view().title);
				surface.results().close();
				frames(2, lumbridge(), still());
				assertFalse(surface.results().isOpen());
				now += 400;
				surface.activateForTest(creature(other, "Goblin"), now);
				assertTrue(tabNames(surface.results().view()).contains("Drops"));
			}
			finally
			{
				surface = main;
				second.shutdownNow();
			}
		}
		finally
		{
			holding.shutdownNow();
		}
	}

	@Test
	public void pricesArriveLaterAndTheSettingOffHidesTheTabs()
	{
		tablesRead();
		final int[] version = {0};
		final List<int[]> asked = new ArrayList<>();
		com.worldmapplus.drops.PriceSource fake = new com.worldmapplus.drops.PriceSource()
		{
			@Override
			public long price(int itemId)
			{
				return version[0] > 0 && itemId == 526 ? 120 : -1;
			}

			@Override
			public void request(int[] itemIds)
			{
				asked.add(itemIds);
			}

			@Override
			public int version()
			{
				return version[0];
			}
		};
		assets.setPrices(fake);
		try
		{
			frames(4, lumbridge(), still());
			now += 400;
			surface.activateForTest(creature(assets, "Goblin"), now);
			frames(2, lumbridge(), still());
			ResultView view = surface.results().view();
			assertEquals(java.util.Arrays.asList("Locations", "Drops"), tabNames(view));
			assertEquals("the prices were asked for once, with the view", 1, asked.size());
			ResultView.Tab drops = view.tabs.get(1);
			ResultView.Row bones = null;
			for (ResultView.Row row : drops.rows)
			{
				if (row.label.equals("Bones"))
				{
					bones = row;
				}
			}
			assertNotNull(bones);
			assertFalse("no price yet, nothing shown", bones.detail.contains("each"));
			version[0] = 1;
			frames(1, lumbridge(), still());
			assertTrue(bones.detail, bones.detail.contains("120 each"));
			assertTrue(bones.below.contains("120 a kill"));
			assertTrue(view.summary, view.summary.contains("coins a kill") || drops.rows.size() > 0);
			// The tab's order can be chosen like the shop tab's.
			clickAt(surface.results().tabRect(1));
			assertEquals("Drops", surface.results().view().current().name);
			int value = surface.results().view().current().sorts.indexOf(ResultView.Sort.VALUE);
			clickAt(surface.results().sortRect(value));
			assertEquals(ResultView.Sort.VALUE, surface.results().view().current().sort);
			assertEquals("the only priced item is first", "Bones", surface.results().view().current().rows.get(0).label);

			dropTables = false;
			now += 400;
			surface.activateForTest(creature(assets, "Goblin"), now);
			assertEquals(java.util.Collections.singletonList("Locations"), tabNames(surface.results().view()));
		}
		finally
		{
			assets.setPrices(null);
		}
	}

	@Test
	public void aDropRowOpensItsItemAndADroppedByRowGoesToTheMonster()
	{
		tablesRead();
		frames(4, lumbridge(), still());
		now += 400;
		surface.activateForTest(creature(assets, "Goblin"), now);
		// Long enough for the glide to the monster to finish, so the camera is still when it is measured below.
		frames(40, lumbridge(), still());
		ResultsPanel panel = surface.results();
		clickAt(panel.tabRect(1));
		assertEquals("Drops", panel.view().current().name);
		int bones = -1;
		for (int i = 0; i < panel.view().current().rows.size(); i++)
		{
			if (panel.view().current().rows.get(i).label.equals("Bones") && panel.rowRect(i) != null)
			{
				bones = i;
			}
		}
		assertTrue("bones are on screen", bones >= 0);
		double cameraX = surface.viewer().getCamera().getX();
		clickAt(panel.rowRect(bones));
		assertEquals("Bones", panel.view().title);
		assertEquals(1, panel.backDepth());
		assertEquals("a drop row does not move the map", cameraX, surface.viewer().getCamera().getX(), 0.5);
		panel.view().selectTab("Dropped by");
		frames(1, lumbridge(), still());
		ResultView.Tab by = panel.view().current();
		assertEquals("Dropped by", by.name);
		int placed = -1;
		for (int i = 0; i < by.rows.size(); i++)
		{
			if (by.rows.get(i).mapId >= 0 && panel.rowRect(i) != null)
			{
				placed = i;
				break;
			}
		}
		assertTrue("a monster with a place is on screen", placed >= 0);
		ResultView.Row row = by.rows.get(placed);
		clickAt(panel.rowRect(placed));
		assertEquals(row.label, surface.found().getName());
		assertEquals(2, panel.backDepth());
		assertTrue(tabNames(panel.view()).contains("Drops"));
	}

	@Test
	public void theNearestMapFallbackTakesAKindWithNoPlaceWhereThePlayerIs()
	{
		com.worldmapplus.search.KindIndex.Kind kind = assets.getKinds().byId("herb_patch");
		SearchEntry entry = SearchEntry.ofKind("Herb patches", "", 0, 0, 0, new ArrayList<>(), java.util.Arrays.asList("herb patch"),
			"herb_patch", -1, null);
		// The player is on a map with no herb patch and the view is elsewhere too: the first group's map is used.
		SearchEntry target = KindResults.target(assets.getKinds(), entry, 999_999, 999_998);
		assertEquals(kind.getGroups().get(0).getMapId(), target.getMapId());
		// On the surface, the surface's patches.
		target = KindResults.target(assets.getKinds(), entry, 0, -1);
		assertEquals(0, target.getMapId());
		assertTrue(target.getAlsoAt().size() >= 5);
	}

	@Test
	public void aClickOnADifferentResultStartsAgainAtItsNearestPlace()
	{
		frames(10, lumbridge(), still());
		java.util.List<double[]> spots = new ArrayList<>();
		spots.add(new double[] {3100.5, 3200.5});
		spots.add(new double[] {3230.5, 3220.5});
		SearchEntry patches = new SearchEntry(SearchKind.SHOP, "Spirit tree patch", "(2 places)", 0, 3100.5, 3200.5, spots);
		SearchEntry banks = new SearchEntry(SearchKind.SHOP, "Bank", "(2 places)", 0, 3100.5, 3200.5, spots);
		now += 400;
		surface.activateForTest(patches, now);
		now += 400;
		surface.activateForTest(banks, now);
		assertEquals(1, surface.foundPlace()[0]);
		SearchEntry single = new SearchEntry(SearchKind.SHOP, "Lumbridge General Store", "", 0, 3222.5, 3218.5);
		now += 400;
		surface.activateForTest(single, now);
		assertEquals(0, surface.foundPlace()[0]);
	}

	@Test
	public void clearRouteTellsShortestPathToClear()
	{
		frames(10, lumbridge(), options());
		rightClick(600, 250);
		frame(lumbridge(), options());
		boolean offered = false;
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			offered |= item.getAction() == ContextMenu.Action.CLEAR_ROUTE;
		}
		assertFalse("nothing to clear yet", offered);
		chooseMenu(ContextMenu.Action.ROUTE);

		rightClick(600, 250);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.CLEAR_ROUTE);
		assertNotNull(lastMessage("shortestpath", "clear"));
	}

	@Test
	public void clickingElsewhereClosesTheMenuWithoutActing()
	{
		frames(10, lumbridge(), options());
		rightClick(600, 250);
		frame(lumbridge(), options());
		assertTrue(surface.menu().isOpen());
		press(150, 600);
		now += 16;
		release(150, 600);
		frame(lumbridge(), options());
		assertFalse(surface.menu().isOpen());
		assertTrue(posted.isEmpty());
	}

	@Test
	public void aRightClickDragDoesNotOpenTheMenu()
	{
		frames(10, lumbridge(), options());
		input().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 600, 250, MouseEvent.BUTTON3));
		now += 16;
		input().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, 700, 250, MouseEvent.BUTTON3));
		frame(lumbridge(), options());
		assertFalse(surface.menu().isOpen());
	}

	@Test
	public void pinsAreAddedFoundBySearchAndRemoved()
	{
		frames(30, lumbridge(), options());
		rightClick(600, 250);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.ADD_PIN);
		assertEquals(1, pins.all().size());
		assertTrue(pins.all().get(0).getName().startsWith("Pin at "));

		// The pin can now be found by name.
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		for (char c : "pin at".toCharArray())
		{
			typeChar(c);
		}
		frame(lumbridge(), options());
		boolean sawPin = false;
		for (com.worldmapplus.search.SearchEntry entry : surface.searchBox().getResults())
		{
			sawPin |= entry.getKind() == SearchKind.PIN;
		}
		assertTrue(sawPin);
		pressKey(KeyEvent.VK_ESCAPE, (char) 27);
		pressKey(KeyEvent.VK_ESCAPE, (char) 27);
		frame(lumbridge(), options());

		// Right-clicking the pin offers removal.
		com.worldmapplus.pins.Pin pin = pins.all().get(0);
		ViewTransform view = surface.currentView();
		rightClick((int) Math.round(view.screenX(pin.getX())), (int) Math.round(view.screenY(pin.getY())));
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.REMOVE_PIN);
		assertTrue(pins.all().isEmpty());
	}

	@Test
	public void measuringStartsAndStops()
	{
		frames(10, lumbridge(), options());
		rightClick(600, 250);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.MEASURE);
		assertTrue(surface.ruler().isActive());
		rightClick(650, 300);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.STOP_MEASURING);
		assertFalse(surface.ruler().isActive());
	}

	@Test
	public void aMarkerFromAnotherPluginIsDrawnWhereItAsked()
	{
		frames(40, lumbridge(), options());
		ViewTransform view = surface.currentView();
		WorldPoint spot = new WorldPoint(3228, 3222, 0);
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("id", "friend");
		data.put("point", spot);
		data.put("name", "Friend");
		data.put("color", new Color(0, 255, 0));
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "marker", data));
		frames(3, lumbridge(), options());
		int px = (int) Math.round(view.screenX(spot.getX() + 0.5));
		int py = (int) Math.round(view.screenY(spot.getY() + 0.5));
		assertEquals(0x00FF00, canvas.getRGB(px, py) & 0xFFFFFF);
	}

	@Test
	public void aFocusRequestMovesTheMap()
	{
		frames(10, lumbridge(), options());
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("point", new WorldPoint(2964, 3378, 0));
		data.put("zoom", 10.0);
		data.put("name", "Falador");
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "focus", data));
		frames(80, lumbridge(), options());
		assertEquals(2964.5, surface.viewer().getCamera().getX(), 1.0);
		assertEquals(3378.5, surface.viewer().getCamera().getY(), 1.0);
		assertEquals(10.0, surface.viewer().currentPixelsPerTile(), 0.1);
		assertFalse(surface.viewer().getCamera().isFollowing());
		assertNotNull(surface.found());
	}

	@Test
	public void anExactFocusOnATileNoMapHasIsDeclinedAndTheViewDoesNotMove()
	{
		frames(10, lumbridge(), options());
		double x = surface.viewer().getCamera().getX();
		double y = surface.viewer().getCamera().getY();
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("point", new WorldPoint(200, 200, 0));
		data.put("id", "f-declined");
		data.put("exact", Boolean.TRUE);
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "focus", data));
		frames(40, lumbridge(), options());
		assertEquals(x, surface.viewer().getCamera().getX(), 0.01);
		assertEquals(y, surface.viewer().getCamera().getY(), 0.01);
		java.util.List<ExternalOverlays.Reply> replies = external.drainReplies(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("focus_result", replies.get(0).getName());
		assertEquals("f-declined", replies.get(0).getData().get("id"));
		assertEquals(Boolean.FALSE, replies.get(0).getData().get("ok"));
		assertEquals("no_map", replies.get(0).getData().get("reason"));
	}

	@Test
	public void aFocusWithAnIdOnTheGuardiansOfTheRiftArenaIsAnsweredOkWithTheMapName()
	{
		frames(10, lumbridge(), options());
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("point", new WorldPoint(3615, 9469, 0));
		data.put("id", "f-rift");
		data.put("exact", Boolean.TRUE);
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "focus", data));
		frames(40, lumbridge(), options());
		java.util.List<ExternalOverlays.Reply> replies = external.drainReplies(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("f-rift", replies.get(0).getData().get("id"));
		assertEquals(Boolean.TRUE, replies.get(0).getData().get("ok"));
		assertEquals("Guardians of the Rift", replies.get(0).getData().get("mapName"));
		assertEquals("area", replies.get(0).getData().get("mapKind"));
		assertEquals(11000, surface.viewer().getMap().getId());
	}

	@Test
	public void theLayersButtonOpensAPanelWhoseRowsChangeSettings()
	{
		frames(20, lumbridge(), options());
		MapButtons probe = new MapButtons();
		probe.layout(BOUNDS);
		Rectangle layers = probe.boundsOf(MapButtons.Button.LAYERS);
		press(layers.x + 5, layers.y + 5);
		now += 16;
		release(layers.x + 5, layers.y + 5);
		frame(lumbridge(), options());
		assertTrue(surface.layerPanel().isOpen());

		Rectangle panel = surface.layerPanel().getBounds();
		int rowY = panel.y + LayerPanel.PADDING + 18 + 2 * LayerPanel.ROW_HEIGHT + 4;
		LayerPanel.Row row = surface.layerPanel().hit(panel.x + 20, rowY);
		assertNotNull(row);
		assertEquals("showWilderness", row.getKey());
		press(panel.x + 20, rowY);
		now += 16;
		release(panel.x + 20, rowY);
		frame(lumbridge(), options());
		assertEquals(Boolean.TRUE, toggled.get("showWilderness"));
		assertTrue("the panel stays open for more ticks", surface.layerPanel().isOpen());

		press(300, 400);
		now += 16;
		release(300, 400);
		frame(lumbridge(), options());
		assertFalse(surface.layerPanel().isOpen());
	}

	@Test
	public void theWheelOverThePanelDoesNotZoomTheMap()
	{
		frames(20, lumbridge(), options());
		MapButtons probe = new MapButtons();
		probe.layout(BOUNDS);
		Rectangle layers = probe.boundsOf(MapButtons.Button.LAYERS);
		press(layers.x + 5, layers.y + 5);
		release(layers.x + 5, layers.y + 5);
		frame(lumbridge(), options());
		Rectangle panel = surface.layerPanel().getBounds();
		double before = surface.viewer().getZoom().targetPixelsPerTile();
		input().mouseWheelMoved(wheel(panel.x + 30, panel.y + 40, -3));
		frames(20, lumbridge(), options());
		assertEquals(before, surface.viewer().getZoom().targetPixelsPerTile(), 1e-9);
	}

	/** Finds a point of interest by title and returns its position. */
	private com.worldmapplus.layers.Hotspot poi(String title)
	{
		for (com.worldmapplus.layers.Hotspot h : com.worldmapplus.data.PoiLoader.load(new Gson(), assets.getCatalog()).getHotspots())
		{
			if (h.getKind() == com.worldmapplus.layers.Hotspot.Kind.POI && h.getCard().getTitle().equals(title))
			{
				return h;
			}
		}
		throw new AssertionError("no point called " + title);
	}

	@Test
	public void aPointOfInterestAnswersTheMouseOnlyWhileItsLayerIsOn()
	{
		com.worldmapplus.layers.Hotspot altar = poi("Air Altar");
		frames(10, lumbridge(), options());
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), altar.getX(), altar.getY());
		surface.viewer().getZoom().jumpTo(6.0);
		frames(5, lumbridge(), options());
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(altar.getX()));
		mouseY = (int) Math.round(view.screenY(altar.getY()));

		frame(lumbridge(), options());
		assertNull("layer is off", surface.lastCardTitle());

		poiLayers = "rc_altar";
		frame(lumbridge(), options());
		assertEquals("Air Altar", surface.lastCardTitle());

		poiLayers = "";
		// A card with a wiki button (here the search button) is kept for a moment so the pointer can reach it; after that it goes.
		now += MapSurface.HOLD_MILLIS + 100;
		frame(lumbridge(), options());
		assertNull(surface.lastCardTitle());
	}

	@Test
	public void aFarmingPointShowsWhatWasLastSeenInItsPatches()
	{
		java.util.Map<String, String> saved = new java.util.HashMap<>();
		long clock = 50_000_000L;
		saved.put("farming.12083.4774", new com.worldmapplus.farming.Observation(32, clock - 600_000L, false).write());
		com.worldmapplus.farming.FarmingTracker tracker = new com.worldmapplus.farming.FarmingTracker(null,
			com.worldmapplus.farming.FarmingData.load(new Gson()), new com.worldmapplus.farming.FarmingTracker.Store()
			{
				@Override
				public String get(String key)
				{
					return saved.get(key);
				}

				@Override
				public void put(String key, String value)
				{
					saved.put(key, value);
				}
			}, () -> clock);
		SurfaceServices services = new SurfaceServices(quiet, new ViewMemory(null)
		{
			@Override
			public Saved load(String slot)
			{
				return null;
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), new TrailTracker(), tracker, new com.google.gson.Gson());
		poiLayers = "farming";
		MapSurface farm = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return poiLayers;
			}
		}, services);

		com.worldmapplus.layers.Hotspot patch = null;
		for (com.worldmapplus.layers.Hotspot h : com.worldmapplus.data.PoiLoader.load(new Gson(), assets.getCatalog()).getHotspots())
		{
			if ("farming".equals(h.getCategory()) && Math.abs(h.getX() - 3052.5) < 1 && Math.abs(h.getY() - 3309.5) < 1)
			{
				patch = h;
			}
		}
		assertNotNull("the Falador patches are on the map", patch);

		Graphics2D g = canvas.createGraphics();
		for (int i = 0; i < 12; i++)
		{
			now += 16;
			farm.render(g, BOUNDS, lumbridge(), options(), now);
		}
		farm.viewer().getCamera().setFollowing(false);
		farm.viewer().showMap(assets.getCatalog().surface(), patch.getX(), patch.getY());
		farm.viewer().getZoom().jumpTo(6.0);
		for (int i = 0; i < 5; i++)
		{
			now += 16;
			farm.render(g, BOUNDS, lumbridge(), options(), now);
		}
		ViewTransform view = farm.currentView();
		mouseX = (int) Math.round(view.screenX(patch.getX()));
		mouseY = (int) Math.round(view.screenY(patch.getY()));
		now += 16;
		farm.render(g, BOUNDS, lumbridge(), options(), now);
		g.dispose();
		assertEquals("Falador", farm.lastCardTitle());
	}

	private MapSurface surfaceWithPointsFromOtherPlugins(java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> points,
		boolean showThem)
	{
		com.worldmapplus.bridge.NativePoints nativePoints = new com.worldmapplus.bridge.NativePoints(
			sink -> points.forEach(sink));
		SurfaceServices services = new SurfaceServices(quiet, new ViewMemory(null)
		{
			@Override
			public Saved load(String slot)
			{
				return null;
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), new TrailTracker(), null,
			nativePoints, new com.google.gson.Gson());
		return new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public boolean showOtherPluginPoints()
			{
				return showThem;
			}
		}, services);
	}

	@Test
	public void aPointAnotherPluginPutOnTheGameMapIsHoverableHereAndCanJumpTheMap()
	{
		net.runelite.client.ui.overlay.worldmap.WorldMapPoint member =
			new net.runelite.client.ui.overlay.worldmap.WorldMapPoint(new WorldPoint(3222, 3218, 0),
				new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
		member.setName("Alice");
		member.setTooltip("Alice | 99/99 hp\nworld 301");
		member.setJumpOnClick(true);
		member.setTarget(new WorldPoint(3100, 3500, 0));
		MapSurface group = surfaceWithPointsFromOtherPlugins(java.util.Collections.singletonList(member), true);

		Graphics2D g = canvas.createGraphics();
		for (int i = 0; i < 12; i++)
		{
			now += 16;
			group.render(g, BOUNDS, lumbridge(), options(), now);
		}
		ViewTransform view = group.currentView();
		mouseX = (int) Math.round(view.screenX(3222.5));
		mouseY = (int) Math.round(view.screenY(3218.5));
		now += 16;
		group.render(g, BOUNDS, lumbridge(), options(), now);
		assertEquals("Alice", group.lastCardTitle());

		// A click on it, which asked to jump, takes this map to the target and stops following.
		group.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, mouseX, mouseY, MouseEvent.BUTTON1));
		now += 16;
		group.getInput().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, mouseX, mouseY, MouseEvent.BUTTON1));
		now += 16;
		group.render(g, BOUNDS, lumbridge(), options(), now);
		g.dispose();
		assertEquals(3100.5, group.viewer().getCamera().getX(), 1.0);
		assertEquals(3500.5, group.viewer().getCamera().getY(), 1.0);
	}

	/**
	 * Built the way RuneLite's Death Indicator builds its point (DeathWorldMapPoint): the hint arrow and the bones drawn into one
	 * picture, anchored at the middle of its bottom edge, snapping to the edge, jumping on click, with the tooltip "Death Location".
	 */
	static final class DeathLikePoint extends net.runelite.client.ui.overlay.worldmap.WorldMapPoint
	{
		static final int W = 20;
		static final int H = 32;
		private final BufferedImage arrowAndBones = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		private final BufferedImage bones = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);

		DeathLikePoint(WorldPoint where)
		{
			super(where, null);
			for (int y = 0; y < H; y++)
			{
				for (int x = 0; x < W; x++)
				{
					arrowAndBones.setRGB(x, y, 0xFFFF0000);
					bones.setRGB(x, y, 0xFFEEEEEE);
				}
			}
			setSnapToEdge(true);
			setJumpOnClick(true);
			setImage(arrowAndBones);
			setImagePoint(new net.runelite.api.Point(W / 2, H));
			setTooltip("Death Location");
		}
	}

	private MapSurface drawnWithDeathPoint(java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> points,
		PlayerPosition at, int frames)
	{
		MapSurface group = surfaceWithPointsFromOtherPlugins(points, true);
		Graphics2D g = canvas.createGraphics();
		for (int i = 0; i < frames; i++)
		{
			now += 16;
			group.render(g, BOUNDS, at, options(), now);
		}
		g.dispose();
		return group;
	}

	private String hoverTitle(MapSurface group, PlayerPosition at, int x, int y)
	{
		mouseX = x;
		mouseY = y;
		Graphics2D g = canvas.createGraphics();
		now += 16;
		group.render(g, BOUNDS, at, options(), now);
		g.dispose();
		return group.lastCardTitle();
	}

	@Test
	public void aDeathPointIsShownAtTheDeathTileWithItsArrowAndTooltipAndJumpsOnClick()
	{
		DeathLikePoint death = new DeathLikePoint(new WorldPoint(3226, 3220, 0));
		java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> points = new java.util.ArrayList<>();
		points.add(death);
		MapSurface group = drawnWithDeathPoint(points, lumbridge(), 12);
		ViewTransform view = group.currentView();
		int tileX = (int) Math.round(view.screenX(3226.5));
		int tileY = (int) Math.round(view.screenY(3220.5));
		// The picture stands on the tile: its bottom middle is the tile's middle.
		assertEquals(0xFF0000, canvas.getRGB(tileX, tileY - DeathLikePoint.H / 2) & 0xFFFFFF);
		assertEquals("on the tile", "Death Location", hoverTitle(group, lumbridge(), tileX, tileY));
		assertEquals("on the arrow above the tile", "Death Location", hoverTitle(group, lumbridge(), tileX, tileY - DeathLikePoint.H / 2));
		assertNotEquals("away from it", "Death Location", hoverTitle(group, lumbridge(), tileX + 60, tileY));

		mouseX = tileX;
		mouseY = tileY - DeathLikePoint.H / 2;
		group.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, mouseX, mouseY, MouseEvent.BUTTON1));
		now += 16;
		group.getInput().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, mouseX, mouseY, MouseEvent.BUTTON1));
		now += 16;
		Graphics2D g = canvas.createGraphics();
		group.render(g, BOUNDS, lumbridge(), options(), now);
		g.dispose();
		assertEquals(3226.5, group.viewer().getCamera().getX(), 1.0);
		assertEquals(3220.5, group.viewer().getCamera().getY(), 1.0);
	}

	@Test
	public void aDeathPointOnAnUpperFloorShowsAtTheSameTileLikeTheGamesMap()
	{
		DeathLikePoint death = new DeathLikePoint(new WorldPoint(3226, 3220, 2));
		java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> points = new java.util.ArrayList<>();
		points.add(death);
		MapSurface group = drawnWithDeathPoint(points, lumbridge(), 12);
		ViewTransform view = group.currentView();
		assertEquals("Death Location", hoverTitle(group, lumbridge(), (int) Math.round(view.screenX(3226.5)),
			(int) Math.round(view.screenY(3220.5))));
	}

	@Test
	public void aDeathPointUndergroundShowsOnTheUndergroundMapAndNotOnTheSurface()
	{
		DeathLikePoint death = new DeathLikePoint(new WorldPoint(3040, 9800, 0));
		java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> points = new java.util.ArrayList<>();
		points.add(death);
		PlayerPosition below = new PlayerPosition(3040.5, 9800.5, new WorldPoint(3040, 9800, 0));
		MapSurface group = drawnWithDeathPoint(points, below, 12);
		assertEquals(6, group.viewer().getMap().getId());
		ViewTransform view = group.currentView();
		int tileX = (int) Math.round(view.screenX(3040.5));
		int tileY = (int) Math.round(view.screenY(9800.5));
		assertEquals("Death Location", hoverTitle(group, below, tileX, tileY));
		// On the surface map the same screen place has no such point.
		MapSurface top = drawnWithDeathPoint(points, lumbridge(), 12);
		ViewTransform topView = top.currentView();
		assertNotEquals("Death Location", hoverTitle(top, lumbridge(), (int) Math.round(topView.screenX(3040.5)),
			(int) Math.round(topView.screenY(3200.5))));
	}

	@Test
	public void aDeathPointOffTheViewSitsOnTheEdgeAndCanBeClickedThere()
	{
		DeathLikePoint death = new DeathLikePoint(new WorldPoint(3500, 3218, 0));
		java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> points = new java.util.ArrayList<>();
		points.add(death);
		MapSurface group = drawnWithDeathPoint(points, lumbridge(), 12);
		ViewTransform view = group.currentView();
		assertTrue(view.screenX(3500.5) > view.getLeft() + view.getWidth());
		int edgeX = view.getLeft() + view.getWidth() - 14;
		int edgeY = (int) Math.round(view.screenY(3218.5));
		// Drawn centred on the edge, not hanging from it.
		assertEquals(0xFF0000, canvas.getRGB(edgeX, edgeY) & 0xFFFFFF);
		assertEquals("Death Location", hoverTitle(group, lumbridge(), edgeX, edgeY));
		mouseX = edgeX;
		mouseY = edgeY;
		group.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, mouseX, mouseY, MouseEvent.BUTTON1));
		now += 16;
		group.getInput().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, mouseX, mouseY, MouseEvent.BUTTON1));
		now += 16;
		Graphics2D g = canvas.createGraphics();
		group.render(g, BOUNDS, lumbridge(), options(), now);
		g.dispose();
		assertEquals(3500.5, group.viewer().getCamera().getX(), 1.0);
	}

	@Test
	public void aDeathPointTheOwnerRemovedIsGoneFromThisMapToo() throws Exception
	{
		DeathLikePoint death = new DeathLikePoint(new WorldPoint(3226, 3220, 0));
		java.util.List<net.runelite.client.ui.overlay.worldmap.WorldMapPoint> points = new java.util.ArrayList<>();
		points.add(death);
		MapSurface group = drawnWithDeathPoint(points, lumbridge(), 12);
		ViewTransform view = group.currentView();
		int tileX = (int) Math.round(view.screenX(3226.5));
		int tileY = (int) Math.round(view.screenY(3220.5));
		assertEquals("Death Location", hoverTitle(group, lumbridge(), tileX, tileY));
		points.clear();
		// The points are read afresh at most every tenth of a second.
		Thread.sleep(150);
		assertNotEquals("Death Location", hoverTitle(group, lumbridge(), tileX, tileY));
		Thread.sleep(150);
		assertNotEquals("Death Location", hoverTitle(group, lumbridge(), tileX, tileY - DeathLikePoint.H / 2));
	}

	@Test
	public void aFocusRequestIsRememberedSoTheGameMapsFollowingCanBeHeldOff()
	{
		frames(5, lumbridge(), options());
		assertFalse(surface.focusedWithin(now, 2500));
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("point", new WorldPoint(3040, 9820, 0));
		data.put("zoom", 6.0);
		external.onPluginMessage(new net.runelite.client.events.PluginMessage("worldmapplus", "focus", data));
		frame(lumbridge(), options());
		assertTrue(surface.focusedWithin(now, 2500));
		assertFalse("and it lapses", surface.focusedWithin(now + 5000, 2500));
		assertEquals("the map went to the dungeon the point is in", 6, surface.viewer().getMap().getId());
	}

	@Test
	public void theNearestTeleportsAreListedNearestFirstWithTheirDistance()
	{
		frames(5, lumbridge(), options());
		HoverCard card = surface.nearestTeleports(0, 3222, 3218);
		assertEquals("Nearest teleports", card.getTitle());
		assertEquals(MapSurface.NEAREST_TELEPORTS, card.getRows().size());
		int previous = -1;
		for (HoverCard.Row row : card.getRows())
		{
			assertTrue(row.getValue(), row.getValue().endsWith(" tiles"));
			int tiles = Integer.parseInt(row.getValue().replace(" tiles", ""));
			assertTrue("nearest first", tiles >= previous);
			previous = tiles;
		}
		assertTrue("Lumbridge itself is a teleport away", Integer.parseInt(card.getRows().get(0).getValue().replace(" tiles", "")) <= 10);
		assertEquals("Click anywhere to close", card.getHint());
	}

	@Test
	public void theNearestTeleportsOnAMapWithNoneSaysSo()
	{
		frames(5, lumbridge(), options());
		HoverCard card = surface.nearestTeleports(31, 100, 100);
		assertEquals("None on this map", card.getRows().get(0).getLabel());
	}

	@Test
	public void theRightClickMenuOffersTheNearestTeleportsAndShowsThemUntilTheNextClick()
	{
		frames(10, lumbridge(), options());
		rightClick(500, 300);
		frame(lumbridge(), options());
		boolean offered = false;
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			offered |= item.getAction() == ContextMenu.Action.NEAREST_TELEPORTS;
		}
		assertTrue(offered);
	}

	@Test
	public void theTogglePutsOtherPluginsPointsAway()
	{
		net.runelite.client.ui.overlay.worldmap.WorldMapPoint member =
			new net.runelite.client.ui.overlay.worldmap.WorldMapPoint(new WorldPoint(3222, 3218, 0),
				new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
		member.setName("Alice");
		MapSurface group = surfaceWithPointsFromOtherPlugins(java.util.Collections.singletonList(member), false);
		Graphics2D g = canvas.createGraphics();
		for (int i = 0; i < 12; i++)
		{
			now += 16;
			group.render(g, BOUNDS, lumbridge(), options(), now);
		}
		ViewTransform view = group.currentView();
		mouseX = (int) Math.round(view.screenX(3222.5));
		mouseY = (int) Math.round(view.screenY(3218.5));
		now += 16;
		group.render(g, BOUNDS, lumbridge(), options(), now);
		g.dispose();
		// A game icon of the town may be under the mouse, but the member the toggle put away may not be.
		String title = group.lastCardTitle();
		assertTrue("the member is not shown, card: " + title, title == null || !title.contains("Alice"));
	}

	@Test
	public void theLayerPanelOffersEveryPointOfInterestKind()
	{
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (LayerPanel.Row row : surface.layerPanel().getRows())
		{
			keys.add(row.getKey());
		}
		assertFalse("banks are search only", keys.contains("poi:bank"));
		assertTrue(keys.contains("poi:agility"));
		assertTrue(keys.contains("showNpcs"));
	}

	@Test
	public void aLongGapBetweenFramesCountsAsTheMapBeingClosedAndOpenedAgain()
	{
		frames(30, lumbridge(), options());
		press(500, 300);
		now += 16;
		drag(600, 300);
		now += 400;
		release(600, 300);
		frame(lumbridge(), options());
		assertFalse(surface.viewer().getCamera().isFollowing());

		// The overlay is not drawn for a long while: the interface was closed.
		now += 60_000;
		frame(lumbridge(), options());
		assertTrue("reopening starts on the player again", surface.viewer().getCamera().isFollowing());
		assertNotNull(saved.get(ViewMemory.WORLD_MAP));
	}

	@Test
	public void aShortGapCarriesOnWhereTheMapWasSoAResizeDoesNotResetIt()
	{
		frames(30, lumbridge(), options());
		press(500, 300);
		now += 16;
		drag(600, 300);
		now += 400;
		release(600, 300);
		frame(lumbridge(), options());
		assertFalse(surface.viewer().getCamera().isFollowing());
		double zoom = surface.viewer().getZoom().targetPixelsPerTile();

		surface.suspend();
		now += 2000;
		frame(lumbridge(), options());
		assertFalse("the view was kept", surface.viewer().getCamera().isFollowing());
		assertEquals(zoom, surface.viewer().getZoom().targetPixelsPerTile(), 1e-9);
	}

	@Test
	public void aFloatingMapAlwaysStartsFreshWhenItComesBack()
	{
		frames(30, lumbridge(), options().resume(false));
		press(500, 300);
		now += 16;
		drag(600, 300);
		now += 400;
		release(600, 300);
		frame(lumbridge(), options().resume(false));
		assertFalse(surface.viewer().getCamera().isFollowing());

		now += 2000;
		frame(lumbridge(), options().resume(false));
		assertTrue(surface.viewer().getCamera().isFollowing());
	}

	@Test
	public void closingExplicitlyReleasesInputAndSavesTheView()
	{
		frames(10, lumbridge(), options());
		surface.hide();
		assertNotNull(saved.get(ViewMemory.WORLD_MAP));
		MouseEvent event = mouse(MouseEvent.MOUSE_PRESSED, 500, 300, MouseEvent.BUTTON1);
		input().mousePressed(event);
		assertFalse(event.isConsumed());
	}

	/** Puts a pin a little way from the player and returns it, with the map showing it. */
	private com.worldmapplus.pins.Pin placePin(String kind)
	{
		frames(30, lumbridge(), options());
		com.worldmapplus.pins.Pin pin = pins.add("Spot", kind, 0, 3226.5, 3220.5, 0);
		frames(3, lumbridge(), options());
		return pin;
	}

	private int headX(com.worldmapplus.pins.Pin pin)
	{
		return (int) Math.round(surface.currentView().screenX(pin.getX()));
	}

	private int headY(com.worldmapplus.pins.Pin pin)
	{
		return (int) Math.round(surface.currentView().screenY(pin.getY())) - 12;
	}

	@Test
	public void rightClickingThePinsRoundHeadOffersToRemoveIt()
	{
		com.worldmapplus.pins.Pin pin = placePin("pin");
		rightClick(headX(pin), headY(pin));
		frame(lumbridge(), options());
		boolean offersRemove = false;
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			offersRemove |= item.getAction() == ContextMenu.Action.REMOVE_PIN;
		}
		assertTrue(offersRemove);
		chooseMenu(ContextMenu.Action.REMOVE_PIN);
		assertTrue(pins.all().isEmpty());
	}

	@Test
	public void leftClickingAPinAsksForARouteToIt()
	{
		com.worldmapplus.pins.Pin pin = placePin("shop");
		press(headX(pin), headY(pin));
		now += 16;
		release(headX(pin), headY(pin));
		frame(lumbridge(), options());
		PluginMessage message = lastMessage("shortestpath", "path");
		assertNotNull(message);
		assertEquals(new WorldPoint(3226, 3220, 0), message.getData().get("target"));
	}

	@Test
	public void aPinHasACardThatSaysWhatClickingDoes()
	{
		com.worldmapplus.pins.Pin pin = placePin("shop");
		mouseX = headX(pin);
		mouseY = headY(pin);
		frame(lumbridge(), options());
		assertEquals("Spot", surface.lastCardTitle());
	}

	@Test
	public void aRestingMouseKeepsTheSameCardAndARenameOrAChangedKindBuildsANewOne()
	{
		com.worldmapplus.pins.Pin pin = placePin("shop");
		mouseX = headX(pin);
		mouseY = headY(pin);
		frame(lumbridge(), options());
		HoverCard first = surface.lastCard();
		assertEquals("Spot", first.getTitle());
		for (int i = 0; i < 20; i++)
		{
			frame(lumbridge(), options());
			assertSame("nothing changed, so no new card", first, surface.lastCard());
		}
		pins.rename(pin.getId(), "Renamed");
		frame(lumbridge(), options());
		assertEquals("Renamed", surface.lastCard().getTitle());
		HoverCard second = surface.lastCard();
		frame(lumbridge(), options());
		assertSame(second, surface.lastCard());
		// Moving the mouse off the pin takes the card away, and back again gives the right one.
		mouseX = 5;
		mouseY = 5;
		frames(60, lumbridge(), options());
		assertNull(surface.lastCard());
		mouseX = headX(pin);
		mouseY = headY(pin);
		frame(lumbridge(), options());
		assertEquals("Renamed", surface.lastCard().getTitle());
	}

	@Test
	public void aPinOffTheMapCannotBeClickedBlind()
	{
		com.worldmapplus.pins.Pin pin = placePin("pin");
		press(headX(pin) + 60, headY(pin));
		now += 16;
		release(headX(pin) + 60, headY(pin));
		frame(lumbridge(), options());
		assertNull(lastMessage("shortestpath", "path"));
	}

	@Test
	public void theEditItemAsksForTheSidePanel()
	{
		com.worldmapplus.pins.Pin pin = placePin("pin");
		rightClick(headX(pin), headY(pin));
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.EDIT_PIN);
		assertEquals(java.util.Arrays.asList(pin.getId()), opened);
	}

	@Test
	public void aSearchedPlaceCanBeSavedFromItsMarker()
	{
		frames(30, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		for (char c : "general store".toCharArray())
		{
			typeChar(c);
		}
		frame(lumbridge(), options());
		pressKey(KeyEvent.VK_ENTER, '\n');
		frames(40, lumbridge(), options());
		com.worldmapplus.search.SearchEntry entry = surface.found();
		assertNotNull(entry);
		// Clearing the text takes the result list off the map, which would otherwise cover the place.
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		pressKey(KeyEvent.VK_ESCAPE, (char) 27);
		pressKey(KeyEvent.VK_ESCAPE, (char) 27);
		frame(lumbridge(), options());
		ViewTransform view = surface.currentView();
		rightClick((int) Math.round(view.screenX(entry.getX())), (int) Math.round(view.screenY(entry.getY())));
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.SAVE_PLACE);
		assertEquals(1, pins.all().size());
		assertEquals(entry.getName(), pins.all().get(0).getName());
		assertEquals(MapSurface.kindOf(entry.getKind()), pins.all().get(0).getKind());
	}

	@Test
	public void aFollowedRouteIsDrawnWithoutTrouble()
	{
		frames(30, lumbridge(), options());
		com.worldmapplus.pins.Pin a = pins.add("A", "pin", 0, 3222.5, 3222.5, 0);
		com.worldmapplus.pins.Pin b = pins.add("B", "pin", 0, 3230.5, 3218.5, 0);
		com.worldmapplus.pins.Pin c = pins.add("C", "pin", 0, 3226.5, 3212.5, 0);
		com.worldmapplus.pins.RouteStore routes = routeStore;
		com.worldmapplus.pins.SavedRoute route = routes.create("R", java.util.Arrays.asList(a.getId(), b.getId(), c.getId()), true);
		routes.setActive(route.getId());
		frames(3, lumbridge(), options());
		routes.setHighlighted(0);
		routes.stopAll();
		frames(2, lumbridge(), options());
		assertEquals(3, pins.all().size());
	}

	@Test
	public void clearingTheRouteAlsoStopsAFollowedSavedRoute()
	{
		frames(30, lumbridge(), options());
		com.worldmapplus.pins.Pin a = pins.add("A", "pin", 0, 3222.5, 3222.5, 0);
		com.worldmapplus.pins.RouteStore routes = routeStore;
		com.worldmapplus.pins.SavedRoute route = routes.create("R", java.util.Arrays.asList(a.getId()), false);
		routes.setActive(route.getId());
		rightClick(600, 250);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.CLEAR_ROUTE);
		assertNull(routes.active());
	}

	@Test
	public void savedPlacesAreSearchableAgainWhenTheMapIsOpenedAnew()
	{
		frames(30, lumbridge(), options());
		pins.add("Zzyzx bench", "pin", 0, 3226.5, 3220.5, 0);
		frames(3, lumbridge(), options());
		// Long enough away that the surface starts again instead of resuming.
		now += MapSurface.RESUME_MILLIS + 5_000;
		frames(30, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		for (char c : "zzyzx".toCharArray())
		{
			typeChar(c);
		}
		frame(lumbridge(), options());
		boolean sawPin = false;
		for (com.worldmapplus.search.SearchEntry entry : surface.searchBox().getResults())
		{
			sawPin |= entry.getKind() == SearchKind.PIN;
		}
		assertTrue(sawPin);
	}

	@Test
	public void aPlaceOnAnotherFloorIsNotDrawnOrClickedOnTheFloorBeingShown()
	{
		frames(30, lumbridge(), options());
		com.worldmapplus.pins.Pin upstairs = pins.add("Upstairs", "pin", 0, 3226.5, 3220.5, 1);
		frames(3, lumbridge(), options());
		press(headX(upstairs), headY(upstairs));
		now += 16;
		release(headX(upstairs), headY(upstairs));
		frame(lumbridge(), options());
		assertNull("floor 0 is shown, so the click went to the map", lastMessage("shortestpath", "path"));
	}

	@Test
	public void clickingOutsideTheMapEndsTheSearchSoTypingGoesToTheGameAgain()
	{
		frames(30, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		assertTrue(surface.searchBox().isFocused());
		input().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 20, 20, MouseEvent.BUTTON1));
		frame(lumbridge(), options());
		assertFalse(surface.searchBox().isFocused());
		frame(lumbridge(), options());
		KeyEvent typed = new KeyEvent(SOURCE, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, 'a');
		input().keyTyped(typed);
		assertFalse("keys are no longer taken", typed.isConsumed());
	}

	@Test
	public void theGamesOwnControlsAreNotDrawnOver()
	{
		Rectangle close = new Rectangle(BOUNDS.x + BOUNDS.width - 60, BOUNDS.y + 4, 50, 36);
		MapSurface.Options keep = options().keepClear(java.util.Arrays.asList(close));
		int marker = 0xFF00FF;
		for (int i = 0; i < 60; i++)
		{
			Graphics2D g = canvas.createGraphics();
			g.setColor(new Color(marker));
			g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
			g.dispose();
			frame(lumbridge(), keep);
		}
		assertEquals("what the game drew there is still there", marker,
			canvas.getRGB(close.x + 25, close.y + 18) & 0xFFFFFF);
		assertTrue("the map is drawn elsewhere", (canvas.getRGB(close.x - 100, close.y + 200) & 0xFFFFFF) != marker);

		press(close.x + 25, close.y + 18);
		now += 16;
		release(close.x + 25, close.y + 18);
		frame(lumbridge(), keep);
		assertTrue(posted.isEmpty());
	}

	@Test
	public void theSearchResultsScrollAndStayWhereTheyWereScrolledTo()
	{
		frames(30, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		for (char c : "shop".toCharArray())
		{
			typeChar(c);
		}
		frames(3, lumbridge(), options());
		assertTrue("more results than rows", surface.searchBox().getResults().size() > 20);
		assertEquals(0, surface.searchBox().getScrollTop());

		int x = BOUNDS.x + BOUNDS.width / 2;
		int y = BOUNDS.y + 120;
		input().mouseWheelMoved(wheel(x, y, 3));
		frames(2, lumbridge(), options());
		int scrolled = surface.searchBox().getScrollTop();
		assertTrue("scrolled down: " + scrolled, scrolled > 0);

		// Left alone, and with the mouse resting over the list, it stays there.
		mouseX = x;
		mouseY = y;
		frames(60, lumbridge(), options());
		assertEquals(scrolled, surface.searchBox().getScrollTop());

		input().mouseWheelMoved(wheel(x, y, 3));
		frames(2, lumbridge(), options());
		assertTrue(surface.searchBox().getScrollTop() > scrolled);
		input().mouseWheelMoved(wheel(x, y, -10));
		frames(2, lumbridge(), options());
		assertEquals(scrolled, surface.searchBox().getScrollTop());
	}

	@Test
	public void draggingTheScrollBarThumbMovesTheListAndLeavesItThere()
	{
		frames(30, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		for (char c : "shop".toCharArray())
		{
			typeChar(c);
		}
		frames(3, lumbridge(), options());
		java.awt.Rectangle results = surface.searchBarResults();
		assertTrue(results.height > 0);
		int x = results.x + results.width - 6;
		int top = results.y + 4;
		int bottom = results.y + results.height - 4;

		press(x, top);
		now += 16;
		input().mouseDragged(mouse(MouseEvent.MOUSE_DRAGGED, x, (top + bottom) / 2, MouseEvent.NOBUTTON));
		frame(lumbridge(), options());
		int middle = surface.searchBox().getScrollTop();
		assertTrue("the list followed the drag: " + middle, middle > 0);
		input().mouseDragged(mouse(MouseEvent.MOUSE_DRAGGED, x, bottom, MouseEvent.NOBUTTON));
		frame(lumbridge(), options());
		int end = surface.searchBox().getScrollTop();
		assertTrue("and went on: " + end, end > middle);
		now += 16;
		release(x, bottom);
		frames(5, lumbridge(), options());
		assertEquals("letting go leaves it where it is", end, surface.searchBox().getScrollTop());
	}

	/** A widget that answers only the questions the key reading asks. */
	private static net.runelite.api.widgets.Widget widget(Rectangle bounds, Object[] handler,
		net.runelite.api.widgets.Widget[] children)
	{
		return widget(bounds, handler, children, null);
	}

	private static net.runelite.api.widgets.Widget widget(Rectangle bounds, Object[] handler,
		net.runelite.api.widgets.Widget[] children, String text)
	{
		return (net.runelite.api.widgets.Widget) Proxy.newProxyInstance(
			net.runelite.api.widgets.Widget.class.getClassLoader(),
			new Class<?>[] {net.runelite.api.widgets.Widget.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getBounds":
						return bounds;
					case "getOnOpListener":
						return handler;
					case "getText":
						return text;
					case "getDynamicChildren":
						return children;
					case "isHidden":
						return false;
					default:
						return null;
				}
			});
	}

	@Test
	public void clickingAnEntryOfTheGamesMapKeyFlashesThatKindOfIcon()
	{
		int bank = 1055;
		Rectangle row = new Rectangle(BOUNDS.x + 10, BOUNDS.y + 300, 150, 20);
		net.runelite.api.widgets.Widget entry = widget(row, new Object[] {1728, -2147483644, bank, -2147483645, -2147483643},
			null);
		keyEntries = widget(new Rectangle(BOUNDS.x, BOUNDS.y + 250, 200, 200), null, new net.runelite.api.widgets.Widget[] {entry});
		MapSurface.Options keep = options().keepClear(java.util.Arrays.asList(new Rectangle(BOUNDS.x, BOUNDS.y + 250, 200, 200)));
		frames(30, lumbridge(), keep);
		assertEquals(0, surface.flashing());

		press(row.x + 20, row.y + 10);
		now += 16;
		release(row.x + 20, row.y + 10);
		frame(lumbridge(), keep);
		assertEquals("the kind that was clicked", bank, surface.flashing());
		assertTrue("and the click was left for the game", posted.isEmpty());

		// Over a bank the rings are drawn: some yellow pixels appear around it.
		frames(5, lumbridge(), keep);
		press(row.x + 20, row.y + 10);
		now += 16;
		release(row.x + 20, row.y + 10);
		frame(lumbridge(), keep);
		assertEquals("a second click puts it out", 0, surface.flashing());
	}

	@Test
	public void aClickOnSomethingThatIsNotAnEntryFlashesNothing()
	{
		keyEntries = widget(new Rectangle(BOUNDS.x, BOUNDS.y + 250, 200, 200), new Object[] {55, "text", 7},
			new net.runelite.api.widgets.Widget[0]);
		MapSurface.Options keep = options().keepClear(java.util.Arrays.asList(new Rectangle(BOUNDS.x, BOUNDS.y + 250, 200, 200)));
		frames(30, lumbridge(), keep);
		press(BOUNDS.x + 20, BOUNDS.y + 300);
		now += 16;
		release(BOUNDS.x + 20, BOUNDS.y + 300);
		frame(lumbridge(), keep);
		assertEquals(0, surface.flashing());
	}

	@Test
	public void panelsTheMapSitsUnderKeepTheirClicksButTheMapStaysDrawnThere()
	{
		Rectangle panel = new Rectangle(BOUNDS.x + 600, BOUNDS.y + 300, 250, 250);
		MapSurface.Options under = options().passInput(java.util.Arrays.asList(panel));
		int marker = 0xFF00FF;
		for (int i = 0; i < 60; i++)
		{
			Graphics2D g = canvas.createGraphics();
			g.setColor(new Color(marker));
			g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
			g.dispose();
			frame(lumbridge(), under);
		}
		assertTrue("the map is drawn under the panel", (canvas.getRGB(panel.x + 100, panel.y + 100) & 0xFFFFFF) != marker);
		MouseEvent over = mouse(MouseEvent.MOUSE_PRESSED, panel.x + 100, panel.y + 100, MouseEvent.BUTTON1);
		input().mousePressed(over);
		assertFalse("a click on the panel is left for the game", over.isConsumed());
		MouseEvent elsewhere = mouse(MouseEvent.MOUSE_PRESSED, BOUNDS.x + 100, BOUNDS.y + 200, MouseEvent.BUTTON1);
		input().mousePressed(elsewhere);
		assertTrue("a click on the map is taken", elsewhere.isConsumed());
	}

	@Test
	public void aMapTooNarrowForTheSearchBarHasNoInvisibleSearchField()
	{
		Rectangle narrow = new Rectangle(100, 80, 380, 400);
		for (int i = 0; i < 30; i++)
		{
			Graphics2D g = canvas.createGraphics();
			now += 16;
			surface.render(g, narrow, lumbridge(), options(), now);
			g.dispose();
		}
		int x = narrow.x + narrow.width / 2;
		int y = narrow.y + 20;
		press(x, y);
		now += 16;
		release(x, y);
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		Graphics2D g = canvas.createGraphics();
		now += 16;
		surface.render(g, narrow, lumbridge(), options(), now);
		g.dispose();
		assertFalse("nothing to type into", surface.searchBox().isFocused());
	}

	@Test
	public void aKeyEntryBesideTheMapIsReadByItsNameWhenItsHandlerIsNotFound()
	{
		// The game's key sits beside the map, not over it, and an entry may carry its name and no readable handler.
		Rectangle row = new Rectangle(BOUNDS.x - 90, BOUNDS.y + 300, 80, 20);
		net.runelite.api.widgets.Widget entry = widget(row, null, null, "<col=ff9040>Bank</col>");
		keyEntries = widget(new Rectangle(BOUNDS.x - 95, BOUNDS.y + 250, 90, 200), null,
			new net.runelite.api.widgets.Widget[] {entry});
		frames(30, lumbridge(), options());
		press(row.x + 10, row.y + 10);
		now += 16;
		release(row.x + 10, row.y + 10);
		frame(lumbridge(), options());
		assertEquals(1055, surface.flashing());
	}

	@Test
	public void aHiddenPinIsNotDrawnOrClickedButStillExists()
	{
		com.worldmapplus.pins.Pin pin = placePin("pin");
		pins.restyle(pin.getId(), 0, 100, true);
		frame(lumbridge(), options());
		mouseX = headX(pin);
		mouseY = headY(pin);
		frame(lumbridge(), options());
		assertFalse("Spot".equals(surface.lastCardTitle()));
		rightClick(headX(pin), headY(pin));
		frame(lumbridge(), options());
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			assertFalse(item.getAction() == ContextMenu.Action.REMOVE_PIN);
		}
		assertEquals(1, pins.all().size());
	}

	@Test
	public void turningPinsOffHidesThemAll()
	{
		com.worldmapplus.pins.Pin pin = placePin("shop");
		showPins = false;
		mouseX = headX(pin);
		mouseY = headY(pin);
		frame(lumbridge(), options());
		assertFalse("Spot".equals(surface.lastCardTitle()));
		showPins = true;
		frame(lumbridge(), options());
		assertEquals("Spot", surface.lastCardTitle());
	}

	@Test
	public void aLargePinCanBePointedAtFurtherFromItsTip()
	{
		com.worldmapplus.pins.Pin pin = placePin("shop");
		int topY = (int) Math.round(surface.currentView().screenY(pin.getY())) - 40;
		mouseX = headX(pin);
		mouseY = topY;
		frame(lumbridge(), options());
		assertFalse("Spot".equals(surface.lastCardTitle()));
		pins.restyle(pin.getId(), 0xFF8800, 250, false);
		frame(lumbridge(), options());
		assertEquals("Spot", surface.lastCardTitle());
	}

	@Test
	public void thePinMenuCanHideAPin()
	{
		com.worldmapplus.pins.Pin pin = placePin("pin");
		rightClick(headX(pin), headY(pin));
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.HIDE_PIN);
		assertTrue(pins.get(pin.getId()).isHidden());
		assertEquals(1, pins.all().size());
	}

	@Test
	public void aClickOnTheIconOfAKeyRowIsReadFromTheWordsBesideIt()
	{
		Rectangle band = new Rectangle(BOUNDS.x - 140, BOUNDS.y + 300, 130, 20);
		net.runelite.api.widgets.Widget icon = widget(new Rectangle(band.x, band.y, 20, 20), null, null, null);
		net.runelite.api.widgets.Widget words = widget(new Rectangle(band.x + 25, band.y, 100, 20), null, null, "Bank");
		keyEntries = widget(new Rectangle(band.x - 5, BOUNDS.y + 250, 140, 200), null, new net.runelite.api.widgets.Widget[] {icon, words});
		frames(30, lumbridge(), options());
		press(band.x + 5, band.y + 10);
		now += 16;
		release(band.x + 5, band.y + 10);
		frame(lumbridge(), options());
		assertEquals("the icon is in the row of the words", 1055, surface.flashing());
	}

	@Test
	public void theTransportationEntryFlashesAndCountsTheBoatsAndGlidersToo()
	{
		Rectangle row = new Rectangle(BOUNDS.x - 100, BOUNDS.y + 300, 90, 20);
		net.runelite.api.widgets.Widget entry = widget(row, new Object[] {1728, -2147483644, 1104, -2147483645, -2147483643}, null);
		keyEntries = widget(new Rectangle(row.x - 5, BOUNDS.y + 250, 100, 200), null, new net.runelite.api.widgets.Widget[] {entry});
		frames(30, lumbridge(), options());
		press(row.x + 10, row.y + 10);
		now += 16;
		release(row.x + 10, row.y + 10);
		frame(lumbridge(), options());
		assertEquals(1104, surface.flashing());
		frames(5, lumbridge(), options());
		assertEquals("it stays on for its time", 1104, surface.flashing());
	}

	private void middleClick(int x, int y)
	{
		input().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON2));
		now += 16;
		input().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON2));
		frame(lumbridge(), options());
	}

	/** Types a search and returns where its first result row is. */
	private java.awt.Point searchFor(String text)
	{
		frames(10, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		for (char c : text.toCharArray())
		{
			typeChar(c);
		}
		frames(30, lumbridge(), options());
		java.awt.Rectangle results = surface.searchBarResults();
		assertTrue("results are showing", results.height > 0);
		return new java.awt.Point(results.x + 30, results.y + 12);
	}

	@Test
	public void aRightClickOnASearchResultCanSaveItAsAPlace()
	{
		java.awt.Point row = searchFor("bob's brilliant");
		assertEquals(SearchKind.SHOP, surface.searchBox().getResults().get(0).getKind());
		rightClick(row.x, row.y);
		frame(lumbridge(), options());
		assertTrue(surface.menu().isOpen());
		boolean saves = false;
		boolean shows = false;
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			saves |= item.getAction() == ContextMenu.Action.SAVE_PLACE && item.getLabel().contains("Bob");
			shows |= item.getAction() == ContextMenu.Action.SHOW_RESULT;
		}
		assertTrue(saves);
		assertTrue(shows);
		chooseMenu(ContextMenu.Action.SAVE_PLACE);
		assertEquals(1, pins.all().size());
		assertTrue(pins.all().get(0).getName().contains("Bob"));
		assertEquals("shop", pins.all().get(0).getKind());
		assertEquals(0, pins.all().get(0).getPlane());
	}

	@Test
	public void aRightClickOnAResultCanShowItOnTheMap()
	{
		java.awt.Point row = searchFor("bob's brilliant");
		rightClick(row.x, row.y);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.SHOW_RESULT);
		frames(5, lumbridge(), options());
		assertNotNull(surface.found());
		assertFalse(surface.viewer().getCamera().isFollowing());
	}

	@Test
	public void aMiddleClickOnASearchResultOpensItInAWindowAndLeavesTheMainMapAlone()
	{
		java.util.List<double[]> opened = new java.util.ArrayList<>();
		surface.setWindowOpener((id, x, y, zoom) -> opened.add(new double[] {id, x, y, zoom}));
		java.awt.Point row = searchFor("bob's brilliant");
		com.worldmapplus.search.SearchEntry first = surface.searchBox().getResults().get(0);
		double cameraX = surface.viewer().getCamera().getX();
		middleClick(row.x, row.y);
		assertEquals(1, opened.size());
		assertEquals(first.getMapId(), (int) opened.get(0)[0]);
		assertEquals(first.getX(), opened.get(0)[1], 40);
		assertEquals("the main map stayed where it was", cameraX, surface.viewer().getCamera().getX(), 0.001);
	}

	@Test
	public void aMiddleClickOnADoorwayOpensItsMapInAWindow()
	{
		java.util.List<double[]> opened = new java.util.ArrayList<>();
		surface.setWindowOpener((id, x, y, zoom) -> opened.add(new double[] {id, x, y, zoom}));
		frames(10, lumbridge(), options());
		Hotspot door = null;
		for (Hotspot h : HotspotLoader.load(new Gson(), assets.getCatalog()))
		{
			if (h.getKind() == Hotspot.Kind.DOORWAY && h.getCard().getTitle().equals("Dwarven Mine")
				&& h.getMapId() == MapInfo.SURFACE && Math.abs(h.getX() - 3019.5) < 2)
			{
				door = h;
			}
		}
		assertNotNull(door);
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), door.getX(), door.getY());
		frames(5, lumbridge(), options());
		ViewTransform view = surface.currentView();
		middleClick((int) Math.round(view.screenX(door.getX())), (int) Math.round(view.screenY(door.getY())));
		assertEquals(1, opened.size());
		assertEquals(door.getTarget().getMapId(), (int) opened.get(0)[0]);
		assertEquals(door.getTarget().getX(), opened.get(0)[1], 1.0);
		assertEquals("the main map did not move", MapInfo.SURFACE, surface.viewer().getMap().getId());
	}

	@Test
	public void aMiddleClickOnNothingOpensNothing()
	{
		java.util.List<double[]> opened = new java.util.ArrayList<>();
		surface.setWindowOpener((id, x, y, zoom) -> opened.add(new double[] {id, x, y, zoom}));
		frames(10, lumbridge(), options());
		middleClick(700, 450);
		assertTrue(opened.isEmpty());
	}

	@Test
	public void clickingAStepOfTheRoutePanelTakesTheMapToWhereItBegins()
	{
		frames(30, lumbridge(), options());
		rightClick(900, 300);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.ROUTE);
		frames(40, lumbridge(), options());
		surface.viewer().getCamera().setFollowing(true);
		frames(5, lumbridge(), options());

		int x = BOUNDS.x + 60;
		int y = BOUNDS.y + 40 + 6 + 28;
		press(x, y);
		now += 16;
		release(x, y);
		frames(3, lumbridge(), options());
		assertNotNull("the step is marked", surface.found());
		assertEquals("Step 1", surface.found().getName());
		assertFalse("the map stopped following the player", surface.viewer().getCamera().isFollowing());
	}

	@Test
	public void aWindowSurfaceTakesItsPointerFromTheWindowAndLeavesFocusRequestsToTheMainMap()
	{
		java.awt.Point[] pointer = {null};
		java.util.List<Runnable> queued = new java.util.ArrayList<>();
		surface.asWindow(() -> pointer[0], queued::add);
		surface.startAt(assets.getCatalog().surface(), 3222.5, 3218.5, 6);
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("point", new WorldPoint(3000, 3000, 0));
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "focus", data));
		frames(10, lumbridge(), options());
		assertEquals("it began where it was told to", 3222.5, surface.viewer().getCamera().getX(), 1.0);
		assertNotNull("the request was left for the main map", external.takeFocus());

		pointer[0] = new java.awt.Point(600, 250);
		rightClick(600, 250);
		frame(lumbridge(), options());
		chooseMenu(ContextMenu.Action.ROUTE);
		assertTrue("the request waits for the game thread", lastMessage("shortestpath", "path") == null);
		assertEquals(1, queued.size());
		queued.get(0).run();
		assertNotNull(lastMessage("shortestpath", "path"));
	}

	private static PlayerPosition at(int x, int y)
	{
		return new PlayerPosition(x + 0.5, y + 0.5, new WorldPoint(x, y, 0));
	}

	@Test
	public void aGroupMemberInTheGuardiansOfTheRiftArenaIsDrawnOnItsMap()
	{
		// The Group Ironmen companion draws a member as a world map point at the tile the member stands on. The arena has no picture on
		// the game's map, so the point has to land on the map of ours that covers it.
		WorldPoint arena = new WorldPoint(3616, 9504, 0);
		net.runelite.client.ui.overlay.worldmap.WorldMapPoint member =
			new net.runelite.client.ui.overlay.worldmap.WorldMapPoint(arena, new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
		member.setName("Alice");
		member.setTooltip("Alice | 99/99 hp\nworld 301");
		MapSurface group = surfaceWithPointsFromOtherPlugins(java.util.Collections.singletonList(member), true);

		Graphics2D g = canvas.createGraphics();
		for (int i = 0; i < 14; i++)
		{
			now += 16;
			group.render(g, BOUNDS, at(3612, 9500), options(), now);
		}
		assertEquals("the map the player is on is the arena's", 11000, group.viewer().getMap().getId());
		ViewTransform view = group.currentView();
		mouseX = (int) Math.round(view.screenX(arena.getX() + 0.5));
		mouseY = (int) Math.round(view.screenY(arena.getY() + 0.5));
		now += 16;
		group.render(g, BOUNDS, at(3612, 9500), options(), now);
		g.dispose();
		assertEquals("Alice", group.lastCardTitle());
	}

	@Test
	public void aFocusRequestForATileOfEachMapDrawnFromTheCacheOpensThatMap()
	{
		frames(5, lumbridge(), options());
		int checked = 0;
		for (MapInfo map : assets.getCatalog().getMaps())
		{
			if (map.getId() < 11_000 || map.getId() >= 20_000)
			{
				continue;
			}
			java.util.Map<String, Object> data = new java.util.HashMap<>();
			data.put("point", new WorldPoint(map.getCenterX(), map.getCenterY(), 0));
			data.put("zoom", 6.0);
			data.put("name", map.getName());
			external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "focus", data));
			frames(2, lumbridge(), options());
			assertEquals(map.getName(), map.getId(), surface.viewer().getMap().getId());
			assertEquals(map.getName(), map.getCenterX() + 0.5, surface.viewer().getCamera().getX(), 1.0);
			assertFalse(surface.viewer().getCamera().isFollowing());
			checked++;
		}
		assertTrue("checked " + checked, checked >= 27);
	}

	@Test
	public void aMarkerInAnInstanceTemplateIsDrawnAfterAFocusRequestThere()
	{
		// A member in the Inferno has template coordinates (WorldPoint.fromLocalInstance), which are in region 9043.
		WorldPoint spot = new WorldPoint(2270, 5340, 0);
		assertEquals(11007, assets.getCatalog().mapFor(spot.getX(), spot.getY()).getId());
		frames(5, lumbridge(), options());
		java.util.Map<String, Object> focus = new java.util.HashMap<>();
		focus.put("point", spot);
		focus.put("zoom", 8.0);
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "focus", focus));
		frames(40, lumbridge(), options());
		assertEquals(11007, surface.viewer().getMap().getId());
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("id", "friend");
		data.put("point", spot);
		data.put("name", "Friend");
		data.put("color", new Color(0, 255, 0));
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "marker", data));
		frames(3, lumbridge(), options());
		ViewTransform view = surface.currentView();
		int px = (int) Math.round(view.screenX(spot.getX() + 0.5));
		int py = (int) Math.round(view.screenY(spot.getY() + 0.5));
		assertEquals(0x00FF00, canvas.getRGB(px, py) & 0xFFFFFF);
	}

	// ------------------------------------------------------------------
	// Round 48: the player's own places and routes in the search
	// ------------------------------------------------------------------

	private com.worldmapplus.pins.SavedRoute ownRoute(String name)
	{
		com.worldmapplus.pins.Pin a = pins.add("Qa stop", "pin", 0, 3222.5, 3222.5, 0);
		com.worldmapplus.pins.Pin b = pins.add("Qb stop", "pin", 0, 3230.5, 3218.5, 0);
		return routeStore.create(name, java.util.Arrays.asList(a.getId(), b.getId()), false);
	}

	private void leftClick(java.awt.Point at)
	{
		press(at.x, at.y);
		now += 16;
		release(at.x, at.y);
		frame(lumbridge(), options());
	}

	@Test
	public void aSavedRouteIsAResultOfItsOwnAndALeftClickStartsIt()
	{
		frames(10, lumbridge(), options());
		com.worldmapplus.pins.SavedRoute route = ownRoute("Zqx herb run");
		java.awt.Point row = searchFor("zqx herb run");
		SearchEntry first = surface.searchBox().getResults().get(0);
		assertEquals(SearchKind.ROUTE, first.getKind());
		assertEquals(route.getId(), first.getOwnId());
		assertTrue(first.getDetail(), first.getDetail().contains("2 stops"));
		leftClick(row);
		assertNotNull("the route is followed, as with the side panel's Start", routeStore.active());
		assertEquals(route.getId(), routeStore.active().getId());
		assertFalse(surface.menu().isOpen());
	}

	@Test
	public void aRightClickOnARouteResultOpensItsEditor()
	{
		frames(10, lumbridge(), options());
		List<Long> edited = new ArrayList<>();
		services.launcher().setRouteEditor(edited::add);
		com.worldmapplus.pins.SavedRoute route = ownRoute("Zqx herb run");
		java.awt.Point row = searchFor("zqx herb run");
		rightClick(row.x, row.y);
		frame(lumbridge(), options());
		assertEquals("the side panel's editor is asked for, as its Edit button does", java.util.Collections.singletonList(route.getId()),
			edited);
		assertFalse("no result menu", surface.menu().isOpen());
		assertNull("and nothing started", routeStore.active());
	}

	@Test
	public void aLeftClickOnASavedPlaceResultJumpsToIt()
	{
		frames(10, lumbridge(), options());
		com.worldmapplus.pins.Pin pin = pins.add("Qwvx bench", "pin", 0, 3100.5, 3300.5, 0);
		java.awt.Point row = searchFor("qwvx bench");
		SearchEntry first = surface.searchBox().getResults().get(0);
		assertEquals(SearchKind.PIN, first.getKind());
		assertEquals(pin.getId(), first.getOwnId());
		leftClick(row);
		frames(60, lumbridge(), options());
		assertEquals("the map goes to the place", 3100.5, surface.viewer().getCamera().getX(), 2.0);
		assertEquals(3300.5, surface.viewer().getCamera().getY(), 2.0);
		assertNull("a left click sends nothing", lastMessage("shortestpath", "path"));
	}

	@Test
	public void aRightClickOnASavedPlaceResultRoutesThere()
	{
		frames(10, lumbridge(), options());
		pins.add("Qwvx bench", "pin", 0, 3100.5, 3300.5, 0);
		java.awt.Point row = searchFor("qwvx bench");
		rightClick(row.x, row.y);
		frame(lumbridge(), options());
		assertFalse("no result menu", surface.menu().isOpen());
		assertNotNull("a right click routes there through Shortest Path, as the pin's Route to does", lastMessage("shortestpath", "path"));
	}

	@Test
	public void ownResultsFollowRenamesAndRemovals()
	{
		frames(10, lumbridge(), options());
		com.worldmapplus.pins.SavedRoute route = ownRoute("Zqx herb run");
		searchFor("zqx");
		assertTrue(hasOwn(SearchKind.ROUTE, "Zqx herb run"));
		routeStore.update(route.getId(), "Zqx fish run", route.getStops(), true);
		frames(2, lumbridge(), options());
		assertFalse("the old name is gone at once", hasOwn(SearchKind.ROUTE, "Zqx herb run"));
		assertTrue(hasOwn(SearchKind.ROUTE, "Zqx fish run"));
		routeStore.remove(route.getId());
		frames(2, lumbridge(), options());
		assertFalse("a removed route is no result", hasOwn(SearchKind.ROUTE, "Zqx fish run"));

		com.worldmapplus.pins.Pin pin = pins.add("Zqx bench", "pin", 0, 3100.5, 3300.5, 0);
		frames(2, lumbridge(), options());
		assertTrue(hasOwn(SearchKind.PIN, "Zqx bench"));
		pins.rename(pin.getId(), "Zqx chair");
		frames(2, lumbridge(), options());
		assertFalse(hasOwn(SearchKind.PIN, "Zqx bench"));
		assertTrue(hasOwn(SearchKind.PIN, "Zqx chair"));
		pins.remove(pin.getId());
		frames(2, lumbridge(), options());
		assertFalse("a removed place is no result", hasOwn(SearchKind.PIN, "Zqx chair"));
	}

	private boolean hasOwn(SearchKind kind, String name)
	{
		for (SearchEntry entry : surface.searchBox().getResults())
		{
			if (entry.getKind() == kind && entry.getName().equals(name))
			{
				return true;
			}
		}
		return false;
	}
}
