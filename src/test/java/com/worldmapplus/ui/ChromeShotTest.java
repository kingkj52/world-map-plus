package com.worldmapplus.ui;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Draws the in-map chrome (buttons, search bar and results, layers popup, context menu, results panel, hover card) into
 * pictures. Only runs when the WORLDMAPPLUS_SHOTS environment variable names a folder. The pictures of one theme go in a
 * folder named after it, so a change of look can be compared byte for byte with an earlier run.
 */
public class ChromeShotTest
{
	private static final Rectangle BOUNDS = new Rectangle(0, 0, 900, 600);
	private static final JPanel SOURCE = new JPanel();
	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private long now = 7_000_000;
	private BufferedImage canvas;
	private MapSurface surface;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor, new com.worldmapplus.tiles.SourceTiles(new Gson()));
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	private MapSurface newSurface()
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
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		pins.add("Jolly Boar Inn", "place", 0, 3278.5, 3502.5);
		ExternalOverlays external = new ExternalOverlays();
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
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), routes, new PanelLauncher());
		return new MapSurface(client, assets, new WorldMapPlusConfig()
		{
		}, services);
	}

	private void frames(int count) throws InterruptedException
	{
		for (int i = 0; i < count; i++)
		{
			Graphics2D g = canvas.createGraphics();
			now += 16;
			surface.render(g, BOUNDS, new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0)),
				new MapSurface.Options().followOnOpen(true).openZoom(4.0), now);
			g.dispose();
		}
	}

	private void settle() throws InterruptedException
	{
		for (int pass = 0; pass < 50; pass++)
		{
			frames(1);
			Thread.sleep(20);
		}
		frames(40);
	}

	private void type(String text)
	{
		for (char c : text.toCharArray())
		{
			surface.getInput().keyTyped(new KeyEvent(SOURCE, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c));
		}
	}

	private void click(int x, int y, int button)
	{
		surface.getInput().mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, button));
		now += 16;
		surface.getInput().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, button));
	}

	private void save(File folder, String name) throws Exception
	{
		// Hover warmth runs on the wall clock, so let every fade finish before the picture is taken.
		Thread.sleep(300);
		frames(2);
		ImageIO.write(canvas, "png", new File(folder, "chrome-" + name + ".png"));
	}

	/** Pictures of the in-map chrome in the current theme, written into {@code folder}. */
	void scenes(File folder) throws Exception
	{
		folder.mkdirs();
		canvas = new BufferedImage(BOUNDS.width, BOUNDS.height, BufferedImage.TYPE_INT_RGB);
		mouseX = -1;
		mouseY = -1;
		surface = newSurface();
		settle();
		save(folder, "map");

		// A hover card: the pointer is moved over the map until an icon answers with its card.
		for (int y = 140; y < 460 && surface.lastCardTitle() == null; y += 14)
		{
			for (int x = 260; x < 640 && surface.lastCardTitle() == null; x += 14)
			{
				mouseX = x;
				mouseY = y;
				frames(1);
			}
		}
		frames(10);
		save(folder, "card");

		// A button under the pointer.
		Rectangle layers = surface.buttons().boundsOf(MapButtons.Button.LAYERS);
		mouseX = layers.x + layers.width / 2;
		mouseY = layers.y + layers.height / 2;
		frames(40);
		save(folder, "button-hover");

		// The layers popup.
		click(mouseX, mouseY, MouseEvent.BUTTON1);
		frames(30);
		mouseX = -1;
		mouseY = -1;
		frames(3);
		save(folder, "layers");
		click(layers.x + layers.width / 2, layers.y + layers.height / 2, MouseEvent.BUTTON1);
		frames(30);

		// A search with results, the second one selected.
		surface.getInput().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f'));
		frames(5);
		type("bank");
		frames(40);
		surface.getInput().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_DOWN, '\0'));
		frames(30);
		save(folder, "search");

		// The result chosen: the results panel and the highlight.
		type("");
		surface.getInput().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_ENTER, '\n'));
		frames(120);
		save(folder, "result");

		// The context menu.
		click(450, 380, MouseEvent.BUTTON3);
		mouseX = 460;
		mouseY = 400;
		frames(5);
		save(folder, "menu");
		click(5, 5, MouseEvent.BUTTON1);
		frames(5);
	}

	@Test
	public void draw() throws Exception
	{
		String folder = System.getenv("WORLDMAPPLUS_SHOTS");
		Assume.assumeTrue(folder != null && !folder.isEmpty());
		try
		{
			for (com.worldmapplus.theme.Palette palette : com.worldmapplus.theme.Palettes.all())
			{
				com.worldmapplus.theme.Look.use(palette);
				String sub = palette == com.worldmapplus.theme.Palettes.BRONZE ? "chrome"
					: palette.name().toLowerCase().replace(' ', '-') + "/chrome";
				scenes(new File(folder, sub));
			}
		}
		finally
		{
			com.worldmapplus.theme.Look.use(com.worldmapplus.theme.Palettes.BRONZE);
		}
	}
}
