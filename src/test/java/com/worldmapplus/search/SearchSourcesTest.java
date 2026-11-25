package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.data.HotspotLoader;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.map.MapCatalog;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** Searches the real bundled data, so a regression in any dataset shows up as a failed lookup. */
public class SearchSourcesTest
{
	private static SearchIndex index;

	@BeforeClass
	public static void build()
	{
		Gson gson = new Gson();
		MapCatalog catalog = MapCatalog.load(gson);
		List<Hotspot> hotspots = HotspotLoader.load(gson, catalog);
		index = new SearchIndex(SearchSources.build(catalog, hotspots));
	}

	private static SearchEntry first(String query)
	{
		List<SearchEntry> found = index.search(query, 5);
		assertFalse("nothing found for " + query, found.isEmpty());
		return found.get(0);
	}

	@Test
	public void findsAWayToTeleportByName()
	{
		boolean found = false;
		for (SearchEntry e : index.search("varrock teleport", 20))
		{
			found |= e.getKind() == SearchKind.TELEPORT && e.getName().equals("Varrock Teleport");
		}
		assertTrue(found);
	}

	@Test
	public void findsAMinigameTeleport()
	{
		boolean found = false;
		for (SearchEntry e : index.search("castle wars minigame", 20))
		{
			found |= e.getKind() == SearchKind.TELEPORT;
		}
		assertTrue(found);
	}

	@Test
	public void findsAKourendMinecartStation()
	{
		boolean found = false;
		for (SearchEntry e : index.search("kourend minecart", 20))
		{
			found |= e.getKind() == SearchKind.TRANSPORT && e.getName().startsWith("Kourend minecart");
		}
		assertTrue(found);
	}

	@Test
	public void findsAWildernessLever()
	{
		boolean found = false;
		for (SearchEntry e : index.search("lever", 30))
		{
			found |= e.getKind() == SearchKind.TRANSPORT && e.getName().startsWith("Lever");
		}
		assertTrue(found);
	}

	@Test
	public void findsMapsByName()
	{
		SearchEntry dwarven = first("dwarven mines");
		assertEquals(SearchKind.PLACE, dwarven.getKind());
	}

	@Test
	public void findsTransports()
	{
		boolean found = false;
		for (SearchEntry e : index.search("charter ship", 20))
		{
			found |= e.getKind() == SearchKind.TRANSPORT;
		}
		assertTrue(found);
	}

	@Test
	public void findsDungeonEntrances()
	{
		boolean found = false;
		for (SearchEntry e : index.search("taverley dungeon", 20))
		{
			found |= e.getKind() == SearchKind.DOORWAY;
		}
		assertTrue(found);
	}

	@Test
	public void theIndexIsBigEnoughToBeUseful()
	{
		assertTrue("entries: " + index.size(), index.size() > 1000);
	}

	@Test
	public void searchingIsFast()
	{
		long start = System.nanoTime();
		for (int i = 0; i < 50; i++)
		{
			index.search("a", 10);
		}
		long millis = (System.nanoTime() - start) / 1_000_000;
		assertTrue("50 searches took " + millis + " ms", millis < 2000);
	}
}
