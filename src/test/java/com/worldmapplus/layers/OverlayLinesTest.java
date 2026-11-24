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
	public void theLineSpacingThinsOutWhenZoomedFarOut()
	{
		assertEquals(1, OverlayLines.levelStep(4.0));
		assertEquals(5, OverlayLines.levelStep(0.8));
		assertEquals(10, OverlayLines.levelStep(0.1));
	}

	@Test
	public void aCloseViewDrawsEveryLevelAndAFarOneEveryTenth()
	{
		for (int level = 1; level <= 57; level++)
		{
			assertTrue(OverlayLines.drawsLine(level, 1));
		}
		assertTrue(OverlayLines.drawsLine(1, 10));
		assertTrue(OverlayLines.drawsLine(20, 10));
		assertFalse(OverlayLines.drawsLine(15, 10));
		assertFalse(OverlayLines.drawsLine(57, 10));
	}

	@Test
	public void regionIdsMatchTheGamesNumbering()
	{
		// Lumbridge is region 12850, Varrock 12853 and Falador 11828.
		assertEquals(12850, OverlayLines.regionId(3222, 3218));
		assertEquals(12853, OverlayLines.regionId(3213, 3428));
		assertEquals(11828, OverlayLines.regionId(2964, 3378));
	}

	private static BufferedImage paintWilderness(ViewTransform view)
	{
		BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		OverlayLines.paintWilderness(g, view, SURFACE);
		g.dispose();
		return image;
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
	public void wildernessLinesAppearOnlyWhereTheWildernessIs()
	{
		ViewTransform wild = new ViewTransform(3150, 3700, 4.0, 0, 0, 600, 400);
		ViewTransform lumbridge = new ViewTransform(3222, 3218, 4.0, 0, 0, 600, 400);
		assertTrue(anyDrawn(paintWilderness(wild)));
		assertFalse(anyDrawn(paintWilderness(lumbridge)));
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
