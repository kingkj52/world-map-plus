package com.worldmapplus.data;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The named places of the world, for searching with {@code type:place}: the game's own map labels, every place the
 * wiki's shop and spawn data says something is at ("Jolly Boar Inn", "Lumbridge Castle", "Slayer Tower"), and the
 * pubs and one-of-a-kind services the game's map has icons for.
 * <p>
 * The wiki says where in free text, often as a town and a detail ("Aldarin - Alchemical Society"). The town and
 * the detail are each a place, a detail that is only a direction ("behind the castle") is not, and the tiles
 * the wiki gives for a place are what fix where it is. The same name found in several spots is one place when the
 * spots are near each other, and the game's own label is where that place is. Names that differ only by a
 * direction ("West Heroes Guild dungeon") are one place when they are close together.
 * <p>
 * These are many, and most of a search's other results would drown in them, so each one is marked as a
 * location and is only searched when a search asks for places.
 */
@Slf4j
public final class PlaceLoader
{
	private static final String SHOPS = "/com/worldmapplus/shops.json";

	/** Tiles within which the places of one name are taken to be the same place. */
	private static final int SAME_PLACE_TILES = 140;

	/** The same, for caves and other places that spread out underground. */
	private static final int SAME_CAVE_TILES = 320;

	/** Tiles within which names that differ by a direction are taken to be one place. */
	private static final int SAME_VARIANT_TILES = 70;

	/** Most results for one name on one map. */
	private static final int MAX_PER_NAME = 3;

	/** Tiles from a pub that a name for it can be taken from. */
	private static final int NAME_REACH = 14;

	/** How far a pub's town is looked for. */
	private static final int TOWN_REACH = 60;

	/** Words in a label that make it a region and not a town. */
	private static final String[] REGION_WORDS = {"kingdom", "sea", "ocean", "desert", "wilderness", "lowlands", "islands",
		"mountains", "continent", "realm", "river"};

	private static final int MIN_LENGTH = 3;
	private static final int MAX_LENGTH = 45;

	/** The marker on an entry that is only searched when places are asked for. */
	public static final String LOCATION = "location";

	/** Kinds of icon on the game's map that are each a place worth going to, found by the name the map key gives them. */
	private static final List<String> SERVICE_ICONS = Arrays.asList("Grand Exchange", "Clan Hub", "Death's Office",
		"Combat Achievements", "Raid", "Makeover Mage", "Estate Agent", "Hairdresser", "Taxidermist", "Pet shop",
		"Stonemason", "Sawmill", "Task Master", "Pricing expert", "Speedrunning shop", "Bounty Hunter trader");

	private static final String PUB_ICON = "Pub";

	/** Words that start a description of where a thing is and not the name of a place, as in "behind the castle". */
	private static final Set<String> DIRECTIONS = new HashSet<>(Arrays.asList("north", "south", "east", "west", "northern",
		"southern", "eastern", "western", "northeast", "northwest", "southeast", "southwest"));

	/** Words that mean a description whatever follows, as in "Behind The Legless Faun pub". */
	private static final Set<String> POSITIONS = new HashSet<>(Arrays.asList("behind", "outside", "near", "along", "inside",
		"in", "on", "by", "next", "during", "at", "above", "below", "under", "between", "beside", "across", "around", "just",
		"within", "beyond", "past", "towards", "upstairs", "downstairs"));

	/** Words that start a description when what follows them is not a capitalised name. */
	private static final Set<String> DESCRIPTIVE = new HashSet<>(Arrays.asList("top", "bottom", "ground", "first", "second",
		"third", "fourth", "floor", "level", "one", "two", "three", "entrance", "exit", "back", "front", "side", "corner",
		"center", "centre", "middle"));

	private static final String[] CAVE_WORDS = {"cave", "cavern", "dungeon", "mine", "tunnel", "sewer", "basement", "cellar",
		"underground", "crypt", "catacomb", "lair", "pit", "hole"};

	private PlaceLoader()
	{
	}

	/** One spot a place name was found at. */
	private static final class Found
	{
		final String name;
		final String parent;
		final String detail;
		final int mapId;
		final double x;
		final double y;
		final boolean label;

		Found(String name, String parent, String detail, int mapId, double x, double y, boolean label)
		{
			this.name = name;
			this.parent = parent;
			this.detail = detail;
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.label = label;
		}
	}

	/** A place: every spot its name was found at, near each other. */
	private static final class Place
	{
		final List<Found> spots = new ArrayList<>();
		String name;
		String detail;
		int mapId;
		double x;
		double y;
	}

	/** The place names inside a piece of the wiki's "where" text, a town first and then what is in it. */
	static List<String> names(String where)
	{
		List<String> result = new ArrayList<>();
		if (where == null || where.contains("&#"))
		{
			return result;
		}
		String text = where.replaceAll("\\s*\\([^)]*\\)\\s*", " ").trim();
		for (String part : text.split("\\s+[-\u2013]\\s+|,\\s+|:\\s+"))
		{
			String name = part.replaceAll("[\\s\\-\u2013,:;.]+$", "").trim();
			if (acceptable(name) && !result.contains(name))
			{
				result.add(name);
			}
		}
		return result;
	}

	private static boolean acceptable(String name)
	{
		if (name.length() < MIN_LENGTH || name.length() > MAX_LENGTH)
		{
			return false;
		}
		char first = name.charAt(0);
		if (!Character.isUpperCase(first) && !Character.isDigit(first))
		{
			return false;
		}
		String[] words = name.split("\\s+");
		String head = words[0].toLowerCase(Locale.ROOT);
		boolean single = words.length == 1;
		if (!single && POSITIONS.contains(head))
		{
			return false;
		}
		if (!single && isDirection(head))
		{
			// "North-west of Lumbridge" is a description and "East Ardougne" is a name.
			String second = words[1];
			if (second.equals("of") || Character.isLowerCase(second.charAt(0)))
			{
				return false;
			}
		}
		if (!single && DESCRIPTIVE.contains(head) && Character.isLowerCase(words[1].charAt(0)))
		{
			return false;
		}
		// A lone direction or position word is not a place.
		return !(single && (isDirection(head) || POSITIONS.contains(head) || DESCRIPTIVE.contains(head)));
	}

	/** True for a direction word, or two joined by a dash, as in "north-west". */
	private static boolean isDirection(String word)
	{
		for (String part : word.split("-"))
		{
			if (!DIRECTIONS.contains(part))
			{
				return false;
			}
		}
		return !word.isEmpty();
	}

	/** Builds the entries from the bundled data and the game's labels. */
	public static List<SearchEntry> build(Gson gson, MapCatalog catalog, MapElements elements)
	{
		List<Found> found = new ArrayList<>();
		SpawnLoader.SpawnFile spawns = SpawnFormat.bundled();
		if (spawns != null)
		{
			groups(found, spawns.monsters, catalog);
			groups(found, spawns.npcs, catalog);
			groups(found, spawns.items, catalog);
		}
		HotspotLoader.ShopFile shops = read(gson, SHOPS, HotspotLoader.ShopFile.class);
		if (shops != null && shops.shops != null)
		{
			for (HotspotLoader.Shop shop : shops.shops)
			{
				if (shop != null && shop.where != null)
				{
					MapInfo map = catalog.mapFor(shop.x, shop.y);
					if (map != null)
					{
						add(found, shop.where, map.getId(), shop.x + 0.5, shop.y + 0.5);
					}
				}
			}
		}
		for (MapInfo map : catalog.getMaps())
		{
			for (MapElements.Label label : elements.labelsOn(map.getId()))
			{
				if (label.getPlane() == 0)
				{
					String text = label.getText().replace('\n', ' ').trim();
					if (text.length() >= MIN_LENGTH)
					{
						found.add(new Found(text, null, "Area name", map.getId(), label.getX(), label.getY(), true));
					}
				}
			}
		}
		pubs(found, catalog, elements);
		services(found, catalog, elements);
		return cluster(found);
	}

	private static void groups(List<Found> found, List<SpawnLoader.Named> named, MapCatalog catalog)
	{
		if (named == null)
		{
			return;
		}
		for (SpawnLoader.Named creature : named)
		{
			if (creature == null || creature.groups == null)
			{
				continue;
			}
			for (SpawnLoader.Group group : creature.groups)
			{
				if (group == null || group.where == null || group.pts == null)
				{
					continue;
				}
				// A few of a group's tiles say where the place is. The rest would only repeat it.
				for (int i = 0; i + 1 < group.pts.length && i < 16; i += 2)
				{
					MapInfo map = catalog.mapFor(group.pts[i], group.pts[i + 1]);
					if (map != null)
					{
						add(found, group.where, map.getId(), group.pts[i] + 0.5, group.pts[i + 1] + 0.5);
					}
				}
			}
		}
	}

	private static void add(List<Found> found, String where, int mapId, double x, double y)
	{
		List<String> names = names(where);
		for (int i = 0; i < names.size(); i++)
		{
			String parent = i == 0 ? null : names.get(0);
			found.add(new Found(names.get(i), parent, parent == null ? "Place" : "Place in " + parent, mapId, x, y, false));
		}
	}

	/**
	 * The game's map has an icon for every pub. One the wiki's text already names is left as it is, and the others
	 * are called "Pub" and the town they are in.
	 */
	private static void pubs(List<Found> found, MapCatalog catalog, MapElements elements)
	{
		Integer category = elements.categoryNamed(PUB_ICON);
		if (category == null)
		{
			return;
		}
		for (MapInfo map : catalog.getMaps())
		{
			for (MapElements.Spot spot : elements.spotsOn(map.getId()))
			{
				if (spot.getCategory() != category)
				{
					continue;
				}
				boolean named = false;
				for (Found other : found)
				{
					if (other.mapId == map.getId() && !other.label && isDrinkingPlace(other.name)
						&& Math.max(Math.abs(other.x - spot.getX()), Math.abs(other.y - spot.getY())) <= NAME_REACH)
					{
						named = true;
						break;
					}
				}
				if (!named)
				{
					String town = townNear(elements, map.getId(), spot.getX(), spot.getY());
					String name = town == null ? "Pub" : "Pub in " + town;
					found.add(new Found(name, town, "Pub", map.getId(), spot.getX(), spot.getY(), false));
				}
			}
		}
	}

	private static boolean isDrinkingPlace(String name)
	{
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.contains("inn") || lower.contains("pub") || lower.contains("tavern") || lower.contains("bar ")
			|| lower.endsWith(" bar") || lower.contains("saloon") || lower.contains("arms");
	}

	/** The nearest of the game's larger labels, as a name for where a thing is. */
	private static String townNear(MapElements elements, int mapId, double x, double y)
	{
		String best = null;
		double bestDistance = TOWN_REACH;
		for (MapElements.Label label : elements.labelsOn(mapId))
		{
			if (label.getPlane() != 0 || label.getScale() < 1 || isRegion(label.getText()))
			{
				continue;
			}
			double d = Math.hypot(label.getX() - x, label.getY() - y);
			if (d < bestDistance)
			{
				bestDistance = d;
				best = label.getText().replace('\n', ' ').trim();
			}
		}
		return best;
	}

	private static boolean isRegion(String text)
	{
		for (String word : text.toLowerCase(Locale.ROOT).split("\\s+"))
		{
			for (String region : REGION_WORDS)
			{
				if (word.equals(region))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The one-of-a-kind services the game's map has icons for are places, named as the map key names them. */
	private static void services(List<Found> found, MapCatalog catalog, MapElements elements)
	{
		for (String service : SERVICE_ICONS)
		{
			Integer category = elements.categoryNamed(service);
			if (category == null)
			{
				continue;
			}
			String name = elements.categoryName(category);
			for (MapInfo map : catalog.getMaps())
			{
				for (MapElements.Spot spot : elements.spotsOn(map.getId()))
				{
					if (spot.getCategory() == category)
					{
						found.add(new Found(name, null, "Place", map.getId(), spot.getX(), spot.getY(), false));
					}
				}
			}
		}
	}

	/** The name with its direction and its article taken off, as the same place is found under both. */
	static String variantKey(String name)
	{
		String key = SearchEntry.normalize(name);
		String[] words = key.split(" ");
		int start = 0;
		while (start < words.length - 1 && (words[start].equals("the") || DIRECTIONS.contains(words[start])))
		{
			start++;
		}
		return String.join(" ", Arrays.copyOfRange(words, start, words.length));
	}

	private static int reachOf(String name)
	{
		String lower = name.toLowerCase(Locale.ROOT);
		for (String word : CAVE_WORDS)
		{
			if (lower.contains(word))
			{
				return SAME_CAVE_TILES;
			}
		}
		return SAME_PLACE_TILES;
	}

	/** One entry for each cluster of tiles a name was found at, with direction variants joined. */
	static List<SearchEntry> cluster(List<Found> found)
	{
		Map<String, List<Place>> byName = new LinkedHashMap<>();
		for (Found spot : found)
		{
			String key = SearchEntry.normalize(spot.name) + "|" + spot.mapId;
			List<Place> places = byName.computeIfAbsent(key, k -> new ArrayList<>());
			int reach = reachOf(spot.name);
			Place home = null;
			for (Place place : places)
			{
				Found first = place.spots.get(0);
				if (Math.max(Math.abs(first.x - spot.x), Math.abs(first.y - spot.y)) <= reach)
				{
					home = place;
					break;
				}
			}
			if (home != null)
			{
				home.spots.add(spot);
			}
			else if (places.size() < MAX_PER_NAME)
			{
				Place fresh = new Place();
				fresh.spots.add(spot);
				places.add(fresh);
			}
		}
		List<Place> all = new ArrayList<>();
		for (List<Place> places : byName.values())
		{
			for (Place place : places)
			{
				settle(place);
				all.add(place);
			}
		}
		return joinVariants(all);
	}

	/** Where a place is and what to say about it: its label if the game has one, else its middle. */
	private static void settle(Place place)
	{
		double sumX = 0;
		double sumY = 0;
		for (Found spot : place.spots)
		{
			sumX += spot.x;
			sumY += spot.y;
		}
		double cx = sumX / place.spots.size();
		double cy = sumY / place.spots.size();
		Found best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Found spot : place.spots)
		{
			double d = Math.hypot(spot.x - cx, spot.y - cy) - (spot.label ? 10_000 : 0);
			if (d < bestDistance)
			{
				bestDistance = d;
				best = spot;
			}
		}
		place.name = best.name;
		place.mapId = best.mapId;
		place.x = best.x;
		place.y = best.y;
		place.detail = best.label ? "Area name" : best.detail;
	}

	/** Places whose names differ only by a direction or an article, and that are close, are one place. */
	private static List<SearchEntry> joinVariants(List<Place> places)
	{
		Map<String, List<Place>> byVariant = new LinkedHashMap<>();
		for (Place place : places)
		{
			byVariant.computeIfAbsent(variantKey(place.name) + "|" + place.mapId, k -> new ArrayList<>()).add(place);
		}
		List<SearchEntry> entries = new ArrayList<>();
		for (List<Place> group : byVariant.values())
		{
			List<Place> kept = new ArrayList<>();
			for (Place place : group)
			{
				Place twin = null;
				for (Place other : kept)
				{
					if (Math.max(Math.abs(other.x - place.x), Math.abs(other.y - place.y)) <= SAME_VARIANT_TILES)
					{
						twin = other;
						break;
					}
				}
				if (twin == null)
				{
					kept.add(place);
				}
				else if (prefer(place, twin))
				{
					kept.set(kept.indexOf(twin), place);
				}
			}
			for (Place place : kept)
			{
				entries.add(new SearchEntry(SearchKind.PLACE, place.name, place.detail, place.mapId, place.x, place.y,
					Collections.emptyList(), -1, LOCATION));
			}
		}
		return entries;
	}

	/** Whether a place is a better name for what two stand for: the game's own label, then the plain name. */
	private static boolean prefer(Place candidate, Place current)
	{
		boolean candidateLabel = "Area name".equals(candidate.detail);
		boolean currentLabel = "Area name".equals(current.detail);
		if (candidateLabel != currentLabel)
		{
			return candidateLabel;
		}
		return candidate.name.length() < current.name.length();
	}

	private static <T> T read(Gson gson, String resource, Class<T> type)
	{
		try (InputStream in = PlaceLoader.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				log.debug("World Map Plus: {} is missing from the plugin jar", resource);
				return null;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return gson.fromJson(reader, type);
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("World Map Plus: could not read {}", resource, e);
			return null;
		}
	}
}
