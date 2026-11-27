package com.worldmapplus.search;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Entries for the places of a kind made the way {@code tools/build_search.py} writes them into {@code search.json} (until round 69
 * {@code SearchEntry.ofKind} made them in the plugin), for tests that need one without the bundled file.
 */
public final class KindEntries
{
	private static final Gson GSON = new Gson();

	private KindEntries()
	{
	}

	/** Every place of a kind ({@code group} -1), or the places of it in one town, found by these words. */
	public static SearchEntry ofKind(String name, String detail, int mapId, double x, double y, List<double[]> tiles,
		List<String> aliases, String kindId, int group, @Nullable String town)
	{
		JsonObject json = new JsonObject();
		json.addProperty("kind", SearchKind.KIND.name());
		json.addProperty("name", name);
		json.addProperty("detail", detail);
		json.addProperty("mapId", mapId);
		json.addProperty("x", x);
		json.addProperty("y", y);
		json.add("alsoAt", GSON.toJsonTree(tiles));
		JsonArray words = new JsonArray();
		for (String alias : aliases)
		{
			words.add(SearchEntry.normalize(alias));
		}
		json.add("aliases", words);
		json.addProperty("kindId", kindId);
		json.addProperty("group", group);
		if (town != null)
		{
			json.addProperty("place", SearchEntry.normalize(town));
		}
		SearchEntry entry = GSON.fromJson(json, SearchEntry.class);
		entry.ready(0);
		return entry;
	}
}
