package com.worldmapplus.ui;

import com.worldmapplus.WorldMapPlusConfig;
import java.awt.Rectangle;

/**
 * Works out where the floating map goes inside the game view.
 * <p>
 * The rectangle always lies inside the view the game draws the scene in. The floating map is drawn
 * under the game's interface, so panels such as the inventory and chat are never covered by it, and
 * this keeps it from running into the edges of the scene as well.
 */
public final class HudLayout
{
	/** Gap between the floating map and the edge of the view. */
	static final int MARGIN = 10;

	/**
	 * The least wide and the least tall the floating map is allowed to be. Its buttons stack in two columns on a map this short,
	 * and a list popped up beside them still has room to be read, so the controls, the map and the popups stay usable.
	 */
	static final int MIN_WIDTH = 180;

	static final int MIN_HEIGHT = 150;

	private HudLayout()
	{
	}

	/**
	 * @param viewport the game view: where the 3D scene is drawn
	 * @param percent  the share of the view's width and height to cover, 100 for all of it
	 */
	public static Rectangle compute(Rectangle viewport, int percent, WorldMapPlusConfig.HudCorner corner)
	{
		if (percent >= 100)
		{
			return new Rectangle(viewport);
		}

		int clamped = Math.max(5, percent);
		int width = Math.min(viewport.width, Math.max(MIN_WIDTH, viewport.width * clamped / 100));
		int height = Math.min(viewport.height, Math.max(MIN_HEIGHT, viewport.height * clamped / 100));

		int left = viewport.x + MARGIN;
		int right = viewport.x + viewport.width - width - MARGIN;
		int top = viewport.y + MARGIN;
		int bottom = viewport.y + viewport.height - height - MARGIN;
		int centreX = viewport.x + (viewport.width - width) / 2;
		int centreY = viewport.y + (viewport.height - height) / 2;

		int x;
		int y;
		switch (corner)
		{
			case TOP_RIGHT:
				x = right;
				y = top;
				break;
			case BOTTOM_LEFT:
				x = left;
				y = bottom;
				break;
			case BOTTOM_RIGHT:
				x = right;
				y = bottom;
				break;
			case CENTRE:
				x = centreX;
				y = centreY;
				break;
			case TOP_LEFT:
			default:
				x = left;
				y = top;
				break;
		}

		x = Math.max(viewport.x, Math.min(x, viewport.x + viewport.width - width));
		y = Math.max(viewport.y, Math.min(y, viewport.y + viewport.height - height));
		return new Rectangle(x, y, width, height);
	}
}
