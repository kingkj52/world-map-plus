package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class PlaceLoaderTest
{
	@Test
	public void aTownAndWhatIsInItAreEachAPlace()
	{
		assertEquals(Arrays.asList("Aldarin", "Alchemical Society"), PlaceLoader.names("Aldarin - Alchemical Society"));
		assertEquals(Arrays.asList("Ape Atoll", "Crash Island"), PlaceLoader.names("Ape Atoll, Crash Island"));
		assertEquals(Arrays.asList("Slayer Tower"), PlaceLoader.names("Slayer Tower ()"));
		assertEquals(Arrays.asList("Slayer Tower"), PlaceLoader.names("Slayer Tower (ground floor)"));
		assertEquals(Arrays.asList("Jolly Boar Inn"), PlaceLoader.names("Jolly Boar Inn"));
	}

	@Test
	public void aDescriptionOfWhereIsNotAPlace()
	{
		assertTrue(PlaceLoader.names("Behind Lumbridge Castle near the Lumbridge Swamp").isEmpty());
		assertTrue(PlaceLoader.names("East side of Lumbridge").isEmpty());
		assertTrue(PlaceLoader.names("Along the River Lum north of Lumbridge").isEmpty());
		assertTrue(PlaceLoader.names("1st floor&#91;UK&#93;2nd floor&#91;US&#93; of Champions' Guild").isEmpty());
		assertEquals("a real name that starts like a direction", Arrays.asList("East Ardougne"),
			PlaceLoader.names("East Ardougne"));
		assertEquals(Arrays.asList("Abandoned Mine"), PlaceLoader.names("Abandoned Mine - level 2"));
		assertTrue(PlaceLoader.names(null).isEmpty());
		assertTrue(PlaceLoader.names("").isEmpty());
		assertTrue(PlaceLoader.names("  -  ").isEmpty());
	}

	@Test
	public void theBundledDataGivesManyPlacesThatAreOnlySearchedForWhenAsked()
	{
		Gson gson = new Gson();
		MapCatalog catalog = MapCatalog.load(gson);
		List<SearchEntry> places = PlaceLoader.build(gson, catalog, MapElements.none());
		assertTrue("places " + places.size(), places.size() > 800);
		boolean boar = false;
		boolean castle = false;
		for (SearchEntry place : places)
		{
			assertEquals(SearchKind.PLACE, place.getKind());
			assertTrue(place.isLocation());
			boar |= place.getName().equals("Jolly Boar Inn");
			castle |= place.getName().equals("Lumbridge Castle");
		}
		assertTrue(boar);
		assertTrue(castle);
	}

	@Test
	public void theGamesLabelsAreAlsoPlaces()
	{
		Gson gson = new Gson();
		MapCatalog catalog = MapCatalog.load(gson);
		MapElements elements = MapElements.load(gson, catalog, Collections.emptyList());
		boolean swamp = false;
		for (SearchEntry place : PlaceLoader.build(gson, catalog, elements))
		{
			swamp |= place.getName().equals("Lumbridge Swamp") && "Area name".equals(place.getDetail());
		}
		assertTrue(swamp);
	}

	@Test
	public void aDescriptionThatStartsWithAPositionIsNotAPlace()
	{
		assertTrue(PlaceLoader.names("North-west of Lumbridge").isEmpty());
		assertTrue(PlaceLoader.names("Behind The Legless Faun pub").isEmpty());
		assertEquals(Arrays.asList("West Ardougne"), PlaceLoader.names("West Ardougne"));
	}

	@Test
	public void namesThatOnlyDifferByADirectionShareOneKey()
	{
		assertEquals(PlaceLoader.variantKey("West Heroes Guild dungeon"), PlaceLoader.variantKey("East Heroes' Guild Dungeon"));
		assertEquals(PlaceLoader.variantKey("The Legless Faun"), PlaceLoader.variantKey("Legless Faun"));
		assertFalse(PlaceLoader.variantKey("West Ardougne").equals(PlaceLoader.variantKey("Ardougne Castle")));
	}

	@Test
	public void theBundledPlacesHaveNoRepeatsAndIncludeThePubsAndTheExchange()
	{
		Gson gson = new Gson();
		MapCatalog catalog = MapCatalog.load(gson);
		MapElements elements = MapElements.load(gson, catalog, Collections.emptyList());
		List<SearchEntry> places = PlaceLoader.build(gson, catalog, elements);
		java.util.Set<String> seen = new java.util.HashSet<>();
		int pubs = 0;
		boolean exchange = false;
		for (SearchEntry place : places)
		{
			String name = place.getName().toLowerCase(java.util.Locale.ROOT);
			assertTrue("the same place twice: " + place.getName() + " at " + (int) place.getX() + "," + (int) place.getY(),
				seen.add(name + "|" + place.getMapId() + "|" + (int) place.getX() / 40 + "|" + (int) place.getY() / 40)
					|| name.startsWith("pub in "));
			pubs += name.contains("pub") || name.contains(" inn") ? 1 : 0;
			exchange |= name.equals("grand exchange");
		}
		assertTrue("pubs " + pubs, pubs >= 30);
		assertTrue(exchange);
	}
}
