package com.worldmapplus.theme;

/**
 * Every colour the plugin's own chrome is drawn with, by what it is for. The map's content (icons, routes, pins, the
 * player's marker) is not chrome and keeps its own colours in every theme.
 */
public enum Role
{
	// Shared by the map and the side panel.
	ACCENT,
	TEXT,
	TEXT_DIM,

	// In-map cards, menus, popups and the search bar.
	PANEL,
	PANEL_ACTIVE,
	BORDER,
	HINT,
	HOVER,
	SHADOW,
	FIELD_FILL,
	FIELD_FOCUSED,
	FIELD_DIM,
	ROW_SELECTED,
	CHIP,
	MISSING,
	ACCENT_GLOW,
	SEARCH_INSET,
	RESULT_BUTTON,
	CARD_BUTTON,
	MENU_HOVER,
	FLOOR_FILL,
	FLOOR_HOVER,
	ARROW_OFF,
	BUTTON_HOVER_MAP,
	LAYER_HOVER,
	TICK_MAP,
	SCROLL_TRACK,
	SCROLL_THUMB,
	PLATE,
	FRAME,

	// The side panel.
	PAGE,
	CARD,
	CARD_HOVER,
	CARD_ACTIVE,
	EDGE,
	FIELD,
	ACCENT_DEEP,
	TEXT_FAINT,
	GOOD,
	BAD,
	BUTTON,
	BUTTON_TOP,
	BUTTON_HOVER,
	BUTTON_PRESSED,
	BUTTON_EDGE,
	PRIMARY,
	PRIMARY_TOP,
	PRIMARY_HOVER,
	PRIMARY_PRESSED,
	PRIMARY_TEXT,
	DISABLED_TEXT,
	BADGE,
	FOCUS
}
