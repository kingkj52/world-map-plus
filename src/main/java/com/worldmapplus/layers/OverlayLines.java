package com.worldmapplus.layers;

import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;

/**
 * Reference lines drawn over the imagery: Wilderness levels and the region grid.
 * <p>
 * Both are computed from coordinates. They are public map information and read nothing from the
 * game.
 */
public final class OverlayLines
{
	private static final Color WILDERNESS = new Color(255, 70, 40, 130);
	private static final Color WILDERNESS_TEXT = new Color(255, 190, 170);
	private static final Color GRID = new Color(255, 255, 255, 70);
	private static final Color CHUNK = new Color(255, 255, 255, 28);
	private static final Color PLATE = new Color(22, 19, 15, 190);
	private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

	private OverlayLines()
	{
	}

	/** Which Wilderness level lines to draw at a zoom: every one when close, every tenth when far. */
	static int levelStep(double pixelsPerTile)
	{
		if (pixelsPerTile >= 1.2)
		{
			return 1;
		}
		return pixelsPerTile >= 0.4 ? 5 : 10;
	}

	public static void paintWilderness(Graphics2D g, ViewTransform view, MapInfo map)
	{
		if (map == null)
		{
			return;
		}
		double ppt = view.getPixelsPerTile();
		int step = levelStep(ppt);
		g.setFont(FONT);
		FontMetrics metrics = g.getFontMetrics();

		int[] souths = {WildernessLevels.SURFACE_SOUTH, WildernessLevels.UNDERGROUND_SOUTH};
		for (int south : souths)
		{
			int top = south + WildernessLevels.LEVELS * WildernessLevels.TILES_PER_LEVEL;
			if (!map.contains(WildernessLevels.MIN_X, south) && !map.contains(WildernessLevels.MIN_X, top - 1))
			{
				continue;
			}
			double x1 = view.screenX(WildernessLevels.MIN_X);
			double x2 = view.screenX(WildernessLevels.MAX_X);
			for (int level = 1; level <= WildernessLevels.LEVELS + 1; level++)
			{
				if (!drawsLine(level, step))
				{
					continue;
				}
				double y = view.screenY(WildernessLevels.lineY(south, level));
				if (y < view.getTop() - 2 || y > view.getTop() + view.getHeight() + 2)
				{
					continue;
				}
				g.setStroke(new BasicStroke(level == 1 ? 1.8f : 1f));
				g.setColor(WILDERNESS);
				g.draw(new Line2D.Double(x1, y, x2, y));
				if (level <= WildernessLevels.LEVELS)
				{
					String text = "Level " + level;
					double labelX = Math.max(x1, view.getLeft()) + 4;
					double labelY = y - 3;
					if (labelX < x2 - 60)
					{
						g.setColor(PLATE);
						g.fillRoundRect((int) labelX - 2, (int) labelY - metrics.getAscent(),
							metrics.stringWidth(text) + 4, metrics.getHeight(), 4, 4);
						g.setColor(WILDERNESS_TEXT);
						g.drawString(text, (float) labelX, (float) labelY);
					}
				}
			}
		}
	}

	/** True when the line that starts a level is drawn at this spacing. The top edge needs every level shown. */
	static boolean drawsLine(int level, int step)
	{
		if (level == WildernessLevels.LEVELS + 1)
		{
			return step == 1;
		}
		return level == 1 || level % step == 0;
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
