package com.worldmapplus.search;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.worldmapplus.MapAssets;
import com.worldmapplus.unlock.Need;
import com.worldmapplus.unlock.Needs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every search entry the plugin has, in the order the index gets them, one record a line, then every kind of place with its towns.
 * Round 69 wrote this from the Java code that made the entries (search/SearchSources, ItemKinds, KindIndex, data/SpawnLoader,
 * SpawnFormat, wiki/WikiTitles) into {@code golden/search-entries.txt}; the plugin now reads them from {@code search.json}, made by
 * {@code tools/build_search.py}, and {@code SearchFileTest} checks that what it reads writes out the same. The tool's
 * {@code --compare} reads this file too (all but the need, which only the plugin can judge), so keep the two in step.
 */
public final class SearchDump
{
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private SearchDump()
	{
	}

	public static String dump(MapAssets assets)
	{
		StringBuilder out = new StringBuilder();
		out.append("## entries\n");
		int n = 0;
		for (SearchEntry e : assets.getSearchEntries())
		{
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("k", e.getKind().name());
			r.put("n", e.getName());
			r.put("d", e.getDetail());
			r.put("m", e.getMapId());
			r.put("x", e.getX());
			r.put("y", e.getY());
			if (!e.getAlsoAt().isEmpty())
			{
				r.put("at", e.getAlsoAt());
			}
			if (e.getPrice() != -1)
			{
				r.put("p", e.getPrice());
			}
			if (!e.getCategory().isEmpty())
			{
				r.put("c", e.getCategory());
			}
			if (e.isLocation())
			{
				r.put("loc", true);
			}
			if (e.isAreaName())
			{
				r.put("area", true);
			}
			if (e.normalizedAliases().length > 0)
			{
				r.put("al", Arrays.asList(e.normalizedAliases()));
			}
			r.put("kid", e.getKindId());
			if (e.getGroup() != -1)
			{
				r.put("g", e.getGroup());
			}
			if (e.isKindAtPlace())
			{
				r.put("pl", e.normalizedPlace());
			}
			r.put("nn", e.normalizedName());
			r.put("nd", e.normalizedDetail());
			r.put("w", wiki(e));
			r.put("need", need(e.getNeed()));
			out.append(n++).append(' ').append(GSON.toJson(r)).append('\n');
		}
		out.append("## kinds\n");
		n = 0;
		for (KindIndex.Kind kind : assets.getKinds().getKinds())
		{
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("id", kind.getId());
			r.put("s", kind.getSingular());
			r.put("p", kind.getPlural());
			r.put("c", kind.getCategory());
			r.put("total", kind.getTotal());
			List<Object> groups = new ArrayList<>();
			for (KindIndex.Group group : kind.getGroups())
			{
				Map<String, Object> g = new LinkedHashMap<>();
				g.put("m", group.getMapId());
				g.put("t", group.getTown());
				g.put("tiles", group.getTiles());
				groups.add(g);
			}
			r.put("g", groups);
			out.append(n++).append(' ').append(GSON.toJson(r)).append('\n');
		}
		return out.toString();
	}

	/** The wiki page a result's menu opens. */
	static String wiki(SearchEntry e)
	{
		return e.getWiki();
	}

	/** What a result says is missing, as words: every way, and the quest it starts. */
	static String need(Need need)
	{
		if (need == null)
		{
			return null;
		}
		List<String> ways = new ArrayList<>();
		for (Need.Check[] way : need.alternatives())
		{
			ways.add(Needs.describe(way));
		}
		return String.join(" / ", ways) + (need.startsQuest() == null ? "" : " starts " + need.startsQuest());
	}
}
