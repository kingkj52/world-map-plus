package com.worldmapplus.drops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Item prices read on the client thread only, bounded, kept for a while; and the lazy drop table loader. */
public class ItemPricesTest
{
	/** A client thread that runs tasks when the test says. */
	private static final class Queue
	{
		final List<Runnable> tasks = new ArrayList<>();

		void runAll()
		{
			List<Runnable> now = new ArrayList<>(tasks);
			tasks.clear();
			now.forEach(Runnable::run);
		}
	}

	@Test
	public void pricesAreOnlyReadOnTheClientThreadAndUnknownUntilThen()
	{
		Queue client = new Queue();
		AtomicInteger reads = new AtomicInteger();
		ItemPrices prices = new ItemPrices(id ->
		{
			reads.incrementAndGet();
			return id == 995 ? 1 : id == 4151 ? 1_500_000 : 0;
		}, client.tasks::add);
		int before = prices.version();
		prices.request(new int[] {995, 4151, 1234, -1});
		assertEquals("nothing is read off the client thread", 0, reads.get());
		assertEquals(-1, prices.price(4151));
		assertEquals(1, client.tasks.size());
		client.runAll();
		assertEquals(3, reads.get());
		assertTrue(prices.version() != before);
		assertEquals(1, prices.price(995));
		assertEquals(1_500_000, prices.price(4151));
		assertEquals("an item with no price is unknown", -1, prices.price(1234));
		prices.request(new int[] {995, 4151, 1234});
		assertEquals("known prices are not asked for again", 0, client.tasks.size());
	}

	@Test
	public void aRequestIsBoundedAndPendingItemsAreNotAskedTwice()
	{
		Queue client = new Queue();
		AtomicInteger reads = new AtomicInteger();
		ItemPrices prices = new ItemPrices(id ->
		{
			reads.incrementAndGet();
			return 5;
		}, client.tasks::add);
		int[] many = new int[5000];
		for (int i = 0; i < many.length; i++)
		{
			many[i] = i;
		}
		prices.request(many);
		prices.request(new int[] {0, 1, 2});
		assertEquals(1, client.tasks.size());
		client.runAll();
		assertEquals(ItemPrices.MAX_PER_REQUEST, reads.get());
	}

	@Test
	public void aFailingReadIsUnknownAndPricesAreReadAgainLater()
	{
		Queue client = new Queue();
		AtomicLong now = new AtomicLong(1000);
		AtomicInteger reads = new AtomicInteger();
		ItemPrices prices = new ItemPrices(id ->
		{
			reads.incrementAndGet();
			if (id == 7)
			{
				throw new IllegalStateException("no such item");
			}
			return 10;
		}, client.tasks::add, now::get);
		prices.request(new int[] {7, 8});
		client.runAll();
		assertEquals(-1, prices.price(7));
		assertEquals(10, prices.price(8));
		now.addAndGet(ItemPrices.KEEP_MILLIS + 1);
		prices.request(new int[] {8});
		client.runAll();
		assertEquals(3, reads.get());
		prices.clear();
		assertEquals(-1, prices.price(8));
	}

	@Test
	public void aClientThreadThatRefusesLeavesNothingPending()
	{
		AtomicInteger calls = new AtomicInteger();
		ItemPrices prices = new ItemPrices(id -> 1, task ->
		{
			if (calls.incrementAndGet() == 1)
			{
				throw new IllegalStateException("shutting down");
			}
			task.run();
		});
		prices.request(new int[] {1});
		prices.request(new int[] {1});
		assertEquals(1, prices.price(1));
	}

	@Test
	public void theTablesAreReadOnceOnTheExecutorAndOnlyWhenAskedFor()
	{
		List<Runnable> executor = new ArrayList<>();
		AtomicInteger reads = new AtomicInteger();
		DropLoader loader = new DropLoader(executor::add, () ->
		{
			reads.incrementAndGet();
			return DropTables.EMPTY;
		});
		assertNull(loader.ready());
		assertEquals("nothing is read before a request", 0, executor.size());
		loader.request();
		loader.request();
		assertEquals("one task, however often it is asked", 1, executor.size());
		assertNull("callers never wait", loader.ready());
		executor.get(0).run();
		assertSame(DropTables.EMPTY, loader.ready());
		loader.request();
		assertEquals(1, executor.size());
		assertEquals(1, reads.get());

		DropLoader failing = new DropLoader(Runnable::run, () ->
		{
			throw new IllegalStateException("broken");
		});
		failing.request();
		assertNotNull(failing.ready());
		assertTrue(failing.ready().isEmpty());
	}
}
