package com.worldmapplus.ui;

import static com.worldmapplus.theme.Palette.colour;

import com.worldmapplus.theme.*;
import java.awt.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * The results panel: what was chosen in the search, with tabs, a Back link through earlier results, how many are at each place and
 * how far it is, and a choice of order. It is the plugin's own drawing and input, like the layers list, on whichever map it belongs
 * to (the world map window, the floating map with click-through off, an extra window). It sits at the left of the map under the
 * route panel, takes the mouse only inside its own box, and is not drawn at all where the map draws no controls.
 */
final class ResultsPanel extends Box
{
	/** How many earlier results Back goes through. */
	static final int MAX_BACK = 20;

	static final int WIDTH = 300;
	static final int HEADER = 24;
	static final int SUMMARY = 18;
	static final int TABS = 22;
	static final int SORTS = 20;
	static final int ROW = 34;
	static final int PAD = 8;
	static final int CLOSE = 14;

	/** Below this size the map has no room for the panel, which then neither shows nor takes clicks. */
	static final int MIN_MAP_WIDTH = 360;
	static final int MIN_MAP_HEIGHT = 220;

	/** What a point of the panel is over. */
	enum Part
	{
		NONE, CLOSE, BACK, TAB, SORT, ROW, SCROLL_BAR, BODY
	}

	/** A part and, for a tab, an order or a row, which one. */
	@RequiredArgsConstructor
	static final class Hit
	{
		static final Hit NONE = new Hit(Part.NONE, -1);
		final Part part;
		final int index;
	}

	private ResultView view;
	private final Deque<ResultView> back = new ArrayDeque<>();
	private Rectangle backRect = new Rectangle();
	private final Rectangle closeRect = new Rectangle();
	private Rectangle[] tabRects = new Rectangle[0];
	private Rectangle[] sortRects = new Rectangle[0];
	private ResultView laidView;
	private int laidTab = -1;
	private int laidBack = -1;
	private int laidTop;
	private int laidRight;
	private final Rectangle laidMap = new Rectangle();

	/** Counts every change of what is shown, so an answer that comes late can tell whether it is still about what is shown. */
	private int changes;

	ResultsPanel()
	{
		super(ROW, 3);
	}

	/** How many times what is shown has changed. */
	int changes()
	{
		return changes;
	}

	/**
	 * Puts a view made again (with its drop tab, once the tables are read) in place of the one shown, keeping the tab, the scroll and
	 * Back. Does nothing when {@code old} is no longer the view shown.
	 */
	boolean swap(ResultView old, ResultView next)
	{
		if (view != old || old == null)
		{
			return false;
		}
		next.selectTab(old.current().name);
		view = next;
		changes++;
		return true;
	}

	/** Shows a view, keeping the one shown before for Back. */
	void open(ResultView next)
	{
		changes++;
		if (view != null)
		{
			back.push(view);
			while (back.size() > MAX_BACK)
			{
				back.removeLast();
			}
		}
		view = next;
		scroller.reset();
	}

	/** Shows a view in place of everything, with nothing to go back to: a new search result. */
	void replace(ResultView next)
	{
		changes++;
		back.clear();
		view = next;
		scroller.reset();
	}

	/** Goes back to the view shown before, if there is one. */
	boolean back()
	{
		if (back.isEmpty())
		{
			return false;
		}
		changes++;
		view = back.pop();
		scroller.reset();
		return true;
	}

	void close()
	{
		changes++;
		view = null;
		back.clear();
		shown = false;
		laidView = null;
		fade.restart();
	}

	boolean isOpen()
	{
		return view != null;
	}

	@Nullable
	ResultView view()
	{
		return view;
	}

	int backDepth()
	{
		return back.size();
	}

	/**
	 * Places the panel at the left of the map, from {@code top} down, as tall as its rows need and the map allows.
	 *
	 * @param map the map's rectangle
	 * @param top the highest the panel may start, below anything already in that corner
	 * @param right the furthest right the panel may reach, left of the map's buttons
	 */
	void layout(Rectangle map, int top, int right)
	{
		if (view == laidView && view != null && view.tabIndex() == laidTab && back.size() == laidBack && top == laidTop
			&& right == laidRight && map.x == laidMap.x && map.y == laidMap.y && map.width == laidMap.width && map.height == laidMap.height)
		{
			// Nothing that decides the layout has changed: the same boxes, nothing made.
			return;
		}
		laidView = view;
		laidTab = view == null ? -1 : view.tabIndex();
		laidBack = back.size();
		laidTop = top;
		laidRight = right;
		laidMap.setBounds(map);
		shown = view != null && map.width >= MIN_MAP_WIDTH && map.height >= MIN_MAP_HEIGHT;
		if (!shown)
		{
			return;
		}
		int x = map.x + 10;
		int width = Math.min(WIDTH, right - x - 8);
		int bottom = map.y + map.height - 46;
		if (width < 180 || bottom - top < HEADER + SUMMARY + ROW + PAD * 2)
		{
			shown = false;
			return;
		}
		ResultView.Tab tab = view.current();
		int head = HEADER + SUMMARY + (view.tabs.size() > 1 ? TABS : 0) + (tab.sorts.size() > 1 ? SORTS : 0);
		int rowsRoom = Math.max(ROW, bottom - top - head - PAD * 2);
		int rowsHeight = Math.min(rowsRoom, tab.rows.size() * ROW);
		bounds = new Rectangle(x, top, width, head + rowsHeight + PAD * 2);
		body = new Rectangle(x, top + PAD + head, width, rowsHeight);
		columnWidth = width;
		scroller.set(tab.rows.size() * ROW, rowsHeight);
		closeRect.setBounds(x + width - CLOSE - 6, top + 5, CLOSE, CLOSE);
		backRect = back.isEmpty() ? new Rectangle() : new Rectangle(x + PAD - 2, top + 3, 44, HEADER - 6);
		int tabY = top + PAD + HEADER + SUMMARY;
		tabRects = new Rectangle[view.tabs.size() > 1 ? view.tabs.size() : 0];
		int tx = x + PAD;
		for (int i = 0; i < tabRects.length; i++)
		{
			int w = Math.max(48, 12 + 7 * view.tabs.get(i).name.length());
			tabRects[i] = new Rectangle(tx, tabY, w, TABS - 4);
			tx += w + 4;
		}
		int sortY = tabY + (tabRects.length > 0 ? TABS : 0);
		sortRects = new Rectangle[tab.sorts.size() > 1 ? tab.sorts.size() : 0];
		int sx = x + PAD + 34;
		for (int i = 0; i < sortRects.length; i++)
		{
			int w = 12 + 6 * tab.sorts.get(i).label().length();
			sortRects[i] = new Rectangle(sx, sortY, w, SORTS - 4);
			sx += w + 4;
		}
	}

	@Override
	int rowCount()
	{
		return view.current().rows.size();
	}

	Hit hit(int x, int y)
	{
		if (!contains(x, y))
		{
			return Hit.NONE;
		}
		if (closeRect.contains(x, y))
		{
			return new Hit(Part.CLOSE, -1);
		}
		if (backRect.contains(x, y))
		{
			return new Hit(Part.BACK, -1);
		}
		for (int i = 0; i < tabRects.length; i++)
		{
			if (tabRects[i].contains(x, y))
			{
				return new Hit(Part.TAB, i);
			}
		}
		for (int i = 0; i < sortRects.length; i++)
		{
			if (sortRects[i].contains(x, y))
			{
				return new Hit(Part.SORT, i);
			}
		}
		if (scrollBarHit(x, y))
		{
			return new Hit(Part.SCROLL_BAR, -1);
		}
		int row = indexAt(x, y);
		return new Hit(row < 0 ? Part.BODY : Part.ROW, row);
	}

	Rectangle tabRect(int index)
	{
		return index < tabRects.length ? new Rectangle(tabRects[index]) : new Rectangle();
	}

	Rectangle sortRect(int index)
	{
		return index < sortRects.length ? new Rectangle(sortRects[index]) : new Rectangle();
	}

	Rectangle backRect()
	{
		return new Rectangle(backRect);
	}

	Rectangle closeRect()
	{
		return new Rectangle(closeRect);
	}

	/** What the frame being drawn needs for its rows. */
	private int room;
	private FontMetrics small;
	private FontMetrics bodyFont;

	@Override
	void paintBox(Graphics2D g)
	{
		panel(g, bounds.x, bounds.y, bounds.width, bounds.height);
		ResultView.Tab tab = view.current();
		int x = bounds.x + PAD;
		int y = bounds.y + PAD;

		g.setFont(Palette.SMALL);
		small = g.getFontMetrics();
		int titleX = x;
		if (!backRect.isEmpty())
		{
			boolean over = mouse != null && backRect.contains(mouse);
			g.setColor(colour(over ? "ACCENT" : "TEXT_DIM"));
			g.drawString("← Back", backRect.x + 2, middle(backRect.y, backRect.height, small));
			titleX = backRect.x + backRect.width + 6;
		}
		g.setFont(Palette.TITLE);
		FontMetrics title = g.getFontMetrics();
		g.setColor(colour("ACCENT"));
		g.drawString(clip(view.title, title, closeRect.x - titleX - 6), titleX, y - 4 + title.getAscent());
		cross(g, closeRect, mouse != null && closeRect.contains(mouse), "HOVER");

		g.setFont(Palette.SMALL);
		g.setColor(colour("TEXT_DIM"));
		g.drawString(clip(view.summary, small, bounds.width - PAD * 2), x, y + HEADER - 2 + small.getAscent() - 2);

		for (int i = 0; i < tabRects.length; i++)
		{
			Rectangle r = tabRects[i];
			boolean active = i == view.tabIndex();
			pill(g, r, active ? "PANEL_ACTIVE" : mouse != null && r.contains(mouse) ? "HOVER" : "RESULT_BUTTON", active ? "ACCENT" : "BORDER");
			g.setColor(colour(active ? "ACCENT" : "TEXT"));
			g.drawString(clip(view.tabs.get(i).label, small, r.width - 6), r.x + 5, middle(r.y, r.height, small));
		}
		if (sortRects.length > 0)
		{
			g.setColor(colour("TEXT_DIM"));
			g.drawString("Sort", x, middle(sortRects[0].y, sortRects[0].height, small));
			for (int i = 0; i < sortRects.length; i++)
			{
				Rectangle r = sortRects[i];
				ResultView.Sort sort = tab.sorts.get(i);
				boolean active = sort == tab.sort;
				if (active || mouse != null && r.contains(mouse))
				{
					g.setColor(colour(active ? "PANEL_ACTIVE" : "HOVER"));
					g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
				}
				g.setColor(colour(active ? "ACCENT" : "TEXT"));
				g.drawString(sort.label(), r.x + 6, middle(r.y, r.height, small));
			}
		}

		room = body.width - PAD * 2 - (scroller.isScrollable() ? Scroller.WIDTH + 4 : 0);
		g.setFont(Palette.BODY);
		bodyFont = g.getFontMetrics();
		paintRows(g);
	}

	@Override
	void paintRow(Graphics2D g, int index, Rectangle cell)
	{
		ResultView.Row row = view.current().rows.get(index);
		int top = cell.y;
		boolean over = mouse != null && mouse.y >= top && mouse.y < top + ROW && body.contains(mouse);
		warm(g, "HOVER", fade.level(row, over, now), body.x + 2, top, room + PAD, ROW);
		g.setColor(colour("BORDER"));
		g.drawLine(body.x + PAD, top + ROW - 1, body.x + PAD + room, top + ROW - 1);
		g.setFont(Palette.SMALL);
		int countWidth = Math.min(room / 2, small.stringWidth(row.count));
		g.setColor(colour("ACCENT"));
		g.drawString(row.fitted(1, row.count, small, countWidth), body.x + PAD + room - countWidth, top + 4 + small.getAscent());
		g.setFont(Palette.BODY);
		g.setColor(colour("TEXT"));
		g.drawString(row.fitted(0, row.label, bodyFont, room - countWidth - 8), body.x + PAD, top + 3 + bodyFont.getAscent());
		g.setFont(Palette.SMALL);
		g.setColor(colour("TEXT_DIM"));
		g.drawString(row.fitted(2, row.below, small, room), body.x + PAD, top + 18 + small.getAscent());
	}
}
