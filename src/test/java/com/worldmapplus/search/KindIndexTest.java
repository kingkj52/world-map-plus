package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.map.MapCatalog;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** The bundled kind index, and search over it together with the other bundled entries the way the plugin builds them. */
public class KindIndexTest
{
	private static MapCatalog catalog;
	private static KindIndex kinds;
	private static SearchIndex index;

	@BeforeClass
	public static void load()
	{
		Gson gson = new Gson();
		catalog = MapCatalog.load(gson);
		kinds = BundledSearch.kinds();
		index = new SearchIndex(BundledSearch.entries());
	}

	@Test
	public void theBundledFileHasTheKindsPlayersAskFor()
	{
		assertTrue("kinds " + kinds.getKinds().size(), kinds.getKinds().size() > 100);
		for (String id : new String[] {"bank", "altar", "anvil", "furnace", "herb_patch", "shark_fishing_spot", "runecrafting_altar",
			"yew_tree", "iron_rocks", "quest_start"})
		{
			KindIndex.Kind kind = kinds.byId(id);
			assertNotNull(id, kind);
			assertTrue(id + " has places", kind.getTotal() >= 2);
		}
		KindIndex.Kind banks = kinds.byId("bank");
		assertEquals("Bank", banks.getCategory());
		assertEquals("Banks", banks.getPlural());
		assertEquals("4 banks", banks.count(4));
		assertEquals("1 bank", banks.count(1));
		assertEquals(null, kinds.byId("shark_fishing_spot").getCategory());
		boolean catherby = false;
		for (KindIndex.Group group : kinds.byId("shark_fishing_spot").getGroups())
		{
			catherby |= group.getTown().equals("Catherby") && group.getTiles().size() >= 2;
		}
		assertTrue("Catherby's shark spots are one group of several", catherby);
	}

	@Test
	public void everyGroupIsOnAMapThatHasPictureOfItsPlaces()
	{
		for (KindIndex.Kind kind : kinds.getKinds())
		{
			for (KindIndex.Group group : kind.getGroups())
			{
				assertNotNull(kind.getId(), catalog.byId(group.getMapId()));
				assertFalse(group.getTown().isEmpty());
				assertFalse(group.getTiles().isEmpty());
			}
		}
	}

	@Test
	public void sharkCatherbyFindsCatherbysSharkFishingSpots()
	{
		List<SearchEntry> found = index.search("shark catherby", 10);
		SearchEntry first = found.get(0);
		assertEquals("Shark fishing spots in Catherby", first.getName());
		assertEquals("shark_fishing_spot", first.getKindId());
		assertTrue(first.getAlsoAt().size() >= 2);
		assertEquals(first.getName(), index.search("catherby shark", 10).get(0).getName());
		assertEquals(first.getName(), index.search("catherby shrak", 10).get(0).getName());
	}

	@Test
	public void aTownAloneDoesNotListEverythingInIt()
	{
		for (SearchEntry entry : index.search("catherby", SearchBox.MAX_RESULTS))
		{
			assertFalse(entry.getName(), entry.isKindAtPlace());
		}
	}

	@Test
	public void aPlainKindWordGivesEveryPlaceOfTheKindFirst()
	{
		SearchEntry first = index.search("bank", 10).get(0);
		assertEquals("Banks", first.getName());
		assertEquals(SearchKind.KIND, first.getKind());
		assertEquals(-1, first.getGroup());
		assertEquals("Herb patches", index.search("herb patch", 10).get(0).getName());
		// The bank point of that name is an exact match; the town's list of banks comes right after it.
		List<String> names = new ArrayList<>();
		for (SearchEntry entry : index.search("varrock bank", 3))
		{
			names.add(entry.getName());
		}
		assertTrue(names.toString(), names.contains("Banks in Varrock"));
	}

	@Test
	public void aFilterByWhereLeavesEveryPlaceOfAKindOut()
	{
		SearchContext here = new SearchContext(true, 0, 3222, 3218, new Gazetteer(catalog, com.worldmapplus.MapAssets.json(new Gson(), "regions.json", Gazetteer.Regions.class),
			null));
		for (SearchEntry entry : index.search("bank dist:200", 50, here))
		{
			assertTrue(entry.getName(), entry.getKind() != SearchKind.KIND || entry.isKindAtPlace());
		}
	}
}
