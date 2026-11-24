package com.worldmapplus.pins;

import com.worldmapplus.MapAssets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Puts the stops of a saved route in the fastest order, on request from the side panel.
 * <p>
 * The distances are the walks this plugin's own planner finds between stops on one floor of one map (the steps a player takes), and
 * the straight line in tiles (diagonal steps counted as one, like a walk) where there is no walk data, the two stops are on different
 * maps or floors, or the walk would be too long for the planner. The order is then solved exactly ({@link FastestOrder}).
 * <p>
 * The work runs as short tasks on RuneLite's shared executor, one queued after the other, never a thread of its own, and only one request at a time: a second request while
 * one runs is refused. Nothing is changed by the task. The answer is handed back with the stops it was worked out for, and
 * {@link #apply} changes the route only if it still has exactly those stops, so an edit made meanwhile is never overwritten.
 */
@Singleton
public class RouteOrderer
{
	/** The length between two saved places. Called on the executor. */
	public interface Distance
	{
		double between(Pin from, Pin to);
	}

	/** What one request found. */
	public static final class Result
	{
		private final long routeId;
		private final List<Long> before;
		private final List<Long> after;
		private final double lengthBefore;
		private final double lengthAfter;
		private final String problem;

		Result(long routeId, List<Long> before, List<Long> after, double lengthBefore, double lengthAfter, @Nullable String problem)
		{
			this.routeId = routeId;
			this.before = Collections.unmodifiableList(new ArrayList<>(before));
			this.after = Collections.unmodifiableList(new ArrayList<>(after));
			this.lengthBefore = lengthBefore;
			this.lengthAfter = lengthAfter;
			this.problem = problem;
		}

		public long getRouteId()
		{
			return routeId;
		}

		/** The stops as they were when the order was worked out. */
		public List<Long> getBefore()
		{
			return before;
		}

		/** The stops in the fastest order. */
		public List<Long> getAfter()
		{
			return after;
		}

		public double getLengthBefore()
		{
			return lengthBefore;
		}

		public double getLengthAfter()
		{
			return lengthAfter;
		}

		/** Why nothing could be worked out, in words, or null. */
		@Nullable
		public String getProblem()
		{
			return problem;
		}

		/** True when the new order is shorter by at least a tile. */
		public boolean isBetter()
		{
			return problem == null && lengthAfter < lengthBefore - 0.5;
		}
	}

	private final Executor executor;
	private final Distance distance;
	private final PinStore pins;
	private final RouteStore routes;
	private final AtomicBoolean busy = new AtomicBoolean();
	private final AtomicInteger generation = new AtomicInteger();
	private volatile long sliceNanos = SLICE_NANOS;

	/** How long one task may keep the shared executor: one search at least, then more while this time lasts. */
	static final long SLICE_NANOS = 4_000_000L;

	@Inject
	public RouteOrderer(ScheduledExecutorService executor, MapAssets assets, PinStore pins, RouteStore routes)
	{
		this(executor, walkOrStraight(assets), pins, routes);
	}

	public RouteOrderer(Executor executor, Distance distance, PinStore pins, RouteStore routes)
	{
		this.executor = executor;
		this.distance = distance;
		this.pins = pins;
		this.routes = routes;
	}

	/** The straight line in tiles, with a diagonal step counted as one step, as the walks count them. */
	static double straight(Pin from, Pin to)
	{
		return Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getY() - to.getY()));
	}

	/** Walks from the planner where both stops are on one floor of one map, and the straight line otherwise. */
	static Distance walkOrStraight(MapAssets assets)
	{
		return (from, to) ->
		{
			if (from.getMap() == to.getMap() && from.getPlane() == to.getPlane())
			{
				int steps = assets.getWalkPlanner().steps((int) Math.floor(from.getX()), (int) Math.floor(from.getY()),
					(int) Math.floor(to.getX()), (int) Math.floor(to.getY()), from.getPlane());
				if (steps >= 0)
				{
					return steps;
				}
			}
			return straight(from, to);
		};
	}

	/** For tests: how long a step may run. Zero means one search per step. */
	void setSliceNanos(long nanos)
	{
		sliceNanos = nanos;
	}

	/** True while an order is being worked out. */
	public boolean isBusy()
	{
		return busy.get();
	}

	/**
	 * Starts working out the fastest order of a route. Returns false, and starts nothing, while another is being worked out. The
	 * answer is handed to {@code done} on the executor's thread; the caller moves it to its own thread.
	 * <p>
	 * The work is cut into short steps, each its own task on the executor and queued only when the one before has ended, so other
	 * plugins' tasks get their turn in between. A step does one walking search, or more while the time slice allows. When the route
	 * has changed (or {@link #cancel} was called) the work stops at the next step and the answer says so.
	 */
	public boolean order(long routeId, boolean keepFirst, boolean roundTrip, Consumer<Result> done)
	{
		SavedRoute route = routes.get(routeId);
		if (route == null || !busy.compareAndSet(false, true))
		{
			return false;
		}
		List<Long> stops = new ArrayList<>(route.getStops());
		List<Pin> places = new ArrayList<>();
		for (long id : stops)
		{
			places.add(pins.get(id));
		}
		Job job = new Job(routeId, stops, places, keepFirst, roundTrip, done, generation.get());
		try
		{
			executor.execute(job);
		}
		catch (RuntimeException e)
		{
			busy.set(false);
			return false;
		}
		return true;
	}

	/** Stops the work in progress at its next step. The answer then says the order was not worked out. */
	public void cancel()
	{
		generation.incrementAndGet();
	}

	private static final String CHANGED = "The route changed while its order was worked out.";

	/** One request, run as a chain of short tasks. */
	private final class Job implements Runnable
	{
		private final long routeId;
		private final List<Long> stops;
		private final List<Pin> places;
		private final boolean keepFirst;
		private final boolean roundTrip;
		private final Consumer<Result> done;
		private final int started;
		private final int n;
		private double[][] matrix;
		private int row;
		private int col;

		Job(long routeId, List<Long> stops, List<Pin> places, boolean keepFirst, boolean roundTrip, Consumer<Result> done, int started)
		{
			this.routeId = routeId;
			this.stops = stops;
			this.places = places;
			this.keepFirst = keepFirst;
			this.roundTrip = roundTrip;
			this.done = done;
			this.started = started;
			this.n = stops.size();
			this.col = 1;
		}

		@Override
		public void run()
		{
			Result result = null;
			try
			{
				result = step();
			}
			catch (RuntimeException e)
			{
				result = new Result(routeId, stops, stops, 0, 0, "The order could not be worked out.");
			}
			if (result == null)
			{
				try
				{
					executor.execute(this);
					return;
				}
				catch (RuntimeException e)
				{
					result = new Result(routeId, stops, stops, 0, 0, "The order could not be worked out.");
				}
			}
			busy.set(false);
			done.accept(result);
		}

		/** Does one slice of the work. Returns the answer when finished, or null when another step is needed. */
		private Result step()
		{
			if (matrix == null)
			{
				String problem = check();
				if (problem != null)
				{
					return new Result(routeId, stops, stops, 0, 0, problem);
				}
				matrix = new double[n][n];
			}
			if (generation.get() != started || !sameRoute())
			{
				return new Result(routeId, stops, stops, 0, 0, CHANGED);
			}
			long end = System.nanoTime() + sliceNanos;
			do
			{
				if (row >= n - 1)
				{
					return finish();
				}
				double length = distance.between(places.get(row), places.get(col));
				matrix[row][col] = length;
				matrix[col][row] = length;
				if (++col >= n)
				{
					row++;
					col = row + 1;
				}
			}
			while (System.nanoTime() < end);
			return row >= n - 1 ? finish() : null;
		}

		private boolean sameRoute()
		{
			SavedRoute now = routes.get(routeId);
			return now != null && now.getStops().equals(stops);
		}

		@Nullable
		private String check()
		{
			if (places.contains(null))
			{
				return "A stop of this route is no longer a saved place.";
			}
			if (n < 3)
			{
				return "A route needs three stops or more to put in another order.";
			}
			if (n > FastestOrder.MAX_STOPS)
			{
				return "The fastest order is worked out for up to " + FastestOrder.MAX_STOPS + " stops. This route has " + n + ".";
			}
			return null;
		}

		private Result finish()
		{
			int[] identity = new int[n];
			for (int i = 0; i < n; i++)
			{
				identity[i] = i;
			}
			int[] order = FastestOrder.solve(matrix, keepFirst, roundTrip);
			double before = FastestOrder.length(matrix, identity, roundTrip);
			double after = FastestOrder.length(matrix, order, roundTrip);
			List<Long> reordered = new ArrayList<>(n);
			for (int index : order)
			{
				reordered.add(stops.get(index));
			}
			if (after >= before)
			{
				reordered = stops;
				after = before;
			}
			return new Result(routeId, stops, reordered, before, after, null);
		}
	}

	/** Puts the new order into the route, only if it still has exactly the stops it was worked out for. True when it did. */
	public boolean apply(Result result)
	{
		if (result.getProblem() != null)
		{
			return false;
		}
		return replace(result.getRouteId(), result.getBefore(), result.getAfter());
	}

	/** Puts the old order back, only if the route still has the order {@link #apply} gave it. True when it did. */
	public boolean undo(Result result)
	{
		return replace(result.getRouteId(), result.getAfter(), result.getBefore());
	}

	private boolean replace(long routeId, List<Long> expected, List<Long> wanted)
	{
		synchronized (routes)
		{
			SavedRoute route = routes.get(routeId);
			if (route == null || !route.getStops().equals(expected))
			{
				return false;
			}
			return routes.update(routeId, route.getName(), wanted, route.isLoop());
		}
	}
}
