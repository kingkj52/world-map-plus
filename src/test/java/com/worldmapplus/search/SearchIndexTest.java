package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SearchIndexTest
{
	private static SearchEntry entry(SearchKind kind, String name, String detail, double x, double y)
	{
		return new SearchEntry(kind, name, detail, 0, x, y);
	}

	private static SearchIndex index(SearchEntry... entries)
	{
		List<SearchEntry> list = new ArrayList<>();
		for (SearchEntry e : entries)
		{
			list.add(e);
		}
		return new SearchIndex(list);
	}

	@Test
	public void normalizingFoldsCaseAndPunctuation()
	{
		assertEquals("bobs brilliant axes", SearchEntry.normalize("Bob's Brilliant Axes."));
		assertEquals("bobs brilliant axes", SearchEntry.normalize("Bob’s Brilliant Axes."));
		assertEquals("a b", SearchEntry.normalize("  A---B  "));
		assertEquals("", SearchEntry.normalize(null));
		assertEquals("", SearchEntry.normalize("!!!"));
	}

	@Test
	public void anExactNameBeatsAPrefix()
	{
		SearchIndex index = index(
			entry(SearchKind.SHOP, "Lumbridge General Store", null, 3211, 3247),
			entry(SearchKind.PLACE, "Lumbridge", null, 3222, 3218));
		List<SearchEntry> found = index.search("lumbridge", 5);
		assertEquals("Lumbridge", found.get(0).getName());
		assertEquals(2, found.size());
	}

	@Test
	public void everyWordMustMatch()
	{
		SearchIndex index = index(
			entry(SearchKind.SHOP, "Lumbridge General Store", null, 1, 1),
			entry(SearchKind.SHOP, "Falador General Store", null, 2, 2));
		assertEquals(1, index.search("general lumb", 5).size());
		assertEquals(0, index.search("general zzz", 5).size());
	}

	@Test
	public void aWordStartBeatsALetterRunInsideAWord()
	{
		SearchIndex index = index(
			entry(SearchKind.ITEM, "Sword", null, 1, 1),
			entry(SearchKind.ITEM, "Wordsmith", null, 2, 2));
		assertEquals("Wordsmith", index.search("word", 5).get(0).getName());
	}

	@Test
	public void theDetailIsSearchedButCountsLess()
	{
		SearchEntry byName = entry(SearchKind.ITEM, "Bronze axe", "Sold at a shop", 1, 1);
		SearchEntry byDetail = entry(SearchKind.ITEM, "Chisel", "Sold at Bronze Shop", 2, 2);
		SearchIndex index = index(byDetail, byName);
		List<SearchEntry> found = index.search("bronze", 5);
		assertEquals(2, found.size());
		assertEquals("Bronze axe", found.get(0).getName());
	}

	@Test
	public void aPlaceOutranksAnItemWithTheSameName()
	{
		SearchIndex index = index(
			entry(SearchKind.ITEM, "Karamja", null, 1, 1),
			entry(SearchKind.PLACE, "Karamja", null, 500, 500));
		assertEquals(SearchKind.PLACE, index.search("karamja", 5).get(0).getKind());
	}

	@Test
	public void duplicatesAtTheSameSpotAreCollapsed()
	{
		SearchIndex index = index(
			entry(SearchKind.SHOP, "Aubury's Rune Shop", null, 3253, 3401),
			entry(SearchKind.SHOP, "Aubury's Rune Shop", null, 3254, 3402));
		assertEquals(1, index.size());
	}

	@Test
	public void theLimitAndEmptyQueriesAreRespected()
	{
		List<SearchEntry> list = new ArrayList<>();
		for (int i = 0; i < 50; i++)
		{
			list.add(entry(SearchKind.ITEM, "Axe " + i, null, i * 20, 0));
		}
		SearchIndex index = new SearchIndex(list);
		assertEquals(7, index.search("axe", 7).size());
		assertTrue(index.search("   ", 7).isEmpty());
		assertTrue(index.search("axe", 0).isEmpty());
	}

	@Test
	public void aWordScoresWholeThenStartThenInsideThenOneLetterOff()
	{
		assertEquals(SearchIndex.WHOLE, SearchIndex.match("lumbridge castle", "castle"));
		assertEquals(SearchIndex.WORD_START, SearchIndex.match("lumbridge castle", "cast"));
		assertEquals(SearchIndex.INSIDE, SearchIndex.match("lumbridge castle", "bri"));
		// Inside a word only from three letters on.
		assertEquals(SearchIndex.NONE, SearchIndex.match("lumbridge castle", "br"));
		assertEquals(SearchIndex.NEAR, SearchIndex.match("lumbridge castle", "lumbrige"));
		assertEquals(SearchIndex.NEAR, SearchIndex.match("shark", "shrak"));
		assertEquals(SearchIndex.NEAR, SearchIndex.match("catherby bay", "catherbu"));
		assertEquals(SearchIndex.NEAR, SearchIndex.match("varrock", "varock"));
		assertEquals(SearchIndex.NEAR, SearchIndex.match("varrock", "varrrock"));
		// One letter off only from five letters on, and never two letters off.
		assertEquals(SearchIndex.NONE, SearchIndex.match("bank", "bnak"));
		assertEquals(SearchIndex.NONE, SearchIndex.match("varrock", "vorrack"));
		assertEquals(SearchIndex.NONE, SearchIndex.match("", "anything"));
	}

	@Test
	public void theOneLetterCheckIsRightAtTheEdges()
	{
		assertTrue(SearchIndex.withinOneEdit("abcde", "abcde", 0, 5));
		assertTrue(SearchIndex.withinOneEdit("abcde", "abcdx", 0, 5));
		assertTrue(SearchIndex.withinOneEdit("abcde", "xbcde", 0, 5));
		assertTrue(SearchIndex.withinOneEdit("abcde", "abdce", 0, 5));
		assertTrue(SearchIndex.withinOneEdit("abcde", "abcd", 0, 4));
		assertTrue(SearchIndex.withinOneEdit("abcde", "abcdef", 0, 6));
		assertTrue(SearchIndex.withinOneEdit("aa", "aaa", 0, 3));
		assertTrue(SearchIndex.withinOneEdit("abcde", "zzabcdezz", 2, 7));
		assertTrue(!SearchIndex.withinOneEdit("abcde", "abxye", 0, 5));
		assertTrue(!SearchIndex.withinOneEdit("abcde", "abc", 0, 3));
		assertTrue(!SearchIndex.withinOneEdit("abcde", "bacdx", 0, 5));
	}

	@Test
	public void theOneLetterCheckAllocatesNothing()
	{
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		String text = "lumbridge castle courtyard and the general store of lumbridge";
		int sink = 0;
		for (int i = 0; i < 20_000; i++)
		{
			sink += SearchIndex.match(text, "lumbrige") + SearchIndex.match(text, "genarel") + SearchIndex.match(text, "zzzzzz");
		}
		long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
		for (int i = 0; i < 100_000; i++)
		{
			sink += SearchIndex.match(text, "lumbrige") + SearchIndex.match(text, "genarel") + SearchIndex.match(text, "zzzzzz");
		}
		long used = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
		assertTrue("sink " + sink, sink > 0);
		assertTrue("allocated " + used + " bytes", used < 4096);
	}

	@Test
	public void aTypoStillFindsTheNameButAnExactSpellingRanksFirst()
	{
		SearchIndex index = index(
			entry(SearchKind.PLACE, "Varrock", null, 1, 1),
			entry(SearchKind.ITEM, "Varock scroll", null, 2, 2));
		List<SearchEntry> found = index.search("varrock", 5);
		assertEquals(2, found.size());
		assertEquals("Varrock", found.get(0).getName());
		assertEquals("Varrock", index.search("varock", 5).get(1).getName());
		assertEquals("Varrock", index.search("varrok", 5).get(0).getName());
		assertTrue(index.search("varr0ck zzzzz", 5).isEmpty());
	}

	@Test
	public void wordsMayComeInAnyOrder()
	{
		SearchIndex index = index(
			entry(SearchKind.PLACE, "Lumbridge Castle", null, 1, 1),
			entry(SearchKind.PLACE, "Castle Wars", null, 2, 2),
			entry(SearchKind.SHOP, "Lumbridge General Store", null, 3, 3));
		assertEquals("Lumbridge Castle", index.search("castle lumbridge", 5).get(0).getName());
		assertEquals("Lumbridge General Store", index.search("store general lumbridge", 5).get(0).getName());
		assertEquals(index.search("lumbridge castle", 5).get(0).getName(), index.search("castle lumbridge", 5).get(0).getName());
	}

	@Test
	public void fewerWordsOfItsOwnComeFirstAndTiesKeepTheirOrder()
	{
		SearchIndex index = index(
			entry(SearchKind.PLACE, "Catherby Bay", null, 1, 1),
			entry(SearchKind.PLACE, "Catherby", null, 2, 2),
			entry(SearchKind.PLACE, "Catherby Beach Path", null, 3, 3));
		List<SearchEntry> found = index.search("cath", 5);
		assertEquals("Catherby", found.get(0).getName());
		assertEquals("Catherby Bay", found.get(1).getName());
		assertEquals("Catherby Beach Path", found.get(2).getName());
		assertEquals(0, SearchIndex.ownWords("catherby", new String[] {"cath"}));
		assertEquals(2, SearchIndex.ownWords("catherby beach path", new String[] {"cath"}));
		// The same search twice gives the same order.
		assertEquals(found, index.search("cath", 5));
	}

	@Test
	public void shorterNamesWinTies()
	{
		SearchIndex index = index(
			entry(SearchKind.ITEM, "Iron axe (broken)", null, 1, 1),
			entry(SearchKind.ITEM, "Iron axe", null, 100, 100));
		assertEquals("Iron axe", index.search("iron", 5).get(0).getName());
	}
}
