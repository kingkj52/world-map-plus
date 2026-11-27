package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Searches the bundled entries made from the maps and the cards (entrances, transports, teleports, shops, their keepers and wares), so
 * a regression in any of those datasets shows up as a failed lookup.
 */
public class CardEntriesTest
{
	private static SearchIndex index;

	@BeforeClass
	public static void build()
	{
		List<SearchEntry> cards = new ArrayList<>();
		for (SearchEntry e : BundledSearch.entries())
		{
			SearchKind k = e.getKind();
			if (k == SearchKind.PLACE && e.getDetail().equals("Map") || k == SearchKind.DOORWAY || k == SearchKind.TRANSPORT
				|| k == SearchKind.SHOP || k == SearchKind.TELEPORT || k == SearchKind.NPC && e.getDetail().startsWith("Runs ")
				|| k == SearchKind.ITEM && e.getDetail().startsWith("Sold at "))
			{
				cards.add(e);
			}
		}
		index = new SearchIndex(cards);
	}

	private static SearchEntry first(String query)
	{
		List<SearchEntry> found = index.search(query, 5);
		assertFalse("nothing found for " + query, found.isEmpty());
		return found.get(0);
	}

	@Test
	public void findsAWayToTeleportByName()
	{
		boolean found = false;
		for (SearchEntry e : index.search("varrock teleport", 20))
		{
			found |= e.getKind() == SearchKind.TELEPORT && e.getName().equals("Varrock Teleport");
		}
		assertTrue(found);
	}

	@Test
	public void findsAMinigameTeleport()
	{
		boolean found = false;
		for (SearchEntry e : index.search("castle wars minigame", 20))
		{
			found |= e.getKind() == SearchKind.TELEPORT;
		}
		assertTrue(found);
	}

	@Test
	public void findsAKourendMinecartStation()
	{
		boolean found = false;
		for (SearchEntry e : index.search("kourend minecart", 20))
		{
			found |= e.getKind() == SearchKind.TRANSPORT && e.getName().startsWith("Kourend minecart");
		}
		assertTrue(found);
	}

	@Test
	public void findsAWildernessLever()
	{
		boolean found = false;
		for (SearchEntry e : index.search("lever", 30))
		{
			found |= e.getKind() == SearchKind.TRANSPORT && e.getName().startsWith("Lever");
		}
		assertTrue(found);
	}

	@Test
	public void findsMapsByName()
	{
		SearchEntry dwarven = first("dwarven mines");
		assertEquals(SearchKind.PLACE, dwarven.getKind());
	}

	@Test
	public void findsAShopAndItsKeeper()
	{
		boolean shop = false;
		boolean keeper = false;
		for (SearchEntry e : index.search("bob", 20))
		{
			shop |= e.getKind() == SearchKind.SHOP && e.getName().contains("Bob's Brilliant Axes");
			keeper |= e.getKind() == SearchKind.NPC && e.getName().equals("Bob");
		}
		assertTrue(shop);
		assertTrue(keeper);
	}

	@Test
	public void findsAnItemAndSaysWhereItIsSold()
	{
		SearchEntry axe = first("bronze pickaxe");
		assertEquals(SearchKind.ITEM, axe.getKind());
		assertTrue(axe.getDetail().startsWith("Sold at "));
	}

	@Test
	public void findsTransports()
	{
		boolean found = false;
		for (SearchEntry e : index.search("charter ship", 20))
		{
			found |= e.getKind() == SearchKind.TRANSPORT;
		}
		assertTrue(found);
	}

	@Test
	public void findsDungeonEntrances()
	{
		boolean found = false;
		for (SearchEntry e : index.search("taverley dungeon", 20))
		{
			found |= e.getKind() == SearchKind.DOORWAY;
		}
		assertTrue(found);
	}

	@Test
	public void theIndexIsBigEnoughToBeUseful()
	{
		assertTrue("entries: " + index.size(), index.size() > 3000);
	}

	@Test
	public void searchingIsFast()
	{
		long start = System.nanoTime();
		for (int i = 0; i < 50; i++)
		{
			index.search("a", 10);
		}
		long millis = (System.nanoTime() - start) / 1_000_000;
		assertTrue("50 searches took " + millis + " ms", millis < 2000);
	}
}
