package com.worldmapplus.ui;

import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.bridge.NativePoints;
import com.worldmapplus.compat.MapCensus;
import com.worldmapplus.farming.FarmingTracker;
import com.worldmapplus.input.ChatSearch;
import com.worldmapplus.game.TrailTracker;
import com.worldmapplus.layers.NpcLayer;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.google.gson.Gson;
import javax.inject.Inject;
import javax.inject.Singleton;

/** The shared pieces a map surface works with, gathered so a surface takes one argument for them. */
@Singleton
public class SurfaceServices
{
	private final NpcLayer npcLayer;
	private final ViewMemory memory;
	private final PinStore pins;
	private final ExternalOverlays external;
	private final MapActions actions;
	private final Settings settings;
	private final TrailTracker trail;
	private final FarmingTracker farming;
	private final NativePoints nativePoints;
	private final RouteStore routes;
	private final PanelLauncher launcher;
	private final MapCensus census;
	private final ChatSearch chatSearch;

	/** For tests and previews, which have no farming tracker. The Gson is for the routes they keep in memory. */
	public SurfaceServices(NpcLayer npcLayer, ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, TrailTracker trail, Gson gson)
	{
		this(npcLayer, memory, pins, external, actions, settings, trail, (FarmingTracker) null, gson);
	}

	/** For tests and previews, which have no points from other plugins. */
	public SurfaceServices(NpcLayer npcLayer, ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, TrailTracker trail, FarmingTracker farming, Gson gson)
	{
		this(npcLayer, memory, pins, external, actions, settings, trail, farming, (NativePoints) null, gson);
	}

	/** For tests and previews, which keep saved routes in memory and have no side panel. */
	public SurfaceServices(NpcLayer npcLayer, ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, TrailTracker trail, FarmingTracker farming,
		NativePoints nativePoints, Gson gson)
	{
		this(npcLayer, memory, pins, external, actions, settings, trail, farming, nativePoints,
			new RouteStore(PinStore.Storage.inMemory(), gson, pins), new PanelLauncher());
	}

	/** For tests and previews, which have no census of the plugins on the game's map. */
	public SurfaceServices(NpcLayer npcLayer, ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, TrailTracker trail, FarmingTracker farming,
		NativePoints nativePoints, RouteStore routes, PanelLauncher launcher)
	{
		this(npcLayer, memory, pins, external, actions, settings, trail, farming, nativePoints, routes, launcher, null);
	}

	/** For tests and previews, which have no chat area to type the search into. */
	public SurfaceServices(NpcLayer npcLayer, ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, TrailTracker trail, FarmingTracker farming,
		NativePoints nativePoints, RouteStore routes, PanelLauncher launcher, MapCensus census)
	{
		this(npcLayer, memory, pins, external, actions, settings, trail, farming, nativePoints, routes, launcher, census,
			null);
	}

	@Inject
	public SurfaceServices(NpcLayer npcLayer, ViewMemory memory, PinStore pins, ExternalOverlays external,
		MapActions actions, Settings settings, TrailTracker trail, FarmingTracker farming,
		NativePoints nativePoints, RouteStore routes, PanelLauncher launcher, MapCensus census, ChatSearch chatSearch)
	{
		this.chatSearch = chatSearch;
		this.census = census;
		this.routes = routes;
		this.launcher = launcher;
		this.farming = farming;
		this.nativePoints = nativePoints;
		this.npcLayer = npcLayer;
		this.memory = memory;
		this.pins = pins;
		this.external = external;
		this.actions = actions;
		this.settings = settings;
		this.trail = trail;
	}

	/** Types the search into the chat area, or null when that is not offered, as in a window of its own. */
	public ChatSearch chatSearch()
	{
		return chatSearch;
	}

	/** Which other plugins have something on the game's map, or null when that is not looked into. */
	public MapCensus census()
	{
		return census;
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

	public NpcLayer npcLayer()
	{
		return npcLayer;
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

	public TrailTracker trail()
	{
		return trail;
	}

	/** The points other plugins put on the game's own map, or null when they are not read. */
	public NativePoints nativePoints()
	{
		return nativePoints;
	}

	/** What was last seen in the farming patches, or null when there is no tracker. */
	public FarmingTracker farming()
	{
		return farming;
	}
}
