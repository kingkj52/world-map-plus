package com.worldmapplus.walk;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.annotation.*;
import lombok.*;

/**
 * Finds walking paths without holding up the frame that asks for one.
 * <p>
 * A frame asks, every frame, for the way between two points of a journey. The first ask starts a search on the shared background
 * executor and gets nothing back; later asks get the answer once it is ready. One answer is kept per journey: while a new search for
 * it is under way, the last answer for the same goal is handed back, so a line that follows the player does not blink out at every
 * step, and when the player has simply walked along the line, the rest of the line is the answer and no search is needed at all.
 * <p>
 * Searches are cheap, but the collision data they read is several megabytes, so it is read on the first search and let go of with
 * {@link #release()}.
 */
public final class WalkPlanner
{
	/** The answer for a walk with no way found, or none that this search can see. Never null, always empty. */
	public static final int[] NONE = new int[0];

	private static final int KEPT = 96;
	private static final int MAX_PENDING = 4;

	/** The last way found for a journey, with where it was from and where it was going. */
	@RequiredArgsConstructor
	private static final class Walk
	{
		final String key;
		final String goal;
		final int[] path;
	}

	private final Executor executor;
	private final Supplier<CollisionMap> source;
	private final Object loadLock = new Object();
	private volatile CollisionMap collision;

	/** Goes up when everything is let go of, so a search that was under way can tell its answer is not wanted. */
	private volatile int generation;

	private final Map<String, Walk> walks = new HashMap<>();
	private final Set<String> pending = new HashSet<>();

	public WalkPlanner(Executor executor)
	{
		this(executor, CollisionMap::load);
	}

	WalkPlanner(Executor executor, Supplier<CollisionMap> source)
	{
		this.executor = executor;
		this.source = source;
	}

	/**
	 * The way between two tiles.
	 *
	 * @param route names the journey the walk belongs to, so an older answer for it can stand in while a new one is being worked out
	 * @return tile pairs, x then y, from the start to the goal; {@link #NONE} when no way is known; or null when nothing is known yet
	 * and a search has been started
	 */
	@Nullable
	public synchronized int[] lookup(String route, int startX, int startY, int goalX, int goalY, int plane)
	{
		String goal = goalX + "," + goalY + "@" + plane;
		String key = startX + "," + startY + ">" + goal;
		Walk last = walks.get(route);
		boolean sameGoal = last != null && last.goal.equals(goal);
		if (sameGoal && last.key.equals(key))
		{
			return last.path;
		}
		if (sameGoal)
		{
			// Walking along the line is not a new question: what is left of the line is the answer.
			for (int i = 0; i + 1 < last.path.length; i += 2)
			{
				if (last.path[i] == startX && last.path[i + 1] == startY)
				{
					int[] rest = Arrays.copyOfRange(last.path, i, last.path.length);
					walks.put(route, new Walk(key, goal, rest));
					return rest;
				}
			}
		}
		if (!pending.contains(key) && pending.size() < MAX_PENDING)
		{
			pending.add(key);
			int generationNow = generation;
			executor.execute(() -> search(route, key, goal, generationNow, startX, startY, goalX, goalY, plane));
		}
		return sameGoal && last.path != NONE ? last.path : null;
	}

	private void search(String route, String key, String goal, int generationAtStart, int startX, int startY, int goalX, int goalY,
		int plane)
	{
		int[] path = null;
		try
		{
			if (generationAtStart == generation)
			{
				path = WalkPath.find(collision(), startX, startY, goalX, goalY, plane);
			}
		}
		catch (RuntimeException e)
		{
			// A walk that cannot be worked out is simply not drawn.
		}
		synchronized (this)
		{
			pending.remove(key);
			if (generationAtStart != generation)
			{
				return;
			}
			if (walks.size() >= KEPT && !walks.containsKey(route))
			{
				walks.clear();
			}
			walks.put(route, new Walk(key, goal, path == null ? NONE : path));
		}
	}

	/** The data is read once, by whichever search needs it first, and the others wait for that one. */
	private CollisionMap collision()
	{
		synchronized (loadLock)
		{
			CollisionMap read = collision;
			if (read == null)
			{
				int before = generation;
				read = source.get();
				// If everything was let go of while this was read, the data is used once and not kept.
				if (before == generation)
				{
					collision = read;
				}
			}
			return read;
		}
	}

	/** Forgets every answer and lets go of the collision data. Searches under way give up. */
	public synchronized void release()
	{
		generation++;
		collision = null;
		walks.clear();
		pending.clear();
	}
}
