package com.worldmapplus.ui;

import com.worldmapplus.drops.DropFile;
import com.worldmapplus.drops.DropFileTest;
import com.worldmapplus.drops.DropTables;
import com.worldmapplus.drops.PriceSource;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Drops and Dropped by tabs: what they hold, their orders, prices worked in late, and unknown prices. */
public class DropViewsTest
{
	private static final DropTables TABLES = DropFile.decode(DropFileTest.sample().packed());

	/** Prices for the sample's items: bones 100, bronze dagger 50, coins 1; the mystery item has none. */
	private static final class Prices implements PriceSource
	{
		final Map<Integer, Long> known = new HashMap<>();
		int version;
		final List<int[]> asked = new ArrayList<>();

		@Override
		public long price(int itemId)
		{
			return known.getOrDefault(itemId, -1L);
		}

		@Override
		public void request(int[] itemIds)
		{
			asked.add(itemIds);
		}

		@Override
		public int version()
		{
			return version;
		}

		void arrive()
		{
			known.put(526, 100L);
			known.put(1205, 50L);
			known.put(995, 1L);
			version++;
		}
	}

	private static List<SearchEntry> world()
	{
		List<SearchEntry> all = new ArrayList<>();
		all.add(new SearchEntry(SearchKind.MONSTER, "Goblin", "Lumbridge (4 spawns)", 0, 3200, 3200,
			Arrays.asList(new double[] {3200, 3200}, new double[] {3202, 3201})));
		all.add(new SearchEntry(SearchKind.MONSTER, "Goblin", "Goblin Village (2 spawns)", 0, 3300, 3300,
			Arrays.asList(new double[] {3300, 3300}, new double[] {3301, 3300})));
		return all;
	}

	private static ResultView.Tab tab(ResultView view, String name)
	{
		for (ResultView.Tab t : view.tabs)
		{
			if (t.name.equals(name))
			{
				return t;
			}
		}
		return null;
	}

	private static List<String> labels(ResultView.Tab tab)
	{
		List<String> out = new ArrayList<>();
		for (ResultView.Row row : tab.rows)
		{
			out.add(row.label);
		}
		return out;
	}

	private static void measure(ResultView view)
	{
		view.measureFrom(true, 0, 3290, 3290, id -> "In map " + id);
	}

	@Test
	public void aMonsterShowsItsLocationsAndItsDropsMostLikelyFirst()
	{
		List<SearchEntry> all = world();
		ResultView view = ResultViews.forEntry(all.get(0), null, all, TABLES);
		assertNotNull(view);
		assertEquals(Arrays.asList("Locations", "Drops"), Arrays.asList(view.tabs.get(0).name, view.tabs.get(1).name));
		assertEquals("Combat 2-5. Drops 3 items, found in 2 places", view.summary);
		measure(view);
		ResultView.Tab drops = tab(view, "Drops");
		assertEquals(Arrays.asList(ResultView.Sort.RARITY, ResultView.Sort.VALUE, ResultView.Sort.NAME), drops.sorts);
		assertEquals(Arrays.asList("Bones", "Bronze dagger", "Coins"), labels(drops));
		assertEquals("Always", drops.rows.get(0).count);
		assertEquals("Always, 1", drops.rows.get(0).detail);
		assertEquals("Rare, 5-15 (noted), 3 rolls", drops.rows.get(2).detail);
		assertEquals("1/128", drops.rows.get(1).count);
		// A drop row has no place of its own and opens the item.
		assertEquals(-1, drops.rows.get(0).mapId);
		assertEquals(SearchKind.ITEM, drops.rows.get(0).follow.getKind());
		assertEquals("", drops.rows.get(0).where);
	}

	@Test
	public void pricesAreWorkedInWhenTheyArriveAndTheValueOrderFollows()
	{
		List<SearchEntry> all = world();
		ResultView view = ResultViews.forEntry(all.get(0), null, all, TABLES);
		Prices prices = new Prices();
		assertTrue(view.refreshPrices(prices));
		assertFalse("the same version changes nothing", view.refreshPrices(prices));
		ResultView.Tab drops = tab(view, "Drops");
		for (ResultView.Row row : drops.rows)
		{
			assertEquals(-1, row.value, 0);
		}
		assertEquals("Combat 2-5. Drops 3 items, found in 2 places", view.summary);

		prices.arrive();
		assertTrue(view.refreshPrices(prices));
		measure(view);
		ResultView.sort(drops, ResultView.Sort.VALUE);
		assertEquals(Arrays.asList("Bones", "Bronze dagger", "Coins"), labels(drops));
		assertEquals(100, drops.rows.get(0).value, 1e-9);
		assertEquals(50 / 128.0, drops.rows.get(1).value, 1e-9);
		assertEquals(3 * 10 / 128.0, drops.rows.get(2).value, 1e-9);
		assertEquals("Always, 1, 100 each, 100 a kill", drops.rows.get(0).detail);
		assertEquals("Rare, 1, 50 each, 0.4 a kill", drops.rows.get(1).detail);
		assertTrue(drops.rows.get(0).below.endsWith("100 a kill"));
		assertEquals("Combat 2-5. Drops 3 items, found in 2 places, about 101 coins a kill", view.summary);

		ResultView.sort(drops, ResultView.Sort.NAME);
		assertEquals(Arrays.asList("Bones", "Bronze dagger", "Coins"), labels(drops));
	}

	@Test
	public void anUnknownPriceShowsNothingAndSortsLast()
	{
		DropTables tables = TABLES;
		SearchEntry man = new SearchEntry(SearchKind.MONSTER, "Man", "", -1, 0, 0);
		ResultView view = ResultViews.forEntry(man, null, new ArrayList<>(), tables);
		assertNotNull("a monster with drops and no place still has a view", view);
		assertEquals(1, view.tabs.size());
		Prices prices = new Prices();
		prices.arrive();
		view.refreshPrices(prices);
		ResultView.Tab drops = view.tabs.get(0);
		ResultView.sort(drops, ResultView.Sort.VALUE);
		assertEquals(Arrays.asList("Coins", "Mystery item"), labels(drops));
		ResultView.Row mystery = drops.rows.get(1);
		assertEquals(-1, mystery.value, 0);
		assertEquals(-1, mystery.price);
		assertEquals("~Rare", mystery.count);
		assertEquals("Rare", mystery.detail);
		assertTrue(drops.rows.get(0).detail.contains("pickpocket"));
		ResultView.sort(drops, ResultView.Sort.RARITY);
		assertEquals("a rarity in words is ordered by the usual figure for it (Rare as 1/256)", "Mystery item", drops.rows.get(0).label);
	}

	@Test
	public void anItemListsWhatDropsItWithTheNearestPlaceOfEach()
	{
		List<SearchEntry> all = world();
		ResultView view = ResultViews.forItem("Coins", all, TABLES);
		assertNotNull(view);
		assertEquals("Dropped by 2 monsters", view.summary);
		ResultView.Tab by = tab(view, "Dropped by");
		assertEquals(Arrays.asList(ResultView.Sort.RARITY, ResultView.Sort.NEAREST, ResultView.Sort.NAME), by.sorts);
		measure(view);
		assertEquals(Arrays.asList("Goblin (Drop table 1)", "Man"), labels(by));
		ResultView.Row goblin = by.rows.get(0);
		assertEquals(0, goblin.mapId);
		assertEquals("the nearest goblin to the player", 3300, goblin.x, 0);
		assertEquals("Combat 2-5, 5-15 (noted), 3 rolls", goblin.detail);
		assertTrue(goblin.where.endsWith("north-east") || goblin.where.contains("tiles"));
		ResultView.Row man = by.rows.get(1);
		assertEquals("No known place", man.where);
		assertEquals(SearchKind.MONSTER, man.follow.getKind());
		assertEquals("Man", man.follow.getName());

		Prices prices = new Prices();
		prices.arrive();
		view.refreshPrices(prices);
		assertEquals("Combat 2-5, 5-15 (noted), 3 rolls, 0.2 a kill", goblin.detail);
		assertEquals("one item's price is not repeated on every row", -1, goblin.detail.indexOf("each"));
		assertEquals("Dropped by 2 monsters", view.summary);
	}

	@Test
	public void withoutTheTablesEverythingIsAsBefore()
	{
		List<SearchEntry> all = world();
		assertEquals(1, ResultViews.forEntry(all.get(0), null, all, null).tabs.size());
		List<SearchEntry> one = new ArrayList<>(all.subList(0, 1));
		assertNull("one place and no tables: no panel", ResultViews.forEntry(one.get(0), null, one, null));
		assertNotNull("one place and drops: a panel", ResultViews.forEntry(one.get(0), null, one, TABLES));
		assertNull("an item nothing drops, sold nowhere", ResultViews.forItem("Rune scimitar", all, TABLES));
		assertTrue(ResultViews.canHaveDrops(all.get(0)));
		assertFalse(ResultViews.canHaveDrops(new SearchEntry(SearchKind.SHOP, "Shop", "", 0, 0, 0)));
	}

	@Test
	public void theRealTablesGiveRealTabs()
	{
		DropTables real = DropFile.load();
		List<SearchEntry> all = world();
		ResultView goblin = ResultViews.forEntry(all.get(0), null, all, real);
		ResultView.Tab drops = tab(goblin, "Drops");
		assertNotNull(drops);
		assertTrue(drops.rows.size() > 10);
		assertTrue("goblins drop bones", labels(drops).contains("Bones"));
		ResultView whip = ResultViews.forItem("Abyssal whip", all, real);
		assertNotNull(whip);
		assertTrue(labels(tab(whip, "Dropped by")).stream().anyMatch(l -> l.startsWith("Abyssal demon")));
		assertTrue(drops.rows.size() <= ResultViews.MAX_ROWS);
	}

	@Test
	public void coinsAreWrittenShort()
	{
		assertEquals("0.4", DropViews.coins(0.39));
		assertEquals("12", DropViews.coins(12.4));
		assertEquals("1,250", DropViews.coins(1250));
		assertEquals("125.0k", DropViews.coins(125_000));
		assertEquals("12.3M", DropViews.coins(12_300_000));
	}
}
