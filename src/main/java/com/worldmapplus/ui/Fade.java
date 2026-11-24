package com.worldmapplus.ui;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Eases a highlight in and out, so a row or a button warms up under the pointer and cools down after it instead of
 * switching. One value from 0 to 1 is kept for each thing being tracked, and moves towards its target as time passes.
 */
final class Fade
{
	/** How long a full change takes. */
	private static final long MILLIS = 140;

	/** Values this close to nothing are dropped, so the table only holds what is still moving or lit. */
	private static final double GONE = 0.01;

	/** The most things tracked at once. Rows come and go as lists change, so the table is kept small. */
	private static final int MAX_TRACKED = 256;

	private final Map<Object, Double> levels = new HashMap<>();
	private long last;

	/**
	 * Moves everything towards its target for the time since the last call and returns the eased value for one key.
	 *
	 * @param now the frame's time in milliseconds
	 */
	double level(Object key, boolean lit, long now)
	{
		advance(now);
		double value = levels.getOrDefault(key, 0.0);
		double target = lit ? 1 : 0;
		double step = step(now);
		if (value < target)
		{
			value = Math.min(target, value + step);
		}
		else if (value > target)
		{
			value = Math.max(target, value - step);
		}
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

	private long stamp = Long.MIN_VALUE;
	private double stepSize;

	/** How far a value moves in this frame, the same for everything asked in it. */
	private double step(long now)
	{
		if (stamp != now)
		{
			stamp = now;
			stepSize = last == 0 || !com.worldmapplus.theme.Motion.fades() ? 1 : Math.min(1.0, Math.max(0, now - last) / (double) MILLIS);
			last = now;
		}
		return stepSize;
	}

	private void advance(long now)
	{
		if (levels.size() > MAX_TRACKED)
		{
			for (Iterator<Map.Entry<Object, Double>> it = levels.entrySet().iterator(); it.hasNext() && levels.size() > MAX_TRACKED / 2; )
			{
				it.next();
				it.remove();
			}
		}
	}

	/** A smooth start and end between 0 and 1. */
	static double ease(double t)
	{
		double c = Math.max(0, Math.min(1, t));
		return c * c * (3 - 2 * c);
	}

	/** A colour part of the way from one to another, alpha included. */
	static Color mix(Color from, Color to, double amount)
	{
		double a = Math.max(0, Math.min(1, amount));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * a),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * a),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * a),
			(int) Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * a));
	}

	/** The same colour with its alpha scaled, for fading something in. */
	static Color withAlpha(Color color, double factor)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(),
			(int) Math.round(color.getAlpha() * Math.max(0, Math.min(1, factor))));
	}
}
