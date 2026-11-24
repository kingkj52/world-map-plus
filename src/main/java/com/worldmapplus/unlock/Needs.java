package com.worldmapplus.unlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Makes {@link Need}s from the bundled data, one reader for each shape the data has. Each reads a field that says a level or a quest and
 * nothing else: no text is guessed at, and what a reader does not recognise gives no need, so the icon counts as open to everybody.
 */
public final class Needs
{
	private static final Pattern POINT_LEVEL = Pattern.compile("^(?:Mooring, )?([A-Za-z]+) level (\\d{1,3})$");

	private Needs()
	{
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
		return Need.anyOf(Collections.singletonList(Requirement.of(skills, quests, requires)));
	}

	/** A need from several ways of using one icon, each given by its {@code skills} and {@code quests} fields. */
	public static final class Ways
	{
		private final List<Requirement> ways = new ArrayList<>();

		/** Adds one way. */
		public Ways add(@Nullable List<String> skills, @Nullable List<String> quests)
		{
			return add(skills, quests, null);
		}

		/** Adds one way, with its needs in the wiki's words. */
		public Ways add(@Nullable List<String> skills, @Nullable List<String> quests, @Nullable List<String> requires)
		{
			ways.add(Requirement.of(skills, quests, requires));
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
	 * level 62}, {@code Mooring, Sailing level 46}). Only the layers whose points carry such a level are read. Returns an empty
	 * requirement for any other layer or any other text.
	 */
	public static Requirement fromPoint(@Nullable String layer, @Nullable String detail)
	{
		if (detail == null || layer == null || !("tree".equals(layer) || "salvage".equals(layer) || "port".equals(layer)))
		{
			return Requirement.NONE;
		}
		Matcher m = POINT_LEVEL.matcher(detail.trim());
		if (!m.matches())
		{
			return Requirement.NONE;
		}
		return Requirement.of(Collections.singletonList(m.group(2) + " " + m.group(1)), null);
	}

	/**
	 * The need of an icon from the points merged into it: every point must carry a level, and the icon is then open when the player can
	 * use any one of them. A single point that says nothing about a level means the icon is open to everybody.
	 */
	@Nullable
	public static Need fromPoints(List<Requirement> perPoint)
	{
		return Need.anyOf(perPoint);
	}
}
