package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;

public class MapActionsTest
{
	private final List<PluginMessage> sent = new ArrayList<>();
	private EventBus bus;
	private ExternalOverlays external;

	/** Stands in for the plugin manager, which cannot be built outside the client. */
	private static final class Fixed extends MapActions
	{
		private final Status status;

		Fixed(EventBus bus, ExternalOverlays external, Status status)
		{
			super(bus, external);
			this.status = status;
		}

		@Override
		public Status shortestPathStatus()
		{
			return status;
		}
	}

	@Before
	public void setUp()
	{
		bus = new EventBus();
		external = new ExternalOverlays();
		bus.register(new Listener(sent));
	}

	/** An event bus only calls public methods of public classes. */
	public static final class Listener
	{
		private final List<PluginMessage> into;

		Listener(List<PluginMessage> into)
		{
			this.into = into;
		}

		@Subscribe
		public void onPluginMessage(PluginMessage message)
		{
			into.add(message);
		}
	}

	@Test
	public void aRouteIsAskedOfShortestPathOnlyAndRemembered()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		WorldPoint target = new WorldPoint(3222, 3218, 0);
		assertEquals(MapActions.Status.READY, actions.routeTo(target));

		assertEquals(1, sent.size());
		PluginMessage message = sent.get(0);
		assertEquals("shortestpath", message.getNamespace());
		assertEquals("path", message.getName());
		Map<String, Object> data = message.getData();
		assertSame(target, data.get("target"));
		assertTrue(data.containsKey("start"));
		assertNull(data.get("start"));
		assertSame(target, actions.getRouteTarget());

		// A temporary override asks Shortest Path to send the route's transports. Nothing is saved.
		Object config = data.get("config");
		assertTrue(config instanceof Map);
		assertEquals(Boolean.TRUE, ((Map<?, ?>) config).get("postTransports"));
		assertEquals(1, ((Map<?, ?>) config).size());
	}

	@Test
	public void nothingIsSentWhenShortestPathIsMissingOrOff()
	{
		for (MapActions.Status status : new MapActions.Status[] {MapActions.Status.MISSING, MapActions.Status.DISABLED})
		{
			MapActions actions = new Fixed(bus, external, status);
			assertEquals(status, actions.routeTo(new WorldPoint(3222, 3218, 0)));
			assertNull(actions.getRouteTarget());
		}
		assertTrue(sent.isEmpty());
	}

	@Test
	public void withNoWayToCheckTheRequestIsStillSent()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.UNKNOWN);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		assertEquals(1, sent.size());
	}

	@Test
	public void clearingAsksShortestPathToClearAndForgetsTheTarget()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		actions.clearRoute();
		assertNull(actions.getRouteTarget());
		assertEquals(2, sent.size());
		assertEquals("clear", sent.get(1).getName());
		assertNotNull(sent.get(1).getNamespace());
	}

	@Test
	public void arrivingForgetsTheTargetWithoutMessagingAnyone()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		actions.routeArrived();
		assertNull(actions.getRouteTarget());
		assertEquals(1, sent.size());
	}

	private static PluginMessage path(Object target, Map<String, Object> config)
	{
		Map<String, Object> data = new java.util.HashMap<>();
		data.put("start", null);
		data.put("target", target);
		if (config != null)
		{
			data.put("config", config);
		}
		return new PluginMessage("shortestpath", "path", data);
	}

	@Test
	public void aRouteAnotherPluginAsksForIsMarkedAndAskedForAgainWithTheJumps()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		WorldPoint target = new WorldPoint(2660, 3305, 0);
		Map<String, Object> theirs = new java.util.HashMap<>();
		theirs.put("avoidWilderness", Boolean.TRUE);
		actions.onPluginMessage(path(target, theirs));

		assertSame(target, actions.getRouteTarget());
		assertEquals("asked once more", 1, sent.size());
		Map<String, Object> data = sent.get(0).getData();
		assertSame(target, data.get("target"));
		Map<?, ?> config = (Map<?, ?>) data.get("config");
		assertEquals(Boolean.TRUE, config.get("postTransports"));
		assertEquals("their own setting is kept", Boolean.TRUE, config.get("avoidWilderness"));
		assertEquals("and theirs is not changed", 1, theirs.size());
		assertTrue(data.containsKey(MapActions.OURS));
	}

	@Test
	public void aRouteThatAlreadyAsksForTheJumpsIsNotAskedForAgain()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		Map<String, Object> theirs = new java.util.HashMap<>();
		theirs.put("postTransports", Boolean.TRUE);
		actions.onPluginMessage(path(new WorldPoint(2660, 3305, 0), theirs));
		assertTrue(sent.isEmpty());
		assertNotNull(actions.getRouteTarget());
	}

	@Test
	public void aRequestThisPluginSentIsNotTakenForSomeoneElses()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		assertEquals(1, sent.size());
		PluginMessage ours = sent.get(0);
		actions.onPluginMessage(ours);
		assertEquals("no echo", 1, sent.size());
		// And the asked for again request, which comes back too, ends the exchange.
		Map<String, Object> theirs = new java.util.HashMap<>();
		actions.onPluginMessage(path(new WorldPoint(2660, 3305, 0), theirs));
		assertEquals(2, sent.size());
		actions.onPluginMessage(sent.get(1));
		assertEquals(2, sent.size());
	}

	@Test
	public void packedTargetsAndSetsAreRead()
	{
		int packed = (2660 & 0x7FFF) | ((3305 & 0x7FFF) << 15) | (1 << 30);
		assertEquals(new WorldPoint(2660, 3305, 1), MapActions.targetOf(packed));
		java.util.Set<Object> set = new java.util.LinkedHashSet<>();
		set.add("not a point");
		set.add(new WorldPoint(10, 20, 0));
		assertEquals(new WorldPoint(10, 20, 0), MapActions.targetOf(set));
		assertNull(MapActions.targetOf(-1));
		assertNull(MapActions.targetOf(null));
		assertNull(MapActions.targetOf("text"));
	}

	@Test
	public void aClearFromAnotherPluginClearsHere()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.onPluginMessage(path(new WorldPoint(2660, 3305, 0), null));
		assertNotNull(actions.getRouteTarget());
		actions.onPluginMessage(new PluginMessage("shortestpath", "clear"));
		assertNull(actions.getRouteTarget());
	}

	@Test
	public void otherMessagesAreIgnored()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.onPluginMessage(new PluginMessage("someoneelse", "path", new java.util.HashMap<>()));
		actions.onPluginMessage(new PluginMessage("shortestpath", "transports", new java.util.HashMap<>()));
		actions.onPluginMessage(path(null, null));
		assertNull(actions.getRouteTarget());
		assertTrue(sent.isEmpty());
	}

	@Test
	public void withoutAPluginManagerTheStatusIsUnknown()
	{
		assertEquals(MapActions.Status.UNKNOWN, new MapActions(bus, external).shortestPathStatus());
	}

	@Test
	public void theSecondRequestIsHeldUntilTheOriginalHasBeenDelivered()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		List<Runnable> held = new ArrayList<>();
		actions.setLater(held::add);
		Map<String, Object> theirs = new java.util.HashMap<>();
		theirs.put("avoidWilderness", Boolean.TRUE);
		actions.onPluginMessage(path(new WorldPoint(2660, 3305, 0), theirs));
		assertTrue("nothing is posted from inside the handler", sent.isEmpty());
		assertEquals(1, held.size());
		held.get(0).run();
		assertEquals(1, sent.size());
		assertEquals(Boolean.TRUE, ((Map<?, ?>) sent.get(0).getData().get("config")).get("postTransports"));
	}

	@Test
	public void aHeldRequestIsDroppedIfAnotherMessageOvertakesIt()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		List<Runnable> held = new ArrayList<>();
		actions.setLater(held::add);
		Map<String, Object> theirs = new java.util.HashMap<>();
		theirs.put("avoidWilderness", Boolean.TRUE);
		actions.onPluginMessage(path(new WorldPoint(2660, 3305, 0), theirs));
		actions.onPluginMessage(new PluginMessage("shortestpath", "clear"));
		held.get(0).run();
		assertTrue("a cleared route is not brought back", sent.isEmpty());
		assertNull(actions.getRouteTarget());
	}

	@Test
	public void otherPluginsRequestsAreCountedEvenWhenNotMirrored()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		int before = actions.othersRequests();
		actions.onPluginMessage(path(new WorldPoint(2660, 3305, 0), null), false);
		assertEquals(before + 1, actions.othersRequests());
		assertNull("not marked", actions.getRouteTarget());
		assertTrue("not asked for again", sent.isEmpty());
		actions.onPluginMessage(new PluginMessage("shortestpath", "clear"), false);
		assertEquals(before + 2, actions.othersRequests());
	}

	@Test
	public void ourOwnRequestsAndClearsAreNotCountedAsOthers()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		actions.onPluginMessage(sent.get(0));
		actions.clearRoute();
		actions.onPluginMessage(sent.get(1));
		assertEquals(0, actions.othersRequests());
	}

	@Test
	public void arrivalIsNoticedOnTheSameFloorOnly()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		actions.checkArrived(new WorldPoint(3222, 3218, 1));
		assertNotNull(actions.getRouteTarget());
		actions.checkArrived(new WorldPoint(3240, 3218, 0));
		assertNotNull(actions.getRouteTarget());
		actions.checkArrived(new WorldPoint(3224, 3219, 0));
		assertNull(actions.getRouteTarget());
	}

	@Test
	public void aHeldRequestIsNotSentAfterThePluginHasStopped()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		List<Runnable> held = new ArrayList<>();
		actions.setLater(held::add);
		Map<String, Object> theirs = new java.util.HashMap<>();
		theirs.put("avoidWilderness", Boolean.TRUE);
		actions.onPluginMessage(path(new WorldPoint(2660, 3305, 0), theirs));
		actions.setActive(false);
		held.get(0).run();
		assertTrue(sent.isEmpty());
	}

	private static PluginMessage jumps(WorldPoint from, WorldPoint to, String label)
	{
		Map<String, Object> data = new java.util.HashMap<>();
		data.put("origin", java.util.Collections.singletonList(from));
		data.put("destination", java.util.Collections.singletonList(to));
		data.put("displayInfo", java.util.Collections.singletonList(label));
		data.put("objectInfo", java.util.Collections.singletonList(label));
		return new PluginMessage("shortestpath", "transports", data);
	}

	@Test
	public void aStartChosenByHandIsSentWithTheTargetAndRemembered()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		WorldPoint target = new WorldPoint(3222, 3218, 0);
		WorldPoint start = new WorldPoint(2660, 3305, 0);
		assertEquals("nothing to start without a route", MapActions.Status.UNKNOWN, actions.setStart(start));
		assertTrue(sent.isEmpty());

		actions.routeTo(target);
		sent.clear();
		assertEquals(MapActions.Status.READY, actions.setStart(start));
		Map<String, Object> data = sent.get(0).getData();
		assertSame(start, data.get("start"));
		assertSame(target, data.get("target"));
		assertSame(start, actions.getRouteStart());
		assertSame(target, actions.getRouteTarget());
	}

	@Test
	public void moreTargetsAreSentAsASetAndTheFirstStaysTheMainOne()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		WorldPoint first = new WorldPoint(3222, 3218, 0);
		WorldPoint second = new WorldPoint(2660, 3305, 0);
		assertEquals(MapActions.Status.UNKNOWN, actions.addTarget(second));
		actions.routeTo(first);
		sent.clear();
		actions.addTarget(second);
		actions.addTarget(second);
		assertEquals("a target already there is not added again", 1, sent.size());
		Object target = sent.get(0).getData().get("target");
		assertTrue(target instanceof java.util.Set);
		assertEquals(2, ((java.util.Set<?>) target).size());
		assertEquals(2, actions.getRouteTargets().size());
		assertSame(first, actions.getRouteTarget());
	}

	@Test
	public void settingANewTargetKeepsTheStartButAskingForARouteStartsFromThePlayer()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		WorldPoint start = new WorldPoint(2660, 3305, 0);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		actions.setStart(start);
		sent.clear();
		WorldPoint other = new WorldPoint(3100, 3100, 0);
		actions.retarget(other);
		assertSame(start, sent.get(0).getData().get("start"));
		assertSame(other, sent.get(0).getData().get("target"));
		assertEquals(1, actions.getRouteTargets().size());

		actions.routeTo(other);
		assertNull(sent.get(1).getData().get("start"));
		assertNull(actions.getRouteStart());
	}

	@Test
	public void aRouteFromAChosenStartKeepsItsFirstJumpsWhenShortestPathStartsAgainFromThePlayer()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		actions.setStart(new WorldPoint(2660, 3305, 0));
		external.onPluginMessage(jumps(new WorldPoint(2660, 3300, 0), new WorldPoint(2700, 3300, 0), "Boat"));
		assertEquals(1, external.hops().size());
		assertEquals("Boat", external.hops().get(0).getLabel());

		// The player walks off the route, Shortest Path starts again from them and sends its jumps for that.
		external.onPluginMessage(jumps(new WorldPoint(3000, 3000, 0), new WorldPoint(3010, 3010, 0), "Lever"));
		assertEquals("Boat", external.hops().get(0).getLabel());

		// A new route from the player follows Shortest Path again.
		actions.routeTo(new WorldPoint(3100, 3100, 0));
		external.onPluginMessage(jumps(new WorldPoint(3000, 3000, 0), new WorldPoint(3010, 3010, 0), "Lever"));
		assertEquals("Lever", external.hops().get(0).getLabel());
	}

	@Test
	public void aRouteAnotherPluginStartsElsewhereThanThePlayerIsFollowedFromThere()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		WorldPoint start = new WorldPoint(2660, 3305, 0);
		Map<String, Object> data = new java.util.HashMap<>();
		data.put("start", start);
		java.util.Set<Object> targets = new java.util.LinkedHashSet<>();
		targets.add(new WorldPoint(3222, 3218, 0));
		targets.add(WorldPointKey.packed(3100, 3100, 0));
		data.put("target", targets);
		actions.onPluginMessage(new PluginMessage("shortestpath", "path", data));
		assertEquals(start, actions.getRouteStart());
		assertEquals(2, actions.getRouteTargets().size());
		assertEquals(new WorldPoint(3100, 3100, 0), actions.getRouteTargets().get(1));

		actions.onPluginMessage(path(new WorldPoint(3000, 3000, 0), null));
		assertNull("a request that names no start starts from the player", actions.getRouteStart());
	}

	@Test
	public void arrivingAtAnyTargetEndsTheRoute()
	{
		MapActions actions = new Fixed(bus, external, MapActions.Status.READY);
		actions.routeTo(new WorldPoint(3222, 3218, 0));
		actions.addTarget(new WorldPoint(2660, 3305, 0));
		actions.setStart(new WorldPoint(3000, 3000, 0));
		actions.checkArrived(new WorldPoint(2661, 3305, 0));
		assertTrue(actions.getRouteTargets().isEmpty());
		assertNull(actions.getRouteStart());
	}

	/** Shortest Path's own packed number for a tile. */
	private static final class WorldPointKey
	{
		static Integer packed(int x, int y, int plane)
		{
			return x | (y << 15) | (plane << 30);
		}
	}
}
