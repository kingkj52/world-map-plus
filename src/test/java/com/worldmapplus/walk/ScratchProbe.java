package com.worldmapplus.walk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Checks against the bundled walking data, on tiles around Lumbridge Castle whose reachability was looked at
 * by hand when the walking search was written. The name is left from that first look.
 */
public class ScratchProbe
{
	private static final CollisionMap MAP = CollisionMap.load();

	@Test
	public void openGroundIsWalkedStraight()
	{
		// Twelve tiles due north and open: twelve steps, each one going a tile north. Equally short ways may
		// zigzag, so the sideways part is not checked.
		int[] path = WalkPath.find(MAP, 3222, 3218, 3222, 3230, 0);
		assertNotNull(path);
		assertEquals(12, path.length / 2 - 1);
		for (int i = 0; i + 3 < path.length; i += 2)
		{
			assertEquals(1, path[i + 3] - path[i + 1]);
		}
	}

	@Test
	public void theCastleWallsAreWalkedRoundAndNotThrough()
	{
		// Twenty two tiles west as the crow flies, with the castle between: the way is longer.
		int[] path = WalkPath.find(MAP, 3222, 3218, 3200, 3220, 0);
		assertNotNull(path);
		int steps = path.length / 2 - 1;
		assertTrue("steps " + steps, steps > 30 && steps < 60);
	}

	@Test
	public void aTileInsideAWallHasNoWayIn()
	{
		assertTrue(MAP.isBlocked(3234, 3232, 0));
		assertNull(WalkPath.find(MAP, 3222, 3218, 3234, 3232, 0));
	}
}
