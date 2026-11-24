package com.worldmapplus.layers;

import java.awt.Color;

/**
 * A kind of point of interest that can be switched on and off as a layer: agility courses, banks,
 * fishing spots and so on.
 */
public final class PoiCategory
{
	private final String id;
	private final String label;
	private final String glyph;
	private final Color color;
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

	/** Short stable name, the one saved in the settings. */
	public String getId()
	{
		return id;
	}

	public String getLabel()
	{
		return label;
	}

	/** A letter drawn on a coloured disc for points that have no icon of their own. */
	public String getGlyph()
	{
		return glyph;
	}

	public Color getColor()
	{
		return color;
	}

	/**
	 * True when the points can be switched on as a layer. False for kinds that are only searched,
	 * because the game's own map already draws them.
	 */
	public boolean isLayer()
	{
		return layer;
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
