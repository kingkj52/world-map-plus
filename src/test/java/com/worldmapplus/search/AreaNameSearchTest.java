package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class AreaNameSearchTest
{
	private static SearchEntry place(String name, String detail)
	{
		return new SearchEntry(SearchKind.PLACE, name, detail, 0, 1405.5, 3363.5, Collections.emptyList(), "location");
	}

	@Test
	public void aNameTheGamesMapPrintsIsFoundByAnOrdinarySearchEvenWhenOtherThingsMatch()
	{
		List<SearchEntry> entries = new ArrayList<>();
		entries.add(place("Auburnvale", "Area name"));
		entries.add(place("Auburnvale Cave", "Cave"));
		entries.add(new SearchEntry(SearchKind.SHOP, "Auburnvale Provisions", "Shop", 0, 1400.5, 3360.5));
		SearchIndex index = new SearchIndex(entries);
		List<SearchEntry> found = index.search("auburnvale", 10);
		boolean area = false;
		for (SearchEntry entry : found)
		{
			area |= entry.getName().equals("Auburnvale") && entry.getKind() == SearchKind.PLACE;
			assertTrue("only the printed names join an ordinary search: " + entry.getName(), !entry.isLocation() || entry.isAreaName());
		}
		assertTrue("the area name is among the results", area);
		assertEquals(true, entries.get(0).isAreaName());
		assertEquals(false, entries.get(1).isAreaName());
	}
}
