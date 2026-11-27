package com.worldmapplus.ui;

import static com.worldmapplus.theme.Palette.colour;

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
public final class ContextMenu extends Box
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
		OPEN_WIKI,
		SEARCH_WIKI,
		/** An entry another plugin added to its own marker: the choice is told to that plugin and nothing else happens. */
		MARKER_OPTION,
		/** Takes this map to where a point of the game's map jumps to, as RuneLite's own "Focus on" entry does on that map. */
		FOCUS_MARKER,
		FOLLOW_MEMBER,
		STOP_FOLLOWING
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
	private Rectangle limit = new Rectangle();
	private Object subject;

	public ContextMenu()
	{
		super(ROW_HEIGHT, 2);
	}

	public boolean isOpen()
	{
		return shown;
	}

	@Override
	public Rectangle getBounds()
	{
		return new Rectangle(bounds);
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
		int visibleRows = Math.min(this.items.size(), Math.max(1, (limit.height - PADDING) / ROW_HEIGHT));
		boolean scrolls = this.items.size() > visibleRows;
		int wanted = Math.max(MIN_WIDTH, width + PADDING * 2) + (scrolls ? Scroller.WIDTH + 2 : 0);
		int menuWidth = Math.max(1, Math.min(wanted, limit.width));
		int menuHeight = visibleRows * ROW_HEIGHT + PADDING;
		scroller.reset();
		scroller.set(this.items.size() * ROW_HEIGHT, visibleRows * ROW_HEIGHT);
		int left = Math.max(limit.x, Math.min(x, limit.x + limit.width - menuWidth));
		int top = Math.max(limit.y, Math.min(y, limit.y + limit.height - menuHeight));
		bounds = new Rectangle(left, top, menuWidth, menuHeight);
		body = new Rectangle(left, top + PADDING / 2, menuWidth, visibleRows * ROW_HEIGHT);
		columnWidth = menuWidth;
		shown = !this.items.isEmpty();
		fade.restart();
	}

	public void close()
	{
		shown = false;
		subject = null;
		fade.restart();
	}

	@Override
	int rowCount()
	{
		return items.size();
	}

	/** The item under a point, or null. */
	@Nullable
	public Item hit(int x, int y)
	{
		int row = indexAt(x, y);
		return row < 0 ? null : items.get(row);
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

	public void paint(Graphics2D g, @Nullable Point mouse)
	{
		paint(g, mouse, System.currentTimeMillis());
	}

	/** What the frame being drawn needs for its rows: the row under the pointer, the cut one under it and the room. */
	private int hovered;
	private int cut;
	private int room;
	private FontMetrics metrics;

	@Override
	void paintBox(Graphics2D g)
	{
		panel(g, bounds.x, bounds.y, bounds.width, bounds.height);
		g.setFont(Palette.BODY);
		metrics = g.getFontMetrics();
		hovered = mouse == null ? -1 : indexAt(mouse.x, mouse.y);
		cut = -1;
		room = bounds.width - PADDING * 2 - (scroller.isScrollable() ? Scroller.WIDTH + 2 : 0);
		paintRows(g);
		if (cut >= 0)
		{
			Rectangle region = new Rectangle(limit.x + EDGE, limit.y + EDGE, Math.max(1, limit.width - 2 * EDGE),
				Math.max(1, limit.height - 2 * EDGE));
			tag(g, metrics, items.get(cut).getLabel(), Math.min(260, Math.max(40, limit.width - 2 * EDGE)),
				rowBox(cut, new Rectangle()), region);
		}
	}

	@Override
	void paintRow(Graphics2D g, int index, Rectangle row)
	{
		String label = items.get(index).getLabel();
		warm(g, "MENU_HOVER", fade.level(label, index == hovered, now), bounds.x + 2, row.y, bounds.width - 4, ROW_HEIGHT);
		g.setColor(colour("TEXT"));
		g.drawString(clip(label, metrics, room), bounds.x + PADDING, middle(row.y, ROW_HEIGHT, metrics));
		if (index == hovered && isCut(label, metrics, room))
		{
			cut = index;
		}
	}
}
