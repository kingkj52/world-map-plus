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
import com.worldmapplus.bridge.NativePoints;

import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.PoiCategory;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Everything the map pops up over itself, at the sizes the floating map has: the layers list, the right click menu, the
 * cards and the tags. Each must stay inside the map, both ways, never go over the button that opened it nor over any other
 * control, and be usable when it is taller or wider than the room (scrolling, cut labels with the whole text in a tag).
 */
public class PopupLayoutTest
{
	private static final JPanel SOURCE = new JPanel();

	/** A plugin name far longer than any real one, to stress the width. */
	static final String LONG_PLUGIN = "The Longest Imaginable Plugin Name For The World Map Plugin With Extra Words Added";

	/** Real plugin names that put points on the world map, the longest first. */
	private static final String[] REAL_PLUGINS = {"Bounty Sailing Map", "Golems Don't Die", "Mahogany Homes", "Turael Skipping"};

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

	/** The sizes the floating map takes: the least it can be, then each setting in the fixed layout and in a large window. */
	static Map<String, Rectangle> sizes()
	{
		Map<String, Rectangle> sizes = new LinkedHashMap<>();
		sizes.put("smallest", new Rectangle(40, 30, HudLayout.MIN_WIDTH, HudLayout.MIN_HEIGHT));
		Rectangle fixed = new Rectangle(0, 0, 512, 334);
		sizes.put("small fixed", shifted(HudLayout.compute(fixed, WorldMapPlusConfig.HudSize.SMALL.percent(),
			WorldMapPlusConfig.HudCorner.TOP_LEFT)));
		sizes.put("medium fixed", shifted(HudLayout.compute(fixed, WorldMapPlusConfig.HudSize.MEDIUM.percent(),
			WorldMapPlusConfig.HudCorner.TOP_LEFT)));
		sizes.put("large fixed", shifted(HudLayout.compute(fixed, WorldMapPlusConfig.HudSize.LARGE.percent(),
			WorldMapPlusConfig.HudCorner.TOP_LEFT)));
		Rectangle window = new Rectangle(0, 0, 1400, 900);
		sizes.put("small", shifted(HudLayout.compute(window, WorldMapPlusConfig.HudSize.SMALL.percent(),
			WorldMapPlusConfig.HudCorner.TOP_LEFT)));
		sizes.put("medium", shifted(HudLayout.compute(window, WorldMapPlusConfig.HudSize.MEDIUM.percent(),
			WorldMapPlusConfig.HudCorner.TOP_LEFT)));
		sizes.put("large", shifted(HudLayout.compute(window, WorldMapPlusConfig.HudSize.LARGE.percent(),
			WorldMapPlusConfig.HudCorner.TOP_LEFT)));
		return sizes;
	}

	private static Rectangle shifted(Rectangle r)
	{
		return new Rectangle(r.x + 25, r.y + 15, r.width, r.height);
	}

	/** A surface being driven the way the game would, with a pointer that tests can move. */
	static final class Rig
	{
		MapSurface surface;
		Rectangle bounds;
		BufferedImage canvas = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
		long now = 9_000_000;
		int mouseX = -1;
		int mouseY = -1;
		final List<WorldMapPoint> points = new ArrayList<>();

		void frames(int count)
		{
			Graphics2D g = canvas.createGraphics();
			PlayerPosition player = new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
			for (int i = 0; i < count; i++)
			{
				now += 16;
				surface.render(g, bounds, player, new MapSurface.Options().followOnOpen(true).openZoom(4.0), now);
			}
			g.dispose();
		}

		MouseEvent mouse(int id, int x, int y, int button)
		{
			return new MouseEvent(SOURCE, id, now, 0, x, y, 1, false, button);
		}

		void click(int x, int y)
		{
			mouseX = x;
			mouseY = y;
			surface.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
			now += 16;
			surface.getInput().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1));
			frames(2);
		}

		void rightClick(int x, int y)
		{
			mouseX = x;
			mouseY = y;
			surface.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON3));
			now += 16;
			surface.getInput().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON3));
			frames(2);
		}

		void wheel(int x, int y, int rotation)
		{
			mouseX = x;
			mouseY = y;
			surface.getInput().mouseWheelMoved(new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, now, 0, x, y, 0, false,
				MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation));
			frames(2);
		}

		void openLayers()
		{
			Rectangle layers = surface.buttons().boundsOf(MapButtons.Button.LAYERS);
			click(layers.x + layers.width / 2, layers.y + layers.height / 2);
		}
	}

	private Rig rig(Rectangle bounds, String pluginName)
	{
		return rig(assets, bounds, pluginName);
	}

	static Rig rig(MapAssets assets, Rectangle bounds, String pluginName)
	{
		Rig rig = new Rig();
		rig.bounds = bounds;
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(rig.mouseX, rig.mouseY);
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
		if (pluginName != null)
		{
			for (int i = 0; i < 3; i++)
			{
				rig.points.add(new WorldMapPoint(new WorldPoint(3222 + i, 3218, 0), null)
				{
				});
			}
		}
		NativePoints nativePoints = new NativePoints(rig.points::forEach);

		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		SurfaceServices services = new SurfaceServices(new ViewMemory(null)
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
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), nativePoints,
			new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins),
			new com.worldmapplus.pins.PanelLauncher());
		rig.surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
		}, services);
		rig.surface.setNativeMap(new NativeMap()
		{
			@Override
			public View view()
			{
				return null;
			}

			@Override
			public void moveTo(int x, int y)
			{
			}
		});
		rig.frames(30);
		return rig;
	}

	/** Every button the map has laid out, floor buttons included. */
	private static List<Rectangle> controls(MapSurface surface)
	{
		List<Rectangle> all = new ArrayList<>();
		for (MapButtons.Button button : MapButtons.Button.values())
		{
			Rectangle r = surface.buttons().boundsOf(button);
			if (!r.isEmpty())
			{
				all.add(r);
			}
		}
		if (surface.floorButtons().isShown())
		{
			for (FloorButtons.Button button : FloorButtons.Button.values())
			{
				all.add(surface.floorButtons().boundsOf(button));
			}
		}
		return all;
	}

	@Test
	public void everyButtonIsInsideTheMapAndNoneOverlapsAnother()
	{
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rig rig = rig(size.getValue(), null);
			List<Rectangle> controls = controls(rig.surface);
			assertTrue(size.getKey() + ": the floor buttons are on show on the surface", rig.surface.floorButtons().isShown());
			for (int i = 0; i < controls.size(); i++)
			{
				assertTrue(size.getKey() + " button " + i + " " + controls.get(i) + " in " + size.getValue(),
					size.getValue().contains(controls.get(i)));
				for (int j = i + 1; j < controls.size(); j++)
				{
					assertFalse(size.getKey() + " buttons " + i + " and " + j + " overlap",
						controls.get(i).intersects(controls.get(j)));
				}
			}
		}
	}

	@Test
	public void theLayersListStaysInsideTheMapAndClearOfEveryControl()
	{
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rig rig = rig(size.getValue(), LONG_PLUGIN);
			rig.openLayers();
			LayerPanel panel = rig.surface.layerPanel();
			String name = size.getKey();
			assertTrue(name + ": the button opened the list", panel.isOpen());
			Rectangle list = panel.getBounds();
			assertTrue(name + ": " + list + " inside " + size.getValue(), size.getValue().contains(list));
			for (Rectangle control : controls(rig.surface))
			{
				assertFalse(name + ": the list " + list + " covers a control " + control, list.intersects(control));
			}
			Rectangle layers = rig.surface.buttons().boundsOf(MapButtons.Button.LAYERS);
			assertFalse(name + ": it covers its own button", list.intersects(layers));
			if (size.getValue().width >= 420)
			{
				assertFalse(name + ": it covers the search field", list.intersects(rig.surface.searchBar().getFieldRect()));
			}
			assertTrue(name + ": it can be closed with a cross inside it", list.contains(panel.closeRect()));
		}
	}

	@Test
	public void theLayersListScrollsToEveryRowWhenItIsTallerThanTheRoom()
	{
		boolean sawScroll = false;
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rig rig = rig(size.getValue(), LONG_PLUGIN);
			rig.openLayers();
			LayerPanel panel = rig.surface.layerPanel();
			String name = size.getKey();
			int rows = panel.getRows().size();
			assertTrue(name + ": there are plugin rows too", rows > 12);
			Rectangle list = panel.getBounds();
			int cx = list.x + list.width / 2;
			int cy = list.y + list.height / 2;
			double zoom = rig.surface.currentView().getPixelsPerTile();
			sawScroll |= panel.isScrollable();
			for (int row = 0; row < rows; row++)
			{
				int guard = 0;
				while (panel.rowRect(row) == null && guard++ < rows * 2)
				{
					rig.wheel(cx, cy, panel.rowRect(0) == null && rowIsAbove(panel, row) ? -1 : 1);
				}
				Rectangle at = panel.rowRect(row);
				assertNotNull(name + ": row " + row + " ('" + panel.getRows().get(row).getLabel() + "') cannot be reached", at);
				assertTrue(name + ": row " + row + " inside the list", panel.getBounds().contains(at));
			}
			assertEquals(name + ": the wheel over the list never zoomed the map", zoom,
				rig.surface.currentView().getPixelsPerTile(), 1e-9);
			assertTrue(name + ": the list is still where it was", rig.surface.layerPanel().getBounds().equals(list));
		}
		assertTrue("at least one size needed scrolling", sawScroll);
	}

	private static boolean rowIsAbove(LayerPanel panel, int row)
	{
		// Rows above the view are reached by scrolling back up: the first row in view says which way to go.
		for (int i = 0; i < panel.getRows().size(); i++)
		{
			if (panel.rowRect(i) != null)
			{
				return row < i;
			}
		}
		return false;
	}

	@Test
	public void theScrollBarCanBeDraggedToTheEnd()
	{
		Rig rig = rig(sizes().get("small fixed"), LONG_PLUGIN);
		rig.openLayers();
		LayerPanel panel = rig.surface.layerPanel();
		assertTrue(panel.isScrollable());
		Rectangle list = panel.getBounds();
		int x = list.x + list.width - Scroller.WIDTH / 2 - 3;
		int top = list.y + LayerPanel.PADDING + LayerPanel.TITLE_HEIGHT + 2;
		rig.mouseX = x;
		rig.mouseY = top;
		rig.surface.getInput().mousePressed(rig.mouse(MouseEvent.MOUSE_PRESSED, x, top, MouseEvent.BUTTON1));
		rig.frames(1);
		rig.surface.getInput().mouseDragged(rig.mouse(MouseEvent.MOUSE_DRAGGED, x, list.y + list.height - 2,
			MouseEvent.NOBUTTON));
		rig.frames(1);
		rig.surface.getInput().mouseReleased(rig.mouse(MouseEvent.MOUSE_RELEASED, x, list.y + list.height - 2,
			MouseEvent.BUTTON1));
		rig.frames(2);
		assertTrue("the list is still open after the bar was let go", panel.isOpen());
		int last = panel.getRows().size() - 1;
		assertNotNull("the last row is in view at the end of the bar", panel.rowRect(last));
	}

	@Test
	public void theLayersListCanBeClosedFourWays()
	{
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			String name = size.getKey();
			Rectangle map = size.getValue();

			// The button toggles it.
			Rig rig = rig(map, LONG_PLUGIN);
			rig.openLayers();
			assertTrue(name, rig.surface.layerPanel().isOpen());
			rig.openLayers();
			assertFalse(name + ": the button closes it again", rig.surface.layerPanel().isOpen());

			// Escape closes it, and nothing else is taken from the keyboard.
			rig.openLayers();
			assertTrue(name, rig.surface.layerPanel().isOpen());
			KeyEvent other = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, rig.now, 0, KeyEvent.VK_A, 'a');
			rig.surface.getInput().keyPressed(other);
			assertFalse(name + ": other keys are left for the game", other.isConsumed());
			KeyEvent escape = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, rig.now, 0, KeyEvent.VK_ESCAPE, (char) 27);
			rig.surface.getInput().keyPressed(escape);
			assertTrue(name + ": Escape is taken while the list is open", escape.isConsumed());
			rig.frames(2);
			assertFalse(name + ": Escape closes it", rig.surface.layerPanel().isOpen());
			KeyEvent later = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, rig.now, 0, KeyEvent.VK_ESCAPE, (char) 27);
			rig.surface.getInput().keyPressed(later);
			assertFalse(name + ": with nothing popped up Escape is the game's again", later.isConsumed());

			// A click on the map outside it closes it.
			rig.openLayers();
			assertTrue(name, rig.surface.layerPanel().isOpen());
			rig.click(map.x + 2, map.y + 2);
			assertFalse(name + ": a click outside closes it", rig.surface.layerPanel().isOpen());

			// So does the cross.
			rig.openLayers();
			Rectangle cross = rig.surface.layerPanel().closeRect();
			rig.click(cross.x + cross.width / 2, cross.y + cross.height / 2);
			assertFalse(name + ": the cross closes it", rig.surface.layerPanel().isOpen());
		}
	}

	@Test
	public void clickingTheRowsStillTogglesThemAtEverySize()
	{
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rig rig = rig(size.getValue(), null);
			rig.openLayers();
			LayerPanel panel = rig.surface.layerPanel();
			Rectangle first = panel.rowRect(0);
			assertNotNull(size.getKey(), first);
			LayerPanel.Row hit = panel.hit(first.x + 30, first.y + first.height / 2);
			assertNotNull(size.getKey(), hit);
			assertEquals("showHotspots", hit.getKey());
		}
	}

	@Test
	public void longLabelsAreCutAndTheirTagStaysInsideTheMapClearOfTheButtons()
	{
		List<String> labels = new ArrayList<>();
		for (PoiCategory category : assets.getPoiCategories())
		{
			labels.add(category.getLabel());
		}
		labels.add(LONG_PLUGIN + " (3)");
		for (String real : REAL_PLUGINS)
		{
			labels.add(real + " (400)");
		}
		FontMetrics metrics = Box.metrics(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rig rig = rig(size.getValue(), LONG_PLUGIN);
			rig.openLayers();
			LayerPanel panel = rig.surface.layerPanel();
			Rectangle list = panel.getBounds();
			Rectangle block = rig.surface.buttons().block();
			int room = panel.labelRoom();
			for (int i = 0; i < panel.getRows().size(); i++)
			{
				LayerPanel.Row row = panel.getRows().get(i);
				if (row.isHeading())
				{
					continue;
				}
				String shown = Box.clip(row.getLabel(), metrics, room);
				assertTrue(size.getKey() + ": '" + shown + "' fits " + room, metrics.stringWidth(shown) <= room || room < 8);
				Rectangle tag = panel.tagFor(i, metrics);
				if (tag != null)
				{
					assertTrue(size.getKey() + ": tag " + tag + " inside " + size.getValue(), size.getValue().contains(tag));
					assertFalse(size.getKey() + ": tag " + tag + " covers a button", tag.intersects(block));
					assertTrue(size.getKey() + ": a cut label ends in an ellipsis",
						Box.clip(row.getLabel(), metrics, room).endsWith(Box.ELLIPSIS)
							|| Box.clip(row.getLabel(), metrics, room).isEmpty());
				}
			}
			assertTrue(list.width >= LayerPanel.MIN_WIDTH || list.width == 0);
		}
		assertTrue(labels.size() > 8);
	}

	@Test
	public void theWholeSurfaceDrawsWithTheListOpenAtEverySize()
	{
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rig rig = rig(size.getValue(), LONG_PLUGIN);
			rig.openLayers();
			Rectangle list = rig.surface.layerPanel().getBounds();
			// Hovering the last visible row draws its tag when it is cut; none of this may throw.
			rig.mouseX = list.x + 30;
			rig.mouseY = list.y + list.height - 12;
			rig.frames(3);
			Rectangle layers = rig.surface.buttons().boundsOf(MapButtons.Button.LAYERS);
			rig.mouseX = layers.x + 3;
			rig.mouseY = layers.y + 3;
			rig.frames(3);
		}
	}

	// ------------------------------------------------------------------
	// The right click menu
	// ------------------------------------------------------------------

	private static List<ContextMenu.Item> items(int count, String label)
	{
		List<ContextMenu.Item> items = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.ROUTE, label + " " + i));
		}
		return items;
	}

	@Test
	public void theMenuStaysInsideTheMapWhateverItsSizeAndWhereverItOpens()
	{
		String longLabel = "Hide " + LONG_PLUGIN + " (still used by routes) and route to it";
		FontMetrics metrics = Box.metrics(ContextMenu.font());
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rectangle map = size.getValue();
			int[][] spots = {{map.x, map.y}, {map.x + map.width, map.y}, {map.x, map.y + map.height},
				{map.x + map.width, map.y + map.height}, {map.x + map.width / 2, map.y + map.height / 2}};
			for (int[] spot : spots)
			{
				for (int count : new int[] {3, 13, 40})
				{
					ContextMenu menu = new ContextMenu();
					List<ContextMenu.Item> list = items(count, longLabel);
					menu.open(list, spot[0], spot[1], ContextMenu.widthFor(list, metrics), map, null);
					Rectangle b = menu.getBounds();
					assertTrue(size.getKey() + " " + count + " rows at " + spot[0] + "," + spot[1] + ": " + b + " in " + map,
						map.contains(b));
					int reached = 0;
					for (int i = 0; i < count; i++)
					{
						int guard = 0;
						while (menu.rowRect(i) == null && guard++ < count * 2)
						{
							menu.scroll(menu.rowRect(0) == null && firstRowInView(menu, count) > i ? -1 : 1);
						}
						Rectangle row = menu.rowRect(i);
						assertNotNull(size.getKey() + ": menu row " + i + " cannot be reached", row);
						assertTrue(b.contains(row));
						ContextMenu.Item hit = menu.hit(row.x + 10, row.y + row.height / 2);
						assertEquals(list.get(i), hit);
						reached++;
					}
					assertEquals(count, reached);
					assertEquals(menu.isScrollable(), count * ContextMenu.ROW_HEIGHT + ContextMenu.PADDING > map.height);
				}
			}
		}
	}

	private static int firstRowInView(ContextMenu menu, int count)
	{
		for (int i = 0; i < count; i++)
		{
			if (menu.rowRect(i) != null)
			{
				return i;
			}
		}
		return 0;
	}

	@Test
	public void theWheelOverAMenuScrollsItAndLeavesTheMapAlone()
	{
		Rig rig = rig(sizes().get("small fixed"), null);
		Rectangle map = rig.bounds;
		ContextMenu menu = rig.surface.menu();
		List<ContextMenu.Item> list = items(20, "Row");
		menu.open(list, map.x + map.width / 3, map.y + map.height / 3, 60, map, null);
		rig.frames(2);
		assertTrue(menu.isOpen());
		assertTrue("twenty rows cannot fit in a map this short", menu.isScrollable());
		assertTrue(map.contains(menu.getBounds()));
		double zoom = rig.surface.currentView().getPixelsPerTile();
		Rectangle b = menu.getBounds();
		rig.wheel(b.x + 20, b.y + b.height / 2, 1);
		assertTrue("the wheel moved the menu", menu.getScroll() > 0);
		assertEquals(zoom, rig.surface.currentView().getPixelsPerTile(), 1e-9);
		// Escape puts the menu away too.
		KeyEvent escape = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, rig.now, 0, KeyEvent.VK_ESCAPE, (char) 27);
		rig.surface.getInput().keyPressed(escape);
		assertTrue(escape.isConsumed());
		rig.frames(2);
		assertFalse(menu.isOpen());
	}

	@Test
	public void aMenuOpenedOnAResultOrAPinIsInsideTheMapToo()
	{
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rig rig = rig(size.getValue(), null);
			Rectangle map = size.getValue();
			rig.rightClick(map.x + map.width - 3, map.y + map.height - 3);
			assertTrue(size.getKey(), map.contains(rig.surface.menu().getBounds()));
			rig.click(map.x + 2, map.y + 2);
		}
	}

	// ------------------------------------------------------------------
	// Cards
	// ------------------------------------------------------------------

	private static HoverCard bigCard()
	{
		HoverCard.Builder builder = HoverCard.builder("A card whose title is much too long to fit in a map as small as the floating map "
			+ "can be made, for the sake of the test");
		builder.subtitle("And a subtitle that is just as long as the title, to be sure that text is cut and not drawn outside");
		for (int i = 0; i < 40; i++)
		{
			builder.row("Row number " + i + " with a long label that goes on", "value " + i);
		}
		builder.preview(3);
		builder.hint("A hint that is also longer than the whole width of the smallest floating map there can be");
		return builder.build();
	}

	@Test
	public void aCardIsNeverLargerThanTheMapNorOutsideItAtAnySize()
	{
		BufferedImage image = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		HoverCard card = bigCard();
		for (Map.Entry<String, Rectangle> size : sizes().entrySet())
		{
			Rectangle map = size.getValue();
			int[][] spots = {{map.x, map.y}, {map.x + map.width, map.y}, {map.x, map.y + map.height},
				{map.x + map.width, map.y + map.height}, {map.x + map.width / 2, map.y + map.height / 2}};
			for (int[] spot : spots)
			{
				for (boolean expanded : new boolean[] {false, true})
				{
					Rectangle b = HoverCardPainter.boundsOf(g, card, expanded, spot[0], spot[1], map);
					assertTrue(size.getKey() + " " + b + " in " + map, map.contains(b));
				}
			}
		}
		g.dispose();
	}

	@Test
	public void aCardIsDrawnInsideTheMapWithoutError()
	{
		BufferedImage image = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		HoverCardPainter painter = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
		for (Rectangle map : sizes().values())
		{
			painter.paint(g, bigCard(), map.x + map.width / 2, map.y + map.height / 2, map, true);
		}
		g.dispose();
	}

	// ------------------------------------------------------------------
	// Text helpers
	// ------------------------------------------------------------------

	@Test
	public void clippedTextFitsAndWrappedTextFitsToo()
	{
		FontMetrics metrics = Box.metrics(ContextMenu.font());
		for (int width : new int[] {10, 30, 60, 120, 400})
		{
			String clipped = Box.clip(LONG_PLUGIN, metrics, width);
			assertTrue(width + " '" + clipped + "'", metrics.stringWidth(clipped) <= width);
			for (String line : Box.wrap(LONG_PLUGIN, metrics, Math.max(width, 20)))
			{
				assertTrue(width + " '" + line + "'", metrics.stringWidth(line) <= Math.max(width, 20));
			}
		}
		assertEquals("short", Box.clip("short", metrics, 200));
	}
}
