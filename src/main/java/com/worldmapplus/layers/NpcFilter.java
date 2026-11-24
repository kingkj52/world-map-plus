package com.worldmapplus.layers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text filter for the nearby NPC markers.
 * <p>
 * A comma separated list of words. A name is shown when it contains any plain word, and hidden
 * when it contains any word that starts with an exclamation mark. An empty filter shows all. A
 * filter of only exclusions shows everything except those.
 * <p>
 * Examples: {@code banker, guard} shows bankers and guards. {@code !chicken} hides chickens.
 */
public final class NpcFilter
{
	private static final NpcFilter ALL = new NpcFilter(new ArrayList<>(), new ArrayList<>());

	private final List<String> include;
	private final List<String> exclude;

	private NpcFilter(List<String> include, List<String> exclude)
	{
		this.include = include;
		this.exclude = exclude;
	}

	public static NpcFilter parse(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			return ALL;
		}
		List<String> include = new ArrayList<>();
		List<String> exclude = new ArrayList<>();
		for (String part : text.split(","))
		{
			String word = part.trim().toLowerCase(Locale.ROOT);
			if (word.isEmpty())
			{
				continue;
			}
			if (word.startsWith("!"))
			{
				String rest = word.substring(1).trim();
				if (!rest.isEmpty())
				{
					exclude.add(rest);
				}
			}
			else
			{
				include.add(word);
			}
		}
		return new NpcFilter(include, exclude);
	}

	public boolean matches(String name)
	{
		if (name == null || name.isEmpty())
		{
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String word : exclude)
		{
			if (lower.contains(word))
			{
				return false;
			}
		}
		if (include.isEmpty())
		{
			return true;
		}
		for (String word : include)
		{
			if (lower.contains(word))
			{
				return true;
			}
		}
		return false;
	}
}
