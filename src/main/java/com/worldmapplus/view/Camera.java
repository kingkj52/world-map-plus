package com.worldmapplus.view;

/**
 * Where the viewer is looking, how dragging moves it, and how it follows the player.
 * <p>
 * Dragging moves the centre one-to-one with the pointer. On release the last drag speed carries
 * on and fades out, which is what makes the map feel snappy rather than sticky. Following the
 * player eases the centre towards a target, and any drag switches following off so the player
 * never fights the map.
 * <p>
 * Pure logic with no clock of its own, so it can be tested without a game.
 */
public final class Camera
{
	/** Time constant of the inertia fade in seconds. */
	static final double INERTIA_SECONDS = 0.28;

	/** Speed, in pixels per second, below which inertia stops. */
	static final double INERTIA_STOP = 12.0;

	/**
	 * Drag events closer together than this are treated as this far apart. Several mouse events can
	 * carry the same timestamp, and dividing a movement by almost no time gave absurd speeds.
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

	private double x;
	private double y;
	private boolean following;

	private boolean dragging;
	private long lastDragMillis;
	/** Drag speed in pixels per second, screen axes. */
	private double speedX;
	private double speedY;
	private boolean coasting;

	/** A glide to a place, started by a jump to a search result or a card: from where the camera was, over a set time. */
	private boolean gliding;
	private double glideFromX;
	private double glideFromY;
	private double glideToX;
	private double glideToY;
	private long glideStart;
	private long glideMillis;

	public Camera(double x, double y)
	{
		this.x = x;
		this.y = y;
	}

	public double getX()
	{
		return x;
	}

	public double getY()
	{
		return y;
	}

	public boolean isFollowing()
	{
		return following;
	}

	public boolean isDragging()
	{
		return dragging;
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

	public void jumpTo(double newX, double newY)
	{
		x = newX;
		y = newY;
		gliding = false;
		stopCoasting();
	}

	/**
	 * Moves to a place over {@code millis} with a smooth start and end, by the clock the frames carry. The end is exactly the
	 * place {@link #jumpTo} would have gone to.
	 */
	public void glideTo(double newX, double newY, long nowMillis, long millis)
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
	public void advanceGlide(long nowMillis)
	{
		if (!gliding)
		{
			return;
		}
		double t = com.worldmapplus.theme.Motion.progress(glideStart, nowMillis, glideMillis);
		if (t >= 1)
		{
			x = glideToX;
			y = glideToY;
			gliding = false;
			return;
		}
		double e = com.worldmapplus.theme.Motion.ease(t);
		x = glideFromX + (glideToX - glideFromX) * e;
		y = glideFromY + (glideToY - glideFromY) * e;
	}

	public boolean isGliding()
	{
		return gliding;
	}

	/** Stops where it is, for a drag or the wheel taking over. */
	public void cancelGlide()
	{
		gliding = false;
	}

	/** Moves the centre without interrupting following or inertia, for zoom anchoring and clamping. */
	public void setPosition(double newX, double newY)
	{
		x = newX;
		y = newY;
	}

	public void beginDrag(long nowMillis)
	{
		dragging = true;
		following = false;
		gliding = false;
		stopCoasting();
		lastDragMillis = nowMillis;
	}

	/**
	 * Moves the camera for a pointer movement.
	 *
	 * @param dx            pixels the pointer moved right
	 * @param dy            pixels the pointer moved down
	 * @param pixelsPerTile the scale in force
	 */
	public void drag(double dx, double dy, double pixelsPerTile, long nowMillis)
	{
		x -= dx / pixelsPerTile;
		y += dy / pixelsPerTile;

		long elapsed = Math.max(MIN_INTERVAL_MILLIS, nowMillis - lastDragMillis);
		double instantX = dx * 1000.0 / elapsed;
		double instantY = dy * 1000.0 / elapsed;
		speedX = clampSpeed(speedX * (1 - SPEED_BLEND) + instantX * SPEED_BLEND);
		speedY = clampSpeed(speedY * (1 - SPEED_BLEND) + instantY * SPEED_BLEND);
		lastDragMillis = nowMillis;
	}

	private static double clampSpeed(double speed)
	{
		return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, speed));
	}

	/** Abandons a drag in progress without any glide, for when the map is hidden mid-gesture. */
	public void cancelDrag()
	{
		dragging = false;
		stopCoasting();
	}

	public void endDrag(long nowMillis)
	{
		if (!dragging)
		{
			return;
		}
		dragging = false;
		boolean resting = nowMillis - lastDragMillis > RESTING_MILLIS;
		double speed = Math.hypot(speedX, speedY);
		coasting = !resting && speed > INERTIA_STOP;
		if (!coasting)
		{
			stopCoasting();
		}
	}

	private void stopCoasting()
	{
		coasting = false;
		speedX = 0;
		speedY = 0;
	}

	/**
	 * Advances inertia and following by one frame.
	 *
	 * @param followX world x to follow, used only while following
	 * @param followY world y to follow, used only while following
	 */
	public void update(double dtSeconds, double pixelsPerTile, double followX, double followY)
	{
		if (dtSeconds <= 0)
		{
			return;
		}

		if (following)
		{
			double blend = 1.0 - Math.exp(-dtSeconds / FOLLOW_SECONDS);
			x += (followX - x) * blend;
			y += (followY - y) * blend;
			return;
		}

		if (coasting)
		{
			x -= speedX * dtSeconds / pixelsPerTile;
			y += speedY * dtSeconds / pixelsPerTile;
			double fade = Math.exp(-dtSeconds / INERTIA_SECONDS);
			speedX *= fade;
			speedY *= fade;
			if (Math.hypot(speedX, speedY) < INERTIA_STOP)
			{
				stopCoasting();
			}
		}
	}

	public boolean isCoasting()
	{
		return coasting;
	}
}
