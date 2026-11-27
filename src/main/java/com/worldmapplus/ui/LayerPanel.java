package com.worldmapplus.ui;

import static com.worldmapplus.theme.Palette.colour;

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
public final class LayerPanel extends Box
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

	private static final Stroke TICK = new BasicStroke(2f);

	@Getter
	private final List<Row> rows;
	private Rectangle limit = new Rectangle();
	private Rectangle block = new Rectangle();

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
		super(ROW_HEIGHT, 3);
		columnWidth = COLUMN_WIDTH;
		perColumn = 1;
		List<Row> list = new ArrayList<>();
		list.add(Row.setting("showHotspots", "Cards on icons, doorways, shops", WorldMapPlusConfig::showHotspots));
		list.add(Row.setting("showGrid", "Region grid and ids", WorldMapPlusConfig::showGrid));
		list.add(Row.setting("showLabels", "Area names", WorldMapPlusConfig::showLabels));
		list.add(Row.setting("showPins", "Pins and saved places", WorldMapPlusConfig::showPins));
		list.add(Row.setting("showOtherPluginPoints", "Other plugins", WorldMapPlusConfig::showOtherPluginPoints));
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
		this.rows = Collections.unmodifiableList(list);
	}

	public boolean isOpen()
	{
		return shown;
	}

	public void toggle()
	{
		shown = !shown;
		fade.restart();
		if (shown)
		{
			scroller.reset();
		}
	}

	public void close()
	{
		shown = false;
		fade.restart();
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
		int left = limit.x + EDGE;
		int right = Math.min(Math.min(anchor.x, this.block.x) - GAP, limit.x + limit.width - EDGE);
		int regionWidth = Math.max(MIN_WIDTH, right - left);
		int top = limit.y + EDGE;
		int bottom = limit.y + limit.height - EDGE;

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
		// A list that scrolls is one column; otherwise the rows flow into as many columns as they need.
		columns = scrolls ? 1 : Math.max(1, needed);
		perColumn = scrolls ? rowCount : roomRows;
		int viewRows = Math.min(rowCount, roomRows);
		int width = columns * columnWidth;
		int height = PADDING * 2 + TITLE_HEIGHT + viewRows * ROW_HEIGHT;
		scroller.set(scrolls ? rowCount * ROW_HEIGHT : 0, viewRows * ROW_HEIGHT);
		int x = Math.max(left, right - width);
		int y = Math.max(top, Math.min(anchor.y + anchor.height - height, bottom - height));
		bounds = new Rectangle(x, y, width, height);
		body = new Rectangle(x, y + PADDING + TITLE_HEIGHT, width, viewRows * ROW_HEIGHT);
	}

	/** The room the panel and its tags have: the map, left clear of the buttons. */
	private Rectangle region()
	{
		int left = limit.x + EDGE;
		int right = Math.min(block.x - GAP, limit.x + limit.width - EDGE);
		return new Rectangle(left, limit.y + EDGE, Math.max(MIN_WIDTH, right - left), Math.max(1, limit.height - 2 * EDGE));
	}

	/** The width of the scroll bar and its gap, when the list scrolls. */
	private int bar()
	{
		return scroller.isScrollable() ? Scroller.WIDTH + 3 : 0;
	}

	/** The pixels a row's label may take. */
	int labelRoom()
	{
		return columnWidth - PADDING - 20 - PADDING - bar();
	}

	/** The cross that closes the panel, in the title. */
	public Rectangle closeRect()
	{
		return shown ? new Rectangle(bounds.x + bounds.width - CLOSE_SIZE - 6, bounds.y + 4, CLOSE_SIZE, CLOSE_SIZE) : new Rectangle();
	}

	/** True over the cross. */
	public boolean closeHit(int x, int y)
	{
		return shown && closeRect().contains(x, y);
	}

	@Override
	int rowCount()
	{
		return rows.size();
	}

	/** The tick box row under a point, or null. Headings are not rows to click. */
	@Nullable
	public Row hit(int x, int y)
	{
		int index = indexAt(x, y);
		return index < 0 || rows.get(index).isHeading() ? null : rows.get(index);
	}

	public void paint(Graphics2D g, WorldMapPlusConfig config, @Nullable Point mouse)
	{
		paint(g, config, mouse, System.currentTimeMillis());
	}

	/** Draws the panel in the frame at {@code now}, fading in when it has just opened. */
	void paint(Graphics2D g, WorldMapPlusConfig config, @Nullable Point mouse, long now)
	{
		this.config = config;
		paint(g, mouse, now);
	}

	/** What the frame being drawn needs for its rows. */
	private WorldMapPlusConfig config;
	private int hovered;
	private int cut;
	private FontMetrics metrics;
	private FontMetrics headingMetrics;

	@Override
	void paintBox(Graphics2D g)
	{
		panel(g, bounds.x, bounds.y, bounds.width, bounds.height);
		g.setFont(Palette.BODY_BOLD);
		g.setColor(colour("TEXT"));
		g.drawString("Layers", bounds.x + PADDING, bounds.y + PADDING + g.getFontMetrics().getAscent());
		Rectangle cross = closeRect();
		cross(g, cross, mouse != null && cross.contains(mouse), "LAYER_HOVER");
		hovered = mouse == null ? -1 : indexAt(mouse.x, mouse.y);
		cut = -1;
		g.setFont(Palette.BODY);
		metrics = g.getFontMetrics();
		g.setFont(Palette.BODY_BOLD);
		headingMetrics = g.getFontMetrics();
		paintRows(g);
		if (cut >= 0)
		{
			labelTag(g, metrics, cut);
		}
	}

	@Override
	void paintRow(Graphics2D g, int index, Rectangle cell)
	{
		Row row = rows.get(index);
		if (row.isHeading())
		{
			g.setFont(Palette.BODY_BOLD);
			g.setColor(colour("ACCENT"));
			g.drawString(clip(row.getLabel(), headingMetrics, columnWidth - PADDING * 2 - bar()), cell.x + PADDING,
				middle(cell.y, ROW_HEIGHT, headingMetrics));
			return;
		}
		g.setFont(Palette.BODY);
		warm(g, "LAYER_HOVER", fade.level(row.getKey(), index == hovered, now), cell.x + 2, cell.y, columnWidth - 4 - bar(), ROW_HEIGHT);
		int box = cell.x + PADDING;
		int boxY = cell.y + (ROW_HEIGHT - 12) / 2;
		g.setColor(colour("BORDER"));
		g.drawRect(box, boxY, 12, 12);
		if (row.isOn(config))
		{
			g.setColor(colour("TICK_MAP"));
			g.setStroke(TICK);
			g.drawLine(box + 3, boxY + 6, box + 5, boxY + 9);
			g.drawLine(box + 5, boxY + 9, box + 10, boxY + 3);
			g.setStroke(Palette.ONE);
		}
		g.setColor(colour("TEXT"));
		g.drawString(clip(row.getLabel(), metrics, labelRoom()), box + 20, middle(cell.y, ROW_HEIGHT, metrics));
		if (index == hovered && isCut(row.getLabel(), metrics, labelRoom()))
		{
			cut = index;
		}
	}

	/** Where the tag for a cut label goes, or null when there is none to show. For tests. */
	Rectangle tagFor(int index, FontMetrics metrics)
	{
		return isCut(rows.get(index).getLabel(), metrics, labelRoom()) ? labelTag(null, metrics, index) : null;
	}

	/** The tag of a row's whole label, drawn when {@code g} is given. */
	private Rectangle labelTag(@Nullable Graphics2D g, FontMetrics metrics, int index)
	{
		Rectangle region = region();
		return tag(g, metrics, rows.get(index).getLabel(), Math.min(260, region.width), rowBox(index, new Rectangle()), region);
	}
}
