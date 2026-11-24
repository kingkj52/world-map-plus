package com.worldmapplus.view;

/**
 * The zoom level and how mouse wheel input changes it.
 * <p>
 * Zoom is held as the base 2 logarithm of pixels per tile, so equal wheel steps feel equal at
 * every scale. Each wheel notch adds a step to a target, and the shown zoom eases towards that
 * target.
 * <p>
 * Acceleration: notches that arrive close together build momentum, and every notch is scaled
 * by the momentum, so a long run of scrolling covers the huge range quickly while a single
 * notch stays fine. A pause resets it, so the next notch is gentle again.
 * <p>
 * The class holds no clock of its own. Callers pass in time, which keeps it deterministic to
 * test.
 */
public final class ZoomModel
{
	/** Zoomed all the way out: the whole world fits in a few hundred pixels. */
	public static final double MIN_PIXELS_PER_TILE = 0.05;

	/** Zoomed all the way in: a single tile is 32 pixels wide. */
	public static final double MAX_PIXELS_PER_TILE = 32.0;

	/** Gap between notches beyond which momentum starts again from nothing. */
	static final long MOMENTUM_WINDOW_MILLIS = 320;

	/** Momentum builds by one per notch up to this. */
	static final double MAX_MOMENTUM = 14.0;

	/** Zoom change of a single unaccelerated notch, in doublings. */
	static final double BASE_STEP = 0.14;

	/** Extra step per unit of momentum, as a multiple of the base step. */
	static final double MOMENTUM_GAIN = 0.4;

	/** How long the shown zoom takes to close most of the gap to the target, in seconds. */
	static final double DEFAULT_SMOOTHING_SECONDS = 0.07;

	/** The shown and target zoom are treated as equal once this close, in doublings. */
	private static final double SETTLED = 0.0005;

	private double minPixelsPerTile = MIN_PIXELS_PER_TILE;
	private double maxPixelsPerTile = MAX_PIXELS_PER_TILE;
	private double speed = 1.0;
	private double acceleration = 1.0;
	private double smoothingSeconds = DEFAULT_SMOOTHING_SECONDS;

	private double shownLog2;
	private double targetLog2;
	private double momentum;
	private long lastNotchMillis = Long.MIN_VALUE;

	public ZoomModel(double initialPixelsPerTile)
	{
		shownLog2 = targetLog2 = toLog2(clamp(initialPixelsPerTile));
	}

	/** Forces a value into the current limits. */
	public double clamp(double pixelsPerTile)
	{
		if (Double.isNaN(pixelsPerTile))
		{
			return Math.max(minPixelsPerTile, Math.min(maxPixelsPerTile, 4.0));
		}
		return Math.max(minPixelsPerTile, Math.min(maxPixelsPerTile, pixelsPerTile));
	}

	/**
	 * Narrows the zoom range, for instance so a small dungeon cannot be shrunk to a speck. The
	 * limits never go outside the global ones, and the shown and target zoom are pulled inside.
	 */
	public void setLimits(double min, double max)
	{
		double lo = Math.max(MIN_PIXELS_PER_TILE, Math.min(min, max));
		double hi = Math.min(MAX_PIXELS_PER_TILE, Math.max(min, max));
		minPixelsPerTile = Math.min(lo, hi);
		maxPixelsPerTile = hi;
		shownLog2 = Math.max(toLog2(minPixelsPerTile), Math.min(toLog2(maxPixelsPerTile), shownLog2));
		targetLog2 = Math.max(toLog2(minPixelsPerTile), Math.min(toLog2(maxPixelsPerTile), targetLog2));
	}

	public double getMinPixelsPerTile()
	{
		return minPixelsPerTile;
	}

	public double getMaxPixelsPerTile()
	{
		return maxPixelsPerTile;
	}

	static double toLog2(double value)
	{
		return Math.log(value) / Math.log(2.0);
	}

	/** Scales every notch. 1.0 is the default. */
	public void setSpeed(double speed)
	{
		this.speed = Math.max(0.1, Math.min(5.0, speed));
	}

	/** Scales how much momentum adds. 0 disables acceleration, 1.0 is the default. */
	public void setAcceleration(double acceleration)
	{
		this.acceleration = Math.max(0.0, Math.min(5.0, acceleration));
	}

	/** 0 makes zoom jump straight to the target. */
	public void setSmoothingSeconds(double seconds)
	{
		this.smoothingSeconds = Math.max(0.0, Math.min(1.0, seconds));
	}

	/**
	 * Registers wheel movement. Negative rotation, a wheel pushed away from the user, zooms in.
	 *
	 * @param preciseRotation notches, fractional on high resolution wheels
	 * @param nowMillis       a monotonic timestamp in milliseconds
	 */
	public void onWheel(double preciseRotation, long nowMillis)
	{
		if (preciseRotation == 0 || Double.isNaN(preciseRotation))
		{
			return;
		}

		double notches = Math.abs(preciseRotation);
		boolean continuing = lastNotchMillis != Long.MIN_VALUE
			&& nowMillis - lastNotchMillis <= MOMENTUM_WINDOW_MILLIS;
		momentum = continuing ? Math.min(MAX_MOMENTUM, momentum + Math.min(1.0, notches)) : 0.0;
		lastNotchMillis = nowMillis;

		double step = BASE_STEP * speed * notches * (1.0 + MOMENTUM_GAIN * acceleration * momentum);
		double direction = preciseRotation < 0 ? 1.0 : -1.0;
		setTargetLog2(targetLog2 + direction * step);
	}

	/** Sets the target in pixels per tile and shows it at once. */
	public void jumpTo(double pixelsPerTile)
	{
		shownLog2 = targetLog2 = toLog2(clamp(pixelsPerTile));
		momentum = 0;
		lastNotchMillis = Long.MIN_VALUE;
	}

	/** Eases towards a new zoom without snapping. */
	public void easeTo(double pixelsPerTile)
	{
		setTargetLog2(toLog2(clamp(pixelsPerTile)));
	}

	private void setTargetLog2(double log2)
	{
		targetLog2 = Math.max(toLog2(minPixelsPerTile), Math.min(toLog2(maxPixelsPerTile), log2));
	}

	/**
	 * Advances the easing and returns the zoom to draw with, in pixels per tile.
	 *
	 * @param dtSeconds time since the previous call
	 */
	public double update(double dtSeconds)
	{
		double gap = targetLog2 - shownLog2;
		if (Math.abs(gap) <= SETTLED || smoothingSeconds <= 0 || dtSeconds <= 0 && gap == 0)
		{
			shownLog2 = targetLog2;
		}
		else if (dtSeconds > 0)
		{
			double blend = 1.0 - Math.exp(-dtSeconds / smoothingSeconds);
			shownLog2 += gap * blend;
		}
		return pixelsPerTile();
	}

	public double pixelsPerTile()
	{
		return Math.pow(2.0, shownLog2);
	}

	public double targetPixelsPerTile()
	{
		return Math.pow(2.0, targetLog2);
	}

	/** True while the shown zoom is still moving towards the target. */
	public boolean isAnimating()
	{
		return Math.abs(targetLog2 - shownLog2) > SETTLED;
	}

	/** Momentum right now, exposed so the acceleration can be checked and tuned. */
	double momentum()
	{
		return momentum;
	}
}
