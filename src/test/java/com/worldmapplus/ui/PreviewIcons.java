package com.worldmapplus.ui;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.game.TrailTracker;
import com.worldmapplus.layers.NpcLayer;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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

/**
 * Renders the game's icons at six zooms over three places (Varlamore, Misthalin from Lumbridge to Varrock, and the Wilderness), to
 * PNG files for a look by eye. Run with {@code ./gradlew previewIcons}; {@code -PiconFloor=1} looks at a floor and
 * {@code -PiconZooms=0.25,1} picks the zooms. Output goes to build/preview.
 */
public class PreviewIcons
{
	private static int mouseX = -1;
	private static int mouseY = -1;
	private static String poiLayers = "";
	private static boolean wilderness;
	private static boolean grid;
	private static boolean regions;
	private static long now = 5_000_000;

	public static void main(String[] args) throws Exception
	{
		String suffix = "";
		File out = new File("build/preview");
		out.mkdirs();
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
		MapAssets assets = new MapAssets(new Gson(), executor);
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, a) ->
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
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
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
		MapActions routing = new MapActions(new EventBus(), external);
		SurfaceServices services = new SurfaceServices(quiet, memory, pins, external,
			routing, new Settings(null), new TrailTracker(), new com.google.gson.Gson());
		WorldMapPlusConfig config = new WorldMapPlusConfig()
		{
		};
		String[] size = System.getProperty("size", "1100x700").split("x");
		Rectangle bounds = new Rectangle(20, 20, Integer.parseInt(size[0]), Integer.parseInt(size[1]));

		String[] zoomText = System.getProperty("zooms", "0.1,0.25,0.5,1,2,4").split(",");
		int floor = Integer.getInteger("floor", 0);
		String only = System.getProperty("place", "");
		// Name, x, y of the middle of the view.
		Object[][] places = {
			{"varlamore", 1700.5, 3150.5},
			{"misthalin", 3200.5, 3320.5},
			{"wilderness", 3120.5, 3800.5}
		};
		for (Object[] place : places)
		{
			if (!only.isEmpty() && !only.equals(place[0]))
			{
				continue;
			}
			for (String zoomName : zoomText)
			{
				double zoom = Double.parseDouble(zoomName);
				mouseX = -1;
				mouseY = -1;
				MapSurface surface = new MapSurface(client, assets, config, services);
				PlayerPosition at = new PlayerPosition((double) place[1], (double) place[2],
					new WorldPoint((int) (double) place[1], (int) (double) place[2], 0));
				BufferedImage image = new BufferedImage(bounds.width + 40, bounds.height + 40, BufferedImage.TYPE_INT_RGB);
				MapSurface.Options options = new MapSurface.Options().openZoom(zoom).followOnOpen(true);
				for (int i = 0; i < 90; i++)
				{
					Graphics2D g = image.createGraphics();
					now += 16;
					surface.render(g, bounds, at, options, now);
					g.dispose();
					Thread.sleep(8);
					if (i == 40 && floor > 0)
					{
						surface.viewer().setFloor(floor);
					}
				}
				String name = "icons-" + place[0] + "-" + zoomName + (floor > 0 ? "-floor" + floor : "") + (size[0].equals("1100") ? "" : "-" + size[0] + "w");
				ImageIO.write(image, "png", new File(out, name + suffix + ".png"));
				System.out.println("  " + name + "  zoom " + surface.currentView().getPixelsPerTile() + " drawn "
					+ assets.getGameIconLayer().drawnCount() + " at "
					+ assets.getGameIconLayer().drawnSize() + " px");
			}
		}
		executor.shutdownNow();
	}
}
