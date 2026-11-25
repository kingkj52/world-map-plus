package com.worldmapplus.game;

import javax.annotation.*;
import javax.inject.*;
import net.runelite.api.*;

/**
 * Reads the local player's position every frame, rather than once per game tick.
 * <p>
 * The player's local location moves smoothly while they walk, so a marker drawn from it glides
 * instead of hopping a tile at a time. That is the whole reason the viewer reads it itself.
 * <p>
 * Client thread only.
 */
@Singleton
public class PlayerLocator
{
	private final Client client;

	@Inject
	public PlayerLocator(Client client)
	{
		this.client = client;
	}

	private volatile PlayerPosition latest;

	/** Keeps the position read on a game tick, so another thread can use it. Called on the client thread. */
	public void remember(@Nullable PlayerPosition position)
	{
		latest = position;
	}

	/** The position as of the last game tick, or null. Safe to read from any thread. */
	@Nullable
	public PlayerPosition latest()
	{
		return latest;
	}

	/** The local player's position, or null when not logged in. */
	@Nullable
	public PlayerPosition get()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		return Positions.of(client, client.getLocalPlayer());
	}
}
