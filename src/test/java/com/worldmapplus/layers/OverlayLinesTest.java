package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class OverlayLinesTest
{
	private static final MapInfo SURFACE = new MapInfo(0, "Surface", 896, 1984, 4096, 4288, 2496, 3328);

	@Test
	public void regionIdsMatchTheGamesNumbering()
	{
		// Lumbridge is region 12850, Varrock 12853 and Falador 11828.
		assertEquals(12850, OverlayLines.regionId(3222, 3218));
		assertEquals(12853, OverlayLines.regionId(3213, 3428));
		assertEquals(11828, OverlayLines.regionId(2964, 3378));
	}

	private static boolean anyDrawn(BufferedImage image)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void theGridDrawsNothingWhenFarOutAndSomethingWhenClose()
	{
		BufferedImage far = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = far.createGraphics();
		OverlayLines.paintGrid(g, new ViewTransform(3222, 3218, 0.3, 0, 0, 600, 400), SURFACE);
		g.dispose();
		assertFalse(anyDrawn(far));

		BufferedImage near = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
		g = near.createGraphics();
		OverlayLines.paintGrid(g, new ViewTransform(3222, 3218, 2.0, 0, 0, 600, 400), SURFACE);
		g.dispose();
		assertTrue(anyDrawn(near));
	}
}
