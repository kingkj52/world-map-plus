package com.worldmapplus.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchIndex;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.search.BundledSearch;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The maps drawn from the game cache (ids from 11000), against the list they were made from, tools/input/instance-areas.json.
 * A member of a group who stands in a raid, a boss lair or an altar has to be drawn on a map of ours, and no older map may lose a
 * tile to the new ones.
 */
public class InstanceAreasTest
{
	private static final int FIRST = 11_000;
	private static final int LAST = 19_999;
	private static final Gson GSON = new Gson();

	private static MapCatalog catalog;
	private static MapCatalog older;
	private static List<JsonObject> areas = new ArrayList<>();

	@BeforeClass
	public static void load() throws IOException
	{
		catalog = MapCatalog.load(GSON);
		List<MapInfo> without = new ArrayList<>();
		for (MapInfo map : catalog.getMaps())
		{
			if (!cacheDrawn(map))
			{
				without.add(map);
			}
		}
		older = new MapCatalog(catalog.getVersion(), without);
		File file = new File("tools/input/instance-areas.json");
		Assume.assumeTrue("the list of areas is not in this copy", file.isFile());
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			for (JsonElement element : GSON.fromJson(reader, JsonObject.class).getAsJsonArray("areas"))
			{
				areas.add(element.getAsJsonObject());
			}
		}
	}

	private static boolean cacheDrawn(MapInfo map)
	{
		return map.getId() >= FIRST && map.getId() <= LAST;
	}

	private static int[][] squares(JsonObject area)
	{
		JsonArray list = area.getAsJsonArray("squares");
		int[][] out = new int[list.size()][];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = new int[] {list.get(i).getAsJsonArray().get(0).getAsInt(), list.get(i).getAsJsonArray().get(1).getAsInt()};
		}
		return out;
	}

	/** The tiles worth asking about in a square: the four corners, the middle and a few between. */
	private static int[][] samples(int sx, int sy)
	{
		int x = sx * 64;
		int y = sy * 64;
		return new int[][] {{x, y}, {x + 63, y}, {x, y + 63}, {x + 63, y + 63}, {x + 32, y + 32}, {x + 7, y + 50}, {x + 49, y + 11}};
	}

	@Test
	public void everyListedAreaHasAMapWithItsNameAndSquares()
	{
		assertFalse(areas.isEmpty());
		Set<Integer> ids = new HashSet<>();
		for (JsonObject area : areas)
		{
			int id = area.get("id").getAsInt();
			assertTrue("each id is new and in the cache drawn range", ids.add(id) && id >= FIRST && id <= LAST);
			MapInfo map = catalog.byId(id);
			assertNotNull(area.get("name").getAsString() + " has a map", map);
			assertEquals(area.get("name").getAsString(), map.getName());
			assertFalse("a cache drawn map is one of the wiki's extra maps, which a standard map outranks", map.isStandard());
			Set<Long> want = new HashSet<>();
			for (int[] sq : squares(area))
			{
				want.add((long) sq[0] << 16 | sq[1]);
			}
			int found = 0;
			for (int sx = map.firstSquareX(); sx <= map.lastSquareX(); sx++)
			{
				for (int sy = map.firstSquareY(); sy <= map.lastSquareY(); sy++)
				{
					if (map.contains(sx * 64, sy * 64))
					{
						assertTrue(map.getName() + " has square " + sx + "," + sy + " that the list does not", want.contains((long) sx << 16 | sy));
						found++;
					}
				}
			}
			assertEquals(map.getName() + " squares", want.size(), found);
			assertTrue("the map opens on a tile of its own", map.contains(map.getCenterX(), map.getCenterY()));
		}
	}

	@Test
	public void mapForGivesTheNewMapForEveryTileOfEachSquare()
	{
		for (JsonObject area : areas)
		{
			int id = area.get("id").getAsInt();
			for (int[] sq : squares(area))
			{
				for (int[] tile : samples(sq[0], sq[1]))
				{
					MapInfo map = catalog.mapFor(tile[0], tile[1]);
					assertNotNull(area.get("name").getAsString() + " at " + tile[0] + "," + tile[1], map);
					assertEquals(area.get("name").getAsString() + " at " + tile[0] + "," + tile[1], id, map.getId());
				}
			}
		}
	}

	@Test
	public void noOlderMapLosesATile()
	{
		int checked = 0;
		for (MapInfo map : older.getMaps())
		{
			for (int sx = map.firstSquareX(); sx <= map.lastSquareX(); sx++)
			{
				for (int sy = map.firstSquareY(); sy <= map.lastSquareY(); sy++)
				{
					if (!map.contains(sx * 64, sy * 64))
					{
						continue;
					}
					for (int[] tile : samples(sx, sy))
					{
						MapInfo before = older.mapFor(tile[0], tile[1]);
						MapInfo now = catalog.mapFor(tile[0], tile[1]);
						assertEquals("tile " + tile[0] + "," + tile[1] + " of " + map.getName(), before.getId(), now.getId());
						MapInfo near = catalog.mapNear(tile[0], tile[1], 192);
						assertEquals("the nearest map of a tile that has one", before.getId(), near.getId());
						checked++;
					}
				}
			}
		}
		assertTrue("checked " + checked + " tiles", checked > 10_000);
	}

	@Test
	public void theNewMapsShareNoSquareWithAnyOlderMap()
	{
		Set<Long> taken = new HashSet<>();
		for (MapInfo map : older.getMaps())
		{
			for (int sx = map.firstSquareX(); sx <= map.lastSquareX(); sx++)
			{
				for (int sy = map.firstSquareY(); sy <= map.lastSquareY(); sy++)
				{
					if (map.contains(sx * 64, sy * 64))
					{
						taken.add((long) sx << 16 | sy);
					}
				}
			}
		}
		for (MapInfo map : catalog.getMaps())
		{
			if (!cacheDrawn(map))
			{
				continue;
			}
			for (int sx = map.firstSquareX(); sx <= map.lastSquareX(); sx++)
			{
				for (int sy = map.firstSquareY(); sy <= map.lastSquareY(); sy++)
				{
					if (map.contains(sx * 64, sy * 64))
					{
						assertTrue(map.getName() + " square " + sx + "," + sy + " is also an older map's", taken.add((long) sx << 16 | sy));
					}
				}
			}
		}
	}

	@Test
	public void theNearestMapFallbackOnlyChangesWhereTheNewMapIsCloser()
	{
		int different = 0;
		for (JsonObject area : areas)
		{
			for (int[] sq : squares(area))
			{
				for (int dx = -400; dx <= 400; dx += 40)
				{
					for (int dy = -400; dy <= 400; dy += 40)
					{
						int x = sq[0] * 64 + 32 + dx;
						int y = sq[1] * 64 + 32 + dy;
						if (x < 0 || y < 0)
						{
							continue;
						}
						MapInfo before = older.mapNear(x, y, 192);
						MapInfo now = catalog.mapNear(x, y, 192);
						if (before != null && older.mapFor(x, y) != null)
						{
							assertEquals(before.getId(), now.getId());
						}
						else if (before == null ? now != null : now.getId() != before.getId())
						{
							assertTrue("only a new map takes over a tile no older map covers: " + x + "," + y, cacheDrawn(now));
							assertTrue(now.distanceTo(x, y) <= (before == null ? 192 : before.distanceTo(x, y)));
							different++;
						}
					}
				}
			}
		}
		assertTrue("some tiles near the new maps now have a nearer map", different > 0);
	}

	@Test
	public void shopsAndOtherDataWithCoordinatesInTheNewAreasLandOnTheirMaps()
	{
		int onNew = 0;
		for (Hotspot hotspot : com.worldmapplus.data.MapElements.load(GSON).loaded(java.util.Collections.emptyList()))
		{
			if (hotspot.getMapId() >= FIRST && hotspot.getMapId() <= LAST)
			{
				MapInfo at = catalog.mapFor((int) Math.floor(hotspot.getX()), (int) Math.floor(hotspot.getY()));
				assertEquals(hotspot.getCard().getTitle(), hotspot.getMapId(), at.getId());
				onNew++;
			}
		}
		assertTrue("shops and the like in the raids and minigames now have a map: " + onNew, onNew > 0);
	}

	@Test
	public void theNamesAreSearchableAsPlaces()
	{
		SearchIndex index = new SearchIndex(BundledSearch.maps());
		for (JsonObject area : areas)
		{
			String name = area.get("name").getAsString();
			boolean found = false;
			for (SearchEntry entry : index.search(name, 10))
			{
				found |= entry.getKind() == SearchKind.PLACE && entry.getName().equals(name)
					&& entry.getMapId() == area.get("id").getAsInt();
			}
			assertTrue(name + " is found as a place", found);
		}
		SearchEntry first = index.search("guardians of the rift", 3).get(0);
		assertEquals(SearchKind.PLACE, first.getKind());
		assertEquals("Map", first.getDetail());
	}

	@Test
	public void everyAreaSaysWhyItIsThereWhereItComesFromAndWhatAMembersPointIs()
	{
		Set<String> kinds = new HashSet<>();
		for (JsonObject area : areas)
		{
			for (String key : new String[] {"name", "why", "source", "kind", "where"})
			{
				assertTrue(area.get("name").getAsString() + " has " + key, !area.get(key).getAsString().trim().isEmpty());
			}
			kinds.add(area.get("kind").getAsString());
			String where = area.get("where").getAsString();
			assertTrue(where, area.get("kind").getAsString().equals("template") ? where.contains("fromLocalInstance")
				: where.startsWith("Real coordinates"));
		}
		assertEquals(new HashSet<>(java.util.Arrays.asList("real", "template")), kinds);
	}
}
