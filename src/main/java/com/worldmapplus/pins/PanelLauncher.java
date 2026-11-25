package com.worldmapplus.pins;

import java.util.function.*;
import javax.inject.*;

/**
 * How the map asks for the side panel to open on a saved place. The plugin gives it the way to do so
 * when it starts; until then, and in tests, asking does nothing.
 */
@Singleton
public class PanelLauncher
{
	private volatile LongConsumer opener;

	public void setOpener(LongConsumer opener)
	{
		this.opener = opener;
	}

	/** Opens the side panel on a saved place. */
	public void open(long pinId)
	{
		LongConsumer current = opener;
		if (current != null)
		{
			current.accept(pinId);
		}
	}

	private volatile LongConsumer routeEditor;

	/** The way to open the side panel's editor of a saved route (round 48); null takes it away. */
	public void setRouteEditor(LongConsumer routeEditor)
	{
		this.routeEditor = routeEditor;
	}

	/** Opens the side panel's editor of a saved route, as its Edit button does. */
	public void editRoute(long routeId)
	{
		LongConsumer current = routeEditor;
		if (current != null)
		{
			current.accept(routeId);
		}
	}
}
