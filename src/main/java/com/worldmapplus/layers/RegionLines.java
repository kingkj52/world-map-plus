package com.worldmapplus.layers;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.List;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * The regions of the world as the game's lore knows them, for the search ("in Kandarin") and the names of places.
 * <p>
 * The data is a bundled table of which 64 tile square belongs to which region. Everything is computed from coordinates and nothing is
 * read from the game. The tint, the borders and the names on the map are drawn by World Map Plus Helper from its own copy of the table.
 */
@Slf4j
public final class RegionLines
{
	private static final String RESOURCE = "/com/worldmapplus/regions.json";

	/** Size of a square of the table, in tiles. */
	public static final int SQUARE = 64;

	/** One region: its squares. */
	public static final class Region
	{
		@Getter
		private final String id;
		@Getter
		private final String name;
		private final Set<Long> squares;

		Region(String id, String name, Set<Long> squares)
		{
			this.id = id;
			this.name = name;
			this.squares = squares;
		}

		public int squareCount()
		{
			return squares.size();
		}
	}

	@Getter
	private final List<Region> regions;
	private final Map<Long, Region> byCell = new HashMap<>();

	public RegionLines(List<Region> regions)
	{
		this.regions = Collections.unmodifiableList(regions);
		for (Region region : regions)
		{
			for (long key : region.squares)
			{
				byCell.put(key, region);
			}
		}
	}

	public static RegionLines empty()
	{
		return new RegionLines(new ArrayList<>());
	}

	/** The region a tile is in, or null for sea and anywhere the table does not cover. */
	public Region regionAt(int x, int y)
	{
		return byCell.get(key(Math.floorDiv(x, SQUARE), Math.floorDiv(y, SQUARE)));
	}

	private static long key(int rx, int ry)
	{
		return ((long) rx << 32) | (ry & 0xFFFFFFFFL);
	}

	public static RegionLines load(Gson gson)
	{
		try (InputStream in = RegionLines.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("World Map Plus: {} is missing from the plugin jar", RESOURCE);
				return empty();
			}
			return read(new InputStreamReader(in, StandardCharsets.UTF_8), gson);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("World Map Plus: could not read {}", RESOURCE, e);
			return empty();
		}
	}

	public static RegionLines read(Reader reader, Gson gson)
	{
		RegionFile file = gson.fromJson(reader, RegionFile.class);
		List<Region> regions = new ArrayList<>();
		if (file == null || file.regions == null)
		{
			return new RegionLines(regions);
		}
		Set<Long> taken = new HashSet<>();
		for (RegionRecord record : file.regions)
		{
			if (record == null || record.id == null || record.name == null || record.cells == null)
			{
				continue;
			}
			Set<Long> squares = new HashSet<>();
			for (int[] cell : record.cells)
			{
				if (cell != null && cell.length >= 2 && taken.add(key(cell[0], cell[1])))
				{
					squares.add(key(cell[0], cell[1]));
				}
			}
			if (!squares.isEmpty())
			{
				regions.add(new Region(record.id, record.name, squares));
			}
		}
		return new RegionLines(regions);
	}

	static final class RegionFile
	{
		List<RegionRecord> regions;
	}

	static final class RegionRecord
	{
		String id;
		String name;
		List<int[]> cells;
	}
}
