package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.view.ViewTransform;
import org.junit.Test;

public class LineUpTest
{
	/**
	 * Where Quest Helper places a tile on the game's world map: its own code, written out here, from the map's centre
	 * tile, zoom and window. The window starts at (left, top).
	 */
	private static double[] questHelperPoint(int worldX, int worldY, int centreX, int centreY, float pixelsPerTile,
		int left, int top, int width, int height)
	{
		int widthInTiles = (int) Math.ceil(width / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(height / pixelsPerTile);
		int yTileMax = centreY - heightInTiles / 2;
		int yTileOffset = (yTileMax - worldY - 1) * -1;
		int xTileOffset = worldX + widthInTiles / 2 - centreX;
		int xGraphDiff = (int) (xTileOffset * pixelsPerTile);
		int yGraphDiff = (int) (yTileOffset * pixelsPerTile);
		yGraphDiff -= pixelsPerTile - Math.ceil(pixelsPerTile / 2);
		xGraphDiff += pixelsPerTile - Math.ceil(pixelsPerTile / 2);
		yGraphDiff = height - yGraphDiff;
		yGraphDiff += top;
		xGraphDiff += left;
		return new double[] {xGraphDiff, yGraphDiff};
	}

	@Test
	public void everyTileLandsWhereQuestHelperPutsItOnTheGameMap()
	{
		float[] zooms = {2f, 3f, 4f, 6f, 8f, 5.5f};
		int[][] windows = {{100, 80, 900, 600}, {0, 0, 1280, 720}, {8, 40, 761, 503}};
		for (float zoom : zooms)
		{
			for (int[] w : windows)
			{
				double[] centre = LineUp.centerFor(3222, 3218, zoom, w[2], w[3]);
				ViewTransform view = new ViewTransform(centre[0], centre[1], zoom, w[0], w[1], w[2], w[3]);
				for (int dx = -40; dx <= 40; dx += 13)
				{
					for (int dy = -30; dy <= 30; dy += 11)
					{
						int wx = 3222 + dx;
						int wy = 3218 + dy;
						double[] theirs = questHelperPoint(wx, wy, 3222, 3218, zoom, w[0], w[1], w[2], w[3]);
						double ourX = view.screenX(wx + 0.5);
						double ourY = view.screenY(wy + 0.5);
						assertEquals("x at zoom " + zoom, theirs[0], ourX, 1.5);
						assertEquals("y at zoom " + zoom, theirs[1], ourY, 1.5);
					}
				}
			}
		}
	}

	@Test
	public void theTileToSendTheGameMapGivesBackTheCentreItCameFrom()
	{
		for (float zoom : new float[] {2f, 4f, 8f, 5.5f})
		{
			double[] centre = LineUp.centerFor(2850, 3500, zoom, 900, 600);
			int[] tile = LineUp.tileFor(centre[0], centre[1], zoom, 900, 600);
			assertEquals(2850, tile[0]);
			assertEquals(3500, tile[1]);
		}
		// A centre between tiles rounds to the nearest tile to send.
		int[] tile = LineUp.tileFor(3000.4, 3100.6, 4.0, 900, 600);
		double[] back = LineUp.centerFor(tile[0], tile[1], 4.0, 900, 600);
		assertTrue(Math.abs(back[0] - 3000.4) <= 0.5 + 1e-9);
		assertTrue(Math.abs(back[1] - 3100.6) <= 0.5 + 1e-9);
	}
}
