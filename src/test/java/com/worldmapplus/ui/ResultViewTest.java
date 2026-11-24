package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.search.KindIndex;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The rows the results panel shows, their counts, distances in words and orders, made from plain entries. */
public class ResultViewTest
{
	private static SearchEntry sold(String item, String shop, String terms, double x)
	{
		return new SearchEntry(SearchKind.ITEM, item, "Sold at " + shop + " for " + terms, 0, x, 100.5);
	}

	@Test
	public void distanceAndDirectionInWords()
	{
		assertEquals("here", ResultView.words(2, 3));
		assertEquals("40 tiles north", ResultView.words(0, 40));
		assertEquals("515 tiles west", ResultView.words(-515, 0));
		assertEquals("1.6k tiles north-east", ResultView.words(1131, 1131));
		assertEquals("100 tiles south", ResultView.words(0, -100));
		assertEquals("71 tiles south-west", ResultView.words(-50, -50));
	}

	@Test
	public void anItemsShopsSortByNearestStockNameAndPrice()
	{
		List<SearchEntry> all = new ArrayList<>();
		all.add(sold("Bronze axe", "Far Shop", "16 coins, 10 in stock", 900.5));
		all.add(sold("Bronze axe", "Near Shop", "20 coins, 2 in stock", 110.5));
		all.add(sold("Bronze axe", "Another Shop", "12 coins, 5 in stock", 400.5));
		all.add(new SearchEntry(SearchKind.SHOP, "Near Shop", "Run by Bob", 0, 110.5, 100.5));
		all.add(sold("Iron axe", "Near Shop", "50 coins", 110.5));
		ResultView view = ResultViews.forItem("Bronze axe", all);
		assertNotNull(view);
		assertEquals("Sold in 3 shops", view.summary);
		ResultView.Tab shops = view.current();
		assertEquals("Shops", shops.name);
		assertEquals("Shops 3", shops.label);
		view.measureFrom(true, 0, 100.5, 100.5, id -> "elsewhere");
		assertEquals("Near Shop", shops.rows.get(0).label);
		assertEquals("10 tiles east", shops.rows.get(0).where);
		assertEquals("Far Shop", shops.rows.get(2).label);
		ResultView.sort(shops, ResultView.Sort.STOCK);
		assertEquals("Far Shop", shops.rows.get(0).label);
		assertEquals(10, shops.rows.get(0).stock);
		ResultView.sort(shops, ResultView.Sort.NAME);
		assertEquals("Another Shop", shops.rows.get(0).label);
		ResultView.sort(shops, ResultView.Sort.PRICE);
		assertEquals(12, shops.rows.get(0).price);
		// The near shop's row opens what that shop sells.
		ResultView.sort(shops, ResultView.Sort.NEAREST);
		assertEquals(SearchKind.SHOP, shops.rows.get(0).follow.getKind());
		ResultView stock = ResultViews.forShop(shops.rows.get(0).follow, all);
		assertEquals(2, stock.current().rows.size());
		assertEquals("Bronze axe", stock.current().rows.get(0).label);
		assertEquals(SearchKind.ITEM, stock.current().rows.get(0).follow.getKind());
	}

	@Test
	public void aRowOnAnotherMapSaysTheMapAndSortsLast()
	{
		List<SearchEntry> all = new ArrayList<>();
		all.add(new SearchEntry(SearchKind.MONSTER, "Goblin", "Lumbridge (3 spawns)", 0, 120.5, 100.5,
			Arrays.asList(new double[] {120.5, 100.5}, new double[] {121.5, 100.5}, new double[] {122.5, 100.5})));
		all.add(new SearchEntry(SearchKind.MONSTER, "Goblin", "Dungeon (1 spawn)", 7, 50.5, 50.5,
			Collections.singletonList(new double[] {50.5, 50.5})));
		ResultView view = ResultViews.forCreature(all.get(0), all);
		view.measureFrom(true, 7, 50.5, 50.5, id -> "In map " + id);
		ResultView.Tab tab = view.current();
		assertEquals("Dungeon", tab.rows.get(0).label);
		assertEquals("1 here", tab.rows.get(0).count);
		assertEquals("Lumbridge", tab.rows.get(1).label);
		assertEquals("3 here", tab.rows.get(1).count);
		assertEquals("In map 0", tab.rows.get(1).where);
		assertEquals("Found in 2 places, 4 in all, nearest first", view.summary);
	}

	@Test
	public void somethingWithOnePlaceHasNoPanel()
	{
		List<SearchEntry> all = Collections.singletonList(new SearchEntry(SearchKind.MONSTER, "Zulrah", "Zul-Andra", 0, 1, 1));
		assertNull(ResultViews.forCreature(all.get(0), all));
		assertNull(ResultViews.forItem("Nothing", all));
		assertNull(ResultViews.forEntry(new SearchEntry(SearchKind.PLACE, "Lumbridge", "", 0, 1, 1), KindIndex.empty(), all));
	}

	@Test
	public void priceAndStockAreReadFromTheShopWords()
	{
		assertEquals(1200, ResultViews.priceOf("1,200 coins, 10 in stock"));
		assertEquals(10, ResultViews.stockOf("1,200 coins, 10 in stock"));
		assertEquals(-1, ResultViews.stockOf("16 tokkul"));
		assertEquals(-1, ResultViews.priceOf("not sold"));
		assertTrue(ResultViews.MAX_ROWS >= 100);
	}
}
