package com.worldmapplus.theme;

import static com.worldmapplus.theme.Role.*;

/** The four themes, and the one in use. */
public final class Palettes
{
	/** The warm dark look the plugin has always had, and the default. Its values must never change. */
	public static final Palette BRONZE = new Palette.Builder("Bronze")
		.set(ACCENT, 255, 214, 120).set(TEXT, 235, 222, 190).set(TEXT_DIM, 165, 150, 122)
		.set(PANEL, 24, 21, 17, 238).set(PANEL_ACTIVE, 40, 34, 26, 245).set(BORDER, 94, 82, 62)
		.set(HINT, 120, 200, 130).set(HOVER, 255, 255, 255, 38).set(SHADOW, 0, 0, 0, 55)
		.set(FIELD_FILL, 22, 19, 15, 235).set(FIELD_FOCUSED, 40, 34, 26, 245).set(FIELD_DIM, 150, 136, 108)
		.set(ROW_SELECTED, 255, 255, 255, 36).set(CHIP, 94, 82, 62, 230).set(MISSING, 232, 176, 72)
		.set(ACCENT_GLOW, 255, 214, 120, 90).set(SEARCH_INSET, 0, 0, 0, 90).set(RESULT_BUTTON, 0, 0, 0, 60)
		.set(CARD_BUTTON, 0, 0, 0, 70).set(MENU_HOVER, 255, 255, 255, 40).set(FLOOR_FILL, 22, 19, 15, 220)
		.set(FLOOR_HOVER, 58, 50, 38, 235).set(ARROW_OFF, 235, 222, 190, 70).set(BUTTON_HOVER_MAP, 62, 53, 40, 245)
		.set(LAYER_HOVER, 255, 255, 255, 36).set(TICK_MAP, 120, 230, 140).set(SCROLL_TRACK, 255, 255, 255, 22)
		.set(SCROLL_THUMB, 255, 214, 120, 190).set(PLATE, 22, 19, 15, 200).set(FRAME, 235, 222, 190, 150)
		.set(PAGE, 30, 28, 25).set(CARD, 43, 39, 33).set(CARD_HOVER, 58, 52, 43).set(CARD_ACTIVE, 64, 55, 40)
		.set(EDGE, 78, 69, 54).set(FIELD, 24, 22, 19).set(ACCENT_DEEP, 196, 150, 70).set(TEXT_FAINT, 118, 106, 86)
		.set(GOOD, 120, 220, 130).set(BAD, 255, 120, 100).set(BUTTON, 64, 57, 46).set(BUTTON_TOP, 74, 66, 53)
		.set(BUTTON_HOVER, 84, 74, 58).set(BUTTON_PRESSED, 48, 42, 34).set(BUTTON_EDGE, 102, 90, 70)
		.set(PRIMARY, 214, 170, 82).set(PRIMARY_TOP, 240, 200, 112).set(PRIMARY_HOVER, 250, 214, 130)
		.set(PRIMARY_PRESSED, 186, 144, 64).set(PRIMARY_TEXT, 40, 30, 14).set(DISABLED_TEXT, 110, 100, 84)
		.set(BADGE, 74, 64, 48).set(FOCUS, 120, 190, 255)
		.build();

	/** Cool dark greys with a blue accent. */
	public static final Palette SLATE = new Palette.Builder("Slate")
		.set(ACCENT, 130, 188, 255).set(TEXT, 222, 228, 238).set(TEXT_DIM, 156, 166, 182)
		.set(PANEL, 20, 23, 28, 238).set(PANEL_ACTIVE, 32, 38, 48, 245).set(BORDER, 72, 82, 98)
		.set(HINT, 120, 205, 155).set(HOVER, 255, 255, 255, 38).set(SHADOW, 0, 0, 0, 55)
		.set(FIELD_FILL, 18, 21, 26, 235).set(FIELD_FOCUSED, 32, 38, 48, 245).set(FIELD_DIM, 146, 156, 172)
		.set(ROW_SELECTED, 255, 255, 255, 36).set(CHIP, 72, 82, 98, 230).set(MISSING, 232, 176, 72)
		.set(ACCENT_GLOW, 130, 188, 255, 90).set(SEARCH_INSET, 0, 0, 0, 90).set(RESULT_BUTTON, 0, 0, 0, 60)
		.set(CARD_BUTTON, 0, 0, 0, 70).set(MENU_HOVER, 255, 255, 255, 40).set(FLOOR_FILL, 18, 21, 26, 220)
		.set(FLOOR_HOVER, 46, 54, 68, 235).set(ARROW_OFF, 222, 228, 238, 70).set(BUTTON_HOVER_MAP, 50, 58, 74, 245)
		.set(LAYER_HOVER, 255, 255, 255, 36).set(TICK_MAP, 110, 220, 150).set(SCROLL_TRACK, 255, 255, 255, 22)
		.set(SCROLL_THUMB, 130, 188, 255, 190).set(PLATE, 18, 21, 26, 200).set(FRAME, 222, 228, 238, 150)
		.set(PAGE, 26, 29, 34).set(CARD, 36, 41, 49).set(CARD_HOVER, 48, 55, 66).set(CARD_ACTIVE, 40, 56, 82)
		.set(EDGE, 62, 71, 86).set(FIELD, 20, 23, 28).set(ACCENT_DEEP, 72, 130, 212).set(TEXT_FAINT, 124, 134, 150)
		.set(GOOD, 120, 220, 150).set(BAD, 255, 125, 115).set(BUTTON, 52, 59, 71).set(BUTTON_TOP, 62, 70, 84)
		.set(BUTTON_HOVER, 74, 84, 102).set(BUTTON_PRESSED, 40, 46, 56).set(BUTTON_EDGE, 88, 100, 120)
		.set(PRIMARY, 98, 162, 242).set(PRIMARY_TOP, 128, 186, 255).set(PRIMARY_HOVER, 146, 198, 255)
		.set(PRIMARY_PRESSED, 74, 134, 214).set(PRIMARY_TEXT, 8, 18, 36).set(DISABLED_TEXT, 112, 122, 138)
		.set(BADGE, 50, 62, 82).set(FOCUS, 190, 222, 255)
		.build();

	/** Light, like the game's map scroll: warm paper, dark brown ink. */
	public static final Palette PARCHMENT = new Palette.Builder("Parchment")
		.set(ACCENT, 118, 66, 8).set(TEXT, 44, 32, 16).set(TEXT_DIM, 88, 70, 44)
		.set(PANEL, 236, 224, 196, 242).set(PANEL_ACTIVE, 246, 238, 214, 248).set(BORDER, 122, 98, 60)
		.set(HINT, 22, 104, 44).set(HOVER, 0, 0, 0, 30).set(SHADOW, 0, 0, 0, 70)
		.set(FIELD_FILL, 244, 236, 212, 240).set(FIELD_FOCUSED, 252, 246, 228, 248).set(FIELD_DIM, 98, 80, 52)
		.set(ROW_SELECTED, 0, 0, 0, 30).set(CHIP, 196, 174, 132, 235).set(MISSING, 138, 76, 0)
		.set(ACCENT_GLOW, 118, 66, 8, 90).set(SEARCH_INSET, 0, 0, 0, 30).set(RESULT_BUTTON, 0, 0, 0, 24)
		.set(CARD_BUTTON, 0, 0, 0, 28).set(MENU_HOVER, 0, 0, 0, 34).set(FLOOR_FILL, 236, 224, 196, 235)
		.set(FLOOR_HOVER, 222, 206, 170, 240).set(ARROW_OFF, 44, 32, 16, 90).set(BUTTON_HOVER_MAP, 224, 208, 172, 248)
		.set(LAYER_HOVER, 0, 0, 0, 30).set(TICK_MAP, 22, 110, 44).set(SCROLL_TRACK, 0, 0, 0, 30)
		.set(SCROLL_THUMB, 118, 66, 8, 200).set(PLATE, 236, 224, 196, 225).set(FRAME, 44, 32, 16, 150)
		.set(PAGE, 220, 207, 176).set(CARD, 238, 228, 202).set(CARD_HOVER, 247, 239, 216).set(CARD_ACTIVE, 250, 238, 198)
		.set(EDGE, 156, 130, 90).set(FIELD, 249, 243, 226).set(ACCENT_DEEP, 150, 92, 24).set(TEXT_FAINT, 110, 92, 64)
		.set(GOOD, 20, 100, 40).set(BAD, 164, 36, 26).set(BUTTON, 214, 198, 160).set(BUTTON_TOP, 224, 210, 174)
		.set(BUTTON_HOVER, 232, 220, 188).set(BUTTON_PRESSED, 196, 178, 138).set(BUTTON_EDGE, 138, 112, 72)
		.set(PRIMARY, 124, 72, 12).set(PRIMARY_TOP, 138, 84, 22).set(PRIMARY_HOVER, 150, 96, 32)
		.set(PRIMARY_PRESSED, 100, 58, 8).set(PRIMARY_TEXT, 252, 244, 222).set(DISABLED_TEXT, 128, 112, 86)
		.set(BADGE, 212, 194, 148).set(FOCUS, 20, 78, 190)
		.build();

	/** Strong borders, solid fills behind every piece of text, and the highest contrast. */
	public static final Palette HIGH_CONTRAST = new Palette.Builder("High contrast")
		.edgeWidth(2f)
		.set(ACCENT, 255, 230, 0).set(TEXT, 255, 255, 255).set(TEXT_DIM, 214, 214, 214)
		.set(PANEL, 0, 0, 0, 255).set(PANEL_ACTIVE, 24, 24, 24, 255).set(BORDER, 255, 255, 255)
		.set(HINT, 120, 255, 140).set(HOVER, 255, 255, 255, 70).set(SHADOW, 0, 0, 0, 120)
		.set(FIELD_FILL, 0, 0, 0, 255).set(FIELD_FOCUSED, 24, 24, 24, 255).set(FIELD_DIM, 204, 204, 204)
		.set(ROW_SELECTED, 255, 255, 255, 70).set(CHIP, 80, 80, 80, 255).set(MISSING, 255, 190, 60)
		.set(ACCENT_GLOW, 255, 230, 0, 160).set(SEARCH_INSET, 0, 0, 0, 160).set(RESULT_BUTTON, 40, 40, 40, 255)
		.set(CARD_BUTTON, 40, 40, 40, 255).set(MENU_HOVER, 255, 255, 255, 70).set(FLOOR_FILL, 0, 0, 0, 255)
		.set(FLOOR_HOVER, 56, 56, 56, 255).set(ARROW_OFF, 255, 255, 255, 110).set(BUTTON_HOVER_MAP, 64, 64, 64, 255)
		.set(LAYER_HOVER, 255, 255, 255, 60).set(TICK_MAP, 110, 255, 140).set(SCROLL_TRACK, 255, 255, 255, 50)
		.set(SCROLL_THUMB, 255, 230, 0, 255).set(PLATE, 0, 0, 0, 255).set(FRAME, 255, 255, 255, 220)
		.set(PAGE, 0, 0, 0).set(CARD, 18, 18, 18).set(CARD_HOVER, 44, 44, 44).set(CARD_ACTIVE, 52, 46, 0)
		.set(EDGE, 200, 200, 200).set(FIELD, 0, 0, 1).set(ACCENT_DEEP, 255, 200, 0).set(TEXT_FAINT, 176, 176, 176)
		.set(GOOD, 110, 255, 140).set(BAD, 255, 130, 120).set(BUTTON, 38, 38, 38).set(BUTTON_TOP, 50, 50, 50)
		.set(BUTTON_HOVER, 76, 76, 76).set(BUTTON_PRESSED, 20, 20, 20).set(BUTTON_EDGE, 245, 245, 245)
		.set(PRIMARY, 255, 222, 0).set(PRIMARY_TOP, 255, 240, 90).set(PRIMARY_HOVER, 255, 246, 140)
		.set(PRIMARY_PRESSED, 220, 196, 0).set(PRIMARY_TEXT, 1, 1, 1).set(DISABLED_TEXT, 150, 150, 150)
		.set(BADGE, 64, 56, 0).set(FOCUS, 0, 210, 255)
		.build();

	private static volatile Palette current = BRONZE;

	private Palettes()
	{
	}

	/** The palette in use. */
	public static Palette current()
	{
		return current;
	}

	public static Palette of(ThemeName name)
	{
		switch (name == null ? ThemeName.BRONZE : name)
		{
			case SLATE:
				return SLATE;
			case PARCHMENT:
				return PARCHMENT;
			case HIGH_CONTRAST:
				return HIGH_CONTRAST;
			default:
				return BRONZE;
		}
	}

	public static Palette[] all()
	{
		return new Palette[] {BRONZE, SLATE, PARCHMENT, HIGH_CONTRAST};
	}

	/** Makes this the palette in use. Callers then tell the drawing classes ({@code Look.use}). */
	public static void set(Palette palette)
	{
		current = palette;
	}
}
