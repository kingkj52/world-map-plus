package com.worldmapplus.layers;

/**
 * The Wilderness level of a position, from its coordinates alone.
 * <p>
 * The level rises by one every eight tiles north of the Wilderness ditch, in a strip of the world
 * that is 448 tiles wide. The same strip exists in the Wilderness dungeons far to the north in the
 * world grid. This is public reference information, computed from where a tile is, and involves
 * nothing the game has not already shown.
 */
public final class WildernessLevels
{
	/** First tile column of the Wilderness strip. */
	public static final int MIN_X = 2944;

	/** One past the last tile column of the strip. */
	public static final int MAX_X = 3392;

	/** South edge of level 1 on the surface. */
	public static final int SURFACE_SOUTH = 3520;

	/** South edge of level 1 in the dungeons. */
	public static final int UNDERGROUND_SOUTH = 9920;

	/** Levels in the strip. */
	public static final int LEVELS = 56;

	/** Tiles per level. */
	public static final int TILES_PER_LEVEL = 8;

	private WildernessLevels()
	{
	}

	/** True when a tile lies in the Wilderness, on the surface or underground. */
	public static boolean contains(int x, int y)
	{
		return level(x, y) > 0;
	}

	/** The Wilderness level of a tile, or 0 outside the Wilderness. */
	public static int level(int x, int y)
	{
		if (x < MIN_X || x >= MAX_X)
		{
			return 0;
		}
		int south = southEdgeFor(y);
		if (south < 0)
		{
			return 0;
		}
		int level = (y - south) / TILES_PER_LEVEL + 1;
		return level >= 1 && level <= LEVELS ? level : 0;
	}

	/** The south edge of the band a y coordinate is in, or -1 if it is in neither band. */
	static int southEdgeFor(int y)
	{
		int top = LEVELS * TILES_PER_LEVEL;
		if (y >= SURFACE_SOUTH && y < SURFACE_SOUTH + top)
		{
			return SURFACE_SOUTH;
		}
		if (y >= UNDERGROUND_SOUTH && y < UNDERGROUND_SOUTH + top)
		{
			return UNDERGROUND_SOUTH;
		}
		return -1;
	}

	/** The y coordinate of the line that starts a level, in a band. Level 57 is the top edge. */
	public static int lineY(int south, int level)
	{
		return south + (level - 1) * TILES_PER_LEVEL;
	}
}
