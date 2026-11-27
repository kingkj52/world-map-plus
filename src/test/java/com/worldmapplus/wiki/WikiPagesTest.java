package com.worldmapplus.wiki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.data.MapElements;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.IconExtra;
import com.worldmapplus.search.BundledSearch;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The wiki pages the bundled data names. Since round 69 tools/build_search.py and tools/build_cards.py work them out (the rules, once
 * wiki/WikiTitles.java, are tested in tools/test_build_search.py): only things whose names come from wiki pages get one, and every
 * one makes a clean address.
 */
public class WikiPagesTest
{
	private static List<SearchEntry> entries;

	@BeforeClass
	public static void read()
	{
		entries = BundledSearch.entries();
	}

	private static void clean(String title)
	{
		assertNotNull(title, WikiLinks.url(title));
		assertTrue(title, WikiLinks.url(title).substring(WikiLinks.PREFIX.length()).matches("([A-Za-z0-9_.~'(),!:/-]|%[0-9A-F]{2})+"));
		assertFalse(title, ("/" + title + "/").contains("/../") || ("/" + title + "/").contains("/./") || title.startsWith("/"));
		assertEquals("nothing has been changed but the end", title.trim(), title);
	}

	@Test
	public void everyTitleTheBundledSearchHasMakesACleanAddress()
	{
		Map<SearchKind, int[]> counts = new EnumMap<>(SearchKind.class);
		for (SearchEntry e : entries)
		{
			int[] count = counts.computeIfAbsent(e.getKind(), k -> new int[2]);
			count[0]++;
			if (e.getWiki() != null)
			{
				count[1]++;
				clean(e.getWiki());
			}
		}
		assertTrue(counts.get(SearchKind.QUEST)[1] > 100);
		assertTrue(counts.get(SearchKind.SHOP)[1] > 300);
		assertTrue(counts.get(SearchKind.MONSTER)[1] > 300);
		assertEquals("no entrance has one", 0, counts.get(SearchKind.DOORWAY)[1]);
		assertEquals("no kind has one", 0, counts.get(SearchKind.KIND)[1]);
	}

	@Test
	public void noMapNameIsGivenATitleUnlessTheGameMapPrintsIt()
	{
		for (SearchEntry e : entries)
		{
			if (e.getKind() == SearchKind.PLACE && "Map".equals(e.getDetail()))
			{
				assertNull(e.getName(), e.getWiki());
			}
		}
	}

	@Test
	public void thePointsMergedIntoIconsThatHaveAPageMakeACleanAddress()
	{
		int pages = 0;
		for (GameIconSet.Spec icon : MapElements.load(new com.google.gson.Gson()).getGameIcons())
		{
			for (IconExtra extra : icon.getExtras() == null ? java.util.Collections.<IconExtra>emptyList() : icon.getExtras())
			{
				if (extra.getWiki() != null)
				{
					clean(extra.getWiki());
					pages++;
				}
			}
		}
		assertTrue("pages " + pages, pages > 10);
	}
}
