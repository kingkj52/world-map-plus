package com.worldmapplus.layers;

import java.awt.*;
import lombok.*;

/**
 * A kind of point of interest that can be switched on and off as a layer: agility courses, banks,
 * fishing spots and so on.
 */
@Getter
public final class PoiCategory
{
	/** Short stable name, the one saved in the settings. */
	private final String id;
	private final String label;
	/** A letter drawn on a coloured disc for points that have no icon of their own. */
	private final String glyph;
	private final Color color;
	/**
	 * True when the points can be switched on as a layer. False for kinds that are only searched,
	 * because the game's own map already draws them.
	 */
	private final boolean layer;

	public PoiCategory(String id, String label, String glyph, Color color)
	{
		this(id, label, glyph, color, true);
	}

	public PoiCategory(String id, String label, String glyph, Color color, boolean layer)
	{
		this.layer = layer;
		this.id = id;
		this.label = label;
		this.glyph = glyph;
		this.color = color;
	}

	/** Parses {@code #rrggbb}, giving grey for anything else. */
	public static Color parseColor(String text)
	{
		try
		{
			return Color.decode(text);
		}
		catch (NumberFormatException | NullPointerException e)
		{
			return Color.GRAY;
		}
	}
}
