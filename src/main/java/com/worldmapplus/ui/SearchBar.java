package com.worldmapplus.ui;

import static com.worldmapplus.theme.Palette.colour;

import com.worldmapplus.search.*;
import com.worldmapplus.theme.*;
import java.awt.*;
import java.util.List;
import java.util.function.Function;
import javax.annotation.*;
import lombok.*;

/**
 * Draws the search field and its results at the top of the map, and finds what a click landed on.
 * <p>
 * All of it is the plugin's own drawing. Typing arrives as events from the plugin's input handler,
 * not through any game text box.
 */
public final class SearchBar
{
	static final int FIELD_HEIGHT = 26;
	static final int ROW_HEIGHT = 36;
	static final int MARGIN = 10;
	static final int HINT_HEIGHT = 22;
	static final int MAX_WIDTH = 380;
	/** Gap to the left of the map's coordinate plate, which sits in the top left corner. */
	static final int LEFT_CLEARANCE = 190;

	/** What is missing for a result, as a card would say it, or null; asked only for the rows drawn (round 47). */
	@Nullable
	private Function<SearchEntry, String> missing;

	/** Tells the bar how to find what is missing for a result, or null for nothing (the settings that mark or hide are off). */
	public void setMissing(@Nullable Function<SearchEntry, String> missing)
	{
		this.missing = missing;
	}

	/** Width of the scroll bar down the right of the results. */
	static final int SCROLL_WIDTH = 10;

	/** What a point is over. */
	public static final class Hit
	{
		public static final Hit NONE = new Hit(false, -1, -1);
		public static final Hit FIELD_HIT = new Hit(true, -1, -1);

		@Getter
		private final boolean field;
		/** The result row under the point, or -1. */
		@Getter
		private final int row;
		private final double scroll;

		private Hit(boolean field, int row, double scroll)
		{
			this.field = field;
			this.row = row;
			this.scroll = scroll;
		}

		static Hit row(int row)
		{
			return new Hit(false, row, -1);
		}

		static Hit scrollBar(double fraction)
		{
			return new Hit(false, -1, fraction);
		}

		/** True over the scroll bar. */
		public boolean isScrollBar()
		{
			return scroll >= 0;
		}

		/** How far down the scroll bar's track the point is, from 0 to 1, when over it. */
		public double getScrollFraction()
		{
			return scroll;
		}
	}

	private Rectangle fieldRect = new Rectangle();
	@Getter
	private int visibleRows;
	/** How many results the map has room for at once, whether or not there are that many. */
	@Getter
	private int capacity;
	private int total;
	private Rectangle resultsRect = new Rectangle();

	/** How long the results take to open or fold away. */
	private static final long OPEN_MILLIS = 180;

	/** How open the results are, from 0 folded away to 1 fully shown. */
	private double open = 1;
	private long lastTick;
	private final Motion rowFade = new Motion();

	/** Places the field top centre of the map, clear of the corner plate and the buttons. */
	public void layout(Rectangle map, int resultCount)
	{
		layout(map, resultCount, false);
	}

	/** As {@link #layout(Rectangle, int)}, leaving room under the results for a line of help. */
	public void layout(Rectangle map, int resultCount, boolean hint)
	{
		int available = Math.max(160, map.width - 2 * LEFT_CLEARANCE);
		int width = Math.min(MAX_WIDTH, available);
		int x = map.x + (map.width - width) / 2;
		fieldRect = new Rectangle(x, map.y + MARGIN, width, FIELD_HEIGHT);

		int room = map.y + map.height - (fieldRect.y + fieldRect.height + 4) - MARGIN - (hint ? HINT_HEIGHT + 4 : 0);
		capacity = Math.max(1, room / ROW_HEIGHT);
		total = resultCount;
		visibleRows = Math.max(0, Math.min(resultCount, room / ROW_HEIGHT));
		resultsRect = new Rectangle(x, fieldRect.y + fieldRect.height + 2, width, visibleRows * ROW_HEIGHT);
	}

	/**
	 * Opens the results while the field has the keyboard or the pointer is on the bar, and folds them away otherwise,
	 * a little each frame so the list slides rather than jumps.
	 */
	public void animate(boolean wantOpen, long nowMillis)
	{
		long elapsed = lastTick == 0 ? 0 : Math.max(0, Math.min(100, nowMillis - lastTick));
		lastTick = nowMillis;
		double step = elapsed / (double) OPEN_MILLIS;
		open = wantOpen ? Math.min(1, open + step) : Math.max(0, open - step);
	}

	/** True when a point is where the pointer keeps the results open: on the field, or on the results while they show. */
	public boolean keepsOpen(int x, int y)
	{
		return fieldRect.contains(x, y) || open > 0.02 && resultsRect.contains(x, y);
	}

	/** True when the results are all the way open. */
	public boolean isOpen()
	{
		return open >= 1;
	}

	/** The results as far as they are open: the same top and width, and a height that grows and shrinks. */
	private Rectangle shown()
	{
		int height = (int) Math.round(resultsRect.height * Motion.ease(open));
		return new Rectangle(resultsRect.x, resultsRect.y, resultsRect.width, height);
	}

	/** The part of the bar that holds the results, as far as they are open. */
	public Rectangle getResultsRect()
	{
		return shown();
	}

	public Rectangle getFieldRect()
	{
		return new Rectangle(fieldRect);
	}

	/** Everything the bar covers, field and results, for hit testing against other map controls. */
	public Rectangle getCoveredRect()
	{
		return visibleRows == 0 || open <= 0 ? new Rectangle(fieldRect) : fieldRect.union(shown());
	}

	public Hit hit(int x, int y)
	{
		if (fieldRect.contains(x, y))
		{
			return Hit.FIELD_HIT;
		}
		if (shown().contains(x, y))
		{
			if (scrolls() && x >= resultsRect.x + resultsRect.width - SCROLL_WIDTH - 2)
			{
				return Hit.scrollBar((y - resultsRect.y) / (double) Math.max(1, resultsRect.height));
			}
			return Hit.row((y - resultsRect.y) / ROW_HEIGHT);
		}
		return Hit.NONE;
	}

	/** Makes the bar take no space and no clicks, for a map too narrow to draw it. */
	public void hideAway()
	{
		fieldRect = new Rectangle();
		resultsRect = new Rectangle();
		visibleRows = 0;
	}

	/** How far down the results a canvas height is, from 0 to 1, for dragging the scroll bar. */
	public double scrollFractionAt(int y)
	{
		return Math.max(0, Math.min(1, (y - resultsRect.y) / (double) Math.max(1, resultsRect.height)));
	}

	/** True when there are more results than rows, so the list scrolls. */
	public boolean scrolls()
	{
		return total > visibleRows && visibleRows > 0;
	}

	/** True over the results, for the wheel. */
	public boolean overResults(int x, int y)
	{
		return visibleRows > 0 && shown().contains(x, y);
	}

	public void paint(Graphics2D g, SearchBox box, @Nullable Point mouse, long nowMillis)
	{
		boolean focused = box.isFocused();
		Box.panel(g, fieldRect.x, fieldRect.y, fieldRect.width, fieldRect.height,
			colour(focused ? "PANEL_ACTIVE" : "PANEL"), colour(focused ? "ACCENT" : "BORDER"));

		g.setFont(Palette.FIELD_FONT);
		FontMetrics metrics = g.getFontMetrics();
		int textX = fieldRect.x + 10;
		int baseline = Box.middle(fieldRect.y, fieldRect.height, metrics);
		String text = box.getText();
		if (text.isEmpty() && !focused)
		{
			g.setColor(colour("FIELD_DIM"));
			g.drawString("Search places, shops, items", textX, baseline);
		}
		else
		{
			String shown = tail(text, metrics, fieldRect.width - 24);
			if (box.isAllSelected())
			{
				g.setColor(colour("ACCENT_GLOW"));
				g.fillRect(textX - 1, fieldRect.y + 4, metrics.stringWidth(shown) + 2, fieldRect.height - 8);
			}
			g.setColor(colour("TEXT"));
			g.drawString(shown, textX, baseline);
			if (focused && !box.isAllSelected() && (nowMillis / 500) % 2 == 0)
			{
				int caret = textX + metrics.stringWidth(shown) + 1;
				g.drawLine(caret, fieldRect.y + 5, caret, fieldRect.y + fieldRect.height - 6);
			}
		}

		List<SearchEntry> results = box.getResults();
		if (results.size() > 1 && !text.isEmpty())
		{
			g.setFont(Palette.SMALL);
			String count = results.size() >= SearchBox.MAX_RESULTS ? SearchBox.MAX_RESULTS + "+" : String.valueOf(results.size());
			FontMetrics small = g.getFontMetrics();
			g.setColor(colour("FIELD_DIM"));
			g.drawString(count, fieldRect.x + fieldRect.width - small.stringWidth(count) - 10,
				Box.middle(fieldRect.y, fieldRect.height, small));
		}
		Rectangle area = shown();
		if (area.height > 0)
		{
			Shape oldClip = g.getClip();
			Composite oldComposite = g.getComposite();
			g.clipRect(area.x, area.y, area.width, area.height);
			g.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1.0, Motion.ease(open) * 1.2)));
			for (int i = 0; i < visibleRows && box.getScrollTop() + i < results.size(); i++)
			{
				int index = box.getScrollTop() + i;
				paintRow(g, results.get(index), i, index == box.getSelected(), mouse, nowMillis);
			}
			if (scrolls())
			{
				paintScrollBar(g, box);
			}
			g.setComposite(oldComposite);
			g.setClip(oldClip);
		}
		if (!box.getHint().isEmpty())
		{
			paintHint(g, box.getHint());
		}
		if (focused && text.isEmpty() && results.isEmpty())
		{
			paintHelp(g);
		}
	}

	/** The track and thumb down the right edge of the results. */
	private void paintScrollBar(Graphics2D g, SearchBox box)
	{
		int trackX = resultsRect.x + resultsRect.width - SCROLL_WIDTH - 2;
		g.setColor(colour("SEARCH_INSET"));
		g.fillRect(trackX, resultsRect.y, SCROLL_WIDTH + 2, resultsRect.height);
		int count = box.getResults().size();
		int thumb = Math.max(18, resultsRect.height * visibleRows / Math.max(1, count));
		int range = resultsRect.height - thumb;
		int hidden = Math.max(1, count - visibleRows);
		int thumbY = resultsRect.y + range * box.getScrollTop() / hidden;
		g.setColor(colour("ACCENT"));
		g.fillRoundRect(trackX + 2, thumbY, SCROLL_WIDTH - 2, thumb, 6, 6);
	}

	/** One line of help just under the field or the results. */
	private void paintHint(Graphics2D g, String text)
	{
		g.setFont(Palette.SMALL);
		FontMetrics small = g.getFontMetrics();
		int y = resultsRect.y + shown().height + 2;
		Box.panel(g, fieldRect.x, y, fieldRect.width, HINT_HEIGHT);
		g.setColor(colour("FIELD_DIM"));
		g.drawString(Box.dots(text, small, fieldRect.width - 20), fieldRect.x + 10,
			Box.middle(y, HINT_HEIGHT, small));
	}

	private static final String[] HELP = {
		"Every word must match. Narrow the results with filters:",
		"region:\"kebos lowlands\"    type:shop,place    loc:nearby    dist:40",
		"cost:500    free:yes    name:\"iron arrow\"    sort:cost    -region:varrock",
		"Tab finishes a name. A tile such as 3222 3218 jumps to it."
	};

	/** What can be typed, under the field while it is empty. */
	private void paintHelp(Graphics2D g)
	{
		g.setFont(Palette.SMALL);
		FontMetrics small = g.getFontMetrics();
		int height = HELP.length * (small.getHeight() + 2) + 12;
		int y = fieldRect.y + fieldRect.height + 2;
		g.setColor(colour("FIELD_FILL"));
		g.fillRect(fieldRect.x, y, fieldRect.width, height);
		g.setColor(colour("BORDER"));
		g.drawRect(fieldRect.x, y, fieldRect.width - 1, height - 1);
		g.setColor(colour("FIELD_DIM"));
		int line = y + 6 + small.getAscent();
		for (String text : HELP)
		{
			g.drawString(Box.dots(text, small, fieldRect.width - 20), fieldRect.x + 10, line);
			line += small.getHeight() + 2;
		}
	}

	/** The rectangle of the row being drawn, reused so a frame makes none. */
	private final Rectangle cell = new Rectangle();

	private void paintRow(Graphics2D g, SearchEntry entry, int row, boolean selected, @Nullable Point mouse,
		long nowMillis)
	{
		Rectangle rect = cell;
		rect.setBounds(resultsRect.x, resultsRect.y + row * ROW_HEIGHT, resultsRect.width, ROW_HEIGHT);
		g.setColor(colour("FIELD_FILL"));
		g.fillRect(rect.x, rect.y, rect.width, rect.height);
		boolean hovered = mouse != null && rect.contains(mouse);
		Box.warm(g, "ROW_SELECTED", rowFade.level(entry, selected || hovered, nowMillis), rect.x, rect.y, rect.width, rect.height);
		g.setColor(colour("BORDER"));
		g.drawLine(rect.x, rect.y + rect.height - 1, rect.x + rect.width - 1, rect.y + rect.height - 1);

		g.setFont(Palette.SMALL);
		FontMetrics small = g.getFontMetrics();
		String kind = entry.getKind().label();
		int chipWidth = small.stringWidth(kind) + 10;
		int chipX = rect.x + rect.width - chipWidth - 8;
		g.setColor(colour("CHIP"));
		g.fillRoundRect(chipX, rect.y + 6, chipWidth, 16, 8, 8);
		g.setColor(colour("TEXT"));
		g.drawString(kind, chipX + 5, Box.middle(rect.y + 6, 16, small));

		g.setFont(Palette.BODY_BOLD);
		FontMetrics bold = g.getFontMetrics();
		g.setColor(colour("TEXT"));
		g.drawString(Box.dots(entry.getName(), bold, chipX - rect.x - 18), rect.x + 10, rect.y + 6 + bold.getAscent());

		g.setFont(Palette.SMALL);
		int width = rect.width - 20 - (scrolls() ? SCROLL_WIDTH : 0);
		int baseline = rect.y + 22 + small.getAscent() - 2;
		String lack = missing == null ? null : missing.apply(entry);
		int x = rect.x + 10;
		if (lack != null)
		{
			// The same words as the card's Missing row, then the result's own detail in what room is left.
			String note = Box.dots("Missing: " + lack, small, width);
			g.setColor(colour("MISSING"));
			g.drawString(note, x, baseline);
			int used = small.stringWidth(note) + 8;
			x += used;
			width -= used;
		}
		if (width > 20)
		{
			g.setColor(colour("FIELD_DIM"));
			g.drawString(Box.dots(entry.getDetail(), small, width), x, baseline);
		}
	}

	/** The end of a string that fits, so the caret is always visible while typing. */
	static String tail(String text, FontMetrics metrics, int maxWidth)
	{
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		int start = 0;
		while (start < text.length() && metrics.stringWidth(text.substring(start)) > maxWidth)
		{
			start++;
		}
		return text.substring(start);
	}
}
