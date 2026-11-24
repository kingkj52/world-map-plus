package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.RouteHop;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

public class RoutePlanTest
{
	/** Hops as Shortest Path would send them. */
	private static List<RouteHop> hops(Object[][] rows)
	{
		ExternalOverlays overlays = new ExternalOverlays();
		List<WorldPoint> from = new ArrayList<>();
		List<WorldPoint> to = new ArrayList<>();
		List<String> info = new ArrayList<>();
		List<String> objects = new ArrayList<>();
		for (Object[] row : rows)
		{
			from.add((WorldPoint) row[0]);
			to.add((WorldPoint) row[1]);
			info.add((String) row[2]);
			objects.add((String) row[3]);
		}
		Map<String, Object> data = new HashMap<>();
		data.put("origin", from);
		data.put("destination", to);
		data.put("displayInfo", info);
		data.put("objectInfo", objects);
		overlays.onPluginMessage(new PluginMessage("shortestpath", "transports", data));
		return overlays.hops();
	}

	@Test
	public void aRouteViaAnEquippedCloakNamesTheCloak()
	{
		List<RouteHop> jumps = hops(new Object[][] {
			{new WorldPoint(3222, 3218, 0), new WorldPoint(2607, 3221, 0), "Ardougne cloak: Kandarin Monastery", null}});
		RoutePlan plan = RoutePlan.build(new double[] {3222.5, 3218.5}, jumps, new double[] {2620.5, 3230.5});
		List<RoutePlan.Step> steps = plan.getSteps();
		assertEquals("no walk before a teleport taken where you stand", 2, steps.size());
		assertFalse(steps.get(0).isWalk());
		assertEquals("Ardougne cloak: Kandarin Monastery", steps.get(0).getText());
		assertTrue(steps.get(1).isWalk());
		assertEquals("Then walk about 13 tiles to the target", steps.get(1).getText());
		assertEquals(13, steps.get(1).getTiles());
	}

	@Test
	public void walksFillTheGapsBetweenJumps()
	{
		List<RouteHop> jumps = hops(new Object[][] {
			{new WorldPoint(3300, 3200, 0), new WorldPoint(2500, 3200, 0), "Charter ship", null},
			{new WorldPoint(2510, 3200, 0), new WorldPoint(1500, 3500, 0), "Fairy ring: CIS", null}});
		RoutePlan plan = RoutePlan.build(new double[] {3200.5, 3200.5}, jumps, new double[] {1520.5, 3510.5});
		List<String> text = new ArrayList<>();
		for (RoutePlan.Step step : plan.getSteps())
		{
			text.add(step.getText());
		}
		assertEquals(java.util.Arrays.asList("Walk about 100 tiles", "Charter ship", "Walk about 10 tiles",
			"Fairy ring: CIS", "Then walk about 20 tiles to the target"), text);
	}

	@Test
	public void aRouteWithNoJumpsIsOneWalk()
	{
		RoutePlan plan = RoutePlan.build(new double[] {3200.5, 3200.5}, new ArrayList<>(), new double[] {3250.5, 3230.5});
		assertEquals(1, plan.getSteps().size());
		assertEquals("Walk about 50 tiles to the target", plan.getSteps().get(0).getText());
	}

	@Test
	public void nothingKnownGivesNothing()
	{
		assertTrue(RoutePlan.build(null, new ArrayList<>(), null).isEmpty());
		assertTrue(RoutePlan.build(new double[] {1, 1}, new ArrayList<>(), null).isEmpty());
		assertTrue(RoutePlan.empty().isEmpty());
	}

	@Test
	public void jumpsAreListedEvenWhenNothingElseIsKnown()
	{
		List<RouteHop> jumps = hops(new Object[][] {
			{new WorldPoint(3300, 3200, 0), new WorldPoint(2500, 3200, 0), "Charter ship", null}});
		RoutePlan plan = RoutePlan.build(null, jumps, null);
		assertEquals(1, plan.getSteps().size());
		assertEquals("Charter ship", plan.getSteps().get(0).getText());
	}

	@Test
	public void aJumpWithNoDisplayTextIsNamedFromItsObject()
	{
		List<RouteHop> jumps = hops(new Object[][] {
			{new WorldPoint(3300, 3200, 0), new WorldPoint(2500, 3200, 0), null, "Enter Saradomin Portal 4387"},
			{new WorldPoint(2500, 3200, 0), new WorldPoint(2400, 3200, 0), null, null}});
		assertEquals("Enter Saradomin Portal", jumps.get(0).getLabel());
		assertEquals("Transport", jumps.get(1).getLabel());
	}

	@Test
	public void aWalkOfAFewTilesIsNotAStep()
	{
		List<RouteHop> jumps = hops(new Object[][] {
			{new WorldPoint(3202, 3200, 0), new WorldPoint(2500, 3200, 0), "Boat", null}});
		RoutePlan plan = RoutePlan.build(new double[] {3200.5, 3200.5}, jumps, new double[] {2501.5, 3200.5});
		assertEquals(1, plan.getSteps().size());
	}
}
