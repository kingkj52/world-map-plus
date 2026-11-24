package com.worldmapplus.ui;

/**
 * The arithmetic of putting this plugin's map exactly where the game's map is looking.
 * <p>
 * Quest Helper draws its own lines on the game's world map, and places a tile on that map from the map's centre tile,
 * its zoom and the size of the map window: the window is taken to be a whole number of tiles wide and high, the tile
 * at the centre is the middle of that, and a tile is placed at the middle of its square. This map, with the same
 * centre, the same zoom and the same window, puts every tile in the same place, so lines drawn on the game's map
 * land on the right tiles here.
 */
final class LineUp
{
	private LineUp()
	{
	}

	/** The world position this map must be centred on to show what the game's map shows. */
	static double[] centerFor(int tileX, int tileY, double zoom, int width, int height)
	{
		int tilesWide = (int) Math.ceil(width / zoom);
		int tilesHigh = (int) Math.ceil(height / zoom);
		return new double[] {tileX + width / (2 * zoom) - tilesWide / 2, tileY + height / (2 * zoom) - tilesHigh / 2};
	}

	/** The tile the game's map must be told to look at for this map's centre to be where it is. */
	static int[] tileFor(double centerX, double centerY, double zoom, int width, int height)
	{
		int tilesWide = (int) Math.ceil(width / zoom);
		int tilesHigh = (int) Math.ceil(height / zoom);
		return new int[] {(int) Math.round(centerX - width / (2 * zoom) + tilesWide / 2),
			(int) Math.round(centerY - height / (2 * zoom) + tilesHigh / 2)};
	}
}
