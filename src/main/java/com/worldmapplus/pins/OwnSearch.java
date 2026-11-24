package com.worldmapplus.pins;

import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The search entries for the player's own saved places and routes (round 48). A saved place is a {@link SearchKind#PIN} at the
 * place; a route is a {@link SearchKind#ROUTE} that says how many stops it has, placed at its first stop that still exists so the
 * filters by distance and place have something to measure. Each entry carries the id of the pin or route it stands for.
 * <p>
 * Made only when the stores' versions change, never while typing, and bounded by the stores' own limits
 * ({@link PinStore#MAX_PINS}, {@link RouteStore#MAX_ROUTES}).
 */
public final class OwnSearch
{
	private OwnSearch()
	{
	}

	/** The entries, pins first, in the stores' order. A route with no stop left has no place: map -1. */
	public static List<SearchEntry> entries(List<Pin> pins, List<SavedRoute> routes, Function<Pin, String> pinDetail)
	{
		List<SearchEntry> entries = new ArrayList<>(pins.size() + routes.size());
		Map<Long, Pin> byId = new HashMap<>();
		for (Pin pin : pins)
		{
			byId.put(pin.getId(), pin);
			entries.add(SearchEntry.own(SearchKind.PIN, pin.getId(), pin.getName(), pinDetail.apply(pin), pin.getMap(), pin.getX(),
				pin.getY()));
		}
		for (SavedRoute route : routes)
		{
			Pin first = null;
			for (long stop : route.getStops())
			{
				first = byId.get(stop);
				if (first != null)
				{
					break;
				}
			}
			entries.add(SearchEntry.own(SearchKind.ROUTE, route.getId(), route.getName(), detail(route),
				first == null ? -1 : first.getMap(), first == null ? 0 : first.getX(), first == null ? 0 : first.getY()));
		}
		return entries;
	}

	/** What the results list says of a route: its stop count, and that it loops. */
	public static String detail(SavedRoute route)
	{
		int count = route.getStops().size();
		return "Your route, " + count + (count == 1 ? " stop" : " stops") + (route.isLoop() ? ", loops" : "");
	}
}
