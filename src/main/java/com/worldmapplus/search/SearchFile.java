package com.worldmapplus.search;

import com.worldmapplus.data.*;
import java.util.*;

/**
 * The shape of {@code search.json}, made by {@code tools/build_search.py} (its docstring says how): every entry the search finds, in
 * the order the index lists them, and every place of each kind. Filled by Gson.
 */
public final class SearchFile
{
	private List<SearchEntry> entries;
	private List<KindIndex.Kind> kinds;

	/** The entries found on a day written as yyyymmdd, each told what it asks of the player. */
	public List<SearchEntry> entries(MapElements elements, int today)
	{
		List<SearchEntry> found = new ArrayList<>();
		for (SearchEntry entry : entries == null ? Collections.<SearchEntry>emptyList() : entries)
		{
			if (entry != null && entry.ready(today))
			{
				entry.setNeed(elements.needOf(entry.thing, entry.way, entry.icons));
				found.add(entry);
			}
		}
		return found;
	}

	public KindIndex kinds()
	{
		return kinds == null ? KindIndex.empty() : new KindIndex(kinds);
	}
}
