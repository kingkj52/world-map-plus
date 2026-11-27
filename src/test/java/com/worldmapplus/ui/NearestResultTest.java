package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class NearestResultTest
{
	private static PlayerPosition at(double x, double y)
	{
		return new PlayerPosition(x, y, new WorldPoint((int) x, (int) y, 0));
	}

	private static SearchEntry banks()
	{
		List<double[]> tiles = Arrays.asList(new double[] {3253.5, 3420.5}, new double[] {3185.5, 3436.5},
			new double[] {3092.5, 3243.5});
		return new SearchEntry(SearchKind.POINT, "Bank: Varrock", "Bank (3 places)", 0, 3253.5, 3420.5, tiles);
	}

	@Test
	public void theJumpGoesToThePlaceNearestThePlayer()
	{
		SearchEntry nearest = MapSurface.nearestTo(banks(), at(3188, 3432));
		assertEquals(3185.5, nearest.getX(), 1e-9);
		assertEquals(3436.5, nearest.getY(), 1e-9);
		assertEquals("Bank: Varrock", nearest.getName());
		assertEquals(3, nearest.getAlsoAt().size());
	}

	@Test
	public void withoutAPlayerOrWithOnePlaceNothingChanges()
	{
		SearchEntry entry = banks();
		assertSame(entry, MapSurface.nearestTo(entry, null));
		SearchEntry single = new SearchEntry(SearchKind.SHOP, "Shop", null, 0, 1, 1);
		assertSame(single, MapSurface.nearestTo(single, at(5, 5)));
	}

	@Test
	public void ifTheFirstPlaceIsAlreadyNearestTheEntryIsReused()
	{
		SearchEntry entry = banks();
		assertSame(entry, MapSurface.nearestTo(entry, at(3255, 3419)));
	}
}
