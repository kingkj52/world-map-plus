package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSprites;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The icons of the game's own list, as the plugin's file holds them: every one has a picture in the atlas, lies on its map, is
 * counted, is there once, links to a map that exists, and the places that were reported are there.
 */
public class GameIconDataTest
{
	private static final Gson GSON = new Gson();
	private static MapCatalog catalog;
	private static JsonObject file;
	private static final Map<Integer, List<double[]>> rows = new TreeMap<>();
	private static List<String> categories = new ArrayList<>();
	private static List<Integer> pictures = new ArrayList<>();
	private static GameIconSprites sprites;
	private static MapAssets assets;

	@BeforeClass
	public static void load() throws IOException
	{
		catalog = MapCatalog.load(GSON);
		try (Reader reader = new InputStreamReader(GameIconDataTest.class.getResourceAsStream("/com/worldmapplus/map-elements.json"),
			StandardCharsets.UTF_8))
		{
			file = GSON.fromJson(reader, JsonObject.class);
		}
		for (JsonElement c : file.getAsJsonArray("categories"))
		{
			categories.add(c.getAsString());
		}
		for (JsonElement p : file.getAsJsonArray("sprites"))
		{
			pictures.add(p.getAsInt());
		}
		for (Map.Entry<String, JsonElement> entry : file.getAsJsonObject("icons").entrySet())
		{
			List<double[]> list = new ArrayList<>();
			for (JsonElement row : entry.getValue().getAsJsonArray())
			{
				JsonArray a = row.getAsJsonArray();
				double[] values = new double[a.size()];
				for (int i = 0; i < values.length; i++)
				{
					values[i] = a.get(i).getAsDouble();
				}
				list.add(values);
			}
			rows.put(Integer.parseInt(entry.getKey()), list);
		}
		sprites = GameIconSprites.load(pictures.size());
		assets = new MapAssets(GSON, Executors.newSingleThreadScheduledExecutor());
	}

	/** A map whose icons come from the game's own list: the game's maps, and the maps drawn from the game's data (ids from 11000). */
	private static boolean gameMap(int id)
	{
		return id < 10000 || id >= 11000;
	}

	@Test
	public void everyIconOfAGameMapHasAPictureThatExistsAndShowsSomething()
	{
		assertEquals("the atlas holds every picture", pictures.size(), sprites.size());
		Set<Integer> used = new HashSet<>();
		int drawn = 0;
		for (Map.Entry<Integer, List<double[]>> entry : rows.entrySet())
		{
			for (double[] row : entry.getValue())
			{
				assertTrue("a row is x, y, category, picture and floor, then a link's far tile and map", row.length == 5 || row.length == 8);
				int picture = (int) row[3];
				if (!gameMap(entry.getKey()))
				{
					assertEquals("an icon of a wiki only map has no picture of the game's: the tile shows it", -1, picture);
					continue;
				}
				int index = pictures.indexOf(picture);
				assertTrue("picture " + picture + " is in the list of the file", index >= 0);
				used.add(picture);
				drawn++;
				BufferedImage image = sprites.get(index);
				assertNotNull(image);
				boolean something = false;
				for (int y = 0; y < image.getHeight() && !something; y++)
				{
					for (int x = 0; x < image.getWidth(); x++)
					{
						something |= image.getRGB(x, y) >>> 24 != 0;
					}
				}
				assertTrue("picture " + picture + " is not blank", something);
			}
		}
		assertEquals("every picture of the atlas is used", new HashSet<>(pictures), used);
		assertTrue("icons drawn " + drawn, drawn > 3500);
	}

	@Test
	public void everyIconLiesOnItsMap()
	{
		for (Map.Entry<Integer, List<double[]>> entry : rows.entrySet())
		{
			MapInfo map = catalog.byId(entry.getKey());
			assertNotNull("map " + entry.getKey(), map);
			for (double[] row : entry.getValue())
			{
				int x = (int) Math.floor(row[0]);
				int y = (int) Math.floor(row[1]);
				assertTrue("icon at " + row[0] + ", " + row[1] + " is outside map " + map.getName(),
					x >= map.getMinX() && x < map.getMaxX() && y >= map.getMinY() && y < map.getMaxY());
				assertTrue("category " + row[2], row[2] >= 0 && row[2] < categories.size());
				assertTrue("floor " + row[4], row[4] >= 0 && row[4] <= 3);
			}
		}
	}

	@Test
	public void theCountsOnEveryMapAndFloorAreTheCountsOfTheGamesList() throws IOException
	{
		File counts = new File("tools/input/game-icon-counts.json");
		Assume.assumeTrue(counts.exists());
		JsonObject expected;
		try (Reader reader = Files.newBufferedReader(counts.toPath(), StandardCharsets.UTF_8))
		{
			expected = GSON.fromJson(reader, JsonObject.class);
		}
		int total = 0;
		for (Map.Entry<Integer, List<double[]>> entry : rows.entrySet())
		{
			if (!gameMap(entry.getKey()))
			{
				continue;
			}
			int[] floors = new int[4];
			for (double[] row : entry.getValue())
			{
				floors[(int) row[4]]++;
			}
			JsonArray want = expected.getAsJsonObject("maps").getAsJsonArray(String.valueOf(entry.getKey()));
			assertNotNull("map " + entry.getKey() + " has icons in the file, so the list has some", want);
			for (int floor = 0; floor < 4; floor++)
			{
				assertEquals("map " + entry.getKey() + " floor " + floor, want.get(floor).getAsInt(), floors[floor]);
				total += floors[floor];
			}
		}
		assertEquals(expected.get("total").getAsInt(), total);
		assertEquals("a map of the list has icons in the file", expected.getAsJsonObject("maps").size(),
			rows.keySet().stream().filter(GameIconDataTest::gameMap).count());
	}

	@Test
	public void noIconIsThereTwice()
	{
		for (Map.Entry<Integer, List<double[]>> entry : rows.entrySet())
		{
			Set<String> seen = new HashSet<>();
			for (double[] row : entry.getValue())
			{
				assertTrue("a second icon of the same kind at " + row[0] + ", " + row[1] + " floor " + row[4] + " on map "
					+ entry.getKey(), seen.add(row[0] + ":" + row[1] + ":" + row[2] + ":" + row[4]));
			}
		}
	}

	@Test
	public void theLinksOfIconsGoToAnotherMapThatExists()
	{
		int linked = 0;
		for (Map.Entry<Integer, List<double[]>> entry : rows.entrySet())
		{
			for (double[] row : entry.getValue())
			{
				if (row.length < 8)
				{
					continue;
				}
				linked++;
				// The far map is the one the row names, and it has a picture of the far tile (round 48).
				MapInfo far = catalog.byId((int) row[7]);
				assertNotNull("the far map of the link of " + row[0] + ", " + row[1] + " on " + entry.getKey(), far);
				assertTrue("the far map of " + row[0] + ", " + row[1] + " shows its tile", far.contains((int) row[5], (int) row[6]));
				assertTrue("a link leaves its map", far.getId() != entry.getKey());
			}
		}
		assertTrue("icons with a link " + linked, linked > 150);
		for (JsonElement link : file.getAsJsonArray("links"))
		{
			JsonArray l = link.getAsJsonArray();
			assertEquals("a link is two tiles and two maps " + l, 6, l.size());
			MapInfo from = catalog.byId(l.get(4).getAsInt());
			MapInfo to = catalog.byId(l.get(5).getAsInt());
			assertNotNull("link from " + l, from);
			assertNotNull("link to " + l, to);
			assertTrue("the near map shows the near tile " + l, from.contains(l.get(0).getAsInt(), l.get(1).getAsInt()));
			assertTrue("the far map shows the far tile " + l, to.contains(l.get(2).getAsInt(), l.get(3).getAsInt()));
			assertTrue(from.getId() != to.getId());
		}
	}

	@Test
	public void theKalphiteCaveHasItsLinkLikeTheKalphiteLair()
	{
		double[] cave = rowAt(0, 3319.5, 3122.5);
		double[] lair = rowAt(0, 3226.5, 3108.5);
		for (double[] row : new double[][] {cave, lair})
		{
			assertTrue("an entrance with a link at " + row[0] + ", " + row[1], row.length == 8);
			assertEquals(42, (int) row[7]);
		}
		// The card the player sees on the cave entrance previews that map and a click on it opens it.
		Hotspot card = null;
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			if (hotspot.getMapId() == 0 && Math.hypot(hotspot.getX() - 3319.5, hotspot.getY() - 3122.5) < 1.5 && hotspot.getTarget() != null)
			{
				card = hotspot;
			}
		}
		assertNotNull("a card with a target on the Kalphite Cave entrance", card);
		assertEquals(42, card.getTarget().getMapId());
		assertTrue(card.getCard().hasPreview());
	}

	@Test
	public void fairyRingsAreDrawnWithTheBlueIcon()
	{
		int blue = pictures.indexOf(90001);
		int plain = pictures.indexOf(1504);
		assertTrue(blue >= 0 && plain >= 0);
		assertTrue("the blue icon is the travel arrow with a cyan rim", has(sprites.get(blue), 26, 219, 219));
		assertFalse("and has none of the orange", has(sprites.get(blue), 228, 100, 21));
		assertTrue("the ordinary travel icon is orange", has(sprites.get(plain), 228, 100, 21));
		int rings = 0;
		for (double[] row : rows.get(0))
		{
			if ((int) row[3] == 90001)
			{
				rings++;
				assertEquals("Transportation", categories.get((int) row[2]));
			}
		}
		assertTrue("fairy rings on the surface " + rings, rings >= 40);
	}

	private static boolean has(BufferedImage image, int r, int g, int b)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) & 0xFFFFFF) == (r << 16 | g << 8 | b))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static double[] rowAt(int map, double x, double y)
	{
		for (double[] row : rows.get(map))
		{
			if (Math.abs(row[0] - x) < 0.6 && Math.abs(row[1] - y) < 0.6)
			{
				return row;
			}
		}
		throw new AssertionError("no icon at " + x + ", " + y + " on map " + map);
	}

	private static Hotspot cardNear(int map, double x, double y)
	{
		Hotspot best = null;
		double bestDistance = 1.2;
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			double d = Math.hypot(hotspot.getX() - x, hotspot.getY() - y);
			if (hotspot.getMapId() == map && d <= bestDistance)
			{
				bestDistance = d;
				best = hotspot;
			}
		}
		return best;
	}

	@Test
	public void theIconsTheOwnerReportedMissingNowAnswerTheMouse()
	{
		Hotspot poll = cardNear(0, 3172.5, 2988.5);
		assertNotNull("the poll booth in the Bandit Camp", poll);
		assertEquals("Poll booth", poll.getCard().getTitle());
		Hotspot quest = cardNear(0, 3190.5, 2926.5);
		assertNotNull("the quest start north east of the quarry", quest);
		assertTrue("quest card " + quest.getCard().getTitle(), quest.getCard().getTitle().contains("Quest")
			|| quest.getCard().getSubtitle() != null && quest.getCard().getSubtitle().contains("Quest"));
	}

	@Test
	public void theBigCitiesWithAMapOfTheirOwnHaveTheFullSet()
	{
		int keldagrim = 0;
		for (double[] row : rows.get(10))
		{
			assertTrue((int) row[3] >= 0);
			keldagrim++;
		}
		assertTrue("Keldagrim icons " + keldagrim, keldagrim >= 38);
		assertEquals("Ruins of Camdozaal", catalog.byId(39).getName());
		assertEquals(8, rows.get(39).size());
		// Each of them has a card to hover and a picture to draw.
		int cards = 0;
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			if (hotspot.getMapId() == 10 && hotspot.isDrawn())
			{
				cards++;
			}
		}
		assertTrue("cards on Keldagrim icons " + cards, cards >= 5);
	}

	@Test
	public void theWikiOnlyMapsKeepTheDiscsTheTilesShow() throws IOException
	{
		File discs = new File("tools/input/map-icons.json");
		Assume.assumeTrue(discs.exists());
		int expected = 0;
		try (Reader reader = Files.newBufferedReader(discs.toPath(), StandardCharsets.UTF_8))
		{
			for (Map.Entry<String, JsonElement> entry : GSON.fromJson(reader, JsonObject.class).getAsJsonObject("maps").entrySet())
			{
				if (Integer.parseInt(entry.getKey()) >= 10000)
				{
					expected += entry.getValue().getAsJsonArray().size();
				}
			}
		}
		int found = 0;
		for (Map.Entry<Integer, List<double[]>> entry : rows.entrySet())
		{
			if (!gameMap(entry.getKey()))
			{
				found += entry.getValue().size();
			}
		}
		assertEquals(expected, found);
	}
}
