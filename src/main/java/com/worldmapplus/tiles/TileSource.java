package com.worldmapplus.tiles;

import java.awt.image.*;
import javax.annotation.*;

/**
 * Where map tile images come from. The plugin gets them from packs it downloads and keeps (see {@link PackedTiles}); tests supply
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
	 * True when {@link #load} can read the tile now, without waiting for a download. Cheap, and called for every tile the view waits
	 * for. A source that has everything always says yes.
	 */
	default boolean ready(int mapId, int x, int y)
	{
		return true;
	}

	/**
	 * Says that the view is waiting for a tile that is not {@link #ready}, and how far it is from the middle of the view (squared,
	 * in squares), so the source can fetch what is nearest the middle first. Called again for as long as the view waits. Cheap.
	 */
	default void request(int mapId, int x, int y, double distance)
	{
	}

	/** A short line for the player about what the source is doing (such as downloading), or null when there is nothing to say. */
	@Nullable
	default String status()
	{
		return null;
	}

	/**
	 * Reads and decodes a tile. Blocking, so never called from the render thread. Returns null when
	 * the square has no tile or cannot be decoded.
	 */
	@Nullable
	BufferedImage load(int mapId, int x, int y);

	/**
	 * A picture of a whole map at a few pixels per square, for when the view is zoomed so far out
	 * that individual tiles would be wasted work. Null when the map has none. Blocking, so never called from the render thread.
	 */
	@Nullable
	default BufferedImage overview(int mapId)
	{
		return null;
	}

	/**
	 * Lets go of whatever the source keeps in memory to make reads faster. It stays usable and reads again when asked.
	 * Called when the plugin shuts down.
	 */
	default void release()
	{
	}

	/** Stops any download under way. Called when the plugin shuts down. The source stays usable and asks again when needed. */
	default void cancel()
	{
	}
}
