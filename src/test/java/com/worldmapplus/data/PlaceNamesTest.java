package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.List;
import org.junit.Test;

/** What World Map Plus keeps of the shop and spawn data (the Helper holds the data itself since round 62E). */
public class PlaceNamesTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void theBundledQuestStartsAreEntriesForNamingIconsAndNothingElse()
	{
		List<SearchEntry> entries = PlaceLoader.quests(GSON, MapCatalog.load(GSON));
		assertTrue("quests " + entries.size(), entries.size() > 100);
		SearchEntry cook = null;
		for (SearchEntry entry : entries)
		{
			assertEquals(SearchKind.QUEST, entry.getKind());
			assertEquals("Quest start", entry.getDetail());
			if (entry.getName().equals("Cook's Assistant"))
			{
				cook = entry;
			}
		}
		assertNotNull("Cook's Assistant starts somewhere", cook);
		assertEquals(0, cook.getMapId());
		assertTrue("and the need is the quest it starts", cook.getNeed() != null && cook.getNeed().startsQuest() != null);
	}

	@Test
	public void theShopsPlacesAreForTheGazetteerAndTheSpawnPlacesAreNamedPlacesToo()
	{
		MapCatalog catalog = MapCatalog.load(GSON);
		assertTrue("shop places " + PlaceLoader.shopPlaces(GSON, catalog).size(), PlaceLoader.shopPlaces(GSON, catalog).size() > 300);
		boolean boar = false;
		for (SearchEntry entry : PlaceLoader.build(GSON, catalog, MapElements.load(GSON, catalog, java.util.Collections.emptyList())))
		{
			boar |= entry.getName().equals("Jolly Boar Inn");
		}
		assertTrue("a place the shop data names is a named place", boar);
	}
}
