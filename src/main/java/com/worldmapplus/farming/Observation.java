package com.worldmapplus.farming;

import javax.annotation.Nullable;

/**
 * What a patch looked like the last time the player was near enough to see it.
 * <p>
 * The game only tells the plugin about a patch while the player is close, so the plugin keeps what it
 * last saw and works out the rest. {@code exact} is true when the plugin watched the patch move on to
 * its next growth stage, so that moment is known to the second and not just to within one stage.
 */
public final class Observation
{
	private final int value;
	private final long seenMillis;
	private final boolean exact;

	public Observation(int value, long seenMillis, boolean exact)
	{
		this.value = value;
		this.seenMillis = seenMillis;
		this.exact = exact;
	}

	/** The patch's variable. */
	public int getValue()
	{
		return value;
	}

	public long getSeenMillis()
	{
		return seenMillis;
	}

	public boolean isExact()
	{
		return exact;
	}

	/** The form kept in the settings: {@code value:seconds:exact}. */
	public String write()
	{
		return value + ":" + (seenMillis / 1000) + ":" + (exact ? 1 : 0);
	}

	/** Reads what {@link #write()} wrote. Anything else gives null. */
	@Nullable
	public static Observation read(@Nullable String text)
	{
		if (text == null)
		{
			return null;
		}
		String[] parts = text.split(":");
		if (parts.length < 2)
		{
			return null;
		}
		try
		{
			int value = Integer.parseInt(parts[0].trim());
			long seconds = Long.parseLong(parts[1].trim());
			boolean exact = parts.length > 2 && "1".equals(parts[2].trim());
			if (value < 0 || seconds < 0)
			{
				return null;
			}
			return new Observation(value, seconds * 1000, exact);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
