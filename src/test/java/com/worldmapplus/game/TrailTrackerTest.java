package com.worldmapplus.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class TrailTrackerTest
{
	private static MapCatalog catalog()
	{
		List<MapInfo> maps = new ArrayList<>();
		maps.add(new MapInfo(0, "Surface", 896, 1984, 4096, 4288, 2496, 3328));
		maps.add(new MapInfo(6, "Mines", 2880, 9600, 3328, 9920, 3040, 9824));
		return new MapCatalog("v", maps);
	}

	private static PlayerPosition at(double x, double y)
	{
		return new PlayerPosition(x, y, new WorldPoint((int) x, (int) y, 0));
	}

	@Test
	public void smallMovementsAreNotRecorded()
	{
		TrailTracker trail = new TrailTracker();
		trail.add(catalog(), at(3200.5, 3200.5));
		trail.add(catalog(), at(3201.0, 3200.5));
		trail.add(catalog(), at(3201.4, 3200.5));
		assertEquals(1, trail.snapshot().size());
		trail.add(catalog(), at(3203.0, 3200.5));
		assertEquals(2, trail.snapshot().size());
	}

	@Test
	public void aLongJumpStartsANewLine()
	{
		TrailTracker trail = new TrailTracker();
		trail.add(catalog(), at(3200.5, 3200.5));
		trail.add(catalog(), at(3202.5, 3200.5));
		trail.add(catalog(), at(3300.5, 3300.5));
		List<double[]> points = trail.snapshot();
		assertEquals(3, points.size());
		assertEquals(0.0, points.get(1)[2], 0);
		assertEquals(1.0, points.get(2)[2], 0);
	}

	@Test
	public void changingMapStartsANewLine()
	{
		TrailTracker trail = new TrailTracker();
		trail.add(catalog(), at(3019.5, 3450.5));
		trail.add(catalog(), at(3019.5, 9850.5));
		List<double[]> points = trail.snapshot();
		assertEquals(1.0, points.get(1)[2], 0);
		assertEquals(6.0, points.get(1)[3], 0);
	}

	@Test
	public void theTrailIsBounded()
	{
		TrailTracker trail = new TrailTracker();
		for (int i = 0; i < TrailTracker.MAX_POINTS + 200; i++)
		{
			trail.add(catalog(), at(3000 + (i % 30) * 2.5, 3000 + (i / 30) * 2.5));
		}
		assertTrue(trail.snapshot().size() <= TrailTracker.MAX_POINTS);
		trail.clear();
		assertTrue(trail.snapshot().isEmpty());
	}

	@Test
	public void nothingIsRecordedWithoutAPosition()
	{
		TrailTracker trail = new TrailTracker();
		trail.add(catalog(), null);
		assertTrue(trail.snapshot().isEmpty());
	}
}
