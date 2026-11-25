package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import java.io.StringReader;
import org.junit.Test;

public class RegionLinesTest
{
	private static final Gson GSON = new Gson();

	private static RegionLines read(String json)
	{
		return RegionLines.read(new StringReader(json), GSON);
	}

	@Test
	public void aTileIsInTheRegionOfItsSquare()
	{
		RegionLines lines = read("{\"regions\":[{\"id\":\"A\",\"name\":\"Alpha\",\"color\":\"#ff0000\",\"cells\":[[50,49]]}]}");
		assertEquals("Alpha", lines.regionAt(3200, 3136).getName());
		assertEquals("Alpha", lines.regionAt(3263, 3199).getName());
		assertNull(lines.regionAt(3264, 3136));
		assertNull(lines.regionAt(3199, 3136));
		assertNull(lines.regionAt(-5, -5));
	}

	@Test
	public void aSquareClaimedTwiceBelongsToTheFirstRegionOnly()
	{
		RegionLines lines = read("{\"regions\":["
			+ "{\"id\":\"A\",\"name\":\"A\",\"color\":\"#ff0000\",\"cells\":[[1,1]]},"
			+ "{\"id\":\"B\",\"name\":\"B\",\"color\":\"#0000ff\",\"cells\":[[1,1]]}]}");
		assertEquals(1, lines.getRegions().size());
		assertEquals("A", lines.regionAt(64, 64).getName());
	}

	@Test
	public void badRecordsAreSkipped()
	{
		RegionLines lines = read("{\"regions\":[null,{\"id\":\"A\"},{\"id\":\"B\",\"name\":\"B\",\"cells\":[]},"
			+ "{\"id\":\"C\",\"name\":\"C\",\"color\":\"nonsense\",\"cells\":[[1,1],[7]]}]}");
		assertEquals(1, lines.getRegions().size());
		assertEquals("C", lines.getRegions().get(0).getName());
		assertEquals(1, lines.getRegions().get(0).squareCount());
	}

	@Test
	public void theBundledTableNamesTheEleven()
	{
		RegionLines lines = RegionLines.load(GSON);
		assertEquals(11, lines.getRegions().size());
		assertEquals("Misthalin", lines.regionAt(3213, 3424).getName());
		assertEquals("Misthalin", lines.regionAt(3222, 3218).getName());
		assertEquals("Asgarnia", lines.regionAt(2964, 3378).getName());
		assertEquals("Kandarin", lines.regionAt(2662, 3305).getName());
		assertEquals("Forinthry", lines.regionAt(3100, 3800).getName());
		assertEquals("Morytania", lines.regionAt(3500, 3480).getName());
		assertEquals("Kharidian Desert", lines.regionAt(3300, 3000).getName());
		assertEquals("Great Kourend", lines.regionAt(1640, 3670).getName());
		assertEquals("Karamja", lines.regionAt(2850, 3000).getName());
		assertNotNull(lines.regionAt(2300, 3180));
	}
}
