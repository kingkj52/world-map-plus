package com.worldmapplus.compat;

import java.util.*;

/**
 * The kinds of the game's icons that are not shown, kept in the settings as one sorted list of names separated by a bar. Parsing is
 * forgiving, so a hand edited setting cannot cause an error, and at most 200 names are kept, so a setting cannot grow without end.
 */
public final class HiddenSources
{
	private HiddenSources()
	{
	}

	public static Set<String> parse(String text)
	{
		Set<String> names = new TreeSet<>();
		for (String piece : text == null ? new String[0] : text.split("\\|"))
		{
			String name = piece.trim();
			if (!name.isEmpty() && names.size() < 200)
			{
				names.add(name);
			}
		}
		return names;
	}

	/** The list with one name hidden or shown. */
	public static String toggled(String text, String name, boolean hidden)
	{
		Set<String> names = parse(text);
		String clean = name.replace("|", " ").trim();
		if (hidden)
		{
			names.add(clean);
		}
		else
		{
			names.remove(clean);
		}
		return String.join("|", names);
	}

	public static boolean isHidden(String text, String name)
	{
		return parse(text).contains(name);
	}
}
