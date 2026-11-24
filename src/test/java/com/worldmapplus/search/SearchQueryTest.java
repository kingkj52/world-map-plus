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
		SearchQuery q = SearchQuery.parse("loc:nearby type:melee_weapon cost:500 sword");
		assertEquals(SearchQuery.NEARBY_TILES, q.getMaxDistance());
		assertEquals(Arrays.asList("melee_weapon"), q.getKinds());
		assertEquals(500, q.getMaxCost());
		assertEquals(-1, q.getMinCost());
		assertEquals(Arrays.asList("sword"), q.getWords());
	}

	@Test
	public void costsCanBeBoundedBothWays()
	{
		assertEquals(499, SearchQuery.parse("cost:<500").getMaxCost());
		assertEquals(500, SearchQuery.parse("cost:<=500").getMaxCost());
		assertEquals(101, SearchQuery.parse("cost:>100").getMinCost());
		assertEquals(100, SearchQuery.parse("cost:>=100").getMinCost());
		SearchQuery range = SearchQuery.parse("price:100-500");
		assertEquals(100, range.getMinCost());
		assertEquals(500, range.getMaxCost());
		assertEquals(10_000, SearchQuery.parse("cost:10k").getMaxCost());
		assertEquals(2_500_000, SearchQuery.parse("cost:2.5m").getMaxCost());
		assertEquals(1_200, SearchQuery.parse("cost:1,200").getMaxCost());
	}

	@Test
	public void aFilterThatCannotBeReadIsFlaggedNotIgnored()
	{
		assertTrue(SearchQuery.parse("cost:cheap").hasBadFilter());
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
		for (String text : new String[] {"cost:NaN", "cost:Infinity", "cost:1e3", "cost:>Infinity", "dist:-1", "cost:99999999999999"})
		{
			SearchQuery query = SearchQuery.parse(text);
			assertTrue(text + " is a bad filter", query.hasBadFilter());
		}
		assertFalse(SearchQuery.parse("cost:2.5k").hasBadFilter());
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
		assertTrue("not every filter can be turned round", SearchQuery.parse("-cost:5").hasBadFilter());
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
		assertEquals(SearchQuery.Sort.COST, SearchQuery.parse("sword sort:cost").getSort());
		assertEquals(SearchQuery.Sort.DISTANCE, SearchQuery.parse("sort:nearest").getSort());
		assertEquals(SearchQuery.Sort.NAME, SearchQuery.parse("sort:alpha").getSort());
		assertEquals(SearchQuery.Sort.RELEVANCE, SearchQuery.parse("sword").getSort());
		assertTrue(SearchQuery.parse("sort:sideways").hasBadFilter());
		assertTrue("sorting alone counts as a filter", SearchQuery.parse("sort:cost sword").hasFilters());
	}

	@Test
	public void nothingCostsLessThanNothing()
	{
		assertTrue(SearchQuery.parse("cost:<0").hasBadFilter());
		assertTrue(SearchQuery.parse("cost:<1").getMaxCost() == 0);
	}

	@Test
	public void freeAndPlacesCanBeAskedFor()
	{
		SearchQuery free = SearchQuery.parse("free:yes");
		assertEquals(0, free.getMinCost());
		assertEquals(0, free.getMaxCost());
		assertTrue(SearchQuery.parse("free:maybe").hasBadFilter());
		assertTrue(SearchQuery.parse("type:place").asksForPlaces());
		assertTrue(SearchQuery.parse("type:shop,places").asksForPlaces());
		assertFalse(SearchQuery.parse("type:shop").asksForPlaces());
	}
}
