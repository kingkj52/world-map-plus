package com.worldmapplus.pins;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Walks a saved route, one stop after another, by asking Shortest Path for each leg in turn.
 * <p>
 * It never moves the player. It asks for a route to the next stop, waits until the player is close to
 * that stop, and then asks for the one after. At the end it either starts again, for a loop, or stops.
 * If another plugin asks Shortest Path for a route of its own, this stops at once and leaves the other
 * route alone.
 * <p>
 * Called once a game tick, from the client thread.
 */
@Singleton
public final class RouteRunner
{
	/** How close, in tiles, counts as having arrived at a stop. */
	public static final int ARRIVED_TILES = 4;

	/** How the runner asks for a leg and learns whether someone else has taken over. */
	public interface Navigator
	{
		/** Asks for a route to a stop. Returns false when it could not be asked, for example when Shortest Path is off. */
		boolean routeTo(Pin stop);

		void clear();

		/** A number that goes up whenever a plugin other than this one asks Shortest Path for something. */
		int othersRequests();
	}

	private final RouteStore routes;
	private final PinStore pins;
	private final Navigator navigator;

	private long followingId;
	private int index;
	private boolean asked;
	private long targetId;
	private String problem;
	private int seenOthers;

	@Inject
	public RouteRunner(RouteStore routes, PinStore pins, RouteNavigator navigator)
	{
		this(routes, pins, (Navigator) navigator);
	}

	public RouteRunner(RouteStore routes, PinStore pins, Navigator navigator)
	{
		this.routes = routes;
		this.pins = pins;
		this.navigator = navigator;
	}

	/** Why a route was stopped by the runner itself, once, or null. For the side panel to say. */
	@Nullable
	public synchronized String takeProblem()
	{
		String text = problem;
		problem = null;
		return text;
	}

	/** The stop being walked to, counting from zero, or -1 when no route is being followed. */
	public int getIndex()
	{
		return followingId == 0 ? -1 : index;
	}

	/**
	 * Once a game tick.
	 *
	 * @param playerX where the player is, or NaN when not known
	 */
	public void tick(double playerX, double playerY)
	{
		tick(playerX, playerY, -1);
	}

	/**
	 * Once a game tick.
	 *
	 * @param playerPlane the floor the player is on, or -1 when it does not matter
	 */
	public void tick(double playerX, double playerY, int playerPlane)
	{
		SavedRoute route = routes.active();
		if (route == null)
		{
			followingId = 0;
			return;
		}
		List<Pin> stops = stopsOf(route);
		if (stops.isEmpty())
		{
			routes.stopAll();
			followingId = 0;
			return;
		}
		if (route.getId() != followingId)
		{
			followingId = route.getId();
			index = 0;
			asked = false;
			seenOthers = navigator.othersRequests();
		}
		if (navigator.othersRequests() != seenOthers)
		{
			// Another plugin has a route going. It is not this plugin's to override.
			routes.stopAll();
			followingId = 0;
			return;
		}
		if (asked && (index >= stops.size() || stops.get(index).getId() != targetId))
		{
			// The stops changed under the route: a place was removed or the route was edited. Find the
			// stop that was being walked to, or ask again for whatever now stands at this position.
			int moved = indexOfStop(stops, targetId);
			if (moved >= 0)
			{
				index = moved;
			}
			else
			{
				asked = false;
			}
		}
		if (index >= stops.size())
		{
			if (!route.isLoop())
			{
				routes.stopAll();
				followingId = 0;
				navigator.clear();
				return;
			}
			index = 0;
		}
		Pin target = stops.get(index);
		if (!asked)
		{
			if (!navigator.routeTo(target))
			{
				synchronized (this)
				{
					problem = "Shortest Path could not be asked, so " + route.getName() + " was stopped.";
				}
				routes.stopAll();
				followingId = 0;
				return;
			}
			asked = true;
			targetId = target.getId();
			return;
		}
		if (Double.isNaN(playerX) || Double.isNaN(playerY))
		{
			return;
		}
		boolean sameFloor = playerPlane < 0 || playerPlane == target.getPlane();
		if (sameFloor && Math.max(Math.abs(playerX - target.getX()), Math.abs(playerY - target.getY())) <= ARRIVED_TILES)
		{
			index++;
			asked = false;
			if (index >= stops.size())
			{
				if (route.isLoop() && stops.size() > 0)
				{
					index = 0;
				}
				else
				{
					routes.stopAll();
					followingId = 0;
					navigator.clear();
				}
			}
		}
	}

	private static int indexOfStop(List<Pin> stops, long id)
	{
		for (int i = 0; i < stops.size(); i++)
		{
			if (stops.get(i).getId() == id)
			{
				return i;
			}
		}
		return -1;
	}

	/** Moves on to the next stop at once, for a stop the player cannot or does not want to reach. */
	public void skip()
	{
		if (followingId != 0)
		{
			index++;
			asked = false;
		}
	}

	/** The saved places of a route that still exist, in order. */
	List<Pin> stopsOf(SavedRoute route)
	{
		List<Pin> stops = new ArrayList<>();
		for (long id : route.getStops())
		{
			Pin pin = pins.get(id);
			if (pin != null)
			{
				stops.add(pin);
			}
		}
		return stops;
	}

	/** The stops of a route, for drawing. */
	public List<Pin> stops(@Nullable SavedRoute route)
	{
		return route == null ? new ArrayList<>() : stopsOf(route);
	}
}
