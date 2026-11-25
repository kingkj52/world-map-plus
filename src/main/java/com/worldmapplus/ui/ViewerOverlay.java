package com.worldmapplus.ui;

import com.worldmapplus.*;
import com.worldmapplus.bridge.*;
import com.worldmapplus.game.*;
import com.worldmapplus.input.*;
import com.worldmapplus.prefs.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.annotation.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.api.worldmap.*;
import net.runelite.client.input.*;
import net.runelite.client.ui.overlay.*;

/**
 * Draws the viewer over the world map window.
 * <p>
 * The game's own world map interface is left exactly as it is. This overlay paints over the map
 * area of it, after the interface has been drawn, and takes the mouse for that area while it is
 * showing. No widget is created, hidden, moved or resized, and no menu entry is added.
 * <p>
 * Everything the map does lives in {@link MapSurface}; this class only decides where it goes and
 * when it shows.
 */
@Singleton
public class ViewerOverlay extends Overlay
{
	private final Client client;
	private final WorldMapPlusConfig config;
	private final PlayerLocator locator;
	private final MapSurface surface;

	/** How long the game's map list may stay open before it is taken to be misread. */
	static final long NATIVE_LIST_PATIENCE_MILLIS = 120_000;

	/**
	 * A plugin that asks this map to centre somewhere may also move the game's map, which would then be
	 * followed a moment later. For this long after an explicit request, the game's map is not followed.
	 */
	static final long EXPLICIT_FOCUS_MILLIS = 2_500;

	private final NativeMapSync nativeSync = new NativeMapSync();
	private NativeMap gameMap;
	private final MouseManager mouseManager;
	private boolean wasDrawn;
	private long listShownSince;

	@Inject
	public ViewerOverlay(Client client, MapAssets assets, WorldMapPlusConfig config, PlayerLocator locator,
		SurfaceServices services, ViewMemory memory, MouseManager mouseManager)
	{
		this.mouseManager = mouseManager;
		this.client = client;
		this.config = config;
		this.locator = locator;
		this.surface = new MapSurface(client, assets, config, services);
		this.gameMap = new NativeMap()
		{
			@Override
			@Nullable
			public View view()
			{
				WorldMap map = client.getWorldMap();
				Point at = map == null ? null : map.getWorldMapPosition();
				return at == null ? null : new View(at.getX(), at.getY(), map.getWorldMapZoom());
			}

			@Override
			public void moveTo(int x, int y)
			{
				WorldMap map = client.getWorldMap();
				if (map != null)
				{
					map.setWorldMapPositionTarget(new WorldPoint(x, y, 0));
				}
			}
		};
		this.surface.setNativeMap(gameMap);

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
		// Last, so the viewer covers anything else drawn over the game's map in this area.
		setPriority(PRIORITY_HIGHEST);
	}

	/** The input handler to register with RuneLite. */
	public ViewerInput getInput()
	{
		return surface.getInput();
	}

	/** The cues of the Sounds setting, or null for none. */
	public void setSounds(Sounds sounds)
	{
		surface.setSounds(sounds);
	}

	/** Where a middle click on a map link, a result or a step of a route opens a map of its own. */
	public void setWindowOpener(MapSurface.WindowOpener opener)
	{
		surface.setWindowOpener(opener);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Rectangle bounds = mapBounds();
		if (bounds == null || !config.replaceWorldMap())
		{
			// Not drawable this frame. While the game rebuilds its window, as it does on a resize, the
			// map lets go of input but keeps its view, and carries on when the frames come back.
			surface.suspend();
			// The game's own map may be somewhere else when the frames come back.
			nativeSync.reset();
			wasDrawn = false;
			return null;
		}
		if (!wasDrawn)
		{
			wasDrawn = true;
			lastInThePluginsQueue();
		}

		// The game's own map list opens inside the map area. While it is open the game's map shows, and
		// afterwards this map follows to wherever it was sent.
		NativeMapSync.Spot moved = nativeSync.update(nativeListOpen(), nativePosition(),
			config.followOtherPlugins() && !surface.isLinedUp()
				&& !surface.focusedWithin(System.currentTimeMillis(), EXPLICIT_FOCUS_MILLIS));
		if (nativeSync.isOpen())
		{
			surface.suspend();
			return null;
		}
		if (moved != null)
		{
			surface.focusOn(moved.getX(), moved.getY());
		}
		NativeMapSync.Spot picked = nativeSync.takeListSelection();
		if (picked != null && moved == null && !surface.showsTile(picked.getX(), picked.getY()))
		{
			// A map picked in the list that the game's map was already on, from a map of this plugin's the game has no place for.
			surface.focusOn(picked.getX(), picked.getY());
		}

		PlayerPosition position = locator.get();
		MapSurface.Options options = new MapSurface.Options()
			.memorySlot(ViewMemory.WORLD_MAP)
			.followOnOpen(config.followPlayer())
			.startAtLastView(config.startAt() == WorldMapPlusConfig.StartAt.LAST_VIEW)
			.resume(true)
			.keepClear(gameControls(bounds));
		surface.render(g, bounds, position, options, System.currentTimeMillis());
		// Plugins that put things on the game's map only near where it looks need it kept with this map.
		NativeMapSync.Spot send = nativeSync.drive(config.syncGameMap() && !surface.isLinedUp() && surface.wantsGameMap(),
			nativePosition(), surface.centerX(), surface.centerY());
		if (send != null)
		{
			gameMap.moveTo(send.getX(), send.getY());
		}
		return null;
	}

	/**
	 * RuneLite offers each mouse event to its listeners one after another, in the order they signed up,
	 * and a listener that takes an event stops the ones after it. This map takes clicks over its whole
	 * area, so it signs up again each time it appears, which puts it at the end of the line. Another
	 * plugin's own panel on the map, such as a group member list, then gets its clicks first.
	 */
	private void lastInThePluginsQueue()
	{
		mouseManager.unregisterMouseListener(surface.getInput());
		mouseManager.registerMouseListener(surface.getInput());
		mouseManager.unregisterMouseWheelListener(surface.getInput());
		mouseManager.registerMouseWheelListener(surface.getInput());
	}

	/** True while the game's own map list, the drop down at the bottom of the window, is showing. */
	private boolean nativeListOpen()
	{
		Widget list = client.getWidget(InterfaceID.Worldmap.MAPLIST_CONTAINER);
		Rectangle area = mapBounds();
		Rectangle box = list == null || list.isHidden() ? null : list.getBounds();
		boolean showing = box != null && area != null && box.width > 0 && box.height > 0 && box.intersects(area);
		long now = System.currentTimeMillis();
		if (!showing)
		{
			listShownSince = 0;
			return false;
		}
		if (listShownSince == 0)
		{
			listShownSince = now;
		}
		// If the list ever reads as open for minutes it is not a list being used, so the map is not
		// left hidden because of it.
		return now - listShownSince < NATIVE_LIST_PATIENCE_MILLIS;
	}

	/** Where the game's own map is looking, or null when that cannot be read. */
	@Nullable
	private NativeMapSync.Spot nativePosition()
	{
		WorldMap map = client.getWorldMap();
		Point at = map == null ? null : map.getWorldMapPosition();
		return at == null ? null : new NativeMapSync.Spot(at.getX(), at.getY());
	}

	/** Called when the game closes the world map window, so the view is saved and input is let go at once. */
	public void mapClosed()
	{
		nativeSync.reset();
		surface.closedCue();
		surface.hide();
	}

	/** True while the viewer is covering the world map window. */
	public boolean isShowing()
	{
		return mapBounds() != null && config.replaceWorldMap();
	}

	/** Called when the plugin shuts down, to leave no state behind. */
	public void reset()
	{
		surface.reset();
	}

	/** Room left around each of the game's buttons, in pixels. */
	private static final int CONTROL_MARGIN = 1;

	/** Room left around each of the game's panels, in pixels: none, so the map meets the panel with no gap. */
	private static final int PANEL_MARGIN = 0;

	/**
	 * The game's own controls that sit over the map area: its close button, the handle for resizing the window,
	 * the buttons that open the map key and the overview, and those two panels when they are open. The map
	 * leaves them alone, so the game draws them and handles clicks on them as it always does. The zoom
	 * buttons and the search box are not among them, because this map has its own.
	 * <p>
	 * A widget that would take a large part of the map is not a control and is ignored, so a layout the
	 * plugin does not know about can never hide the map.
	 */
	private List<Rectangle> gameControls(Rectangle mapArea)
	{
		List<Rectangle> areas = new ArrayList<>(4);
		for (int i = 0; i < GAME_CONTROLS.length; i++)
		{
			Widget widget = client.getWidget(GAME_CONTROLS[i]);
			Rectangle box = widget == null || widget.isHidden() ? null : widget.getBounds();
			// The key and overview are whole panels and may take more of a small window than a button does.
			int limit = i >= PANELS_FROM ? MAX_PANEL_PERCENT : MAX_CONTROL_PERCENT;
			if (box == null || box.width <= 0 || box.height <= 0
				|| (long) box.width * box.height * 100 > (long) mapArea.width * mapArea.height * limit)
			{
				continue;
			}
			// A panel is left exactly as the game's widget has it, so no strip of the game's own map shows between it and this map.
			int margin = i >= PANELS_FROM ? PANEL_MARGIN : CONTROL_MARGIN;
			Rectangle padded = new Rectangle(box.x - margin, box.y - margin, box.width + 2 * margin, box.height + 2 * margin);
			if (padded.intersects(mapArea))
			{
				areas.add(padded);
			}
		}
		return areas;
	}

	private static final int[] GAME_CONTROLS = {
		InterfaceID.Worldmap.CLOSE,
		InterfaceID.Worldmap.RESIZE_INDICATOR,
		InterfaceID.Worldmap.KEY_TOGGLE,
		InterfaceID.Worldmap.OVERVIEW_TOGGLE,
		InterfaceID.Worldmap.KEY_CONTAINER,
		InterfaceID.Worldmap.OVERVIEW_CONTAINER
	};

	/** From this place in the list on, the widgets are panels and not buttons. */
	private static final int PANELS_FROM = 4;

	/** The most of the map area a panel may take and still be left to the game. */
	private static final int MAX_PANEL_PERCENT = 60;

	/** A game widget taking more than this share of the map area is not treated as a control. */
	private static final int MAX_CONTROL_PERCENT = 35;

	/** The map area of the game's world map window, or null when that window is not showing. */
	@Nullable
	private Rectangle mapBounds()
	{
		Widget container = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (container == null || container.isHidden())
		{
			return null;
		}
		Rectangle bounds = container.getBounds();
		return bounds == null || bounds.width <= 0 || bounds.height <= 0 ? null : bounds;
	}
}
