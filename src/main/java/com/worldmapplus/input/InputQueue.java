package com.worldmapplus.input;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Hands input from the AWT thread to the render thread.
 * <p>
 * Bounded, so a stalled renderer cannot make it grow without end: past the limit the oldest
 * events are dropped, which is harmless for a pointer.
 */
public final class InputQueue
{
	static final int LIMIT = 512;

	private final ConcurrentLinkedQueue<MapInput> queue = new ConcurrentLinkedQueue<>();
	private final AtomicInteger size = new AtomicInteger();

	public void add(MapInput input)
	{
		queue.add(input);
		if (size.incrementAndGet() > LIMIT)
		{
			if (queue.poll() != null)
			{
				size.decrementAndGet();
			}
		}
	}

	/** Takes everything queued so far, oldest first. */
	public List<MapInput> drain()
	{
		// Called every frame, and nearly always empty, so nothing is made then.
		MapInput next = queue.poll();
		if (next == null)
		{
			return Collections.emptyList();
		}
		List<MapInput> events = new ArrayList<>();
		do
		{
			size.decrementAndGet();
			events.add(next);
		}
		while ((next = queue.poll()) != null);
		return events;
	}

	public void clear()
	{
		drain();
	}
}
