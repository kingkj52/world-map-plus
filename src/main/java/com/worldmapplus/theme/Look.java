package com.worldmapplus.theme;

import com.worldmapplus.panel.*;
import com.worldmapplus.ui.*;

/** Puts a theme into use: the palette, the in-map chrome and the side panel's colours. */
public final class Look
{
	private Look()
	{
	}

	/** Makes {@code name} the look of everything the plugin draws. Cheap, and safe to call again with the same name. */
	public static void use(ThemeName name)
	{
		use(Palettes.of(name));
	}

	public static void use(Palette palette)
	{
		Palettes.set(palette);
		Theme.apply(palette);
		PanelTheme.apply(palette);
	}
}
