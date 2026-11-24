package com.worldmapplus.wiki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Which things get a wiki page and under what title: the name the data carries, and none where a page is not certain. */
public class WikiTitlesTest
{
	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor);
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	private static SearchEntry entry(SearchKind kind, String name, String detail)
	{
		return new SearchEntry(kind, name, detail, 0, 3200.5, 3200.5);
	}

	@Test
	public void theKindsTheDataTookFromWikiPagesAreGivenTheirNameAsTheTitle()
	{
		assertEquals("Cook's Assistant", WikiTitles.forEntry(entry(SearchKind.QUEST, "Cook's Assistant", "Quest start")));
		assertEquals("Aberrant spectre", WikiTitles.forEntry(entry(SearchKind.MONSTER, "Aberrant spectre", "")));
		assertEquals("Vial of water", WikiTitles.forEntry(entry(SearchKind.ITEM, "Vial of water", "Sold at X for 2 coins")));
		assertEquals("Aubury", WikiTitles.forEntry(entry(SearchKind.NPC, "Aubury", "Runs Aubury's Rune Shop")));
		assertEquals("Harmony", WikiTitles.forEntry(entry(SearchKind.MUSIC, "Harmony", "")));
	}

	@Test
	public void aShopLosesTheFullStopTheWikiEndsItsNameWith()
	{
		assertEquals("Aaron's Archery Appendages", WikiTitles.forEntry(entry(SearchKind.SHOP, "Aaron's Archery Appendages.", "")));
		assertEquals("Aaron's Archery Appendages", WikiTitles.forShop("Aaron's Archery Appendages."));
		assertEquals("Between a Rock...", WikiTitles.clean("Between a Rock..."));
	}

	@Test
	public void anAreaNameOfTheGamesMapIsAPageAndABareMapNameIsNot()
	{
		assertEquals("Kingdom of Varlamore", WikiTitles.forEntry(entry(SearchKind.PLACE, "Kingdom of Varlamore", "Area name")));
		assertNull("our own map names", WikiTitles.forEntry(entry(SearchKind.PLACE, "Map", "Map")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.PLACE, "Kharidian Desert Underground", "Map")));
		assertNull("a place the wiki describes in words", WikiTitles.forEntry(entry(SearchKind.PLACE, "Aldarin - Alchemical Society", "Aldarin")));
		assertNull("a word for a kind of place", WikiTitles.forEntry(entry(SearchKind.PLACE, "Map", "Area name")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.PLACE, "Cave", "Area name")));
	}

	@Test
	public void onlyTheKindsOfTransportThatArePagesAreGivenOne()
	{
		assertEquals("Charter ship", WikiTitles.forEntry(entry(SearchKind.TRANSPORT, "Charter ship: Aldarin", "Brimhaven")));
		assertEquals("Fairy ring", WikiTitles.forEntry(entry(SearchKind.TRANSPORT, "Fairy ring: Falador", "")));
		assertEquals("Spirit tree", WikiTitles.forTransport("Spirit tree"));
		assertNull(WikiTitles.forEntry(entry(SearchKind.TRANSPORT, "Portal: Somewhere", "")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.TRANSPORT, "Lever", "")));
		assertNull(WikiTitles.forTransport("Quetzal"));
	}

	@Test
	public void aTeleportSpellIsAPageAndAnItemOrAPlaceIsNot()
	{
		assertEquals("Varrock Teleport", WikiTitles.forEntry(entry(SearchKind.TELEPORT, "Varrock Teleport", "")));
		assertEquals("Varrock Teleport", WikiTitles.forEntry(entry(SearchKind.TELEPORT, "Varrock Teleport: Grand Exchange", "")));
		assertEquals("Teleport to House", WikiTitles.forEntry(entry(SearchKind.TELEPORT, "Teleport to House (Inside)", "")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.TELEPORT, "Ardougne cloak: Kandarin Monastery", "")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.TELEPORT, "Teleport", "")));
	}

	@Test
	public void anEntranceAndATileAndAPinAreNeverGivenOne()
	{
		assertNull(WikiTitles.forEntry(entry(SearchKind.DOORWAY, "Cave passage", "Leads underground")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.TILE, "Tile 3222, 3218", "")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.PIN, "My pin", "")));
		assertNull(WikiTitles.forEntry(null));
	}

	@Test
	public void courseAndMinigameAndTreeNamesAreGivenOneOnlyWhenTheyAreCertain()
	{
		assertEquals("Draynor Village Rooftop Course", WikiTitles.forPointLayer("agility", "Draynor Village Rooftop Course", "Agility course"));
		assertNull(WikiTitles.forPointLayer("agility", "Penguin Agility Course", "Agility course"));
		assertEquals("Tithe Farm", WikiTitles.forPointLayer("minigame", "Tithe Farm", "Minigame"));
		assertNull("a room of the Warriors' Guild", WikiTitles.forPointLayer("minigame", "Animation Room", "Minigame"));
		assertNull("a grade of Pest Control", WikiTitles.forPointLayer("minigame", "Pest Control Novice", "Minigame"));
		assertNull("with words about a trial", WikiTitles.forPointLayer("minigame", "Tithe Farm", "Barracuda Trial at Backwater"));
		assertEquals("Yew tree", WikiTitles.forPointLayer("tree", "Yew tree", "Woodcutting level 60"));
		assertNull(WikiTitles.forPointLayer("tree", "Maple/Yew trees", "Woodcutting level 60"));
		assertNull(WikiTitles.forPointLayer("fishing", "Lumbridge Swamp", "Fishing"));
		assertEquals("Tithe Farm", WikiTitles.forEntry(entry(SearchKind.POINT, "Tithe Farm", "Minigame")));
		assertNull(WikiTitles.forEntry(entry(SearchKind.POINT, "Bank", "Bank")));
	}

	@Test
	public void everyTitleTheBundledSearchHasMakesACleanAddress()
	{
		Map<SearchKind, int[]> counts = new EnumMap<>(SearchKind.class);
		for (SearchEntry e : assets.getSearchEntries())
		{
			int[] count = counts.computeIfAbsent(e.getKind(), k -> new int[2]);
			count[0]++;
			String title = WikiTitles.forEntry(e);
			if (title != null)
			{
				count[1]++;
				assertNotNull(title, WikiLinks.url(title));
				assertTrue(title, WikiLinks.isWikiAddress(WikiLinks.url(title)));
				assertEquals("nothing has been changed but the end", title.trim(), title);
			}
		}
		for (Map.Entry<SearchKind, int[]> e : counts.entrySet())
		{
			System.out.println("wiki titles: " + e.getKey() + " " + e.getValue()[1] + " of " + e.getValue()[0]);
		}
		assertTrue(counts.get(SearchKind.QUEST)[1] > 100);
		assertTrue(counts.get(SearchKind.SHOP)[1] > 300);
		assertTrue(counts.get(SearchKind.MONSTER)[1] > 300);
		assertEquals("no entrance has one", 0, counts.get(SearchKind.DOORWAY)[1]);
		assertEquals(0, counts.get(SearchKind.TILE) == null ? 0 : counts.get(SearchKind.TILE)[1]);
	}

	@Test
	public void noMapNameIsGivenATitleUnlessTheGameMapPrintsIt()
	{
		for (SearchEntry e : assets.getSearchEntries())
		{
			if (e.getKind() == SearchKind.PLACE && "Map".equals(e.getDetail()))
			{
				assertNull(e.getName(), WikiTitles.forEntry(e));
			}
		}
	}
}
