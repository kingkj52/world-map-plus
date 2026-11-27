package com.worldmapplus.view;

import com.worldmapplus.map.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.tiles.*;
import java.awt.*;
import javax.annotation.*;
import lombok.*;

/**
 * The state of one open map: which map, where the camera is, how far in it is zoomed, and what the mouse is doing. Every
 * surface (the window over the world map, the floating map and the extra windows) moves through this one model, so a wheel turn
 * or a drag feels the same on each. Free of any game types and of any clock of its own, so all of it can be tested without a
 * client.
 * <p>
 * Zoom is held as the base 2 logarithm of pixels per tile, so equal wheel steps feel equal at every scale. Each wheel notch adds
 * a step to a target, and the shown zoom eases towards that target. Notches that arrive close together build momentum, and
 * every notch is scaled by the momentum, so a long run of scrolling covers the huge range quickly while a single notch stays
 * fine. A pause resets it.
 * <p>
 * Dragging moves the centre one to one with the pointer. On release the last drag speed carries on and fades out. Following
 * the player eases the centre towards a target, and any drag switches following off so the player never fights the map.
 * <p>
 * One call to {@link #update} per frame advances zoom, inertia, glides and following and returns the transform to draw with.
 * Input methods only record intent; they never draw. Not thread safe: the overlay and the input handlers must reach it from the
 * same thread, or hand it work through a queue.
 */
public final class MapViewer
{
	/** Pointer movement beyond this many pixels turns a press into a drag. */
	static final int DRAG_THRESHOLD = 4;

	/** Pixels per tile at which a map opens when nothing else is asked for. */
	public static final double DEFAULT_ZOOM = 4.0;

	/** Zoomed all the way out: the whole world fits in a few hundred pixels. */
	public static final double MIN_PIXELS_PER_TILE = 0.05;

	/** Zoomed all the way in: a single tile is 32 pixels wide. */
	public static final double MAX_PIXELS_PER_TILE = 32.0;

	/** How much of a screen's worth the camera may be panned past the map's edge. */
	private static final double OVERSCROLL = 0.25;

	/** Gap between notches beyond which momentum starts again from nothing. */
	static final long MOMENTUM_WINDOW_MILLIS = 320;

	/** Momentum builds by one per notch up to this. */
	static final double MAX_MOMENTUM = 14.0;

	/** Zoom change of a single unaccelerated notch, in doublings. */
	static final double BASE_STEP = 0.14;

	/** Extra step per unit of momentum, as a multiple of the base step. */
	static final double MOMENTUM_GAIN = 0.4;

	/** The shown and target zoom are treated as equal once this close, in doublings. */
	private static final double SETTLED = 0.0005;

	/** Time constant of the inertia fade in seconds. */
	static final double INERTIA_SECONDS = 0.28;

	/** Speed, in pixels per second, below which inertia stops. */
	static final double INERTIA_STOP = 12.0;

	/**
	 * Drag events closer together than this are treated as this far apart. Several mouse events can carry the same timestamp,
	 * and dividing a movement by almost no time gave absurd speeds.
	 */
	static final long MIN_INTERVAL_MILLIS = 8;

	/** The fastest a released drag can carry on, in pixels per second. */
	static final double MAX_SPEED = 4000.0;

	/** Smoothing of the measured drag speed. Higher reacts faster. */
	private static final double SPEED_BLEND = 0.5;

	/** A release after the pointer has rested this long carries no inertia. */
	static final long RESTING_MILLIS = 90;

	/** Time constant when easing to a follow target, in seconds. */
	static final double FOLLOW_SECONDS = 0.06;

	private MapInfo map;
	/** The floor on show: 0 is the ground, 1 to 3 are above it. */
	@Getter
	private int floor;
	private Rectangle bounds = new Rectangle(0, 0, 1, 1);
	private long lastUpdateMillis = Long.MIN_VALUE;

	/** The centre of the view, in world tiles. */
	@Getter
	private double x;
	@Getter
	private double y;
	@Getter
	private boolean following;
	/** True while a released drag carries on. */
	@Getter
	private boolean coasting;
	/** Drag speed in pixels per second, screen axes. */
	private double speedX;
	private double speedY;
	private long lastDragMillis;

	/** A glide to a place, started by a jump to a search result or a card: from where the camera was, over a set time. */
	@Getter
	private boolean gliding;
	private double glideFromX;
	private double glideFromY;
	private double glideToX;
	private double glideToY;
	private long glideStart;
	private long glideMillis;

	@Getter
	private double minPixelsPerTile = MIN_PIXELS_PER_TILE;
	@Getter
	private double maxPixelsPerTile = MAX_PIXELS_PER_TILE;
	private double speed = 1.0;
	private double acceleration = 1.0;
	/** How long the shown zoom takes to close most of the gap to the target, in seconds. */
	private double smoothingSeconds = 0.07;
	private double shownLog2;
	private double targetLog2;
	private double momentum;
	private long lastNotchMillis = Long.MIN_VALUE;
	private double anchorX;
	private double anchorY;
	private boolean anchored;

	private boolean pressed;
	/** True while a drag is in progress, so a click is not also reported for it. */
	@Getter
	private boolean dragging;
	private int pressX;
	private int pressY;
	private int lastX;
	private int lastY;

	public MapViewer(MapInfo map, double centerX, double centerY, double pixelsPerTile)
	{
		this.map = map;
		this.x = centerX;
		this.y = centerY;
		shownLog2 = targetLog2 = toLog2(clampZoom(pixelsPerTile));
	}

	@Nullable
	public MapInfo getMap()
	{
		return map;
	}

	public void setFloor(int floor)
	{
		this.floor = Math.max(0, Math.min(TileSource.MAX_FLOOR, floor));
	}

	/**
	 * How the wheel feels: a scale for every notch (1 is the default), how much momentum adds (0 turns acceleration off), and how
	 * long the zoom takes to settle in seconds (0 jumps straight to the target).
	 */
	public void setFeel(double speed, double acceleration, double smoothingSeconds)
	{
		this.speed = Math.max(0.1, Math.min(5.0, speed));
		this.acceleration = Math.max(0.0, Math.min(5.0, acceleration));
		this.smoothingSeconds = Math.max(0.0, Math.min(1.0, smoothingSeconds));
	}

	public void setFollowing(boolean following)
	{
		this.following = following;
		if (following)
		{
			stopCoasting();
			gliding = false;
		}
	}

	/** Moves the centre at once, stopping any glide or inertia. */
	public void jumpTo(double newX, double newY)
	{
		x = newX;
		y = newY;
		gliding = false;
		stopCoasting();
	}

	/** Moves the centre without interrupting following or inertia, for zoom anchoring, clamping and lining up. */
	public void setPosition(double newX, double newY)
	{
		x = newX;
		y = newY;
	}

	/** Switches map and aims the camera, keeping the current zoom inside the new map's limits. */
	public void showMap(MapInfo newMap, double centerX, double centerY)
	{
		if (newMap != this.map)
		{
			this.floor = 0;
		}
		this.map = newMap;
		jumpTo(centerX, centerY);
		anchored = false;
		applyLimits();
	}

	/**
	 * Like {@link #showMap} but, on the map already shown, the camera glides there over a short time. It ends exactly where
	 * {@code showMap} would put it. On another map, or when the camera is already there, it is {@code showMap}.
	 */
	public void glideTo(MapInfo newMap, double centerX, double centerY, long nowMillis)
	{
		double pixels = Math.hypot(centerX - x, centerY - y) * pixelsPerTile();
		if (newMap != this.map || pixels < 1)
		{
			showMap(newMap, centerX, centerY);
			return;
		}
		glide(centerX, centerY, nowMillis, Motion.glideMillis(pixels));
		anchored = false;
	}

	/** Moves to a place over {@code millis} with a smooth start and end, by the clock the frames carry. */
	void glide(double newX, double newY, long nowMillis, long millis)
	{
		glideFromX = x;
		glideFromY = y;
		glideToX = newX;
		glideToY = newY;
		glideStart = nowMillis;
		glideMillis = Math.max(1, millis);
		gliding = true;
		stopCoasting();
	}

	/** Moves a glide on to the frame at {@code nowMillis}. A late frame lands where the glide would be by then, or at its end. */
	void advanceGlide(long nowMillis)
	{
		if (!gliding)
		{
			return;
		}
		double t = Motion.progress(glideStart, nowMillis, glideMillis);
		if (t >= 1)
		{
			x = glideToX;
			y = glideToY;
			gliding = false;
			return;
		}
		double e = Motion.ease(t);
		x = glideFromX + (glideToX - glideFromX) * e;
		y = glideFromY + (glideToY - glideFromY) * e;
	}

	/** True while the camera glides or the zoom eases. */
	public boolean isMoving()
	{
		return gliding || isAnimating();
	}

	/** Zoom limits for the current map: from showing the whole map a little smaller, to the global maximum. */
	private void applyLimits()
	{
		double min = MIN_PIXELS_PER_TILE;
		if (map != null)
		{
			double fitX = bounds.width / (double) Math.max(1, map.getMaxX() - map.getMinX());
			double fitY = bounds.height / (double) Math.max(1, map.getMaxY() - map.getMinY());
			min = Math.max(MIN_PIXELS_PER_TILE, Math.min(fitX, fitY) * 0.6);
		}
		setLimits(min, MAX_PIXELS_PER_TILE);
	}

	/**
	 * Narrows the zoom range, so a small dungeon cannot be shrunk to a speck. The limits never go outside the global ones, and
	 * the shown and target zoom are pulled inside.
	 */
	void setLimits(double min, double max)
	{
		double lo = Math.max(MIN_PIXELS_PER_TILE, Math.min(min, max));
		double hi = Math.min(MAX_PIXELS_PER_TILE, Math.max(min, max));
		minPixelsPerTile = Math.min(lo, hi);
		maxPixelsPerTile = hi;
		shownLog2 = Math.max(toLog2(minPixelsPerTile), Math.min(toLog2(maxPixelsPerTile), shownLog2));
		targetLog2 = Math.max(toLog2(minPixelsPerTile), Math.min(toLog2(maxPixelsPerTile), targetLog2));
	}

	/** Forces a zoom into the current limits. */
	public double clampZoom(double pixelsPerTile)
	{
		if (Double.isNaN(pixelsPerTile))
		{
			return Math.max(minPixelsPerTile, Math.min(maxPixelsPerTile, 4.0));
		}
		return Math.max(minPixelsPerTile, Math.min(maxPixelsPerTile, pixelsPerTile));
	}

	static double toLog2(double value)
	{
		return Math.log(value) / Math.log(2.0);
	}

	/** Sets the zoom at once, in pixels per tile. */
	public void jumpZoom(double pixelsPerTile)
	{
		shownLog2 = targetLog2 = toLog2(clampZoom(pixelsPerTile));
		momentum = 0;
		lastNotchMillis = Long.MIN_VALUE;
	}

	/** Eases towards a new zoom without snapping. */
	public void easeZoom(double pixelsPerTile)
	{
		setTargetLog2(toLog2(clampZoom(pixelsPerTile)));
	}

	private void setTargetLog2(double log2)
	{
		targetLog2 = Math.max(toLog2(minPixelsPerTile), Math.min(toLog2(maxPixelsPerTile), log2));
	}

	/** The zoom shown now, in pixels per tile. */
	public double pixelsPerTile()
	{
		return Math.pow(2.0, shownLog2);
	}

	/** The zoom the shown one eases towards. */
	public double targetZoom()
	{
		return Math.pow(2.0, targetLog2);
	}

	/** True while the shown zoom is still moving towards the target. */
	boolean isAnimating()
	{
		return Math.abs(targetLog2 - shownLog2) > SETTLED;
	}

	/** Momentum right now, so the acceleration can be checked and tuned. */
	double momentum()
	{
		return momentum;
	}

	/**
	 * A wheel turn at a pointer. Negative rotation, a wheel pushed away from the user, zooms in, about the pointer.
	 *
	 * @param preciseRotation notches, fractional on high resolution wheels
	 * @param nowMillis       a monotonic timestamp in milliseconds
	 */
	public void onWheel(double preciseRotation, int mouseX, int mouseY, long nowMillis)
	{
		if (preciseRotation != 0 && !Double.isNaN(preciseRotation))
		{
			double notches = Math.abs(preciseRotation);
			boolean continuing = lastNotchMillis != Long.MIN_VALUE && nowMillis - lastNotchMillis <= MOMENTUM_WINDOW_MILLIS;
			momentum = continuing ? Math.min(MAX_MOMENTUM, momentum + Math.min(1.0, notches)) : 0.0;
			lastNotchMillis = nowMillis;
			double step = BASE_STEP * speed * notches * (1.0 + MOMENTUM_GAIN * acceleration * momentum);
			setTargetLog2(targetLog2 + (preciseRotation < 0 ? 1.0 : -1.0) * step);
		}
		gliding = false;
		anchorX = mouseX;
		anchorY = mouseY;
		anchored = true;
	}

	public void onPress(int x, int y, long nowMillis)
	{
		pressed = true;
		dragging = false;
		pressX = lastX = x;
		pressY = lastY = y;
	}

	/** Returns true if this movement was part of a drag that moved the map. */
	public boolean onDrag(int px, int py, long nowMillis)
	{
		if (!pressed)
		{
			return false;
		}
		if (!dragging && Math.hypot(px - pressX, py - pressY) < DRAG_THRESHOLD)
		{
			return false;
		}
		if (!dragging)
		{
			dragging = true;
			following = false;
			gliding = false;
			stopCoasting();
			lastDragMillis = nowMillis;
			lastX = pressX;
			lastY = pressY;
		}
		double dx = px - lastX;
		double dy = py - lastY;
		double ppt = pixelsPerTile();
		x -= dx / ppt;
		y += dy / ppt;
		long elapsed = Math.max(MIN_INTERVAL_MILLIS, nowMillis - lastDragMillis);
		speedX = clampSpeed(speedX * (1 - SPEED_BLEND) + dx * 1000.0 / elapsed * SPEED_BLEND);
		speedY = clampSpeed(speedY * (1 - SPEED_BLEND) + dy * 1000.0 / elapsed * SPEED_BLEND);
		lastDragMillis = nowMillis;
		lastX = px;
		lastY = py;
		return true;
	}

	private static double clampSpeed(double speed)
	{
		return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, speed));
	}

	/** Ends a press. Returns true when it was a click, meaning it never turned into a drag. */
	public boolean onRelease(long nowMillis)
	{
		if (!pressed)
		{
			return false;
		}
		pressed = false;
		boolean wasDrag = dragging;
		dragging = false;
		if (wasDrag)
		{
			boolean resting = nowMillis - lastDragMillis > RESTING_MILLIS;
			coasting = !resting && Math.hypot(speedX, speedY) > INERTIA_STOP;
			if (!coasting)
			{
				stopCoasting();
			}
		}
		return !wasDrag;
	}

	/** Drops a press or drag that was in progress, so a map that comes back never thinks the mouse is still down. */
	public void cancelGesture()
	{
		pressed = false;
		dragging = false;
		stopCoasting();
	}

	private void stopCoasting()
	{
		coasting = false;
		speedX = 0;
		speedY = 0;
	}

	/**
	 * Advances one frame and returns the transform to draw with.
	 *
	 * @param followX world x to follow while following is on
	 * @param followY world y to follow while following is on
	 */
	public ViewTransform update(long nowMillis, Rectangle newBounds, double followX, double followY)
	{
		boolean resized = newBounds.width != bounds.width || newBounds.height != bounds.height;
		bounds = new Rectangle(newBounds);
		if (resized)
		{
			applyLimits();
		}

		double dt = lastUpdateMillis == Long.MIN_VALUE ? 0.0
			: Math.min(0.1, Math.max(0.0, (nowMillis - lastUpdateMillis) / 1000.0));
		lastUpdateMillis = nowMillis;

		double before = pixelsPerTile();
		double gap = targetLog2 - shownLog2;
		if (Math.abs(gap) <= SETTLED || smoothingSeconds <= 0 || dt <= 0 && gap == 0)
		{
			shownLog2 = targetLog2;
		}
		else if (dt > 0)
		{
			shownLog2 += gap * (1.0 - Math.exp(-dt / smoothingSeconds));
		}
		double after = pixelsPerTile();

		if (after != before)
		{
			ViewTransform old = new ViewTransform(x, y, before, bounds.x, bounds.y, bounds.width, bounds.height);
			double ax = anchored && !following ? anchorX : bounds.x + bounds.width / 2.0;
			double ay = anchored && !following ? anchorY : bounds.y + bounds.height / 2.0;
			ViewTransform zoomed = old.zoomedAbout(ax, ay, after);
			x = zoomed.getCenterX();
			y = zoomed.getCenterY();
		}

		advanceGlide(nowMillis);
		if (dt > 0 && following)
		{
			double blend = 1.0 - Math.exp(-dt / FOLLOW_SECONDS);
			x += (followX - x) * blend;
			y += (followY - y) * blend;
		}
		else if (dt > 0 && coasting)
		{
			x -= speedX * dt / after;
			y += speedY * dt / after;
			double fade = Math.exp(-dt / INERTIA_SECONDS);
			speedX *= fade;
			speedY *= fade;
			if (Math.hypot(speedX, speedY) < INERTIA_STOP)
			{
				stopCoasting();
			}
		}
		clampCamera(after);

		return new ViewTransform(x, y, after, bounds.x, bounds.y, bounds.width, bounds.height);
	}

	/** Keeps the centre within the map, plus a margin so edges can be brought to the middle. */
	private void clampCamera(double pixelsPerTile)
	{
		if (map == null)
		{
			return;
		}
		double marginX = bounds.width / pixelsPerTile * OVERSCROLL;
		double marginY = bounds.height / pixelsPerTile * OVERSCROLL;
		x = Math.max(map.getMinX() - marginX, Math.min(map.getMaxX() + marginX, x));
		y = Math.max(map.getMinY() - marginY, Math.min(map.getMaxY() + marginY, y));
	}
}
