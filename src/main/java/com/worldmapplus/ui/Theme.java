package com.worldmapplus.ui;

import com.worldmapplus.theme.*;
import java.awt.*;

/**
 * The look shared by everything the map draws over the imagery: one set of colours and fonts, and one way of
 * painting a panel, so cards, menus and panels read as parts of the same thing. The colours are copies of the palette in
 * use ({@link Palettes#current()}) so painting reads a field and makes nothing; {@link #apply} refreshes them when the
 * theme changes. A frame drawn while the theme changes may use the old colour for one more frame.
 */
public final class Theme
{
	/** The fill of a card or a panel. Dark enough to read over any terrain, a little see-through. */
	static Color PANEL;

	/** A panel that is the one thing being used, such as a field with the keyboard. */
	static Color PANEL_ACTIVE;

	static Color BORDER;

	/** The accent: titles, the active field's edge, the scroll thumb. */
	static Color ACCENT;

	static Color TEXT;
	static Color TEXT_DIM;
	static Color HINT;
	static Color HOVER;

	static Color FIELD_FILL;
	static Color FIELD_FOCUSED;
	static Color FIELD_DIM;
	static Color ROW_SELECTED;
	static Color CHIP;
	static Color MISSING;
	static Color ACCENT_GLOW;
	static Color SEARCH_INSET;
	static Color RESULT_BUTTON;
	static Color CARD_BUTTON;
	static Color MENU_HOVER;
	static Color FLOOR_FILL;
	static Color FLOOR_HOVER;
	static Color ARROW_OFF;
	static Color BUTTON_HOVER;
	static Color LAYER_HOVER;
	static Color TICK;
	static Color SCROLL_TRACK;
	static Color SCROLL_THUMB;
	static Color PLATE;
	static Color FRAME;

	private static Color SHADOW;
	private static Stroke EDGE;
	private static int edgeInset;

	static final int ARC = 8;

	static final Font TITLE_FONT = Palette.TITLE;
	static final Font BODY_FONT = Palette.BODY;
	static final Font SMALL_FONT = Palette.SMALL;

	static
	{
		apply(Palettes.current());
	}

	private Theme()
	{
	}

	/** Takes the colours of a palette. Called when the theme is chosen. */
	public static void apply(Palette p)
	{
		PANEL = p.get(Role.PANEL);
		PANEL_ACTIVE = p.get(Role.PANEL_ACTIVE);
		BORDER = p.get(Role.BORDER);
		ACCENT = p.get(Role.ACCENT);
		TEXT = p.get(Role.TEXT);
		TEXT_DIM = p.get(Role.TEXT_DIM);
		HINT = p.get(Role.HINT);
		HOVER = p.get(Role.HOVER);
		SHADOW = p.get(Role.SHADOW);
		FIELD_FILL = p.get(Role.FIELD_FILL);
		FIELD_FOCUSED = p.get(Role.FIELD_FOCUSED);
		FIELD_DIM = p.get(Role.FIELD_DIM);
		ROW_SELECTED = p.get(Role.ROW_SELECTED);
		CHIP = p.get(Role.CHIP);
		MISSING = p.get(Role.MISSING);
		ACCENT_GLOW = p.get(Role.ACCENT_GLOW);
		SEARCH_INSET = p.get(Role.SEARCH_INSET);
		RESULT_BUTTON = p.get(Role.RESULT_BUTTON);
		CARD_BUTTON = p.get(Role.CARD_BUTTON);
		MENU_HOVER = p.get(Role.MENU_HOVER);
		FLOOR_FILL = p.get(Role.FLOOR_FILL);
		FLOOR_HOVER = p.get(Role.FLOOR_HOVER);
		ARROW_OFF = p.get(Role.ARROW_OFF);
		BUTTON_HOVER = p.get(Role.BUTTON_HOVER_MAP);
		LAYER_HOVER = p.get(Role.LAYER_HOVER);
		TICK = p.get(Role.TICK_MAP);
		SCROLL_TRACK = p.get(Role.SCROLL_TRACK);
		SCROLL_THUMB = p.get(Role.SCROLL_THUMB);
		PLATE = p.get(Role.PLATE);
		FRAME = p.get(Role.FRAME);
		EDGE = p.edge();
		edgeInset = (int) p.edgeWidth() / 2;
	}

	/** A panel with a soft shadow under it, a fill and an edge (one pixel, or two in the high contrast theme). */
	static void panel(Graphics2D g, int x, int y, int width, int height)
	{
		panel(g, x, y, width, height, PANEL, BORDER);
	}

	static void panel(Graphics2D g, int x, int y, int width, int height, Color fill, Color edge)
	{
		g.setColor(SHADOW);
		g.fillRoundRect(x - 1, y + 1, width + 2, height + 2, ARC + 2, ARC + 2);
		g.fillRoundRect(x - 2, y + 2, width + 4, height + 4, ARC + 4, ARC + 4);
		g.setColor(fill);
		g.fillRoundRect(x, y, width, height, ARC, ARC);
		g.setStroke(EDGE);
		g.setColor(edge);
		g.drawRoundRect(x + edgeInset, y + edgeInset, width - 1 - 2 * edgeInset, height - 1 - 2 * edgeInset, ARC, ARC);
	}
}
