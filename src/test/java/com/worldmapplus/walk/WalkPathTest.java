package com.worldmapplus.walk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class WalkPathTest
{
	/** A region where every step is possible, so a test can then put walls in. */
	private static byte[] open()
	{
		byte[] data = new byte[CollisionMap.bitsPerPlane() / 8];
		Arrays.fill(data, (byte) 0xFF);
		return data;
	}

	private static void clear(byte[] data, int x, int y, int flag)
	{
		int bit = ((y & 63) * 64 + (x & 63)) * 2 + flag;
		data[bit >> 3] &= ~(1 << (bit & 7));
	}

	private static CollisionMap one(byte[] region)
	{
		Map<Integer, byte[]> regions = new HashMap<>();
		regions.put(0, region);
		return new CollisionMap(regions);
	}

	@Test
	public void inOpenGroundTheWayIsStraightAndDiagonal()
	{
		int[] path = WalkPath.find(one(open()), 5, 5, 12, 9, 0);
		assertNotNull(path);
		assertEquals("seven steps, going diagonally where it helps", 8, path.length / 2);
		assertArrayEquals(new int[] {5, 5}, Arrays.copyOfRange(path, 0, 2));
		assertArrayEquals(new int[] {12, 9}, Arrays.copyOfRange(path, path.length - 2, path.length));
	}

	@Test
	public void aWallIsWalkedRoundNotThrough()
	{
		byte[] region = open();
		for (int y = 0; y <= 20; y++)
		{
			clear(region, 9, y, 1);
		}
		CollisionMap map = one(region);
		int[] path = WalkPath.find(map, 5, 10, 15, 10, 0);
		assertNotNull(path);
		boolean crossed = false;
		boolean wentAround = false;
		for (int i = 0; i + 3 < path.length; i += 2)
		{
			int x1 = path[i];
			int x2 = path[i + 2];
			assertTrue("every step is to a neighbouring tile", Math.abs(x2 - x1) <= 1 && Math.abs(path[i + 3] - path[i + 1]) <= 1);
			if (x1 == 9 && x2 == 10 && path[i + 1] <= 20)
			{
				crossed = true;
			}
			wentAround |= path[i + 1] > 20;
		}
		assertFalse("never through the wall", crossed);
		assertTrue("it went over the end of the wall", wentAround);
		assertTrue("so it is longer than the straight way", path.length / 2 - 1 > 10);
	}

	@Test
	public void aGoalWithNoWayInGivesNothing()
	{
		byte[] region = open();
		// Box the goal in on every side.
		for (int d = -1; d <= 1; d++)
		{
			clear(region, 30 + d, 30, 0);
		}
		for (int x = 28; x <= 32; x++)
		{
			for (int y = 28; y <= 32; y++)
			{
				if (x == 28 || x == 32 || y == 28 || y == 32)
				{
					clear(region, x, y, 0);
					clear(region, x, y, 1);
					clear(region, x - 1, y, 1);
					clear(region, x, y - 1, 0);
				}
			}
		}
		assertNull(WalkPath.find(one(region), 5, 5, 30, 30, 0));
	}

	@Test
	public void noDataMeansNoWay()
	{
		assertNull(WalkPath.find(new CollisionMap(new HashMap<>()), 5, 5, 9, 9, 0));
		assertNull("another region has no data", WalkPath.find(one(open()), 5, 5, 700, 700, 0));
	}

	@Test
	public void aWalkTooWideToSearchIsLeftAlone()
	{
		assertNull(WalkPath.find(one(open()), 5, 5, 20000, 20000, 0));
	}

	@Test
	public void theBundledDataFindsAWayAcrossLumbridge()
	{
		CollisionMap map = CollisionMap.load();
		assertTrue("data for Lumbridge", map.knows(3222, 3218));
		int[] path = WalkPath.find(map, 3222, 3218, 3200, 3220, 0);
		assertNotNull(path);
		int steps = path.length / 2 - 1;
		assertTrue("at least as long as the straight way: " + steps, steps >= 22);
		assertTrue("and not wildly longer: " + steps, steps < 80);
		for (int i = 0; i < path.length; i += 2)
		{
			assertFalse("no step lands on a tile nothing can leave", map.isBlocked(path[i], path[i + 1], 0));
			if (i + 3 < path.length)
			{
				assertTrue(Math.abs(path[i + 2] - path[i]) <= 1 && Math.abs(path[i + 3] - path[i + 1]) <= 1);
			}
		}
	}

	@Test
	public void theBundledDataKeepsAWalkOutOfAClosedBuilding()
	{
		CollisionMap map = CollisionMap.load();
		// From the middle of Lumbridge Castle's ground floor to Draynor, a few hundred tiles, round the walls.
		int[] path = WalkPath.find(map, 3222, 3218, 3105, 3250, 0);
		assertNotNull(path);
		assertTrue(path.length / 2 - 1 >= 117);
	}

	@Test
	public void theWholeBundledDataIsReadable()
	{
		CollisionMap map = CollisionMap.load();
		assertTrue(map.knows(3200, 3200));
		assertTrue(map.knows(2600, 3100));
		assertFalse(map.knows(100, 100));
	}

	@Test
	public void thePlannerAnswersLaterAndKeepsTheOldWayWhileWorkingOutANewOne()
	{
		List<Runnable> queue = new ArrayList<>();
		WalkPlanner planner = new WalkPlanner(queue::add, () -> one(open()));
		assertNull("nothing known on the first ask", planner.lookup("leg", 5, 5, 12, 9, 0));
		assertEquals(1, queue.size());
		planner.lookup("leg", 5, 5, 12, 9, 0);
		assertEquals("one search for one question", 1, queue.size());
		queue.remove(0).run();
		int[] first = planner.lookup("leg", 5, 5, 12, 9, 0);
		assertNotNull(first);
		assertEquals(8, first.length / 2);

		// The player walked along the line: what is left of the line is the answer, and nothing is searched for.
		int[] rest = planner.lookup("leg", first[2], first[3], 12, 9, 0);
		assertEquals(first.length - 2, rest.length);
		assertEquals(first[2], rest[0]);
		assertTrue("no new search", queue.isEmpty());

		// The player has left the line: a new question, answered with the old way until the new one is ready.
		assertSame(rest, planner.lookup("leg", 5, 8, 12, 9, 0));
		queue.remove(0).run();
		int[] second = planner.lookup("leg", 5, 8, 12, 9, 0);
		assertTrue(second != rest);
		assertEquals(8, second.length / 2);

		assertEquals("a walk with no data is remembered as none", 0,
			planner.lookup("far", 5, 5, 900, 900, 0) == null ? 0 : -1);
		queue.remove(0).run();
		assertSame(WalkPlanner.NONE, planner.lookup("far", 5, 5, 900, 900, 0));
		planner.release();
		assertNull(planner.lookup("leg", 5, 5, 12, 9, 0));
	}

	@Test
	public void aSearchThatWasUnderWayWhenEverythingWasLetGoOfLeavesNothingBehind()
	{
		List<Runnable> queue = new ArrayList<>();
		WalkPlanner planner = new WalkPlanner(queue::add, () -> one(open()));
		assertNull(planner.lookup("leg", 5, 5, 12, 9, 0));
		planner.release();
		queue.remove(0).run();
		assertNull("its answer was not wanted any more", planner.lookup("leg", 5, 5, 12, 9, 0));
	}

	@Test
	public void aSearchGivesUpOnAGoalNothingCanReach()
	{
		byte[] region = open();
		// Wall the goal off completely in a large open area, so the search would otherwise explore everything.
		for (int x = 28; x <= 32; x++)
		{
			for (int y = 28; y <= 32; y++)
			{
				if (x == 28 || x == 32 || y == 28 || y == 32)
				{
					clear(region, x, y, 0);
					clear(region, x, y, 1);
					clear(region, x - 1, y, 1);
					clear(region, x, y - 1, 0);
				}
			}
		}
		long start = System.nanoTime();
		assertNull(WalkPath.find(one(region), 5, 5, 30, 30, 0));
		assertTrue("quick", (System.nanoTime() - start) < 2_000_000_000L);
	}
}
