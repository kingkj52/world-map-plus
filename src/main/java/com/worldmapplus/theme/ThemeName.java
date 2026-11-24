package com.worldmapplus.theme;

/** The themes a player can choose in the settings. */
public enum ThemeName
{
	BRONZE("Bronze"),
	SLATE("Slate"),
	PARCHMENT("Parchment"),
	HIGH_CONTRAST("High contrast");

	private final String label;

	ThemeName(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
