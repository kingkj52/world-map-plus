package com.worldmapplus.data;

import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.DoorwayGraph;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.search.SearchEntry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Checks that the bundled datasets agree with each other, so a bad regeneration is caught by the build. */
public class BundledDataTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void mostThingsInDungeonsCanBeReachedFromTheSurfaceThroughDoorways()
	{
		MapCatalog catalog = MapCatalog.load(GSON);
		List<Hotspot> hotspots = HotspotLoader.load(GSON, catalog);
		DoorwayGraph graph = new DoorwayGraph(hotspots);
		com.google.gson.JsonObject names = GSON.fromJson(new java.io.InputStreamReader(
			BundledDataTest.class.getResourceAsStream("/com/worldmapplus/place-names.json"), java.nio.charset.StandardCharsets.UTF_8),
			com.google.gson.JsonObject.class);
		List<com.worldmapplus.map.MapInfo> entries = new java.util.ArrayList<>();
		for (com.google.gson.JsonElement row : names.getAsJsonArray("spawns"))
		{
			com.worldmapplus.map.MapInfo at = catalog.mapFor(row.getAsJsonArray().get(1).getAsInt(), row.getAsJsonArray().get(2).getAsInt());
			if (at != null)
			{
				entries.add(at);
			}
		}

		Set<Integer> underground = new HashSet<>();
		Map<Integer, Integer> unreachable = new HashMap<>();
		for (com.worldmapplus.map.MapInfo entry : entries)
		{
			if (entry.getId() == 0 || entry.getId() >= com.worldmapplus.map.MapInfo.FIRST_EXTRA)
			{
				// The wiki's extra maps are single rooms and cutscene areas that are mostly reached from
				// inside another map, so only the standard maps are expected to have a doorway.
				continue;
			}
			underground.add(entry.getId());
			if (graph.firstSteps(0, entry.getId()).isEmpty())
			{
				unreachable.merge(entry.getId(), 1, Integer::sum);
			}
		}
		assertTrue("expected spawns in dungeons, found maps " + underground, underground.size() >= 10);
		// Some detached maps (instances, Zanaris routes, minigames) have no doorway from the surface.
		// Most of the ones that hold spawns must have one.
		double reachableMaps = 1.0 - unreachable.size() / (double) underground.size();
		assertTrue("unreachable maps: " + unreachable, reachableMaps >= 0.5);
	}

	@Test
	public void theWikisExtraMapsAreBundledAndSearchable()
	{
		MapCatalog catalog = MapCatalog.load(GSON);
		int extras = 0;
		for (com.worldmapplus.map.MapInfo map : catalog.getMaps())
		{
			if (!map.isStandard())
			{
				extras++;
			}
		}
		assertTrue("extra maps " + extras, extras > 100);
		assertTrue(catalog.byId(10128) != null && catalog.byId(10128).getName().equals("Puro-Puro"));
		assertTrue("Puro-Puro is under no standard map, so it answers for its own tiles",
			catalog.mapFor(2592, 4320) != null && catalog.mapFor(2592, 4320).getId() == 10128);
		assertTrue("where a standard map covers the tiles, it answers",
			catalog.mapFor(1312, 10080) != null && catalog.mapFor(1312, 10080).isStandard());
	}

	@Test
	public void everyDoorwayEndIsOnTheMapItNamesAndOnlyTwoSidedOnesLeadAnywhere()
	{
		MapCatalog catalog = MapCatalog.load(GSON);
		List<Hotspot> doorways = new java.util.ArrayList<>();
		for (Hotspot hotspot : HotspotLoader.load(GSON, catalog))
		{
			if (hotspot.getKind() == Hotspot.Kind.DOORWAY)
			{
				doorways.add(hotspot);
			}
		}
		assertTrue("doorways " + doorways.size(), doorways.size() > 400);
		int followable = 0;
		for (Hotspot door : doorways)
		{
			// Round 48: a doorway's map is the one the data names, which must have a picture of the tile. The most specific map that
			// covers the tile is not always it: the Morytania Underground draws the Myreque Hideout over the same squares as the
			// Kharidian Desert Underground draws the Giants' Foundry.
			com.worldmapplus.map.MapInfo here = catalog.byId(door.getMapId());
			assertTrue("a doorway on a map with no picture of it: " + door.getCard().getTitle(),
				here != null && here.contains((int) Math.floor(door.getX()), (int) Math.floor(door.getY())));
			if (door.getTarget() != null)
			{
				followable++;
				com.worldmapplus.map.MapInfo there = catalog.byId(door.getTarget().getMapId());
				assertTrue("a doorway that leads nowhere: " + door.getCard().getTitle(),
					there != null && there.contains((int) Math.floor(door.getTarget().getX()), (int) Math.floor(door.getTarget().getY())));
			}
		}
		assertTrue("followable doorways " + followable, followable > 400);
	}

	@Test
	public void shopCardsStayNearTheWikisPinsAndMostSitOnAnIcon() throws Exception
	{
		org.junit.Assume.assumeTrue("the build inputs are not in this copy", java.nio.file.Files.exists(java.nio.file.Paths.get("tools", "input", "shops.json")));
		com.google.gson.JsonObject file;
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get("tools", "input", "shops.json"),
			java.nio.charset.StandardCharsets.UTF_8))
		{
			file = GSON.fromJson(reader, com.google.gson.JsonObject.class);
		}
		int shops = 0;
		int moved = 0;
		for (com.google.gson.JsonElement element : file.getAsJsonArray("shops"))
		{
			com.google.gson.JsonObject shop = element.getAsJsonObject();
			shops++;
			assertTrue("every shop keeps the wiki's pin: " + shop.get("name"), shop.has("pin"));
			double dx = shop.get("x").getAsInt() - shop.getAsJsonArray("pin").get(0).getAsInt();
			double dy = shop.get("y").getAsInt() - shop.getAsJsonArray("pin").get(1).getAsInt();
			double away = Math.hypot(dx, dy);
			assertTrue(shop.get("name") + " moved " + away + " tiles", away <= 21);
			if (away > 0)
			{
				moved++;
			}
		}
		assertTrue("shops moved onto icons: " + moved + " of " + shops, moved * 4 >= shops);
	}

	@Test
	public void theCatalogueCoversTheWholeSurfaceTheDataPointsAt()
	{
		MapCatalog catalog = MapCatalog.load(GSON);
		// A few well known places that must always be on the surface map.
		int[][] places = {{3222, 3218}, {3213, 3428}, {2964, 3378}, {3093, 3493}, {2656, 3286}};
		for (int[] place : places)
		{
			assertTrue("no map at " + place[0] + "," + place[1],
				catalog.mapFor(place[0], place[1]) != null && catalog.mapFor(place[0], place[1]).getId() == 0);
		}
	}
}
