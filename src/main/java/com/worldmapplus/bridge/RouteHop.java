package com.worldmapplus.bridge;

/**
 * One jump in a route Shortest Path found: where it is taken from, where it arrives, and what it is,
 * such as {@code Ardougne cloak: Kandarin Monastery} or {@code Charter ship}.
 */
public final class RouteHop
{
	private final double fromX;
	private final double fromY;
	private final double toX;
	private final double toY;
	private final int fromPlane;
	private final int toPlane;
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

	public double getFromX()
	{
		return fromX;
	}

	public double getFromY()
	{
		return fromY;
	}

	/** The floor the jump is taken from. */
	public int getFromPlane()
	{
		return fromPlane;
	}

	/** The floor it arrives on. */
	public int getToPlane()
	{
		return toPlane;
	}

	public double getToX()
	{
		return toX;
	}

	public double getToY()
	{
		return toY;
	}

	/** What the jump is, never empty. */
	public String getLabel()
	{
		return label;
	}
}
