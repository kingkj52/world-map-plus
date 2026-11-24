package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** The icons of the Guardians of the Rift arena (map 11000), which the game's own map has no place for. */
public class ArenaIconsTest
{
	private static JsonObject elements()
	{
		return new Gson().fromJson(new InputStreamReader(ArenaIconsTest.class
			.getResourceAsStream("/com/worldmapplus/map-elements.json"), StandardCharsets.UTF_8), JsonObject.class);
	}

	@Test
	public void theArenaHasItsBankItsShopItsMinigameAndItsWayBackToTheTower()
	{
		JsonObject file = elements();
		JsonArray categories = file.getAsJsonArray("categories");
		JsonArray rows = file.getAsJsonObject("icons").getAsJsonArray("11000");
		assertNotNull("the arena has icons", rows);
		Set<String> kinds = new HashSet<>();
		JsonArray portal = null;
		for (int i = 0; i < rows.size(); i++)
		{
			JsonArray row = rows.get(i).getAsJsonArray();
			String kind = categories.get(row.get(2).getAsInt()).getAsString();
			kinds.add(kind);
			if (kind.equals("Transportation"))
			{
				portal = row;
			}
		}
		assertTrue(kinds.toString(), kinds.contains("Bank") && kinds.contains("Minigame") && kinds.contains("Magic shop")
			&& kinds.contains("Transportation"));
		assertNotNull(portal);
		assertEquals("the way back to the Wizards' Tower is a link, with its map", 8, portal.size());
		assertEquals(3104, portal.get(5).getAsInt());
		assertEquals(9573, portal.get(6).getAsInt());
		assertEquals("into the Misthalin Underground", 12, portal.get(7).getAsInt());
	}
}
