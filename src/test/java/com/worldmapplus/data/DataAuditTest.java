package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The classes of error tools/audit_data.py looks for, held on the real bundled files so a regeneration cannot bring them back:
 * shop cards moved onto icons of other things, wiki markup left in text, a shop's text from another place's row, doorways listed
 * twice or to the wrong one of two maps that draw one place.
 */
public class DataAuditTest
{
	private static final Gson GSON = new Gson();
	private static final Pattern MARKUP = Pattern.compile(
		"\\{\\{|\\}\\}|\\[\\[|\\]\\]|<ref|</ref|<br|refn|\\|name=|\\|group=|&nbsp;|&amp;|&#", Pattern.CASE_INSENSITIVE);
	private static final Pattern EMPTY_PARENS = Pattern.compile("\\(\\s*\\)");
	private static final Pattern DOUBLE_SPACE = Pattern.compile("[ \\t]{2,}");
	private static final Set<String> SELLERS = new HashSet<>(java.util.Arrays.asList(
		"herbalist", "apothecary", "stonemason", "taxidermist", "pub", "tannery", "brewery", "estate agent", "shipwright",
		"makeover mage", "hairdresser", "grand exchange", "sawmill"));

	private static JsonObject load(String name) throws Exception
	{
		// shops.json is a build input since round 62E (the Helper holds the copy the plugin reads), not a resource of this plugin.
		if (name.equals("shops.json"))
		{
			org.junit.Assume.assumeTrue("the build inputs are not in this copy", java.nio.file.Files.exists(java.nio.file.Paths.get("tools", "input", "shops.json")));
		}
		try (Reader reader = name.equals("shops.json")
			? java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get("tools", "input", name), StandardCharsets.UTF_8)
			: new InputStreamReader(DataAuditTest.class.getResourceAsStream("/com/worldmapplus/" + name), StandardCharsets.UTF_8))
		{
			return GSON.fromJson(reader, JsonObject.class);
		}
	}

	private static boolean isShopIcon(String name)
	{
		String c = name.toLowerCase(Locale.ROOT);
		return c.contains("shop") || c.contains("store") || c.contains("trader") || SELLERS.contains(c);
	}

	private static String norm(String text)
	{
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	private static boolean stray(String text)
	{
		return MARKUP.matcher(text).find() || EMPTY_PARENS.matcher(text).find() || DOUBLE_SPACE.matcher(text).find()
			|| text.matches(".*[,;:|(\\[{<]\\s*$");
	}

	@Test
	public void aShopCardMovedOffItsPinNeverSitsOnAnIconOfSomethingElse() throws Exception
	{
		JsonObject elements = load("map-elements.json");
		JsonArray categories = elements.getAsJsonArray("categories");
		Map<Integer, List<double[]>> icons = new HashMap<>();
		JsonObject byMap = elements.getAsJsonObject("icons");
		for (Map.Entry<String, JsonElement> e : byMap.entrySet())
		{
			List<double[]> rows = new ArrayList<>();
			for (JsonElement row : e.getValue().getAsJsonArray())
			{
				JsonArray r = row.getAsJsonArray();
				rows.add(new double[]{r.get(0).getAsDouble(), r.get(1).getAsDouble(), r.get(2).getAsInt()});
			}
			icons.put(Integer.parseInt(e.getKey()), rows);
		}
		List<String> bad = new ArrayList<>();
		for (JsonElement el : load("shops.json").getAsJsonArray("shops"))
		{
			JsonObject s = el.getAsJsonObject();
			int map = s.get("map").getAsInt();
			if (map < 0 || !s.has("pin"))
			{
				continue;
			}
			int x = s.get("x").getAsInt();
			int y = s.get("y").getAsInt();
			JsonArray pin = s.getAsJsonArray("pin");
			if (Math.hypot(pin.get(0).getAsInt() - x, pin.get(1).getAsInt() - y) <= 1.0)
			{
				continue;
			}
			double best = 1.3;
			String category = null;
			for (double[] icon : icons.getOrDefault(map, new ArrayList<>()))
			{
				double d = Math.hypot(icon[0] - (x + 0.5), icon[1] - (y + 0.5));
				if (d <= best)
				{
					best = d;
					category = categories.get((int) icon[2]).getAsString();
				}
			}
			if (category != null && !isShopIcon(category) && !norm(category).equals(norm(
				s.has("specialty") ? s.get("specialty").getAsString() : null)))
			{
				bad.add(s.get("name").getAsString() + " on " + category + " at " + x + "," + y);
			}
		}
		assertTrue("shops moved onto icons of other things: " + bad, bad.isEmpty());
	}

	@Test
	public void shopTextHasNoMarkupAndNoRepeatedStockRows() throws Exception
	{
		List<String> bad = new ArrayList<>();
		for (JsonElement el : load("shops.json").getAsJsonArray("shops"))
		{
			JsonObject s = el.getAsJsonObject();
			String name = s.get("name").getAsString();
			for (String field : new String[]{"owner", "where", "specialty"})
			{
				if (s.has(field) && stray(s.get(field).getAsString()))
				{
					bad.add(name + "." + field + " = " + s.get(field).getAsString());
				}
			}
			if (MARKUP.matcher(name).find())
			{
				bad.add(name);
			}
			Set<String> rows = new HashSet<>();
			for (JsonElement item : s.getAsJsonArray("items"))
			{
				if (!rows.add(item.toString()))
				{
					bad.add(name + " repeats " + item);
				}
			}
		}
		assertTrue("stray text or repeated rows: " + bad, bad.isEmpty());
	}

	@Test
	public void aShopThatStandsInManyPlacesTellsThePlaceOfItsOwnPin() throws Exception
	{
		// These wiki pages have a row for each place; the card used to carry the last row's text for the first row's pin.
		Map<String, String> expected = new HashMap<>();
		expected.put("Lost Property shop", "Lumbridge");
		expected.put("Construction supplies", "Auburnvale");
		expected.put("Garden Centre", "Falador Park");
		expected.put("Shantay Pass Shop", "Shantay Pass");
		expected.put("Forestry Shop", "Draynor Village");
		int seen = 0;
		for (JsonElement el : load("shops.json").getAsJsonArray("shops"))
		{
			JsonObject s = el.getAsJsonObject();
			String want = expected.get(s.get("name").getAsString());
			if (want != null)
			{
				seen++;
				assertEquals(s.get("name").getAsString(), want, s.get("where").getAsString());
			}
		}
		assertEquals(expected.size(), seen);
	}

	@Test
	public void spawnPlaceTextHasNoMarkupOrEmptyBrackets() throws Exception
	{
		List<String> bad = new ArrayList<>();
		for (String key : new String[]{"spawns", "shops"})
		{
			for (JsonElement e : load("place-names.json").getAsJsonArray(key))
			{
				String where = e.getAsJsonArray().get(0).getAsString();
				if (stray(where))
				{
					bad.add(where);
				}
			}
		}
		assertTrue("stray spawn place text (" + bad.size() + "): " + bad.subList(0, Math.min(10, bad.size())), bad.isEmpty());
	}

	@Test
	public void doorwaysAreNotListedTwiceAndKeldagrimDoesNotLeadToTheTrollStronghold() throws Exception
	{
		Set<String> seen = new HashSet<>();
		List<String> bad = new ArrayList<>();
		for (JsonElement el : load("entrances.json").getAsJsonArray("links"))
		{
			JsonObject link = el.getAsJsonObject();
			if (!seen.add(link.toString()))
			{
				bad.add("repeated " + link);
			}
			JsonObject a = link.getAsJsonObject("a");
			if (link.get("name").getAsString().equals("Keldagrim Entrance") && !link.get("b").isJsonNull()
				&& a.get("map").getAsInt() == 0 && link.getAsJsonObject("b").get("map").getAsInt() != 10)
			{
				bad.add("Keldagrim entrance leads to map " + link.getAsJsonObject("b").get("map"));
			}
		}
		assertTrue(bad.toString(), bad.isEmpty());
	}

	@Test
	public void cardTextOfTheOtherFilesHasNoMarkup() throws Exception
	{
		List<String> bad = new ArrayList<>();
		for (JsonElement el : load("icon-details.json").getAsJsonArray("details"))
		{
			JsonObject d = el.getAsJsonObject();
			for (String field : new String[]{"title", "subtitle"})
			{
				if (stray(d.get(field).getAsString()))
				{
					bad.add(d.get(field).getAsString());
				}
			}
		}
		for (JsonElement el : load("entrances.json").getAsJsonArray("links"))
		{
			String name = el.getAsJsonObject().get("name").getAsString();
			if (stray(name))
			{
				bad.add(name);
			}
		}
		assertTrue(bad.toString(), bad.isEmpty());
	}
}
