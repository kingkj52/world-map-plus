package com.worldmapplus.ui;

import java.util.Locale;
import javax.annotation.*;
import lombok.*;

/**
 * A measuring tool: pick a start tile, move the pointer, read the distance.
 * <p>
 * Reports both the walking distance, the larger of the two axis differences because the game moves
 * diagonally, and the straight line distance.
 */
@Getter
public final class Ruler
{
	private boolean active;
	private int mapId;
	private double startX;
	private double startY;

	public void start(int mapId, double x, double y)
	{
		this.active = true;
		this.mapId = mapId;
		this.startX = Math.floor(x) + 0.5;
		this.startY = Math.floor(y) + 0.5;
	}

	public void stop()
	{
		active = false;
	}

	/** Whole tiles walked to reach a point, moving diagonally where possible. */
	public int walkingTiles(double x, double y)
	{
		return (int) Math.max(Math.abs(Math.floor(x) - Math.floor(startX)), Math.abs(Math.floor(y) - Math.floor(startY)));
	}

	/** Straight line distance in tiles. */
	public double straightTiles(double x, double y)
	{
		return Math.hypot(Math.floor(x) + 0.5 - startX, Math.floor(y) + 0.5 - startY);
	}

	/** A short description of a measurement, or null when not measuring. */
	@Nullable
	public String describe(double x, double y)
	{
		if (!active)
		{
			return null;
		}
		return walkingTiles(x, y) + " tiles  (" + String.format(Locale.ROOT, "%.1f", straightTiles(x, y)) + " straight)";
	}
}
