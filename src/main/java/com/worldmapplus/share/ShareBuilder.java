package com.worldmapplus.share;

import com.worldmapplus.pins.*;
import java.util.*;
import java.util.function.*;

/**
 * Makes a {@link Share} from the player's own saved places and routes. Only the tile, the floor and, when the tile alone
 * does not give it, the map are taken: never the name, the kind, the colour or the size.
 */
public final class ShareBuilder
{
	private ShareBuilder()
	{
	}

	/**
	 * The shared form of a saved place. {@code autoMap} gives the map this version shows a tile on; the place's own map is
	 * sent only when it differs.
	 */
	public static SharePoint pointOf(Pin pin, IntBinaryOperator autoMap)
	{
		int x = (int) Math.floor(pin.getX());
		int y = (int) Math.floor(pin.getY());
		int map = autoMap.applyAsInt(x, y) == pin.getMap() ? SharePoint.AUTO_MAP : pin.getMap();
		return new SharePoint(x, y, Math.max(0, Math.min(3, pin.getPlane())), map);
	}

	/** One place as a pin code, several as a set of places, in the order given. Ids of deleted places are left out. */
	public static Share ofIds(List<Long> ids, PinStore pins, IntBinaryOperator autoMap)
	{
		List<SharePoint> points = new ArrayList<>();
		for (long id : ids)
		{
			Pin pin = pins.get(id);
			if (pin != null)
			{
				points.add(pointOf(pin, autoMap));
			}
		}
		return points.size() == 1 ? Share.pin(points.get(0)) : Share.places(points);
	}

	/** A route's stops in order, and whether it loops. A stop whose place has since been deleted is left out. */
	public static Share ofRoute(SavedRoute route, PinStore pins, IntBinaryOperator autoMap)
	{
		List<SharePoint> points = new ArrayList<>();
		for (long id : route.getStops())
		{
			Pin pin = pins.get(id);
			if (pin != null)
			{
				points.add(pointOf(pin, autoMap));
			}
		}
		return Share.route(points, route.isLoop());
	}
}
