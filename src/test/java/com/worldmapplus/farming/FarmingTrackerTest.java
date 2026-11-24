package com.worldmapplus.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class FarmingTrackerTest
{
	/** A herb patch in Falador: region 12083, the herb patch is the fourth, variable 4774. */
	private static final int X = 3052;
	private static final int Y = 3309;
	private static final int HERB_VARBIT = 4774;

	private final Map<String, String> saved = new HashMap<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private long now = 1_000_000L;
	private FarmingData data;
	private FarmingTracker tracker;

	private final FarmingTracker.Store store = new FarmingTracker.Store()
	{
		@Override
		public String get(String key)
		{
			return saved.get(key);
		}

		@Override
		public void put(String key, String value)
		{
			saved.put(key, value);
		}
	};

	@Before
	public void setUp()
	{
		data = FarmingData.load(new Gson());
		tracker = new FarmingTracker(null, data, store, () -> now);
	}

	private void ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			tracker.update(X, Y, 0, id -> varbits.getOrDefault(id, 0));
			now += 600;
		}
	}

	private FarmingData.Region falador()
	{
		return data.regionsAt(X, Y, 0).get(0);
	}

	private FarmingData.Patch herb()
	{
		for (FarmingData.Patch p : falador().getPatches())
		{
			if (p.getVarbit() == HERB_VARBIT)
			{
				return p;
			}
		}
		throw new AssertionError("no herb patch");
	}

	@Test
	public void nothingIsReadUntilTheVariablesHaveSettled()
	{
		varbits.put(HERB_VARBIT, 4);
		ticks(FarmingTracker.SETTLE_TICKS);
		assertNull(tracker.observation(falador(), herb()));
		ticks(1);
		assertNotNull(tracker.observation(falador(), herb()));
	}

	@Test
	public void whatIsSeenIsKeptWithWhenItWasSeen()
	{
		varbits.put(HERB_VARBIT, 4);
		ticks(FarmingTracker.SETTLE_TICKS + 1);
		Observation seen = tracker.observation(falador(), herb());
		assertEquals(4, seen.getValue());
		assertFalse("planted before the plugin was watching", seen.isExact());
		assertTrue(saved.containsKey("farming.12083." + HERB_VARBIT));
	}

	@Test
	public void aStageWatchedChangingIsExact()
	{
		varbits.put(HERB_VARBIT, 4);
		ticks(FarmingTracker.SETTLE_TICKS + 2);
		varbits.put(HERB_VARBIT, 5);
		ticks(1);
		Observation seen = tracker.observation(falador(), herb());
		assertEquals(5, seen.getValue());
		assertTrue(seen.isExact());
	}

	@Test
	public void aChangeThatIsNotTheNextStageIsNotExact()
	{
		varbits.put(HERB_VARBIT, 4);
		ticks(FarmingTracker.SETTLE_TICKS + 2);
		varbits.put(HERB_VARBIT, 0);
		ticks(1);
		assertFalse(tracker.observation(falador(), herb()).isExact());
	}

	@Test
	public void aChangeFoundOnReturningIsNotExact()
	{
		varbits.put(HERB_VARBIT, 4);
		ticks(FarmingTracker.SETTLE_TICKS + 1);
		// Walk away and come back: the region changes and the patch has moved on meanwhile.
		tracker.update(3200, 3000, 0, id -> 0);
		varbits.put(HERB_VARBIT, 5);
		ticks(FarmingTracker.SETTLE_TICKS + 2);
		Observation seen = tracker.observation(falador(), herb());
		assertEquals(5, seen.getValue());
		assertFalse("the change was not watched", seen.isExact());
	}

	@Test
	public void valuesTheTablesDoNotKnowAreIgnored()
	{
		varbits.put(HERB_VARBIT, 99999);
		ticks(FarmingTracker.SETTLE_TICKS + 2);
		assertNull(tracker.observation(falador(), herb()));
		assertFalse(saved.containsKey("farming.12083." + HERB_VARBIT));
	}

	@Test
	public void aNewTrackerReadsWhatWasSaved()
	{
		varbits.put(HERB_VARBIT, 6);
		ticks(FarmingTracker.SETTLE_TICKS + 1);
		FarmingTracker later = new FarmingTracker(null, data, store, () -> now);
		assertEquals(6, later.observation(falador(), herb()).getValue());
	}

	@Test
	public void forgettingTheAccountKeepsWhatIsSaved()
	{
		varbits.put(HERB_VARBIT, 6);
		ticks(FarmingTracker.SETTLE_TICKS + 1);
		tracker.onGameState(net.runelite.api.GameState.LOGIN_SCREEN);
		assertEquals("read back from the store", 6, tracker.observation(falador(), herb()).getValue());
	}

	@Test
	public void standingOutsideAnyRegionReadsNothing()
	{
		for (int i = 0; i < 10; i++)
		{
			tracker.update(3200, 3000, 0, id ->
			{
				throw new AssertionError("a variable was read away from the patches");
			});
		}
	}

	@Test
	public void theStageAdvanceTestSeesOnlyTheNextStageOfTheSameCrop()
	{
		PatchState one = data.decode("HERB", 4);
		PatchState two = data.decode("HERB", 5);
		PatchState other = data.decode("HERB", 11);
		assertTrue(FarmingTracker.advancedOneStage(one, two));
		assertFalse(FarmingTracker.advancedOneStage(two, one));
		assertFalse(FarmingTracker.advancedOneStage(one, other));
		assertFalse(FarmingTracker.advancedOneStage(null, two));
	}
}
