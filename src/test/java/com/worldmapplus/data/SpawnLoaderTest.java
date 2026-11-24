package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchIndex;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SpawnLoaderTest
{
	private static final Gson GSON = new Gson();

	private static MapCatalog catalog()
	{
		List<MapInfo> maps = new ArrayList<>();
		maps.add(new MapInfo(0, "Gielinor Surface", 896, 1984, 4096, 4288, 2496, 3328));
		maps.add(new MapInfo(6, "Dwarven Mines", 2880, 9600, 3328, 9920, 3040, 9824));
		return new MapCatalog("v", maps);
	}

	private static List<SearchEntry> read(String json)
	{
		return SpawnLoader.read(GSON.fromJson(json, SpawnLoader.SpawnFile.class), catalog());
	}

	@Test
	public void eachGroupBecomesOneEntryWithAllItsTiles()
	{
		List<SearchEntry> entries = read("{\"monsters\":[{\"name\":\"Cow\",\"groups\":["
			+ "{\"where\":\"Lumbridge East farm\",\"pts\":[3254,3255,3254,3258,3261,3259]},"
			+ "{\"where\":\"Ardougne Farm\",\"pts\":[2657,3341]}]}]}");
		assertEquals(2, entries.size());
		SearchEntry lumbridge = entries.get(0);
		assertEquals(SearchKind.MONSTER, lumbridge.getKind());
		assertEquals("Cow", lumbridge.getName());
		assertEquals("Lumbridge East farm (3 spawns)", lumbridge.getDetail());
		assertEquals(3, lumbridge.getAlsoAt().size());
		assertEquals(0, lumbridge.getMapId());
		assertEquals("Ardougne Farm (1 spawn)", entries.get(1).getDetail());
	}

	@Test
	public void theViewLandsOnASpawnNearTheMiddle()
	{
		SearchEntry entry = read("{\"monsters\":[{\"name\":\"Cow\",\"groups\":["
			+ "{\"where\":\"x\",\"pts\":[3000,3000,3010,3000,3020,3000]}]}]}").get(0);
		assertEquals(3010.5, entry.getX(), 1e-9);
		assertEquals(3000.5, entry.getY(), 1e-9);
	}

	@Test
	public void aGroupInADungeonIsOnThatMap()
	{
		SearchEntry entry = read("{\"monsters\":[{\"name\":\"Dwarf\",\"groups\":["
			+ "{\"where\":\"Mines\",\"pts\":[3040,9800,3041,9801]}]}]}").get(0);
		assertEquals(6, entry.getMapId());
	}

	@Test
	public void tilesOnAnotherMapAreLeftOutAndUnknownMapsSkipped()
	{
		List<SearchEntry> entries = read("{\"monsters\":[{\"name\":\"Mixed\",\"groups\":["
			+ "{\"where\":\"mixed\",\"pts\":[3200,3200,3040,9800]},"
			+ "{\"where\":\"nowhere\",\"pts\":[10,10]}]}]}");
		assertEquals(1, entries.size());
		assertEquals(1, entries.get(0).getAlsoAt().size());
		assertEquals(0, entries.get(0).getMapId());
	}

	@Test
	public void npcsItemsQuestsAndMusicAreKindsOfTheirOwn()
	{
		List<SearchEntry> entries = read("{"
			+ "\"npcs\":[{\"name\":\"Betty\",\"groups\":[{\"where\":\"Port Sarim\",\"pts\":[3014,3258]}]}],"
			+ "\"items\":[{\"name\":\"Snape grass\",\"groups\":[{\"where\":\"Draynor\",\"pts\":[3100,3200]}]}],"
			+ "\"quests\":[{\"name\":\"Cook's Assistant\",\"x\":3211,\"y\":3216}],"
			+ "\"music\":[{\"name\":\"Harmony\",\"x\":3222,\"y\":3218}]}");
		assertEquals(4, entries.size());
		assertEquals(SearchKind.NPC, entries.get(0).getKind());
		assertEquals(SearchKind.ITEM, entries.get(1).getKind());
		assertTrue(entries.get(1).getDetail().startsWith("Free to take."));
		assertEquals(SearchKind.QUEST, entries.get(2).getKind());
		assertEquals("Quest start", entries.get(2).getDetail());
		assertEquals(SearchKind.MUSIC, entries.get(3).getKind());
	}

	@Test
	public void damagedDataGivesNothing()
	{
		assertTrue(read("null").isEmpty());
		assertTrue(read("{}").isEmpty());
		assertTrue(read("{\"monsters\":[null,{\"name\":null},{\"name\":\"x\",\"groups\":[null,{\"pts\":[1]}]}]}").isEmpty());
	}

	@Test
	public void theBundledSpawnDataIsUsefulWhenPresent()
	{
		MapCatalog bundled = MapCatalog.load(GSON);
		List<SearchEntry> entries = SpawnLoader.load(bundled);
		if (entries.isEmpty())
		{
			// The data file is produced by tools/build_spawns.py. Until it has been run there is nothing to check.
			return;
		}
		SearchIndex index = new SearchIndex(entries);
		assertTrue("cows", containsKind(index.search("cow", 40), SearchKind.MONSTER));
		assertTrue("a quest", containsKind(index.search("cook's assistant", 10), SearchKind.QUEST));
		assertTrue("entries " + entries.size(), entries.size() > 2000);
	}

	private static boolean containsKind(List<SearchEntry> entries, SearchKind kind)
	{
		for (SearchEntry entry : entries)
		{
			if (entry.getKind() == kind)
			{
				return true;
			}
		}
		return false;
	}
}
