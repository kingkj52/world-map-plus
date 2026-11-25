package com.worldmapplus.panel;

import com.worldmapplus.pins.*;
import java.util.*;
import java.util.function.*;

/**
 * Chooses and orders the saved places the side panel lists: a text filter, a type filter and a sort.
 * Pure logic, so it is tested without any window.
 */
public final class PlaceList
{
	/** The type filter value that lets every type through. */
	public static final String ALL_TYPES = "All types";

	/** How the list is ordered. */
	public enum Sort
	{
		NAME("Name"),
		NEWEST("Newest first"),
		OLDEST("Oldest first"),
		TYPE("Type"),
		AREA("Area");

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

	private PlaceList()
	{
	}

	/**
	 * @param text words that must all appear in a place's name, type or area, in any order; blank for none
	 * @param type a type to keep, or {@link #ALL_TYPES}
	 * @param areaName the name of the map a place is on
	 */
	public static List<Pin> apply(List<Pin> pins, String text, String type, Sort sort, IntFunction<String> areaName)
	{
		String[] words = words(text);
		boolean anyType = type == null || type.isEmpty() || ALL_TYPES.equals(type);
		List<Pin> kept = new ArrayList<>();
		for (Pin pin : pins)
		{
			if (!anyType && !pin.getKind().equalsIgnoreCase(type))
			{
				continue;
			}
			if (words.length > 0 && !matches(pin, words, areaName))
			{
				continue;
			}
			kept.add(pin);
		}
		kept.sort(comparator(sort, areaName));
		return kept;
	}

	/** The types in use, sorted, for the type filter. */
	public static List<String> typesOf(List<Pin> pins)
	{
		TreeSet<String> kinds = new TreeSet<>();
		for (Pin pin : pins)
		{
			kinds.add(pin.getKind());
		}
		return new ArrayList<>(kinds);
	}

	/** The word a type is shown as. */
	public static String typeLabel(String kind)
	{
		return kind.isEmpty() ? kind : Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
	}

	static String[] words(String text)
	{
		String trimmed = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
	}

	private static boolean matches(Pin pin, String[] words, IntFunction<String> areaName)
	{
		String haystack = (pin.getName() + " " + pin.getKind() + " " + areaName.apply(pin.getMap())).toLowerCase(Locale.ROOT);
		for (String word : words)
		{
			if (!haystack.contains(word))
			{
				return false;
			}
		}
		return true;
	}

	private static Comparator<Pin> comparator(Sort sort, IntFunction<String> areaName)
	{
		Comparator<Pin> byName = Comparator.comparing(Pin::getName, String.CASE_INSENSITIVE_ORDER)
			.thenComparingLong(Pin::getId);
		switch (sort)
		{
			case NEWEST:
				return Comparator.comparingLong(Pin::getCreated).reversed()
					.thenComparing(Comparator.comparingLong(Pin::getId).reversed());
			case OLDEST:
				return Comparator.comparingLong(Pin::getCreated).thenComparingLong(Pin::getId);
			case TYPE:
				return Comparator.comparing(Pin::getKind).thenComparing(byName);
			case AREA:
				return Comparator.comparing((Pin pin) -> areaName.apply(pin.getMap()), String.CASE_INSENSITIVE_ORDER)
					.thenComparing(byName);
			default:
				return byName;
		}
	}
}
