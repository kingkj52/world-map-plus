package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class NpcFilterTest
{
	@Test
	public void anEmptyFilterShowsEveryNamedNpc()
	{
		NpcFilter filter = NpcFilter.parse("");
		assertTrue(filter.matches("Banker"));
		assertTrue(filter.matches("Cow"));
		assertFalse(filter.matches(""));
		assertFalse(filter.matches(null));
	}

	@Test
	public void plainWordsAreAnyOfAndCaseInsensitive()
	{
		NpcFilter filter = NpcFilter.parse("banker, GUARD");
		assertTrue(filter.matches("Banker"));
		assertTrue(filter.matches("Falador guard"));
		assertFalse(filter.matches("Cow"));
	}

	@Test
	public void aBangExcludes()
	{
		NpcFilter filter = NpcFilter.parse("!chicken");
		assertFalse(filter.matches("Chicken"));
		assertTrue(filter.matches("Cow"));
	}

	@Test
	public void exclusionsBeatInclusions()
	{
		NpcFilter filter = NpcFilter.parse("guard, !jail");
		assertTrue(filter.matches("Guard"));
		assertFalse(filter.matches("Jail guard"));
	}

	@Test
	public void emptyPiecesAreIgnored()
	{
		NpcFilter filter = NpcFilter.parse(" , ,cow,, !");
		assertTrue(filter.matches("Cow"));
		assertFalse(filter.matches("Goat"));
	}

	@Test
	public void theRadiusIsCappedToTheMinimapsReach()
	{
		assertEquals(NpcLayer.MAX_RADIUS, NpcLayer.radius(500));
		assertEquals(1, NpcLayer.radius(-4));
		assertEquals(12, NpcLayer.radius(12));
	}

	@Test
	public void nearUsesASquareRadiusOnTheSameFloor()
	{
		WorldPoint player = new WorldPoint(3200, 3200, 0);
		assertTrue(NpcLayer.near(new WorldPoint(3216, 3184, 0), player, 16));
		assertFalse(NpcLayer.near(new WorldPoint(3217, 3200, 0), player, 16));
		assertFalse(NpcLayer.near(new WorldPoint(3200, 3200, 1), player, 16));
	}
}
