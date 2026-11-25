package com.worldmapplus.search;

import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * What was typed in the search bar, split into plain words and filters.
 * <p>
 * Plain words work as they always have: every word has to match somewhere in a result. A word with a
 * colon in front is a filter:
 * <pre>
 *   name:"iron arrow"   words that must be in the name itself
 *   region:varrock      only results in a region, town or map that has that in its name   (also in:, area:)
 *   loc:nearby          only results near the player on the same map                      (also near:me, here)
 *   dist:40             only results within that many tiles of the player
 *   type:shop           only results of a kind: place shop npc monster item quest transport teleport
 *                       doorway point music tile pin route; or an item kind such as melee_weapon   (also kind:)
 *   map:keldagrim       only results on a map with that in its name
 * </pre>
 * A value with spaces goes in quotes. Anything that is not one of these keys stays a plain word, so a
 * search for {@code bob's} or a time such as {@code 12:30} behaves as before.
 */
public final class SearchQuery
{
	/** How far {@code loc:nearby} reaches, in tiles. */
	public static final int NEARBY_TILES = 80;

	/** The keys that name an area. The way to write one is region:"kebos lowlands". */
	private static final Set<String> AREA_KEYS = new LinkedHashSet<>(Arrays.asList(
		"region", "in", "area", "at", "where", "place"));

	private static final Set<String> KEYS = new LinkedHashSet<>(Arrays.asList(
		"name", "region", "in", "area", "at", "where", "place", "loc", "near", "dist", "type", "kind", "map", "sort"));

	/** The kinds of result that type: knows, for completing what is typed. */
	public static final String[] RESULT_KINDS = {"place", "shop", "npc", "monster", "item", "quest", "transport",
		"teleport", "entrance", "point", "music", "tile", "pin", "route"};

	/** The kinds of item that type: knows (the Helper judges them), for completing what is typed. */
	public static final String[] ITEM_KINDS = {"melee_weapon", "ranged_weapon", "magic_weapon", "ammo", "armour", "food", "potion", "tool", "rune",
		"seed", "ore", "bar", "log", "jewellery"};

	/** How results are put in order. */
	public enum Sort
	{
		/** Best match first. */
		RELEVANCE,
		/** Nearest the player first. */
		DISTANCE,
		/** A to Z. */
		NAME
	}

	/** The plain words, normalised, that each have to match a result's name or detail. */
	@Getter
	private final List<String> words = new ArrayList<>();
	/** Words that must be in the name. */
	@Getter
	private final List<String> nameWords = new ArrayList<>();
	@Getter
	private final List<String> regions = new ArrayList<>();
	@Getter
	private final List<String> kinds = new ArrayList<>();
	@Getter
	private final List<String> maps = new ArrayList<>();
	/** Each group of kinds is one type: filter, of which a result needs to be any one kind. */
	@Getter
	private final List<List<String>> kindGroups = new ArrayList<>();
	@Getter
	private final List<String> excludedRegions = new ArrayList<>();
	@Getter
	private final List<String> excludedMaps = new ArrayList<>();
	@Getter
	private final List<String> excludedKinds = new ArrayList<>();
	@Getter
	private Sort sort = Sort.RELEVANCE;
	/** Tiles from the player a result may be, or -1 for no limit. */
	@Getter
	private int maxDistance = -1;
	private boolean badFilter;

	private SearchQuery()
	{
	}

	/** Reads what was typed. Never fails: text that is not understood stays a plain word. */
	public static SearchQuery parse(@Nullable String text)
	{
		SearchQuery query = new SearchQuery();
		if (text == null)
		{
			return query;
		}
		for (String token : tokens(text))
		{
			// A dash in front keeps results out: -region:varrock, -type:shop.
			boolean not = token.length() > 1 && token.charAt(0) == '-';
			String body = not ? token.substring(1) : token;
			int colon = body.indexOf(':');
			String key = colon > 0 ? body.substring(0, colon).toLowerCase(Locale.ROOT) : "";
			if (!KEYS.contains(key) || colon == body.length() - 1)
			{
				query.addWords(token.replace(':', ' '), query.words);
				continue;
			}
			query.apply(key, unquote(body.substring(colon + 1)), not);
		}
		return query;
	}

	private void apply(String key, String value, boolean not)
	{
		String normalized = SearchEntry.normalize(splitCamel(value));
		if (not && !AREA_KEYS.contains(key) && !key.equals("map") && !key.equals("type") && !key.equals("kind"))
		{
			badFilter = true;
			return;
		}
		switch (key)
		{
			case "name":
				addWords(value, nameWords);
				break;
			case "region":
			case "in":
			case "area":
			case "at":
			case "where":
			case "place":
				if (!normalized.isEmpty())
				{
					(not ? excludedRegions : regions).add(normalized);
				}
				break;
			case "map":
				if (!normalized.isEmpty())
				{
					(not ? excludedMaps : maps).add(normalized);
				}
				break;
			case "loc":
			case "near":
				if (normalized.equals("nearby") || normalized.equals("me") || normalized.equals("here")
					|| normalized.equals("close"))
				{
					maxDistance = maxDistance < 0 ? NEARBY_TILES : Math.min(maxDistance, NEARBY_TILES);
				}
				else if (key.equals("near") && !normalized.isEmpty())
				{
					regions.add(normalized);
				}
				else
				{
					badFilter = true;
				}
				break;
			case "dist":
				try
				{
					int tiles = Integer.parseInt(value.trim());
					if (tiles < 0)
					{
						throw new NumberFormatException("negative distance");
					}
					maxDistance = maxDistance < 0 ? tiles : Math.min(maxDistance, tiles);
				}
				catch (NumberFormatException e)
				{
					badFilter = true;
				}
				break;
			case "type":
			case "kind":
				addKinds(value, not);
				break;
			case "sort":
				sort = sortOf(normalized);
				if (sort == null)
				{
					sort = Sort.RELEVANCE;
					badFilter = true;
				}
				break;
			default:
				break;
		}
	}

	/** Puts a space inside a camel case word, so kebosLowlands reads as kebos lowlands. */
	static String splitCamel(String value)
	{
		StringBuilder out = new StringBuilder(value.length() + 4);
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(value.charAt(i - 1)))
			{
				out.append(' ');
			}
			out.append(c);
		}
		return out.toString();
	}

	/** A list of kinds, separated by commas, any of which will do: type:shop,bank. */
	private void addKinds(String value, boolean not)
	{
		List<String> group = new ArrayList<>();
		for (String part : value.split(","))
		{
			String kind = SearchEntry.normalize(splitCamel(part)).replace(' ', '_');
			if (!kind.isEmpty())
			{
				group.add(kind);
			}
		}
		if (group.isEmpty())
		{
			return;
		}
		if (not)
		{
			excludedKinds.addAll(group);
		}
		else
		{
			kindGroups.add(group);
			kinds.addAll(group);
		}
	}

	private static Sort sortOf(String normalized)
	{
		switch (normalized)
		{
			case "dist":
			case "distance":
			case "near":
			case "nearest":
			case "closest":
				return Sort.DISTANCE;
			case "name":
			case "alpha":
			case "alphabetical":
			case "az":
				return Sort.NAME;
			case "relevance":
			case "best":
				return Sort.RELEVANCE;
			default:
				return null;
		}
	}

	private static void addWords(String text, List<String> into)
	{
		String normalized = SearchEntry.normalize(text);
		if (!normalized.isEmpty())
		{
			Collections.addAll(into, normalized.split(" "));
		}
	}

	/** Splits on spaces, keeping a quoted stretch together with the word in front of it. */
	static List<String> tokens(String text)
	{
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == '"')
			{
				quoted = !quoted;
				current.append(c);
			}
			else if (Character.isWhitespace(c) && !quoted)
			{
				if (current.length() > 0)
				{
					result.add(current.toString());
					current.setLength(0);
				}
			}
			else
			{
				current.append(c);
			}
		}
		if (current.length() > 0)
		{
			result.add(current.toString());
		}
		return result;
	}

	private static String unquote(String value)
	{
		String v = value.trim();
		if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
		{
			return v.substring(1, v.length() - 1);
		}
		return v.replace("\"", "");
	}

	/** True when a type: filter names places, which is what brings the many named locations into the results. */
	public boolean asksForPlaces()
	{
		for (List<String> group : kindGroups)
		{
			for (String kind : group)
			{
				if (kind.equals("place") || kind.equals("places") || kind.equals("location") || kind.equals("locations"))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** True when a filter was written that could not be read, such as {@code cost:cheap}. */
	public boolean hasBadFilter()
	{
		return badFilter;
	}

	/** True when anything other than plain words was typed. */
	public boolean hasFilters()
	{
		return !nameWords.isEmpty() || !regions.isEmpty() || !kinds.isEmpty() || !maps.isEmpty() || maxDistance >= 0
			|| badFilter || !excludedRegions.isEmpty() || !excludedMaps.isEmpty()
			|| !excludedKinds.isEmpty() || sort != Sort.RELEVANCE;
	}

	/** True when the text names a place or a map to be in or out of. */
	public boolean hasPlaceFilter()
	{
		return !regions.isEmpty() || !maps.isEmpty() || !excludedRegions.isEmpty() || !excludedMaps.isEmpty();
	}

	/** True when there is something to look for at all. */
	public boolean isEmpty()
	{
		return words.isEmpty() && !hasFilters();
	}
}
