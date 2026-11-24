package com.worldmapplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.layers.PoiIcons;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

/** The tile memory budget, and the icons being read ahead of the first frame. */
public class MemoryAndIconsTest
{
	@Test
	public void theTileBudgetIsAnEighthOfTheHeapBetweenTwentyFourAndNinetySixMegabytes()
	{
		assertEquals(96L << 20, MapAssets.tileBudget(4096L << 20));
		assertEquals(96L << 20, MapAssets.tileBudget(768L << 20));
		assertEquals(64L << 20, MapAssets.tileBudget(512L << 20));
		assertEquals(32L << 20, MapAssets.tileBudget(256L << 20));
		assertEquals(24L << 20, MapAssets.tileBudget(128L << 20));
		assertEquals(24L << 20, MapAssets.tileBudget(32L << 20));
		assertEquals("an unknown limit gets the usual budget", 96L << 20, MapAssets.tileBudget(0));
	}

	@Test
	public void iconsAreReadAheadInSmallTasksOffTheCallingThread()
	{
		PoiIcons icons = new PoiIcons();
		Set<String> names = new LinkedHashSet<>();
		for (int i = 0; i < 20; i++)
		{
			names.add("air_altar_icon.png");
			names.add("nothing_" + i + ".png");
		}
		names.add("blood_altar_icon.png");
		Deque<Runnable> queue = new ArrayDeque<>();
		icons.preload(queue::add, names);
		assertEquals("one task at a time", 1, queue.size());
		int tasks = 0;
		while (!queue.isEmpty())
		{
			queue.poll().run();
			assertTrue(queue.size() <= 1);
			tasks++;
		}
		assertEquals("six names a task", (names.size() + 5) / 6, tasks);
		assertNotNull(icons.get("air_altar_icon.png"));
		assertNotNull(icons.get("blood_altar_icon.png"));
	}
}
