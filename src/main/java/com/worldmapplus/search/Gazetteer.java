package com.worldmapplus.search;

import com.worldmapplus.map.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.*;

/**
 * Answers "is this spot in Varrock", "in Kandarin", "in the Dwarven Mines".
 * <p>
 * Three kinds of name are known:
 * <ul>
 * <li>the names of the maps, such as Keldagrim or the Dwarven Mines, which cover everything on them;</li>
 * <li>the lore regions, such as Misthalin and Kandarin, from the regions table;</li>
 * <li>towns and other places, as a square around the shops that say they are there, and the game's own map labels, each
 * reaching as far as its text size allows. Both are worked out at build time by {@code tools/build_places.py}.</li>
 * </ul>
 * A name matches when what was typed is part of it, so {@code varrock} matches Varrock, South-east Varrock
 * and Varrock Palace.
 */
public final class Gazetteer
{
	/**
	 * One record of {@code places.json}, made by {@code tools/build_places.py}: the square of a town or an area named by one of the
	 * game's labels. Filled by Gson.
	 */
	static final class Named
	{
		String n;
		int m;
		/** The game's text size of an area's label: 0 small, 1 medium, 2 large. */
		int s;
		double x;
		double y;
		/** How far a town reaches from its middle, in tiles. */
		double r;
		/** The name, normalised once. */
		transient String key;
	}

	/** One lore region of {@code regions.json}, made by {@code tools/build_regions.py}, as the 64 tile squares it has. Filled by Gson. */
	static final class Lore
	{
		String id;
		String name;
		List<int[]> cells;
		/** The name and the id, normalised once. */
		transient String[] keys;
	}

	/** The shape of {@code regions.json}. Filled by Gson. */
	public static final class Regions
	{
		List<Lore> regions;
	}

	/** The shape of {@code places.json}. Filled by Gson. */
	public static final class Places
	{
		List<Named> towns;
		List<Named> areas;
	}

	private final List<MapInfo> maps;

	/** Each map's name, normalised once, by map id. */
	private final Map<Integer, String> mapNames = new HashMap<>();
	/** The lore regions that have a square, in the file's order, and the one each square is in. */
	private final List<Lore> lore = new ArrayList<>();
	private final Map<Long, Lore> loreBySquare = new HashMap<>();
	private final List<Named> towns = new ArrayList<>();

	/** How far from its label an area reaches, in tiles, by the game's text size: small, medium, large. */
	private static final double[] REACH = {20, 45, 260};

	/** The labels by map and size, each size being one class of area. */
	private final Map<Integer, List<List<Named>>> labelAreas = new HashMap<>();

	/** What each spot is in, by label, worked out once. */
	private final Map<Long, String[]> spotCache = new HashMap<>();

	private Gazetteer(List<MapInfo> maps)
	{
		this.maps = maps;
		for (MapInfo map : maps)
		{
			mapNames.putIfAbsent(map.getId(), SearchEntry.normalize(map.getName()));
		}
	}

	public static Gazetteer empty()
	{
		return new Gazetteer(new ArrayList<>());
	}

	/**
	 * @param regions the lore regions of the surface. A square two regions claim is the first one's.
	 * @param file the towns and the game's labels as areas, from {@code places.json}. A spot is in the nearest label of each size that is
	 * near enough, and in every town whose square holds it.
	 */
	public Gazetteer(MapCatalog catalog, @Nullable Regions regions, @Nullable Places file)
	{
		this(catalog == null ? new ArrayList<>() : new ArrayList<>(catalog.getMaps()));
		for (Lore region : regions == null || regions.regions == null ? Collections.<Lore>emptyList() : regions.regions)
		{
			if (region == null || region.id == null || region.name == null || region.cells == null)
			{
				continue;
			}
			boolean any = false;
			for (int[] cell : region.cells)
			{
				any |= cell != null && cell.length >= 2 && loreBySquare.putIfAbsent(square(cell[0], cell[1]), region) == null;
			}
			if (any)
			{
				region.keys = new String[] {SearchEntry.normalize(region.name), SearchEntry.normalize(region.id)};
				lore.add(region);
			}
		}
		for (MapInfo map : maps)
		{
			labelAreas.put(map.getId(), Arrays.asList(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
		}
		if (file == null)
		{
			return;
		}
		towns.addAll(named(file.towns));
		for (Named area : named(file.areas))
		{
			List<List<Named>> classes = labelAreas.get(area.m);
			if (classes != null && area.s >= 0 && area.s < 3)
			{
				classes.get(area.s).add(area);
			}
		}
	}

	/** The records that have a name, each with its name normalised. */
	private static List<Named> named(@Nullable List<Named> records)
	{
		List<Named> found = new ArrayList<>();
		if (records != null)
		{
			for (Named record : records)
			{
				if (record != null && record.n != null)
				{
					record.key = SearchEntry.normalize(record.n);
					found.add(record);
				}
			}
		}
		return found;
	}

	/** The names, normalised, of the label areas a spot is in. At most one for each size of text. */
	String[] labelNamesAt(int mapId, double x, double y)
	{
		List<List<Named>> classes = labelAreas.get(mapId);
		if (classes == null)
		{
			return NO_NAMES;
		}
		long key = ((long) mapId << 40) | ((long) ((int) x & 0xFFFFF) << 20) | ((int) y & 0xFFFFF);
		String[] cached = spotCache.get(key);
		if (cached != null)
		{
			return cached;
		}
		List<String> found = new ArrayList<>(3);
		for (int scale = 0; scale < 3; scale++)
		{
			Named best = null;
			double bestDistance = REACH[scale];
			for (Named area : classes.get(scale))
			{
				double d = Math.max(Math.abs(area.x - x), Math.abs(area.y - y));
				if (d <= bestDistance)
				{
					bestDistance = d;
					best = area;
				}
			}
			if (best != null)
			{
				found.add(best.key);
			}
		}
		String[] names = found.toArray(new String[0]);
		if (spotCache.size() > MAX_CACHED_SPOTS)
		{
			spotCache.clear();
		}
		spotCache.put(key, names);
		return names;
	}

	private static final String[] NO_NAMES = new String[0];
	private static final int MAX_CACHED_SPOTS = 60_000;

	/** True when every word of {@code region} is in {@code name}, in any order. */
	static boolean allWords(String name, String region)
	{
		// Each space separated word of the region has to be somewhere in the name. Makes no garbage: it runs for every entry at every
		// key press of a search with a region in it.
		int length = region.length();
		int start = 0;
		while (start < length)
		{
			int end = region.indexOf(' ', start);
			if (end < 0)
			{
				end = length;
			}
			if (end > start && !containsRange(name, region, start, end))
			{
				return false;
			}
			start = end + 1;
		}
		return true;
	}

	private static boolean containsRange(String text, String word, int from, int to)
	{
		int n = to - from;
		for (int i = 0; i + n <= text.length(); i++)
		{
			if (text.regionMatches(i, word, from, n))
			{
				return true;
			}
		}
		return false;
	}

	private static final Named[] NO_TOWNS = new Named[0];

	/** The towns whose names have the region's words in them, kept for the last few regions asked about (one per key press). */
	private final Map<String, Named[]> townsByRegion = new ConcurrentHashMap<>();

	private Named[] townsNamed(String region)
	{
		Named[] named = townsByRegion.get(region);
		if (named == null)
		{
			List<Named> found = new ArrayList<>();
			for (Named town : towns)
			{
				if (allWords(town.key, region))
				{
					found.add(town);
				}
			}
			named = found.isEmpty() ? NO_TOWNS : found.toArray(new Named[0]);
			if (townsByRegion.size() >= 64)
			{
				townsByRegion.clear();
			}
			townsByRegion.put(region, named);
		}
		return named;
	}

	private static long square(int sx, int sy)
	{
		return ((long) sx << 32) | (sy & 0xFFFFFFFFL);
	}

	/** True when anything here is called by a name with these words in it. */
	public boolean knows(String region)
	{
		return !suggest(region, 1).isEmpty();
	}

	/**
	 * Names of areas that match what has been typed so far, best first, for helping someone find the right
	 * words: the ones that start with it before the ones that only contain it.
	 */
	public List<String> suggest(String typed, int limit)
	{
		String region = SearchEntry.normalize(typed);
		Set<String> seen = new LinkedHashSet<>();
		if (region.isEmpty() || limit <= 0)
		{
			return new ArrayList<>();
		}
		List<String> starts = new ArrayList<>();
		List<String> contains = new ArrayList<>();
		Consumer<String> offer = display ->
		{
			String name = SearchEntry.normalize(display);
			if (!name.isEmpty() && allWords(name, region) && seen.add(name))
			{
				(name.startsWith(region) ? starts : contains).add(display);
			}
		};
		for (MapInfo map : maps)
		{
			offer.accept(map.getName());
		}
		for (Lore each : lore)
		{
			offer.accept(each.name);
		}
		for (Named town : towns)
		{
			offer.accept(town.n);
		}
		for (int scale = 2; scale >= 0; scale--)
		{
			for (List<List<Named>> classes : labelAreas.values())
			{
				for (Named area : classes.get(scale))
				{
					offer.accept(area.n);
				}
			}
		}
		Comparator<String> shortest = Comparator.comparingInt(String::length)
			.thenComparing(Comparator.naturalOrder());
		starts.sort(shortest);
		contains.sort(shortest);
		List<String> result = new ArrayList<>(starts);
		result.addAll(contains);
		return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
	}

	/** True when the map with this id has {@code name} in its name. */
	public boolean isMapNamed(String name, int mapId)
	{
		String known = mapNames.get(mapId);
		return known != null && allWords(known, name);
	}

	/** True when something at a spot is inside a place whose name has {@code region} in it. */
	public boolean contains(String region, int mapId, double x, double y)
	{
		if (isMapNamed(region, mapId))
		{
			return true;
		}
		if (mapId == MapInfo.SURFACE)
		{
			Lore at = loreBySquare.get(square(Math.floorDiv((int) Math.floor(x), 64), Math.floorDiv((int) Math.floor(y), 64)));
			if (at != null)
			{
				if (allWords(at.keys[0], region) || allWords(at.keys[1], region))
				{
					return true;
				}
			}
		}
		for (Named town : townsNamed(region))
		{
			if (town.m == mapId && Math.max(Math.abs(x - town.x), Math.abs(y - town.y)) <= town.r)
			{
				return true;
			}
		}
		for (String name : labelNamesAt(mapId, x, y))
		{
			if (allWords(name, region))
			{
				return true;
			}
		}
		return false;
	}

}
