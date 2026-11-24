package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WildernessLevelsTest
{
	@Test
	public void theDitchIsTheEdgeOfLevelOne()
	{
		assertEquals(0, WildernessLevels.level(3100, 3519));
		assertEquals(1, WildernessLevels.level(3100, 3520));
		assertEquals(1, WildernessLevels.level(3100, 3527));
		assertEquals(2, WildernessLevels.level(3100, 3528));
	}

	@Test
	public void knownPlacesAreAtTheirKnownLevels()
	{
		// The Wilderness Agility Course is at levels 52 to 56, and the Lava Maze entrance is level 41.
		assertEquals(53, WildernessLevels.level(3004, 3936));
		assertEquals(56, WildernessLevels.level(3000, 3967));
		assertEquals(41, WildernessLevels.level(3030, 3840));
		// Edgeville ditch and Varrock are outside it.
		assertEquals(0, WildernessLevels.level(3100, 3500));
	}

	@Test
	public void theStripHasEdgesAtTheSidesAndTop()
	{
		assertEquals(0, WildernessLevels.level(2943, 3600));
		assertEquals(1 + (3600 - 3520) / 8, WildernessLevels.level(2944, 3600));
		assertEquals(0, WildernessLevels.level(3392, 3600));
		assertEquals(0, WildernessLevels.level(3100, 3968));
		assertEquals(56, WildernessLevels.level(3100, 3967));
	}

	@Test
	public void theDungeonsHaveTheSameLevels()
	{
		assertEquals(1, WildernessLevels.level(3100, 9920));
		assertEquals(33, WildernessLevels.level(3100, 9920 + 32 * 8 + 3));
		assertTrue(WildernessLevels.contains(3100, 10000));
		assertFalse(WildernessLevels.contains(3100, 9919));
	}

	@Test
	public void lineYIsTheSouthEdgeOfTheLevel()
	{
		assertEquals(3520, WildernessLevels.lineY(3520, 1));
		assertEquals(3528, WildernessLevels.lineY(3520, 2));
		assertEquals(3968, WildernessLevels.lineY(3520, 57));
		assertEquals(WildernessLevels.level(3100, WildernessLevels.lineY(3520, 30)), 30);
	}
}
