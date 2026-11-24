package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** What the gazetteer says about a few spots, from the game's own labels. The class name is left from a probe. */
public class ProbeTest
{
	private static Gazetteer places;

	@BeforeClass
	public static void load()
	{
		places = new MapAssets(new Gson(), new java.util.concurrent.ScheduledThreadPoolExecutor(1)).getGazetteer();
	}

	@Test
	public void aSpotIsInThePlaceTheTownAndTheRegionAroundIt()
	{
		List<String> names = Arrays.asList(places.labelNamesAt(0, 1250.5, 3736.5));
		assertTrue(names.toString(), names.contains("farming guild"));
		assertTrue(names.toString(), names.contains("kebos lowlands"));
		assertTrue(places.contains("kebos lowlands", 0, 1250.5, 3736.5));
		assertFalse(places.contains("kebos lowlands", 0, 3213.5, 3428.5));
	}

	@Test
	public void aShopWithTheWrongPlaceNameDoesNotStretchTheArea()
	{
		// The wiki puts a garden centre in Falador under the Farming Guild. Falador is not in the Farming Guild.
		assertFalse(places.contains("farming guild", 0, 3018.5, 3374.5));
		assertTrue(places.contains("farming guild", 0, 1250.5, 3736.5));
	}

	@Test
	public void namesAreSuggestedForWhatIsTyped()
	{
		assertEquals("Kebos Lowlands", places.suggest("kebos lowl", 3).get(0));
		assertTrue(places.suggest("kebos", 10).size() >= 3);
		assertTrue(places.suggest("zzzzqq", 3).isEmpty());
		assertTrue(places.knows("farming guild"));
		assertFalse(places.knows("farming guilds of doom"));
		assertFalse("a sea is not an area", places.suggest("unquiet ocean", 5).contains("Unquiet Ocean"));
	}

	@Test
	public void wordOrderDoesNotMatter()
	{
		assertTrue(Gazetteer.allWords("kebos lowlands", "lowlands kebos"));
		assertFalse(Gazetteer.allWords("kebos lowlands", "kebos highlands"));
	}
}
