package com.worldmapplus.ui;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Text and placement helpers shared by everything the map pops up over itself: the layers list, the right click menu, the
 * cards and the tags. A popup is never wider or taller than the map it is in, so a long name is cut short with an ellipsis and
 * its full text is shown in a tag while the pointer is on it.
 */
final class PopupText
{
	/** The ellipsis put where a name was cut. */
	static final String ELLIPSIS = "…";

	/** Gap kept between a popup and the edge of the map. */
	static final int EDGE = 6;

	private PopupText()
	{
	}

	/** Metrics for a font, from a throwaway image, for sizing before anything is drawn. */
	static FontMetrics metrics(Font font)
	{
		Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
		try
		{
			return probe.getFontMetrics(font);
		}
		finally
		{
			probe.dispose();
		}
	}

	/** The text cut to fit {@code maxWidth} pixels, ending in an ellipsis when it had to be cut. */
	static String clip(String text, FontMetrics metrics, int maxWidth)
	{
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		if (maxWidth <= metrics.stringWidth(ELLIPSIS))
		{
			return "";
		}
		int end = text.length();
		while (end > 0 && metrics.stringWidth(text.substring(0, end) + ELLIPSIS) > maxWidth)
		{
			end--;
		}
		return text.substring(0, end).trim() + ELLIPSIS;
	}

	/** True when {@link #clip} would cut the text. */
	static boolean isCut(String text, FontMetrics metrics, int maxWidth)
	{
		return metrics.stringWidth(text) > maxWidth;
	}

	/** The text in lines no wider than {@code maxWidth}, broken at spaces, or anywhere for a word that is too long. */
	static List<String> wrap(String text, FontMetrics metrics, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String joined = line.length() == 0 ? word : line + " " + word;
			if (metrics.stringWidth(joined) <= maxWidth)
			{
				line = new StringBuilder(joined);
				continue;
			}
			if (line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder();
			}
			String rest = word;
			while (metrics.stringWidth(rest) > maxWidth && rest.length() > 1)
			{
				int cut = rest.length() - 1;
				while (cut > 1 && metrics.stringWidth(rest.substring(0, cut)) > maxWidth)
				{
					cut--;
				}
				lines.add(rest.substring(0, cut));
				rest = rest.substring(cut);
			}
			line.append(rest);
		}
		if (line.length() > 0 || lines.isEmpty())
		{
			lines.add(line.toString());
		}
		return lines;
	}

	/**
	 * Where a tag of this size goes beside a row: under it when there is room, over it otherwise, kept inside {@code region}
	 * (which the caller has already kept clear of the map's controls).
	 */
	static Rectangle tagRect(int width, int height, Rectangle row, Rectangle region)
	{
		int w = Math.min(width, region.width);
		int h = Math.min(height, region.height);
		int x = Math.max(region.x, Math.min(row.x, region.x + region.width - w));
		int below = row.y + row.height + 2;
		int y = below + h <= region.y + region.height ? below : row.y - h - 2;
		y = Math.max(region.y, Math.min(y, region.y + region.height - h));
		return new Rectangle(x, y, w, h);
	}
}
