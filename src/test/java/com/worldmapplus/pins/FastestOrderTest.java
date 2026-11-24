package com.worldmapplus.pins;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The exact order solver against every order tried by brute force, and the orderer's one task, currency check and undo. */
public class FastestOrderTest
{
	private static double[][] points(double[][] xy)
	{
		int n = xy.length;
		double[][] d = new double[n][n];
		for (int i = 0; i < n; i++)
		{
			for (int j = 0; j < n; j++)
			{
				d[i][j] = Math.hypot(xy[i][0] - xy[j][0], xy[i][1] - xy[j][1]);
			}
		}
		return d;
	}

	private static double brute(double[][] d, boolean keepFirst, boolean roundTrip)
	{
		int n = d.length;
		int[] order = new int[n];
		for (int i = 0; i < n; i++)
		{
			order[i] = i;
		}
		double[] best = {Double.POSITIVE_INFINITY};
		permute(order, keepFirst || roundTrip ? 1 : 0, d, roundTrip, best);
		return best[0];
	}

	private static void permute(int[] a, int k, double[][] d, boolean roundTrip, double[] best)
	{
		if (k == a.length)
		{
			best[0] = Math.min(best[0], FastestOrder.length(d, a, roundTrip));
			return;
		}
		for (int i = k; i < a.length; i++)
		{
			int t = a[k];
			a[k] = a[i];
			a[i] = t;
			permute(a, k + 1, d, roundTrip, best);
			a[i] = a[k];
			a[k] = t;
		}
	}

	@Test
	public void theAnswerIsAsShortAsEveryOrderTriedOneByOne()
	{
		Random random = new Random(7);
		for (int trial = 0; trial < 40; trial++)
		{
			int n = 3 + random.nextInt(6);
			double[][] xy = new double[n][2];
			for (double[] p : xy)
			{
				p[0] = random.nextInt(500);
				p[1] = random.nextInt(500);
			}
			double[][] d = points(xy);
			for (int mode = 0; mode < 4; mode++)
			{
				boolean keepFirst = (mode & 1) != 0;
				boolean roundTrip = (mode & 2) != 0;
				int[] order = FastestOrder.solve(d, keepFirst, roundTrip);
				int[] sorted = order.clone();
				Arrays.sort(sorted);
				for (int i = 0; i < n; i++)
				{
					assertEquals("every stop once", i, sorted[i]);
				}
				if (keepFirst || roundTrip)
				{
					assertEquals("the first stop stays first", 0, order[0]);
				}
				assertEquals(brute(d, keepFirst, roundTrip), FastestOrder.length(d, order, roundTrip), 1e-6);
			}
		}
	}

	@Test
	public void stopsAlongALineAreVisitedInOrderAndTwelveStopsAreQuick()
	{
		double[][] d = points(new double[][] {{0, 0}, {30, 0}, {10, 0}, {20, 0}});
		assertArrayEquals(new int[] {0, 2, 3, 1}, FastestOrder.solve(d, true, false));
		double[][] xy = new double[12][2];
		for (int i = 0; i < 12; i++)
		{
			xy[i][0] = (i * 37) % 101;
			xy[i][1] = (i * 53) % 97;
		}
		long start = System.nanoTime();
		FastestOrder.solve(points(xy), false, true);
		assertTrue("well under a second", System.nanoTime() - start < 1_000_000_000L);
	}

	private static final class Setup
	{
		final PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		final RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		final List<Runnable> queued = new ArrayList<>();
		final RouteOrderer orderer = new RouteOrderer(queued::add, RouteOrderer::straight, pins, routes);
		SavedRoute route;

		Setup()
		{
			List<Long> stops = new ArrayList<>();
			for (int x : new int[] {3200, 3260, 3210, 3250, 3220})
			{
				stops.add(pins.add("At " + x, "pin", 0, x + 0.5, 3200.5, 0).getId());
			}
			route = routes.create("Line", stops, false);
		}

		void run()
		{
			while (!queued.isEmpty())
			{
				queued.remove(0).run();
			}
		}
	}

	@Test
	public void oneTaskAtATimeOnTheExecutorAndTheNewOrderIsAppliedAndUndone()
	{
		Setup s = new Setup();
		AtomicReference<RouteOrderer.Result> got = new AtomicReference<>();
		assertTrue(s.orderer.order(s.route.getId(), true, false, got::set));
		assertTrue(s.orderer.isBusy());
		assertFalse("a second request waits its turn", s.orderer.order(s.route.getId(), true, false, got::set));
		assertEquals("nothing runs on the caller's thread", 1, s.queued.size());
		s.run();
		assertTrue(s.queued.isEmpty());
		assertFalse(s.orderer.isBusy());
		RouteOrderer.Result result = got.get();
		assertNotNull(result);
		assertNull(result.getProblem());
		assertTrue(result.isBetter());
		assertEquals(60 + 50 + 40 + 30, result.getLengthBefore(), 1e-6);
		assertEquals(60, result.getLengthAfter(), 1e-6);
		List<Long> before = s.route.getStops();
		assertEquals("nothing changed until applied", before, s.routes.get(s.route.getId()).getStops());
		assertTrue(s.orderer.apply(result));
		List<Long> after = s.routes.get(s.route.getId()).getStops();
		assertEquals(Arrays.asList(before.get(0), before.get(2), before.get(4), before.get(3), before.get(1)), after);
		assertTrue(s.orderer.undo(result));
		assertEquals(before, s.routes.get(s.route.getId()).getStops());
	}

	@Test
	public void anAnswerForARouteThatChangedMeanwhileIsNotApplied()
	{
		Setup s = new Setup();
		AtomicReference<RouteOrderer.Result> got = new AtomicReference<>();
		s.orderer.order(s.route.getId(), true, false, got::set);
		List<Long> edited = new ArrayList<>(s.route.getStops());
		edited.remove(4);
		s.routes.update(s.route.getId(), "Line", edited, false);
		s.run();
		assertFalse(s.orderer.apply(got.get()));
		assertEquals(edited, s.routes.get(s.route.getId()).getStops());
	}

	@Test
	public void tooFewOrTooManyStopsAreSaidInWords()
	{
		Setup s = new Setup();
		AtomicReference<RouteOrderer.Result> got = new AtomicReference<>();
		SavedRoute two = s.routes.create("Two", s.route.getStops().subList(0, 2), false);
		s.orderer.order(two.getId(), true, false, got::set);
		s.run();
		assertNotNull(got.get().getProblem());
		List<Long> many = new ArrayList<>();
		for (int i = 0; i < 13; i++)
		{
			many.add(s.pins.add("P" + i, "pin", 0, 3000 + i, 3000, 0).getId());
		}
		SavedRoute big = s.routes.create("Big", many, false);
		s.orderer.order(big.getId(), true, false, got::set);
		s.run();
		assertTrue(got.get().getProblem().contains("12"));
		assertFalse(s.orderer.order(999, true, false, got::set));
	}

	@Test
	public void eachTaskDoesOneSearchAndTheOrderIsTheOneTheWholeMatrixGives()
	{
		for (int seed = 0; seed < 30; seed++)
		{
			java.util.Random random = new java.util.Random(seed);
			int n = 3 + random.nextInt(8);
			PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
			RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
			List<Long> stops = new ArrayList<>();
			double[][] xy = new double[n][2];
			for (int i = 0; i < n; i++)
			{
				xy[i][0] = 3000 + random.nextInt(300);
				xy[i][1] = 3000 + random.nextInt(300);
				stops.add(pins.add("P" + i, "pin", 0, xy[i][0] + 0.5, xy[i][1] + 0.5, 0).getId());
			}
			SavedRoute route = routes.create("R", stops, false);
			boolean keepFirst = random.nextBoolean();
			boolean roundTrip = random.nextBoolean();
			List<Runnable> queued = new ArrayList<>();
			int[] calls = {0};
			RouteOrderer orderer = new RouteOrderer(queued::add, (a, b) ->
			{
				calls[0]++;
				return RouteOrderer.straight(a, b) + ((a.getX() + b.getY()) % 7);
			}, pins, routes);
			orderer.setSliceNanos(0);
			AtomicReference<RouteOrderer.Result> got = new AtomicReference<>();
			assertTrue(orderer.order(route.getId(), keepFirst, roundTrip, got::set));
			int tasks = 0;
			while (!queued.isEmpty())
			{
				int before = calls[0];
				queued.remove(0).run();
				tasks++;
				assertTrue("at most one search in a task", calls[0] - before <= 1);
				assertTrue("one task queued at a time", queued.size() <= 1);
			}
			assertEquals(n * (n - 1) / 2, calls[0]);
			assertEquals(n * (n - 1) / 2, tasks);
			// The order the single task gave before: the same matrix through the same solver.
			double[][] m = new double[n][n];
			for (int i = 0; i < n; i++)
			{
				for (int j = i + 1; j < n; j++)
				{
					Pin a = pins.get(stops.get(i));
					Pin b = pins.get(stops.get(j));
					m[i][j] = RouteOrderer.straight(a, b) + ((a.getX() + b.getY()) % 7);
					m[j][i] = m[i][j];
				}
			}
			int[] order = FastestOrder.solve(m, keepFirst, roundTrip);
			int[] identity = new int[n];
			for (int i = 0; i < n; i++)
			{
				identity[i] = i;
			}
			List<Long> expected = new ArrayList<>();
			for (int k : order)
			{
				expected.add(stops.get(k));
			}
			double lb = FastestOrder.length(m, identity, roundTrip);
			double la = FastestOrder.length(m, order, roundTrip);
			if (la >= lb)
			{
				expected = stops;
				la = lb;
			}
			assertEquals(expected, got.get().getAfter());
			assertEquals(lb, got.get().getLengthBefore(), 1e-9);
			assertEquals(la, got.get().getLengthAfter(), 1e-9);
		}
	}

	@Test
	public void aRouteEditedOrAWorkCancelledBetweenStepsStopsTheWork()
	{
		Setup s = new Setup();
		s.orderer.setSliceNanos(0);
		AtomicReference<RouteOrderer.Result> got = new AtomicReference<>();
		s.orderer.order(s.route.getId(), true, false, got::set);
		s.queued.remove(0).run();
		assertNull(got.get());
		List<Long> edited = new ArrayList<>(s.route.getStops());
		edited.remove(4);
		s.routes.update(s.route.getId(), "Line", edited, false);
		s.run();
		assertNotNull(got.get().getProblem());
		assertFalse(s.orderer.apply(got.get()));
		assertFalse(s.orderer.isBusy());

		s.orderer.order(s.route.getId(), true, false, got::set);
		got.set(null);
		s.queued.remove(0).run();
		s.orderer.cancel();
		s.run();
		assertNotNull(got.get().getProblem());
		assertFalse(s.orderer.isBusy());
		assertTrue("a new request is accepted after", s.orderer.order(s.route.getId(), true, false, got::set));
	}
}
