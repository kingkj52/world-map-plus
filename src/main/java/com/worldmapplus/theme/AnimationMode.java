package com.worldmapplus.theme;

import com.worldmapplus.prefs.*;

/** Whether the plugin animates. Round 76 dropped the third choice, Reduced; a stored Reduced reads as the default, On. */
public enum AnimationMode
{
	ON,
	OFF;

	@Override
	public String toString()
	{
		return Settings.label(this);
	}
}
