package com.worldmapplus.drops;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.annotation.*;
import org.slf4j.*;

/**
 * Reads the drop tables the first time something asks for them, as one task on RuneLite's shared executor, and never at plugin start.
 * Asking again while the task runs, or after, does nothing more. Callers never wait: they see null until the tables are ready and look
 * again later (the map looks once a frame, a field read).
 */
public final class DropLoader
{
	private static final Logger log = LoggerFactory.getLogger(DropLoader.class);

	private final Executor executor;
	private final Supplier<DropTables> source;
	private final AtomicBoolean asked = new AtomicBoolean();
	private volatile DropTables tables;

	public DropLoader(Executor executor, Supplier<DropTables> source)
	{
		this.executor = executor;
		this.source = source;
	}

	/** The tables, or null while they have not been read yet. */
	@Nullable
	public DropTables ready()
	{
		return tables;
	}

	/** Starts reading the tables if nobody has asked before. Returns at once. */
	public void request()
	{
		if (tables != null || !asked.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			executor.execute(() ->
			{
				DropTables read;
				try
				{
					long start = System.nanoTime();
					read = source.get();
					log.debug("World Map Plus read {} drop tabs in {} ms", read.tabCount(), (System.nanoTime() - start) / 1_000_000L);
				}
				catch (RuntimeException e)
				{
					log.warn("World Map Plus could not read its drop tables", e);
					read = DropTables.EMPTY;
				}
				tables = read == null ? DropTables.EMPTY : read;
			});
		}
		catch (RuntimeException e)
		{
			// The executor refused (shutting down): let a later request try again.
			asked.set(false);
		}
	}
}
