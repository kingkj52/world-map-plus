package com.worldmapplus.layers;

import java.util.*;

/**
 * Which maps lead to which, through doorways.
 * <p>
 * Used to say where to go to reach something that is on a map other than the one in view. If a
 * monster lives in a dungeon, the doorways on the surface that lead there are worth marking, and if
 * the dungeon is reached through another dungeon, the first doorway of that chain is.
 */
public final class DoorwayGraph
{
	private final Map<Integer, List<Hotspot>> outgoing = new HashMap<>();

	public DoorwayGraph(List<Hotspot> hotspots)
	{
		for (Hotspot hotspot : hotspots)
		{
			if (hotspot.getKind() == Hotspot.Kind.DOORWAY && hotspot.getTarget() != null)
			{
				outgoing.computeIfAbsent(hotspot.getMapId(), k -> new ArrayList<>()).add(hotspot);
			}
		}
	}

	/**
	 * The doorways on {@code fromMap} that start the shortest way to {@code targetMap}. Empty when
	 * the maps are the same or no chain of doorways connects them.
	 */
	public List<Hotspot> firstSteps(int fromMap, int targetMap)
	{
		if (fromMap == targetMap)
		{
			return Collections.emptyList();
		}
		List<Hotspot> starts = outgoing.getOrDefault(fromMap, Collections.emptyList());
		if (starts.isEmpty())
		{
			return Collections.emptyList();
		}

		// Breadth first from the target's side would need incoming edges, so find, for each first
		// doorway, how many steps the rest of the way takes and keep the doorways tied for fewest.
		int best = Integer.MAX_VALUE;
		List<Hotspot> result = new ArrayList<>();
		for (Hotspot start : starts)
		{
			int steps = distance(start.getTarget().getMapId(), targetMap, fromMap);
			if (steps < 0)
			{
				continue;
			}
			if (steps < best)
			{
				best = steps;
				result.clear();
			}
			if (steps == best)
			{
				result.add(start);
			}
		}
		return result;
	}

	/** Doorways to cross from one map to another, or -1. {@code avoid} is a map not to pass back through. */
	private int distance(int from, int to, int avoid)
	{
		if (from == to)
		{
			return 0;
		}
		Deque<int[]> queue = new ArrayDeque<>();
		Set<Integer> seen = new HashSet<>();
		queue.add(new int[] {from, 0});
		seen.add(from);
		seen.add(avoid);
		while (!queue.isEmpty())
		{
			int[] current = queue.poll();
			for (Hotspot door : outgoing.getOrDefault(current[0], Collections.emptyList()))
			{
				int next = door.getTarget().getMapId();
				if (next == to)
				{
					return current[1] + 1;
				}
				if (seen.add(next))
				{
					queue.add(new int[] {next, current[1] + 1});
				}
			}
		}
		return -1;
	}
}
