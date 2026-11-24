package com.worldmapplus.pins;

import com.worldmapplus.bridge.MapActions;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Lets a saved route ask Shortest Path for each leg, through the same {@link MapActions} the map's own
 * right-click route uses. Nothing here reaches the game.
 */
@Singleton
public class RouteNavigator implements RouteRunner.Navigator
{
	private final MapActions actions;

	@Inject
	public RouteNavigator(MapActions actions)
	{
		this.actions = actions;
	}

	@Override
	public boolean routeTo(Pin stop)
	{
		MapActions.Status status = actions.routeTo(pointOf(stop));
		return status != MapActions.Status.MISSING && status != MapActions.Status.DISABLED;
	}

	@Override
	public void clear()
	{
		actions.clearRoute();
	}

	@Override
	public int othersRequests()
	{
		return actions.othersRequests();
	}

	/** The tile a saved place stands on. */
	public static WorldPoint pointOf(Pin pin)
	{
		return new WorldPoint((int) Math.floor(pin.getX()), (int) Math.floor(pin.getY()), pin.getPlane());
	}
}
