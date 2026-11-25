package com.worldmapplus.pins;

import com.worldmapplus.bridge.*;
import lombok.*;

/**
 * Starting a saved route, the one way both the side panel's Start button and a route chosen in the search do it (round 48): Shortest
 * Path must be there and on, the route must have a saved place left, and then the route is the one followed, which
 * {@link RouteRunner} turns into one Shortest Path request per stop. Nothing is sent from here.
 */
public final class RouteStart
{
	private RouteStart()
	{
	}

	/** What happened, in words for the player. True in {@link Result#isStarted()} when the route is now followed. */
	public static Result start(SavedRoute route, RouteStore routes, PinStore pins, MapActions.Status shortestPath)
	{
		if (shortestPath == MapActions.Status.MISSING)
		{
			return new Result(false, "Shortest Path is not installed. Add it from the Plugin Hub to get routes.");
		}
		if (shortestPath == MapActions.Status.DISABLED)
		{
			return new Result(false, "Shortest Path is turned off. Switch it on to get routes.");
		}
		boolean any = false;
		for (long stop : route.getStops())
		{
			any |= pins.get(stop) != null;
		}
		if (!any)
		{
			return new Result(false, route.getName() + " has no saved places left. Edit it to add some.");
		}
		routes.setActive(route.getId());
		return new Result(true, "Following " + route.getName() + ". Shortest Path is asked for one stop at a time.");
	}

	/** Whether a route started, and what to tell the player. */
	@Getter
	public static final class Result
	{
		private final boolean started;
		private final String message;

		Result(boolean started, String message)
		{
			this.started = started;
			this.message = message;
		}
	}
}
