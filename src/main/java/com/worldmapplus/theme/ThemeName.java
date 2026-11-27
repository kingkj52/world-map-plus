package com.worldmapplus.theme;

import com.worldmapplus.prefs.*;

/** The themes a player can choose in the settings. */
public enum ThemeName
{
	BRONZE,
	SLATE,
	PARCHMENT,
	HIGH_CONTRAST;

	@Override
	public String toString()
	{
		return Settings.label(this);
	}
}
