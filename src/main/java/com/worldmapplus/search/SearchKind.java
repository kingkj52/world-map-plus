package com.worldmapplus.search;

/** What a search result is. The weight breaks ties between equally good name matches. */
public enum SearchKind
{
	TILE("Tile", 40),
	/** One of the player's own saved places. */
	PIN("Pin", 35),
	/** One of the player's own saved routes. */
	ROUTE("Route", 35),
	PLACE("Place", 30),
	/** Every place of one kind, such as every bank, or those of one town. */
	KIND("Kind", 32),
	QUEST("Quest", 28),
	SHOP("Shop", 25),
	MONSTER("Monster", 22),
	DOORWAY("Entrance", 20),
	NPC("NPC", 15),
	TRANSPORT("Transport", 10),
	TELEPORT("Teleport", 12),
	POINT("Point", 26),
	ITEM("Item", 5),
	MUSIC("Music", 3),
	/** A result another plugin gave, through the plugin API. */
	PARTNER("Plugin", 8);

	private final String label;
	private final int weight;

	SearchKind(String label, int weight)
	{
		this.label = label;
		this.weight = weight;
	}

	public String label()
	{
		return label;
	}

	int weight()
	{
		return weight;
	}

	/** True for the player's own saved places and routes, which rank first only on their exact name. */
	public boolean isOwn()
	{
		return this == PIN || this == ROUTE;
	}

	/**
	 * The tie break a match of this kind gets. The player's own things take their high weight only when the exact name was typed, and
	 * otherwise the least of all, so a few saved places never push the map's own places down the list (round 48).
	 */
	int weight(boolean exactName)
	{
		return isOwn() && !exactName ? 1 : weight;
	}
}
