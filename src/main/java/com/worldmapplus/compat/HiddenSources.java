package com.worldmapplus.compat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The plugins whose points on the game's map are not shown here, kept in the settings as one list of plugin names
 * separated by a bar. Parsing is forgiving, so a hand edited setting cannot cause an error.
 */
public final class HiddenSources
{
	private static final String SEPARATOR = "|";

	/** The most names kept, so a setting cannot grow without end. */
	static final int MOST = 200;

	private HiddenSources()
	{
	}

	public static Set<String> parse(String text)
	{
		Set<String> names = new LinkedHashSet<>();
		if (text == null)
		{
			return names;
		}
		for (String piece : text.split("\\|"))
		{
			String name = piece.trim();
			if (!name.isEmpty() && names.size() < MOST)
			{
				names.add(name);
			}
		}
		return names;
	}

	public static String format(Set<String> names)
	{
		String[] sorted = names.toArray(new String[0]);
		Arrays.sort(sorted);
		return String.join(SEPARATOR, sorted);
	}

	/** The list with one plugin's points hidden or shown. */
	public static String toggled(String text, String name, boolean hidden)
	{
		Set<String> names = parse(text);
		String clean = name.replace(SEPARATOR, " ").trim();
		if (hidden)
		{
			names.add(clean);
		}
		else
		{
			names.remove(clean);
		}
		return format(names);
	}

	public static boolean isHidden(String text, String name)
	{
		return parse(text).contains(name);
	}
}
