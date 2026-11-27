package com.worldmapplus.search;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entries as {@code tools/build_search.py} writes them, with what their row in the results panel says (round 71: row, terms, stock,
 * ground), for tests that make their own entries.
 */
public final class ResultEntries
{
	private static final Gson GSON = new Gson();
	private static final Pattern NUMBER = Pattern.compile("^([\\d,]+) coins?|(\\d[\\d,]*) in stock");

	private ResultEntries()
	{
	}

	private static JsonObject base(SearchKind kind, String name, String detail, int map, double x, double y, List<double[]> at)
	{
		JsonObject e = new JsonObject();
		e.addProperty("kind", kind.name());
		e.addProperty("name", name);
		e.addProperty("detail", detail);
		e.addProperty("mapId", map);
		e.addProperty("x", x);
		e.addProperty("y", y);
		if (at != null)
		{
			JsonArray tiles = new JsonArray();
			for (double[] p : at)
			{
				JsonArray tile = new JsonArray();
				tile.add(p[0]);
				tile.add(p[1]);
				tiles.add(tile);
			}
			e.add("alsoAt", tiles);
		}
		return e;
	}

	private static SearchEntry made(JsonObject json)
	{
		SearchEntry entry = GSON.fromJson(json, SearchEntry.class);
		entry.ready(0);
		return entry;
	}

	/** An item sold at a shop for {@code terms} ("20 coins, 2 in stock"), with its price and stock. */
	public static SearchEntry sold(String item, String shop, String terms, int map, double x, double y)
	{
		JsonObject e = base(SearchKind.ITEM, item, "Sold at " + shop + " for " + terms, map, x, y, null);
		e.addProperty("row", shop);
		e.addProperty("terms", terms);
		Matcher m = NUMBER.matcher(terms);
		while (m.find())
		{
			e.addProperty(m.group(1) != null ? "price" : "stock", Long.parseLong((m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "")));
		}
		return made(e);
	}

	/** A monster or an NPC found at {@code where} (null for a group of spawns with no place name). */
	public static SearchEntry creature(SearchKind kind, String name, String where, int map, double x, double y, List<double[]> at)
	{
		String count = at.size() == 1 ? "1 spawn" : at.size() + " spawns";
		JsonObject e = base(kind, name, where == null ? count : where + " (" + count + ")", map, x, y, at);
		if (where != null)
		{
			e.addProperty("row", where);
		}
		return made(e);
	}
}
