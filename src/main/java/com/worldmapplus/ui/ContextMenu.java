package com.worldmapplus.ui;

import com.worldmapplus.theme.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.annotation.*;
import lombok.*;

/**
 * The small menu that opens on a right click inside the map.
 * <p>
 * This is the plugin's own drawing and its own hit testing. It is not the game's menu, adds
 * nothing to it, and nothing it does is sent to the game server: every item either moves the map,
 * changes the plugin's own pins, copies text, or sends a plugin message to another plugin.
 */
public final class ContextMenu
{
	/** What an item does. */
	public enum Action
	{
		ROUTE,
		SET_ROUTE_START,
		ADD_ROUTE_TARGET,
		CLEAR_ROUTE,
		COPY_TILE,
		NEAREST_TELEPORTS,
		ADD_PIN,
		ROUTE_TO_PIN,
		EDIT_PIN,
		HIDE_PIN,
		REMOVE_PIN,
		SAVE_PLACE,
		SHOW_RESULT,
		/** The button a partner put on cards (the Wiki button), pressed from the menu. */
		CARD_BUTTON,
		MEASURE,
		STOP_MEASURING,
		/** An entry another plugin added through the plugin API: the choice is told to that plugin. */
		PARTNER_OPTION
	}

	/** One line of the menu. */
	@Getter
	@RequiredArgsConstructor
	public static final class Item
	{
		private final Action action;
		private final String label;
	}

	static final int ROW_HEIGHT = 22;
	static final int PADDING = 6;
	static final int MIN_WIDTH = 150;

	/** The items on the menu, top to bottom. */
	@Getter
	private List<Item> items = Collections.emptyList();
	private Rectangle bounds = new Rectangle();
	private Rectangle limit = new Rectangle();
	@Getter
	private boolean open;
	private Object subject;
	private final Scroller scroller = new Scroller();
	private int visibleRows;

	public Rectangle getBounds()
	{
		return new Rectangle(bounds);
	}

	/** True when a point is anywhere on the open menu. */
	public boolean contains(int x, int y)
	{
		return open && bounds.contains(x, y);
	}

	/** What the menu was opened about, such as the world position under the click. */
	@Nullable
	public Object getSubject()
	{
		return subject;
	}

	/**
	 * Opens at a point, kept inside {@code limit}. A menu wider than the limit is made as wide as it, and its long labels are
	 * cut with an ellipsis, with the whole text in a tag while the pointer is on the row. A menu taller than the limit shows
	 * as many rows as fit and scrolls, with the wheel or a bar down its right edge.
	 *
	 * @param width the widest label's width in pixels, from the caller's font metrics
	 */
	public void open(List<Item> items, int x, int y, int width, Rectangle limit, @Nullable Object subject)
	{
		this.items = Collections.unmodifiableList(new ArrayList<>(items));
		this.subject = subject;
		this.limit = new Rectangle(limit);
		int maxRows = Math.max(1, (limit.height - PADDING) / ROW_HEIGHT);
		visibleRows = Math.min(this.items.size(), maxRows);
		boolean scrolls = this.items.size() > visibleRows;
		int wanted = Math.max(MIN_WIDTH, width + PADDING * 2) + (scrolls ? Scroller.WIDTH + 2 : 0);
		int menuWidth = Math.max(1, Math.min(wanted, limit.width));
		int menuHeight = visibleRows * ROW_HEIGHT + PADDING;
		scroller.reset();
		scroller.set(this.items.size() * ROW_HEIGHT, visibleRows * ROW_HEIGHT);
		int left = Math.max(limit.x, Math.min(x, limit.x + limit.width - menuWidth));
		int top = Math.max(limit.y, Math.min(y, limit.y + limit.height - menuHeight));
		this.bounds = new Rectangle(left, top, menuWidth, menuHeight);
		this.open = !this.items.isEmpty();
		appear.restart();
	}

	public void close()
	{
		open = false;
		subject = null;
		appear.restart();
	}

	/** Moves the list by rows, for the wheel. Does nothing when every row shows. */
	public void scroll(int rows)
	{
		scroller.by(rows * ROW_HEIGHT);
	}

	/** True when part of the list is out of view. */
	public boolean isScrollable()
	{
		return open && scroller.isScrollable();
	}

	/** How far the list is scrolled, in pixels. */
	public int getScroll()
	{
		return scroller.offset();
	}

	private Rectangle rowsArea()
	{
		return new Rectangle(bounds.x, bounds.y + PADDING / 2, bounds.width, visibleRows * ROW_HEIGHT);
	}

	private Rectangle track()
	{
		Rectangle area = rowsArea();
		return new Rectangle(bounds.x + bounds.width - Scroller.WIDTH - 2, area.y, Scroller.WIDTH, area.height);
	}

	/** True over the bar, when there is one. */
	public boolean scrollBarHit(int x, int y)
	{
		return open && scroller.isScrollable() && track().contains(x, y);
	}

	/** Moves the list so that its bar's thumb is at a height, while the bar is dragged. */
	public void dragBarTo(int y)
	{
		scroller.toTrack(y, track());
	}

	/** The item under a point, or null. */
	@Nullable
	public Item hit(int x, int y)
	{
		if (!open || !bounds.contains(x, y) || scrollBarHit(x, y))
		{
			return null;
		}
		Rectangle area = rowsArea();
		if (!area.contains(x, y))
		{
			return null;
		}
		int row = (y - area.y + scroller.offset()) / ROW_HEIGHT;
		return row >= 0 && row < items.size() ? items.get(row) : null;
	}

	/** The rectangle a row takes now, or null when it is out of view or not there. For tests. */
	@Nullable
	public Rectangle rowRect(int index)
	{
		if (!open || index < 0 || index >= items.size())
		{
			return null;
		}
		Rectangle area = rowsArea();
		Rectangle row = new Rectangle(area.x, area.y + index * ROW_HEIGHT - scroller.offset(), area.width, ROW_HEIGHT);
		return area.contains(row) ? row : null;
	}

	/** The width a menu with these items needs. */
	public static int widthFor(List<Item> items, FontMetrics metrics)
	{
		int width = 0;
		for (Item item : items)
		{
			width = Math.max(width, metrics.stringWidth(item.getLabel()));
		}
		return width;
	}

	public static Font font()
	{
		return Palette.BODY;
	}

	private final Fade fade = new Fade();
	private final Appear appear = new Appear();

	public void paint(Graphics2D g, @Nullable Point mouse)
	{
		paint(g, mouse, System.currentTimeMillis());
	}

	/** Draws the menu in the frame at {@code now}, fading in when it has just opened. */
	void paint(Graphics2D g, @Nullable Point mouse, long now)
	{
		if (!open)
		{
			return;
		}
		Composite faded = appear.begin(g, now);
		try
		{
			paintRows(g, mouse, now);
		}
		finally
		{
			Appear.end(g, faded);
		}
	}

	/** True while the menu is still fading in. */
	boolean moving(long now)
	{
		return open && appear.moving(now);
	}

	private void paintRows(Graphics2D g, @Nullable Point mouse, long now)
	{
		Theme.panel(g, bounds.x, bounds.y, bounds.width, bounds.height);

		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		Item hovered = mouse == null ? null : hit(mouse.x, mouse.y);
		Rectangle area = rowsArea();
		int room = bounds.width - PADDING * 2 - (scroller.isScrollable() ? Scroller.WIDTH + 2 : 0);
		Shape before = g.getClip();
		g.clipRect(area.x, area.y, area.width, area.height);
		Item cut = null;
		int cutRow = -1;
		for (int i = 0; i < items.size(); i++)
		{
			int rowTop = area.y + i * ROW_HEIGHT - scroller.offset();
			if (rowTop + ROW_HEIGHT < area.y || rowTop > area.y + area.height)
			{
				continue;
			}
			double warmth = fade.level(items.get(i).getLabel(), items.get(i) == hovered, now);
			if (warmth > 0)
			{
				g.setColor(Fade.withAlpha(Theme.MENU_HOVER, warmth));
				g.fillRect(bounds.x + 2, rowTop, bounds.width - 4, ROW_HEIGHT);
			}
			g.setColor(Theme.TEXT);
			String label = items.get(i).getLabel();
			g.drawString(PopupText.clip(label, metrics, room), bounds.x + PADDING,
				rowTop + (ROW_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent());
			if (items.get(i) == hovered && PopupText.isCut(label, metrics, room))
			{
				cut = items.get(i);
				cutRow = i;
			}
		}
		g.setClip(before);
		scroller.paint(g, track());
		if (cut != null)
		{
			Rectangle row = new Rectangle(bounds.x, area.y + cutRow * ROW_HEIGHT - scroller.offset(), bounds.width, ROW_HEIGHT);
			paintTag(g, metrics, cut.getLabel(), row);
		}
	}

	/** The whole text of a cut label in a box beside its row, inside the map. */
	private void paintTag(Graphics2D g, FontMetrics metrics, String text, Rectangle row)
	{
		int width = Math.min(260, Math.max(40, limit.width - 2 * PopupText.EDGE));
		List<String> lines = PopupText.wrap(text, metrics, width - 16);
		int widest = 0;
		for (String line : lines)
		{
			widest = Math.max(widest, metrics.stringWidth(line));
		}
		int w = Math.min(width, widest + 16);
		int h = lines.size() * metrics.getHeight() + 8;
		Rectangle region = new Rectangle(limit.x + PopupText.EDGE, limit.y + PopupText.EDGE,
			Math.max(1, limit.width - 2 * PopupText.EDGE), Math.max(1, limit.height - 2 * PopupText.EDGE));
		Rectangle tag = PopupText.tagRect(w, h, row, region);
		Theme.panel(g, tag.x, tag.y, tag.width, tag.height);
		g.setColor(Theme.TEXT);
		for (int i = 0; i < lines.size(); i++)
		{
			g.drawString(lines.get(i), tag.x + 8, tag.y + 4 + metrics.getAscent() + i * metrics.getHeight());
		}
	}
}
