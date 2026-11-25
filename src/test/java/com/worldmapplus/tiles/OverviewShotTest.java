package com.worldmapplus.tiles;

import com.worldmapplus.map.MapInfo;
import com.worldmapplus.render.MapRenderer;
import com.worldmapplus.view.ViewTransform;
import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;

/**
 * Draws the far zoom view from the overview pictures the jar carries, with no tile, so what a player sees offline or before
 * a download can be looked at. Only runs when the WORLDMAPPLUS_SHOTS environment variable names a folder, and writes nothing
 * otherwise. The file names start with the text in WORLDMAPPLUS_SHOT_NAME (default "overview").
 */
public class OverviewShotTest
{
	private static final MapInfo SURFACE = new MapInfo(0, "Gielinor Surface", 960, 2048, 4032, 4224, 2496, 3328);

	private static void shot(String folder, String name, double cx, double cy, double scale, int w, int h) throws Exception
	{
		PackedTiles source = PackedTiles.overviewsOnly(TileIndex.of(new HashMap<>()), new Gson(), Runnable::run);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		ViewTransform view = new ViewTransform(cx, cy, scale, 0, 0, w, h);
		renderer.paint(g, view, SURFACE);
		renderer.paint(g, view, SURFACE);
		g.dispose();
		ImageIO.write(canvas, "png", new File(folder, name + ".png"));
	}

	@Test
	public void draw() throws Exception
	{
		String folder = System.getenv("WORLDMAPPLUS_SHOTS");
		Assume.assumeTrue(folder != null && !folder.isEmpty());
		String prefix = System.getenv("WORLDMAPPLUS_SHOT_NAME") == null ? "overview" : System.getenv("WORLDMAPPLUS_SHOT_NAME");
		new File(folder).mkdirs();
		shot(folder, prefix + "-whole", 2496, 3136, 0.25, 800, 560);
		shot(folder, prefix + "-near", 3200, 3200, 0.49, 900, 600);
	}
}
