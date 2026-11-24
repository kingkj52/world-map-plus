package com.worldmapplus.drops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntToLongFunction;
import java.util.function.LongSupplier;

/**
 * Prices of the items in the drop tabs, read from RuneLite's own item manager (the public guide prices RuneLite itself keeps; this
 * plugin makes no request of any kind). The manager is only read on the client thread: {@link #request} hands one task to the
 * client thread's queue that reads the missing prices, at most {@link #MAX_PER_REQUEST} of them, and keeps them. Everything else,
 * on any thread, reads what was kept. Prices are forgotten after {@link #KEEP_MILLIS} so a view made later reads them again, and the
 * memory is bounded by {@link #MAX_KNOWN}.
 */
public final class ItemPrices implements PriceSource
{
	static final int MAX_PER_REQUEST = 600;
	static final int MAX_KNOWN = 10_000;
	static final long KEEP_MILLIS = 30 * 60_000L;

	private final IntToLongFunction reader;
	private final Consumer<Runnable> clientThread;
	private final LongSupplier clock;
	/** Price per item id, -1 for an item the manager has no price for. */
	private final Map<Integer, Long> known = new ConcurrentHashMap<>();
	private final Set<Integer> pending = ConcurrentHashMap.newKeySet();
	private final AtomicInteger version = new AtomicInteger();
	private volatile long since;

	/**
	 * @param reader reads one price on the client thread (the item manager's price of an item)
	 * @param clientThread runs a task later on the client thread ({@code ClientThread.invokeLater})
	 */
	public ItemPrices(IntToLongFunction reader, Consumer<Runnable> clientThread)
	{
		this(reader, clientThread, System::currentTimeMillis);
	}

	ItemPrices(IntToLongFunction reader, Consumer<Runnable> clientThread, LongSupplier clock)
	{
		this.reader = reader;
		this.clientThread = clientThread;
		this.clock = clock;
		this.since = clock.getAsLong();
	}

	@Override
	public long price(int itemId)
	{
		Long value = known.get(itemId);
		return value == null ? -1 : value;
	}

	@Override
	public int version()
	{
		return version.get();
	}

	@Override
	public void request(int[] itemIds)
	{
		long now = clock.getAsLong();
		if (now - since > KEEP_MILLIS || known.size() > MAX_KNOWN)
		{
			known.clear();
			since = now;
		}
		List<Integer> wanted = new ArrayList<>();
		for (int id : itemIds)
		{
			if (wanted.size() >= MAX_PER_REQUEST)
			{
				break;
			}
			if (id >= 0 && !known.containsKey(id) && pending.add(id))
			{
				wanted.add(id);
			}
		}
		if (wanted.isEmpty())
		{
			return;
		}
		try
		{
			clientThread.accept(() -> readOnClientThread(wanted));
		}
		catch (RuntimeException e)
		{
			pending.removeAll(wanted);
		}
	}

	/** Runs on the client thread: reads each price once. A price of 0 or less, or one that cannot be read, is unknown. */
	private void readOnClientThread(List<Integer> ids)
	{
		for (int id : ids)
		{
			long value;
			try
			{
				long price = reader.applyAsLong(id);
				value = price > 0 ? price : -1;
			}
			catch (RuntimeException e)
			{
				value = -1;
			}
			known.put(id, value);
			pending.remove(id);
		}
		version.incrementAndGet();
	}

	/** Forgets everything, for the plugin's shutdown. */
	public void clear()
	{
		known.clear();
		pending.clear();
		version.incrementAndGet();
	}
}
