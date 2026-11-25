package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class SearchQueryTest
{
	@Test
	public void plainWordsAreJustWords()
	{
		SearchQuery q = SearchQuery.parse("iron arrow");
		assertEquals(Arrays.asList("iron", "arrow"), q.getWords());
		assertFalse(q.hasFilters());
		assertFalse(q.isEmpty());
	}

	@Test
	public void emptyOrNullIsEmpty()
	{
		assertTrue(SearchQuery.parse(null).isEmpty());
		assertTrue(SearchQuery.parse("   ").isEmpty());
	}

	@Test
	public void aQuotedValueKeepsItsSpaces()
	{
		SearchQuery q = SearchQuery.parse("region:varrock name:\"iron arrow\"");
		assertEquals(Arrays.asList("varrock"), q.getRegions());
		assertEquals(Arrays.asList("iron", "arrow"), q.getNameWords());
		assertTrue(q.getWords().isEmpty());
		assertTrue(q.hasFilters());
	}

	@Test
	public void theWholeExampleFromTheRequestIsUnderstood()
	{
		SearchQuery q = SearchQuery.parse("loc:nearby type:melee_weapon sword");
		assertEquals(SearchQuery.NEARBY_TILES, q.getMaxDistance());
		assertEquals(Arrays.asList("melee_weapon"), q.getKinds());
		assertEquals(Arrays.asList("sword"), q.getWords());
	}

	@Test
	public void aFilterThatCannotBeReadIsFlaggedNotIgnored()
	{
		assertTrue(SearchQuery.parse("sort:cheap").hasBadFilter());
		assertTrue(SearchQuery.parse("dist:far").hasBadFilter());
		assertTrue(SearchQuery.parse("loc:mars").hasBadFilter());
	}

	@Test
	public void aWordWithAColonThatIsNotAFilterStaysAWord()
	{
		SearchQuery q = SearchQuery.parse("12:30 bob's: quest:start");
		assertFalse(q.hasFilters());
		assertEquals(Arrays.asList("12", "30", "bobs", "quest", "start"), q.getWords());
	}

	@Test
	public void aKeyWithNothingAfterItIsAWord()
	{
		SearchQuery q = SearchQuery.parse("region:");
		assertFalse(q.hasFilters());
		assertEquals(Arrays.asList("region"), q.getWords());
	}

	@Test
	public void keysAreNotCaseSensitive()
	{
		SearchQuery q = SearchQuery.parse("Region:Varrock TYPE:Shop");
		assertEquals(Arrays.asList("varrock"), q.getRegions());
		assertEquals(Arrays.asList("shop"), q.getKinds());
	}

	@Test
	public void nearAWordIsARegionAndNearMeIsTheNeighbourhood()
	{
		assertEquals(Arrays.asList("falador"), SearchQuery.parse("near:falador").getRegions());
		assertEquals(SearchQuery.NEARBY_TILES, SearchQuery.parse("near:me").getMaxDistance());
		assertEquals(25, SearchQuery.parse("dist:25").getMaxDistance());
		assertEquals(25, SearchQuery.parse("dist:25 loc:nearby").getMaxDistance());
	}

	@Test
	public void aKindWithSpacesOrDashesBecomesUnderscored()
	{
		assertEquals(Arrays.asList("melee_weapon"), SearchQuery.parse("type:\"melee weapon\"").getKinds());
		assertEquals(Arrays.asList("melee_weapon"), SearchQuery.parse("kind:melee-weapon").getKinds());
	}

	@Test
	public void numbersAreDigitsOnlyAndDistancesAreNotNegative()
	{
		for (String text : new String[] {"dist:-1", "dist:NaN", "dist:1e3"})
		{
			SearchQuery query = SearchQuery.parse(text);
			assertTrue(text + " is a bad filter", query.hasBadFilter());
		}
		assertFalse(SearchQuery.parse("dist:0").hasBadFilter());
	}

	@Test
	public void anAreaCanBeWrittenInTheWaysPeopleWillTry()
	{
		for (String text : new String[] {"region:\"kebos lowlands\"", "region:kebos_lowlands", "region:kebos-lowlands",
			"region:kebosLowlands", "area:\"Kebos Lowlands\"", "in:\"KEBOS  lowlands\"", "where:kebos_lowlands"})
		{
			assertEquals(text, Arrays.asList("kebos lowlands"), SearchQuery.parse(text).getRegions());
		}
	}

	@Test
	public void aDashKeepsThingsOut()
	{
		SearchQuery q = SearchQuery.parse("-region:varrock -type:shop -map:keldagrim sword");
		assertEquals(Arrays.asList("varrock"), q.getExcludedRegions());
		assertEquals(Arrays.asList("shop"), q.getExcludedKinds());
		assertEquals(Arrays.asList("keldagrim"), q.getExcludedMaps());
		assertTrue(q.getRegions().isEmpty());
		assertEquals(Arrays.asList("sword"), q.getWords());
		assertTrue("a dash in front of a plain word is just a word", SearchQuery.parse("-dragon").getWords().contains("dragon"));
		assertTrue("not every filter can be turned round", SearchQuery.parse("-dist:5").hasBadFilter());
	}

	@Test
	public void severalKindsWithACommaMeanAnyOfThem()
	{
		SearchQuery q = SearchQuery.parse("type:shop,npc type:melee_weapon");
		assertEquals(2, q.getKindGroups().size());
		assertEquals(Arrays.asList("shop", "npc"), q.getKindGroups().get(0));
		assertEquals(Arrays.asList("melee_weapon"), q.getKindGroups().get(1));
	}

	@Test
	public void sortCanBeAskedFor()
	{
		assertEquals(SearchQuery.Sort.DISTANCE, SearchQuery.parse("sort:nearest").getSort());
		assertEquals(SearchQuery.Sort.NAME, SearchQuery.parse("sort:alpha").getSort());
		assertEquals(SearchQuery.Sort.RELEVANCE, SearchQuery.parse("sword").getSort());
		assertTrue(SearchQuery.parse("sort:sideways").hasBadFilter());
		assertTrue("sorting alone counts as a filter", SearchQuery.parse("sort:name sword").hasFilters());
	}

	@Test
	public void placesCanBeAskedFor()
	{
		assertTrue(SearchQuery.parse("type:place").asksForPlaces());
		assertTrue(SearchQuery.parse("type:shop,places").asksForPlaces());
		assertFalse(SearchQuery.parse("type:shop").asksForPlaces());
	}
}
