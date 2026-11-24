package com.worldmapplus.search;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Assume;
import org.junit.Test;

/** Measures what one keystroke of the search costs, on the render thread. Runs only when WORLDMAPPLUS_PERF is set. */
public class SearchCostTest
{
	@Test
	public void keystrokes()
	{
		Assume.assumeTrue(System.getenv("WORLDMAPPLUS_PERF") != null);
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		MapAssets assets = new MapAssets(new Gson(), executor);
		SearchIndex index = assets.getSearchIndex();
		SearchContext context = new SearchContext(true, 0, 3222.5, 3218.5, assets.getGazetteer());
		for (String query : new String[] {"a", "e", "ba", "bank", "gold", "the", "region:varrock shop", "kind:shop cost:<500"})
		{
			for (int i = 0; i < 50; i++)
			{
				index.search(query, 200, context);
			}
			int runs = 200;
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
			long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
			long start = threads.getCurrentThreadCpuTime();
			int found = 0;
			for (int i = 0; i < runs; i++)
			{
				found = index.search(query, 200, context).size();
			}
			System.out.printf("search \"%s\": %.3f ms cpu, %d KB allocated, %d results%n", query,
				(threads.getCurrentThreadCpuTime() - start) / 1e6 / runs,
				(threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - bytes) / 1024 / runs, found);
		}
		executor.shutdownNow();
	}
}
