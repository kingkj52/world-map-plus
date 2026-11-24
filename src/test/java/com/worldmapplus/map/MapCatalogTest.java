package com.worldmapplus.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.io.StringReader;
import org.junit.Test;

public class MapCatalogTest
{
	private static final String JSON = "{\"version\":\"2026-08-12_a\",\"maps\":["
		+ "{\"id\":0,\"name\":\"Gielinor Surface\",\"minX\":896,\"minY\":1984,\"maxX\":4096,\"maxY\":4288,\"cx\":2496,\"cy\":3328},"
		+ "{\"id\":6,\"name\":\"Dwarven Mines\",\"minX\":2880,\"minY\":9600,\"maxX\":3328,\"maxY\":9920,\"cx\":3040,\"cy\":9824},"
		+ "{\"id\":99,\"name\":\"Small Overlap\",\"minX\":3000,\"minY\":3000,\"maxX\":3100,\"maxY\":3100,\"cx\":3050,\"cy\":3050}"
		+ "]}";

	private static MapCatalog catalog()
	{
		return MapCatalog.read(new Gson(), new StringReader(JSON));
	}

	@Test
	public void overlappingBoundsDoNotMeanOverlappingImagery()
	{
		// Two maps whose rectangles overlap, where only one has imagery for the square in question.
		int sewers = (48 << 16) | 151;
		int mines = (47 << 16) | 152;
		MapInfo small = new MapInfo(6, "Dwarven Mines", 2944, 9664, 3264, 9856, 3040, 9824).withSquares(mines);
		MapInfo large = new MapInfo(12, "Misthalin Underground", 3072, 9472, 3328, 10048, 3168, 9632)
			.withSquares(sewers, (49 << 16) | 151);
		MapCatalog c = new MapCatalog("v", java.util.Arrays.asList(small, large));
		assertEquals("the sewers belong to the map that has imagery there", 12, c.mapFor(3084, 9672).getId());
		assertEquals(6, c.mapFor(3019, 9780).getId());
		assertEquals(false, small.contains(3084, 9672));
		assertEquals(true, large.contains(3084, 9672));
		assertNull("inside a rectangle but in a square with no imagery", c.mapFor(3300, 9500));
	}

	@Test
	public void anExtraMapIsOnlyChosenWhereNoStandardMapCovers()
	{
		MapInfo big = new MapInfo(14, "Morytania Underground", 3328, 9600, 3904, 9984, 3479, 9837);
		MapInfo extra = new MapInfo(10000, "Abandoned Mine - Level 1", 3328, 9536, 3520, 9728, 3424, 9632);
		MapCatalog c = new MapCatalog("v", java.util.Arrays.asList(extra, big));
		assertEquals("a standard map wins even when the extra one is smaller", 14, c.mapFor(3400, 9650).getId());
		assertEquals("outside every standard map the extra one answers", 10000, c.mapFor(3400, 9560).getId());
		assertEquals(true, big.isStandard());
		assertEquals(false, extra.isStandard());
	}

	@Test
	public void readsVersionAndMaps()
	{
		MapCatalog c = catalog();
		assertEquals("2026-08-12_a", c.getVersion());
		assertEquals(3, c.getMaps().size());
		assertEquals("Dwarven Mines", c.byId(6).getName());
	}

	@Test
	public void theSmallerOfTwoOverlappingMapsWins()
	{
		MapCatalog c = catalog();
		assertEquals(99, c.mapFor(3050, 3050).getId());
		assertEquals(0, c.mapFor(3200, 3200).getId());
	}

	@Test
	public void findsUndergroundMaps()
	{
		assertEquals(6, catalog().mapFor(3040, 9800).getId());
	}

	@Test
	public void positionsOutsideEveryMapGiveNull()
	{
		assertNull(catalog().mapFor(100, 100));
	}

	@Test
	public void boundsAreHalfOpen()
	{
		MapInfo surface = catalog().byId(0);
		assertTrue(surface.contains(896, 1984));
		assertTrue(!surface.contains(4096, 3000));
		assertTrue(!surface.contains(3000, 4288));
	}

	@Test
	public void squareRangesCoverTheBounds()
	{
		MapInfo surface = catalog().byId(0);
		assertEquals(14, surface.firstSquareX());
		assertEquals(63, surface.lastSquareX());
		assertEquals(31, surface.firstSquareY());
		assertEquals(66, surface.lastSquareY());
	}

	@Test
	public void surfaceFallsBackToTheFirstMap()
	{
		MapCatalog c = new MapCatalog("v", java.util.Collections.singletonList(
			new MapInfo(5, "Only", 0, 0, 64, 64, 32, 32)));
		assertNotNull(c.surface());
		assertEquals(5, c.surface().getId());
	}

	@Test
	public void damagedInputGivesAnEmptyCatalogue()
	{
		MapCatalog c = MapCatalog.read(new Gson(), new StringReader("null"));
		assertTrue(c.getMaps().isEmpty());
		assertNull(c.surface());
	}

	@Test
	public void aTileWithNoPictureBelongsToTheNearestMapWithinAMargin()
	{
		MapInfo surface = new MapInfo(0, "Surface", 0, 0, 4096, 4096, 2000, 2000).withSquares((10 << 16) | 10, (11 << 16) | 10);
		MapInfo cave = new MapInfo(5, "Cave", 1000, 9000, 1200, 9200, 1100, 9100).withSquares((16 << 16) | 141, (17 << 16) | 141);
		MapCatalog c = new MapCatalog("v", java.util.Arrays.asList(surface, cave));
		// On a square with a picture.
		assertEquals(5, c.mapNear(1030, 9050, 192).getId());
		// Two squares east of the cave's last square: no picture there, so the cave is nearest.
		assertEquals(5, c.mapNear(1090 + 64 + 40, 9050, 192).getId());
		// Far from every picture there is no answer.
		assertNull(c.mapNear(3000, 3000, 192));
		assertEquals(0, cave.distanceTo(1030, 9050));
		assertTrue(cave.distanceTo(1300, 9050) > 64);
	}
}
