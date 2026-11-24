package com.worldmapplus.search;

import javax.annotation.Nullable;

/** What a search needs to know besides the text: where the player is, and what the place names mean. */
public final class SearchContext
{
	/** A context that knows nothing, so filters about places and the player match nothing. */
	public static final SearchContext NONE = new SearchContext(false, 0, 0, 0, Gazetteer.empty());

	private final boolean playerKnown;
	private final int playerMap;
	private final double playerX;
	private final double playerY;
	private final Gazetteer gazetteer;

	public SearchContext(boolean playerKnown, int playerMap, double playerX, double playerY, Gazetteer gazetteer)
	{
		this.playerKnown = playerKnown;
		this.playerMap = playerMap;
		this.playerX = playerX;
		this.playerY = playerY;
		this.gazetteer = gazetteer;
	}

	public boolean isPlayerKnown()
	{
		return playerKnown;
	}

	public int getPlayerMap()
	{
		return playerMap;
	}

	public double getPlayerX()
	{
		return playerX;
	}

	public double getPlayerY()
	{
		return playerY;
	}

	public Gazetteer getGazetteer()
	{
		return gazetteer;
	}

	/** Tiles from the player to a result, going diagonally where that helps, or -1 off the player's map or when unknown. */
	public double distanceTo(@Nullable SearchEntry entry)
	{
		if (entry == null || !playerKnown || entry.getMapId() != playerMap)
		{
			return -1;
		}
		return Math.max(Math.abs(entry.getX() - playerX), Math.abs(entry.getY() - playerY));
	}
}
