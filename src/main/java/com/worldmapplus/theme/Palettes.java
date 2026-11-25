package com.worldmapplus.theme;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** The four themes, and the one in use. */
public final class Palettes
{
	/** The warm dark look the plugin has always had, and the default. Its values must never change. */
	public static final Palette BRONZE;

	/** Cool dark greys with a blue accent. */
	public static final Palette SLATE;

	/** Light, like the game's map scroll: warm paper, dark brown ink. */
	public static final Palette PARCHMENT;

	/** Strong borders, solid fills behind every piece of text, and the highest contrast. */
	public static final Palette HIGH_CONTRAST;

	/** Palettes are written in palettes.json: a name, optionally an edge width, and [r, g, b] or [r, g, b, a] for each role. */
	static
	{
		try (Reader in = new InputStreamReader(Palettes.class.getResourceAsStream("/com/worldmapplus/theme/palettes.json"),
			StandardCharsets.UTF_8))
		{
			JsonArray specs = new JsonParser().parse(in).getAsJsonArray();
			Palette[] built = new Palette[specs.size()];
			for (int i = 0; i < built.length; i++)
			{
				JsonObject spec = specs.get(i).getAsJsonObject();
				Palette.Builder builder = new Palette.Builder(spec.get("name").getAsString());
				if (spec.has("edgeWidth"))
				{
					builder.edgeWidth(spec.get("edgeWidth").getAsFloat());
				}
				for (Map.Entry<String, JsonElement> colour : spec.getAsJsonObject("colours").entrySet())
				{
					JsonArray v = colour.getValue().getAsJsonArray();
					int alpha = v.size() > 3 ? v.get(3).getAsInt() : 255;
					builder.set(Role.valueOf(colour.getKey()), v.get(0).getAsInt(), v.get(1).getAsInt(), v.get(2).getAsInt(), alpha);
				}
				built[i] = builder.build();
			}
			BRONZE = built[0];
			SLATE = built[1];
			PARCHMENT = built[2];
			HIGH_CONTRAST = built[3];
		}
		catch (IOException e)
		{
			throw new IllegalStateException("World Map Plus: palettes.json is missing from the plugin jar", e);
		}
	}

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
