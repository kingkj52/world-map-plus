package com.worldmapplus.game;

import net.runelite.api.coords.WorldPoint;

/**
 * Where the local player is, to a fraction of a tile.
 * <p>
 * {@code x} and {@code y} are continuous world tile coordinates, so a player standing in the
 * middle of tile 3200,3200 is at 3200.5,3200.5 and one walking between two tiles is somewhere in
 * between. Inside an instance they are the coordinates of the area the instance was copied from,
 * which is where the map should draw them.
 */
public final class PlayerPosition
{
	private final double x;
	private final double y;
	private final WorldPoint tile;

	public PlayerPosition(double x, double y, WorldPoint tile)
	{
		this.x = x;
		this.y = y;
		this.tile = tile;
	}

	public double getX()
	{
		return x;
	}

	public double getY()
	{
		return y;
	}

	/** The whole tile the player is on. */
	public WorldPoint getTile()
	{
		return tile;
	}
}
