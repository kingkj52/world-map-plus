package com.worldmapplus.farming;

import javax.annotation.Nullable;

/**
 * Says in words what is in a patch and when it will be ready, from what was last seen.
 * <p>
 * A crop moves on one stage every {@code tick} minutes. When the plugin did not watch the last move,
 * the next one is somewhere in the coming {@code tick} minutes, so the finish is a range: the stages
 * still to come, less one, up to all of them, times the tick, less the time since the patch was seen.
 * When the plugin did watch the last move, the range closes to one moment.
 */
public final class Forecast
{
	private Forecast()
	{
	}

	/** The two lines a card shows for a patch. */
	public static final class Result
	{
		private final String headline;
		private final String timing;

		Result(String headline, String timing)
		{
			this.headline = headline;
			this.timing = timing;
		}

		/** What is in the patch, such as {@code Ranarr, growing (stage 2 of 5)}. */
		public String getHeadline()
		{
			return headline;
		}

		/** When it will be ready, or what to do about it. Empty when there is nothing to add. */
		public String getTiming()
		{
			return timing;
		}
	}

	public static Result describe(@Nullable PatchState state, Observation seen, long nowMillis)
	{
		if (state == null)
		{
			return new Result("Not recognised", "");
		}
		String name = state.getProduce().getName();
		double elapsed = Math.max(0, (nowMillis - seen.getSeenMillis()) / 60000.0);
		switch (state.getCrop())
		{
			case GROWING:
				if ("Weeds".equals(name))
				{
					return weeds(state.getStage());
				}
				if ("Scarecrow".equals(name))
				{
					return new Result("Scarecrow", "");
				}
				return growing(state, name, seen.isExact(), elapsed);
			case HARVESTABLE:
				return harvestable(state, name, seen.isExact(), elapsed);
			case DISEASED:
				return new Result(name + ", diseased", "Cure it or it will die");
			case DEAD:
				return new Result(name + ", dead", "Clear it before planting again");
			case FILLING:
				return new Result(name + ", filling", "");
			default:
				return new Result("Empty", "");
		}
	}

	private static Result weeds(int stage)
	{
		if (stage <= 0)
		{
			return new Result("Empty", "Raked and ready to plant");
		}
		return new Result(stage >= 3 ? "Overgrown with weeds" : "Weeds growing back", "Rake it before planting");
	}

	private static Result growing(PatchState state, String name, boolean exact, double elapsed)
	{
		String headline = name + ", growing (stage " + (state.getStage() + 1) + " of " + state.getStages() + ")";
		int left = state.stagesLeft();
		int tick = state.getTickMinutes();
		if (left <= 0 || tick <= 0)
		{
			return new Result(headline, "");
		}
		return new Result(headline, "Ready " + window("", left, tick, exact, elapsed));
	}

	private static Result harvestable(PatchState state, String name, boolean exact, double elapsed)
	{
		int tick = state.getTickMinutes();
		int left = state.stagesLeft();
		if (tick > 0 && left > 0)
		{
			return new Result(name + ", picked, growing back", "Full " + window("", left, tick, exact, elapsed));
		}
		return new Result(name + ", ready to harvest", "");
	}

	/** {@code in 40 to 60 min}, {@code within 20 min}, {@code in about 1h 5m}, or {@code by now}. */
	static String window(String prefix, int stagesLeft, int tickMinutes, boolean exact, double elapsedMinutes)
	{
		double latest = stagesLeft * (double) tickMinutes - elapsedMinutes;
		double earliest = (stagesLeft - 1) * (double) tickMinutes - elapsedMinutes;
		if (latest <= 0)
		{
			return prefix + "by now";
		}
		if (exact)
		{
			return prefix + "in about " + duration(latest);
		}
		if (earliest <= 0)
		{
			return prefix + "within " + duration(latest);
		}
		return prefix + "in " + duration(earliest) + " to " + duration(latest);
	}

	/** {@code 45 min}, {@code 1h 5m}, {@code 2h}. Rounded up to the minute. */
	public static String duration(double minutes)
	{
		long total = (long) Math.ceil(minutes);
		if (total < 60)
		{
			return Math.max(1, total) + " min";
		}
		long hours = total / 60;
		long rest = total % 60;
		return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
	}

	/** {@code seen just now}, {@code seen 5 min ago}, {@code seen 2h 10m ago}, {@code seen 3 days ago}. */
	public static String seenText(long seenMillis, long nowMillis)
	{
		long minutes = Math.max(0, (nowMillis - seenMillis) / 60000);
		if (minutes < 1)
		{
			return "seen just now";
		}
		if (minutes >= 60 * 48)
		{
			return "seen " + (minutes / (60 * 24)) + " days ago";
		}
		return "seen " + duration(minutes) + " ago";
	}
}
