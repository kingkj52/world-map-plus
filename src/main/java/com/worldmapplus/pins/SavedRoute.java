package com.worldmapplus.pins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A route the player made out of saved places: an ordered list of stops that Shortest Path is asked to
 * walk to one after another, once or round and round. Held and saved on the player's own machine only.
 * <p>
 * Gson fills the fields directly. Whether the route is being followed right now is not saved, so a
 * restart never starts walking anywhere.
 */
public final class SavedRoute
{
	private long id;
	private String name;
	private List<Long> stops;
	private boolean loop;

	public SavedRoute()
	{
	}

	public SavedRoute(long id, String name, List<Long> stops, boolean loop)
	{
		this.id = id;
		this.name = name;
		this.stops = new ArrayList<>(stops);
		this.loop = loop;
	}

	public long getId()
	{
		return id;
	}

	public String getName()
	{
		return name == null ? "" : name;
	}

	/** The ids of the saved places to go to, in order. */
	public List<Long> getStops()
	{
		return stops == null ? Collections.emptyList() : Collections.unmodifiableList(stops);
	}

	/** True when, after the last stop, the route starts again at the first. */
	public boolean isLoop()
	{
		return loop;
	}

	SavedRoute with(String newName, List<Long> newStops, boolean newLoop)
	{
		return new SavedRoute(id, newName, newStops, newLoop);
	}
}
