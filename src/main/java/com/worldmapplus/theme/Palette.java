package com.worldmapplus.theme;

import com.google.gson.*;
import com.worldmapplus.panel.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * The themes and the one colour lookup everything the plugin draws uses: {@link #colour} gives a colour by its name in the theme in
 * use. The colours are written in {@code palettes.json} only: per theme a name, optionally an edge width, and [r, g, b] or
 * [r, g, b, a] for each colour name, in the order of {@link ThemeName}. Every colour is made once when the file is read, so a lookup
 * makes nothing. The map's content (icons, routes, pins, the player's marker) is not chrome and keeps its own colours in every theme.
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

	/** Bronze (the look the plugin has always had, whose values must never change), Slate, Parchment and High contrast. */
	private static final Palette[] ALL = read();
	private static volatile Palette current = ALL[0];

	private final String name;
	/** The colours by name, in the file's order. */
	private final Map<String, Color> colours = new LinkedHashMap<>();
	private final float edgeWidth;
	private final Stroke edge;

	private Palette(JsonObject spec)
	{
		name = spec.get("name").getAsString();
		edgeWidth = spec.has("edgeWidth") ? spec.get("edgeWidth").getAsFloat() : 1f;
		edge = edgeWidth == 1f ? ONE : new BasicStroke(edgeWidth);
		for (Map.Entry<String, JsonElement> colour : spec.getAsJsonObject("colours").entrySet())
		{
			JsonArray v = colour.getValue().getAsJsonArray();
			colours.put(colour.getKey(), new Color(v.get(0).getAsInt(), v.get(1).getAsInt(), v.get(2).getAsInt(),
				v.size() > 3 ? v.get(3).getAsInt() : 255));
		}
	}

	private static Palette[] read()
	{
		try (Reader in = new InputStreamReader(Palette.class.getResourceAsStream("/com/worldmapplus/theme/palettes.json"),
			StandardCharsets.UTF_8))
		{
			JsonArray specs = new JsonParser().parse(in).getAsJsonArray();
			Palette[] all = new Palette[specs.size()];
			for (int i = 0; i < all.length; i++)
			{
				all[i] = new Palette(specs.get(i).getAsJsonObject());
			}
			return all;
		}
		catch (IOException | RuntimeException e)
		{
			throw new IllegalStateException("World Map Plus: palettes.json is missing from the plugin jar or damaged", e);
		}
	}

	/** The colour of this name in the theme in use. Never null for a name the file has; a test checks every name the code asks. */
	public static Color colour(String name)
	{
		return current.colours.get(name);
	}

	/** The theme in use. */
	public static Palette current()
	{
		return current;
	}

	public static Palette of(ThemeName name)
	{
		return ALL[name == null ? 0 : name.ordinal()];
	}

	public static Palette[] all()
	{
		return ALL.clone();
	}

	/** Makes a theme the look of everything the plugin draws, and gives the side panel its colours. Cheap; safe to call again. */
	public static void use(ThemeName name)
	{
		use(of(name));
	}

	public static void use(Palette palette)
	{
		current = palette;
		PanelTheme.apply(palette);
	}

	public String name()
	{
		return name;
	}

	/** The colour of this name in this theme. */
	public Color get(String name)
	{
		return colours.get(name);
	}

	/** Every colour name, in the file's order. */
	public Set<String> names()
	{
		return Collections.unmodifiableSet(colours.keySet());
	}

	/** The name whose colour is exactly {@code colour} in this theme, or null. The first in the file's order wins. */
	public String nameOf(Color colour)
	{
		for (Map.Entry<String, Color> e : colours.entrySet())
		{
			if (e.getValue().equals(colour))
			{
				return e.getKey();
			}
		}
		return null;
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
}
