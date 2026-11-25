package com.worldmapplus.view;

import com.worldmapplus.map.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.tiles.*;
import java.awt.*;
import javax.annotation.*;
import lombok.*;

/**
 * The state of one open map: which map, where the camera is, how far in it is zoomed, and what
 * the mouse is doing. Free of any game types so all of it can be tested without a client.
 * <p>
 * One call to {@link #update} per frame advances zoom, inertia and following and returns the
 * transform to draw with. Input methods only record intent; they never draw.
 * <p>
 * Not thread safe. The overlay and the input handlers must reach it from the same thread, or
 * hand it work through a queue.
 */
public final class MapViewer
{
	/** Pointer movement beyond this many pixels turns a press into a drag. */
	static final int DRAG_THRESHOLD = 4;

	/** Pixels per tile at which a map opens when nothing else is asked for. */
	public static final double DEFAULT_ZOOM = 4.0;

	/** How much of a screen's worth the camera may be panned past the map's edge. */
	private static final double OVERSCROLL = 0.25;

	@Getter
	private final Camera camera;
	@Getter
	private final ZoomModel zoom;
	private MapInfo map;
	/** The floor on show: 0 is the ground, 1 to 3 are above it. */
	@Getter
	private int floor;
	private Rectangle bounds = new Rectangle(0, 0, 1, 1);

	private double anchorX;
	private double anchorY;
	private boolean anchored;
	private long lastUpdateMillis = Long.MIN_VALUE;

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
		this.camera = new Camera(centerX, centerY);
		this.zoom = new ZoomModel(pixelsPerTile);
		this.zoom.setSmoothingSeconds(ZoomModel.DEFAULT_SMOOTHING_SECONDS);
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

	/** Switches map and aims the camera, keeping the current zoom inside the new map's limits. */
	public void showMap(MapInfo newMap, double centerX, double centerY)
	{
		if (newMap != this.map)
		{
			this.floor = 0;
		}
		this.map = newMap;
		camera.jumpTo(centerX, centerY);
		anchored = false;
		applyLimits();
	}

	/**
	 * Like {@link #showMap} but, on the map already shown, the camera glides there over a short time. It ends exactly where
	 * {@code showMap} would put it. On another map, or when the camera is already there, it is {@code showMap}.
	 */
	public void glideTo(MapInfo newMap, double centerX, double centerY, long nowMillis)
	{
		double pixels = Math.hypot(centerX - camera.getX(), centerY - camera.getY()) * zoom.pixelsPerTile();
		if (newMap != this.map || pixels < 1)
		{
			showMap(newMap, centerX, centerY);
			return;
		}
		camera.glideTo(centerX, centerY, nowMillis, Motion.glideMillis(pixels));
		anchored = false;
	}

	/** True while the camera glides or the zoom eases. */
	public boolean isMoving()
	{
		return camera.isGliding() || zoom.isAnimating();
	}

	/** Zoom limits for the current map: from showing the whole map a little smaller, to the global maximum. */
	private void applyLimits()
	{
		double min = ZoomModel.MIN_PIXELS_PER_TILE;
		if (map != null)
		{
			double fitX = bounds.width / (double) Math.max(1, map.getMaxX() - map.getMinX());
			double fitY = bounds.height / (double) Math.max(1, map.getMaxY() - map.getMinY());
			min = Math.max(ZoomModel.MIN_PIXELS_PER_TILE, Math.min(fitX, fitY) * 0.6);
		}
		zoom.setLimits(min, ZoomModel.MAX_PIXELS_PER_TILE);
	}

	public void onWheel(double preciseRotation, int mouseX, int mouseY, long nowMillis)
	{
		zoom.onWheel(preciseRotation, nowMillis);
		camera.cancelGlide();
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

	/** Where the current or last press began, so a click can be placed after the release. */
	public Point getPressPoint()
	{
		return new Point(pressX, pressY);
	}

	/** Returns true if this movement was part of a drag that moved the map. */
	public boolean onDrag(int x, int y, long nowMillis)
	{
		if (!pressed)
		{
			return false;
		}
		if (!dragging && Math.hypot(x - pressX, y - pressY) < DRAG_THRESHOLD)
		{
			return false;
		}
		if (!dragging)
		{
			dragging = true;
			camera.beginDrag(nowMillis);
			lastX = pressX;
			lastY = pressY;
		}
		camera.drag(x - lastX, y - lastY, currentPixelsPerTile(), nowMillis);
		lastX = x;
		lastY = y;
		return true;
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
			camera.endDrag(nowMillis);
		}
		return !wasDrag;
	}

	/** Drops a press or drag that was in progress, so a map that comes back never thinks the mouse is still down. */
	public void cancelGesture()
	{
		pressed = false;
		dragging = false;
		camera.cancelDrag();
	}

	public double currentPixelsPerTile()
	{
		return zoom.pixelsPerTile();
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

		double before = zoom.pixelsPerTile();
		double after = zoom.update(dt);

		if (after != before)
		{
			ViewTransform old = new ViewTransform(camera.getX(), camera.getY(), before,
				bounds.x, bounds.y, bounds.width, bounds.height);
			double ax = anchored && !camera.isFollowing() ? anchorX : bounds.x + bounds.width / 2.0;
			double ay = anchored && !camera.isFollowing() ? anchorY : bounds.y + bounds.height / 2.0;
			ViewTransform zoomed = old.zoomedAbout(ax, ay, after);
			camera.setPosition(zoomed.getCenterX(), zoomed.getCenterY());
		}

		camera.advanceGlide(nowMillis);
		camera.update(dt, after, followX, followY);
		clampCamera(after);

		return new ViewTransform(camera.getX(), camera.getY(), after,
			bounds.x, bounds.y, bounds.width, bounds.height);
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
		double x = Math.max(map.getMinX() - marginX, Math.min(map.getMaxX() + marginX, camera.getX()));
		double y = Math.max(map.getMinY() - marginY, Math.min(map.getMaxY() + marginY, camera.getY()));
		if (x != camera.getX() || y != camera.getY())
		{
			camera.setPosition(x, y);
		}
	}
}
