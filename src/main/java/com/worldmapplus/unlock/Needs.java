package com.worldmapplus.unlock;

import java.util.*;
import java.util.regex.*;
import javax.annotation.*;

/**
 * Makes {@link Need}s from the bundled data, one reader for each shape the data has. Each reads fields that say a level or a quest and
 * keeps their words as they are: what they mean is for the Helper to work out, and what it does not understand is never held against
 * the player.
 */
public final class Needs
{
	private static final Pattern POINT_LEVEL = Pattern.compile("^(?:Mooring, )?([A-Za-z]+) level (\\d{1,3})$");

	/** A way that asks for nothing. */
	public static final List<List<String>> NO_WAY = way(null, null, null);

	private Needs()
	{
	}

	/** One way of using something: its levels, its quests and what the wiki words otherwise (any may be null). */
	public static List<List<String>> way(@Nullable List<String> skills, @Nullable List<String> quests, @Nullable List<String> requires)
	{
		List<List<String>> way = new ArrayList<>(3);
		for (List<String> texts : Arrays.asList(skills, quests, requires))
		{
			List<String> kept = new ArrayList<>();
			for (String text : texts == null ? Collections.<String>emptyList() : texts)
			{
				if (text != null && !text.trim().isEmpty())
				{
					kept.add(text.trim());
				}
			}
			way.add(Collections.unmodifiableList(kept));
		}
		return Collections.unmodifiableList(way);
	}

	/** A need from the {@code skills} and {@code quests} fields of one way of using something, or null when it asks nothing. */
	@Nullable
	public static Need ofWay(@Nullable List<String> skills, @Nullable List<String> quests)
	{
		return ofWay(skills, quests, null);
	}

	/** The same with the third list of needs in the wiki's words (diary tiers, quest points, levels, quests), or null. */
	@Nullable
	public static Need ofWay(@Nullable List<String> skills, @Nullable List<String> quests, @Nullable List<String> requires)
	{
		return Need.anyOf(Collections.singletonList(way(skills, quests, requires)));
	}

	/** A need from several ways of using one icon, each given by its {@code skills} and {@code quests} fields. */
	public static final class Ways
	{
		private final List<List<List<String>>> ways = new ArrayList<>();

		/** Adds one way. */
		public Ways add(@Nullable List<String> skills, @Nullable List<String> quests)
		{
			return add(skills, quests, null);
		}

		/** Adds one way, with its needs in the wiki's words. */
		public Ways add(@Nullable List<String> skills, @Nullable List<String> quests, @Nullable List<String> requires)
		{
			ways.add(way(skills, quests, requires));
			return this;
		}

		/** The need: null when there is no way, or when any way asks nothing. */
		@Nullable
		public Need build()
		{
			return Need.anyOf(ways);
		}
	}

	/** A need from the rows of an icon's note, where a row called {@code Requires} holds levels such as {@code 61 Agility}. */
	@Nullable
	public static Need fromRows(@Nullable List<List<String>> rows)
	{
		if (rows == null)
		{
			return null;
		}
		for (List<String> row : rows)
		{
			if (row != null && row.size() >= 2 && "Requires".equals(row.get(0)))
			{
				return ofWay(Collections.singletonList(row.get(1)), null);
			}
		}
		return null;
	}

	/**
	 * What a point of interest merged into an icon says about the level it asks for, in the fixed words the data uses ({@code Woodcutting
	 * level 62}, {@code Mooring, Sailing level 46}). Only the layers whose points carry such a level are read. Returns {@link #NO_WAY}
	 * for any other layer or any other text.
	 */
	public static List<List<String>> fromPoint(@Nullable String layer, @Nullable String detail)
	{
		if (detail == null || layer == null || !("tree".equals(layer) || "salvage".equals(layer) || "port".equals(layer)))
		{
			return NO_WAY;
		}
		Matcher m = POINT_LEVEL.matcher(detail.trim());
		return m.matches() ? way(Collections.singletonList(m.group(2) + " " + m.group(1)), null, null) : NO_WAY;
	}

	/**
	 * The need of an icon from the points merged into it: every point must carry a level, and the icon is then open when the player can
	 * use any one of them. A single point that says nothing about a level means the icon is open to everybody.
	 */
	@Nullable
	public static Need fromPoints(List<List<List<String>>> perPoint)
	{
		return Need.anyOf(perPoint);
	}
}
