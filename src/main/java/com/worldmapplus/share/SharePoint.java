package com.worldmapplus.share;

/**
 * One shared location: a world tile, its floor, and the World Map Plus map it belongs to when that map is not the one
 * the tile itself gives. Immutable. Region numbers follow RuneLite's {@code WorldPoint}: region id
 * {@code (x >> 6) << 8 | y >> 6}, local position {@code x & 63}, {@code y & 63}.
 */
public final class SharePoint
{
	/** The map is the one the receiving client shows this tile on. */
	public static final int AUTO_MAP = -1;

	/** The largest x or y a code can hold. */
	public static final int MAX_COORDINATE = 32_767;

	/** The largest explicit map id a code can hold. */
	public static final int MAX_MAP = 32_767;

	private final int x;
	private final int y;
	private final int plane;
	private final int map;

	public SharePoint(int x, int y, int plane)
	{
		this(x, y, plane, AUTO_MAP);
	}

	public SharePoint(int x, int y, int plane, int map)
	{
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.map = map;
	}

	public int getX()
	{
		return x;
	}

	public int getY()
	{
		return y;
	}

	public int getPlane()
	{
		return plane;
	}

	/** The map id, or {@link #AUTO_MAP}. */
	public int getMap()
	{
		return map;
	}

	public boolean hasMap()
	{
		return map != AUTO_MAP;
	}

	/** RuneLite's region id. It only tells regions apart for y below 16384, which every map of the plugin is. */
	public int regionId()
	{
		return (x >> 6) << 8 | y >> 6;
	}

	public int localX()
	{
		return x & 63;
	}

	public int localY()
	{
		return y & 63;
	}

	/** True when every value is one a code can hold. */
	boolean isValid()
	{
		return x >= 0 && x <= MAX_COORDINATE && y >= 0 && y <= MAX_COORDINATE && plane >= 0 && plane <= 3
			&& (map == AUTO_MAP || map >= 0 && map <= MAX_MAP);
	}

	/** "1249, 3720", with the floor when it is not the ground floor. */
	public String describe()
	{
		return x + ", " + y + (plane == 0 ? "" : ", floor " + plane);
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof SharePoint))
		{
			return false;
		}
		SharePoint p = (SharePoint) other;
		return p.x == x && p.y == y && p.plane == plane && p.map == map;
	}

	@Override
	public int hashCode()
	{
		return ((x * 31 + y) * 31 + plane) * 31 + map;
	}

	@Override
	public String toString()
	{
		return "(" + x + ", " + y + ", " + plane + (map == AUTO_MAP ? "" : ", map " + map) + ")";
	}
}
