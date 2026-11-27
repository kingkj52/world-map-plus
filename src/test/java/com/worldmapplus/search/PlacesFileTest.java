package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.map.MapCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * {@code places.json}, made by {@code tools/build_places.py}, and the named places, which {@code tools/build_search.py} puts among the
 * search entries (round 69). Until round 67 the plugin worked the places, the town squares and the label areas out itself;
 * {@code golden/places-derived.json} is what that Java code made from the data of the time, and the bundled files must read back as
 * exactly that. What they read as now is written to {@code build/places-read.json}. After a data update that is meant to change places,
 * look at the difference and copy that file over the golden one.
 */
public class PlacesFileTest
{
	private static Gazetteer.Places file;
	/** The named places among the search entries. */
	private static List<SearchEntry> named;

	@BeforeClass
	public static void read()
	{
		file = MapAssets.json(new Gson(), "places.json", Gazetteer.Places.class);
		assertNotNull(file);
		named = new ArrayList<>();
		for (SearchEntry e : BundledSearch.of(SearchKind.PLACE))
		{
			if (e.isLocation())
			{
				named.add(e);
			}
		}
	}

	@Test
	public void theBundledFileReadsBackAsWhatTheOldJavaCodeMade() throws IOException
	{
		Gson gson = new Gson();
		List<String> places = new ArrayList<>();
		for (SearchEntry e : named)
		{
			places.add("{\"n\":" + gson.toJson(e.getName()) + ",\"d\":" + gson.toJson(e.getDetail()) + ",\"m\":" + e.getMapId()
				+ ",\"x\":" + e.getX() + ",\"y\":" + e.getY() + "}");
		}
		List<String> towns = new ArrayList<>();
		for (Gazetteer.Named town : file.towns)
		{
			towns.add("{\"n\":" + gson.toJson(town.n) + ",\"m\":" + town.m + ",\"x\":" + town.x + ",\"y\":" + town.y + ",\"r\":" + town.r
				+ "}");
		}
		List<String> areas = new ArrayList<>();
		for (Gazetteer.Named area : file.areas)
		{
			areas.add("{\"n\":" + gson.toJson(area.n) + ",\"m\":" + area.m + ",\"s\":" + area.s + ",\"x\":" + area.x + ",\"y\":" + area.y
				+ "}");
		}
		String text = "{\"places\":[\n" + String.join(",\n", places) + "\n],\"towns\":[\n" + String.join(",\n", towns)
			+ "\n],\"areas\":[\n" + String.join(",\n", areas) + "\n]}\n";
		Path out = Paths.get("build", "places-read.json");
		Files.createDirectories(out.getParent());
		Files.write(out, text.getBytes(StandardCharsets.UTF_8));
		String golden = new String(Files.readAllBytes(Paths.get("src", "test", "resources", "golden", "places-derived.json")),
			StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertEquals(golden, text);
	}

	@Test
	public void thePlacesAreManyAndOnlySearchedForWhenAsked()
	{
		List<SearchEntry> places = named;
		assertTrue("places " + places.size(), places.size() > 800);
		Set<String> seen = new HashSet<>();
		int pubs = 0;
		boolean boar = false;
		boolean castle = false;
		boolean swamp = false;
		boolean exchange = false;
		for (SearchEntry place : places)
		{
			assertEquals(SearchKind.PLACE, place.getKind());
			assertTrue(place.isLocation());
			String name = place.getName().toLowerCase(Locale.ROOT);
			assertTrue("the same place twice: " + place.getName() + " at " + (int) place.getX() + "," + (int) place.getY(),
				seen.add(name + "|" + place.getMapId() + "|" + (int) place.getX() / 40 + "|" + (int) place.getY() / 40)
					|| name.startsWith("pub in "));
			pubs += name.contains("pub") || name.contains(" inn") ? 1 : 0;
			boar |= place.getName().equals("Jolly Boar Inn");
			castle |= place.getName().equals("Lumbridge Castle");
			swamp |= place.getName().equals("Lumbridge Swamp") && "Area name".equals(place.getDetail());
			exchange |= name.equals("grand exchange");
		}
		assertTrue("pubs " + pubs, pubs >= 30);
		assertTrue(boar && castle && swamp && exchange);
	}

	@Test
	public void townsAndLabelsSayWhatASpotIsIn()
	{
		Gson gson = new Gson();
		Gazetteer gazetteer = new Gazetteer(MapCatalog.load(gson), MapAssets.json(gson, "regions.json", Gazetteer.Regions.class), file);
		assertTrue(gazetteer.contains("varrock", 0, 3212, 3428));
		assertTrue(gazetteer.contains("farming guild", 0, 1249, 3727));
		assertTrue(!gazetteer.contains("varrock", 0, 2964, 3378));
		assertTrue(gazetteer.knows("lumbridge swamp"));
		// A sea is no place a shop or a monster is in.
		for (Gazetteer.Named area : file.areas)
		{
			assertTrue(area.n, !area.key.matches(".*\\b(sea|ocean)\\b.*"));
		}
	}
}
