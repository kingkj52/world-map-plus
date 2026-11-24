package com.worldmapplus.view;

/**
 * Maps between world tile coordinates and canvas pixels for one frame of the viewer.
 * <p>
 * World coordinates are fractional tile coordinates: the tile at {@code (x, y)} covers
 * {@code [x, x + 1)} on each axis, so a tile centre is {@code x + 0.5}. North is larger y, which
 * is up on screen, so the vertical axis is flipped on the way to pixels.
 * <p>
 * Instances are immutable. Every change returns a new transform, which keeps the render and
 * input threads from ever seeing a half-updated view.
 */
public final class ViewTransform
{
	private final double centerX;
	private final double centerY;
	private final double pixelsPerTile;
	private final int left;
	private final int top;
	private final int width;
	private final int height;

	public ViewTransform(double centerX, double centerY, double pixelsPerTile,
		int left, int top, int width, int height)
	{
		if (!(pixelsPerTile > 0))
		{
			throw new IllegalArgumentException("pixelsPerTile must be positive: " + pixelsPerTile);
		}
		this.centerX = centerX;
		this.centerY = centerY;
		this.pixelsPerTile = pixelsPerTile;
		this.left = left;
		this.top = top;
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
	}

	public double getCenterX()
	{
		return centerX;
	}

	public double getCenterY()
	{
		return centerY;
	}

	public double getPixelsPerTile()
	{
		return pixelsPerTile;
	}

	public int getLeft()
	{
		return left;
	}

	public int getTop()
	{
		return top;
	}

	public int getWidth()
	{
		return width;
	}

	public int getHeight()
	{
		return height;
	}

	/** Canvas x of the vertical centre line of the view. */
	private double midX()
	{
		return left + width / 2.0;
	}

	/** Canvas y of the horizontal centre line of the view. */
	private double midY()
	{
		return top + height / 2.0;
	}

	public double screenX(double worldX)
	{
		return midX() + (worldX - centerX) * pixelsPerTile;
	}

	/** Canvas y of a world y. Larger world y is further up the screen. */
	public double screenY(double worldY)
	{
		return midY() - (worldY - centerY) * pixelsPerTile;
	}

	public double worldX(double screenX)
	{
		return centerX + (screenX - midX()) / pixelsPerTile;
	}

	public double worldY(double screenY)
	{
		return centerY - (screenY - midY()) / pixelsPerTile;
	}

	public boolean contains(double screenX, double screenY)
	{
		return screenX >= left && screenX < left + width && screenY >= top && screenY < top + height;
	}

	/** Smallest world x on screen. */
	public double minWorldX()
	{
		return worldX(left);
	}

	/** Largest world x on screen. */
	public double maxWorldX()
	{
		return worldX(left + width);
	}

	/** Smallest world y on screen (the bottom edge). */
	public double minWorldY()
	{
		return worldY(top + height);
	}

	/** Largest world y on screen (the top edge). */
	public double maxWorldY()
	{
		return worldY(top);
	}

	public ViewTransform withCenter(double x, double y)
	{
		return new ViewTransform(x, y, pixelsPerTile, left, top, width, height);
	}



	/** Moves the view so the content under the given pixel shifts by the given pixel amounts. */
	public ViewTransform pannedByPixels(double dx, double dy)
	{
		return withCenter(centerX - dx / pixelsPerTile, centerY + dy / pixelsPerTile);
	}

	/**
	 * Changes the scale while keeping the world point under a canvas pixel fixed in place, which
	 * is what makes zooming feel anchored to the cursor.
	 */
	public ViewTransform zoomedAbout(double anchorScreenX, double anchorScreenY, double newPixelsPerTile)
	{
		double anchorWorldX = worldX(anchorScreenX);
		double anchorWorldY = worldY(anchorScreenY);
		double newCenterX = anchorWorldX - (anchorScreenX - midX()) / newPixelsPerTile;
		double newCenterY = anchorWorldY + (anchorScreenY - midY()) / newPixelsPerTile;
		return new ViewTransform(newCenterX, newCenterY, newPixelsPerTile, left, top, width, height);
	}
}
