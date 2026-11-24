package com.worldmapplus.tiles;

import java.awt.image.BufferedImage;
import javax.annotation.Nullable;

/**
 * Where map tile images come from. The plugin reads them from its own jar; tests supply
 * synthetic ones.
 * <p>
 * A tile is one 64 by 64 tile map square drawn at 4 pixels per game tile, so 256 by 256 pixels.
 * Squares are numbered {@code x = worldX / 64}, {@code y = worldY / 64}, so a larger y is further
 * north.
 */
public interface TileSource
{
	/** The side of a tile image in pixels. */
	int TILE_PIXELS = 256;

	/** The side of a tile in game tiles. */
	int TILE_SIZE = 64;

	/**
	 * Floors above the ground are stored as maps of their own, numbered this far apart from the map
	 * they belong to, so the cache and the jar layout need no idea of floors.
	 */
	int FLOOR_STRIDE = 20_000;

	/** The highest floor there can be. */
	int MAX_FLOOR = 3;

	/** The id tiles are stored under for a map's floor. Floor 0 is the map itself. */
	static int tileMapId(int mapId, int floor)
	{
		return floor <= 0 ? mapId : mapId + Math.min(floor, MAX_FLOOR) * FLOOR_STRIDE;
	}

	/** True when the map has a tile for this square. Cheap, and safe to call every frame. */
	boolean exists(int mapId, int x, int y);

	/**
	 * Reads and decodes a tile. Blocking, so never called from the render thread. Returns null when
	 * the square has no tile or cannot be decoded.
	 */
	@Nullable
	BufferedImage load(int mapId, int x, int y);

	/**
	 * A picture of a whole map at a few pixels per square, for when the view is zoomed so far out
	 * that individual tiles would be wasted work. Null when the map has none.
	 */
	@Nullable
	default BufferedImage overview(int mapId)
	{
		return null;
	}

	/**
	 * A picture that is made a piece at a time, so that a long decode can be shared out over many short tasks and other work
	 * gets the thread in between.
	 */
	interface Picture
	{
		/**
		 * Works for about the given time, at least a little, and returns true when the picture is finished or can not be made.
		 * Never throws.
		 */
		boolean step(long nanos);

		/** The picture once {@link #step} has returned true, or null when it could not be made. */
		@Nullable
		BufferedImage image();
	}

	/**
	 * Starts the overview of a map as a {@link Picture}, or returns null when the map has none. The default makes the whole
	 * overview in the first piece. Blocking, so never called from the render thread.
	 */
	@Nullable
	default Picture startOverview(int mapId)
	{
		return new Picture()
		{
			private BufferedImage image;

			@Override
			public boolean step(long nanos)
			{
				image = overview(mapId);
				return true;
			}

			@Override
			public BufferedImage image()
			{
				return image;
			}
		};
	}

	/**
	 * Lets go of whatever the source keeps in memory to make reads faster. It stays usable and reads again when asked.
	 * Called when the plugin shuts down.
	 */
	default void release()
	{
	}
}
