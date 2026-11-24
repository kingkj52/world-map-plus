package com.worldmapplus.tiles.codec;

import com.worldmapplus.tiles.TileSource;

/**
 * Which chunk resource holds a tile. This is the one place that knows, shared by the packer that writes the chunks and
 * the reader that finds them, so the two cannot disagree. Names are relative to the folder the chunks are bundled in.
 * <p>
 * The ground map (map 0) and the floors above it, which share one set of squares, are grouped in regions of
 * {@value #REGION} by {@value #REGION} squares, with the floors of a region in the chunk of that region. Every
 * other map, with its floors if it has any, is a chunk of its own.
 */
public final class ChunkNames
{
	/** Side of a region in map squares: 2 to the power {@link #REGION_SHIFT}. */
	public static final int REGION = 8;

	public static final int REGION_SHIFT = 3;

	/** The map whose tiles are grouped by region. */
	public static final int REGIONAL_MAP = 0;

	public static final String EXTENSION = ".wmc";

	public static final String OVERVIEW_EXTENSION = ".wmo";

	private ChunkNames()
	{
	}

	/** The map a tile map id belongs to, without the offset that marks a floor. */
	public static int baseMap(int mapId)
	{
		return mapId % TileSource.FLOOR_STRIDE;
	}

	/** The name of the chunk that holds the tile of this square, for example {@code 0/3_5.wmc} or {@code 10187.wmc}. */
	public static String chunk(int mapId, int x, int y)
	{
		int base = baseMap(mapId);
		if (base == REGIONAL_MAP)
		{
			return base + "/" + (x >> REGION_SHIFT) + "_" + (y >> REGION_SHIFT) + EXTENSION;
		}
		return base + EXTENSION;
	}

	/**
	 * The map a chunk file name belongs to when the name is {@code <map>.wmc}, the files {@link SmallPack} may hold, or -1 for a name
	 * with a folder in it (the ground map's regions), for anything else, and for a number too large to be a map id.
	 */
	public static int packedBase(String name)
	{
		int end = name.length() - EXTENSION.length();
		if (end < 1 || end > 7 || !name.endsWith(EXTENSION))
		{
			return -1;
		}
		int value = 0;
		for (int i = 0; i < end; i++)
		{
			char c = name.charAt(i);
			if (c < '0' || c > '9')
			{
				return -1;
			}
			value = value * 10 + (c - '0');
		}
		return value > ChunkFile.MAX_ID ? -1 : value;
	}

	/** The name of the picture file for the overview of a map. */
	public static String overview(int mapId)
	{
		return "overview-" + mapId + OVERVIEW_EXTENSION;
	}
}
