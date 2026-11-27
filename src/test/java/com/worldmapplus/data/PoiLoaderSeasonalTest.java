package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Points of events carry a first and a last day and drop out of the loaded data on their own (tools/merge_seasonal.py). */
public class PoiLoaderSeasonalTest
{
	private static final Gson GSON = new Gson();

	private static MapCatalog catalog()
	{
		List<MapInfo> maps = new ArrayList<>();
		maps.add(new MapInfo(0, "Gielinor Surface", 896, 1984, 4096, 4288, 2496, 3328));
		return new MapCatalog("v", maps);
	}

	private static final String JSON = "{\"categories\":["
		+ "{\"id\":\"bank\",\"label\":\"Banks\",\"glyph\":\"B\",\"color\":\"#ffffff\",\"items\":[{\"n\":\"Always\",\"p\":[3000,3000]}]},"
		+ "{\"id\":\"seasonal\",\"label\":\"Seasonal events\",\"glyph\":\"*\",\"color\":\"#f97316\",\"items\":["
		+ "{\"n\":\"Event A\",\"p\":[3001,3001],\"s\":20261023,\"e\":20261107},"
		+ "{\"n\":\"Event B\",\"p\":[3002,3002],\"s\":20261023,\"e\":20261130}]}]}";

	private static int hotspots(int today)
	{
		return PoiLoader.read(GSON.fromJson(JSON, PoiLoader.PoiFile.class), catalog(), today).getHotspots().size();
	}

	private static int categories(int today)
	{
		return PoiLoader.read(GSON.fromJson(JSON, PoiLoader.PoiFile.class), catalog(), today).getCategories().size();
	}

	@Test
	public void aPointIsHiddenBeforeItsStart()
	{
		assertEquals(1, hotspots(20261022));
		assertEquals(1, categories(20261022));
	}

	@Test
	public void aPointShowsOnItsFirstAndLastDay()
	{
		assertEquals(3, hotspots(20261023));
		assertEquals(3, hotspots(20261107));
	}

	@Test
	public void aPointGoesAwayAfterItsEndAndTheOthersStay()
	{
		assertEquals(2, hotspots(20261108));
		assertEquals(2, categories(20261108));
	}

	@Test
	public void aCategoryWhoseEveryPointEndedIsNotListed()
	{
		assertEquals(1, hotspots(20261201));
		assertEquals(1, categories(20261201));
	}

	@Test
	public void pointsWithoutDatesAreNeverHidden()
	{
		assertEquals(1, hotspots(20300101));
		assertEquals(1, hotspots(19990101));
	}

	@Test
	public void theClockOverloadReadsWithoutDatesLikeBefore()
	{
		String plain = "{\"categories\":[{\"id\":\"bank\",\"label\":\"Banks\",\"items\":[{\"n\":\"Always\",\"p\":[3000,3000]}]}]}";
		assertEquals(1, PoiLoader.read(GSON.fromJson(plain, PoiLoader.PoiFile.class), catalog(), 20261125).getHotspots().size());
	}
}
