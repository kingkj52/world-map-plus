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
import com.worldmapplus.bridge.PartnerDouble;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.data.HotspotLoader;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.input.ViewerInput;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
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
		SurfaceServices services = new SurfaceServices(memory, pins, external, new MapActions(bus, external),
			settings, routeStore, launcher);
		this.services = services;
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
		};
		surface = new MapSurface(client, assets, new WorldMapPlusConfig()
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
	public void searchingForATeleportTakesTheMapThere()
	{
		frames(10, lumbridge(), options());
		input().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frame(lumbridge(), options());
		assertTrue(surface.searchBox().isFocused());

		for (char c : "varrock teleport".toCharArray())
		{
			typeChar(c);
		}
		frame(lumbridge(), options());
		assertFalse(surface.searchBox().getResults().isEmpty());
		assertEquals(SearchKind.TELEPORT, surface.searchBox().getResults().get(0).getKind());

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
		java.util.List<double[]> places = new ArrayList<>();
		places.add(new double[] {3253.5, 3420.5});
		places.add(new double[] {3227.5, 3218.5});
		places.add(new double[] {3185.5, 3436.5});
		SearchEntry banks = new SearchEntry(SearchKind.KIND, "Banks", "Every bank", 0, 3253.5, 3420.5, places);
		banks.setReveal("Bank");
		now += 400;
		surface.activateForTest(banks, now);
		SearchEntry found = surface.found();
		assertEquals(0, found.getMapId());
		assertEquals("every place of the result is marked", 3, found.getAlsoAt().size());
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
		java.util.List<double[]> places = new ArrayList<>();
		places.add(new double[] {3185.5, 3436.5});
		places.add(new double[] {3227.5, 3218.5});
		SearchEntry banks = new SearchEntry(SearchKind.KIND, "Banks", "Every bank", 0, 3185.5, 3436.5, places);
		banks.setReveal("Bank");
		return banks;
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

	/** What a partner's panel (the Helper's results panel) saw and was sent. */
	private static final class PanelProbe
	{
		int surface = -1;
		int frames;
		final int[] limits = new int[2];
		final List<String> events = new ArrayList<>();
		boolean wanted = true;
	}

	private PanelProbe registerPanel(PartnerDouble partner)
	{
		PanelProbe probe = new PanelProbe();
		partner.hello("part two");
		partner.register("painter", "results", 1, (java.util.function.BiConsumer<Graphics2D, java.util.Map<String, Object>>) (g2, frame) ->
		{
			probe.surface = (Integer) frame.get("surface");
			probe.frames++;
			int[] limits = (int[]) frame.get("limits");
			probe.limits[0] = limits[0];
			probe.limits[1] = limits[1];
			int[] box = (int[]) frame.get("box");
			if (probe.wanted)
			{
				int[] map = (int[]) frame.get("map");
				box[0] = map[0] + 10;
				box[1] = limits[0];
				box[2] = 200;
				box[3] = 150;
			}
			else
			{
				box[2] = 0;
			}
		}, "layer", "screen", "input", (java.util.function.Function<java.util.Map<String, Object>, Boolean>) event ->
		{
			probe.events.add(event.get("type") + ":" + event.get("x") + ":" + event.get("y") + ":" + event.get("amount"));
			return !"drag".equals(event.get("type")) && !"press".equals(event.get("type"));
		});
		return probe;
	}

	@Test
	public void aPartnersPanelIsDrawnUnderTheRoutePanelAndTakesTheMouseOnlyInsideItsBox()
	{
		PartnerDouble partner = new PartnerDouble(external, "helper");
		PanelProbe probe = registerPanel(partner);
		frames(4, lumbridge(), still());
		assertTrue("the panel is drawn every frame", probe.frames >= 3);
		assertTrue("it may start below the top of the map", probe.limits[0] >= BOUNDS.y + 40);
		assertTrue("it may reach the buttons at the right at most", probe.limits[1] <= BOUNDS.x + BOUNDS.width);
		int x = BOUNDS.x + 60;
		int y = probe.limits[0] + 50;
		double before = surface.viewer().getCamera().getX();
		mouseX = x;
		mouseY = y;
		press(x, y);
		now += 16;
		release(x, y);
		frames(2, lumbridge(), still());
		assertTrue("a click inside the box is given to the panel: " + probe.events, probe.events.contains("click:" + x + ":" + y + ":0"));
		assertEquals("and is not a click on the map", before, surface.viewer().getCamera().getX(), 1e-9);
		probe.events.clear();
		input().mouseWheelMoved(wheel(x, y, 2));
		frames(2, lumbridge(), still());
		assertEquals(1, probe.events.size());
		assertTrue(probe.events.get(0).startsWith("wheel:" + x + ":" + y));
		probe.events.clear();
		int farX = BOUNDS.x + 600;
		mouseX = farX;
		mouseY = y;
		press(farX, y);
		now += 16;
		release(farX, y);
		frames(2, lumbridge(), still());
		assertTrue("a click elsewhere is not given to it: " + probe.events, probe.events.isEmpty());
		probe.wanted = false;
		frames(2, lumbridge(), still());
		probe.events.clear();
		press(x, y);
		now += 16;
		release(x, y);
		assertTrue("a panel that shows nothing takes no click", probe.events.isEmpty());
	}

	@Test
	public void aPanelDrawsNothingInClickThrough()
	{
		PartnerDouble partner = new PartnerDouble(external, "helper");
		PanelProbe probe = registerPanel(partner);
		frames(2, lumbridge(), still());
		int drawn = probe.frames;
		frames(3, lumbridge(), still().interactive(false));
		assertEquals("a map that draws no controls draws no panel", drawn, probe.frames);
	}

	@Test
	public void aChosenResultIsToldToThePanelAndAShowRequestTakesTheMapThereAndMarksTheTiles()
	{
		PartnerDouble partner = new PartnerDouble(external, "helper");
		PanelProbe probe = registerPanel(partner);
		frames(10, lumbridge(), still());
		partner.heard();
		now += 400;
		surface.activateForTest(kindEntry("bank"), now);
		java.util.Map<String, Object> told = null;
		for (ExternalOverlays.Reply reply : partner.heard())
		{
			if ("chosen".equals(reply.getData().get("topic")))
			{
				told = reply.getData();
			}
		}
		assertNotNull("the choice is told to the panel", told);
		assertEquals(probe.surface, told.get("surface"));
		java.util.Map<?, ?> entry = (java.util.Map<?, ?>) told.get("entry");
		assertEquals("KIND", entry.get("kind"));
		assertEquals("Banks", entry.get("name"));
		assertEquals(2, ((List<?>) entry.get("alsoAt")).size());
		partner.ask("show", "surface", probe.surface, "mapId", 0, "x", 3185.5, "y", 3436.5, "name", "Varrock", "detail", "2 banks",
			"marks", java.util.Arrays.asList(new double[] {3185.5, 3436.5}, new double[] {3189.5, 3436.5}));
		frames(2, lumbridge(), still());
		assertEquals("Varrock", surface.found().getName());
		assertEquals(2, surface.found().getAlsoAt().size());
		assertEquals(0, surface.found().getMapId());
		partner.ask("show", "surface", probe.surface + 1, "mapId", 0, "x", 3222.5, "y", 3218.5, "name", "Elsewhere");
		frames(2, lumbridge(), still());
		assertEquals("a request for another surface is not ours", "Varrock", surface.found().getName());
	}

	@Test
	public void theMapClosingTellsThePanelToGo()
	{
		PartnerDouble partner = new PartnerDouble(external, "helper");
		PanelProbe probe = registerPanel(partner);
		frames(3, lumbridge(), still());
		probe.events.clear();
		surface.reset();
		assertTrue(probe.events.toString(), probe.events.contains("close:0:0:0"));
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
		TestMarker.show(external, spot, "Friend", new Color(0, 255, 0));
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
		PartnerDouble.post(external, "request", "focus", data);
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
		PartnerDouble.post(external, "request", "focus", data);
		frames(40, lumbridge(), options());
		assertEquals(x, surface.viewer().getCamera().getX(), 0.01);
		assertEquals(y, surface.viewer().getCamera().getY(), 0.01);
		java.util.List<ExternalOverlays.Reply> replies = external.drainReplies(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("reply", replies.get(0).getName());
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
		PartnerDouble.post(external, "request", "focus", data);
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
	public void aFarmingPointShowsTheCardThePartnerGivesForThings()
	{
		// The Helper plugin owns the cards of farming points: it registers a card with an owns list and answers a body.
		com.worldmapplus.bridge.PartnerDouble helper = new com.worldmapplus.bridge.PartnerDouble(external, "helper");
		helper.hello("part two");
		java.util.List<java.util.Map<String, Object>> asked = new java.util.ArrayList<>();
		helper.register("card", "farming", 100, (java.util.function.Function<java.util.Map<String, Object>, Object>) thing ->
		{
			asked.add(thing);
			if (!Boolean.TRUE.equals(thing.get("body")))
			{
				return null;
			}
			java.util.Map<String, Object> body = new java.util.HashMap<>();
			body.put("title", "Falador");
			body.put("rows", java.util.Arrays.asList(java.util.Arrays.asList("Herb", "Ranarr, growing")));
			return body;
		}, "owns", java.util.Arrays.asList("farming"));
		SurfaceServices services = new SurfaceServices(new ViewMemory(null)
		{
			@Override
			public Saved load(String slot)
			{
				return null;
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), new com.google.gson.Gson());
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
		assertFalse("the partner was asked for the body of a thing of kind farming", asked.isEmpty());
		assertEquals("farming", asked.get(0).get("kind"));
	}

	

	@Test
	public void aShopIconShowsTheShopCardThePartnerGivesInPlaceOfItsPlainCard()
	{
		com.worldmapplus.data.MapElements.Spot shop = null;
		for (com.worldmapplus.data.MapElements.Spot spot : assets.getElements().spotsOn(0))
		{
			String name = assets.getElements().categoryName(spot.getCategory());
			if (shop == null && name != null && com.worldmapplus.data.MapElements.kindFor(name) == com.worldmapplus.layers.Hotspot.Kind.SHOP)
			{
				shop = spot;
			}
		}
		assertNotNull("the map has an icon of a shop", shop);
		Graphics2D g = canvas.createGraphics();
		frames(12, lumbridge(), options());
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), shop.getX(), shop.getY());
		surface.viewer().getZoom().jumpTo(6.0);
		frames(5, lumbridge(), options());
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(shop.getX()));
		mouseY = (int) Math.round(view.screenY(shop.getY()));
		frame(lumbridge(), options());
		String plain = surface.lastCardTitle();
		assertNotNull("the icon has its plain card", plain);

		com.worldmapplus.bridge.PartnerDouble helper = new com.worldmapplus.bridge.PartnerDouble(external, "helper");
		helper.hello("part two");
		java.util.List<java.util.Map<String, Object>> asked = new java.util.ArrayList<>();
		helper.register("card", "shop", 110, (java.util.function.Function<java.util.Map<String, Object>, Object>) thing ->
		{
			asked.add(thing);
			java.util.Map<String, Object> body = new java.util.HashMap<>();
			body.put("title", "Zed's Shop");
			body.put("rows", java.util.Arrays.asList(java.util.Arrays.asList("Cabbage", "1 coin")));
			return body;
		}, "owns", java.util.Arrays.asList("shop"));
		frame(lumbridge(), options());
		assertEquals("the partner's card replaces the plain one", "Zed's Shop", surface.lastCardTitle());
		assertFalse(asked.isEmpty());
		assertEquals("shop", asked.get(0).get("kind"));
		assertEquals(0, asked.get(0).get("mapId"));
		assertEquals(Boolean.TRUE, asked.get(0).get("body"));
		int before = asked.size();
		frames(5, lumbridge(), options());
		assertEquals("a resting pointer does not ask again", before, asked.size());
		g.dispose();
	}

	@Test
	public void aFocusRequestIsRememberedSoTheGameMapsFollowingCanBeHeldOff()
	{
		frames(5, lumbridge(), options());
		assertFalse(surface.focusedWithin(now, 2500));
		java.util.Map<String, Object> data = new java.util.HashMap<>();
		data.put("point", new WorldPoint(3040, 9820, 0));
		data.put("zoom", 6.0);
		PartnerDouble.post(external, "request", "focus", data);
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
		for (char c : "teleport".toCharArray())
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
		for (char c : "teleport".toCharArray())
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
		java.awt.Point row = searchFor("varrock teleport");
		assertEquals(SearchKind.TELEPORT, surface.searchBox().getResults().get(0).getKind());
		rightClick(row.x, row.y);
		frame(lumbridge(), options());
		assertTrue(surface.menu().isOpen());
		boolean saves = false;
		boolean shows = false;
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			saves |= item.getAction() == ContextMenu.Action.SAVE_PLACE && item.getLabel().contains("Varrock");
			shows |= item.getAction() == ContextMenu.Action.SHOW_RESULT;
		}
		assertTrue(saves);
		assertTrue(shows);
		chooseMenu(ContextMenu.Action.SAVE_PLACE);
		assertEquals(1, pins.all().size());
		assertTrue(pins.all().get(0).getName().contains("Varrock"));
		assertEquals("teleport", pins.all().get(0).getKind());
		assertEquals(0, pins.all().get(0).getPlane());
	}

	@Test
	public void aRightClickOnAResultCanShowItOnTheMap()
	{
		java.awt.Point row = searchFor("varrock teleport");
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
		java.awt.Point row = searchFor("varrock teleport");
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
		PartnerDouble.post(external, "request", "focus", data);
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
			PartnerDouble.post(external, "request", "focus", data);
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
		PartnerDouble.post(external, "request", "focus", focus);
		frames(40, lumbridge(), options());
		assertEquals(11007, surface.viewer().getMap().getId());
		TestMarker.show(external, spot, "Friend", new Color(0, 255, 0));
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
