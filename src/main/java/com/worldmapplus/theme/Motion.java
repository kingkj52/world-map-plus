package com.worldmapplus.theme;

/**
 * How much the plugin moves, and the one curve and duration it moves with. Every animation is a function of the frame's time
 * and a start time, so a late frame lands where it should and nothing counts frames, and every one ends exactly where the
 * same change would be without animation. The plugin owns no timer or thread for this: animations advance when the map or the
 * panel is drawn, and ask for more drawing only while something is still moving.
 */
public final class Motion
{
	/** How long a card, a popup or the results panel takes to appear. */
	public static final long FADE_MILLIS = 120;

	/** A glide to a place takes at least this long, and at most {@code GLIDE_MAX_MILLIS}. */
	public static final long GLIDE_MIN_MILLIS = 200;
	public static final long GLIDE_MAX_MILLIS = 450;

	private static volatile AnimationMode mode = AnimationMode.ON;

	private Motion()
	{
	}

	public static void use(AnimationMode next)
	{
		mode = next == null ? AnimationMode.ON : next;
	}

	public static AnimationMode mode()
	{
		return mode;
	}

	/** Gliding, zooming to a place and pulses: only in the full mode. */
	public static boolean full()
	{
		return mode == AnimationMode.ON;
	}

	/** The short fades of cards, popups, rows and buttons: in the full and the reduced mode. */
	public static boolean fades()
	{
		return mode != AnimationMode.OFF;
	}

	/** How far along something is: 0 at its start, 1 from its end on. A start in the future counts as 0. */
	public static double progress(long since, long now, long duration)
	{
		if (duration <= 0 || now >= since + duration)
		{
			return 1;
		}
		return now <= since ? 0 : (now - since) / (double) duration;
	}

	/** A smooth start and end between 0 and 1. */
	public static double ease(double t)
	{
		double c = Math.max(0, Math.min(1, t));
		return c * c * (3 - 2 * c);
	}

	/** How long a glide over this many screen pixels takes. */
	public static long glideMillis(double pixels)
	{
		return Math.max(GLIDE_MIN_MILLIS, Math.min(GLIDE_MAX_MILLIS, GLIDE_MIN_MILLIS + (long) (pixels / 6)));
	}
}
