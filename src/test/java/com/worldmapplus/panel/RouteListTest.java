package com.worldmapplus.panel;

import static org.junit.Assert.assertEquals;

import com.worldmapplus.pins.SavedRoute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.Test;

public class RouteListTest
{
	private static final LongFunction<String> STOPS = id -> id == 1 ? "Seed shop" : id == 2 ? "Bank" : "";

	private static List<SavedRoute> sample()
	{
		return Arrays.asList(
			new SavedRoute(1, "Herb run", Arrays.asList(1L, 2L, 1L), true),
			new SavedRoute(2, "banking", Arrays.asList(2L), false),
			new SavedRoute(3, "Another", Arrays.asList(1L, 2L), false));
	}

	private static List<String> names(List<SavedRoute> routes)
	{
		List<String> names = new ArrayList<>();
		for (SavedRoute route : routes)
		{
			names.add(route.getName());
		}
		return names;
	}

	@Test
	public void sortsByNameNewestOldestAndStopCount()
	{
		assertEquals(Arrays.asList("Another", "banking", "Herb run"),
			names(RouteList.apply(sample(), "", false, RouteList.Sort.NAME, STOPS)));
		assertEquals(Arrays.asList("Another", "banking", "Herb run"),
			names(RouteList.apply(sample(), "", false, RouteList.Sort.NEWEST, STOPS)));
		assertEquals(Arrays.asList("Herb run", "banking", "Another"),
			names(RouteList.apply(sample(), "", false, RouteList.Sort.OLDEST, STOPS)));
		assertEquals(Arrays.asList("Herb run", "Another", "banking"),
			names(RouteList.apply(sample(), "", false, RouteList.Sort.MOST_STOPS, STOPS)));
		assertEquals(Arrays.asList("banking", "Another", "Herb run"),
			names(RouteList.apply(sample(), "", false, RouteList.Sort.FEWEST_STOPS, STOPS)));
	}

	@Test
	public void loopsCanBeFirstOrTheOnlyOnes()
	{
		assertEquals("Herb run", names(RouteList.apply(sample(), "", false, RouteList.Sort.LOOPS_FIRST, STOPS)).get(0));
		assertEquals(Arrays.asList("Herb run"), names(RouteList.apply(sample(), "", true, RouteList.Sort.NAME, STOPS)));
	}

	@Test
	public void theTextMatchesTheNameOrAStopsName()
	{
		assertEquals(Arrays.asList("Another", "Herb run"),
			names(RouteList.apply(sample(), "seed", false, RouteList.Sort.NAME, STOPS)));
		assertEquals(Arrays.asList("banking"), names(RouteList.apply(sample(), "BANKING", false, RouteList.Sort.NAME, STOPS)));
		assertEquals(Arrays.asList("Herb run"), names(RouteList.apply(sample(), "herb seed", false, RouteList.Sort.NAME, STOPS)));
	}
}
