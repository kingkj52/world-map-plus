package com.worldmapplus.pins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class RouteRunnerTest
{
	private static final Gson GSON = new Gson();

	private static final class Memory implements PinStore.Storage
	{
		private String value;

		@Override
		public String read()
		{
			return value;
		}

		@Override
		public void write(String json)
		{
			value = json;
		}
	}

	private final List<String> asked = new ArrayList<>();
	private int cleared;
	private int others;
	private boolean available = true;
	private PinStore pins;
	private RouteStore routes;
	private RouteRunner runner;
	private Pin a;
	private Pin b;
	private Pin c;

	@Before
	public void setUp()
	{
		pins = new PinStore(new Memory(), GSON);
		routes = new RouteStore(new Memory(), GSON, pins);
		a = pins.add("A", 0, 3200, 3200);
		b = pins.add("B", 0, 3300, 3300);
		c = pins.add("C", 0, 3400, 3200);
		runner = new RouteRunner(routes, pins, new RouteRunner.Navigator()
		{
			@Override
			public boolean routeTo(Pin stop)
			{
				asked.add(stop.getName());
				return available;
			}

			@Override
			public void clear()
			{
				cleared++;
			}

			@Override
			public int othersRequests()
			{
				return others;
			}
		});
	}

	private SavedRoute start(boolean loop)
	{
		SavedRoute route = routes.create("R", Arrays.asList(a.getId(), b.getId(), c.getId()), loop);
		routes.setActive(route.getId());
		return route;
	}

	@Test
	public void doesNothingWhileNoRouteIsFollowed()
	{
		runner.tick(3200, 3200);
		assertTrue(asked.isEmpty());
		assertEquals(-1, runner.getIndex());
	}

	@Test
	public void asksForTheFirstStopThenWaitsUntilThePlayerIsThere()
	{
		start(false);
		runner.tick(3000, 3000);
		assertEquals(Arrays.asList("A"), asked);
		runner.tick(3100, 3100);
		runner.tick(3190, 3190);
		assertEquals("still walking, nothing more asked", 1, asked.size());
		runner.tick(3198, 3202);
		runner.tick(3198, 3202);
		assertEquals(Arrays.asList("A", "B"), asked);
		assertEquals(1, runner.getIndex());
	}

	@Test
	public void aRouteThatIsNotALoopEndsAtTheLastStop()
	{
		SavedRoute route = start(false);
		runner.tick(0, 0);
		runner.tick(3200, 3200);
		runner.tick(0, 0);
		runner.tick(3300, 3300);
		runner.tick(0, 0);
		runner.tick(3400, 3200);
		assertEquals(Arrays.asList("A", "B", "C"), asked);
		assertNull("it stopped", routes.active());
		assertEquals(1, cleared);
		assertEquals(-1, runner.getIndex());
		assertNotNull(routes.get(route.getId()));
	}

	@Test
	public void aLoopGoesRoundAgain()
	{
		start(true);
		runner.tick(0, 0);
		runner.tick(3200, 3200);
		runner.tick(0, 0);
		runner.tick(3300, 3300);
		runner.tick(0, 0);
		runner.tick(3400, 3200);
		runner.tick(0, 0);
		assertEquals(Arrays.asList("A", "B", "C", "A"), asked);
		assertTrue(routes.active() != null);
		assertEquals(0, cleared);
	}

	@Test
	public void anotherPluginsRouteStopsTheRunnerAndIsLeftAlone()
	{
		start(true);
		runner.tick(0, 0);
		others++;
		runner.tick(0, 0);
		assertNull(routes.active());
		assertEquals("it did not clear their route", 0, cleared);
		assertEquals(1, asked.size());
	}

	@Test
	public void startingAnotherRouteStartsItFromItsFirstStop()
	{
		start(false);
		runner.tick(0, 0);
		runner.tick(3200, 3200);
		SavedRoute two = routes.create("Two", Arrays.asList(c.getId(), a.getId()), false);
		routes.setActive(two.getId());
		runner.tick(0, 0);
		assertEquals("C", asked.get(asked.size() - 1));
	}

	@Test
	public void placesThatAreGoneAreSkippedAndAnEmptyRouteStops()
	{
		start(false);
		pins.remove(a.getId());
		pins.remove(b.getId());
		runner.tick(0, 0);
		assertEquals(Arrays.asList("C"), asked);
		pins.remove(c.getId());
		runner.tick(0, 0);
		assertNull(routes.active());
	}

	@Test
	public void anUnknownPlayerPositionWaitsRatherThanSkipping()
	{
		start(false);
		runner.tick(Double.NaN, Double.NaN);
		runner.tick(Double.NaN, Double.NaN);
		assertEquals(1, asked.size());
		assertEquals(0, runner.getIndex());
	}

	@Test
	public void aRouteStopsWithAReasonWhenShortestPathCannotBeAsked()
	{
		available = false;
		SavedRoute route = start(false);
		runner.tick(0, 0);
		assertNull("it did not wait for an answer that cannot come", routes.active());
		assertEquals(-1, runner.getIndex());
		assertTrue(runner.takeProblem().contains(route.getName()));
		assertNull("the reason is given once", runner.takeProblem());
	}

	@Test
	public void aStopRemovedWhileBeingWalkedToLetsTheNextOneBeAsked()
	{
		start(false);
		runner.tick(0, 0);
		assertEquals(Arrays.asList("A"), asked);
		pins.remove(a.getId());
		runner.tick(0, 0);
		assertEquals("B is now first and is asked for", Arrays.asList("A", "B"), asked);
	}

	@Test
	public void editingTheRouteKeepsFollowingTheSameStop()
	{
		SavedRoute route = start(false);
		runner.tick(0, 0);
		runner.tick(3200, 3200);
		runner.tick(0, 0);
		assertEquals(Arrays.asList("A", "B"), asked);
		assertEquals(1, runner.getIndex());
		// B moves to the front of the list while it is being walked to.
		routes.update(route.getId(), "R", Arrays.asList(b.getId(), a.getId(), c.getId()), false);
		runner.tick(0, 0);
		assertEquals("still walking to B, nothing new asked", 2, asked.size());
		assertEquals(0, runner.getIndex());
		runner.tick(3300, 3300);
		runner.tick(0, 0);
		assertEquals("then on to A, the next stop after B", "A", asked.get(asked.size() - 1));
	}
}
