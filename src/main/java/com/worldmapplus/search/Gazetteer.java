package com.worldmapplus.search;

import com.worldmapplus.data.MapElements;
import com.worldmapplus.layers.RegionLines;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "is this spot in Varrock", "in Kandarin", "in the Dwarven Mines".
 * <p>
 * Three kinds of name are known:
 * <ul>
 * <li>the names of the maps, such as Keldagrim or the Dwarven Mines, which cover everything on them;</li>
 * <li>the lore regions, such as Misthalin and Kandarin, from the region borders data;</li>
 * <li>towns and other places, as a circle around the shops that say they are there. A place name that is
 * only in the shop data, such as Varrock or the Farming Guild, covers the spot where its shops are and
 * a margin around them.</li>
 * </ul>
 * A name matches when what was typed is part of it, so {@code varrock} matches Varrock, South-east Varrock
 * and Varrock Palace.
 */
public final class Gazetteer
{
	/** Least reach of a town's circle, in tiles. */
	static final double MIN_RADIUS = 30;

	/** Greatest reach of a town's circle, in tiles. */
	static final double MAX_RADIUS = 120;

	/** A shop this far from the middle of the shops that share its place name is taken to be a mistake. */
	static final double OUTLIER_TILES = 200;

	/** Margin added to the farthest shop of a town. */
	static final double MARGIN = 15;

	/** A named place, found from where a shop says it is. */
	public static final class Place
	{
		private final String name;
		private final int mapId;
		private final double x;
		private final double y;

		public Place(String name, int mapId, double x, double y)
		{
			this.name = name;
			this.mapId = mapId;
			this.x = x;
			this.y = y;
		}
	}

	private static final class Circle
	{
		final String name;
		final String display;
		final int mapId;
		final double x;
		final double y;
		final double radius;

		Circle(String name, String display, int mapId, double x, double y, double radius)
		{
			this.name = name;
			this.display = display;
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.radius = radius;
		}
	}

	private final List<MapInfo> maps;

	/** Each map's name, normalised once, by map id. */
	private final Map<Integer, String> mapNames = new java.util.HashMap<>();
	private final RegionLines regions;
	private final List<Circle> towns = new ArrayList<>();

	/** A named area taken from one of the game's map labels. It reaches as far as its class allows. */
	private static final class Area
	{
		final String display;
		final String name;
		final double x;
		final double y;

		Area(String display, double x, double y)
		{
			this.display = display;
			this.name = SearchEntry.normalize(display);
			this.x = x;
			this.y = y;
		}
	}

	/** How far from its label an area reaches, in tiles, by the game's text size: small, medium, large. */
	private static final double[] REACH = {20, 45, 260};

	/** Words that mark a label as water. Seas are not places a shop or a monster can be said to be in. */
	private static final String[] WATER = {"ocean", "sea", "bay", "strait", "reef", "coast", "channel", "sound",
		"waters", "shallows", "passage", "belt", "depths", "tides", "expanse", "reach", "shore", "lagoon", "gulf",
		"deep", "narrows", "sound"};

	/** The labels by map and size, each size being one class of area. */
	private final Map<Integer, List<List<Area>>> labelAreas = new java.util.HashMap<>();

	/** What each spot is in, by label, worked out once. */
	private final Map<Long, String[]> spotCache = new java.util.HashMap<>();

	private Gazetteer(List<MapInfo> maps, RegionLines regions)
	{
		this.maps = maps;
		for (MapInfo map : maps)
		{
			mapNames.putIfAbsent(map.getId(), SearchEntry.normalize(map.getName()));
		}
		this.regions = regions;
	}

	public static Gazetteer empty()
	{
		return new Gazetteer(new ArrayList<>(), RegionLines.empty());
	}

	public Gazetteer(MapCatalog catalog, RegionLines regions, List<Place> places)
	{
		this(catalog == null ? new ArrayList<>() : new ArrayList<>(catalog.getMaps()), regions);
		Map<String, List<Place>> groups = new LinkedHashMap<>();
		for (Place place : places)
		{
			String key = SearchEntry.normalize(place.name);
			if (!key.isEmpty())
			{
				groups.computeIfAbsent(key + "|" + place.mapId, k -> new ArrayList<>()).add(place);
			}
		}
		for (List<Place> group : groups.values())
		{
			// The middle by the median and the reach by the farthest of the nearest four fifths, so one
			// shop whose page has the wrong town does not drag a whole circle across the map.
			double[] xs = new double[group.size()];
			double[] ys = new double[group.size()];
			for (int i = 0; i < group.size(); i++)
			{
				xs[i] = group.get(i).x;
				ys[i] = group.get(i).y;
			}
			java.util.Arrays.sort(xs);
			java.util.Arrays.sort(ys);
			double cx = xs[xs.length / 2];
			double cy = ys[ys.length / 2];
			// A shop whose page puts it in the wrong place, such as a garden centre in Falador that says it is in the
			// Farming Guild, is a long way from the others and is left out of the reach.
			List<Double> near = new ArrayList<>();
			for (Place place : group)
			{
				double d = Math.max(Math.abs(place.x - cx), Math.abs(place.y - cy));
				if (d <= OUTLIER_TILES)
				{
					near.add(d);
				}
			}
			double[] away = new double[near.size()];
			for (int i = 0; i < away.length; i++)
			{
				away[i] = near.get(i);
			}
			java.util.Arrays.sort(away);
			double far = away.length == 0 ? 0 : away[Math.min(away.length - 1, (int) Math.ceil(away.length * 0.8) - 1)];
			towns.add(new Circle(SearchEntry.normalize(group.get(0).name), group.get(0).name, group.get(0).mapId, cx, cy,
				Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, far + MARGIN))));
		}
	}

	/**
	 * Takes the game's own map labels as areas. A spot is in the nearest label of each size that is near
	 * enough: within 20 tiles for small text, 45 for medium and 260 for large. A spot in the Farming Guild is then
	 * in the Farming Guild, in the town or place the medium text names, and in the large region around, such
	 * as Kebos Lowlands. Labels of seas are left out.
	 */
	public void addLabels(MapElements elements)
	{
		labelAreas.clear();
		spotCache.clear();
		dropTownsTheLabelsContradict(elements);
		for (MapInfo map : maps)
		{
			List<List<Area>> classes = new ArrayList<>();
			for (int i = 0; i < 3; i++)
			{
				classes.add(new ArrayList<>());
			}
			for (MapElements.Label label : elements.labelsOn(map.getId()))
			{
				int scale = label.getScale();
				if (label.getPlane() != 0 || scale < 0 || scale > 2 || isWater(label.getText()))
				{
					continue;
				}
				classes.get(scale).add(new Area(label.getText().replace('\n', ' '), label.getX(), label.getY()));
			}
			labelAreas.put(map.getId(), classes);
		}
	}

	/**
	 * The towns that were worked out from where shops say they are come second to the game's own labels. A
	 * town name that a label of the game has, but at some other place than the circle's middle, was a
	 * mistake in the shop data, such as a shop that says it is in the Farming Guild while standing in
	 * Falador, and its circle is dropped.
	 */
	private void dropTownsTheLabelsContradict(MapElements elements)
	{
		townsByRegion.clear();
		towns.removeIf(town ->
		{
			boolean labelled = false;
			for (MapElements.Label label : elements.labelsOn(town.mapId))
			{
				if (!allWords(SearchEntry.normalize(label.getText()), town.name))
				{
					continue;
				}
				labelled = true;
				if (Math.max(Math.abs(label.getX() - town.x), Math.abs(label.getY() - town.y)) <= SAME_PLACE_TILES)
				{
					return false;
				}
			}
			return labelled;
		});
	}

	/** A label and a town circle that are this close are the same place. */
	private static final double SAME_PLACE_TILES = 90;

	private static boolean isWater(String text)
	{
		String name = SearchEntry.normalize(text);
		for (String word : WATER)
		{
			for (String part : name.split(" "))
			{
				if (part.equals(word))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The names, normalised, of the label areas a spot is in. At most one for each size of text. */
	String[] labelNamesAt(int mapId, double x, double y)
	{
		List<List<Area>> classes = labelAreas.get(mapId);
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
			Area best = null;
			double bestDistance = REACH[scale];
			for (Area area : classes.get(scale))
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
				found.add(best.name);
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

	private static final Circle[] NO_TOWNS = new Circle[0];

	/** The towns whose names have the region's words in them, kept for the last few regions asked about (one per key press). */
	private final java.util.Map<String, Circle[]> townsByRegion = new java.util.concurrent.ConcurrentHashMap<>();

	private Circle[] townsNamed(String region)
	{
		Circle[] named = townsByRegion.get(region);
		if (named == null)
		{
			List<Circle> found = new ArrayList<>();
			for (Circle town : towns)
			{
				if (allWords(town.name, region))
				{
					found.add(town);
				}
			}
			named = found.isEmpty() ? NO_TOWNS : found.toArray(new Circle[0]);
			if (townsByRegion.size() >= 64)
			{
				townsByRegion.clear();
			}
			townsByRegion.put(region, named);
		}
		return named;
	}

	/** The normalised name and id of each region of the lore map, made once, since a search asks for them for every entry. */
	private final java.util.Map<RegionLines.Region, String[]> loreNames = new java.util.concurrent.ConcurrentHashMap<>();

	private String[] loreNamesOf(RegionLines.Region lore)
	{
		String[] names = loreNames.get(lore);
		if (names == null)
		{
			names = new String[] {SearchEntry.normalize(lore.getName()), SearchEntry.normalize(lore.getId())};
			if (loreNames.size() < 4096)
			{
				loreNames.put(lore, names);
			}
		}
		return names;
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
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		if (region.isEmpty() || limit <= 0)
		{
			return new ArrayList<>();
		}
		List<String> starts = new ArrayList<>();
		List<String> contains = new ArrayList<>();
		java.util.function.Consumer<String> offer = display ->
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
		for (RegionLines.Region lore : regions.getRegions())
		{
			offer.accept(lore.getName());
		}
		for (Circle town : towns)
		{
			offer.accept(town.display);
		}
		for (int scale = 2; scale >= 0; scale--)
		{
			for (List<List<Area>> classes : labelAreas.values())
			{
				for (Area area : classes.get(scale))
				{
					offer.accept(area.display);
				}
			}
		}
		java.util.Comparator<String> shortest = java.util.Comparator.comparingInt(String::length)
			.thenComparing(java.util.Comparator.naturalOrder());
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
			RegionLines.Region lore = regions.regionAt((int) Math.floor(x), (int) Math.floor(y));
			if (lore != null)
			{
				String[] names = loreNamesOf(lore);
				if (allWords(names[0], region) || allWords(names[1], region))
				{
					return true;
				}
			}
		}
		for (Circle town : townsNamed(region))
		{
			if (town.mapId == mapId && Math.max(Math.abs(x - town.x), Math.abs(y - town.y)) <= town.radius)
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
