package com.worldmapplus.ui;

import com.worldmapplus.*;
import com.worldmapplus.game.*;
import com.worldmapplus.input.*;
import com.worldmapplus.prefs.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.client.ui.overlay.*;

/**
 * A see-through map you can keep open while you play.
 * <p>
 * It is drawn under the game's interface, so the inventory, chat box and every other panel stay on
 * top of it and are never covered. With click-through on, the mouse is not taken at all: every
 * click and wheel turn reaches the game exactly as if the map were not there. With it off the map
 * can be dragged, zoomed and searched, and it takes the mouse only inside its own rectangle.
 * <p>
 * It never shows over the world map window, where the viewer already is.
 */
@Singleton
public class HudOverlay extends Overlay
{
	private final Client client;
	private final WorldMapPlusConfig config;
	private final PlayerLocator locator;
	private final HudState state;
	private final MapSurface surface;

	@Inject
	public HudOverlay(Client client, MapAssets assets, WorldMapPlusConfig config, PlayerLocator locator,
		SurfaceServices services, ViewMemory memory, HudState state)
	{
		this.client = client;
		this.config = config;
		this.locator = locator;
		this.state = state;
		this.surface = new MapSurface(client, assets, config, services);

		setPosition(OverlayPosition.DYNAMIC);
		// Under the widgets, so the game's own interface is always drawn over this map.
		setLayer(OverlayLayer.UNDER_WIDGETS);
		setPriority(PRIORITY_LOW);
	}

	/** The input handler to register with RuneLite. */
	public ViewerInput getInput()
	{
		return surface.getInput();
	}

	/** Where a middle click on a map link, a result or a step of a route opens a map of its own. */
	public void setWindowOpener(MapSurface.WindowOpener opener)
	{
		surface.setWindowOpener(opener);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.hudEnabled() || !state.isVisible() || client.getGameState() != GameState.LOGGED_IN
			|| worldMapIsOpen())
		{
			surface.hide();
			return null;
		}

		Rectangle viewport = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
			client.getViewportWidth(), client.getViewportHeight());
		if (viewport.width <= 0 || viewport.height <= 0)
		{
			surface.hide();
			return null;
		}
		Rectangle bounds = HudLayout.compute(viewport, config.hudSize().percent(), config.hudCorner());

		boolean passThrough = state.isClickThrough();
		PlayerPosition position = locator.get();
		MapSurface.Options options = new MapSurface.Options()
			.memorySlot(ViewMemory.HUD)
			.openZoom(config.hudZoom())
			.followOnOpen(true)
			.resume(false)
			.alpha(Math.max(25, config.hudOpacity()) / 100f)
			.interactive(!passThrough)
			.controlFOverMapOnly(true)
			.passInput(passThrough ? Collections.emptyList() : interfacePanels(bounds))
			.showFrame(true);
		surface.render(g, bounds, position, options, System.currentTimeMillis());
		return null;
	}

	/** Most panels taken from the interface. */
	private static final int MAX_PANELS = 16;

	/** Most widgets looked at in a frame, so the search can never cost much. */
	private static final int MAX_WIDGETS = 700;

	/** A widget taking more than this share of the canvas is a layer holding the interface, not a panel. */
	private static final int MAX_PANEL_PERCENT = 40;

	/** Panels smaller than this, in pixels across, are ignored. */
	private static final int MIN_PANEL_SIDE = 24;

	/**
	 * The panels of the game's interface that lie over the map's rectangle, such as the inventory, the chat box
	 * and the minimap. The map is drawn under them. While it takes the mouse, clicks over them still go to the
	 * game. Found by looking at the bounds of the visible widgets, down to a few levels, so it is a best effort:
	 * a panel it misses is no worse off than before, and one it finds keeps its clicks.
	 */
	private List<Rectangle> interfacePanels(Rectangle area)
	{
		List<Rectangle> found = new ArrayList<>();
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return found;
		}
		long canvas = (long) client.getCanvasWidth() * client.getCanvasHeight();
		int[] looked = {0};
		for (Widget root : roots)
		{
			collectPanels(root, 0, area, canvas, found, looked);
		}
		return panelsThatAreNotTheWholeMap(found, area);
	}

	/** A widget that covers more of the map than this share is a layer of the interface, not a panel over a corner of it. */
	static final int MAX_COVER_PERCENT = 50;

	/**
	 * The panels worth leaving to the game. One that covers most of the map is a transparent layer, and leaving the
	 * mouse to it would leave the whole map to the game, so the map could never be clicked.
	 */
	static List<Rectangle> panelsThatAreNotTheWholeMap(List<Rectangle> panels, Rectangle area)
	{
		List<Rectangle> kept = new ArrayList<>();
		long mapSize = (long) area.width * area.height;
		for (Rectangle panel : panels)
		{
			Rectangle over = panel.intersection(area);
			long covered = over.isEmpty() ? 0 : (long) over.width * over.height;
			if (covered * 100 <= mapSize * MAX_COVER_PERCENT)
			{
				kept.add(panel);
			}
		}
		return kept;
	}

	private void collectPanels(Widget widget, int depth, Rectangle area, long canvas, List<Rectangle> found, int[] looked)
	{
		if (widget == null || widget.isHidden() || depth > 3 || found.size() >= MAX_PANELS || looked[0]++ > MAX_WIDGETS)
		{
			return;
		}
		Rectangle bounds = widget.getBounds();
		if (bounds != null && bounds.width >= MIN_PANEL_SIDE && bounds.height >= MIN_PANEL_SIDE && bounds.intersects(area))
		{
			long size = (long) bounds.width * bounds.height;
			if (size * 100 <= canvas * MAX_PANEL_PERCENT)
			{
				found.add(new Rectangle(bounds));
				return;
			}
		}
		for (Widget[] group : new Widget[][] {widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			if (group != null)
			{
				for (Widget child : group)
				{
					collectPanels(child, depth + 1, area, canvas, found, looked);
				}
			}
		}
	}

	/** Called when the plugin shuts down, to leave no state behind. */
	public void reset()
	{
		surface.reset();
	}

	private boolean worldMapIsOpen()
	{
		Widget container = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return container != null && !container.isHidden();
	}
}
