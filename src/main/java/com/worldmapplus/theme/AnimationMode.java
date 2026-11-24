package com.worldmapplus.theme;

/** How much the plugin animates. */
public enum AnimationMode
{
	ON("On"),
	REDUCED("Reduced"),
	OFF("Off");

	private final String label;

	AnimationMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
