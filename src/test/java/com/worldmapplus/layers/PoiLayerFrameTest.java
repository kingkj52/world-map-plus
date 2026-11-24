package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The points of interest layer, drawn at zooms from the whole world to a few tiles. The pictures are compared by a checksum, which
 * was taken before the layer was made to work without allocating, so any change in what it draws is caught.
 */
public class PoiLayerFrameTest
{
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

	private static Set<String> everyLayer()
	{
		Set<String> on = new LinkedHashSet<>();
		for (PoiCategory category : assets.getPoiCategories())
		{
			if (category.isLayer())
			{
				on.add(category.getId());
			}
		}
		return on;
	}

	private static int draw(PoiLayer layer, double centerX, double centerY, double zoom, Set<String> on, BufferedImage image)
	{
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(java.awt.Color.BLACK);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			MapInfo surface = assets.getCatalog().surface();
			layer.paint(g, new ViewTransform(centerX, centerY, zoom, 0, 0, image.getWidth(), image.getHeight()), surface, on);
		}
		finally
		{
			g.dispose();
		}
		return Arrays.hashCode(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
	}

	/** The zooms drawn: from far out to close in. */
	private static final double[] ZOOMS = {0.06, 0.15, 0.3, 0.7, 1.0, 2.0, 4.0, 12.0};

	@Test
	public void theLayerDrawsWhatItAlwaysDid()
	{
		PoiLayer layer = assets.getPoiLayer();
		Set<String> on = everyLayer();
		BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
		StringBuilder sums = new StringBuilder();
		for (double zoom : ZOOMS)
		{
			sums.append(draw(layer, 3222.5, 3218.5, zoom, on, image)).append(',');
			sums.append(draw(layer, 3100.25, 3400.75, zoom, on, image)).append(',');
		}
		System.out.println("poi checksums " + sums);
		assertEquals(EXPECTED, sums.toString());
	}

	@Test
	public void aFrameMakesNoListOrTableOfItsOwn()
	{
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		PoiLayer layer = assets.getPoiLayer();
		Set<String> on = everyLayer();
		BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		ViewTransform view = new ViewTransform(3222.5, 3218.5, 0.3, 0, 0, 1280, 720);
		MapInfo surface = assets.getCatalog().surface();
		for (int i = 0; i < 100; i++)
		{
			layer.paint(g, view, surface, on);
		}
		int frames = 200;
		long before = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
		for (int i = 0; i < frames; i++)
		{
			layer.paint(g, view, surface, on);
		}
		long perFrame = (threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - before) / frames;
		long startedAt = System.nanoTime();
		for (int i = 0; i < frames; i++)
		{
			layer.paint(g, view, surface, on);
		}
		System.out.printf("poi layer takes %.3f ms a frame%n", (System.nanoTime() - startedAt) / 1e6 / frames);
		g.dispose();
		System.out.println("poi layer allocates " + perFrame + " bytes a frame");
		// Before the layer kept its working space it made about 585,000 bytes a frame here; what is left is mostly Java2D, which makes
		// 352 bytes for each picture it scales. A table or list made again each frame would put it back over the line.
		assertTrue("allocated " + perFrame + " bytes a frame", perFrame < 530_000);
	}

	private static final String EXPECTED = "492633403,-134113459,2128187414,165319506,-1758637187,-145450113,244548557,1470593867,-207047643,-1019440799,-1345580172,1495265653,1525885804,515650408,-277986379,386648173,";
}
