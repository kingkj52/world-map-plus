package com.worldmapplus.ui;

import static com.worldmapplus.theme.Palette.colour;

import com.worldmapplus.theme.*;
import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import javax.annotation.*;

/**
 * The one way the map draws a box over itself, and the rows in it: hover cards, the right click menu, the layers panel, the results
 * panel, the search bar's lists, the route panel, tags and tips. Colours come from the one lookup ({@link Palette#colour}) of the theme
 * in use, so a theme change shows from the next frame. A box is never wider or taller than the map it is in, so a long text is cut
 * short and its whole text is shown in a tag while the pointer is on it.
 * <p>
 * The static routines draw. A list of rows that scrolls and fades in (the right click menu, the layers panel, the results panel)
 * extends this class for where its rows are, its scroll bar and its fade, and draws its own row in {@link #paintRow}.
 */
abstract class Box
{
	/** The ellipsis put where a name was cut. */
	static final String ELLIPSIS = "…";

	/** Gap kept between a box and the edge of the map. */
	static final int EDGE = 6;

	/** Where the box and its rows were laid out last, and whether it shows. */
	Rectangle bounds = new Rectangle();
	Rectangle body = new Rectangle();
	boolean shown;
	final Scroller scroller = new Scroller();
	final Motion fade = new Motion();
	private final int rowHeight;
	private final int barInset;
	/** Rows flow into columns of this many rows, each this wide; a list that scrolls has one column. */
	int columns = 1;
	int perColumn = Integer.MAX_VALUE;
	int columnWidth;
	private final Rectangle cell = new Rectangle();

	Box(int rowHeight, int barInset)
	{
		this.rowHeight = rowHeight;
		this.barInset = barInset;
	}

	/** How many rows there are. */
	abstract int rowCount();

	/** Draws the box, its frame's pointer and time in {@link #mouse} and {@link #now}. */
	abstract void paintBox(Graphics2D g);

	/** Draws one row in view, in {@code row}; the drawing is clipped to the rows' area. */
	abstract void paintRow(Graphics2D g, int index, Rectangle row);

	/** The pointer, or null, and the time of the frame being drawn. */
	@Nullable
	Point mouse;
	long now;

	/** Draws the box in the frame at {@code now}, fading in when it has just opened. */
	void paint(Graphics2D g, @Nullable Point mouse, long now)
	{
		if (!shown)
		{
			return;
		}
		Composite faded = fade.begin(g, now);
		try
		{
			this.mouse = mouse;
			this.now = now;
			paintBox(g);
		}
		finally
		{
			Motion.end(g, faded);
		}
	}

	/** True when a point is anywhere on the box. */
	public boolean contains(int x, int y)
	{
		return shown && bounds.contains(x, y);
	}

	public Rectangle getBounds()
	{
		return shown ? new Rectangle(bounds) : new Rectangle();
	}

	/** True when part of the list is out of view, so it scrolls. */
	public boolean isScrollable()
	{
		return shown && scroller.isScrollable();
	}

	/** How far the list is scrolled, in pixels. */
	public int getScroll()
	{
		return scroller.offset();
	}

	/** Moves the list by rows, for the wheel. Does nothing when every row shows. */
	public void scroll(int rows)
	{
		scroller.by(rows * rowHeight);
	}

	/** True over the scroll bar, when the list scrolls. */
	public boolean scrollBarHit(int x, int y)
	{
		return isScrollable() && track().contains(x, y);
	}

	/** Moves the list so that its bar's thumb is at a height, while the bar is dragged. */
	public void dragBarTo(int y)
	{
		scroller.toTrack(y, track());
	}

	/** True while the box is still fading in. */
	boolean moving(long now)
	{
		return shown && fade.moving(now);
	}

	Rectangle track()
	{
		return new Rectangle(bounds.x + bounds.width - Scroller.WIDTH - barInset, body.y, Scroller.WIDTH, body.height);
	}

	/** The rectangle a row takes now, scrolled, whether or not it is in view. */
	Rectangle rowBox(int index, Rectangle into)
	{
		into.setBounds(body.x + index / perColumn * columnWidth, body.y + index % perColumn * rowHeight - scroller.offset(), columnWidth,
			rowHeight);
		return into;
	}

	/** The rectangle a row takes now, or null when it is not wholly in view or not there. */
	@Nullable
	public Rectangle rowRect(int index)
	{
		if (!shown || index < 0 || index >= rowCount())
		{
			return null;
		}
		Rectangle row = rowBox(index, new Rectangle());
		return body.contains(row) ? row : null;
	}

	/** The row under a point, or -1. */
	int indexAt(int x, int y)
	{
		if (!contains(x, y) || scrollBarHit(x, y) || !body.contains(x, y))
		{
			return -1;
		}
		int column = (x - body.x) / columnWidth;
		int row = (y - body.y + scroller.offset()) / rowHeight;
		int index = column < columns && row < perColumn ? column * perColumn + row : -1;
		return index < rowCount() ? index : -1;
	}

	/** Draws the rows in view, clipped to their area, then the scroll bar. */
	void paintRows(Graphics2D g)
	{
		Shape before = g.getClip();
		g.clipRect(body.x, body.y, body.width, body.height);
		for (int i = 0; i < rowCount(); i++)
		{
			rowBox(i, cell);
			if (cell.y + rowHeight > body.y && cell.y <= body.y + body.height)
			{
				paintRow(g, i, cell);
			}
		}
		g.setClip(before);
		scroller.paint(g, track());
	}

	/** A panel with a soft shadow under it, a fill and an edge (one pixel, or two in the high contrast theme). */
	static void panel(Graphics2D g, int x, int y, int width, int height)
	{
		panel(g, x, y, width, height, colour("PANEL"), colour("BORDER"));
	}

	static void panel(Graphics2D g, int x, int y, int width, int height, Color fill, Color edge)
	{
		Palette p = Palette.current();
		int inset = (int) p.edgeWidth() / 2;
		g.setColor(colour("SHADOW"));
		g.fillRoundRect(x - 1, y + 1, width + 2, height + 2, 10, 10);
		g.fillRoundRect(x - 2, y + 2, width + 4, height + 4, 12, 12);
		g.setColor(fill);
		g.fillRoundRect(x, y, width, height, 8, 8);
		g.setStroke(p.edge());
		g.setColor(edge);
		g.drawRoundRect(x + inset, y + inset, width - 1 - 2 * inset, height - 1 - 2 * inset, 8, 8);
	}

	/** Lines of text in a panel, as tags and tips show them. */
	static void lines(Graphics2D g, FontMetrics metrics, List<String> lines, int x, int y, int width, int height)
	{
		panel(g, x, y, width, height);
		g.setColor(colour("TEXT"));
		for (int i = 0; i < lines.size(); i++)
		{
			g.drawString(lines.get(i), x + 8, y + 4 + metrics.getAscent() + i * metrics.getHeight());
		}
	}

	/**
	 * The whole text of a cut label in a tag beside its row, wrapped to at most {@code width} and kept inside {@code region}; drawn
	 * when {@code g} is given. Returns where the tag is.
	 */
	static Rectangle tag(@Nullable Graphics2D g, FontMetrics metrics, String text, int width, Rectangle row, Rectangle region)
	{
		List<String> lines = wrap(text, metrics, width - 16);
		Rectangle tag = tagRect(Math.min(width, widest(metrics, lines) + 16), lines.size() * metrics.getHeight() + 8, row, region);
		if (g != null)
		{
			lines(g, metrics, lines, tag.x, tag.y, tag.width, tag.height);
		}
		return tag;
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

	/** Text on a dark plate with its top left at x and y, so it reads over any terrain: names, notices, the coordinates. */
	static void plate(Graphics2D g, FontMetrics metrics, String text, int x, int y, int padX, int padY, int arc)
	{
		g.setColor(colour("PLATE"));
		g.fillRoundRect(x, y, metrics.stringWidth(text) + 2 * padX, metrics.getHeight() + 2 * padY, arc, arc);
		g.setColor(colour("TEXT"));
		g.drawString(text, x + padX, y + padY + metrics.getAscent());
	}

	/** A row's highlight, as warm as the pointer has made it. */
	static void warm(Graphics2D g, String colour, double warmth, int x, int y, int width, int height)
	{
		if (warmth > 0)
		{
			g.setColor(Motion.withAlpha(colour(colour), warmth));
			g.fillRect(x, y, width, height);
		}
	}

	/** A rounded button: a fill and an edge. */
	static void pill(Graphics2D g, Rectangle r, String fill, String edge)
	{
		g.setColor(colour(fill));
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(colour(edge));
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 6, 6);
	}

	/** The cross that closes a panel, lit under the pointer. */
	static void cross(Graphics2D g, Rectangle r, boolean over, String hover)
	{
		if (over)
		{
			g.setColor(colour(hover));
			g.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
		}
		g.setColor(colour(over ? "ACCENT" : "TEXT"));
		g.setStroke(Palette.ICON_LINE);
		g.drawLine(r.x + 3, r.y + 3, r.x + r.width - 4, r.y + r.height - 4);
		g.drawLine(r.x + r.width - 4, r.y + 3, r.x + 3, r.y + r.height - 4);
		g.setStroke(Palette.ONE);
	}

	/** The baseline that centres a line of text in a band. */
	static int middle(int top, int height, FontMetrics metrics)
	{
		return top + (height - metrics.getHeight()) / 2 + metrics.getAscent();
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

	/** The text cut to fit, ending in three dots when it had to be cut, as cards and search results cut theirs; "" for none. */
	static String dots(@Nullable String text, FontMetrics metrics, int maxWidth)
	{
		if (text == null || metrics.stringWidth(text) <= maxWidth)
		{
			return text == null ? "" : text;
		}
		String result = text;
		while (result.length() > 1 && metrics.stringWidth(result + "...") > maxWidth)
		{
			result = result.substring(0, result.length() - 1);
		}
		return result + "...";
	}

	/** True when {@link #clip} would cut the text. */
	static boolean isCut(String text, FontMetrics metrics, int maxWidth)
	{
		return metrics.stringWidth(text) > maxWidth;
	}

	/** The width of the widest line. */
	static int widest(FontMetrics metrics, List<String> lines)
	{
		int widest = 0;
		for (String line : lines)
		{
			widest = Math.max(widest, metrics.stringWidth(line));
		}
		return widest;
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
}
