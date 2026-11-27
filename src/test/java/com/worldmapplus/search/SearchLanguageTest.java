package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** The search filters, against the real bundled data. */
public class SearchLanguageTest
{
	private static ExecutorService executor;
	private static MapAssets assets;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newSingleThreadExecutor();
		assets = new MapAssets(new Gson(), new java.util.concurrent.ScheduledThreadPoolExecutor(1));
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	private static SearchContext atVarrock()
	{
		return new SearchContext(true, 0, 3212.5, 3428.5, assets.getGazetteer());
	}

	private static List<SearchEntry> search(String text, SearchContext context)
	{
		return assets.getSearchIndex().search(text, 200, context);
	}

	@Test
	public void theBarracudaTrialsCanBeSearchedFor()
	{
		for (String text : new String[] {"barracuda trials", "tempor tantrum", "jubbly jive", "gwenith glide"})
		{
			List<SearchEntry> found = search(text, atVarrock());
			boolean trial = false;
			for (SearchEntry e : found)
			{
				trial |= e.getName().contains("Barracuda Trials") && e.getMapId() == 0;
			}
			assertTrue("'" + text + "' finds a Barracuda Trial", trial);
		}
		int trials = 0;
		for (SearchEntry e : search("barracuda trials", atVarrock()))
		{
			trials += e.getName().contains("(Barracuda Trials)") ? 1 : 0;
		}
		assertEquals(3, trials);
	}

	@Test
	public void anItemCanBeFoundInATown()
	{
		List<SearchEntry> found = search("name:\"iron arrow\" region:varrock", atVarrock());
		assertFalse("no iron arrows in Varrock", found.isEmpty());
		for (SearchEntry e : found)
		{
			assertEquals(SearchKind.ITEM, e.getKind());
			assertTrue(e.getName(), e.getName().toLowerCase().contains("iron arrow"));
			assertTrue(e.getX() > 3100 && e.getX() < 3330 && e.getY() > 3330 && e.getY() < 3520);
		}
	}

	@Test
	public void regionFilterKeepsOtherTownsOut()
	{
		List<SearchEntry> varrock = search("type:shop region:varrock", atVarrock());
		List<SearchEntry> falador = search("type:shop region:falador", atVarrock());
		assertFalse(varrock.isEmpty());
		assertFalse(falador.isEmpty());
		// A shop's own text says where it is, and a few say two towns, so most and not all must agree.
		int wrong = 0;
		int surface = 0;
		for (SearchEntry e : varrock)
		{
			if (e.getMapId() == 0)
			{
				surface++;
				wrong += e.getX() > 3100 && e.getX() < 3330 ? 0 : 1;
			}
		}
		for (SearchEntry e : falador)
		{
			if (e.getMapId() == 0)
			{
				surface++;
				wrong += e.getX() < 3100 ? 0 : 1;
			}
		}
		assertTrue(wrong + " of " + surface + " are in the wrong town", wrong * 5 <= surface);
	}

	@Test
	public void nearbyAndASwordAndACost()
	{
		List<SearchEntry> found = search("loc:nearby type:melee_weapon cost:500 sword", atVarrock());
		assertFalse("no cheap sword near Varrock", found.isEmpty());
		double last = -1;
		for (SearchEntry e : found)
		{
			assertEquals(SearchKind.ITEM, e.getKind());
			assertTrue(e.getName(), (e.getName() + " " + e.getDetail()).toLowerCase().contains("sword"));
			assertTrue(e.getName() + " costs " + e.getPrice(), e.getPrice() >= 0 && e.getPrice() <= 500);
			double d = atVarrock().distanceTo(e);
			assertTrue(d >= 0 && d <= SearchQuery.NEARBY_TILES);
			last = d;
		}
		assertTrue(last >= 0);
	}

	@Test
	public void nearbyResultsComeNearestFirst()
	{
		List<SearchEntry> found = search("loc:nearby type:shop", atVarrock());
		assertTrue(found.size() > 3);
		double previous = -1;
		for (SearchEntry e : found)
		{
			double d = atVarrock().distanceTo(e);
			assertTrue(d >= previous);
			previous = d;
		}
	}

	@Test
	public void withoutKnowingWhereThePlayerIsNearbyFindsNothing()
	{
		assertTrue(search("loc:nearby type:shop", SearchContext.NONE).isEmpty());
	}

	@Test
	public void aLoreRegionAndAMapNameWork()
	{
		assertFalse(search("type:shop region:misthalin", atVarrock()).isEmpty());
		List<SearchEntry> keldagrim = search("type:shop map:keldagrim", atVarrock());
		assertFalse(keldagrim.isEmpty());
		for (SearchEntry e : keldagrim)
		{
			assertTrue("Keldagrim and its rat pits: " + e.getMapId(), e.getMapId() == 10 || e.getMapId() == 10088);
		}
		assertTrue("nowhere is called Atlantis", search("region:atlantis", atVarrock()).isEmpty());
	}

	@Test
	public void typeAcceptsKindsOfResultAndKindsOfItem()
	{
		assertFalse(search("type:quest", atVarrock()).isEmpty());
		assertFalse(search("type:monster goblin", atVarrock()).isEmpty());
		List<SearchEntry> bows = search("type:ranged_weapon", atVarrock());
		assertFalse(bows.isEmpty());
		for (SearchEntry e : bows)
		{
			assertTrue(e.getCategory().contains("ranged_weapon"));
		}
	}

	@Test
	public void costRangesAndBadFiltersBehave()
	{
		for (SearchEntry e : search("type:item cost:100-200", atVarrock()))
		{
			assertTrue(e.getPrice() >= 100 && e.getPrice() <= 200);
		}
		assertTrue(search("cost:cheap", atVarrock()).isEmpty());
		assertTrue("items without a price never match a cost", search("type:npc cost:10", atVarrock()).isEmpty());
	}

	@Test
	public void plainSearchesWorkAsBefore()
	{
		List<SearchEntry> found = search("bronze pickaxe", atVarrock());
		assertFalse(found.isEmpty());
		assertEquals(found, assets.getSearchIndex().search("bronze pickaxe", 200));
	}

	@Test
	public void ageOfTheTownTableIsReasonable()
	{
		assertTrue(assets.getGazetteer().contains("varrock", 0, 3212, 3428));
		assertFalse(assets.getGazetteer().contains("varrock", 0, 2964, 3378));
		assertTrue(assets.getGazetteer().contains("falador", 0, 2964, 3378));
		assertTrue(assets.getGazetteer().contains("wilderness", 0, 3100, 3800));
		assertTrue(assets.getGazetteer().contains("kandarin", 0, 2662, 3305));
	}

	private static List<String> names(List<SearchEntry> entries)
	{
		java.util.List<String> names = new java.util.ArrayList<>();
		for (SearchEntry e : entries)
		{
			names.add(e.getKind() + ":" + e.getName() + "@" + e.getX() + "," + e.getY());
		}
		return names;
	}

	@Test
	public void theGamesOwnPlaceNamesAreAreas()
	{
		List<SearchEntry> guild = search("type:shop region:\"farming guild\"", atVarrock());
		assertFalse("a shop in the Farming Guild", guild.isEmpty());
		for (SearchEntry e : guild)
		{
			assertTrue(e.getName() + " is by the Farming Guild", Math.abs(e.getX() - 1249) < 60 && Math.abs(e.getY() - 3739) < 60);
		}
		List<SearchEntry> region = search("type:shop region:\"kebos lowlands\"", atVarrock());
		for (SearchEntry inGuild : guild)
		{
			assertTrue(inGuild.getName() + " is in Kebos Lowlands too", names(region).contains(
				inGuild.getKind() + ":" + inGuild.getName() + "@" + inGuild.getX() + "," + inGuild.getY()));
		}
		for (SearchEntry e : region)
		{
			assertTrue(e.getName() + " is not at Varrock", e.getX() < 1700);
		}
	}

	@Test
	public void everyWayOfWritingTheSameAreaGivesTheSameResults()
	{
		List<String> reference = names(search("type:shop region:\"kebos lowlands\"", atVarrock()));
		assertFalse(reference.isEmpty());
		for (String form : new String[] {"type:shop region:kebos_lowlands", "type:shop region:kebosLowlands",
			"type:shop area:\"Kebos Lowlands\"", "region:kebos-lowlands type:shop", "type:shop in:\"lowlands kebos\""})
		{
			assertEquals(form, reference, names(search(form, atVarrock())));
		}
	}

	@Test
	public void aDashLeavesAnAreaOut()
	{
		List<SearchEntry> all = search("type:shop", atVarrock());
		List<SearchEntry> varrock = search("type:shop region:varrock", atVarrock());
		List<SearchEntry> others = search("type:shop -region:varrock", atVarrock());
		assertFalse(varrock.isEmpty());
		assertFalse(others.isEmpty());
		for (String inVarrock : names(varrock))
		{
			assertFalse(names(others).contains(inVarrock));
		}
		assertTrue(others.size() <= all.size());
	}

	@Test
	public void severalKindsCanBeAskedForAtOnce()
	{
		boolean shop = false;
		boolean npc = false;
		for (SearchEntry e : search("type:shop,npc region:varrock", atVarrock()))
		{
			shop |= e.getKind() == SearchKind.SHOP;
			npc |= e.getKind() == SearchKind.NPC;
			assertTrue(e.getKind() == SearchKind.SHOP || e.getKind() == SearchKind.NPC);
		}
		assertTrue(shop);
		assertTrue(npc);
	}

	@Test
	public void sortCostPutsTheCheapestFirst()
	{
		List<SearchEntry> found = search("arrow type:item sort:cost", atVarrock());
		assertTrue(found.size() > 3);
		long last = -1;
		boolean pricesEnded = false;
		for (SearchEntry e : found)
		{
			if (e.getPrice() < 0)
			{
				pricesEnded = true;
				continue;
			}
			assertFalse("items without a price come last", pricesEnded);
			assertTrue(e.getPrice() >= last);
			last = e.getPrice();
		}
	}

	@Test
	public void sortDistanceAndNameOrderTheResults()
	{
		List<SearchEntry> near = search("type:shop sort:dist", atVarrock());
		double last = -1;
		for (SearchEntry e : near)
		{
			double d = atVarrock().distanceTo(e);
			if (d < 0)
			{
				break;
			}
			assertTrue(d >= last);
			last = d;
		}
		List<SearchEntry> alpha = search("type:shop region:varrock sort:name", atVarrock());
		for (int i = 1; i < alpha.size(); i++)
		{
			assertTrue(alpha.get(i - 1).getName().compareToIgnoreCase(alpha.get(i).getName()) <= 0);
		}
	}

	private static SearchBox typed(String text)
	{
		SearchBox box = new SearchBox();
		box.setIndex(assets.getSearchIndex());
		box.setContext(SearchLanguageTest::atVarrock);
		box.focus();
		for (char c : text.toCharArray())
		{
			box.type(c);
		}
		return box;
	}

	@Test
	public void theBarOffersToFinishAnAreaName()
	{
		SearchBox box = typed("type:shop region:keb");
		assertTrue(box.getHint(), box.getHint().startsWith("Tab to finish: region:\""));
		assertTrue(box.complete());
		assertTrue(box.getText(), box.getText().startsWith("type:shop region:\"Kebos"));
		assertTrue("a finished filter has nothing left to offer", box.getHint().isEmpty() || !box.getHint().startsWith("Tab"));
	}

	@Test
	public void theBarOffersToFinishAKind()
	{
		SearchBox box = typed("type:mel");
		assertEquals("type:melee_weapon", box.getHint().replace("Tab to finish: ", ""));
		assertTrue(box.complete());
		assertEquals("type:melee_weapon", box.getText());
		SearchBox several = typed("type:shop,mon");
		assertTrue(several.complete());
		assertEquals("type:shop,monster", several.getText());
	}

	@Test
	public void theBarSaysWhyThereAreNoResults()
	{
		assertTrue(typed("region:zzzzqqq").getHint().startsWith("No area called \"zzzzqqq\""));
		assertTrue(typed("region:kebosx lowlands").getHint().contains("region:\"Kebos"));
		assertTrue(typed("zzzzqqq").getHint().startsWith("No results"));
		assertTrue(typed("cost:cheap").getHint().contains("not understood"));
		assertTrue("good results need no hint", typed("lumbridge").getHint().isEmpty());
		assertTrue(typed("").getHint().isEmpty());
	}

	@Test
	public void typePlaceFindsTheNamedPlacesOfAnArea()
	{
		List<SearchEntry> found = search("type:place region:misthalin", atVarrock());
		assertTrue("places in Misthalin " + found.size(), found.size() > 100);
		boolean boar = false;
		boolean swamp = false;
		boolean castle = false;
		for (SearchEntry e : found)
		{
			assertTrue(e.getKind() + " is somewhere to go", e.getKind() == SearchKind.PLACE || e.getKind() == SearchKind.SHOP
				|| e.getKind() == SearchKind.QUEST || e.getKind() == SearchKind.DOORWAY);
			boar |= e.getName().equals("Jolly Boar Inn");
			swamp |= e.getName().equals("Lumbridge Swamp");
			castle |= e.getName().equals("Lumbridge Castle");
		}
		assertTrue(boar);
		assertTrue(swamp);
		assertTrue(castle);
	}

	@Test
	public void anOrdinarySearchLeavesTheNamedPlacesOutUnlessThereIsNothingElse()
	{
		for (SearchEntry e : search("lumbridge", atVarrock()))
		{
			assertFalse(e.getName() + " is a named place", e.isLocation() && !e.isAreaName());
		}
		List<SearchEntry> boar = search("jolly boar", atVarrock());
		assertFalse("with nothing else to show, the place is the answer", boar.isEmpty());
		assertEquals("Jolly Boar Inn", boar.get(0).getName());
	}

	@Test
	public void freeItemsCanBeAskedForAndSortFirstByCost()
	{
		List<SearchEntry> free = search("free:yes", atVarrock());
		assertFalse(free.isEmpty());
		for (SearchEntry e : free)
		{
			assertEquals(0, e.getPrice());
		}
		List<SearchEntry> asType = search("type:free region:lumbridge", atVarrock());
		assertFalse(asType.isEmpty());
		for (SearchEntry e : asType)
		{
			assertEquals(SearchKind.ITEM, e.getKind());
		}
		List<SearchEntry> byCost = search("type:item region:lumbridge sort:cost", atVarrock());
		assertEquals("what is free comes first", 0, byCost.get(0).getPrice());
		assertEquals("and counts as costing nothing", 0, search("cost:0 region:lumbridge", atVarrock()).get(0).getPrice());
		assertTrue("so a ceiling on cost keeps it", search("cost:50 type:item region:lumbridge", atVarrock()).stream()
			.anyMatch(e -> e.getPrice() == 0));
	}
}
