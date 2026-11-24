package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DoorwayGraphTest
{
	/** A one way doorway from one map to another. */
	private static Hotspot door(int fromMap, double x, int toMap)
	{
		return new Hotspot(fromMap, x, 0, 10, HoverCard.builder("Door " + fromMap + ">" + toMap).build(),
			new Hotspot.Target(toMap, 0, 0), new ArrayList<>(), Hotspot.Kind.DOORWAY);
	}

	private static Hotspot shop(int map)
	{
		return new Hotspot(map, 0, 0, 10, HoverCard.builder("Shop").build(), null, new ArrayList<>(), Hotspot.Kind.SHOP);
	}

	@Test
	public void aDirectDoorwayIsTheFirstStep()
	{
		List<Hotspot> all = new ArrayList<>();
		all.add(door(0, 100, 6));
		all.add(door(6, 50, 0));
		DoorwayGraph graph = new DoorwayGraph(all);
		List<Hotspot> steps = graph.firstSteps(0, 6);
		assertEquals(1, steps.size());
		assertEquals(100, steps.get(0).getX(), 1e-9);
	}

	@Test
	public void everyDoorwayIntoTheSameMapIsMarked()
	{
		List<Hotspot> all = new ArrayList<>();
		all.add(door(0, 100, 6));
		all.add(door(0, 200, 6));
		all.add(door(0, 300, 7));
		assertEquals(2, new DoorwayGraph(all).firstSteps(0, 6).size());
	}

	@Test
	public void aChainOfMapsMarksTheFirstDoorway()
	{
		List<Hotspot> all = new ArrayList<>();
		all.add(door(0, 100, 20));
		all.add(door(20, 5, 21));
		all.add(door(0, 400, 30));
		DoorwayGraph graph = new DoorwayGraph(all);
		List<Hotspot> steps = graph.firstSteps(0, 21);
		assertEquals(1, steps.size());
		assertEquals(100, steps.get(0).getX(), 1e-9);
	}

	@Test
	public void theShorterWayWins()
	{
		List<Hotspot> all = new ArrayList<>();
		all.add(door(0, 100, 5));
		all.add(door(5, 1, 6));
		all.add(door(6, 1, 9));
		all.add(door(0, 200, 9));
		List<Hotspot> steps = new DoorwayGraph(all).firstSteps(0, 9);
		assertEquals(1, steps.size());
		assertEquals(200, steps.get(0).getX(), 1e-9);
	}

	@Test
	public void unconnectedOrIdenticalMapsGiveNothing()
	{
		List<Hotspot> all = new ArrayList<>();
		all.add(door(0, 100, 6));
		all.add(shop(0));
		DoorwayGraph graph = new DoorwayGraph(all);
		assertTrue(graph.firstSteps(0, 0).isEmpty());
		assertTrue(graph.firstSteps(0, 99).isEmpty());
		assertTrue(graph.firstSteps(6, 0).isEmpty());
	}

	@Test
	public void loopsDoNotHang()
	{
		List<Hotspot> all = new ArrayList<>();
		all.add(door(0, 1, 1));
		all.add(door(1, 1, 2));
		all.add(door(2, 1, 1));
		all.add(door(2, 2, 0));
		assertTrue(new DoorwayGraph(all).firstSteps(0, 99).isEmpty());
	}
}
