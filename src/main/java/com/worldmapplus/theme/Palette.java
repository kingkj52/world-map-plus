package com.worldmapplus.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Stroke;
import java.util.EnumMap;
import java.util.Map;

/**
 * One complete look: a colour for every {@link Role}, plus the fonts and strokes the chrome is drawn with. Immutable, and
 * every colour, font and stroke is made once, so painting with a palette allocates nothing. A palette is chosen by name
 * in the settings and is the only place the chrome's colours are written down.
 */
public final class Palette
{
	/** The fonts of the chrome, the same in every theme so layouts do not move when the theme changes. */
	public static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	public static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	public static final Font BODY_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);
	public static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
	public static final Font SMALL_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 11);
	public static final Font SMALL_ITALIC = new Font(Font.SANS_SERIF, Font.ITALIC, 11);
	public static final Font TINY = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
	public static final Font FIELD_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
	public static final Font BUTTON_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 14);
	public static final Font CODE = new Font(Font.MONOSPACED, Font.BOLD, 13);

	/** Strokes used by several pieces. */
	public static final Stroke ONE = new BasicStroke(1f);
	public static final Stroke ICON_LINE = new BasicStroke(1.6f);

	private final String name;
	private final Color[] colours;
	private final Stroke edge;
	private final float edgeWidth;

	private Palette(String name, Color[] colours, float edgeWidth)
	{
		this.name = name;
		this.colours = colours;
		this.edgeWidth = edgeWidth;
		this.edge = edgeWidth == 1f ? ONE : new BasicStroke(edgeWidth);
	}

	public String name()
	{
		return name;
	}

	/** The colour for a role. Never null. */
	public Color get(Role role)
	{
		return colours[role.ordinal()];
	}

	/** The stroke of card and panel edges: one pixel, or heavier in the high contrast theme. */
	public Stroke edge()
	{
		return edge;
	}

	public float edgeWidth()
	{
		return edgeWidth;
	}

	/** The role whose colour is exactly {@code colour} in this palette, or null. The first role in declaration order wins. */
	public Role roleOf(Color colour)
	{
		for (Role role : Role.values())
		{
			if (colours[role.ordinal()].equals(colour))
			{
				return role;
			}
		}
		return null;
	}

	/** Builds a palette role by role. */
	public static final class Builder
	{
		private final String name;
		private final Map<Role, Color> set = new EnumMap<>(Role.class);
		private float edgeWidth = 1f;

		public Builder(String name)
		{
			this.name = name;
		}

		public Builder set(Role role, int r, int g, int b)
		{
			set.put(role, new Color(r, g, b));
			return this;
		}

		public Builder set(Role role, int r, int g, int b, int a)
		{
			set.put(role, new Color(r, g, b, a));
			return this;
		}

		public Builder edgeWidth(float width)
		{
			this.edgeWidth = width;
			return this;
		}

		/** Fails when a role was left out, so a new role cannot be forgotten by a theme. */
		public Palette build()
		{
			Color[] all = new Color[Role.values().length];
			for (Role role : Role.values())
			{
				Color colour = set.get(role);
				if (colour == null)
				{
					throw new IllegalStateException(name + " has no colour for " + role);
				}
				all[role.ordinal()] = colour;
			}
			return new Palette(name, all, edgeWidth);
		}
	}
}
