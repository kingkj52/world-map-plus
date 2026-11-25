package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;

/** The search keeps only the best few matches, and gives the same first few as sorting every match would. */
public class SearchTopNTest
{
	private static SearchEntry place(String name, int i)
	{
		return new SearchEntry(SearchKind.PLACE, name, "detail", 0, 3000 + i * 16, 3000);
	}

	@Test
	public void entriesThatTieKeepTheOrderTheyHaveInTheIndex()
	{
		List<SearchEntry> entries = new ArrayList<>();
		for (int i = 0; i < 50; i++)
		{
			entries.add(place("Same name", i));
		}
		SearchIndex index = new SearchIndex(entries);
		for (int limit : new int[] {1, 7, 49, 50, 51, 500})
		{
			List<SearchEntry> found = index.search("same name", limit);
			assertEquals(Math.min(limit, 50), found.size());
			for (int i = 0; i < found.size(); i++)
			{
				assertEquals("entry " + i + " of " + limit, entries.get(i), found.get(i));
			}
		}
	}

	@Test
	public void aLimitGivesTheFirstOfTheFullList()
	{
		Random random = new Random(11);
		List<SearchEntry> entries = new ArrayList<>();
		String[] words = {"bank", "baker", "banker", "bar", "barbarian", "bank booth", "iron", "iron bar", "gold bar", "bakery"};
		for (int i = 0; i < 600; i++)
		{
			String name = words[random.nextInt(words.length)] + (random.nextBoolean() ? " " + words[random.nextInt(words.length)] : "");
			entries.add(new SearchEntry(SearchKind.values()[random.nextInt(4)], name, "d" + random.nextInt(3), 0,
				3000 + random.nextInt(2000), 3000 + random.nextInt(2000)));
		}
		SearchIndex index = new SearchIndex(entries);
		SearchContext context = new SearchContext(true, 0, 3500, 3500, Gazetteer.empty());
		String[] queries = {"b", "ba", "bank", "bar", "iron bar", "bar iron", "gold", "bakr", "a", "kind:place bank", "sort:name bar",
			"sort:distance ba", "near:300 bar"};
		for (String query : queries)
		{
			List<SearchEntry> all = index.search(query, Integer.MAX_VALUE, context);
			for (int limit : new int[] {1, 5, 40, 200})
			{
				List<SearchEntry> top = index.search(query, limit, context);
				assertEquals(query + " " + limit, all.subList(0, Math.min(limit, all.size())), top);
			}
		}
	}

	@Test
	public void theBundledIndexAnswersTheSameForALimitAndForEverything() throws Exception
	{
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		try
		{
			MapAssets assets = new MapAssets(new Gson(), executor);
			SearchIndex index = assets.getSearchIndex();
			SearchContext context = new SearchContext(true, 0, 3222.5, 3218.5, assets.getGazetteer());
			Random random = new Random(5);
			List<String> queries = new ArrayList<>();
			for (char c = 'a'; c <= 'z'; c++)
			{
				queries.add("" + c);
			}
			for (int i = 0; i < 120; i++)
			{
				queries.add("" + (char) ('a' + random.nextInt(26)) + (char) ('a' + random.nextInt(26)));
			}
			List<SearchEntry> entries = assets.getSearchEntries();
			for (int i = 0; i < 150; i++)
			{
				String[] words = entries.get(random.nextInt(entries.size())).getName().toLowerCase().split(" ");
				queries.add(words[random.nextInt(words.length)]);
			}
			queries.add("region:varrock shop");
			queries.add("kind:shop cost:<500");
			queries.add("sort:cost kind:item");
			queries.add("bank near:50");
			for (String query : queries)
			{
				List<SearchEntry> all = index.search(query, Integer.MAX_VALUE, context);
				List<SearchEntry> top = index.search(query, 200, context);
				assertEquals(query, all.subList(0, Math.min(200, all.size())), top);
				assertTrue(top.size() <= 200);
			}
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	@Test
	public void theWordsOfARegionAreFoundInANameAsBefore()
	{
		Random random = new Random(3);
		String[] pool = {"var", "rock", "lum", "bridge", "e", " ", "  ", "a", "castle", "x"};
		for (int i = 0; i < 3000; i++)
		{
			StringBuilder name = new StringBuilder();
			StringBuilder region = new StringBuilder();
			for (int k = random.nextInt(5); k >= 0; k--)
			{
				name.append(pool[random.nextInt(pool.length)]);
			}
			for (int k = random.nextInt(4); k >= 0; k--)
			{
				region.append(pool[random.nextInt(pool.length)]);
			}
			boolean expected = true;
			for (String word : region.toString().split(" "))
			{
				if (!word.isEmpty() && !name.toString().contains(word))
				{
					expected = false;
				}
			}
			assertEquals("[" + name + "] has [" + region + "]", expected, Gazetteer.allWords(name.toString(), region.toString()));
		}
	}

	@Test
	public void locationIsReadFromTheCategoryList()
	{
		assertTrue(entry("location").isLocation());
		assertTrue(entry("a,location").isLocation());
		assertTrue(entry("location,b").isLocation());
		assertTrue(entry("a,location,b").isLocation());
		assertFalse(entry("").isLocation());
		assertFalse(entry("locations").isLocation());
		assertFalse(entry("a,mylocation").isLocation());
		assertFalse(entry("a,,b").isLocation());
	}

	private static SearchEntry entry(String category)
	{
		return new SearchEntry(SearchKind.PLACE, "N", "d", 0, 1, 1, new ArrayList<>(), category);
	}
}
