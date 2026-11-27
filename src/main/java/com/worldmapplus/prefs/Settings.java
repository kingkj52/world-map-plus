package com.worldmapplus.prefs;

import com.worldmapplus.*;
import java.util.*;
import javax.inject.*;
import net.runelite.client.config.*;

/**
 * Lets the map flip its own on and off settings from its layer panel.
 * <p>
 * The values are the same ones shown in the plugin's settings, so a change made in the panel and
 * one made in the settings are the same change and both survive a restart.
 */
@Singleton
public class Settings
{
	private final ConfigManager configManager;

	@Inject
	public Settings(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** Sets a text setting, such as the list of point of interest layers that are on. */
	public void setString(String key, String value)
	{
		set(key, value);
	}

	/** Sets one of the plugin's settings. The key is the one named in {@link WorldMapPlusConfig}. */
	public void setBoolean(String key, boolean value)
	{
		set(key, value);
	}

	/** A setting's choice as the settings list shows it: its name in plain words, such as Where I last looked. */
	public static String label(Enum<?> choice)
	{
		String words = choice.name().replace('_', ' ').toLowerCase(Locale.ROOT).replace(" i ", " I ");
		return Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	void set(String key, Object value)
	{
		configManager.setConfiguration(WorldMapPlusConfig.GROUP, key, value);
	}
}
