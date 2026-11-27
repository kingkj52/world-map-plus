package com.worldmapplus.ui;

import com.google.gson.*;
import com.worldmapplus.bridge.*;

import com.worldmapplus.game.*;
import com.worldmapplus.input.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.pins.*;
import com.worldmapplus.prefs.*;
import javax.inject.*;

/** The shared pieces a map surface works with, gathered so a surface takes one argument for them. */
@Singleton
public class SurfaceServices
{
	private final ViewMemory memory;
	private final PinStore pins;
	private final ExternalOverlays external;
	private final MapActions actions;
	private final Settings settings;
	private final NativePoints nativePoints;
	private final RouteStore routes;
	private final PanelLauncher launcher;
	private final ChatSearch chatSearch;

	/** For tests and previews, which have no points from other plugins. The Gson is for the routes they keep in memory. */
	public SurfaceServices(ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, Gson gson)
	{
		this(memory, pins, external, actions, settings, (NativePoints) null, gson);
	}

	/** For tests and previews, which keep saved routes in memory and have no side panel. */
	public SurfaceServices(ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, NativePoints nativePoints, Gson gson)
	{
		this(memory, pins, external, actions, settings, nativePoints,
			new RouteStore(PinStore.Storage.inMemory(), gson, pins), new PanelLauncher());
	}

	/** For tests and previews, which have no chat area to type the search into. */
	public SurfaceServices(ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, NativePoints nativePoints, RouteStore routes, PanelLauncher launcher)
	{
		this(memory, pins, external, actions, settings, nativePoints, routes, launcher, null);
	}

	@Inject
	public SurfaceServices(ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, NativePoints nativePoints, RouteStore routes, PanelLauncher launcher, ChatSearch chatSearch)
	{
		this.chatSearch = chatSearch;
		this.routes = routes;
		this.launcher = launcher;
		this.nativePoints = nativePoints;
		this.memory = memory;
		this.pins = pins;
		this.external = external;
		this.actions = actions;
		this.settings = settings;
	}

	/** Types the search into the chat area, or null when that is not offered, as in a window of its own. */
	public ChatSearch chatSearch()
	{
		return chatSearch;
	}


	/** The saved routes, for drawing the ones being followed or looked at. */
	public RouteStore routes()
	{
		return routes;
	}

	/** Opens the side panel on a saved place. */
	public PanelLauncher launcher()
	{
		return launcher;
	}

	public ViewMemory memory()
	{
		return memory;
	}

	public PinStore pins()
	{
		return pins;
	}

	public ExternalOverlays external()
	{
		return external;
	}

	public MapActions actions()
	{
		return actions;
	}

	public Settings settings()
	{
		return settings;
	}

	/** The points other plugins put on the game's own map, or null when they are not read. */
	public NativePoints nativePoints()
	{
		return nativePoints;
	}
}
