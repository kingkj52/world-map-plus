package com.worldmapplus.map;

import java.util.HashSet;
import java.util.Set;
import lombok.*;

/**
 * One map the viewer can show: the surface, or one of the underground and detached areas.
 * <p>
 * Bounds are in world tiles and use the same coordinates as {@code WorldPoint}. Gson fills the
 * fields directly, which is why they are plain non-final fields.
 */
public final class MapInfo
{
	/** The wiki's id for the surface map, and the map the viewer starts on. */
	public static final int SURFACE = 0;

	/**
	 * Maps from this id up are the wiki's extra maps: single dungeons, cutscene areas and minigame
	 * rooms. They are drawn over the same coordinates as the standard maps, so a position is only
	 * given to one of them when no standard map covers it.
	 */
	public static final int FIRST_EXTRA = 10_000;

	@Getter
	private int id;
	@Getter
	private String name;
	@Getter
	private int minX;
	@Getter
	private int minY;
	@Getter
	private int maxX;
	@Getter
	private int maxY;
	private int cx;
	private int cy;
	/** The 64 tile squares that have imagery, each as x << 16 | y. Absent means the whole rectangle counts. */
	private int[] sq;
	private transient Set<Integer> squareSet;

	public MapInfo()
	{
	}

	public MapInfo(int id, String name, int minX, int minY, int maxX, int maxY, int cx, int cy)
	{
		this.id = id;
		this.name = name;
		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
		this.cx = cx;
		this.cy = cy;
	}

	/** True for the standard maps. False for the wiki's extra maps, which only a search or a link opens. */
	public boolean isStandard()
	{
		return id < FIRST_EXTRA;
	}

	/** A sensible place to look when this map is opened with nothing else to aim at. */
	public int getCenterX()
	{
		return cx;
	}

	public int getCenterY()
	{
		return cy;
	}

	/**
	 * True when a tile is on this map. The rectangle alone is not enough, because the bounds of two
	 * maps can overlap while their imagery does not, so a tile also needs to lie in a square this map
	 * has imagery for, when the map lists its squares.
	 */
	public boolean contains(int x, int y)
	{
		if (x < minX || x >= maxX || y < minY || y >= maxY)
		{
			return false;
		}
		if (sq == null)
		{
			return true;
		}
		Set<Integer> squares = squareSet;
		if (squares == null)
		{
			squares = new HashSet<>();
			for (int key : sq)
			{
				squares.add(key);
			}
			squareSet = squares;
		}
		return squares.contains(((x >> 6) << 16) | (y >> 6));
	}

	/**
	 * How far a tile is from this map's imagery, in tiles: 0 when {@link #contains it is on the map}, otherwise the shortest distance,
	 * along the longer of the two axes, to a square the map has imagery for (or to its rectangle when it lists no squares).
	 */
	public int distanceTo(int x, int y)
	{
		if (contains(x, y))
		{
			return 0;
		}
		int best = Integer.MAX_VALUE;
		if (sq == null)
		{
			return axisDistance(x, minX, maxX - 1, y, minY, maxY - 1);
		}
		for (int packed : sq)
		{
			int left = (packed >>> 16) << 6;
			int bottom = (packed & 0xFFFF) << 6;
			best = Math.min(best, axisDistance(x, left, left + 63, y, bottom, bottom + 63));
		}
		return best;
	}

	private static int axisDistance(int x, int minX, int maxX, int y, int minY, int maxY)
	{
		int dx = x < minX ? minX - x : Math.max(0, x - maxX);
		int dy = y < minY ? minY - y : Math.max(0, y - maxY);
		return Math.max(dx, dy);
	}

	/** A copy of this map that only counts the given squares, each as x << 16 | y. For tests. */
	public MapInfo withSquares(int... squares)
	{
		MapInfo copy = new MapInfo(id, name, minX, minY, maxX, maxY, cx, cy);
		copy.sq = squares.clone();
		return copy;
	}

	/** Area in tiles, used to prefer the more specific of two overlapping maps. */
	public long area()
	{
		return (long) (maxX - minX) * (maxY - minY);
	}

	/** The 64 tile square columns this map spans, first inclusive. */
	public int firstSquareX()
	{
		return Math.floorDiv(minX, 64);
	}

	public int lastSquareX()
	{
		return Math.floorDiv(maxX - 1, 64);
	}

	public int firstSquareY()
	{
		return Math.floorDiv(minY, 64);
	}

	public int lastSquareY()
	{
		return Math.floorDiv(maxY - 1, 64);
	}

	@Override
	public String toString()
	{
		return id + " " + name;
	}
}
