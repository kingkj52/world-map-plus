package com.worldmapplus.pins;

import com.google.gson.*;
import java.util.*;
import javax.annotation.*;
import javax.inject.*;
import net.runelite.client.config.*;

/**
 * The player's routes, kept in memory and saved to the plugin's settings as one JSON value (see {@link Saved}).
 * <p>
 * Which route is being followed, and which is being looked at, are kept in memory only. The lists are
 * capped so a damaged or hand-edited setting cannot make the plugin hold an unreasonable amount.
 */
@Singleton
public class RouteStore extends Saved<SavedRoute>
{
	static final String KEY = "routes";
	public static final int MAX_ROUTES = 50;
	public static final int MAX_STOPS = 40;

	private long activeId;
	private long highlightedId;

	@Inject
	public RouteStore(ConfigManager configManager, Gson gson, PinStore pins)
	{
		this(PinStore.Storage.settings(configManager, KEY), gson, pins);
	}

	public RouteStore(PinStore.Storage storage, Gson gson, PinStore pins)
	{
		super(storage, gson, KEY, SavedRoute[].class, MAX_ROUTES);
		// A place that is removed leaves every route that went through it.
		pins.onRemoved(this::forget);
	}

	@Override
	long idOf(SavedRoute route)
	{
		return route.getId();
	}

	@Override
	SavedRoute kept(SavedRoute route)
	{
		return new SavedRoute(route.getId(), clean(route.getName(), "Route"), capped(route.getStops()), route.isLoop());
	}

	/** Adds a route and saves. Returns it, or null when the list is full. */
	@Nullable
	public synchronized SavedRoute create(String name, List<Long> stops, boolean loop)
	{
		long id = newId();
		if (id == 0)
		{
			return null;
		}
		SavedRoute route = new SavedRoute(id, clean(name, "Route"), capped(stops), loop);
		items.add(route);
		changed();
		return route;
	}

	/** Changes a route's name, stops and loop setting at once. Returns false when there is no such route. */
	public synchronized boolean update(long id, String name, List<Long> stops, boolean loop)
	{
		return replace(id, route -> route.with(clean(name, "Route"), capped(stops), loop));
	}

	public synchronized boolean remove(long id)
	{
		ensureLoaded();
		boolean removed = items.removeIf(route -> route.getId() == id);
		if (removed)
		{
			if (activeId == id)
			{
				activeId = 0;
			}
			if (highlightedId == id)
			{
				highlightedId = 0;
			}
			changed();
		}
		return removed;
	}

	/** Takes a removed place out of every route. */
	synchronized void forget(long pinId)
	{
		ensureLoaded();
		boolean any = false;
		for (int i = 0; i < items.size(); i++)
		{
			SavedRoute route = items.get(i);
			if (route.getStops().contains(pinId))
			{
				List<Long> kept = new ArrayList<>(route.getStops());
				kept.removeIf(stop -> stop == pinId);
				items.set(i, route.with(route.getName(), kept, route.isLoop()));
				any = true;
			}
		}
		if (any)
		{
			changed();
		}
	}

	/** Starts following a route, and stops any other. Zero stops all. Not saved: a restart never starts walking anywhere. */
	public synchronized void setActive(long id)
	{
		if (activeId != id)
		{
			activeId = id;
			version++;
		}
	}

	public synchronized void stopAll()
	{
		setActive(0);
	}

	/** The route being followed, or null. */
	@Nullable
	public synchronized SavedRoute active()
	{
		return activeId == 0 ? null : get(activeId);
	}

	/** Marks a route to be drawn on the map while the player looks at it in the side panel. Zero for none. */
	public synchronized void setHighlighted(long id)
	{
		// Not a change a list needs to be rebuilt for, and a rebuild under the mouse would flicker.
		highlightedId = id;
	}

	/** The routes to draw: the one being followed and the one being looked at. */
	public synchronized List<SavedRoute> drawn()
	{
		List<SavedRoute> result = new ArrayList<>(2);
		SavedRoute following = active();
		SavedRoute looking = highlightedId == 0 ? null : get(highlightedId);
		if (following != null)
		{
			result.add(following);
		}
		if (looking != null && looking != following)
		{
			result.add(looking);
		}
		return result;
	}

	private static List<Long> capped(List<Long> stops)
	{
		List<Long> result = new ArrayList<>();
		for (Long stop : stops)
		{
			if (stop != null && result.size() < MAX_STOPS)
			{
				result.add(stop);
			}
		}
		return result;
	}
}
