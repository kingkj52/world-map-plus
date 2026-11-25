package com.worldmapplus.pins;

import com.google.gson.*;
import com.worldmapplus.*;
import java.util.*;
import javax.annotation.*;
import javax.inject.*;
import net.runelite.client.config.*;

/**
 * The player's routes, kept in memory and saved to the plugin's settings as one JSON value.
 * <p>
 * Which route is being followed, and which is being looked at, are kept in memory only. The lists are
 * capped so a damaged or hand-edited setting cannot make the plugin hold an unreasonable amount.
 */
@Singleton
public class RouteStore
{
	/** A saved value longer than this is ignored, so a damaged or hand edited setting costs little to read. */
	static final int MAX_SAVED_CHARS = 1_000_000;

	private static final long MAX_NEXT_ID = 1L << 50;

	static final String KEY = "routes";
	public static final int MAX_ROUTES = 50;
	public static final int MAX_STOPS = 40;
	static final int MAX_NAME = 60;

	private final PinStore.Storage storage;
	private final Gson gson;
	private List<SavedRoute> routes = new ArrayList<>();
	private boolean loaded;
	private int version;
	private long nextId = 1;
	private String lastJson = "";
	private long activeId;
	private long highlightedId;

	@Inject
	public RouteStore(ConfigManager configManager, Gson gson, PinStore pins)
	{
		this(PinStore.Storage.settings(configManager, KEY), gson, pins);
	}

	public RouteStore(PinStore.Storage storage, Gson gson, PinStore pins)
	{
		this.storage = storage;
		this.gson = gson;
		// A place that is removed leaves every route that went through it.
		pins.onRemoved(this::forget);
	}

	/** Increases whenever the routes, or which one is followed, change. Looking at a route does not count. */
	public synchronized int getVersion()
	{
		return version;
	}

	public synchronized List<SavedRoute> all()
	{
		ensureLoaded();
		return Collections.unmodifiableList(new ArrayList<>(routes));
	}

	@Nullable
	public synchronized SavedRoute get(long id)
	{
		ensureLoaded();
		for (SavedRoute route : routes)
		{
			if (route.getId() == id)
			{
				return route;
			}
		}
		return null;
	}

	/** Adds a route and saves. Returns it, or null when the list is full. */
	@Nullable
	public synchronized SavedRoute create(String name, List<Long> stops, boolean loop)
	{
		ensureLoaded();
		if (routes.size() >= MAX_ROUTES)
		{
			return null;
		}
		long id = nextId;
		for (SavedRoute existing : routes)
		{
			id = Math.max(id, existing.getId() + 1);
		}
		nextId = id + 1;
		SavedRoute route = new SavedRoute(id, clean(name), capped(stops), loop);
		routes.add(route);
		changed(true);
		return route;
	}

	/** Changes a route's name, stops and loop setting at once. Returns false when there is no such route. */
	public synchronized boolean update(long id, String name, List<Long> stops, boolean loop)
	{
		ensureLoaded();
		for (int i = 0; i < routes.size(); i++)
		{
			if (routes.get(i).getId() == id)
			{
				routes.set(i, routes.get(i).with(clean(name), capped(stops), loop));
				changed(true);
				return true;
			}
		}
		return false;
	}

	public synchronized boolean remove(long id)
	{
		ensureLoaded();
		boolean removed = routes.removeIf(route -> route.getId() == id);
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
			changed(true);
		}
		return removed;
	}

	/** Takes a removed place out of every route. */
	synchronized void forget(long pinId)
	{
		ensureLoaded();
		boolean any = false;
		for (int i = 0; i < routes.size(); i++)
		{
			SavedRoute route = routes.get(i);
			if (route.getStops().contains(pinId))
			{
				List<Long> kept = new ArrayList<>(route.getStops());
				kept.removeIf(stop -> stop == pinId);
				routes.set(i, route.with(route.getName(), kept, route.isLoop()));
				any = true;
			}
		}
		if (any)
		{
			changed(true);
		}
	}

	/** Starts following a route, and stops any other. Zero stops all. */
	public synchronized void setActive(long id)
	{
		if (activeId != id)
		{
			activeId = id;
			changed(false);
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
		if (highlightedId != id)
		{
			// Not a change a list needs to be rebuilt for, and a rebuild under the mouse would flicker.
			highlightedId = id;
		}
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

	static String clean(String name)
	{
		String text = name == null ? "" : name.replaceAll("[<>]", "").trim().replaceAll("\\s+", " ");
		if (text.isEmpty())
		{
			text = "Route";
		}
		return text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
	}

	private void changed(boolean save)
	{
		version++;
		if (save)
		{
			RouteFile file = new RouteFile();
			file.routes = routes;
			file.nextId = nextId;
			String json = gson.toJson(file);
			lastJson = json;
			storage.write(json);
		}
	}

	private void ensureLoaded()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;
		read(storage.read());
	}

	private void read(@Nullable String json)
	{
		lastJson = json == null ? "" : json;
		RouteFile file = parseFile(gson, json);
		routes = file.routes;
		long highest = 0;
		for (SavedRoute route : routes)
		{
			highest = Math.max(highest, route.getId());
		}
		// A hand edited number near the top of the range is not trusted, so ids can never wrap round.
		nextId = Math.max(Math.min(file.nextId, MAX_NEXT_ID), Math.max(1, highest + 1));
	}

	/**
	 * Takes in the saved value again if something other than this store changed it, such as a switch of
	 * RuneLite profile. Returns true when the list was replaced. Our own writes are recognised and ignored.
	 */
	public synchronized boolean reloadIfChanged()
	{
		if (!loaded)
		{
			return false;
		}
		String now = storage.read();
		if ((now == null ? "" : now).equals(lastJson))
		{
			return false;
		}
		read(now);
		version++;
		return true;
	}

	/** Reads saved routes. Anything unreadable gives an empty list rather than an error. */
	static List<SavedRoute> parse(Gson gson, @Nullable String json)
	{
		return parseFile(gson, json).routes;
	}

	private static RouteFile parseFile(Gson gson, @Nullable String json)
	{
		RouteFile result = new RouteFile();
		result.routes = new ArrayList<>();
		if (json == null || json.isEmpty() || json.length() > MAX_SAVED_CHARS)
		{
			return result;
		}
		try
		{
			RouteFile file = gson.fromJson(json, RouteFile.class);
			if (file == null || file.routes == null)
			{
				return result;
			}
			result.nextId = file.nextId;
			Set<Long> seen = new HashSet<>();
			for (SavedRoute route : file.routes)
			{
				if (route == null || result.routes.size() >= MAX_ROUTES || route.getId() <= 0 || !seen.add(route.getId()))
				{
					continue;
				}
				result.routes.add(new SavedRoute(route.getId(), clean(route.getName()), capped(route.getStops()),
					route.isLoop()));
			}
		}
		catch (JsonParseException e)
		{
			RouteFile empty = new RouteFile();
			empty.routes = new ArrayList<>();
			return empty;
		}
		return result;
	}

	/** The shape of the saved value. Filled by Gson. */
	static final class RouteFile
	{
		List<SavedRoute> routes;
		long nextId;
	}
}
