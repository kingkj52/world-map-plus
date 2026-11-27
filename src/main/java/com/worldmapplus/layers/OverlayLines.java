package com.worldmapplus.layers;

import com.worldmapplus.map.*;
import com.worldmapplus.view.*;
import java.awt.*;
import java.awt.geom.*;

/**
 * The region grid drawn over the imagery, with each region's id. Computed from coordinates; it reads nothing from the game.
 */
public final class OverlayLines
{
	private static final Color GRID = new Color(255, 255, 255, 70);
	private static final Color CHUNK = new Color(255, 255, 255, 28);
	private static final Color PLATE = new Color(22, 19, 15, 190);
	private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

	private OverlayLines()
	{
	}

	/** The id RuneLite and the game give a 64 by 64 tile region. */
	static int regionId(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	public static void paintGrid(Graphics2D g, ViewTransform view, MapInfo map)
	{
		double ppt = view.getPixelsPerTile();
		if (map == null || ppt < 0.7)
		{
			return;
		}
		int minX = (int) Math.floor(view.minWorldX());
		int maxX = (int) Math.ceil(view.maxWorldX());
		int minY = (int) Math.floor(view.minWorldY());
		int maxY = (int) Math.ceil(view.maxWorldY());

		if (ppt >= 4)
		{
			g.setStroke(new BasicStroke(1f));
			g.setColor(CHUNK);
			for (int x = (minX >> 3) << 3; x <= maxX; x += 8)
			{
				double sx = view.screenX(x);
				g.draw(new Line2D.Double(sx, view.getTop(), sx, view.getTop() + view.getHeight()));
			}
			for (int y = (minY >> 3) << 3; y <= maxY; y += 8)
			{
				double sy = view.screenY(y);
				g.draw(new Line2D.Double(view.getLeft(), sy, view.getLeft() + view.getWidth(), sy));
			}
		}

		g.setStroke(new BasicStroke(1.4f));
		g.setColor(GRID);
		for (int x = (minX >> 6) << 6; x <= maxX; x += 64)
		{
			double sx = view.screenX(x);
			g.draw(new Line2D.Double(sx, view.getTop(), sx, view.getTop() + view.getHeight()));
		}
		for (int y = (minY >> 6) << 6; y <= maxY; y += 64)
		{
			double sy = view.screenY(y);
			g.draw(new Line2D.Double(view.getLeft(), sy, view.getLeft() + view.getWidth(), sy));
		}

		if (ppt >= 1.0)
		{
			g.setFont(FONT);
			FontMetrics metrics = g.getFontMetrics();
			for (int x = (minX >> 6) << 6; x <= maxX; x += 64)
			{
				for (int y = (minY >> 6) << 6; y <= maxY; y += 64)
				{
					String text = String.valueOf(regionId(x, y));
					double sx = view.screenX(x) + 3;
					double sy = view.screenY(y + 64) + metrics.getAscent() + 2;
					g.setColor(PLATE);
					g.fillRoundRect((int) sx - 2, (int) sy - metrics.getAscent(), metrics.stringWidth(text) + 4,
						metrics.getHeight(), 4, 4);
					g.setColor(Color.WHITE);
					g.drawString(text, (float) sx, (float) sy);
				}
			}
		}
	}
}
