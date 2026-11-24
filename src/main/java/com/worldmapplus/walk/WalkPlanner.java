package com.worldmapplus.walk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Finds walking paths without holding up the frame that asks for one.
 * <p>
 * A frame asks, every frame, for the way between two points. The first ask starts a search on the shared
 * background executor and gets nothing back; later asks get the answer once it is ready. While a new search for
 * the same route is under way, the last answer for that route is handed back, so a line that follows the player
 * does not blink out at every step. When the player has simply walked along the line, the rest of the line is
 * the answer and no search is needed at all.
 * <p>
 * Searches are cheap, but the collision data they read is several megabytes, so it is read on the first search
 * and let go of with {@link #release()}.
 */
public final class WalkPlanner
{
	/** The answer for a walk with no way found, or none that this search can see. Never null, always empty. */
	public static final int[] NONE = new int[0];

	private static final int KEPT = 96;
	private static final int MAX_PENDING = 4;

	/** The last way found for a journey, with where it was going. */
	private static final class Last
	{
		final int goalX;
		final int goalY;
		final int plane;
		final int[] path;

		Last(int goalX, int goalY, int plane, int[] path)
		{
			this.goalX = goalX;
			this.goalY = goalY;
			this.plane = plane;
			this.path = path;
		}

		boolean goesTo(int x, int y, int z)
		{
			return goalX == x && goalY == y && plane == z;
		}
	}

	private final Executor executor;
	private final Supplier<CollisionMap> source;
	private final Object loadLock = new Object();
	private volatile CollisionMap collision;

	/** Goes up when everything is let go of, so a search that was under way can tell its answer is not wanted. */
	private volatile int generation;

	private final Map<String, int[]> done = new LinkedHashMap<String, int[]>(128, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest)
		{
			return size() > KEPT;
		}
	};
	private final Set<String> pending = new HashSet<>();
	private final Map<String, Last> lastForRoute = new HashMap<>();

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
	 * @param route names the journey the walk belongs to, so an older answer for it can stand in while a new
	 * one is being worked out
	 * @return tile pairs, x then y, from the start to the goal; {@link #NONE} when no way is known; or null when
	 * nothing is known yet and a search has been started
	 */
	@Nullable
	public synchronized int[] lookup(String route, int startX, int startY, int goalX, int goalY, int plane)
	{
		String key = startX + "," + startY + ">" + goalX + "," + goalY + "@" + plane;
		int[] hit = done.get(key);
		if (hit != null)
		{
			return hit;
		}
		Last last = lastForRoute.get(route);
		boolean sameGoal = last != null && last.goesTo(goalX, goalY, plane);
		if (sameGoal)
		{
			// Walking along the line is not a new question: what is left of the line is the answer.
			int at = indexOf(last.path, startX, startY);
			if (at >= 0)
			{
				int[] rest = Arrays.copyOfRange(last.path, at * 2, last.path.length);
				done.put(key, rest);
				lastForRoute.put(route, new Last(goalX, goalY, plane, rest));
				return rest;
			}
		}
		if (!pending.contains(key) && pending.size() < MAX_PENDING)
		{
			pending.add(key);
			int generationNow = generation;
			executor.execute(() -> search(route, key, generationNow, startX, startY, goalX, goalY, plane));
		}
		return sameGoal ? last.path : null;
	}

	private static int indexOf(int[] path, int x, int y)
	{
		for (int i = 0; i + 1 < path.length; i += 2)
		{
			if (path[i] == x && path[i + 1] == y)
			{
				return i / 2;
			}
		}
		return -1;
	}

	private void search(String route, String key, int generationAtStart, int startX, int startY, int goalX, int goalY,
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
			done.put(key, path == null ? NONE : path);
			if (path != null)
			{
				lastForRoute.put(route, new Last(goalX, goalY, plane, path));
			}
			else
			{
				lastForRoute.remove(route);
			}
		}
	}

	/**
	 * How many steps the walk between two tiles takes, or -1 when no way is known. Works the answer out in the calling thread, so it
	 * is for a task already running on the shared executor, never for a frame or the client thread. Answers are not kept.
	 */
	public int steps(int startX, int startY, int goalX, int goalY, int plane)
	{
		int generationAtStart = generation;
		try
		{
			int[] path = WalkPath.find(collision(), startX, startY, goalX, goalY, plane);
			return path == null || generationAtStart != generation ? -1 : path.length / 2 - 1;
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	/** The data is read once, by whichever search needs it first, and the others wait for that one. */
	private CollisionMap collision()
	{
		CollisionMap loaded = collision;
		if (loaded != null)
		{
			return loaded;
		}
		synchronized (loadLock)
		{
			if (collision != null)
			{
				return collision;
			}
			int before = generation;
			CollisionMap read = source.get();
			// If everything was let go of while this was read, the data is used once and not kept.
			if (before == generation)
			{
				collision = read;
			}
			return read;
		}
	}

	/** Forgets every answer and lets go of the collision data. Searches under way give up. */
	public synchronized void release()
	{
		generation++;
		collision = null;
		done.clear();
		pending.clear();
		lastForRoute.clear();
	}
}
