package com.worldmapplus.pins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.search.Gazetteer;
import com.worldmapplus.search.SearchContext;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchIndex;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Round 48: the player's own saved places and routes as search entries, how they rank and how a route is started. */
public class OwnSearchTest
{
	private final PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
	private final RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);

	private List<SearchEntry> own()
	{
		return OwnSearch.entries(pins.all(), routes.all(), pin -> "Your pin");
	}

	@Test
	public void emptyStoresGiveNoEntries()
	{
		assertTrue(own().isEmpty());
		SearchIndex index = new SearchIndex(own());
		assertEquals(0, index.size());
		assertTrue(index.search("anything", 10).isEmpty());
	}

	@Test
	public void pinsAndRoutesBecomeEntriesWithTheirIdsAndStopCounts()
	{
		Pin a = pins.add("Altar", "pin", 0, 3000.5, 3300.5, 0);
		Pin b = pins.add("Bank", "bank", 0, 3010.5, 3310.5, 0);
		SavedRoute route = routes.create("Prayer run", Arrays.asList(a.getId(), b.getId()), true);
		List<SearchEntry> entries = own();
		assertEquals(3, entries.size());
		assertEquals(SearchKind.PIN, entries.get(0).getKind());
		assertEquals(a.getId(), entries.get(0).getOwnId());
		SearchEntry r = entries.get(2);
		assertEquals(SearchKind.ROUTE, r.getKind());
		assertEquals(route.getId(), r.getOwnId());
		assertEquals("Your route, 2 stops, loops", r.getDetail());
		assertEquals("a route stands at its first stop", 3000.5, r.getX(), 0);
		assertEquals(0, r.getMapId());
	}

	@Test
	public void aRouteWhoseFirstStopIsGoneStandsAtTheNextAndOneWithNoneHasNoMap()
	{
		Pin a = pins.add("A", "pin", 0, 3000.5, 3300.5, 0);
		Pin b = pins.add("B", "pin", 0, 3010.5, 3310.5, 0);
		routes.create("Two", Arrays.asList(a.getId(), b.getId()), false);
		pins.remove(a.getId());
		SearchEntry two = byName(own(), "Two");
		assertEquals(3010.5, two.getX(), 0);
		pins.remove(b.getId());
		assertEquals(-1, byName(own(), "Two").getMapId());
	}

	@Test
	public void twoOfTheSameNameAtOnePlaceStayTwoResults()
	{
		pins.add("Spot", "pin", 0, 3000.5, 3300.5, 0);
		pins.add("Spot", "pin", 0, 3000.5, 3300.5, 0);
		assertEquals(2, new SearchIndex(own()).search("spot", 10).size());
	}

	@Test
	public void anExactNameRanksFirstAndAPartOfANameNeverPushesMapPlacesDown()
	{
		pins.add("Lumbridge", "pin", 0, 3000.5, 3300.5, 0);
		pins.add("Bank stash", "pin", 0, 3000.5, 3300.5, 0);
		routes.create("Bank run", Collections.emptyList(), false);
		List<SearchEntry> entries = new ArrayList<>(own());
		entries.add(new SearchEntry(SearchKind.PLACE, "Lumbridge", "Area", 0, 3222, 3218));
		entries.add(new SearchEntry(SearchKind.PLACE, "Bank chest", "Area", 0, 3100, 3100));
		entries.add(new SearchEntry(SearchKind.SHOP, "Bank shop", "", 0, 3100, 3200));
		entries.add(new SearchEntry(SearchKind.NPC, "Banker", "", 0, 3100, 3200));
		SearchIndex index = new SearchIndex(entries);

		List<SearchEntry> lumbridge = index.search("lumbridge", 10);
		assertEquals("the player's own place of exactly that name comes first", SearchKind.PIN, lumbridge.get(0).getKind());
		assertEquals(SearchKind.PLACE, lumbridge.get(1).getKind());

		List<SearchEntry> bank = index.search("bank", 10);
		int place = indexOf(bank, "Bank chest");
		int shop = indexOf(bank, "Bank shop");
		int stash = indexOf(bank, "Bank stash");
		int run = indexOf(bank, "Bank run");
		assertTrue(bank.toString(), place >= 0 && stash >= 0 && run >= 0);
		assertTrue("the map's places come before the player's own partial matches", place < stash && place < run && shop < stash);

		List<SearchEntry> exactRoute = index.search("bank run", 10);
		assertEquals(SearchKind.ROUTE, exactRoute.get(0).getKind());
	}

	@Test
	public void theFilterLanguageHasAWordForEach()
	{
		pins.add("Bank stash", "pin", 0, 3000.5, 3300.5, 0);
		routes.create("Bank run", Collections.emptyList(), false);
		List<SearchEntry> entries = new ArrayList<>(own());
		entries.add(new SearchEntry(SearchKind.PLACE, "Bank chest", "Area", 0, 3100, 3100));
		SearchIndex index = new SearchIndex(entries);
		SearchContext context = new SearchContext(false, 0, 0, 0, new Gazetteer(com.worldmapplus.map.MapCatalog.load(new Gson()),
			com.worldmapplus.layers.RegionLines.load(new Gson()), Collections.emptyList()));
		for (String filter : new String[] {"kind:route bank", "type:routes bank", "kind:route"})
		{
			List<SearchEntry> found = index.search(filter, 10, context);
			assertEquals(filter, 1, found.size());
			assertEquals(SearchKind.ROUTE, found.get(0).getKind());
		}
		List<SearchEntry> found = index.search("kind:pin bank", 10, context);
		assertEquals(1, found.size());
		assertEquals(SearchKind.PIN, found.get(0).getKind());
		assertTrue("type: completes route", Arrays.asList(com.worldmapplus.search.SearchQuery.RESULT_KINDS).contains("route"));
	}

	@Test
	public void startingARouteIsTheSameEverywhere()
	{
		Pin a = pins.add("A", "pin", 0, 3000.5, 3300.5, 0);
		SavedRoute route = routes.create("R", Collections.singletonList(a.getId()), false);
		RouteStart.Result missing = RouteStart.start(route, routes, pins, MapActions.Status.MISSING);
		assertFalse(missing.isStarted());
		assertTrue(missing.getMessage().contains("not installed"));
		assertFalse(RouteStart.start(route, routes, pins, MapActions.Status.DISABLED).isStarted());
		assertEquals(null, routes.active());
		RouteStart.Result ok = RouteStart.start(route, routes, pins, MapActions.Status.UNKNOWN);
		assertTrue(ok.isStarted());
		assertEquals(route.getId(), routes.active().getId());
		routes.stopAll();
		pins.remove(a.getId());
		RouteStart.Result empty = RouteStart.start(routes.get(route.getId()), routes, pins, MapActions.Status.READY);
		assertFalse(empty.isStarted());
		assertTrue(empty.getMessage().contains("no saved places left"));
		assertEquals(null, routes.active());
	}

	private static SearchEntry byName(List<SearchEntry> entries, String name)
	{
		for (SearchEntry entry : entries)
		{
			if (entry.getName().equals(name))
			{
				return entry;
			}
		}
		throw new AssertionError(name);
	}

	private static int indexOf(List<SearchEntry> entries, String name)
	{
		for (int i = 0; i < entries.size(); i++)
		{
			if (entries.get(i).getName().equals(name))
			{
				return i;
			}
		}
		return -1;
	}
}
