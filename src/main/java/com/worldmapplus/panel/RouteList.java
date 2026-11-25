package com.worldmapplus.panel;

import com.worldmapplus.pins.*;
import java.util.*;
import java.util.function.*;

/**
 * Chooses and orders the saved routes the side panel lists: a text filter, a loops-only filter and a
 * sort. Pure logic, so it is tested without any window.
 */
public final class RouteList
{
	/** How the list is ordered. */
	public enum Sort
	{
		NAME("Name"),
		NEWEST("Newest first"),
		OLDEST("Oldest first"),
		MOST_STOPS("Most stops"),
		FEWEST_STOPS("Fewest stops"),
		LOOPS_FIRST("Loops first");

		private final String label;

		Sort(String label)
		{
			this.label = label;
		}

		public String label()
		{
			return label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private RouteList()
	{
	}

	/**
	 * @param text words that must all appear in a route's name or in the name of one of its stops
	 * @param loopsOnly keep only routes that go round and round
	 * @param stopName the name of the saved place with an id, or an empty text when it is gone
	 */
	public static List<SavedRoute> apply(List<SavedRoute> routes, String text, boolean loopsOnly, Sort sort,
		LongFunction<String> stopName)
	{
		String[] words = PlaceList.words(text);
		List<SavedRoute> kept = new ArrayList<>();
		for (SavedRoute route : routes)
		{
			if (loopsOnly && !route.isLoop())
			{
				continue;
			}
			if (words.length > 0 && !matches(route, words, stopName))
			{
				continue;
			}
			kept.add(route);
		}
		kept.sort(comparator(sort));
		return kept;
	}

	private static boolean matches(SavedRoute route, String[] words, LongFunction<String> stopName)
	{
		StringBuilder haystack = new StringBuilder(route.getName());
		for (long stop : route.getStops())
		{
			haystack.append(' ').append(stopName.apply(stop));
		}
		String text = haystack.toString().toLowerCase(Locale.ROOT);
		for (String word : words)
		{
			if (!text.contains(word))
			{
				return false;
			}
		}
		return true;
	}

	private static Comparator<SavedRoute> comparator(Sort sort)
	{
		Comparator<SavedRoute> byName = Comparator.comparing(SavedRoute::getName, String.CASE_INSENSITIVE_ORDER)
			.thenComparingLong(SavedRoute::getId);
		switch (sort)
		{
			case NEWEST:
				return Comparator.comparingLong(SavedRoute::getId).reversed();
			case OLDEST:
				return Comparator.comparingLong(SavedRoute::getId);
			case MOST_STOPS:
				return Comparator.comparingInt((SavedRoute r) -> r.getStops().size()).reversed().thenComparing(byName);
			case FEWEST_STOPS:
				return Comparator.comparingInt((SavedRoute r) -> r.getStops().size()).thenComparing(byName);
			case LOOPS_FIRST:
				return Comparator.comparing((SavedRoute r) -> !r.isLoop()).thenComparing(byName);
			default:
				return byName;
		}
	}
}
