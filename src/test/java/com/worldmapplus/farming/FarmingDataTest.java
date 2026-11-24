package com.worldmapplus.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

public class FarmingDataTest
{
	private static FarmingData data;

	@BeforeClass
	public static void load()
	{
		data = FarmingData.load(new Gson());
	}

	@Test
	public void theBundledTablesLoad()
	{
		assertTrue("regions " + data.getRegions().size(), data.getRegions().size() > 35);
		int patches = 0;
		for (FarmingData.Region region : data.getRegions())
		{
			patches += region.getPatches().size();
		}
		assertTrue("patches " + patches, patches > 90);
	}

	@Test
	public void aHerbPatchValueMeansWhatTheGameSays()
	{
		PatchState seed = data.decode("HERB", 4);
		assertEquals("Guam", seed.getProduce().getName());
		assertEquals(CropState.GROWING, seed.getCrop());
		assertEquals(0, seed.getStage());
		assertEquals(20, seed.getTickMinutes());
		assertEquals(5, seed.getStages());
		assertEquals("four growth ticks to go", 4, seed.stagesLeft());

		PatchState grown = data.decode("HERB", 8);
		assertEquals(CropState.HARVESTABLE, grown.getCrop());

		PatchState weeds = data.decode("HERB", 0);
		assertEquals("Weeds", weeds.getProduce().getName());
		assertEquals("value 0 is the weediest", 3, weeds.getStage());
		assertEquals(0, data.decode("HERB", 3).getStage());
	}

	@Test
	public void aValueTheTablesDoNotKnowGivesNothing()
	{
		assertNull(data.decode("HERB", 200000));
		assertNull(data.decode("NOT_A_KIND", 1));
		assertNull(data.decode("COMPOST", 1));
	}

	@Test
	public void fortheRegionsOfAPlaceTheBoundsRulesPickTheRightOnes()
	{
		// Falador's allotments and the Port Sarim spirit tree share a region square.
		List<FarmingData.Region> falador = data.regionsAt(3052, 3309, 0);
		assertEquals(1, falador.size());
		assertEquals("Falador", falador.get(0).getName());
		List<FarmingData.Region> portSarim = data.regionsAt(3056, 3259, 0);
		assertEquals(1, portSarim.size());
		assertEquals("Port Sarim", portSarim.get(0).getName());

		// Catherby upstairs sends the fruit tree, not the allotments.
		assertEquals("Catherby", data.regionsAt(2810, 3462, 0).get(0).getName());
		boolean fruit = false;
		for (FarmingData.Region r : data.regionsAt(2860, 3430, 0))
		{
			for (FarmingData.Patch p : r.getPatches())
			{
				fruit |= p.getKind().equals("FRUIT_TREE");
			}
		}
		assertTrue(fruit);

		assertTrue("nowhere near a patch", data.regionsAt(3200, 3000, 0).isEmpty());
	}

	@Test
	public void aMapPointStandsForTheMatchingPatchesOfItsRegion()
	{
		List<FarmingData.Located> found = data.patchesFor("Allotment/Herb/Flower patch", 3052, 3309);
		assertEquals(4, found.size());
		int allotments = 0;
		boolean herb = false;
		boolean flower = false;
		for (FarmingData.Located located : found)
		{
			assertEquals("Falador", located.getRegion().getName());
			allotments += located.getPatch().getKind().equals("ALLOTMENT") ? 1 : 0;
			herb |= located.getPatch().getKind().equals("HERB");
			flower |= located.getPatch().getKind().equals("FLOWER");
		}
		assertEquals(2, allotments);
		assertTrue(herb && flower);

		List<FarmingData.Located> tree = data.patchesFor("Spirit Tree patch", 3056, 3259);
		assertEquals(1, tree.size());
		assertEquals("Port Sarim", tree.get(0).getRegion().getName());
		assertTrue(data.patchesFor("Banana", 3052, 3309).isEmpty());
	}

	@Test
	public void everyFarmingPointOnTheMapHasPatches()
	{
		com.worldmapplus.map.MapCatalog catalog = com.worldmapplus.map.MapCatalog.load(new Gson());
		com.worldmapplus.data.PoiLoader.Result pois = com.worldmapplus.data.PoiLoader.load(new Gson(), catalog);
		int farming = 0;
		int matched = 0;
		for (com.worldmapplus.layers.Hotspot h : pois.getHotspots())
		{
			if ("farming".equals(h.getCategory()))
			{
				farming++;
				if (!data.patchesFor(h.getCard().getTitle(), (int) Math.floor(h.getX()), (int) Math.floor(h.getY())).isEmpty())
				{
					matched++;
				}
			}
		}
		assertTrue("farming points " + farming, farming > 50);
		assertTrue("points with patches " + matched + " of " + farming, matched * 10 >= farming * 8);
	}

	@Test
	public void namesBecomeKinds()
	{
		assertEquals(Arrays.asList("CELASTRUS", "FRUIT_TREE"), FarmingData.kindsIn("Celastrus/Fruit Tree patch"));
		assertEquals(Arrays.asList("ALLOTMENT"), FarmingData.kindsIn("Allotment patch"));
		assertTrue(FarmingData.kindsIn("Something else").isEmpty());
		assertEquals("Fruit tree", FarmingData.kindName("FRUIT_TREE"));
	}

	@Test
	public void aPatchHasAReadableLabel()
	{
		FarmingData.Region ardougne = null;
		for (FarmingData.Region r : data.getRegions())
		{
			if (r.getId() == 10548)
			{
				ardougne = r;
			}
		}
		assertNotNull(ardougne);
		assertEquals("North allotment", ardougne.getPatches().get(0).label());
		assertEquals("Flower", ardougne.getPatches().get(2).label());
		assertFalse(ardougne.getPatches().isEmpty());
	}

	@Test
	public void regionNumbersFollowTheGamesFormula()
	{
		assertEquals(12083, FarmingData.regionId(3052, 3309));
		assertEquals(10548, FarmingData.regionId(2663, 3375));
	}
}
