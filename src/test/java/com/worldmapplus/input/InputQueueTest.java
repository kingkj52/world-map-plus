package com.worldmapplus.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class InputQueueTest
{
	@Test
	public void anEmptyQueueHandsOutTheSameEmptyListEveryFrame()
	{
		InputQueue queue = new InputQueue();
		List<MapInput> first = queue.drain();
		assertTrue(first.isEmpty());
		assertSame(first, queue.drain());
	}

	@Test
	public void eventsComeOutOldestFirstAndOnlyOnce()
	{
		InputQueue queue = new InputQueue();
		queue.add(MapInput.wheel(1, 2, 3, 100, false));
		queue.add(MapInput.wheel(4, 5, 6, 101, false));
		List<MapInput> events = queue.drain();
		assertEquals(2, events.size());
		assertEquals(1, events.get(0).x);
		assertEquals(4, events.get(1).x);
		assertTrue(queue.drain().isEmpty());
	}

	@Test
	public void pastTheLimitTheOldestAreDropped()
	{
		InputQueue queue = new InputQueue();
		for (int i = 0; i < InputQueue.LIMIT + 10; i++)
		{
			queue.add(MapInput.wheel(i, 0, 1, i, false));
		}
		List<MapInput> events = queue.drain();
		assertEquals(InputQueue.LIMIT, events.size());
		assertEquals(10, events.get(0).x);
	}
}
