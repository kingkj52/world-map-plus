package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import java.util.Collections;
import org.junit.Test;

/** The lore regions of {@code regions.json}, as the search's {@code region:} words see them (until round 67 {@code RegionLines}). */
public class LoreRegionsTest
{
	private static final Gson GSON = new Gson();

	private static Gazetteer read(String json)
	{
		return new Gazetteer(null, GSON.fromJson(json, Gazetteer.Regions.class), null);
	}

	@Test
	public void aTileIsInTheRegionOfItsSquare()
	{
		Gazetteer lines = read("{\"regions\":[{\"id\":\"A\",\"name\":\"Alpha\",\"color\":\"#ff0000\",\"cells\":[[50,49]]}]}");
		assertTrue(lines.contains("alpha", 0, 3200, 3136));
		assertTrue(lines.contains("alpha", 0, 3263.9, 3199.9));
		assertFalse(lines.contains("alpha", 0, 3264, 3136));
		assertFalse(lines.contains("alpha", 0, 3199, 3136));
		assertFalse(lines.contains("alpha", 0, -5, -5));
		assertFalse("only the surface has lore regions", lines.contains("alpha", 5, 3200, 3136));
		assertTrue("the id is a name too", lines.contains("a", 0, 3200, 3136));
	}

	@Test
	public void aSquareClaimedTwiceBelongsToTheFirstRegionOnly()
	{
		Gazetteer lines = read("{\"regions\":["
			+ "{\"id\":\"A\",\"name\":\"Aa\",\"color\":\"#ff0000\",\"cells\":[[1,1]]},"
			+ "{\"id\":\"B\",\"name\":\"Bb\",\"color\":\"#0000ff\",\"cells\":[[1,1]]}]}");
		assertTrue(lines.contains("aa", 0, 64, 64));
		assertFalse(lines.contains("bb", 0, 64, 64));
		assertEquals("a region left with no square is not offered", Collections.emptyList(), lines.suggest("bb", 5));
	}

	@Test
	public void badRecordsAreSkipped()
	{
		Gazetteer lines = read("{\"regions\":[null,{\"id\":\"A\"},{\"id\":\"B\",\"name\":\"Bb\",\"cells\":[]},"
			+ "{\"id\":\"C\",\"name\":\"Cc\",\"color\":\"nonsense\",\"cells\":[[1,1],[7],null]}]}");
		assertEquals(Collections.singletonList("Cc"), lines.suggest("c", 5));
		assertFalse(lines.knows("bb"));
		assertTrue(lines.contains("cc", 0, 64, 64));
	}

	@Test
	public void theBundledTableNamesTheRegions()
	{
		Gazetteer lines = new Gazetteer(null, MapAssets.json(GSON, "regions.json", Gazetteer.Regions.class), null);
		assertTrue(lines.contains("misthalin", 0, 3213, 3424));
		assertTrue(lines.contains("misthalin", 0, 3222, 3218));
		assertTrue(lines.contains("asgarnia", 0, 2964, 3378));
		assertTrue(lines.contains("kandarin", 0, 2662, 3305));
		assertTrue(lines.contains("forinthry", 0, 3100, 3800));
		assertTrue(lines.contains("morytania", 0, 3500, 3480));
		assertTrue(lines.contains("kharidian desert", 0, 3300, 3000));
		assertTrue(lines.contains("great kourend", 0, 1640, 3670));
		assertTrue(lines.contains("karamja", 0, 2850, 3000));
		assertFalse(lines.contains("misthalin", 0, 2964, 3378));
	}
}
