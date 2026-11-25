package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Results other plugins give are listed after this plugin's own, and never push them out. */
public class SearchBoxExtraTest
{
	private static SearchEntry place(String name)
	{
		return new SearchEntry(SearchKind.PLACE, name, "", 0, 3200, 3200);
	}

	private static SearchEntry theirs(String name)
	{
		return new SearchEntry(SearchKind.PARTNER, name, "", 0, 3300, 3300);
	}

	private static SearchBox box(List<SearchEntry> ours)
	{
		SearchBox box = new SearchBox();
		box.setIndex(new SearchIndex(ours));
		box.focus();
		return box;
	}

	@Test
	public void theirResultsFollowOurOwn()
	{
		SearchBox box = box(Arrays.asList(place("Varrock bank"), place("Varrock square")));
		box.setExtra((q, where) -> Collections.singletonList(theirs("Varrock plugin spot")));
		for (char c : "varrock".toCharArray())
		{
			box.type(c);
		}
		List<SearchEntry> results = box.getResults();
		assertEquals(3, results.size());
		assertEquals(SearchKind.PARTNER, results.get(2).getKind());
	}

	@Test
	public void aResultThatScoresHigherThanOurOwnIsListedAmongThemWithFiltersTooUnlessTheOrderIsNotByScore()
	{
		SearchEntry kind = new SearchEntry(SearchKind.KIND, "Banks", "Every bank", 0, 3300, 3300);
		kind.setAliases(Arrays.asList("Bank", "every bank"));
		SearchBox box = box(Arrays.asList(place("Bank of Gielinor"), place("Bank Alley Lane"), place("The old bank")));
		box.setExtra((q, where) -> Collections.singletonList(kind));
		for (char c : "bank".toCharArray())
		{
			box.type(c);
		}
		assertEquals("the exact name it gives puts it first", "Banks", box.getResults().get(0).getName());
		assertEquals(4, box.getResults().size());
		SearchBox filtered = box(Arrays.asList(place("Bank of Gielinor")));
		filtered.setExtra((q, where) -> Collections.singletonList(kind));
		for (char c : "bank type:place".toCharArray())
		{
			filtered.type(c);
		}
		assertEquals("a text with a filter is ranked by the plain words as well", "Banks", filtered.getResults().get(0).getName());
		SearchBox sorted = box(Arrays.asList(place("Bank of Gielinor")));
		sorted.setExtra((q, where) -> Collections.singletonList(kind));
		for (char c : "bank sort:name".toCharArray())
		{
			sorted.type(c);
		}
		assertEquals("another order than by score lists theirs last", "Banks", sorted.getResults().get(sorted.getResults().size() - 1).getName());
	}

	@Test
	public void withoutAProviderOrWithNoAnswerTheListIsExactlyAsBefore()
	{
		List<SearchEntry> entries = Arrays.asList(place("Varrock bank"));
		SearchBox plain = box(entries);
		SearchBox extra = box(entries);
		extra.setExtra((q, where) -> Collections.emptyList());
		for (char c : "varrock".toCharArray())
		{
			plain.type(c);
			extra.type(c);
		}
		assertEquals(plain.getResults().size(), extra.getResults().size());
		assertEquals(plain.getResults().get(0), extra.getResults().get(0));
	}

	@Test
	public void theCapKeepsRoomForTheirResultsAndAnAnswerThatArrivesLaterIsAddedByARefresh()
	{
		List<SearchEntry> many = new ArrayList<>();
		for (int i = 0; i < SearchBox.MAX_RESULTS + 20; i++)
		{
			many.add(place("Bank " + i));
		}
		SearchBox box = box(many);
		List<SearchEntry> answer = new ArrayList<>();
		box.setExtra((q, where) -> answer);
		for (char c : "bank".toCharArray())
		{
			box.type(c);
		}
		assertEquals(SearchBox.MAX_RESULTS, box.getResults().size());
		answer.add(theirs("Bank plugin"));
		answer.add(theirs("Bank plugin two"));
		box.refreshExtras();
		assertEquals(SearchBox.MAX_RESULTS, box.getResults().size());
		assertTrue(box.getResults().get(SearchBox.MAX_RESULTS - 1).getKind() == SearchKind.PARTNER);
		assertEquals(SearchKind.PARTNER, box.getResults().get(SearchBox.MAX_RESULTS - 2).getKind());
	}
}
