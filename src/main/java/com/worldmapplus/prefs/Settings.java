package com.worldmapplus.prefs;

import com.worldmapplus.WorldMapPlusConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

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
		configManager.setConfiguration(WorldMapPlusConfig.GROUP, key, value);
	}

	/** Sets one of the plugin's settings. The key is the one named in {@link WorldMapPlusConfig}. */
	public void setBoolean(String key, boolean value)
	{
		configManager.setConfiguration(WorldMapPlusConfig.GROUP, key, value);
	}
}
