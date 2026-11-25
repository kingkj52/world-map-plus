package com.worldmapplus.tiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

public class TileCacheTest
{
	/** Room for this many full tiles. */
	private static long tiles(int count)
	{
		return (long) count * 256 * 256 * 4;
	}

	/** Runs everything that is queued, including what the tasks queue in turn, and returns how many tasks ran. */
	private static int drain(Deque<Runnable> queue)
	{
		int ran = 0;
		while (!queue.isEmpty())
		{
			queue.poll().run();
			assertTrue("tasks run " + ran, ++ran < 100_000);
		}
		return ran;
	}

	@Test
	public void noTwoDifferentTilesShareAKeyAcrossTheWholeRangeOfMapIds()
	{
		int[][] squares = {{0, 0}, {1, 0}, {0, 1}, {63, 63}, {0xFFFFE, 0xFFFFE}, {0xFFFFF, 0xFFFFF}, {0xFFFFF, 0}, {0, 0xFFFFF}};
		int mips = TileCache.MAX_MIP + 2;
		// Every id up to 100000, which covers a floor of a map (floor times 20000 plus the map, so up to 80000 and more), and the
		// ends of the 20 bit range. All distinct.
		int[] ids = new int[100_000 + 4];
		for (int i = 0; i < 100_000; i++)
		{
			ids[i] = i;
		}
		ids[100_000] = 0x7FFFF;
		ids[100_001] = 0x80000;
		ids[100_002] = 0xFFFFE;
		ids[100_003] = 0xFFFFF;
		long[] keys = new long[ids.length * squares.length * mips];
		int n = 0;
		for (int id : ids)
		{
			for (int[] s : squares)
			{
				for (int mip = 0; mip < mips; mip++)
				{
					keys[n++] = TileCache.key(id, s[0], s[1], mip);
				}
			}
		}
		java.util.Arrays.sort(keys);
		int clashes = 0;
		for (int i = 1; i < keys.length; i++)
		{
			if (keys[i] == keys[i - 1])
			{
				clashes++;
			}
		}
		assertEquals("keys that are shared by two different tiles", 0, clashes);
	}

	@Test
	public void mapsThatOnlyDifferByMoreThanSixteenBitsAreServedApart()
	{
		int low = 4464;
		int high = low + 65536;
		int floorOfHigh = 3 * 20000 + 10187;
		FakeTiles source = new FakeTiles().add(low, 7, 9).add(high, 7, 9).add(floorOfHigh, 7, 9).add(floorOfHigh - 65536, 7, 9);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		int[] maps = {low, high, floorOfHigh, floorOfHigh - 65536};
		for (int map : maps)
		{
			assertNull(cache.get(map, 7, 9, 0));
		}
		for (int map : maps)
		{
			BufferedImage tile = cache.get(map, 7, 9, 0);
			assertNotNull("map " + map, tile);
			assertEquals("map " + map + " has its own tile", FakeTiles.colourOf(map, 7, 9).getRGB() & 0xFFFFFF, tile.getRGB(5, 5) & 0xFFFFFF);
		}
		assertEquals("four tiles loaded, none taken for another", 4, source.loads());
	}

	@Test
	public void squaresAndMapsThatDoNotFitAKeyAreNotServed()
	{
		FakeTiles source = new FakeTiles().add(-1, 1, 1).add(0x100000, 1, 1).add(0, -1, 1).add(0, 0x100000, 1);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		assertNull(cache.get(-1, 1, 1, 0));
		assertNull(cache.get(0x100000, 1, 1, 0));
		assertNull(cache.get(0, -1, 1, 0));
		assertNull(cache.get(0, 0x100000, 1, 0));
		assertNull(cache.getOverview(-1));
		assertNull(cache.getOverview(0x100000));
		assertNull(cache.substitute(-1, 1, 1, 0));
		assertFalse(cache.isLoading(0, -1, 1));
		assertEquals(0, source.loads());
	}

	@Test
	public void aTileIsNullUntilItHasLoadedThenServedFromMemory()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);

		cache.beginFrame();
		// With a direct executor the load runs inside the first request, and that request has
		// already decided it had nothing to return.
		assertNull(cache.get(0, 50, 50, 0));
		BufferedImage tile = cache.get(0, 50, 50, 0);
		assertNotNull(tile);
		assertEquals(256, tile.getWidth());
		assertEquals(1, source.loads());
		cache.get(0, 50, 50, 0);
		assertEquals(1, source.loads());
	}

	@Test
	public void missingSquaresAreNeverLoaded()
	{
		FakeTiles source = new FakeTiles();
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		assertNull(cache.get(0, 1, 1, 0));
		assertNull(cache.get(0, 1, 1, 0));
		assertEquals(0, source.loads());
		assertFalse("a square without a tile is not coming", cache.isLoading(0, 1, 1));
	}

	@Test
	public void aTileAskedForSmallIsKeptSmallAndTheFullOneIsDecodedAgainWhenNeeded()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		assertNull(cache.get(0, 50, 50, 2));
		BufferedImage mip2 = cache.get(0, 50, 50, 2);
		assertNotNull(mip2);
		assertEquals(64, mip2.getWidth());
		assertEquals(FakeTiles.colourOf(0, 50, 50).getRGB() & 0xFFFFFF, mip2.getRGB(10, 10) & 0xFFFFFF);
		assertEquals(1, source.loads());
		assertEquals("only the small copy is kept", 1, cache.size());
		assertEquals(64L * 64 * 4, cache.heldBytes());

		assertNull("the full size was never kept", cache.get(0, 50, 50, 0));
		assertNotNull(cache.get(0, 50, 50, 0));
		assertEquals("so it is decoded again", 2, source.loads());
		assertEquals(2, cache.size());
	}

	@Test
	public void everyMipLevelIsTheSameAverageAsShrinkingTheFullTile()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50);
		for (int mip = 1; mip <= TileCache.MAX_MIP; mip++)
		{
			TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
			cache.beginFrame();
			cache.get(0, 50, 50, mip);
			BufferedImage got = cache.get(0, 50, 50, mip);
			BufferedImage expected = TileCache.shrink(source.load(0, 50, 50), mip);
			assertNotNull(got);
			assertEquals(expected.getWidth(), got.getWidth());
			for (int y = 0; y < expected.getHeight(); y++)
			{
				for (int x = 0; x < expected.getWidth(); x++)
				{
					assertEquals(expected.getRGB(x, y), got.getRGB(x, y));
				}
			}
		}
	}

	@Test
	public void aTileThatFailedToLoadIsNotRetried()
	{
		FakeTiles source = new FakeTiles()
		{
			@Override
			public BufferedImage load(int mapId, int x, int y)
			{
				super.load(mapId, x, y);
				return null;
			}
		}.add(0, 7, 7);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		for (int i = 0; i < 5; i++)
		{
			assertNull(cache.get(0, 7, 7, 0));
		}
		assertEquals(1, source.loads());
		assertFalse("a tile that failed is not coming", cache.isLoading(0, 7, 7));
	}

	@Test
	public void theLeastRecentlyUsedTilesAreEvicted()
	{
		FakeTiles source = new FakeTiles();
		for (int x = 0; x < 10; x++)
		{
			source.add(0, x, 0);
		}
		// Room for about three full tiles.
		TileCache cache = new TileCache(source, Runnable::run, 3L * 256 * 256 * 4);
		cache.beginFrame();
		for (int x = 0; x < 10; x++)
		{
			cache.get(0, x, 0, 0);
			cache.get(0, x, 0, 0);
		}
		assertTrue("size " + cache.size(), cache.size() <= 3);
		assertNotNull(cache.get(0, 9, 0, 0));
	}

	@Test
	public void requestsNoLongerWantedAreDroppedBeforeDecoding()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		assertNull(cache.get(0, 5, 5, 0));
		assertEquals(1, queue.size());
		for (int i = 0; i < 50; i++)
		{
			cache.beginFrame();
		}
		queue.poll().run();
		assertEquals(0, source.loads());
		assertEquals(0, cache.pendingCount());
		assertTrue("nothing is queued once nothing is wanted", queue.isEmpty());
	}

	@Test
	public void wantedRequestsAreDecodedWhenTheQueueRuns()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		assertNull(cache.get(0, 5, 5, 0));
		queue.poll().run();
		assertEquals(1, source.loads());
		assertNotNull(cache.get(0, 5, 5, 0));
	}

	@Test
	public void theOverviewLoadsOnceAndIsCached()
	{
		BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		FakeTiles source = new FakeTiles().overview(picture);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		assertNull(cache.getOverview(0));
		assertNotNull(cache.getOverview(0));
		assertSame(picture, cache.peekOverview(0));
		assertEquals(1, source.overviewReads());
	}

	@Test
	public void aMapWithoutAnOverviewGivesNull()
	{
		TileCache cache = new TileCache(new FakeTiles(), Runnable::run, 64L << 20);
		assertNull(cache.getOverview(3));
		assertNull(cache.getOverview(3));
		assertFalse(cache.isOverviewPending(3));
		assertEquals(0, cache.pendingCount());
	}

	@Test
	public void shrinkAveragesBlocks()
	{
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0xFF0000);
		image.setRGB(1, 0, 0x000000);
		image.setRGB(0, 1, 0x000000);
		image.setRGB(1, 1, 0xFF0000);
		BufferedImage small = TileCache.shrink(image, 1);
		assertEquals(2, small.getWidth());
		assertEquals(0x7F, (small.getRGB(0, 0) >> 16) & 0xFF);
	}

	@Test
	public void aBurstOfRequestsPutsOneTaskOnTheExecutorAtATime()
	{
		FakeTiles source = new FakeTiles();
		for (int i = 0; i < 100; i++)
		{
			source.add(0, i, 50);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		for (int i = 0; i < 100; i++)
		{
			assertNull(cache.get(0, i, 50, 0));
		}
		assertEquals("one task for the whole burst", TileCache.MAX_TASKS, queue.size());
		assertTrue(TileCache.MAX_TASKS <= 2);
		// Each task decodes one tile and queues the next, so the queue never holds more than one of them.
		int ran = 0;
		while (!queue.isEmpty())
		{
			queue.poll().run();
			ran++;
			assertTrue("queued " + queue.size(), queue.size() <= TileCache.MAX_TASKS);
			assertTrue("tiles loaded after " + ran + " tasks: " + source.loads(), source.loads() == Math.min(100, ran));
		}
		assertEquals("every wanted tile was loaded by the chain, with no further request", 100, source.loads());
		assertEquals(0, cache.pendingCount());
	}

	@Test
	public void anotherPluginsTaskNeverWaitsForMoreThanOneOfOurTasks()
	{
		FakeTiles source = new FakeTiles();
		for (int i = 0; i < 20; i++)
		{
			source.add(0, i, 50);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		for (int i = 0; i < 20; i++)
		{
			cache.get(0, i, 50, 0);
		}
		boolean[] foreignRan = new boolean[1];
		int ourTasksBefore = 0;
		queue.add(() -> foreignRan[0] = true);
		// The foreign task joined the queue behind our one running task. Whatever that task queues goes behind it.
		while (!foreignRan[0])
		{
			queue.poll().run();
			ourTasksBefore++;
		}
		assertEquals("the foreign task ran after one of ours", 2, ourTasksBefore);
	}

	@Test
	public void theChainStopsWhenNothingAsksAnyMore()
	{
		FakeTiles source = new FakeTiles();
		for (int i = 0; i < 10; i++)
		{
			source.add(0, i, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.setAskWindowMillis(100);
		cache.beginFrame();
		for (int i = 0; i < 10; i++)
		{
			cache.get(0, i, 0, 0);
		}
		queue.poll().run();
		assertEquals(1, source.loads());
		assertEquals(1, queue.size());
		try
		{
			Thread.sleep(250);
		}
		catch (InterruptedException e)
		{
			throw new IllegalStateException(e);
		}
		queue.poll().run();
		assertEquals("nothing was loaded for a renderer that went quiet", 1, source.loads());
		assertTrue("and nothing is queued", queue.isEmpty());
		// Asking again starts it again.
		cache.beginFrame();
		for (int i = 0; i < 10; i++)
		{
			cache.get(0, i, 0, 0);
		}
		assertEquals(1, queue.size());
		drain(queue);
		assertEquals(10, source.loads());
	}

	@Test
	public void endingTheViewStopsTheChainAtOnce()
	{
		FakeTiles source = new FakeTiles();
		for (int i = 0; i < 10; i++)
		{
			source.add(0, i, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		for (int i = 0; i < 10; i++)
		{
			cache.get(0, i, 0, 0);
		}
		queue.poll().run();
		assertEquals(1, source.loads());
		cache.endView();
		queue.poll().run();
		assertEquals(1, source.loads());
		assertTrue(queue.isEmpty());
		cache.beginFrame();
		cache.get(0, 3, 0, 0);
		assertEquals("the next request starts the chain again", 1, queue.size());
	}

	@Test
	public void aLongFillWithAnExecutorThatRunsTasksInTheCallerDoesNotPileUpCalls()
	{
		FakeTiles source = new FakeTiles();
		for (int i = 0; i < 3000; i++)
		{
			source.add(0, i, 0);
		}
		// The first task is held back until every tile has been asked for, the rest run inside the task that submits them.
		List<Runnable> held = new ArrayList<>();
		Executor inline = task ->
		{
			if (held.isEmpty() && source.loads() == 0)
			{
				held.add(task);
			}
			else
			{
				task.run();
			}
		};
		TileCache cache = new TileCache(source, inline, tiles(4));
		cache.setAskWindowMillis(600_000);
		cache.beginFrame();
		for (int i = 0; i < 3000; i++)
		{
			cache.get(0, i, 0, 0);
		}
		assertEquals(1, held.size());
		held.get(0).run();
		assertEquals(3000, source.loads());
	}

	@Test
	public void tilesNearestTheMiddleOfTheViewLoadFirst()
	{
		FakeTiles source = new FakeTiles();
		for (int x = 0; x < 40; x++)
		{
			source.add(0, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		// The renderer asks left to right, but the view is centred on square 30.
		cache.beginFrame(30.5, 0.5);
		for (int x = 0; x < 40; x++)
		{
			assertNull(cache.get(0, x, 0, 0));
		}
		for (int i = 0; i < 8; i++)
		{
			queue.poll().run();
		}
		java.util.List<int[]> order = source.order();
		assertEquals(8, order.size());
		assertEquals(30, order.get(0)[1]);
		// Each load is the nearest of what was left: 30, then 29 and 31, and so on outwards.
		for (int i = 1; i < order.size(); i++)
		{
			assertTrue(Math.abs(order.get(i)[1] - 30) >= Math.abs(order.get(i - 1)[1] - 30));
		}
		assertTrue(Math.abs(order.get(7)[1] - 30) <= 4);
	}

	@Test
	public void theNextFramesRequestsFeedTheQueueAgainAndEverythingLoadsInTheEnd()
	{
		FakeTiles source = new FakeTiles();
		for (int x = 0; x < 40; x++)
		{
			source.add(0, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		int frames = 0;
		while (cache.pendingCount() > 0 || frames == 0)
		{
			cache.beginFrame(20.5, 0.5);
			for (int x = 0; x < 40; x++)
			{
				cache.get(0, x, 0, 0);
			}
			drain(queue);
			assertTrue("frames " + frames, ++frames < 20);
		}
		assertEquals(40, source.loads());
		for (int x = 0; x < 40; x++)
		{
			assertNotNull(cache.get(0, x, 0, 0));
		}
	}

	@Test
	public void aRequestForATileThatIsAlreadyBeingLoadedDoesNotQueueAnother()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		for (int i = 0; i < 20; i++)
		{
			assertNull(cache.get(0, 5, 5, 0));
		}
		// The renderer asks again every frame, which keeps the tile wanted but adds no work beyond the task limit.
		assertTrue(queue.size() <= TileCache.MAX_TASKS);
		drain(queue);
		assertEquals(1, source.loads());
	}

	@Test
	public void clearingTheCacheLetsTheSourceGoOfWhatItHolds()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		cache.get(0, 5, 5, 0);
		assertEquals(0, source.releases());
		cache.clear();
		assertEquals(1, source.releases());
		assertEquals(0, cache.size());
		assertEquals(0, cache.pendingCount());
		assertEquals(0, cache.heldBytes());
	}

	@Test
	public void anExecutorThatRefusesWorkLeavesNothingPending()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5);
		TileCache cache = new TileCache(source, task ->
		{
			throw new java.util.concurrent.RejectedExecutionException("shut down");
		}, 64L << 20);
		cache.beginFrame();
		assertNull(cache.get(0, 5, 5, 0));
		assertNull(cache.getOverview(0));
		assertEquals(0, cache.pendingCount());
		assertFalse(cache.isOverviewPending(0));
	}

	@Test
	public void aLoadThatFinishesAfterTheCacheWasClearedIsDropped()
	{
		TileCache[] holder = new TileCache[1];
		FakeTiles source = new FakeTiles()
		{
			@Override
			public BufferedImage load(int mapId, int x, int y)
			{
				BufferedImage image = super.load(mapId, x, y);
				if (loads() == 1)
				{
					holder[0].clear();
				}
				return image;
			}
		}.add(0, 5, 5);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		holder[0] = cache;
		cache.beginFrame();
		assertNull(cache.get(0, 5, 5, 0));
		assertEquals("the tile loaded before the clear is not kept", 0, cache.size());
		assertEquals(0, cache.heldBytes());
		assertNull(cache.get(0, 5, 5, 0));
		assertNotNull(cache.get(0, 5, 5, 0));
		assertEquals(2, source.loads());
	}

	// ---- mip levels ----

	@Test
	public void aSmallerLevelIsNeverMadeInsideAGetCall()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		cache.get(0, 50, 50, 0);
		drain(queue);
		BufferedImage full = cache.get(0, 50, 50, 0);
		assertNotNull(full);
		assertEquals(1, source.loads());

		// The full tile is in memory. Asking for a smaller one answers null at once and leaves the averaging to a task.
		assertNull(cache.get(0, 50, 50, 1));
		assertEquals(1, queue.size());
		assertEquals("nothing was made by the call", 1, cache.size());
		drain(queue);
		BufferedImage first = cache.get(0, 50, 50, 1);
		BufferedImage second = cache.get(0, 50, 50, 1);
		assertNotNull(first);
		assertSame("the shrunk copy is kept", first, second);
		assertEquals("without decoding the tile again", 1, source.loads());
		assertEquals(128, first.getWidth());
	}

	@Test
	public void manyTilesAveragedDownInOneTaskWhenTheirFullSizeIsInMemory()
	{
		FakeTiles source = new FakeTiles();
		for (int x = 0; x < 6; x++)
		{
			source.add(0, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame(3, 0);
		for (int x = 0; x < 6; x++)
		{
			cache.get(0, x, 0, 0);
		}
		drain(queue);
		cache.beginFrame(3, 0);
		for (int x = 0; x < 6; x++)
		{
			assertNull(cache.get(0, x, 0, 2));
		}
		int ran = drain(queue);
		assertTrue("six tiles take one task and the empty one after it, ran " + ran, ran <= 3);
		assertEquals("no decoding", 6, source.loads());
		for (int x = 0; x < 6; x++)
		{
			assertEquals(64, cache.get(0, x, 0, 2).getWidth());
		}
	}

	@Test
	public void theFullTileIsKeptWhenTheAskingChangesWhileItIsBeingDecoded()
	{
		TileCache[] holder = new TileCache[1];
		FakeTiles source = new FakeTiles()
		{
			@Override
			public BufferedImage load(int mapId, int x, int y)
			{
				BufferedImage image = super.load(mapId, x, y);
				// The renderer zooms in while the tile is being decoded and asks for the full size.
				holder[0].get(mapId, x, y, 0);
				return image;
			}
		}.add(0, 5, 5);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		holder[0] = cache;
		cache.beginFrame();
		assertNull(cache.get(0, 5, 5, 3));
		drain(queue);
		assertNotNull("the full tile that was decoded is not thrown away", cache.get(0, 5, 5, 0));
		assertEquals(1, source.loads());
	}

	@Test
	public void aStandInIsTheNearestSizeInMemory()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5).add(0, 6, 6);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		assertNull(cache.substitute(0, 5, 5, 2));
		cache.get(0, 5, 5, 0);
		BufferedImage full = cache.get(0, 5, 5, 0);
		assertSame("a larger size will do", full, cache.substitute(0, 5, 5, 2));
		cache.get(0, 5, 5, 3);
		BufferedImage coarse = cache.get(0, 5, 5, 3);
		assertNotNull(coarse);
		assertSame("a size one step away is nearer than one two steps away", coarse, cache.substitute(0, 5, 5, 2));
		assertSame("a finer copy is chosen before a coarser one at the same distance", full, cache.substitute(0, 5, 5, 1));
		assertNull("other tiles have none", cache.substitute(0, 6, 6, 2));
	}

	// ---- the whole-map picture ----

	@Test
	public void wholeMapPicturesAreNotPushedOutByTiles()
	{
		BufferedImage picture = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
		FakeTiles source = new FakeTiles().overview(picture);
		for (int x = 0; x < 30; x++)
		{
			source.add(0, x, 0);
		}
		TileCache cache = new TileCache(source, Runnable::run, tiles(4) + 512L * 512 * 4);
		cache.beginFrame();
		cache.getOverview(0);
		assertSame(picture, cache.getOverview(0));
		for (int x = 0; x < 30; x++)
		{
			cache.get(0, x, 0, 0);
			cache.get(0, x, 0, 0);
		}
		assertSame("the picture is still there after a long walk", picture, cache.peekOverview(0));
		assertTrue("size " + cache.size(), cache.size() <= 4);
		assertEquals(1, cache.pinnedSize());
	}

	@Test
	public void onlyAHandfulOfWholeMapPicturesAreKept()
	{
		BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		FakeTiles source = new FakeTiles().overview(picture);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		for (int map = 0; map < 5; map++)
		{
			cache.getOverview(map);
			assertNotNull(cache.getOverview(map));
		}
		assertEquals(3, cache.pinnedSize());
		assertNull("the oldest went", cache.peekOverview(0));
		assertNotNull(cache.peekOverview(4));
	}

	@Test
	public void aWholeMapPictureIsReadInOneTaskAndOnlyOnce()
	{
		BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		FakeTiles source = new FakeTiles().overview(picture);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame();
		assertNull(cache.getOverview(0));
		assertTrue(cache.isOverviewPending(0));
		assertEquals(1, cache.pendingCount());
		assertEquals(1, queue.size());
		queue.poll().run();
		assertNotNull(cache.peekOverview(0));
		assertFalse(cache.isOverviewPending(0));
		assertEquals(0, cache.pendingCount());
		drain(queue);
		assertEquals("read once", 1, source.overviewReads());
	}

	@Test
	public void aPictureThatFailsIsNotTriedAgain()
	{
		FakeTiles source = new FakeTiles();
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		for (int i = 0; i < 5; i++)
		{
			assertNull(cache.getOverview(0));
		}
		assertEquals(1, source.overviewReads());
		assertEquals(0, cache.pendingCount());
	}

	@Test
	public void aFewTilesGoBeforeAPictureThatOnlyStandsInForTiles()
	{
		BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		FakeTiles source = new FakeTiles().overview(picture);
		for (int x = 0; x < 10; x++)
		{
			source.add(0, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame(5, 0);
		cache.getOverview(0, false);
		for (int x = 0; x < 10; x++)
		{
			cache.get(0, x, 0, 0);
		}
		assertEquals("a picture that is only a stand in is not pending work", 10, cache.pendingCount());
		drain(queue);
		assertEquals(10, source.loads());
		assertEquals(1, source.overviewReads());
		assertNotNull(cache.peekOverview(0));
	}

	@Test
	public void withManyTilesWaitingThePictureComesAfterTheFirstFewTiles()
	{
		BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		FakeTiles source = new FakeTiles().overview(picture);
		for (int x = 0; x < 200; x++)
		{
			source.add(0, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame(100, 0);
		cache.getOverview(0, false);
		for (int x = 0; x < 200; x++)
		{
			cache.get(0, x, 0, 0);
		}
		int tilesFirst = TileCache.HEAD_START_TILES;
		for (int i = 0; i < tilesFirst; i++)
		{
			queue.poll().run();
		}
		assertEquals(tilesFirst, source.loads());
		assertEquals(0, source.overviewReads());
		queue.poll().run();
		assertEquals(1, source.overviewReads());
		assertEquals("the tiles wait for the picture", tilesFirst, source.loads());
		assertNotNull(cache.peekOverview(0));
		queue.poll().run();
		assertEquals(tilesFirst + 1, source.loads());
		drain(queue);
		assertEquals(200, source.loads());
	}

	@Test
	public void aPictureTheViewNeedsGoesAfterTheFirstTilesToo()
	{
		BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		FakeTiles source = new FakeTiles().overview(picture);
		for (int x = 0; x < 20; x++)
		{
			source.add(0, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame(10, 0);
		cache.getOverview(0, true);
		for (int x = 0; x < 20; x++)
		{
			cache.get(0, x, 0, 0);
		}
		// The picture is needed, so after a head start of tiles it is made before the rest.
		for (int i = 0; i < TileCache.HEAD_START_TILES + 1; i++)
		{
			queue.poll().run();
		}
		assertEquals(TileCache.HEAD_START_TILES, source.loads());
		assertEquals(1, source.overviewReads());
	}

	// ---- fetching ahead ----

	/** Tiles along one row, with a view over squares 10 and 11 of it. */
	private static FakeTiles row()
	{
		FakeTiles source = new FakeTiles();
		for (int x = 0; x < 30; x++)
		{
			source.add(0, x, 0);
		}
		return source;
	}

	private static TileCache aheadCache(FakeTiles source, Deque<Runnable> queue, long budget)
	{
		TileCache cache = new TileCache(source, queue::add, budget);
		cache.setAskWindowMillis(60_000);
		cache.setPrefetchDelayMillis(0);
		return cache;
	}

	@Test
	public void tilesBesideTheViewAreFetchedOnceItIsDone()
	{
		FakeTiles source = row();
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = aheadCache(source, queue, 64L << 20);
		cache.beginFrame(11, 0.5);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		assertTrue("nothing is queued by noting the view", queue.isEmpty());
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		assertEquals(1, queue.size());
		drain(queue);
		// Six rings either side of squares 10 and 11, along the one row that has tiles.
		assertEquals(12, source.loads());
		assertEquals(12, cache.aheadSize());
		assertEquals("tiles fetched ahead are kept apart from the view's", 0, cache.size());
		List<int[]> order = source.order();
		assertTrue("nearest first: " + order.get(0)[1], order.get(0)[1] == 9 || order.get(0)[1] == 12);

		// The view reaches one of them: it is in the cache at once, with no decode.
		BufferedImage image = cache.get(0, 12, 0, 0);
		assertNotNull(image);
		assertEquals(12, source.loads());
		assertEquals(1, cache.size());
		assertEquals(11, cache.aheadSize());
	}

	@Test
	public void tilesAreFetchedAheadAtTheSizeTheViewUses()
	{
		FakeTiles source = row();
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = aheadCache(source, queue, 64L << 20);
		cache.beginFrame(11, 0.5);
		cache.noteView(0, 0, 3, 10, 11, 0, 0);
		cache.noteView(0, 0, 3, 10, 11, 0, 0);
		drain(queue);
		assertEquals(12, cache.aheadSize());
		assertEquals("only the small copies are held", 12L * 32 * 32 * 4, cache.heldBytes());
		assertNotNull(cache.get(0, 9, 0, 3));
		assertEquals(12, source.loads());
	}

	@Test
	public void fetchingAheadWaitsForTheViewToStayStill()
	{
		FakeTiles source = row();
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.setAskWindowMillis(60_000);
		cache.setPrefetchDelayMillis(60_000);
		cache.beginFrame(11, 0.5);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		assertTrue(queue.isEmpty());
		assertEquals(0, source.loads());
	}

	@Test
	public void fetchingAheadFollowsTheViewWhenItMoves()
	{
		FakeTiles source = row();
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = aheadCache(source, queue, 64L << 20);
		cache.beginFrame(3.5, 0.5);
		cache.noteView(0, 0, 0, 3, 3, 0, 0);
		cache.noteView(0, 0, 0, 3, 3, 0, 0);
		queue.poll().run();
		queue.poll().run();
		int before = source.loads();
		// The view moves far away: the ring around the old place is not carried on with.
		cache.beginFrame(25.5, 0.5);
		cache.noteView(0, 0, 0, 25, 25, 0, 0);
		cache.noteView(0, 0, 0, 25, 25, 0, 0);
		drain(queue);
		List<int[]> order = source.order();
		for (int i = before; i < order.size(); i++)
		{
			assertTrue("tile " + order.get(i)[1] + " is beside the new view", order.get(i)[1] >= 19);
		}
	}

	@Test
	public void fetchingAheadNeverTakesRoomFromTheTilesOfTheView()
	{
		FakeTiles source = row();
		Deque<Runnable> queue = new ArrayDeque<>();
		// Room for six full tiles; the view is four of them.
		TileCache cache = aheadCache(source, queue, tiles(6));
		cache.beginFrame(11, 0.5);
		for (int x = 9; x <= 12; x++)
		{
			cache.get(0, x, 0, 0);
		}
		drain(queue);
		for (int x = 9; x <= 12; x++)
		{
			assertNotNull(cache.get(0, x, 0, 0));
		}
		cache.noteView(0, 0, 0, 9, 12, 0, 0);
		cache.noteView(0, 0, 0, 9, 12, 0, 0);
		drain(queue);
		assertEquals("the view's tiles are all still there", 4, cache.size());
		for (int x = 9; x <= 12; x++)
		{
			assertNotNull(cache.get(0, x, 0, 0));
		}
		assertTrue("held " + cache.heldBytes(), cache.heldBytes() <= tiles(6));
		assertTrue("ahead " + cache.aheadSize(), cache.aheadSize() <= 2);
	}

	@Test
	public void fetchingAheadUsesAtMostHalfOfTheBudget()
	{
		FakeTiles source = row();
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = aheadCache(source, queue, tiles(10));
		cache.beginFrame(11, 0.5);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		drain(queue);
		assertTrue("ahead " + cache.aheadSize(), cache.aheadSize() <= 5);
		assertTrue(cache.heldBytes() <= tiles(5));
		assertTrue("something was fetched", cache.aheadSize() > 0);
	}

	@Test
	public void nothingIsFetchedAheadOnceTheViewHasEnded()
	{
		FakeTiles source = row();
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = aheadCache(source, queue, 64L << 20);
		cache.beginFrame(11, 0.5);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		queue.poll().run();
		int loaded = source.loads();
		cache.endView();
		drain(queue);
		assertEquals(loaded, source.loads());
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		cache.noteView(0, 0, 0, 10, 11, 0, 0);
		assertTrue("an ended view fetches nothing until it is asked again", queue.size() <= 1);
	}

	@Test
	public void aFloorViewFetchesTheGroundBeneathItToo()
	{
		FakeTiles source = new FakeTiles();
		int floor = TileSource.tileMapId(0, 1);
		for (int x = 0; x < 10; x++)
		{
			source.add(0, x, 0).add(floor, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = aheadCache(source, queue, 64L << 20);
		cache.beginFrame(4.5, 0.5);
		cache.noteView(floor, 0, 0, 4, 4, 0, 0);
		cache.noteView(floor, 0, 0, 4, 4, 0, 0);
		drain(queue);
		boolean ground = false;
		boolean upstairs = false;
		for (int[] load : source.order())
		{
			ground |= load[0] == 0;
			upstairs |= load[0] == floor;
		}
		assertTrue(ground && upstairs);
	}

	// ---- memory ----

	@Test
	public void everythingIsLetGoOfAfterTheTimeNothingAsked()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		cache.get(0, 5, 5, 0);
		cache.get(0, 5, 5, 0);
		assertTrue(cache.heldBytes() > 0);
		assertFalse("not yet", cache.releaseIfIdle(60_000));
		assertTrue(cache.heldBytes() > 0);
		try
		{
			Thread.sleep(40);
		}
		catch (InterruptedException e)
		{
			throw new IllegalStateException(e);
		}
		assertTrue(cache.releaseIfIdle(10));
		assertEquals(0, cache.heldBytes());
		assertEquals(1, source.releases());
		assertFalse("nothing left to let go of", cache.releaseIfIdle(10));
		assertEquals(1, source.releases());
	}

	@Test
	public void aCacheThatIsBeingAskedIsNotReleased()
	{
		FakeTiles source = new FakeTiles().add(0, 5, 5);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.beginFrame();
		cache.get(0, 5, 5, 0);
		cache.get(0, 5, 5, 0);
		assertFalse(cache.releaseIfIdle(5_000));
		assertTrue(cache.heldBytes() > 0);
	}

	/** A source whose tiles on the right half (x of 6 and more) are still being downloaded until it is told they have come. */
	private static final class Arriving extends FakeTiles
	{
		final List<double[]> asked = new ArrayList<>();
		volatile boolean arrived;

		@Override
		public boolean ready(int mapId, int x, int y)
		{
			return arrived || x < 6;
		}

		@Override
		public void request(int mapId, int x, int y, double distance)
		{
			asked.add(new double[] {x, distance});
		}
	}

	@Test
	public void tilesStillBeingDownloadedAreAskedForNearestFirstAndTheRestIsDecodedMeanwhile()
	{
		Arriving source = new Arriving();
		for (int x = 4; x < 9; x++)
		{
			source.add(0, x, 0);
		}
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		cache.beginFrame(7.5, 0.5);
		for (int x = 4; x < 9; x++)
		{
			assertNull(cache.get(0, x, 0, 0));
		}
		drain(queue);
		assertNotNull("a tile that is here is decoded", cache.get(0, 4, 0, 0));
		assertNotNull(cache.get(0, 5, 0, 0));
		assertNull("a tile on its way is not decoded, nor given up on", cache.get(0, 7, 0, 0));
		assertTrue(cache.isLoading(0, 7, 0));
		assertEquals("only the tiles that are here were loaded", 2, source.loads());
		assertEquals(3, cache.pendingCount());
		assertFalse("nothing is asked of the source for a tile that is here", source.asked.stream().anyMatch(a -> a[0] < 6));
		double nearest = Double.MAX_VALUE;
		for (double[] a : source.asked)
		{
			if (a[0] == 7)
			{
				nearest = Math.min(nearest, a[1]);
			}
		}
		assertEquals("the distance from the middle of the view is passed on", 0.0, nearest, 1e-9);

		// The tiles come: the next frames load them.
		source.arrived = true;
		for (int frame = 0; frame < 3; frame++)
		{
			cache.beginFrame(7.5, 0.5);
			for (int x = 4; x < 9; x++)
			{
				cache.get(0, x, 0, 0);
			}
			drain(queue);
		}
		assertNotNull(cache.get(0, 7, 0, 0));
		assertEquals(5, source.loads());
		assertEquals(0, cache.pendingCount());
	}
}
