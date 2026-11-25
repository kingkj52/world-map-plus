package com.worldmapplus.tiles;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import lombok.extern.slf4j.*;

/**
 * Which map squares have a tile, per map. Bundled in the jar as {@code tiles/index.json} (a few tens of kilobytes), so a missing
 * square is known without any file or download. A damaged or missing index gives an empty one.
 */
@Slf4j
public final class TileIndex
{
	private static final int MAX_BYTES = 4 << 20;

	/** The map ids that have tiles, ascending, and the sorted square keys of each. */
	private final int[] maps;
	private final int[][] squares;

	private TileIndex(Map<Integer, int[]> byMap)
	{
		TreeMap<Integer, int[]> sorted = new TreeMap<>(byMap);
		maps = new int[sorted.size()];
		squares = new int[sorted.size()][];
		int i = 0;
		for (Map.Entry<Integer, int[]> entry : sorted.entrySet())
		{
			maps[i] = entry.getKey();
			squares[i++] = entry.getValue();
		}
	}

	/** Packs a square into the key used by the index. */
	public static int key(int x, int y)
	{
		return (x << 16) | (y & 0xFFFF);
	}

	/** Reads the index from the jar. */
	public static TileIndex load(Gson gson)
	{
		try (InputStream in = TileIndex.class.getResourceAsStream("/com/worldmapplus/tiles/index.json"))
		{
			byte[] bytes = in == null ? null : in.readNBytes(MAX_BYTES + 1);
			if (bytes == null || bytes.length > MAX_BYTES)
			{
				log.warn("World Map Plus: the tile index is missing or too large");
				return of(Collections.emptyMap());
			}
			Raw raw = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), Raw.class);
			return of(raw == null || raw.maps == null ? Collections.emptyMap() : raw.maps);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("World Map Plus: could not read the tile index", e);
			return of(Collections.emptyMap());
		}
	}

	/** An index of the given squares, keyed by map id text as in index.json. Entries that are not map ids are skipped. */
	static TileIndex of(Map<String, int[]> maps)
	{
		Map<Integer, int[]> parsed = new HashMap<>();
		for (Map.Entry<String, int[]> entry : maps.entrySet())
		{
			try
			{
				int[] keys = entry.getValue() == null ? new int[0] : entry.getValue().clone();
				Arrays.sort(keys);
				parsed.put(Integer.parseInt(entry.getKey()), keys);
			}
			catch (NumberFormatException e)
			{
				// Not a map id.
			}
		}
		return new TileIndex(parsed);
	}

	/** True when the map has a tile for this square. Allocates nothing. */
	public boolean exists(int mapId, int x, int y)
	{
		int at = Arrays.binarySearch(maps, mapId);
		return at >= 0 && Arrays.binarySearch(squares[at], key(x, y)) >= 0;
	}

	public int tileCount(int mapId)
	{
		int at = Arrays.binarySearch(maps, mapId);
		return at < 0 ? 0 : squares[at].length;
	}

	/** The highest floor above the ground that this map has tiles for, or 0 when it has none. */
	public int highestFloor(int mapId)
	{
		int highest = 0;
		for (int floor = 1; floor <= TileSource.MAX_FLOOR; floor++)
		{
			if (tileCount(TileSource.tileMapId(mapId, floor)) > 0)
			{
				highest = floor;
			}
		}
		return highest;
	}

	/** The shape of index.json. Filled by Gson. */
	private static final class Raw
	{
		private Map<String, int[]> maps;
	}
}
