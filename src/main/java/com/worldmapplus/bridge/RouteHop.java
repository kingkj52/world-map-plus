package com.worldmapplus.bridge;

import lombok.*;

/**
 * One jump in a route Shortest Path found: where it is taken from, where it arrives, and what it is,
 * such as {@code Ardougne cloak: Kandarin Monastery} or {@code Charter ship}.
 */
@Getter
public final class RouteHop
{
	private final double fromX;
	private final double fromY;
	private final double toX;
	private final double toY;
	/** The floor the jump is taken from. */
	private final int fromPlane;
	/** The floor it arrives on. */
	private final int toPlane;
	/** What the jump is, never empty. */
	private final String label;

	RouteHop(double fromX, double fromY, int fromPlane, double toX, double toY, int toPlane, String label)
	{
		this.fromPlane = fromPlane;
		this.toPlane = toPlane;
		this.fromX = fromX;
		this.fromY = fromY;
		this.toX = toX;
		this.toY = toY;
		this.label = label;
	}
}
