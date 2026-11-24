package com.worldmapplus.pins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class RouteStoreTest
{
	private static final Gson GSON = new Gson();

	private static final class Memory implements PinStore.Storage
	{
		private String value;
		private int writes;

		@Override
		public String read()
		{
			return value;
		}

		@Override
		public void write(String json)
		{
			value = json;
			writes++;
		}
	}

	private final Memory pinMemory = new Memory();
	private final Memory routeMemory = new Memory();
	private PinStore pins;
	private RouteStore routes;

	@Before
	public void setUp()
	{
		pins = new PinStore(pinMemory, GSON);
		routes = new RouteStore(routeMemory, GSON, pins);
	}

	@Test
	public void startsEmptyAndSavesWhatIsCreated()
	{
		assertTrue(routes.all().isEmpty());
		SavedRoute route = routes.create("Herb run", Arrays.asList(1L, 2L, 3L), true);
		assertNotNull(route);
		assertEquals("Herb run", route.getName());
		assertTrue(route.isLoop());
		assertEquals(1, routeMemory.writes);

		RouteStore again = new RouteStore(routeMemory, GSON, pins);
		assertEquals(1, again.all().size());
		assertEquals(Arrays.asList(1L, 2L, 3L), again.all().get(0).getStops());
	}

	@Test
	public void updatingChangesNameStopsAndLoop()
	{
		SavedRoute route = routes.create("A", Arrays.asList(1L), false);
		assertTrue(routes.update(route.getId(), "  B   route ", Arrays.asList(3L, 2L), true));
		SavedRoute changed = routes.get(route.getId());
		assertEquals("B route", changed.getName());
		assertEquals(Arrays.asList(3L, 2L), changed.getStops());
		assertTrue(changed.isLoop());
		assertFalse(routes.update(999, "x", new ArrayList<>(), false));
	}

	@Test
	public void aRemovedPlaceLeavesEveryRoute()
	{
		Pin one = pins.add("One", 0, 3200, 3200);
		Pin two = pins.add("Two", 0, 3210, 3210);
		SavedRoute route = routes.create("R", Arrays.asList(one.getId(), two.getId()), false);
		pins.remove(one.getId());
		assertEquals(Arrays.asList(two.getId()), routes.get(route.getId()).getStops());
		assertTrue(routeMemory.value.contains(String.valueOf(two.getId())));
	}

	@Test
	public void oneRouteIsFollowedAtATimeAndOnlyInMemory()
	{
		SavedRoute a = routes.create("A", Arrays.asList(1L), false);
		SavedRoute b = routes.create("B", Arrays.asList(1L), false);
		int writes = routeMemory.writes;
		routes.setActive(a.getId());
		assertEquals(a.getId(), routes.active().getId());
		routes.setActive(b.getId());
		assertEquals(b.getId(), routes.active().getId());
		assertEquals("following a route is not saved", writes, routeMemory.writes);
		routes.stopAll();
		assertNull(routes.active());

		RouteStore again = new RouteStore(routeMemory, GSON, pins);
		assertNull("a restart follows nothing", again.active());
	}

	@Test
	public void removingTheFollowedRouteStopsIt()
	{
		SavedRoute a = routes.create("A", Arrays.asList(1L), false);
		routes.setActive(a.getId());
		assertTrue(routes.remove(a.getId()));
		assertNull(routes.active());
	}

	@Test
	public void theDrawnRoutesAreTheFollowedAndTheLookedAt()
	{
		SavedRoute a = routes.create("A", Arrays.asList(1L), false);
		SavedRoute b = routes.create("B", Arrays.asList(1L), false);
		assertTrue(routes.drawn().isEmpty());
		routes.setActive(a.getId());
		routes.setHighlighted(b.getId());
		assertEquals(2, routes.drawn().size());
		routes.setHighlighted(a.getId());
		assertEquals("the same route is drawn once", 1, routes.drawn().size());
		routes.setHighlighted(0);
		assertEquals(1, routes.drawn().size());
	}

	@Test
	public void listsAreCappedAndDamagedDataGivesNothing()
	{
		List<Long> many = new ArrayList<>();
		for (long i = 0; i < 100; i++)
		{
			many.add(i);
		}
		assertEquals(RouteStore.MAX_STOPS, routes.create("Long", many, false).getStops().size());
		for (int i = 0; i < RouteStore.MAX_ROUTES + 5; i++)
		{
			routes.create("R" + i, new ArrayList<>(), false);
		}
		assertEquals(RouteStore.MAX_ROUTES, routes.all().size());
		assertNull(routes.create("one too many", new ArrayList<>(), false));

		routeMemory.value = "{not json";
		assertTrue(new RouteStore(routeMemory, GSON, pins).all().isEmpty());
		routeMemory.value = "{\"routes\":[null,{\"id\":4,\"name\":\"  \",\"stops\":null}]}";
		List<SavedRoute> read = new RouteStore(routeMemory, GSON, pins).all();
		assertEquals(1, read.size());
		assertEquals("Route", read.get(0).getName());
		assertTrue(read.get(0).getStops().isEmpty());
	}

	@Test
	public void theVersionChangesWithTheListAndWhatIsFollowedButNotWhatIsLookedAt()
	{
		int v = routes.getVersion();
		SavedRoute a = routes.create("A", Arrays.asList(1L), false);
		assertTrue(routes.getVersion() > v);
		v = routes.getVersion();
		routes.setActive(a.getId());
		assertTrue(routes.getVersion() > v);
		v = routes.getVersion();
		routes.setActive(a.getId());
		assertEquals("no change, no new version", v, routes.getVersion());
		routes.setHighlighted(a.getId());
		assertEquals("looking at a route does not rebuild lists", v, routes.getVersion());
	}

	@Test
	public void namesCannotCarryMarkup()
	{
		SavedRoute route = routes.create("<html><img src='http://example.invalid/x.png'>Run", new ArrayList<>(), false);
		assertEquals("htmlimg src='http://example.invalid/x.png'Run", route.getName());
		assertEquals("Route", routes.create("<<>>", new ArrayList<>(), false).getName());
	}

	@Test
	public void routeIdsAreNotReusedAndBadRoutesAreLeftOut()
	{
		SavedRoute one = routes.create("One", Arrays.asList(1L), false);
		routes.remove(one.getId());
		assertTrue(routes.create("Two", Arrays.asList(1L), false).getId() > one.getId());

		routeMemory.value = "{\"routes\":[{\"id\":0,\"name\":\"zero\"},{\"id\":3,\"name\":\"ok\"},{\"id\":3,\"name\":\"again\"}]}";
		List<SavedRoute> read = new RouteStore(routeMemory, GSON, pins).all();
		assertEquals(1, read.size());
		assertEquals("ok", read.get(0).getName());
	}

	@Test
	public void aChangeMadeBySomeoneElseIsTakenInButOurOwnWritesAreNot()
	{
		routes.create("Mine", Arrays.asList(1L), false);
		assertFalse(routes.reloadIfChanged());
		routeMemory.value = "{\"routes\":[{\"id\":8,\"name\":\"Other profile\",\"stops\":[2]}]}";
		assertTrue(routes.reloadIfChanged());
		assertEquals("Other profile", routes.all().get(0).getName());
		assertTrue(routes.create("Next", Arrays.asList(1L), false).getId() > 8);
	}
}
