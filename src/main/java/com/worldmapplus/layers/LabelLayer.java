package com.worldmapplus.layers;

import com.worldmapplus.data.MapElements;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the game's own text labels on the map: the names of places, towns, regions and seas.
 * <p>
 * The wiki's tiles carry the icons and no text, so without this the map shows no names at all. The labels are
 * the game's own, with its text size and colour, placed by coordinates. Larger text is for larger things and
 * is shown from further away; small text is shown once the map is close enough to have room for it. A label
 * that would land on one already drawn is left out, larger first, so names do not pile up.
 */
public final class LabelLayer
{
	/** Zoom, in pixels per tile, from which labels of each size show, and until which. Index is the game's size. */
	private static final double[] FROM = {3.0, 0.8, 0.1};
	private static final double[] UNTIL = {64.0, 64.0, 3.5};

	/** Label text for the two floors of a dungeon, which only make sense close up. */
	private static final double LEVEL_FROM = 3.0;

	private static final Font[] FONTS = {
		new Font(Font.SANS_SERIF, Font.PLAIN, 11),
		new Font(Font.SANS_SERIF, Font.BOLD, 13),
		new Font(Font.SANS_SERIF, Font.BOLD, 17)
	};
	private static final Color OUTLINE = new Color(0, 0, 0, 220);
	private static final int GAP = 2;

	private final MapElements elements;
	private final List<Rectangle> placed = new ArrayList<>();

	public LabelLayer(MapElements elements)
	{
		this.elements = elements;
	}

	/**
	 * @param plane the floor being shown. On a map without floors only the ground floor's labels show.
	 */
	public void paint(Graphics2D g, ViewTransform view, MapInfo map, int plane)
	{
		if (map == null)
		{
			return;
		}
		List<MapElements.Label> labels = elements.labelsOn(map.getId());
		if (labels.isEmpty())
		{
			return;
		}
		double zoom = view.getPixelsPerTile();
		placed.clear();
		Composite oldComposite = g.getComposite();
		try
		{
			// Larger labels first, so they get their room before the small ones.
			for (int scale = 2; scale >= 0; scale--)
			{
				if (zoom < FROM[scale] * 0.8 || zoom > UNTIL[scale] * 1.3)
				{
					continue;
				}
				float alpha = fade(zoom, FROM[scale], UNTIL[scale]);
				g.setFont(FONTS[scale]);
				FontMetrics metrics = g.getFontMetrics();
				for (MapElements.Label label : labels)
				{
					if (label.getScale() == scale && label.getPlane() == plane)
					{
						drawLabel(g, view, metrics, label, alpha, zoom);
					}
				}
			}
		}
		finally
		{
			g.setComposite(oldComposite);
		}
	}

	private void drawLabel(Graphics2D g, ViewTransform view, FontMetrics metrics, MapElements.Label label, float alpha,
		double zoom)
	{
		if (label.getText().endsWith(" level") && zoom < LEVEL_FROM)
		{
			return;
		}
		double sx = view.screenX(label.getX());
		double sy = view.screenY(label.getY());
		String[] lines = label.getLines();
		int lineHeight = metrics.getHeight();
		int width = 0;
		for (String line : lines)
		{
			width = Math.max(width, metrics.stringWidth(line));
		}
		int height = lineHeight * lines.length;
		int left = (int) Math.round(sx - width / 2.0);
		int top = (int) Math.round(sy - height / 2.0);
		Rectangle box = new Rectangle(left - GAP, top - GAP, width + 2 * GAP, height + 2 * GAP);
		if (box.x > view.getLeft() + view.getWidth() || box.y > view.getTop() + view.getHeight()
			|| box.x + box.width < view.getLeft() || box.y + box.height < view.getTop())
		{
			return;
		}
		for (Rectangle other : placed)
		{
			if (other.intersects(box))
			{
				return;
			}
		}
		placed.add(box);

		g.setComposite(AlphaComposite.SrcOver.derive(alpha));
		Color color = new Color(label.getColor() & 0xFFFFFF);
		for (int i = 0; i < lines.length; i++)
		{
			int lineWidth = metrics.stringWidth(lines[i]);
			int x = (int) Math.round(sx - lineWidth / 2.0);
			int y = top + i * lineHeight + metrics.getAscent();
			g.setColor(OUTLINE);
			g.drawString(lines[i], x - 1, y - 1);
			g.drawString(lines[i], x + 1, y - 1);
			g.drawString(lines[i], x - 1, y + 1);
			g.drawString(lines[i], x + 1, y + 1);
			g.setColor(color);
			g.drawString(lines[i], x, y);
		}
	}

	/** Full strength inside the range, easing out a little either side of it. */
	static float fade(double zoom, double from, double until)
	{
		if (zoom < from)
		{
			return (float) Math.max(0, 1 - (from - zoom) / (from * 0.2));
		}
		if (zoom > until)
		{
			return (float) Math.max(0, 1 - (zoom - until) / (until * 0.3));
		}
		return 1f;
	}

	/** True when labels of this game size show at this zoom at all. For tests. */
	public static boolean showsAt(int scale, double zoom)
	{
		return scale >= 0 && scale < FROM.length && zoom >= FROM[scale] && zoom <= UNTIL[scale];
	}
}
