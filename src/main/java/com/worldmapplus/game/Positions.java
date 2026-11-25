package com.worldmapplus.game;

import javax.annotation.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;

/**
 * Turns an actor's local location into a fractional world position.
 * <p>
 * An actor's local location moves smoothly while it walks, so a marker drawn from it glides rather
 * than hopping a tile at a time. Tile and fraction both come from the same local point so they can
 * never disagree for a frame.
 */
public final class Positions
{
	/** Local coordinates are 128 units to a tile. */
	static final int UNITS_PER_TILE = 128;

	/** The floor boats sail on. */
	static final int SEA_FLOOR = 0;

	private Positions()
	{
	}

	/** The fraction of a tile a local coordinate is into its tile, 0.5 being the centre. */
	public static double fraction(int localCoordinate)
	{
		return (localCoordinate & (UNITS_PER_TILE - 1)) / (double) UNITS_PER_TILE;
	}

	/** The actor's position, or null if it has none. Client thread only. */
	@Nullable
	public static PlayerPosition of(Client client, @Nullable Actor actor)
	{
		if (actor == null)
		{
			return null;
		}

		WorldView view = actor.getWorldView();
		LocalPoint local = actor.getLocalLocation();
		if (local != null && (view == null || view.isTopLevel()))
		{
			WorldPoint tile = WorldPoint.fromLocalInstance(client, local);
			if (tile != null)
			{
				return new PlayerPosition(tile.getX() + fraction(local.getX()), tile.getY() + fraction(local.getY()), tile);
			}
		}

		// On a boat the local point is in the boat's own scene, whose coordinates mean nothing on the map. The boat is an
		// entity of the main world, which says where a point on it is in the main world: the same turn Perspective and
		// Quest Helper make.
		if (local != null && view != null && !view.isTopLevel())
		{
			PlayerPosition aboard = onBoat(client, view, local);
			if (aboard != null)
			{
				return aboard;
			}
		}

		// Anywhere else the local point is not in the main scene: the tile centre.
		WorldPoint tile = actor.getWorldLocation();
		if (tile == null)
		{
			return null;
		}
		if (view != null && !view.isTopLevel() && tile.getPlane() != SEA_FLOOR)
		{
			// Still aboard a boat, only one the client cannot place: the floor is the sea's all the same.
			tile = new WorldPoint(tile.getX(), tile.getY(), SEA_FLOOR);
		}
		return new PlayerPosition(tile.getX() + 0.5, tile.getY() + 0.5, tile);
	}

	/** Where a point on a boat is in the main world, or null when the boat is not known. Client thread only. */
	@Nullable
	static PlayerPosition onBoat(Client client, WorldView boat, LocalPoint local)
	{
		WorldView main = client.getTopLevelWorldView();
		if (main == null)
		{
			return null;
		}
		WorldEntity entity = main.worldEntities().byIndex(boat.getId());
		if (entity == null)
		{
			return null;
		}
		LocalPoint there = entity.transformToMainWorld(local);
		if (there == null)
		{
			return null;
		}
		// The boat's own scene puts its deck on floor 1, but the boat sails on the sea, which is the ground floor of the main
		// world. Using the deck's floor would open the map one floor up.
		WorldPoint tile = WorldPoint.fromLocalInstance(client, there, SEA_FLOOR);
		if (tile == null)
		{
			return null;
		}
		return new PlayerPosition(tile.getX() + fraction(there.getX()), tile.getY() + fraction(there.getY()), tile);
	}
}
