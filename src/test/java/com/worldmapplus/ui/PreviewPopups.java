package com.worldmapplus.ui;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.HoverCard;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;

/**
 * Draws the layers list open, the right click menu open and a hover card over floating maps of every size, with the real classes,
 * so their layout can be judged by eye. Run with {@code ./gradlew previewPopups}. The pictures go to build/preview and are
 * never committed.
 */
public final class PreviewPopups
{
	private PreviewPopups()
	{
	}

	public static void main(String[] args) throws Exception
	{
		File out = new File("build/preview");
		out.mkdirs();
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		MapAssets assets = new MapAssets(new Gson(), executor, new com.worldmapplus.tiles.SourceTiles(new Gson()));
		try
		{
			for (Map.Entry<String, Rectangle> size : PopupLayoutTest.sizes().entrySet())
			{
				String slug = size.getKey().replace(' ', '-');
				Rectangle map = size.getValue();

				PopupLayoutTest.Rig layers = PopupLayoutTest.rig(assets, map, PopupLayoutTest.LONG_PLUGIN);
				layers.openLayers();
				Rectangle list = layers.surface.layerPanel().getBounds();
				layers.mouseX = list.x + 30;
				layers.mouseY = list.y + list.height - 12;
				layers.frames(4);
				save(layers, map, new File(out, "popup-layers-" + slug + ".png"));

				PopupLayoutTest.Rig menu = PopupLayoutTest.rig(assets, map, null);
				List<ContextMenu.Item> items = new ArrayList<>();
				String[] labels = {"Route here (Shortest Path)", "Set route start here", "Add route target 2 here", "Clear route",
					"Copy tile  3222, 3218", "Nearest teleports", "Save place: " + PopupLayoutTest.LONG_PLUGIN, "Add pin here",
					"Measure from here", "Hide a pin (still used by routes)", "Remove a pin", "Rename in side panel",
					"Route to a pin"};
				for (String label : labels)
				{
					items.add(new ContextMenu.Item(ContextMenu.Action.ROUTE, label));
				}
				Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
				probe.setFont(ContextMenu.font());
				int width = ContextMenu.widthFor(items, probe.getFontMetrics());
				probe.dispose();
				menu.surface.menu().open(items, map.x + map.width / 2, map.y + map.height / 3, width, map, null);
				Rectangle at = menu.surface.menu().getBounds();
				menu.mouseX = at.x + 20;
				menu.mouseY = at.y + at.height - 18;
				menu.frames(4);
				save(menu, map, new File(out, "popup-menu-" + slug + ".png"));

				PopupLayoutTest.Rig card = PopupLayoutTest.rig(assets, map, null);
				HoverCard.Builder builder = HoverCard.builder("Fairy ring network with a long name for the test");
				builder.subtitle("A subtitle");
				for (int i = 0; i < 12; i++)
				{
					builder.row("Row " + i + " with some words", "value");
				}
				builder.hint("Hold Shift to see everything");
				card.frames(2);
				Graphics2D g = card.canvas.createGraphics();
				new HoverCardPainter(assets.getCatalog(), assets.getRenderer()).paint(g, builder.build(),
					map.x + map.width / 2, map.y + map.height / 2, map, false);
				g.dispose();
				save(card, map, new File(out, "popup-card-" + slug + ".png"), false);
			}
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	private static void save(PopupLayoutTest.Rig rig, Rectangle map, File file) throws Exception
	{
		save(rig, map, file, true);
	}

	/** The map and a margin round it, at twice the size. */
	private static void save(PopupLayoutTest.Rig rig, Rectangle map, File file, boolean ignored) throws Exception
	{
		int margin = 12;
		int x = Math.max(0, map.x - margin);
		int y = Math.max(0, map.y - margin);
		int w = Math.min(rig.canvas.getWidth() - x, map.width + 2 * margin);
		int h = Math.min(rig.canvas.getHeight() - y, map.height + 2 * margin);
		BufferedImage crop = rig.canvas.getSubimage(x, y, w, h);
		BufferedImage big = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = big.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(crop, 0, 0, w * 2, h * 2, null);
		g.dispose();
		ImageIO.write(big, "png", file);
	}
}
