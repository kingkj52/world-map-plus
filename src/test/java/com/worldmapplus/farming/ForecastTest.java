package com.worldmapplus.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ForecastTest
{
	private static final long MINUTE = 60_000L;

	private static final Produce RANARR = new Produce("Ranarr", 20, 5, 0, 3);
	private static final Produce BUSH = new Produce("Redberry", 20, 6, 20, 5);
	private static final Produce WEEDS = new Produce("Weeds", 5, 4, 0, 1);

	private static Forecast.Result growing(int stage, long seenAt, long now, boolean exact)
	{
		return Forecast.describe(new PatchState(RANARR, CropState.GROWING, stage),
			new Observation(5, seenAt, exact), now);
	}

	@Test
	public void aCropNotWatchedGivesARangeOfOneTick()
	{
		// Seen at stage 0 with four growth ticks to go: ready between 60 and 80 minutes from then.
		Forecast.Result r = growing(0, 0, 0, false);
		assertEquals("Ranarr, growing (stage 1 of 5)", r.getHeadline());
		assertEquals("Ready in 1h to 1h 20m", r.getTiming());
	}

	@Test
	public void timePassedIsTakenOffBothEnds()
	{
		Forecast.Result r = growing(0, 0, 30 * MINUTE, false);
		assertEquals("Ready in 30 min to 50 min", r.getTiming());
	}

	@Test
	public void whenTheEarliestHasPassedItSaysWithin()
	{
		Forecast.Result r = growing(0, 0, 70 * MINUTE, false);
		assertEquals("Ready within 10 min", r.getTiming());
	}

	@Test
	public void aWatchedChangeGivesOneMoment()
	{
		Forecast.Result r = growing(1, 0, 5 * MINUTE, true);
		// Three ticks to go from a watched stage: 60 minutes, less the five that have passed.
		assertEquals("Ready in about 55 min", r.getTiming());
	}

	@Test
	public void aCropPastItsLatestTimeIsReadyByNow()
	{
		Forecast.Result r = growing(3, 0, 25 * MINUTE, false);
		assertEquals("Ready by now", r.getTiming());
	}

	@Test
	public void weedsAreDescribedByHowOvergrownTheyAre()
	{
		Observation seen = new Observation(0, 0, false);
		assertEquals("Empty", Forecast.describe(new PatchState(WEEDS, CropState.GROWING, 0), seen, 0).getHeadline());
		assertEquals("Weeds growing back",
			Forecast.describe(new PatchState(WEEDS, CropState.GROWING, 2), seen, 0).getHeadline());
		assertEquals("Overgrown with weeds",
			Forecast.describe(new PatchState(WEEDS, CropState.GROWING, 3), seen, 0).getHeadline());
	}

	@Test
	public void diseaseDeathAndHarvestAreSaidPlainly()
	{
		Observation seen = new Observation(0, 0, false);
		assertEquals("Ranarr, diseased",
			Forecast.describe(new PatchState(RANARR, CropState.DISEASED, 1), seen, 0).getHeadline());
		assertEquals("Ranarr, dead",
			Forecast.describe(new PatchState(RANARR, CropState.DEAD, 1), seen, 0).getHeadline());
		assertEquals("Ranarr, ready to harvest",
			Forecast.describe(new PatchState(RANARR, CropState.HARVESTABLE, 2), seen, 0).getHeadline());
	}

	@Test
	public void aPickedBushSaysWhenItIsFullAgain()
	{
		Forecast.Result r = Forecast.describe(new PatchState(BUSH, CropState.HARVESTABLE, 2),
			new Observation(5, 0, false), 0);
		assertEquals("Redberry, picked, growing back", r.getHeadline());
		assertTrue(r.getTiming(), r.getTiming().startsWith("Full in"));
		Forecast.Result full = Forecast.describe(new PatchState(BUSH, CropState.HARVESTABLE, 4),
			new Observation(5, 0, false), 0);
		assertEquals("Redberry, ready to harvest", full.getHeadline());
	}

	@Test
	public void anUnknownStateSaysSo()
	{
		assertEquals("Not recognised", Forecast.describe(null, new Observation(1, 0, false), 0).getHeadline());
	}

	@Test
	public void durationsAreShort()
	{
		assertEquals("1 min", Forecast.duration(0.2));
		assertEquals("45 min", Forecast.duration(45));
		assertEquals("1h", Forecast.duration(60));
		assertEquals("1h 5m", Forecast.duration(65));
		assertEquals("2h", Forecast.duration(120));
	}

	@Test
	public void whenSeenIsInWords()
	{
		assertEquals("seen just now", Forecast.seenText(0, 20_000));
		assertEquals("seen 5 min ago", Forecast.seenText(0, 5 * MINUTE));
		assertEquals("seen 2h 10m ago", Forecast.seenText(0, 130 * MINUTE));
		assertEquals("seen 3 days ago", Forecast.seenText(0, 3 * 24 * 60 * MINUTE));
	}
}
