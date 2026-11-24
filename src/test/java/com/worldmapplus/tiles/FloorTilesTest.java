package com.worldmapplus.tiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import org.junit.BeforeClass;
import org.junit.Test;

/** The floors above the ground, from the real bundled tiles. */
public class FloorTilesTest
{
	private static BundledTiles tiles;

	@BeforeClass
	public static void load()
	{
		tiles = BundledTiles.load(new Gson());
	}

	@Test
	public void theSurfaceHasThreeFloorsAboveTheGround()
	{
		assertEquals(3, tiles.highestFloor(0));
		for (int floor = 1; floor <= 3; floor++)
		{
			assertTrue("floor " + floor, tiles.tileCount(TileSource.tileMapId(0, floor)) > 300);
		}
	}

	@Test
	public void otherMapsHaveNoFloors()
	{
		assertEquals(0, tiles.highestFloor(6));
	}

	@Test
	public void aFloorTileIsClearWhereTheFloorHasNothing()
	{
		// Varrock castle, first floor.
		int id = TileSource.tileMapId(0, 1);
		assertTrue(tiles.exists(id, 50, 54));
		BufferedImage image = tiles.load(id, 50, 54);
		assertNotNull(image);
		assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
		int clear = 0;
		int solid = 0;
		for (int x = 0; x < image.getWidth(); x++)
		{
			for (int y = 0; y < image.getHeight(); y++)
			{
				int alpha = image.getRGB(x, y) >>> 24;
				if (alpha == 0)
				{
					clear++;
				}
				else if (alpha == 255)
				{
					solid++;
				}
			}
		}
		assertTrue("clear " + clear, clear > 5000);
		assertTrue("solid " + solid, solid > 2000);
	}

	@Test
	public void theGroundTilesStayOpaque()
	{
		BufferedImage ground = tiles.load(0, 50, 54);
		assertNotNull(ground);
		assertEquals(BufferedImage.TYPE_INT_RGB, ground.getType());
	}

	@Test
	public void aFloorTileShrinksWithoutDarkeningItsEdges()
	{
		BufferedImage image = tiles.load(TileSource.tileMapId(0, 1), 50, 54);
		BufferedImage small = TileCache.shrink(image, 2);
		assertEquals(64, small.getWidth());
		assertEquals(BufferedImage.TYPE_INT_ARGB, small.getType());
		boolean clear = false;
		boolean solid = false;
		for (int x = 0; x < 64; x++)
		{
			for (int y = 0; y < 64; y++)
			{
				int alpha = small.getRGB(x, y) >>> 24;
				clear |= alpha == 0;
				solid |= alpha == 255;
			}
		}
		assertTrue(clear && solid);
	}
}
