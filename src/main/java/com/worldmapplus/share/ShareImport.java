package com.worldmapplus.share;

import com.worldmapplus.map.*;
import com.worldmapplus.pins.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;

/**
 * Saves a share the player chose to keep, through the plugin's own pins and routes. It only adds: nothing the player has
 * is changed, a place the player already has at the same tile, floor and map is used again instead of saved twice, a
 * new pin gets the player's own defaults (the name a dropped pin gets, the usual colour and size), and a place that is
 * not on a map this version has is never put on another map.
 */
public final class ShareImport
{
	private final PinStore pins;
	private final RouteStore routes;
	private final Supplier<MapCatalog> catalog;

	public ShareImport(PinStore pins, RouteStore routes, MapCatalog catalog)
	{
		this(pins, routes, () -> catalog);
	}

	/** The catalog is asked for only when it is needed, so the maps need not be loaded before then. */
	public ShareImport(PinStore pins, RouteStore routes, Supplier<MapCatalog> catalog)
	{
		this.pins = pins;
		this.routes = routes;
		this.catalog = catalog;
	}

	/** The id of the map this version shows a tile on, or {@link SharePoint#AUTO_MAP} when none has it. */
	public int autoMap(int x, int y)
	{
		MapCatalog maps = catalog.get();
		MapInfo map = maps == null ? null : maps.mapFor(x, y);
		return map == null ? SharePoint.AUTO_MAP : map.getId();
	}

	/** The map a shared point goes on here, or null when this version has no map with that tile. */
	@Nullable
	public MapInfo mapOf(SharePoint point)
	{
		MapCatalog maps = catalog.get();
		if (maps == null)
		{
			return null;
		}
		if (!point.hasMap())
		{
			return maps.mapFor(point.getX(), point.getY());
		}
		MapInfo map = maps.byId(point.getMap());
		return map != null && map.contains(point.getX(), point.getY()) ? map : null;
	}

	/**
	 * A name for a shared point that is known for sure, for the words shown in chat: the name of a place the player has
	 * saved at that very tile, floor and map, else the name of the map when it is not the surface, else null. Never a
	 * guess from what is near.
	 */
	@Nullable
	public String knownName(SharePoint point)
	{
		MapInfo map = mapOf(point);
		if (map == null)
		{
			return null;
		}
		Pin pin = existing(point, map.getId());
		// A pin that still has the name a dropped or saved pin gets says no more than the coordinates.
		if (pin != null && !pin.getName().startsWith("Pin at ") && !"Pin".equals(pin.getName()))
		{
			return pin.getName();
		}
		return map.getId() == MapInfo.SURFACE ? null : map.getName();
	}

	/** The saved place at a point's tile, floor and map, or null. */
	@Nullable
	public Pin existing(SharePoint point, int mapId)
	{
		for (Pin pin : pins.all())
		{
			if (pin.getMap() == mapId && pin.getPlane() == point.getPlane() && (int) Math.floor(pin.getX()) == point.getX()
				&& (int) Math.floor(pin.getY()) == point.getY())
			{
				return pin;
			}
		}
		return null;
	}

	/** Saves the places of a pin or a set of places. Returns the sentence to tell the player. */
	public String savePlaces(Share share)
	{
		int added = 0;
		int had = 0;
		int skipped = 0;
		boolean full = false;
		for (SharePoint point : share.getPoints())
		{
			MapInfo map = mapOf(point);
			if (map == null)
			{
				skipped++;
				continue;
			}
			if (existing(point, map.getId()) != null)
			{
				had++;
				continue;
			}
			if (add(point, map.getId()) == null)
			{
				full = true;
				break;
			}
			added++;
		}
		StringBuilder text = new StringBuilder();
		text.append(added == 0 ? "Nothing new was saved." : "Saved " + added + (added == 1 ? " place." : " places."));
		if (had > 0)
		{
			text.append(' ').append(had).append(had == 1 ? " was" : " were").append(" already in your places.");
		}
		if (skipped > 0)
		{
			text.append(' ').append(skipped).append(skipped == 1 ? " is" : " are").append(" not on a map this version has.");
		}
		if (full)
		{
			text.append(" Your list of places is full.");
		}
		return text.toString();
	}

	/**
	 * Saves a route: a place for every stop (one the player has at the same spot is used again), then the route over
	 * them, in the shared order. Nothing is saved unless all of it fits. Returns the sentence to tell the player.
	 */
	public String saveRoute(Share share, String name)
	{
		List<SharePoint> stops = share.getPoints();
		if (stops.size() > RouteStore.MAX_STOPS)
		{
			return "A route holds at most " + RouteStore.MAX_STOPS + " stops. This one has " + stops.size() + ".";
		}
		if (routes.all().size() >= RouteStore.MAX_ROUTES)
		{
			return "Your list of routes is full.";
		}
		Map<SharePoint, Integer> mapIds = new LinkedHashMap<>();
		int missing = 0;
		for (int i = 0; i < stops.size(); i++)
		{
			MapInfo map = mapOf(stops.get(i));
			if (map == null)
			{
				return "Stop " + (i + 1) + " (" + stops.get(i).describe() + ") is not on a map this version has, so the route was not saved.";
			}
			if (!mapIds.containsKey(stops.get(i)))
			{
				mapIds.put(stops.get(i), map.getId());
				missing += existing(stops.get(i), map.getId()) == null ? 1 : 0;
			}
		}
		if (pins.all().size() + missing > PinStore.MAX_PINS)
		{
			return "The route needs " + missing + " new places and your list of places is too full.";
		}
		Map<SharePoint, Long> ids = new LinkedHashMap<>();
		for (Map.Entry<SharePoint, Integer> entry : mapIds.entrySet())
		{
			Pin pin = existing(entry.getKey(), entry.getValue());
			if (pin == null)
			{
				pin = add(entry.getKey(), entry.getValue());
			}
			if (pin == null)
			{
				return "Your list of places is full.";
			}
			ids.put(entry.getKey(), pin.getId());
		}
		List<Long> order = new ArrayList<>();
		for (SharePoint stop : stops)
		{
			order.add(ids.get(stop));
		}
		String title = name == null || name.trim().isEmpty() ? "Shared route" : name.trim();
		if (routes.create(title, order, share.loops()) == null)
		{
			return "Your list of routes is full.";
		}
		return "Saved the route " + title + " with " + stops.size() + (stops.size() == 1 ? " stop" : " stops")
			+ (missing > 0 ? " and " + missing + " new " + (missing == 1 ? "place." : "places.") : ".");
	}

	@Nullable
	private Pin add(SharePoint point, int mapId)
	{
		return pins.add("Pin at " + point.getX() + ", " + point.getY(), "pin", mapId, point.getX() + 0.5, point.getY() + 0.5,
			point.getPlane());
	}
}
