package com.worldmapplus.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.pins.Pin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.Test;

public class PlaceListTest
{
	private static final IntFunction<String> AREAS = id -> id == 0 ? "Gielinor" : "Taverley Dungeon";

	private static Pin pin(long id, String name, String kind, int map, long created)
	{
		return new Pin(id, name, map, 1, 1, kind, created);
	}

	private static List<Pin> sample()
	{
		return Arrays.asList(
			pin(1, "seed shop", "shop", 0, 300),
			pin(2, "Bank", "pin", 0, 100),
			pin(3, "Black demons", "monster", 7, 200),
			pin(4, "Axe shop", "shop", 0, 0));
	}

	private static List<String> names(List<Pin> pins)
	{
		List<String> names = new ArrayList<>();
		for (Pin pin : pins)
		{
			names.add(pin.getName());
		}
		return names;
	}

	@Test
	public void sortsByNameWithoutCaringAboutCase()
	{
		assertEquals(Arrays.asList("Axe shop", "Bank", "Black demons", "seed shop"),
			names(PlaceList.apply(sample(), "", PlaceList.ALL_TYPES, PlaceList.Sort.NAME, AREAS)));
	}

	@Test
	public void sortsNewestAndOldest()
	{
		assertEquals(Arrays.asList("seed shop", "Black demons", "Bank", "Axe shop"),
			names(PlaceList.apply(sample(), "", PlaceList.ALL_TYPES, PlaceList.Sort.NEWEST, AREAS)));
		assertEquals(Arrays.asList("Axe shop", "Bank", "Black demons", "seed shop"),
			names(PlaceList.apply(sample(), "", PlaceList.ALL_TYPES, PlaceList.Sort.OLDEST, AREAS)));
	}

	@Test
	public void sortsByTypeAndByArea()
	{
		assertEquals(Arrays.asList("Black demons", "Bank", "Axe shop", "seed shop"),
			names(PlaceList.apply(sample(), "", PlaceList.ALL_TYPES, PlaceList.Sort.TYPE, AREAS)));
		List<String> byArea = names(PlaceList.apply(sample(), "", PlaceList.ALL_TYPES, PlaceList.Sort.AREA, AREAS));
		assertEquals("Black demons", byArea.get(3));
	}

	@Test
	public void theTypeFilterKeepsOneType()
	{
		assertEquals(Arrays.asList("Axe shop", "seed shop"),
			names(PlaceList.apply(sample(), "", "shop", PlaceList.Sort.NAME, AREAS)));
		assertEquals(4, PlaceList.apply(sample(), "", "", PlaceList.Sort.NAME, AREAS).size());
		assertEquals(4, PlaceList.apply(sample(), "", null, PlaceList.Sort.NAME, AREAS).size());
	}

	@Test
	public void everyWordOfTheTextMustMatchNameTypeOrArea()
	{
		assertEquals(Arrays.asList("Black demons"),
			names(PlaceList.apply(sample(), "dungeon demons", PlaceList.ALL_TYPES, PlaceList.Sort.NAME, AREAS)));
		assertEquals(Arrays.asList("Axe shop", "seed shop"),
			names(PlaceList.apply(sample(), "SHOP", PlaceList.ALL_TYPES, PlaceList.Sort.NAME, AREAS)));
		assertTrue(PlaceList.apply(sample(), "shop demons", PlaceList.ALL_TYPES, PlaceList.Sort.NAME, AREAS).isEmpty());
	}

	@Test
	public void listsTheTypesInUse()
	{
		assertEquals(Arrays.asList("monster", "pin", "shop"), PlaceList.typesOf(sample()));
		assertEquals("Shop", PlaceList.typeLabel("shop"));
	}
}
