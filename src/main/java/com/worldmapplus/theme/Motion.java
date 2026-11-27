package com.worldmapplus.theme;

import java.awt.*;
import java.util.*;

/**
 * The one fade, and how much the plugin moves. Every animation is a function of the frame's time and a start time, so a late frame
 * lands where it should and nothing counts frames, and every one ends exactly where the same change would be without animation. The
 * plugin owns no timer or thread for the map: animations advance when the map or the panel is drawn, and ask for more drawing only
 * while something is still moving.
 * <p>
 * One instance fades what it is used for: things that open fade in ({@link #restart}, {@link #begin}), and rows and buttons warm up
 * under the pointer and cool down after ({@link #level}), one value from 0 to 1 kept for each thing tracked.
 */
public final class Motion
{
	/** How long a card, a popup or the results panel takes to appear. */
	public static final long FADE_MILLIS = 120;

	/** How long a highlight takes to warm up or cool down fully. */
	private static final long WARM_MILLIS = 140;

	/** A glide to a place takes at least this long, and at most {@code GLIDE_MAX_MILLIS}. */
	public static final long GLIDE_MIN_MILLIS = 200;
	public static final long GLIDE_MAX_MILLIS = 450;

	/** Values this close to nothing are dropped, so the table only holds what is still moving or lit. */
	private static final double GONE = 0.01;

	/** The most things tracked at once. Rows come and go as lists change, so the table is kept small. */
	private static final int MAX_TRACKED = 256;

	private static volatile boolean on = true;

	private final Map<Object, Double> levels = new HashMap<>();
	private long last;
	private long stamp = Long.MIN_VALUE;
	private double stepSize;
	private long since = Long.MIN_VALUE;

	public static void use(AnimationMode next)
	{
		on = next != AnimationMode.OFF;
	}

	/** True with animations on: gliding, zooming to a place, pulses and the fades of cards, popups, rows and buttons. */
	public static boolean on()
	{
		return on;
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

	/** A colour part of the way from one to another, alpha included. */
	public static Color mix(Color from, Color to, double amount)
	{
		double a = Math.max(0, Math.min(1, amount));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * a),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * a),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * a),
			(int) Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * a));
	}

	/** The same colour with its alpha scaled, for fading something in. */
	public static Color withAlpha(Color color, double factor)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(),
			(int) Math.round(color.getAlpha() * Math.max(0, Math.min(1, factor))));
	}

	/**
	 * Moves everything tracked towards its target for the time since the last call and returns the eased value for one key: how
	 * warm a row or a button under the pointer is.
	 *
	 * @param now the frame's time in milliseconds
	 */
	public double level(Object key, boolean lit, long now)
	{
		if (levels.size() > MAX_TRACKED)
		{
			for (Iterator<Map.Entry<Object, Double>> it = levels.entrySet().iterator(); it.hasNext() && levels.size() > MAX_TRACKED / 2; )
			{
				it.next();
				it.remove();
			}
		}
		if (stamp != now)
		{
			// How far a value moves in this frame, the same for everything asked in it.
			stamp = now;
			stepSize = last == 0 || !on ? 1 : Math.min(1.0, Math.max(0, now - last) / (double) WARM_MILLIS);
			last = now;
		}
		double value = levels.getOrDefault(key, 0.0);
		double target = lit ? 1 : 0;
		value = value < target ? Math.min(target, value + stepSize) : Math.max(target, value - stepSize);
		if (value < GONE && !lit)
		{
			levels.remove(key);
			return 0;
		}
		if (levels.size() < MAX_TRACKED || levels.containsKey(key))
		{
			levels.put(key, value);
		}
		return ease(value);
	}

	/**
	 * Called when the thing opens or closes: the next frame starts its fade in. The start is the time of that frame, so a popup made
	 * while the map was not being drawn does not appear half faded.
	 */
	public void restart()
	{
		since = Long.MIN_VALUE;
	}

	/** The opacity to draw with in the frame at {@code now}: always 1 with animations off, and exactly 1 once the fade is done. */
	public float alpha(long now)
	{
		if (!on)
		{
			return 1f;
		}
		if (since == Long.MIN_VALUE)
		{
			since = now;
		}
		double t = progress(since, now, FADE_MILLIS);
		return t >= 1 ? 1f : (float) ease(t);
	}

	/** True while a fade in is still going, so the caller knows another frame is wanted. */
	public boolean moving(long now)
	{
		return on && since != Long.MIN_VALUE && now - since < FADE_MILLIS;
	}

	/**
	 * Sets the opacity for the frame; returns what to hand back to {@link #end}, or null when nothing was changed (the ordinary case:
	 * no allocation).
	 */
	public Composite begin(Graphics2D g, long now)
	{
		float a = alpha(now);
		if (a >= 1f)
		{
			return null;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(a));
		return old;
	}

	public static void end(Graphics2D g, Composite old)
	{
		if (old != null)
		{
			g.setComposite(old);
		}
	}
}
