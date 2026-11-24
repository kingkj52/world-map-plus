package com.worldmapplus.ui;

import com.worldmapplus.theme.Palette;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.annotation.Nullable;

/**
 * The small column of buttons in the corner of the map: zoom in, zoom out and locate me.
 * <p>
 * These are the plugin's own drawing and its own hit testing. They are not game widgets and add
 * nothing to any game menu.
 */
public final class MapButtons
{
	public enum Button
	{
		LAYERS("", "Layers"),
		LINE_UP("", "Line up with the game map, so other plugins' lines such as Quest Helper's show here"),
		ZOOM_IN("+", "Zoom in"),
		ZOOM_OUT("−", "Zoom out"),
		LOCATE("Me", "Centre on me");

		private final String label;
		private final String tip;

		Button(String label, String tip)
		{
			this.label = label;
			this.tip = tip;
		}

		/** What the button does, for the tooltip. */
		public String tip()
		{
			return tip;
		}
	}

	static final int SIZE = 28;
	static final int GAP = 4;
	static final int MARGIN = 10;


	private final Rectangle[] rects = new Rectangle[Button.values().length];
	private boolean lineUpShown;
	private boolean lineUpOn;
	private boolean zoomShown = true;

	/** Whether the zoom buttons are offered. They are not while the game's map zooms this one. */
	public void setZoomShown(boolean shown)
	{
		zoomShown = shown;
	}

	private boolean offered(Button button)
	{
		if (button == Button.LINE_UP)
		{
			return lineUpShown;
		}
		if (button == Button.ZOOM_IN || button == Button.ZOOM_OUT)
		{
			return zoomShown;
		}
		return true;
	}

	/** Whether the line up button is offered, which it is where the game's own map is underneath, and whether it is on. */
	public void setLineUp(boolean shown, boolean on)
	{
		lineUpShown = shown;
		lineUpOn = on;
	}

	/** Places the buttons against the bottom right of the map. */
	public void layout(Rectangle map)
	{
		layout(map, false);
	}

	/**
	 * Places the buttons against the bottom right of the map, in one column when the map is tall enough and in more columns,
	 * each to the left of the last, when it is not, so that every button is inside the map at any size.
	 *
	 * @param floors whether the two floor buttons go above the layers button, as part of the same stack
	 */
	public void layout(Rectangle map, boolean floors)
	{
		int step = SIZE + GAP;
		int right = map.x + map.width - MARGIN - SIZE;
		int bottom = map.y + map.height - MARGIN - SIZE;
		int perColumn = Math.max(1, (map.height - 2 * MARGIN + GAP) / step);
		// From the bottom up: locate, zoom out, zoom in, line up when offered, layers, then the floor pair.
		Button[] all = Button.values();
		int index = 0;
		for (int i = all.length - 1; i >= 0; i--)
		{
			Button button = all[i];
			if (!offered(button))
			{
				rects[button.ordinal()] = null;
				continue;
			}
			rects[button.ordinal()] = slot(right, bottom, perColumn, index++);
		}
		if (floors)
		{
			floorDown = slot(right, bottom, perColumn, index++);
			floorUp = slot(right, bottom, perColumn, index);
		}
		else
		{
			floorDown = null;
			floorUp = null;
		}
	}

	private static Rectangle slot(int right, int bottom, int perColumn, int index)
	{
		int step = SIZE + GAP;
		return new Rectangle(right - (index / perColumn) * step, bottom - (index % perColumn) * step, SIZE, SIZE);
	}

	private Rectangle floorUp;
	private Rectangle floorDown;

	/** Where the floor button for going up goes, or an empty rectangle when the floor buttons are not part of the stack. */
	public Rectangle floorUpSlot()
	{
		return floorUp == null ? new Rectangle() : new Rectangle(floorUp);
	}

	/** Where the floor button for going down goes, or an empty rectangle when the floor buttons are not part of the stack. */
	public Rectangle floorDownSlot()
	{
		return floorDown == null ? new Rectangle() : new Rectangle(floorDown);
	}

	/** The rectangle around every button placed, floor buttons included, or an empty rectangle before the first layout. */
	public Rectangle block()
	{
		Rectangle block = null;
		for (Rectangle rect : rects)
		{
			block = union(block, rect);
		}
		block = union(block, floorUp);
		block = union(block, floorDown);
		return block == null ? new Rectangle() : block;
	}

	/** True when a rectangle touches any button placed, floor buttons included. */
	public boolean covers(Rectangle other)
	{
		for (Rectangle rect : rects)
		{
			if (rect != null && rect.intersects(other))
			{
				return true;
			}
		}
		return floorUp != null && floorUp.intersects(other) || floorDown != null && floorDown.intersects(other);
	}

	private static Rectangle union(Rectangle a, Rectangle b)
	{
		return a == null ? b : b == null ? a : a.union(b);
	}

	/** The button under a canvas point, or null. */
	@Nullable
	public Button hit(int x, int y)
	{
		for (Button button : Button.values())
		{
			Rectangle rect = rects[button.ordinal()];
			if (rect != null && rect.contains(x, y))
			{
				return button;
			}
		}
		return null;
	}

	public Rectangle boundsOf(Button button)
	{
		Rectangle rect = rects[button.ordinal()];
		return rect == null ? new Rectangle() : new Rectangle(rect);
	}

	/** Three stacked diamonds, the usual sign for map layers. */
	private static void paintLayersIcon(Graphics2D g, Rectangle rect)
	{
		int cx = rect.x + rect.width / 2;
		int cy = rect.y + rect.height / 2;
		g.setColor(Theme.TEXT);
		for (int i = -1; i <= 1; i++)
		{
			int y = cy + i * 5;
			int[] xs = {cx - 8, cx, cx + 8, cx};
			int[] ys = {y, y - 4, y, y + 4};
			if (i == 1)
			{
				g.fillPolygon(xs, ys, 4);
			}
			else
			{
				g.drawPolygon(xs, ys, 4);
			}
		}
	}

	/** A ring with a cross through it, the sign for lining up with something. */
	private static void paintLineUpIcon(Graphics2D g, Rectangle rect, boolean on)
	{
		int cx = rect.x + rect.width / 2;
		int cy = rect.y + rect.height / 2;
		g.setColor(on ? Theme.ACCENT : Theme.TEXT);
		g.setStroke(new BasicStroke(1.6f));
		g.drawOval(cx - 6, cy - 6, 12, 12);
		g.drawLine(cx - 10, cy, cx + 10, cy);
		g.drawLine(cx, cy - 10, cx, cy + 10);
		g.setStroke(new BasicStroke(1f));
	}

	private final Fade fade = new Fade();

	public void paint(Graphics2D g, @Nullable Button hovered)
	{
		long now = System.currentTimeMillis();
		g.setStroke(new BasicStroke(1f));
		for (Button button : Button.values())
		{
			Rectangle rect = rects[button.ordinal()];
			if (rect == null)
			{
				continue;
			}
			g.setColor(Fade.mix(Theme.PANEL, Theme.BUTTON_HOVER, fade.level(button, button == hovered, now)));
			g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 6, 6);
			if (button == Button.LINE_UP && lineUpOn)
			{
				g.setColor(Theme.PANEL_ACTIVE);
				g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 6, 6);
			}
			g.setColor(button == Button.LINE_UP && lineUpOn ? Theme.ACCENT : Theme.BORDER);
			g.drawRoundRect(rect.x, rect.y, rect.width - 1, rect.height - 1, 6, 6);

			if (button == Button.LINE_UP)
			{
				paintLineUpIcon(g, rect, lineUpOn);
				continue;
			}
			if (button == Button.LAYERS)
			{
				paintLayersIcon(g, rect);
				continue;
			}
			g.setFont(button == Button.LOCATE ? Palette.SMALL_BOLD : Palette.BUTTON_FONT);
			FontMetrics metrics = g.getFontMetrics();
			g.setColor(Theme.TEXT);
			int tx = rect.x + (rect.width - metrics.stringWidth(button.label)) / 2;
			int ty = rect.y + (rect.height - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(button.label, tx, ty);
		}
	}
}
