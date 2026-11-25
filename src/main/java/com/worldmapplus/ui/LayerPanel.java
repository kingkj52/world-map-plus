package com.worldmapplus.ui;

import com.worldmapplus.*;
import com.worldmapplus.compat.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.prefs.*;
import com.worldmapplus.theme.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.annotation.*;
import lombok.*;

/**
 * A panel of tick boxes for what the map draws, opened from the layers button.
 * <p>
 * The panel is the plugin's own drawing. Each row changes one of the plugin's settings, so the panel
 * and the settings page always agree.
 * <p>
 * It sits to the left of the map's button column, with its bottom level with the layers button, and never goes over any of the
 * buttons: its width is the room left of them, and its height is the room above and below its place, both inside the map. When
 * the map is short the rows flow into more columns as far as the width allows. When even that is not enough, the rows are one
 * list that scrolls, with the wheel or a bar down its right edge. A label too long for its row is cut with an ellipsis, and
 * the whole text shows in a tag while the pointer is on it. A cross in the title closes the panel.
 */
public final class LayerPanel
{
	/** One row: a tick box with a label, or a heading. */
	public static final class Row
	{
		/** The setting this row changes, {@code poi:<id>} for a point of interest layer, or {@code gameicon:<kind>} for a kind of the game's icons. */
		@Getter
		private final String key;
		@Getter
		private final String label;
		@Getter
		private final boolean heading;
		private final Predicate<WorldMapPlusConfig> state;
		private final BiConsumer<Settings, WorldMapPlusConfig> flip;

		private Row(String key, String label, boolean heading, Predicate<WorldMapPlusConfig> state,
			BiConsumer<Settings, WorldMapPlusConfig> flip)
		{
			this.key = key;
			this.label = label;
			this.heading = heading;
			this.state = state;
			this.flip = flip;
		}

		static Row setting(String key, String label, Predicate<WorldMapPlusConfig> state)
		{
			return new Row(key, label, false, state,
				(settings, config) -> settings.setBoolean(key, !state.test(config)));
		}

		static Row poi(PoiCategory category)
		{
			String id = category.getId();
			Predicate<WorldMapPlusConfig> state = config -> PoiSelection.parse(config.poiLayers()).contains(id);
			return new Row("poi:" + id, category.getLabel(), false, state,
				(settings, config) -> settings.setString("poiLayers",
					PoiSelection.toggled(config.poiLayers(), id, !state.test(config))));
		}

		/** A tick box for one kind of the game's own map icons, such as Water source. On means the icons are drawn. */
		static Row gameIcon(String kind)
		{
			return new Row("gameicon:" + kind, kind, false, config -> !HiddenSources.isHidden(config.hiddenGameIcons(), kind),
				(settings, config) -> settings.setString("hiddenGameIcons",
					HiddenSources.toggled(config.hiddenGameIcons(), kind, !HiddenSources.isHidden(config.hiddenGameIcons(), kind))));
		}

		/** A tick box for the points one other plugin has on the game's map. On means they are shown here. */
		static Row plugin(String name, int points)
		{
			String label = points > 0 ? name + " (" + points + ")" : name;
			return new Row("plugin:" + name, label, false,
				config -> !HiddenSources.isHidden(config.hiddenMapSources(), name),
				(settings, config) -> settings.setString("hiddenMapSources",
					HiddenSources.toggled(config.hiddenMapSources(), name,
						!HiddenSources.isHidden(config.hiddenMapSources(), name))));
		}

		static Row heading(String label)
		{
			return new Row("", label, true, config -> false, (settings, config) ->
			{
			});
		}

		public boolean isOn(WorldMapPlusConfig config)
		{
			return state.test(config);
		}

		/** Flips the setting this row stands for. */
		public void toggle(Settings settings, WorldMapPlusConfig config)
		{
			flip.accept(settings, config);
		}
	}

	static final int ROW_HEIGHT = 22;
	static final int PADDING = 8;
	static final int TITLE_HEIGHT = 18;
	static final int COLUMN_WIDTH = 230;

	/** Gap between the panel and the buttons it sits beside. */
	static final int GAP = 8;

	/** The narrowest the panel is made, however little room the map leaves. */
	static final int MIN_WIDTH = 56;

	/** Size of the cross that closes the panel. */
	static final int CLOSE_SIZE = 14;

	private final List<Row> fixed;
	@Getter
	private volatile List<Row> rows;
	private Map<String, Integer> pluginKey = Collections.emptyMap();
	private List<Row> pluginRows = Collections.emptyList();
	private Map<String, Integer> layerKey = Collections.emptyMap();
	private List<Row> layerRows = Collections.emptyList();
	@Getter
	private boolean open;
	private Rectangle bounds = new Rectangle();
	private Rectangle limit = new Rectangle();
	private Rectangle block = new Rectangle();
	private int rowsPerColumn = 1;
	private int columns = 1;
	private int columnWidth = COLUMN_WIDTH;
	private int viewRows = 1;
	private final Scroller scroller = new Scroller();
	private final Fade fade = new Fade();

	public LayerPanel(List<PoiCategory> categories)
	{
		this(categories, Collections.emptyList());
	}

	/**
	 * @param categories the kinds of point of interest, each a layer
	 * @param gameIconKinds the kinds of the game's own map icons the plugin draws, each with a tick box that shows or hides them
	 */
	public LayerPanel(List<PoiCategory> categories, List<String> gameIconKinds)
	{
		List<Row> list = new ArrayList<>();
		list.add(Row.setting("showNpcs", "Nearby NPCs", WorldMapPlusConfig::showNpcs));
		list.add(Row.setting("showHotspots", "Cards on icons, doorways, shops", WorldMapPlusConfig::showHotspots));
		list.add(Row.setting("showWilderness", "Wilderness levels", WorldMapPlusConfig::showWilderness));
		list.add(Row.setting("showGrid", "Region grid and ids", WorldMapPlusConfig::showGrid));
		list.add(Row.setting("showTrail", "Where I have been", WorldMapPlusConfig::showTrail));
		list.add(Row.setting("showRegions", "Region borders and names", WorldMapPlusConfig::showRegions));
		list.add(Row.setting("showLabels", "Area names", WorldMapPlusConfig::showLabels));
		list.add(Row.setting("showPins", "Pins and saved places", WorldMapPlusConfig::showPins));
		if (!categories.isEmpty())
		{
			list.add(Row.heading("Points of interest (names on the icons)"));
			for (PoiCategory category : categories)
			{
				if (category.isLayer())
				{
					list.add(Row.poi(category));
				}
			}
		}
		if (!gameIconKinds.isEmpty())
		{
			list.add(Row.heading("Icons of the game's map"));
			for (String kind : gameIconKinds)
			{
				list.add(Row.gameIcon(kind));
			}
		}
		this.fixed = Collections.unmodifiableList(list);
		this.rows = fixed;
	}

	/**
	 * Sets the plugins listed under the layers: those with points on the game's map, each with a tick box that shows or
	 * hides them here. The panel is only rebuilt when the list changes.
	 */
	public void setPlugins(Map<String, Integer> plugins)
	{
		if (plugins == pluginKey || plugins.equals(pluginKey))
		{
			pluginKey = plugins;
			return;
		}
		pluginKey = plugins;
		List<Row> list = new ArrayList<>();
		if (!plugins.isEmpty())
		{
			list.add(Row.heading("On the game map from other plugins"));
			for (Map.Entry<String, Integer> entry : plugins.entrySet())
			{
				list.add(Row.plugin(entry.getKey(), entry.getValue()));
			}
		}
		pluginRows = list;
		combine();
	}

	/**
	 * Sets the layers other plugins named for the markers and lines they sent to this map, each with a tick box (kept in the same
	 * setting as the plugins' points). Rebuilt only when the names or counts change; the map passes the same object while nothing did.
	 */
	public void setLayers(Map<String, Integer> layers)
	{
		if (layers == layerKey || layers.equals(layerKey))
		{
			layerKey = layers;
			return;
		}
		layerKey = layers;
		List<Row> list = new ArrayList<>();
		if (!layers.isEmpty())
		{
			list.add(Row.heading("Sent to this map by other plugins"));
			for (Map.Entry<String, Integer> layer : layers.entrySet())
			{
				list.add(Row.plugin(layer.getKey(), layer.getValue()));
			}
		}
		layerRows = list;
		combine();
	}

	private void combine()
	{
		if (pluginRows.isEmpty() && layerRows.isEmpty())
		{
			rows = fixed;
			return;
		}
		List<Row> list = new ArrayList<>(fixed);
		list.addAll(pluginRows);
		list.addAll(layerRows);
		rows = Collections.unmodifiableList(list);
	}

	public void toggle()
	{
		open = !open;
		appear.restart();
		if (open)
		{
			scroller.reset();
		}
	}

	public void close()
	{
		open = false;
		appear.restart();
	}

	public Rectangle getBounds()
	{
		return open ? new Rectangle(bounds) : new Rectangle();
	}

	/** Places the panel to the left of a button, with its bottom edge level with the button's. */
	public void layout(Rectangle anchor, Rectangle limit)
	{
		layout(anchor, anchor, limit, null);
	}

	/**
	 * Places the panel to the left of the buttons, with its bottom edge level with the layers button's where there is room.
	 *
	 * @param anchor the layers button
	 * @param block the rectangle around all the map's buttons, which the panel stays clear of
	 * @param limit the map, which the panel stays inside
	 * @param avoid another control to stay clear of if the panel would be under it, such as the search field, or null
	 */
	public void layout(Rectangle anchor, Rectangle block, Rectangle limit, @Nullable Rectangle avoid)
	{
		this.limit = new Rectangle(limit);
		this.block = block == null || block.isEmpty() ? new Rectangle(anchor) : new Rectangle(block);
		int edge = PopupText.EDGE;
		int left = limit.x + edge;
		int right = Math.min(Math.min(anchor.x, this.block.x) - GAP, limit.x + limit.width - edge);
		int regionWidth = Math.max(MIN_WIDTH, right - left);
		int top = limit.y + edge;
		int bottom = limit.y + limit.height - edge;

		int rowCount = rows.size();
		int fits = Math.max(1, regionWidth / COLUMN_WIDTH);
		columnWidth = Math.min(COLUMN_WIDTH, regionWidth);

		// A control that the panel would be under, such as the search field, is left clear when it is above the panel.
		int guess = Math.min(regionWidth, columnWidth * Math.min(fits, 3));
		Rectangle guessed = new Rectangle(right - guess, top, guess, bottom - top);
		if (avoid != null && !avoid.isEmpty() && avoid.intersects(guessed))
		{
			top = Math.max(top, avoid.y + avoid.height + 4);
		}
		int regionHeight = Math.max(PADDING * 2 + TITLE_HEIGHT + ROW_HEIGHT, bottom - top);
		int roomRows = Math.max(1, (regionHeight - PADDING * 2 - TITLE_HEIGHT) / ROW_HEIGHT);
		int needed = (rowCount + roomRows - 1) / roomRows;
		boolean scrolls = needed > fits;
		if (scrolls)
		{
			columns = 1;
			rowsPerColumn = rowCount;
			viewRows = Math.min(rowCount, roomRows);
		}
		else
		{
			columns = Math.max(1, needed);
			rowsPerColumn = roomRows;
			viewRows = Math.min(rowCount, roomRows);
		}
		int width = columns * columnWidth;
		int height = PADDING * 2 + TITLE_HEIGHT + viewRows * ROW_HEIGHT;
		scroller.set(scrolls ? rowCount * ROW_HEIGHT : 0, viewRows * ROW_HEIGHT);
		int x = Math.max(left, right - width);
		int y = Math.max(top, Math.min(anchor.y + anchor.height - height, bottom - height));
		bounds = new Rectangle(x, y, width, height);
	}

	/** The room the panel and its tags have: the map, left clear of the buttons. */
	private Rectangle region()
	{
		int edge = PopupText.EDGE;
		int left = limit.x + edge;
		int right = Math.min(block.x - GAP, limit.x + limit.width - edge);
		return new Rectangle(left, limit.y + edge, Math.max(MIN_WIDTH, right - left), Math.max(1, limit.height - 2 * edge));
	}

	private Rectangle body()
	{
		return new Rectangle(bounds.x, bounds.y + PADDING + TITLE_HEIGHT, bounds.width, viewRows * ROW_HEIGHT);
	}

	private Rectangle track()
	{
		Rectangle body = body();
		return new Rectangle(bounds.x + bounds.width - Scroller.WIDTH - 3, body.y, Scroller.WIDTH, body.height);
	}

	/** The pixels a row's label may take. */
	int labelRoom()
	{
		return columnWidth - PADDING - 20 - PADDING - (scroller.isScrollable() ? Scroller.WIDTH + 3 : 0);
	}

	/** The cross that closes the panel, in the title. */
	public Rectangle closeRect()
	{
		return open ? new Rectangle(bounds.x + bounds.width - CLOSE_SIZE - 6, bounds.y + 4, CLOSE_SIZE, CLOSE_SIZE) : new Rectangle();
	}

	/** True over the cross. */
	public boolean closeHit(int x, int y)
	{
		return open && closeRect().contains(x, y);
	}

	/** True when part of the list is out of view, so it scrolls. */
	public boolean isScrollable()
	{
		return open && scroller.isScrollable();
	}

	/** How far the list is scrolled, in pixels. */
	public int getScroll()
	{
		return scroller.offset();
	}

	/** Moves the list by rows, for the wheel. Does nothing when every row shows. */
	public void scroll(int rows)
	{
		scroller.by(rows * ROW_HEIGHT);
	}

	/** True over the scroll bar, when the list scrolls. */
	public boolean scrollBarHit(int x, int y)
	{
		return open && scroller.isScrollable() && track().contains(x, y);
	}

	/** Moves the list so that its bar's thumb is at a height, while the bar is dragged. */
	public void dragBarTo(int y)
	{
		scroller.toTrack(y, track());
	}

	/**
	 * The rectangle a row takes now, or null when it is not wholly in view. For tests, so they can check that scrolling reaches
	 * every row.
	 */
	@Nullable
	public Rectangle rowRect(int index)
	{
		if (!open || index < 0 || index >= rows.size())
		{
			return null;
		}
		Rectangle body = body();
		int column = index / rowsPerColumn;
		Rectangle row = new Rectangle(bounds.x + column * columnWidth, body.y + (index % rowsPerColumn) * ROW_HEIGHT
			- (columns == 1 ? scroller.offset() : 0), columnWidth, ROW_HEIGHT);
		return body.contains(row) ? row : null;
	}

	/** The tick box row under a point, or null. Headings are not rows to click. */
	@Nullable
	public Row hit(int x, int y)
	{
		int index = indexAt(x, y);
		if (index < 0)
		{
			return null;
		}
		Row found = rows.get(index);
		return found.isHeading() ? null : found;
	}

	private int indexAt(int x, int y)
	{
		if (!open || !bounds.contains(x, y) || scrollBarHit(x, y))
		{
			return -1;
		}
		Rectangle body = body();
		if (!body.contains(x, y))
		{
			return -1;
		}
		int column = (x - bounds.x) / columnWidth;
		int row = (y - body.y + (columns == 1 ? scroller.offset() : 0)) / ROW_HEIGHT;
		int index = column * rowsPerColumn + row;
		if (column >= columns || row >= rowsPerColumn || index < 0 || index >= rows.size())
		{
			return -1;
		}
		return index;
	}

	/** True when a point is anywhere on the panel, including its title. */
	public boolean contains(int x, int y)
	{
		return open && bounds.contains(x, y);
	}

	private final Appear appear = new Appear();

	public void paint(Graphics2D g, WorldMapPlusConfig config, @Nullable Point mouse)
	{
		paint(g, config, mouse, System.currentTimeMillis());
	}

	/** Draws the panel in the frame at {@code now}, fading in when it has just opened. */
	void paint(Graphics2D g, WorldMapPlusConfig config, @Nullable Point mouse, long now)
	{
		if (!open)
		{
			return;
		}
		Composite faded = appear.begin(g, now);
		try
		{
			paintPanel(g, config, mouse, now);
		}
		finally
		{
			Appear.end(g, faded);
		}
	}

	/** True while the panel is still fading in. */
	boolean moving(long now)
	{
		return open && appear.moving(now);
	}

	private void paintPanel(Graphics2D g, WorldMapPlusConfig config, @Nullable Point mouse, long now)
	{
		Theme.panel(g, bounds.x, bounds.y, bounds.width, bounds.height);

		g.setFont(Palette.BODY_BOLD);
		FontMetrics title = g.getFontMetrics();
		g.setColor(Theme.TEXT);
		g.drawString("Layers", bounds.x + PADDING, bounds.y + PADDING + title.getAscent());
		paintClose(g, mouse);

		int hoveredIndex = mouse == null ? -1 : indexAt(mouse.x, mouse.y);
		Row hovered = hoveredIndex < 0 ? null : rows.get(hoveredIndex);
		Rectangle body = body();
		Shape before = g.getClip();
		g.clipRect(body.x, body.y, body.width, body.height);
		int room = labelRoom();
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		g.setFont(Palette.BODY_BOLD);
		FontMetrics headingMetrics = g.getFontMetrics();
		int cutIndex = -1;
		for (int i = 0; i < rows.size(); i++)
		{
			Row row = rows.get(i);
			int column = i / rowsPerColumn;
			int rowTop = body.y + (i % rowsPerColumn) * ROW_HEIGHT - (columns == 1 ? scroller.offset() : 0);
			if (rowTop + ROW_HEIGHT < body.y || rowTop > body.y + body.height)
			{
				continue;
			}
			int left = bounds.x + column * columnWidth;

			if (row.isHeading())
			{
				g.setFont(Palette.BODY_BOLD);
				g.setColor(Theme.ACCENT);
				g.drawString(PopupText.clip(row.getLabel(), headingMetrics, columnWidth - PADDING * 2
						- (scroller.isScrollable() ? Scroller.WIDTH + 3 : 0)),
					left + PADDING, rowTop + (ROW_HEIGHT - headingMetrics.getHeight()) / 2 + headingMetrics.getAscent());
				continue;
			}

			g.setFont(Palette.BODY);
			double warmth = fade.level(row.getKey(), row == hovered, now);
			if (warmth > 0)
			{
				g.setColor(Fade.withAlpha(Theme.LAYER_HOVER, warmth));
				g.fillRect(left + 2, rowTop, columnWidth - 4 - (scroller.isScrollable() ? Scroller.WIDTH + 3 : 0), ROW_HEIGHT);
			}
			int box = left + PADDING;
			int boxY = rowTop + (ROW_HEIGHT - 12) / 2;
			g.setColor(Theme.BORDER);
			g.drawRect(box, boxY, 12, 12);
			if (row.isOn(config))
			{
				g.setColor(Theme.TICK);
				g.setStroke(new BasicStroke(2f));
				g.drawLine(box + 3, boxY + 6, box + 5, boxY + 9);
				g.drawLine(box + 5, boxY + 9, box + 10, boxY + 3);
				g.setStroke(new BasicStroke(1f));
			}
			g.setColor(Theme.TEXT);
			g.drawString(PopupText.clip(row.getLabel(), metrics, room), box + 20,
				rowTop + (ROW_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent());
			if (i == hoveredIndex && PopupText.isCut(row.getLabel(), metrics, room))
			{
				cutIndex = i;
			}
		}
		g.setClip(before);
		scroller.paint(g, track());
		if (cutIndex >= 0)
		{
			Rectangle rect = rowRectAnyway(cutIndex);
			paintTag(g, metrics, rows.get(cutIndex).getLabel(), rect);
		}
	}

	private Rectangle rowRectAnyway(int index)
	{
		Rectangle body = body();
		int column = index / rowsPerColumn;
		return new Rectangle(bounds.x + column * columnWidth, body.y + (index % rowsPerColumn) * ROW_HEIGHT
			- (columns == 1 ? scroller.offset() : 0), columnWidth, ROW_HEIGHT);
	}

	private void paintClose(Graphics2D g, @Nullable Point mouse)
	{
		Rectangle cross = closeRect();
		boolean over = mouse != null && cross.contains(mouse);
		if (over)
		{
			g.setColor(Theme.LAYER_HOVER);
			g.fillRoundRect(cross.x, cross.y, cross.width, cross.height, 4, 4);
		}
		g.setColor(over ? Theme.ACCENT : Theme.TEXT);
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(cross.x + 3, cross.y + 3, cross.x + cross.width - 4, cross.y + cross.height - 4);
		g.drawLine(cross.x + cross.width - 4, cross.y + 3, cross.x + 3, cross.y + cross.height - 4);
		g.setStroke(new BasicStroke(1f));
	}

	/** Where the tag for a cut label goes, or null when there is none to show. For tests. */
	Rectangle tagFor(int index, FontMetrics metrics)
	{
		Row row = rows.get(index);
		if (!PopupText.isCut(row.getLabel(), metrics, labelRoom()))
		{
			return null;
		}
		return tagBox(metrics, row.getLabel(), rowRectAnyway(index));
	}

	private Rectangle tagBox(FontMetrics metrics, String text, Rectangle row)
	{
		Rectangle region = region();
		int width = Math.min(260, region.width);
		List<String> lines = PopupText.wrap(text, metrics, width - 16);
		int widest = 0;
		for (String line : lines)
		{
			widest = Math.max(widest, metrics.stringWidth(line));
		}
		return PopupText.tagRect(Math.min(width, widest + 16), lines.size() * metrics.getHeight() + 8, row, region);
	}

	private void paintTag(Graphics2D g, FontMetrics metrics, String text, Rectangle row)
	{
		Rectangle tag = tagBox(metrics, text, row);
		List<String> lines = PopupText.wrap(text, metrics, tag.width - 16);
		Theme.panel(g, tag.x, tag.y, tag.width, tag.height);
		g.setColor(Theme.TEXT);
		for (int i = 0; i < lines.size(); i++)
		{
			g.drawString(lines.get(i), tag.x + 8, tag.y + 4 + metrics.getAscent() + i * metrics.getHeight());
		}
	}
}
