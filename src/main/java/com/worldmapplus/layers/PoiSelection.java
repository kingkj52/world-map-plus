package com.worldmapplus.layers;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which point of interest layers are on, kept in the settings as one comma separated list of ids.
 * <p>
 * Parsing is forgiving: unknown and blank pieces are dropped, so a hand edited setting cannot cause
 * an error.
 */
public final class PoiSelection
{
	private PoiSelection()
	{
	}

	public static Set<String> parse(String text)
	{
		Set<String> ids = new LinkedHashSet<>();
		if (text == null)
		{
			return ids;
		}
		for (String piece : text.split(","))
		{
			String id = piece.trim();
			if (!id.isEmpty())
			{
				ids.add(id);
			}
		}
		return ids;
	}

	public static String format(Set<String> ids)
	{
		String[] sorted = ids.toArray(new String[0]);
		Arrays.sort(sorted);
		return String.join(",", sorted);
	}

	/** The list with one id switched on or off. */
	public static String toggled(String text, String id, boolean on)
	{
		Set<String> ids = parse(text);
		if (on)
		{
			ids.add(id);
		}
		else
		{
			ids.remove(id);
		}
		return format(ids);
	}
}
