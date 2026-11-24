package com.worldmapplus.layers;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Assume;
import org.junit.Test;

/** Measures what the label layer costs in a frame. Runs only when WORLDMAPPLUS_PERF is set. */
public class LabelCostTest
{
	@Test
	public void labelsAtTypicalZooms()
	{
		Assume.assumeTrue(System.getenv("WORLDMAPPLUS_PERF") != null);
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		MapAssets assets = new MapAssets(new Gson(), executor);
		LabelLayer layer = assets.getLabels();
		BufferedImage canvas = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		int on = assets.getElements().labelsOn(assets.getCatalog().surface().getId()).size();
		for (double zoom : new double[] {0.1, 0.3, 1.0, 4.0, 12.0})
		{
			ViewTransform view = new ViewTransform(3222.5, 3218.5, zoom, 0, 0, 1280, 720);
			for (int i = 0; i < 200; i++)
			{
				layer.paint(g, view, assets.getCatalog().surface(), 0);
			}
			long start = System.nanoTime();
			int frames = 1000;
			for (int i = 0; i < frames; i++)
			{
				layer.paint(g, view, assets.getCatalog().surface(), 0);
			}
			System.out.printf("labels (%d on the surface) at %.1f px/tile: %.3f ms a frame%n", on, zoom,
				(System.nanoTime() - start) / 1e6 / frames);
		}
		g.dispose();
		executor.shutdownNow();
	}
}
