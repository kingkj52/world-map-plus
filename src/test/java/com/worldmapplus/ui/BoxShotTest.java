package com.worldmapplus.ui;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.PoiCategory;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.search.BundledSearch;
import com.worldmapplus.search.SearchBox;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchIndex;
import com.worldmapplus.theme.AnimationMode;
import com.worldmapplus.theme.Motion;
import com.worldmapplus.theme.Palette;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Round 74: every box with rows the map draws over itself, drawn on its own with times given, in every theme and with animations on,
 * (until round 76) reduced and off: the right click menu (fading in, a row warming and cooling, a cut label with its tag, scrolled and dragged), the
 * layers panel (columns, a heading, ticks on and off, the close cross, a cut label's tag, scrolled), the search bar (the empty field,
 * the help, results with the selection, the pointer, what is missing, the scroll bar, all selected, the hint, folding away) and hover
 * cards (rows, columns, a hint, the Wiki and Search wiki buttons lit and not, cut texts, a card bigger than its room, a picture). The
 * map's own route panel, notice and button tip are drawn through the real surface. Only runs when the WORLDMAPPLUS_SHOTS environment
 * variable names a folder; the pictures go in {@code boxes/<theme>[-still]} (round 76 dropped the reduced pictures with the setting), so a change of the drawing code can be compared
 * pixel for pixel with an earlier run. {@code boxes/alloc.txt} says how many bytes a settled frame of each box makes.
 */
public class BoxShotTest
{
	private static final Rectangle MAP = new Rectangle(0, 0, 900, 600);
	private static final JPanel SOURCE = new JPanel();
	private static ScheduledExecutorService executor;
	private static MapAssets assets;
	private static List<SearchEntry> entries;

	private BufferedImage canvas;
	private long now;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor, new com.worldmapplus.tiles.SourceTiles(new Gson()));
		entries = BundledSearch.entries();
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	private Graphics2D ground()
	{
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		// A patterned ground, so see-through fills and fades show.
		for (int y = 0; y < canvas.getHeight(); y += 20)
		{
			for (int x = 0; x < canvas.getWidth(); x += 20)
			{
				g.setColor(((x + y) / 20) % 2 == 0 ? new Color(70, 96, 60) : new Color(200, 190, 160));
				g.fillRect(x, y, 20, 20);
			}
		}
		return g;
	}

	private void frame(Consumer<Graphics2D> paint, File folder, String name) throws Exception
	{
		Graphics2D g = ground();
		paint.accept(g);
		g.dispose();
		if (name != null)
		{
			ImageIO.write(canvas, "png", new File(folder, name + ".png"));
		}
	}

	/** Frames until every fade is done, then the picture. */
	private void settled(Consumer<Graphics2D> paint, File folder, String name) throws Exception
	{
		for (int i = 0; i < 30; i++)
		{
			now += 16;
			frame(paint, folder, null);
		}
		frame(paint, folder, name);
	}

	private static Point centre(Rectangle r)
	{
		return new Point(r.x + r.width / 2, r.y + r.height / 2);
	}

	// The right click menu.

	private static List<ContextMenu.Item> items(String... labels)
	{
		List<ContextMenu.Item> list = new ArrayList<>();
		for (String label : labels)
		{
			list.add(new ContextMenu.Item(ContextMenu.Action.COPY_TILE, label));
		}
		return list;
	}

	private void open(ContextMenu menu, List<ContextMenu.Item> list, int x, int y, Rectangle limit)
	{
		menu.open(list, x, y, ContextMenu.widthFor(list, Box.metrics(ContextMenu.font())), limit, null);
	}

	private Point mouse;

	private void menuScenes(File folder) throws Exception
	{
		ContextMenu menu = new ContextMenu();
		Consumer<Graphics2D> paint = g -> menu.paint(g, mouse, now);
		mouse = null;
		List<ContextMenu.Item> plain = items("Route here", "Copy tile 3222, 3218", "Nearest teleports", "Add a pin here",
			"Measure from here", "Search the wiki for Lumbridge");
		open(menu, plain, 300, 150, MAP);
		frame(paint, folder, "menu-fade-0");
		now += 40;
		frame(paint, folder, "menu-fade-40");
		now += 40;
		frame(paint, folder, "menu-fade-80");
		settled(paint, folder, "menu");
		mouse = centre(menu.rowRect(2));
		now += 16;
		frame(paint, folder, null);
		now += 50;
		frame(paint, folder, "menu-row-warming");
		settled(paint, folder, "menu-row");
		mouse = centre(menu.rowRect(4));
		now += 16;
		frame(paint, folder, null);
		now += 50;
		frame(paint, folder, "menu-row-moving");
		mouse = new Point(5, 5);
		settled(paint, folder, "menu-cooled");

		// At the bottom right corner: kept inside the map.
		mouse = null;
		open(menu, plain, 880, 590, MAP);
		settled(paint, folder, "menu-corner");

		// A label too long for a narrow map: cut, and its tag while the pointer is on it.
		Rectangle narrow = new Rectangle(100, 60, 170, 300);
		open(menu, items("Route here", "Focus on Golems Don't Die: a point with a very long name indeed", "Copy tile"), 120, 80,
			narrow);
		settled(paint, folder, "menu-cut");
		mouse = centre(menu.rowRect(1));
		settled(paint, folder, "menu-cut-tag");

		// More items than fit: the list scrolls.
		String[] many = new String[30];
		for (int i = 0; i < many.length; i++)
		{
			many[i] = "Option number " + (i + 1) + (i % 4 == 0 ? " with more words" : "");
		}
		Rectangle short_ = new Rectangle(0, 0, 900, 230);
		mouse = null;
		open(menu, items(many), 400, 20, short_);
		settled(paint, folder, "menu-scrolls");
		menu.scroll(5);
		settled(paint, folder, "menu-scrolled");
		Rectangle b = menu.getBounds();
		menu.dragBarTo(b.y + b.height - 10);
		mouse = new Point(b.x + b.width - 6, b.y + b.height - 20);
		settled(paint, folder, "menu-dragged");
		mouse = new Point(b.x + 20, b.y + 60);
		settled(paint, folder, "menu-scrolled-row");
		menu.close();
		frame(paint, folder, "menu-closed");
	}

	// The layers panel.

	private static final WorldMapPlusConfig CONFIG = new WorldMapPlusConfig()
	{
		@Override
		public boolean showGrid()
		{
			return false;
		}

		@Override
		public String hiddenGameIcons()
		{
			return "Water source|Anvil";
		}

		@Override
		public String poiLayers()
		{
			return "bank,altar";
		}
	};

	private static LayerPanel layerPanel()
	{
		List<PoiCategory> categories = Arrays.asList(new PoiCategory("bank", "Banks", "B", new Color(220, 180, 40)),
			new PoiCategory("altar", "Runecrafting altars", "A", new Color(120, 120, 220)),
			new PoiCategory("teleport", "Teleport destinations of every kind there is in the game", "T", new Color(200, 80, 200)));
		List<String> kinds = new ArrayList<>(Arrays.asList("Water source", "Anvil", "Bank", "Furnace", "Altar", "General store",
			"Spinning wheel", "Range", "Fishing spot", "Mining site", "Rare trees", "Agility shortcut", "Dungeon entrance",
			"Transportation", "Quest start"));
		return new LayerPanel(categories, kinds);
	}

	private void layerScenes(File folder) throws Exception
	{
		LayerPanel panel = layerPanel();
		Rectangle anchor = new Rectangle(860, 300, 30, 30);
		Rectangle block = new Rectangle(855, 10, 40, 330);
		Rectangle[] limit = {MAP};
		Rectangle[] avoid = {null};
		Consumer<Graphics2D> paint = g ->
		{
			panel.layout(anchor, block, limit[0], avoid[0]);
			panel.paint(g, CONFIG, mouse, now);
		};
		mouse = null;
		panel.toggle();
		frame(paint, folder, "layers-fade-0");
		now += 40;
		frame(paint, folder, "layers-fade-40");
		now += 40;
		frame(paint, folder, "layers-fade-80");
		settled(paint, folder, "layers");
		mouse = centre(panel.rowRect(1));
		now += 16;
		frame(paint, folder, null);
		now += 50;
		frame(paint, folder, "layers-row-warming");
		settled(paint, folder, "layers-row");
		mouse = centre(panel.rowRect(5));
		settled(paint, folder, "layers-heading");
		mouse = centre(panel.closeRect());
		settled(paint, folder, "layers-close");

		// A tall map: one column, with the search field to stay clear of.
		limit[0] = new Rectangle(0, 0, 900, 900);
		avoid[0] = new Rectangle(250, 10, 380, 26);
		mouse = null;
		settled(paint, folder, "layers-tall");

		// A short, narrow map: the list scrolls, and long labels are cut with a tag while the pointer is on them.
		limit[0] = new Rectangle(560, 0, 340, 260);
		avoid[0] = null;
		settled(paint, folder, "layers-scrolls");
		panel.scroll(3);
		settled(paint, folder, "layers-scrolled");
		Rectangle b = panel.getBounds();
		panel.dragBarTo(b.y + b.height - 10);
		mouse = new Point(b.x + b.width - 6, b.y + b.height - 20);
		settled(paint, folder, "layers-dragged");
		panel.scroll(-100);
		panel.scroll(4);
		for (int i = 0; i < panel.getRows().size(); i++)
		{
			Rectangle row = panel.rowRect(i);
			if (row != null && panel.getRows().get(i).getLabel().startsWith("Teleport"))
			{
				mouse = centre(row);
			}
		}
		settled(paint, folder, "layers-cut-tag");

		// Hardly any room at all.
		limit[0] = new Rectangle(780, 0, 120, 500);
		mouse = null;
		settled(paint, folder, "layers-narrow");
		panel.close();
		frame(paint, folder, "layers-closed");
	}

	// The search bar.

	private void searchScenes(File folder) throws Exception
	{
		SearchBar bar = new SearchBar();
		SearchBox box = new SearchBox();
		box.setIndex(new SearchIndex(entries));
		boolean[] wantOpen = {true};
		boolean[] hint = {false};
		Consumer<Graphics2D> paint = g ->
		{
			bar.layout(MAP, box.getResults().size(), hint[0]);
			box.setVisibleRows(bar.getVisibleRows());
			bar.animate(wantOpen[0], now);
			bar.paint(g, box, mouse, now);
		};
		mouse = null;
		now = now / 1000 * 1000;
		settled(paint, folder, "search-empty");
		box.focus();
		now = now / 1000 * 1000;
		frame(paint, folder, "search-help");
		now += 500;
		frame(paint, folder, "search-help-caret");
		box.setText("bank");
		now = now / 1000 * 1000;
		settled(paint, folder, "search-bank");
		box.moveSelection(1);
		now += 16;
		frame(paint, folder, null);
		now += 50;
		frame(paint, folder, "search-selection-moving");
		now = now / 1000 * 1000 + 500;
		frame(paint, folder, "search-selected");
		Rectangle results = bar.getResultsRect();
		mouse = new Point(results.x + 40, results.y + SearchBar.ROW_HEIGHT * 3 + 10);
		settled(paint, folder, "search-hover");
		mouse = new Point(results.x + results.width - 5, results.y + 30);
		box.scroll(4);
		settled(paint, folder, "search-scrolled");
		bar.setMissing(e -> e.getName().length() % 2 == 0 ? "Quest: The Restless Ghost and a few more words" : null);
		mouse = null;
		settled(paint, folder, "search-missing");
		bar.setMissing(null);
		box.selectAll();
		settled(paint, folder, "search-all-selected");
		box.setText("a much longer search than the field has room for, typed in full");
		settled(paint, folder, "search-long");
		box.setText("type:");
		hint[0] = !box.getHint().isEmpty();
		settled(paint, folder, "search-hint");
		box.setText("zzqqxxyy");
		hint[0] = !box.getHint().isEmpty();
		settled(paint, folder, "search-nothing");
		box.setText("bank");
		hint[0] = false;
		settled(paint, folder, null);
		wantOpen[0] = false;
		now += 16;
		frame(paint, folder, null);
		now += 60;
		frame(paint, folder, "search-folding");
		settled(paint, folder, "search-folded");
		box.blur();
		settled(paint, folder, "search-blurred");
	}

	// Hover cards.

	private static HoverCard card(String title)
	{
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			if (hotspot.getCard().getTitle().equals(title))
			{
				return hotspot.getCard();
			}
		}
		throw new AssertionError("no card " + title);
	}

	private void cardScenes(File folder) throws Exception
	{
		HoverCardPainter painter = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
		HoverCard[] card = {null};
		Rectangle[] limit = {MAP};
		boolean[] expanded = {false};
		Point[] at = {new Point(100, 80)};
		Consumer<Graphics2D> paint = g -> painter.paint(g, card[0], at[0].x, at[0].y, limit[0], expanded[0], mouse);
		mouse = null;

		card[0] = HoverCard.builder("Bank booth").build();
		frame(paint, folder, "card-title");
		card[0] = HoverCard.builder("Furnace").subtitle("Smelt ores into bars").row("Members", "No").row("Nearest bank", "12 tiles")
			.hint("Hold Shift for more").build();
		frame(paint, folder, "card-rows");
		card[0] = HoverCard.builder("Varrock").subtitle("The capital of Misthalin").row("Shops", "28").wiki("Varrock").build();
		frame(paint, folder, "card-wiki");
		Rectangle button = painter.lastButton();
		mouse = centre(button);
		frame(paint, folder, "card-wiki-lit");
		mouse = null;
		card[0] = HoverCard.builder("A dig site").row("Found by", "Searching").wikiSearch("Dig site spot").build();
		frame(paint, folder, "card-search");
		mouse = centre(painter.lastButton());
		frame(paint, folder, "card-search-lit");
		mouse = null;

		HoverCard.Builder many = HoverCard.builder("General store: everything a person could want to buy and sell in one place")
			.subtitle("A subtitle that is also far too long to fit in the width a card is allowed to have on any map at all");
		for (int i = 0; i < 60; i++)
		{
			many.row((i % 5 == 3 ? "  " : "") + "Item number " + i + (i % 7 == 0 ? " with a long name that goes on" : ""),
				i * 13 + " gp");
		}
		for (int i = 0; i < 6; i++)
		{
			many.overflowRow("More " + i, "shown with Shift");
		}
		card[0] = many.hint("Hold Shift for every row").wiki("General store").build();
		frame(paint, folder, "card-many");
		expanded[0] = true;
		frame(paint, folder, "card-many-expanded");
		limit[0] = new Rectangle(0, 0, 420, 300);
		at[0] = new Point(380, 260);
		frame(paint, folder, "card-many-small-room");
		expanded[0] = false;
		frame(paint, folder, "card-many-small-room-short");
		limit[0] = MAP;
		at[0] = new Point(860, 560);
		card[0] = HoverCard.builder("Flipped").row("Beside", "the pointer").build();
		frame(paint, folder, "card-flipped");
		at[0] = new Point(100, 80);

		// Cards from the data, one with a picture of where a doorway leads, drawn until the picture is final.
		for (String title : new String[] {"Travel cart: Brimhaven", "Agility shortcuts", "Crumbling wall"})
		{
			card[0] = card(title);
			for (int pass = 0; pass < 40; pass++)
			{
				frame(paint, folder, null);
				Thread.sleep(10);
			}
			frame(paint, folder, "card-" + title.toLowerCase().replaceAll("[^a-z]+", "-"));
		}
	}

	// The route panel, a notice and a button's tip, through the surface.

	private int surfaceX = -1;
	private int surfaceY = -1;

	private void surfaceScenes(File folder) throws Exception
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(surfaceX, surfaceY);
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? (Object) false : type.isPrimitive() ? (Object) 0 : null;
			});
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		MapActions actions = new MapActions(new EventBus(), external);
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(new ViewMemory(null)
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
		}, pins, external, actions, new Settings(null), null, routes, new PanelLauncher(),
			(com.worldmapplus.input.ChatSearch) null);
		MapSurface surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
		}, services);
		Rectangle[] bounds = {MAP};
		Consumer<Graphics2D> paint = g -> surface.render(g, bounds[0],
			new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0)),
			new MapSurface.Options().followOnOpen(true).openZoom(4.0), now);
		surfaceX = -1;
		surfaceY = -1;
		for (int pass = 0; pass < 50; pass++)
		{
			now += 16;
			frame(paint, folder, null);
			Thread.sleep(20);
		}

		// A route with jumps: the panel lists its steps.
		Map<String, Object> data = new HashMap<>();
		data.put("origin", Arrays.asList(new WorldPoint(3230, 3230, 0), new WorldPoint(3105, 3250, 0),
			new WorldPoint(2950, 3380, 0)));
		data.put("destination", Arrays.asList(new WorldPoint(3110, 3255, 0), new WorldPoint(2965, 3378, 0),
			new WorldPoint(2808, 3436, 0)));
		data.put("displayInfo", Arrays.asList("Lumbridge Home Teleport to somewhere with a long name", "Charter ship", "Spirit tree"));
		actions.routeTo(new WorldPoint(2810, 3440, 0));
		external.onPluginMessage(new PluginMessage("shortestpath", "transports", data));
		for (int pass = 0; pass < 30; pass++)
		{
			now += 16;
			frame(paint, folder, null);
			Thread.sleep(20);
		}
		frame(paint, folder, "surface-route");
		Rectangle layers = surface.buttons().boundsOf(MapButtons.Button.LAYERS);
		surfaceX = 20;
		surfaceY = 40 + 6 + 20 + 25;
		now += 16;
		frame(paint, folder, null);
		now += 50;
		frame(paint, folder, "surface-route-step-warming");
		settled(paint, folder, "surface-route-step");

		// A button's tip.
		surfaceX = layers.x + layers.width / 2;
		surfaceY = layers.y + layers.height / 2;
		settled(paint, folder, "surface-tip");

		// A narrow map: the notice wraps.
		surfaceX = -1;
		surfaceY = -1;
		bounds[0] = new Rectangle(0, 0, 300, 600);
		click(surface, 150, 300, MouseEvent.BUTTON3);
		settled(paint, folder, "surface-narrow-menu");
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			if (item.getAction() == ContextMenu.Action.ROUTE)
			{
				Rectangle b = surface.menu().getBounds();
				for (int y = b.y; y < b.y + b.height; y++)
				{
					if (surface.menu().hit(b.x + 10, y) == item)
					{
						click(surface, b.x + 10, y + 3, MouseEvent.BUTTON1);
						break;
					}
				}
				break;
			}
		}
		settled(paint, folder, "surface-narrow-notice");
	}

	private void click(MapSurface surface, int x, int y, int button)
	{
		surface.getInput().mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, button));
		now += 16;
		surface.getInput().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, button));
	}

	void scenes(File folder, boolean withSurface) throws Exception
	{
		folder.mkdirs();
		canvas = new BufferedImage(MAP.width, MAP.height, BufferedImage.TYPE_INT_RGB);
		now = 9_000_000;
		menuScenes(folder);
		layerScenes(folder);
		searchScenes(folder);
		cardScenes(folder);
		if (withSurface)
		{
			surfaceScenes(folder);
		}
	}

	/** Bytes a settled frame of each box makes, measured over many frames (Java2D's own share included). */
	private void allocation(File file) throws Exception
	{
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
			.getThreadMXBean();
		canvas = new BufferedImage(MAP.width, MAP.height, BufferedImage.TYPE_INT_RGB);
		Map<String, Consumer<Graphics2D>> boxes = new java.util.LinkedHashMap<>();

		ContextMenu menu = new ContextMenu();
		open(menu, items("Route here", "Copy tile 3222, 3218", "Nearest teleports", "Add a pin here"), 300, 150, MAP);
		Point menuMouse = centre(menu.rowRect(1));
		boxes.put("menu", g -> menu.paint(g, menuMouse, now));

		LayerPanel panel = layerPanel();
		panel.toggle();
		Rectangle anchor = new Rectangle(860, 300, 30, 30);
		Rectangle block = new Rectangle(855, 10, 40, 330);
		panel.layout(anchor, block, MAP, null);
		Point layerMouse = centre(panel.rowRect(1));
		boxes.put("layers", g ->
		{
			panel.layout(anchor, block, MAP, null);
			panel.paint(g, CONFIG, layerMouse, now);
		});

		SearchBar bar = new SearchBar();
		SearchBox box = new SearchBox();
		box.setIndex(new SearchIndex(entries));
		box.focus();
		box.setText("bank");
		Point barMouse = new Point(400, 120);
		boxes.put("search", g ->
		{
			bar.layout(MAP, box.getResults().size(), false);
			box.setVisibleRows(bar.getVisibleRows());
			bar.animate(true, now);
			bar.paint(g, box, barMouse, now);
		});

		HoverCardPainter painter = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
		HoverCard card = HoverCard.builder("Varrock").subtitle("The capital of Misthalin").row("Shops", "28").row("Banks", "2")
			.hint("Hold Shift for more").wiki("Varrock").build();
		Point cardMouse = new Point(200, 120);
		boxes.put("card", g -> painter.paint(g, card, 100, 80, MAP, false, cardMouse));

		try (PrintWriter out = new PrintWriter(file, "UTF-8"))
		{
			for (Map.Entry<String, Consumer<Graphics2D>> e : boxes.entrySet())
			{
				Graphics2D g = canvas.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				for (int i = 0; i < 2000; i++)
				{
					now += 16;
					e.getValue().accept(g);
				}
				int frames = 4000;
				long before = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
				for (int i = 0; i < frames; i++)
				{
					now += 16;
					e.getValue().accept(g);
				}
				long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
				g.dispose();
				out.println(e.getKey() + " " + (bytes / frames) + " bytes a frame");
			}
		}
	}

	@Test
	public void draw() throws Exception
	{
		String folder = System.getenv("WORLDMAPPLUS_SHOTS");
		Assume.assumeTrue(folder != null && !folder.isEmpty());
		try
		{
			for (Palette palette : Palette.all())
			{
				Palette.use(palette);
				String name = palette.name().toLowerCase().replace(' ', '-');
				scenes(new File(folder, "boxes/" + name), true);
				Motion.use(AnimationMode.OFF);
				scenes(new File(folder, "boxes/" + name + "-still"), false);
				Motion.use(AnimationMode.ON);
			}
			Palette.use(Palette.all()[0]);
			allocation(new File(folder, "boxes/alloc.txt"));
		}
		finally
		{
			Motion.use(AnimationMode.ON);
			Palette.use(Palette.all()[0]);
		}
	}
}
