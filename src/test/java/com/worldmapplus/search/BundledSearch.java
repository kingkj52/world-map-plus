package com.worldmapplus.search;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.data.MapElements;
import com.worldmapplus.data.PoiLoader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The bundled search entries ({@code search.json}) without the rest of the plugin's data, for tests that look through them. */
public final class BundledSearch
{
	private BundledSearch()
	{
	}

	/** Every entry, in the index's order, with no needs. */
	public static List<SearchEntry> entries()
	{
		SearchFile file = MapAssets.json(new Gson(), "search.json", SearchFile.class);
		if (file == null)
		{
			throw new IllegalStateException("search.json is missing or damaged");
		}
		return file.entries(MapElements.none(), PoiLoader.today());
	}

	/** Every place of each kind. */
	public static KindIndex kinds()
	{
		return MapAssets.json(new Gson(), "search.json", SearchFile.class).kinds();
	}

	/** The entries of these kinds only. */
	public static List<SearchEntry> of(SearchKind first, SearchKind... rest)
	{
		Set<SearchKind> kinds = EnumSet.of(first, rest);
		List<SearchEntry> found = new ArrayList<>();
		for (SearchEntry entry : entries())
		{
			if (kinds.contains(entry.getKind()))
			{
				found.add(entry);
			}
		}
		return found;
	}

	/** The entries of the maps themselves. */
	public static List<SearchEntry> maps()
	{
		List<SearchEntry> found = new ArrayList<>();
		for (SearchEntry entry : of(SearchKind.PLACE))
		{
			if (entry.getDetail().equals("Map"))
			{
				found.add(entry);
			}
		}
		return found;
	}

	/** The entries made from the spawn data: monsters, NPCs found about (not shopkeepers), ground items, quest starts and music. */
	public static List<SearchEntry> spawns()
	{
		List<SearchEntry> found = new ArrayList<>();
		for (SearchEntry entry : of(SearchKind.MONSTER, SearchKind.NPC, SearchKind.ITEM, SearchKind.QUEST, SearchKind.MUSIC))
		{
			boolean sold = entry.getKind() == SearchKind.ITEM && !entry.getDetail().startsWith("Free to take. ");
			boolean keeper = entry.getKind() == SearchKind.NPC && entry.getDetail().startsWith("Runs ");
			if (!sold && !keeper)
			{
				found.add(entry);
			}
		}
		return found;
	}
}
