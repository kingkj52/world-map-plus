package com.worldmapplus.prefs;

import com.worldmapplus.WorldMapPlusConfig;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Remembers where the map was last looking, so it can open there again.
 * <p>
 * Stored as one value, {@code mapId,x,y,pixelsPerTile}, so a read can never see half an update.
 * Anything that does not parse is treated as nothing remembered.
 */
@Singleton
public class ViewMemory
{
	/** The slot of the window over the world map. */
	public static final String WORLD_MAP = "lastView";

	/** The slot of the see-through map over the game. */
	public static final String HUD = "lastHudView";

	private final ConfigManager configManager;

	@Inject
	public ViewMemory(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** A remembered view. */
	public static final class Saved
	{
		private final int mapId;
		private final double x;
		private final double y;
		private final double pixelsPerTile;

		Saved(int mapId, double x, double y, double pixelsPerTile)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.pixelsPerTile = pixelsPerTile;
		}

		public int getMapId()
		{
			return mapId;
		}

		public double getX()
		{
			return x;
		}

		public double getY()
		{
			return y;
		}

		public double getPixelsPerTile()
		{
			return pixelsPerTile;
		}
	}

	public void save(String slot, int mapId, double x, double y, double pixelsPerTile)
	{
		configManager.setConfiguration(WorldMapPlusConfig.GROUP, slot, format(mapId, x, y, pixelsPerTile));
	}

	@Nullable
	public Saved load(String slot)
	{
		return parse(configManager.getConfiguration(WorldMapPlusConfig.GROUP, slot));
	}

	static String format(int mapId, double x, double y, double pixelsPerTile)
	{
		return mapId + "," + round(x) + "," + round(y) + "," + round(pixelsPerTile);
	}

	private static String round(double value)
	{
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}

	@Nullable
	static Saved parse(@Nullable String text)
	{
		if (text == null)
		{
			return null;
		}
		String[] parts = text.split(",");
		if (parts.length != 4)
		{
			return null;
		}
		try
		{
			int mapId = Integer.parseInt(parts[0].trim());
			double x = Double.parseDouble(parts[1].trim());
			double y = Double.parseDouble(parts[2].trim());
			double ppt = Double.parseDouble(parts[3].trim());
			if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(ppt) || Double.isInfinite(x)
				|| Double.isInfinite(y) || Double.isInfinite(ppt) || ppt <= 0)
			{
				return null;
			}
			return new Saved(mapId, x, y, ppt);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
