package com.worldmapplus.ui;

import static com.worldmapplus.theme.Palette.colour;

import com.worldmapplus.*;
import com.worldmapplus.bridge.*;
import com.worldmapplus.compat.*;
import com.worldmapplus.data.*;
import com.worldmapplus.drops.*;
import com.worldmapplus.game.*;
import com.worldmapplus.input.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.map.*;
import com.worldmapplus.pins.*;
import com.worldmapplus.prefs.*;
import com.worldmapplus.search.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.unlock.*;
import com.worldmapplus.view.*;
import com.worldmapplus.wiki.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;

import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.*;
import lombok.*;
import net.runelite.api.Client;
import net.runelite.api.coords.*;

/**
 * One drawn and operable map: the viewer, its controls, search and cards, inside a rectangle.
 * <p>
 * The window over the game's world map and the see-through map over the game scene are both a
 * surface, so they look and behave alike. They differ only in the rectangle they are given and the
 * {@link Options} they run with.
 * <p>
 * Client thread only. Mouse and key events arrive on the AWT thread through the surface's own
 * {@link ViewerInput}, which queues them for {@link #render}.
 */
public final class MapSurface
{
	/** How a host wants its surface to behave this frame. */
	public static final class Options
	{
		private String memorySlot = ViewMemory.WORLD_MAP;
		private double openZoom = MapViewer.DEFAULT_ZOOM;
		private boolean followOnOpen = true;
		private boolean startAtLastView;
		private float alpha = 1f;
		private boolean interactive = true;
		private boolean showFrame;
		private boolean resume = true;
		private boolean controlFOverMapOnly;
		private List<Rectangle> keepClear = Collections.emptyList();
		private List<Rectangle> passInput = Collections.emptyList();

		/** Which remembered view this surface uses. */
		public Options memorySlot(String slot)
		{
			this.memorySlot = slot;
			return this;
		}

		/** The zoom it opens at when not restoring a remembered view. */
		public Options openZoom(double pixelsPerTile)
		{
			this.openZoom = pixelsPerTile;
			return this;
		}

		public Options followOnOpen(boolean follow)
		{
			this.followOnOpen = follow;
			return this;
		}

		public Options startAtLastView(boolean last)
		{
			this.startAtLastView = last;
			return this;
		}

		/** 1 is opaque. Applies to everything the surface draws. */
		public Options alpha(float alpha)
		{
			this.alpha = Math.max(0.05f, Math.min(1f, alpha));
			return this;
		}

		/**
		 * When true, Ctrl+F starts a search only while the pointer is over the map. The floating map wants this, so the chord
		 * keeps working for the game and other plugins during normal play.
		 */
		public Options controlFOverMapOnly(boolean only)
		{
			this.controlFOverMapOnly = only;
			return this;
		}

		/** When false the surface only shows the map: no mouse, no hover, no controls. */
		public Options interactive(boolean interactive)
		{
			this.interactive = interactive;
			return this;
		}

		/**
		 * Whether a map that comes back soon after it went away carries on where it was, instead of
		 * starting again at the player. The window over the world map wants this, because resizing it
		 * makes the game rebuild the interface. The floating map wants a fresh start each time.
		 */
		public Options resume(boolean resume)
		{
			this.resume = resume;
			return this;
		}

		/**
		 * Places inside the map's rectangle that belong to the game, such as its close button. The surface
		 * draws nothing there and takes no mouse events there.
		 */
		public Options keepClear(List<Rectangle> areas)
		{
			this.keepClear = areas == null ? Collections.emptyList() : areas;
			return this;
		}

		/**
		 * Places inside the map's rectangle where mouse events go to the game, while the map is still drawn there.
		 * For parts of the game's interface that are drawn over a map that sits under it.
		 */
		public Options passInput(List<Rectangle> areas)
		{
			this.passInput = areas == null ? Collections.emptyList() : areas;
			return this;
		}

		/** Draws a thin border so the edge of a floating map is visible. */
		public Options showFrame(boolean showFrame)
		{
			this.showFrame = showFrame;
			return this;
		}
	}

	private static final Color PLAYER_RING = new Color(255, 255, 255);
	private static final Color PLAYER_FILL = new Color(255, 214, 0);
	private static final Color HOVER_RING = new Color(255, 214, 0, 230);
	private static final Color LINK_LINE = new Color(255, 150, 60, 230);
	private static final Color FOUND = new Color(80, 220, 255);
	private static final Color PIN_FILL = new Color(230, 60, 140);
	private static final Color ROUTE_TARGET = new Color(255, 214, 0);
	private static final Color ROUTE_START = new Color(90, 220, 120);

	/** How near the mouse must be to a pin or a marker to get its card, in pixels. */
	private static final double HOVER_PIXELS = 12;

	/** The zoom step of the zoom buttons: one doubling. */
	private static final double BUTTON_ZOOM_FACTOR = 2.0;

	/** A found place is shown at least this close, in pixels per tile. */
	private static final double FOUND_MIN_ZOOM = 8.0;

	/** How long the marker on a found place stays. */
	private static final long FOUND_MILLIS = 12_000;
	private static final long ROUTE_NOTICE_MILLIS = 9_000;

	/** Frames further apart than this mean the surface was closed and has opened again. */
	static final long REOPEN_GAP_MILLIS = 750;

	/** A surface that returns within this long carries on where it was, if its host asks for that. */
	static final long RESUME_MILLIS = 15_000;

	/** Below this width the search bar does not fit and is left out. */
	private static final int MIN_WIDTH_FOR_SEARCH = 420;

	private final Client client;
	private final MapAssets assets;
	private final WorldMapPlusConfig config;
	private final ViewMemory memory;
	private final PinStore pins;
	private final RouteStore routes;
	private final PanelLauncher launcher;
	private final ExternalOverlays external;
	private final MapActions actions;
	private final Settings settings;
	/** The game's own map underneath, where this map is shown over it. Null for a map in a window of its own. */
	private NativeMap nativeMap;
	/** True while this map is being kept where the game's map is. */
	@Getter
	private boolean linedUp;
	/** True from when the player moves this map until the game's map has caught up with it. */
	private boolean catchingUp;
	private int sentX = Integer.MIN_VALUE;
	private int sentY = Integer.MIN_VALUE;
	private long sentAt;
	/** Where this map put its centre last frame while lined up, to tell when something else has moved it. */
	private double putX = Double.NaN;
	private double putY = Double.NaN;
	private int catchingFrames;
	private int differentMapFrames;
	/** Where each mouse button last went down, by button number, to tell a click from a drag and place it. */
	private final Point[] down = {null, new Point(), null, null};
	private final NativePoints nativePoints;
	/** The layers list. Built when it is first needed, because its rows come from the bundled data, which loads on first use. */
	private volatile LayerPanel layerPanel;
	private Set<String> poiOn = Collections.emptySet();
	private String poiOnText = "";
	/** The input handler to register with RuneLite for this surface. */
	@Getter
	private final ViewerInput input = new ViewerInput();
	private final ContextMenu menu = new ContextMenu();

	private final MapButtons buttons = new MapButtons();
	private final FloorButtons floorButtons = new FloorButtons();
	private int lastPlayerFloor = -1;
	private final SearchBox searchBox = new SearchBox();
	private final SearchBar searchBar = new SearchBar();
	/** The panel of a chosen result with several places: tabs, Back, counts and orders. */
	private final ResultsPanel results = new ResultsPanel();
	private HoverCardPainter cardPainter;
	private BufferedImage layerImage;

	private MapViewer viewer;
	private boolean showing;
	private String slot = ViewMemory.WORLD_MAP;
	private Rectangle lastBounds = new Rectangle(0, 0, 1, 1);

	/** True while the current press began on the search bar rather than the map. */
	private boolean pressOnControls;

	/** The category of the map key that is flashing, and since when. 0 when none is. */
	private int flashCategory;
	private List<SearchEntry> flashExtra = Collections.emptyList();
	private long flashSince;

	/** True while the thumb of the search results' scroll bar is being dragged. */
	private boolean scrollDrag;

	/** The menu or list whose scroll bar's thumb is being dragged: the right click menu, the layers list or the results panel. */
	private Box barDrag;

	private SearchEntry found;
	private long foundSince;
	private PlayerPosition lastPlayer;
	private String routeNotice;
	private HoverCard pinnedCard;
	private Point pinnedAt = new Point();
	private long pinnedSince;
	private long lastFocusMillis = Long.MIN_VALUE / 2;
	private long routeNoticeSince;
	private long lastFrame = Long.MIN_VALUE;

	/** The title of the card drawn in the last frame, or null. For tests. */
	private String lastCardTitle;
	private HoverCard lastCard;

	/** The version of the pins the search index was last built with, or -1 for never. */
	private int searchPinVersion = -1;
	/** The route store's version the search index was made with. */
	private int searchRouteVersion = -1;
	private List<Pin> pinList = new ArrayList<>();
	private final Map<Long, Pin> pinById = new HashMap<>();

	/** The saved place the open menu was opened on, or 0. */
	private long menuPinId;

	/** A place under the mouse that the open menu offers to save, or null. */
	private SaveCandidate menuSave;

	/** True while the current left press began on the context menu. */
	private boolean pressOnMenu;

	public MapSurface(Client client, MapAssets assets, WorldMapPlusConfig config,
		SurfaceServices services)
	{
		this.client = client;
		this.assets = assets;
		this.config = config;
		this.memory = services.memory();
		this.pins = services.pins();
		this.routes = services.routes();
		this.launcher = services.launcher();
		this.external = services.external();
		this.actions = services.actions();
		this.settings = services.settings();
		this.nativePoints = services.nativePoints();
		this.chat = services.chatSearch();
	}

	/** The title of the chat area prompt the search is typed into when the setting for that is on. */
	static final String CHAT_TITLE = "Search the map";

	/** Where the search is typed when the player asks for the chat area, or null when this surface cannot use it. */
	private ChatSearch chat;

	/** The chat area prompt this surface has open, or null. Set only by {@link #syncChat}, cleared by every way out. */
	private volatile ChatSearch.Session chatSession;

	/**
	 * True when the search is typed into the chat area: the setting is on, and this is a map that can use it. A map in a window of
	 * its own has a real text field's worth of keys already and never touches the game's chat area.
	 */
	private boolean usesChat()
	{
		return chat != null && config.typeInChatArea();
	}

	/** Takes what the chat area prompt reported since the last frame: the text, then Enter, then the prompt going away. */
	private void pullChat(long now)
	{
		ChatSearch.Session session = chatSession;
		if (session == null)
		{
			return;
		}
		String text = session.takeText();
		if (text != null)
		{
			searchBox.setText(text);
		}
		if (session.takeDone())
		{
			activate(searchBox.selectedEntry(), now);
		}
		if (session.isClosed())
		{
			// Escape, Enter, or another prompt took the chat area. The search is over, as when a click lands elsewhere.
			chatSession = null;
			searchBox.blur();
		}
	}

	/**
	 * Makes the chat area prompt match the search: open while the search has the keyboard and is typed in the chat area, closed
	 * otherwise. Also says who takes the keys and the clicks away from the map, so the direct route and the chat route never both
	 * run. With the setting off and no prompt open this does nothing but set those two flags.
	 */
	private void syncChat(boolean interactive)
	{
		boolean focused = interactive && searchBox.isFocused();
		boolean viaChat = usesChat();
		ChatSearch.Session session = chatSession;
		if (focused && viaChat && session == null)
		{
			chatSession = chat.open(CHAT_TITLE, searchBox.getText());
		}
		else if (session != null && !(focused && viaChat))
		{
			closeChat();
		}
		input.setCaptureKeys(focused && !viaChat);
		input.setWatchClicks(focused && viaChat);
		input.setCaptureEscape(interactive && popupOpen());
	}

	/** True while the layers list or the right click menu is popped up over the map. */
	private boolean popupOpen()
	{
		LayerPanel panel = layerPanel;
		return menu.isOpen() || panel != null && panel.isOpen();
	}

	/** Closes the chat area prompt if this surface has one open. Safe from any thread. */
	private void closeChat()
	{
		ChatSearch.Session session = chatSession;
		if (session != null)
		{
			chatSession = null;
			chat.close(session);
		}
	}

	/** Opens a place on the floating map. Returns a sentence to show when it cannot show there now, or null. */
	public interface WindowOpener
	{
		@Nullable
		String open(int mapId, double x, double y, double zoom);
	}

	private WindowOpener windowOpener;

	/** A place this map was asked to show, taken at its next frame, or null. */
	private MapInfo startMap;
	private double startX;
	private double startY;
	private double startZoom;

	/** Where a middle click on a map link, a search result or a step of the route opens the place: the floating map. */
	public void setWindowOpener(@Nullable WindowOpener opener)
	{
		this.windowOpener = opener;
	}

	/** A place to show at the next frame: where the map opens, in place of the player or the last view, or where it goes if open. */
	public void startAt(MapInfo map, double x, double y, double zoom)
	{
		this.startMap = map;
		this.startX = x;
		this.startY = y;
		this.startZoom = zoom;
	}

	/**
	 * Draws one frame inside {@code bounds}.
	 *
	 * @param position the player, or null when not known
	 */
	public void render(Graphics2D g, Rectangle bounds, @Nullable PlayerPosition position, Options options, long now)
	{
		long previousFrame = lastFrame;
		if (showing && lastFrame != Long.MIN_VALUE && now - lastFrame > REOPEN_GAP_MILLIS)
		{
			// An overlay is not drawn while its interface is closed, so a long gap means it was closed
			// and has been opened again. Treat it as a fresh open.
			hide();
		}
		lastFrame = now;
		if (!showing)
		{
			boolean soon = previousFrame != Long.MIN_VALUE && now - previousFrame <= RESUME_MILLIS;
			if (viewer != null && options.resume && soon)
			{
				resume();
			}
			else
			{
				begin(position, options);
			}
		}
		else if (startMap != null)
		{
			// Asked to show a place while open: it goes there.
			viewer.setFollowing(false);
			goTo(startMap, startX, startY, now);
			zoomForJump(startZoom);
			startMap = null;
		}
		slot = options.memorySlot;
		lastBounds = bounds;
		lastPlayer = position;
		refreshSearch();
		applyFocus(now);
		trackFloor(position);

		boolean interactive = options.interactive;
		if (interactive && nativePoints != null && config.syncGameMap())
		{
			// Points read here, shown or not, say whether the game's map has to be kept with this one.
			nativePoints.markers(System.currentTimeMillis());
		}
		if (interactive)
		{
			input.setActive(bounds);
			input.setControlFNeedsPointer(options.controlFOverMapOnly);
			input.setPointerOver(mouse(bounds) != null);
			if (options.passInput.isEmpty())
			{
				input.setKeepClear(options.keepClear);
			}
			else
			{
				List<Rectangle> both = new ArrayList<>(options.keepClear);
				both.addAll(options.passInput);
				input.setKeepClear(both);
			}
			gameAreas = options.passInput.isEmpty() ? options.keepClear : both(options.keepClear, options.passInput);
			searchAvailable = bounds.width >= MIN_WIDTH_FOR_SEARCH;
			buttons.setLineUp(nativeMap != null, config.lineUpWithGameMap());
			buttons.setZoomShown(!linedUp);
			boolean floors = hasFloors(viewer.getMap());
			buttons.layout(bounds, floors);
			floorButtons.setShown(floors);
			floorButtons.layout(buttons.floorUpSlot(), buttons.floorDownSlot());
			if (searchAvailable)
			{
				searchBar.layout(bounds, searchBox.getResults().size(), !searchBox.getHint().isEmpty());
			}
			else
			{
				// Too narrow to draw the bar, so it takes no clicks and no typing either.
				searchBar.layout(bounds, 0, false);
				searchBar.hideAway();
				searchBox.blur();
			}
			layerPanel().layout(buttons.boundsOf(MapButtons.Button.LAYERS), buttons.block(), bounds,
				searchAvailable ? searchBar.getFieldRect() : null);
			searchBox.setVisibleRows(searchBar.getCapacity());
			Point pointer = mouse(bounds);
			searchBar.animate(searchBox.isFocused() || pointer != null && searchBar.keepsOpen(pointer.x, pointer.y), now);
		}
		else
		{
			input.release();
			searchBox.blur();
			menu.close();
			closeLayerPanel();
			clearHeld();
		}
		applySettings();

		pullChat(now);
		for (MapInput event : input.drain())
		{
			apply(event, position, now);
		}
		syncChat(interactive);

		double followX = position != null ? position.getX() : viewer.getX();
		double followY = position != null ? position.getY() : viewer.getY();
		if (followMember(now))
		{
			followX = memberX;
			followY = memberY;
		}
		else if (position != null && !playerIsOnViewedMap(position))
		{
			viewer.setFollowing(false);
		}

		double[] lined = lineUpPrepare(bounds, now, position);
		ViewTransform view = viewer.update(now, bounds, followX, followY);
		if (lined != null)
		{
			view = lineUpFinish(lined, view, bounds);
		}
		MapInfo map = viewer.getMap();

		if (options.alpha < 1f)
		{
			// A translucent map is drawn solid into a scratch image and laid over the game once.
			// Drawing the layers one by one at partial opacity would let the dark backdrop and the
			// tiles show through each other and darken whatever is behind.
			BufferedImage layer = scratch(bounds);
			Graphics2D layerGraphics = layer.createGraphics();
			try
			{
				layerGraphics.translate(-bounds.x, -bounds.y);
				paintContents(layerGraphics, bounds, view, map, position, options, interactive, now);
			}
			finally
			{
				layerGraphics.dispose();
			}
			Composite oldComposite = g.getComposite();
			g.setComposite(AlphaComposite.SrcOver.derive(options.alpha));
			g.drawImage(layer, bounds.x, bounds.y, null);
			g.setComposite(oldComposite);
		}
		else
		{
			paintContents(g, bounds, view, map, position, options, interactive, now);
		}
	}

	/** A reusable opaque image the size of the surface. */
	private BufferedImage scratch(Rectangle bounds)
	{
		if (layerImage == null || layerImage.getWidth() != bounds.width || layerImage.getHeight() != bounds.height)
		{
			layerImage = new BufferedImage(Math.max(1, bounds.width), Math.max(1, bounds.height),
				BufferedImage.TYPE_INT_RGB);
		}
		return layerImage;
	}

	private void paintContents(Graphics2D g, Rectangle bounds, ViewTransform view, MapInfo map,
		@Nullable PlayerPosition position, Options options, boolean interactive, long now)
	{
		lastCardTitle = null;
		lastCard = null;
		animNow = now;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Shape oldClip = g.getClip();
		Area drawable = new Area(bounds);
		for (Rectangle area : options.keepClear)
		{
			drawable.subtract(new Area(area));
		}
		g.clip(drawable);
		try
		{
			assets.getRenderer().paint(g, view, map, hasFloors(map) ? viewer.getFloor() : 0);
			if (config.showGrid())
			{
				OverlayLines.paintGrid(g, view, map);
			}
			Set<String> poi = enabledPoi();
			if (!poi.isEmpty())
			{
				assets.getPoiLayer().paint(g, view, map, poi);
			}
			applyHiddenGameIcons();
			applyProgress();
			icons().paint(g, view, map, iconFloor(map));
			// Names go over the map's icons and dots, so an icon never hides the name of the place it is in.
			if (config.showLabels())
			{
				labelLayer().paint(g, view, map, hasFloors(map) ? viewer.getFloor() : 0);
			}
			RoutePlan plan = routePlan(position);
			if (map != null)
			{
				drawExternalLines(g, view, map);
				drawRouteLegs(g, view, map, plan);
				drawRouteTarget(g, view, map, position);
				drawFlash(g, view, map, now);
				drawSavedRoutes(g, view, map);
				drawPins(g, view, map);
				drawExternalMarkers(g, view, map);
				drawFound(g, view, map, now);
			}

			if (position != null && playerIsOnViewedMap(position))
			{
				drawPlayer(g, view, position);
			}

			Point mouse = interactive ? mouse(bounds) : null;
			if (interactive)
			{
				MapButtons.Button overButton = mouse == null || menu.isOpen() ? null : buttons.hit(mouse.x, mouse.y);
				buttons.paint(g, overButton);
				drawTip(g, overButton);
				drawFloors(g, mouse);
				drawResults(g, bounds, mouse, now);
				if (bounds.width >= MIN_WIDTH_FOR_SEARCH)
				{
					searchBar.setMissing(judges() ? resultMissing : null);
					searchBar.paint(g, searchBox, mouse, now);
				}
				drawRouteNotice(g, bounds, now);
				drawRoutePanel(g, bounds, plan);
				drawHover(g, bounds, view, map, mouse);
				if (lastCardTitle == null)
				{
					cardShown = null;
				}
				layerPanel().paint(g, config, mouse);
				menu.paint(g, mouse);
				drawPinnedCard(g, bounds, now);
			}
		}
		finally
		{
			g.setClip(oldClip);
		}

		if (options.showFrame)
		{
			g.setColor(colour("FRAME"));
			g.setStroke(new BasicStroke(1f));
			g.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
		}
	}

	/** The viewer, for tests. */
	MapViewer viewer()
	{
		return viewer;
	}

	/** Where the search results are drawn, for tests. */
	Rectangle searchBarResults()
	{
		return searchBar.getResultsRect();
	}

	/** The category of the map key that is flashing, or 0. For tests. */
	int flashing()
	{
		return flashCategory;
	}

	/** The search state, for tests. */
	SearchBox searchBox()
	{
		return searchBox;
	}

	/** The context menu, for tests. */
	ContextMenu menu()
	{
		return menu;
	}

	/** The title of the hover card drawn in the last frame, or null. */
	String lastCardTitle()
	{
		return lastCardTitle;
	}

	/** The hover card drawn in the last frame, or null. */
	HoverCard lastCard()
	{
		return lastCard;
	}

	/** The corner buttons, as laid out in the last frame. For tests. */
	MapButtons buttons()
	{
		return buttons;
	}

	/** The floor buttons, as laid out in the last frame. For tests. */
	FloorButtons floorButtons()
	{
		return floorButtons;
	}

	/** The search bar, as laid out in the last frame. For tests. */
	SearchBar searchBar()
	{
		return searchBar;
	}

	/** The layer panel, built on the first call. */
	LayerPanel layerPanel()
	{
		LayerPanel panel = layerPanel;
		if (panel == null)
		{
			synchronized (this)
			{
				panel = layerPanel;
				if (panel == null)
				{
					panel = new LayerPanel(assets.getPoiCategories(), assets.getElements().drawnCategoryNames());
					layerPanel = panel;
				}
			}
		}
		return panel;
	}

	/** Closes the layers list if there is one. A list that was never built is closed already, and building it would load the data. */
	private void closeLayerPanel()
	{
		LayerPanel panel = layerPanel;
		if (panel != null)
		{
			panel.close();
		}
	}

	/** The place most recently jumped to by search, for tests. */
	SearchEntry found()
	{
		return found;
	}

	/** Call on a frame the surface is not shown. Saves the view and lets go of the mouse and keyboard. */
	public void hide()
	{
		if (!showing)
		{
			return;
		}
		showing = false;
		// Whatever is still being loaded for this map stops; another map that is showing asks again with its next frame.
		assets.endView();
		suspend();
		searchBox.blur();
		found = null;
		results.close();
		if (viewer != null && viewer.getMap() != null)
		{
			memory.save(slot, viewer.getMap().getId(), viewer.getX(), viewer.getY(),
				viewer.targetZoom());
		}
	}

	/** Drops all state, for plugin shutdown. */
	public void reset()
	{
		showing = false;
		layerImage = null;
		viewer = null;
		found = null;
		results.close();
		searchBox.blur();
		suspend();
	}

	private void applySettings()
	{
		WorldMapPlusConfig.ZoomFeel feel = config.zoomFeel();
		viewer.setFeel(feel.speed, feel.acceleration, feel.smoothing);
		assets.getRenderer().setSmoothMagnify(config.smoothMagnify());
		Motion.use(config.animations());
	}

	/** Goes to a place: a glide on the map already shown when animations are on, a jump otherwise. */
	private void goTo(MapInfo map, double x, double y, long now)
	{
		if (Motion.on())
		{
			viewer.glideTo(map, x, y, now);
		}
		else
		{
			viewer.showMap(map, x, y);
		}
	}

	/** Sets the zoom for a place just gone to: eased when animations are on, at once otherwise. */
	private void zoomForJump(double zoom)
	{
		if (Motion.on())
		{
			viewer.easeZoom(zoom);
		}
		else
		{
			viewer.jumpZoom(zoom);
		}
	}

	/** True while something of the map is still moving, so the caller can keep drawing. Nothing is, when the map is still. */
	public boolean isMoving()
	{
		long now = lastFrame;
		return viewer != null && (viewer.isMoving() || menu.moving(now) || results.moving(now)
			|| layerPanel != null && layerPanel.moving(now));
	}

	/** Comes back after a short absence with the view, search and marks exactly as they were. */
	private void resume()
	{
		showing = true;
		suspend();
		searchBox.blur();
		menu.close();
		closeLayerPanel();
	}

	/**
	 * Lets go of the mouse and keyboard without forgetting anything, for a moment when the map cannot
	 * be drawn, such as while the game rebuilds its window. It is drawn again, as it was, when the
	 * frames come back.
	 */
	public void suspend()
	{
		input.release();
		closeChat();
		pressOnControls = false;
		scrollDrag = false;
		barDrag = null;
		pressOnMenu = false;
		if (viewer != null)
		{
			viewer.cancelGesture();
		}
	}

	private void begin(@Nullable PlayerPosition position, Options options)
	{
		showing = true;
		lastPlayerFloor = -1;
		input.clear();
		searchBox.setTileMap((x, y) ->
		{
			MapInfo at = assets.getCatalog().mapFor(x, y);
			return at == null ? -1 : at.getId();
		});
		searchBox.setContext(this::searchContext);
		searchBox.setIndex(assets.getSearchIndex());
		// That index has no saved places in it. Forgetting the version makes the next frame add them again.
		searchPinVersion = -1;
		searchBox.blur();
		pressOnControls = false;
		scrollDrag = false;
		found = null;

		MapCatalog catalog = assets.getCatalog();
		ViewMemory.Saved saved = options.startAtLastView ? memory.load(options.memorySlot) : null;
		MapInfo savedMap = saved == null ? null : catalog.byId(saved.getMapId());

		MapInfo map;
		double x;
		double y;
		double zoom = options.openZoom;
		boolean follow = false;

		if (startMap != null)
		{
			map = startMap;
			x = startX;
			y = startY;
			zoom = startZoom;
			startMap = null;
		}
		else if (savedMap != null)
		{
			map = savedMap;
			x = saved.getX();
			y = saved.getY();
			zoom = saved.getPixelsPerTile();
		}
		else
		{
			MapInfo playerMap = position == null ? null
				: catalog.mapNear(position.getTile().getX(), position.getTile().getY(), NEAR_MAP_TILES);
			map = playerMap != null ? playerMap : catalog.surface();
			if (playerMap != null)
			{
				x = position.getX();
				y = position.getY();
				follow = options.followOnOpen;
			}
			else if (map != null)
			{
				x = map.getCenterX();
				y = map.getCenterY();
			}
			else
			{
				x = 3200;
				y = 3200;
			}
		}

		if (viewer == null)
		{
			viewer = new MapViewer(map, x, y, zoom);
		}
		else
		{
			viewer.showMap(map, x, y);
		}
		viewer.jumpZoom(zoom);
		viewer.setFollowing(follow);
		followName = null;
	}

	// ------------------------------------------------------------------
	// Input
	// ------------------------------------------------------------------

	/** An event this map saw but left for the game. Only a click is used, to tell what it landed on. */
	private void applyLeftForGame(MapInput event, @Nullable PlayerPosition position)
	{
		if (event.kind == MapInput.Kind.RELEASE && event.code() == MouseEvent.BUTTON1)
		{
			if (isClick(down[0], event))
			{
				click(event.x, event.y, position);
			}
			down[0] = null;
		}
	}

	/** True when a button is let go within a few pixels of where it went down. */
	private static boolean isClick(@Nullable Point press, MapInput event)
	{
		return press != null && Math.hypot(event.x - press.x, event.y - press.y) <= 5;
	}

	/** The menu or list under a point, which the wheel scrolls in place of zooming the map under it, or null. */
	@Nullable
	private Box listAt(int x, int y)
	{
		LayerPanel panel = layerPanel;
		return menu.contains(x, y) ? menu : panel != null && panel.contains(x, y) ? panel
			: results.contains(x, y) && !searchBar.overResults(x, y) ? results : null;
	}

	private void apply(MapInput event, @Nullable PlayerPosition position, long now)
	{
		if (event.kind == MapInput.Kind.PRESS && event.code() > 0 && event.code() < down.length && (event.taken || event.code() == 1))
		{
			// A press left for the game is kept apart, in place 0, so it never stands in for one this map took.
			down[event.taken ? event.code() : 0] = new Point(event.x, event.y);
		}
		if (!event.taken)
		{
			applyLeftForGame(event, position);
			return;
		}
		switch (event.kind)
		{
			case WHEEL:
			{
				Box list = listAt(event.x, event.y);
				int rows = event.amount > 0 ? WHEEL_ROWS : -WHEEL_ROWS;
				if (list == null && !searchBar.overResults(event.x, event.y))
				{
					if (!isControl(event.x, event.y))
					{
						viewer.onWheel(event.amount, event.x, event.y, event.time);
					}
				}
				else if (event.amount != 0 && list != null)
				{
					list.scroll(rows);
				}
				else if (event.amount != 0 && searchBar.scrolls())
				{
					searchBox.scroll(rows);
				}
				break;
			}
			case PRESS:
				if (pinnedCard != null && !menu.isOpen())
				{
					pinnedCard = null;
				}
				if (event.code() == MouseEvent.BUTTON1 && menu.isOpen())
				{
					barDrag = menu.scrollBarHit(event.x, event.y) ? menu : null;
					if (barDrag != null)
					{
						menu.dragBarTo(event.y);
					}
					pressOnMenu = barDrag != null || menu.hit(event.x, event.y) != null;
					if (!pressOnMenu)
					{
						menu.close();
						pressOnControls = isControl(event.x, event.y);
						if (!pressOnControls)
						{
							viewer.onPress(event.x, event.y, event.time);
						}
					}
				}
				else if (event.code() == MouseEvent.BUTTON1)
				{
					pressOnControls = isControl(event.x, event.y);
					if (pressOnControls)
					{
						scrollDrag = searchBar.hit(event.x, event.y).isScrollBar();
						LayerPanel panel = layerPanel;
						barDrag = scrollDrag ? null : panel != null && panel.scrollBarHit(event.x, event.y) ? panel
							: searchBar.hit(event.x, event.y) == SearchBar.Hit.NONE && results.scrollBarHit(event.x, event.y) ? results : null;
						if (barDrag != null)
						{
							barDrag.dragBarTo(event.y);
						}
						else if (scrollDrag)
						{
							searchBox.scrollToFraction(searchBar.scrollFractionAt(event.y));
						}
					}
					else
					{
						viewer.onPress(event.x, event.y, event.time);
					}
				}
				break;
			case DRAG:
				if (barDrag != null)
				{
					barDrag.dragBarTo(event.y);
				}
				else if (scrollDrag)
				{
					// Dragging the scroll bar's thumb moves the list with it.
					searchBox.scrollToFraction(searchBar.scrollFractionAt(event.y));
				}
				else if (!pressOnControls && !pressOnMenu)
				{
					viewer.onDrag(event.x, event.y, event.time);
				}
				break;
			case RELEASE:
				if (event.code() == MouseEvent.BUTTON3)
				{
					if (isClick(down[3], event))
					{
						openMenu(event.x, event.y);
					}
					down[3] = null;
				}
				else if (event.code() == MouseEvent.BUTTON2)
				{
					if (isClick(down[2], event))
					{
						openInWindow(event.x, event.y);
					}
					down[2] = null;
				}
				else if (event.code() == MouseEvent.BUTTON1)
				{
					if (pressOnMenu)
					{
						pressOnMenu = false;
						if (barDrag == menu)
						{
							// Letting go of the bar leaves the menu open.
							barDrag = null;
						}
						else
						{
							ContextMenu.Item item = menu.hit(down[1].x, down[1].y);
							if (item != null && item == menu.hit(event.x, event.y))
							{
								perform(item);
							}
							menu.close();
						}
					}
					else if (pressOnControls)
					{
						pressOnControls = false;
						if (barDrag == null && !scrollDrag)
						{
							clickSearch(down[1].x, down[1].y, now);
						}
						barDrag = null;
						scrollDrag = false;
					}
					else if (viewer.onRelease(event.time))
					{
						click(down[1].x, down[1].y, position);
					}
				}
				break;
			case GAME_PRESS:
				keyClicked(event.x, event.y, now);
				break;
			case OUTSIDE_PRESS:
				if (chatSession != null && chat.covers(event.x, event.y))
				{
					// A click in the chat area's own prompt, such as to move the cursor, is still typing the search.
					break;
				}
				searchBox.blur();
				break;
			case TYPED:
				// With the search typed in the chat area, whatever the map saw from the keyboard before that is stale.
				if (!usesChat())
				{
					searchBox.type(event.character());
				}
				break;
			case KEY:
				if (event.code() == KeyEvent.VK_ESCAPE && popupOpen())
				{
					// Escape puts away what is popped up over the map, first, and only then ends a search.
					closeLayerPanel();
					menu.close();
					break;
				}
				if (!usesChat() || event.code() == ViewerInput.KEY_FOCUS_SEARCH)
				{
					key(event.code(), now);
				}
				break;
			default:
				break;
		}
	}

	private void key(int code, long now)
	{
		if (code == ViewerInput.KEY_FOCUS_SEARCH)
		{
			if (searchAvailable)
			{
				searchBox.focus();
			}
			return;
		}
		if (code == ViewerInput.KEY_SELECT_ALL)
		{
			searchBox.selectAll();
			return;
		}
		switch (code)
		{
			case KeyEvent.VK_BACK_SPACE:
				searchBox.backspace();
				break;
			case KeyEvent.VK_ESCAPE:
				if (!searchBox.clear())
				{
					searchBox.blur();
				}
				break;
			case KeyEvent.VK_ENTER:
				activate(searchBox.selectedEntry(), now);
				break;
			case KeyEvent.VK_TAB:
				searchBox.complete();
				break;
			case KeyEvent.VK_DOWN:
				searchBox.moveSelection(1);
				break;
			case KeyEvent.VK_UP:
				searchBox.moveSelection(-1);
				break;
			default:
				break;
		}
	}

	/** A click that began and ended on the search bar or the layer panel. */
	private void clickSearch(int x, int y, long now)
	{
		if (heldCard != null && heldButton != null && heldButton.contains(x, y))
		{
			boolean page = HoverCardPainter.hasPage(heldCard);
			openWiki(page ? heldCard.getWiki() : heldCard.getWikiSearch(), page);
			return;
		}
		if (overHeldCard(x, y))
		{
			// The rest of the card does nothing when it is clicked.
			return;
		}
		int step = routeRowAt(x, y);
		if (step >= 0)
		{
			jumpToStep(step);
			return;
		}
		LayerPanel shown = layerPanel;
		LayerPanel.Row row = shown == null ? null : shown.hit(x, y);
		if (row != null)
		{
			row.toggle(settings, config);
			return;
		}
		if (shown != null && shown.closeHit(x, y))
		{
			shown.close();
			return;
		}
		if (shown != null && shown.contains(x, y))
		{
			return;
		}
		SearchBar.Hit hit = searchBar.hit(x, y);
		if (hit == SearchBar.Hit.NONE && results.contains(x, y))
		{
			clickResults(x, y, now);
			return;
		}
		if (hit.isField())
		{
			// Searching and the layers list are not both open over the same corner of the map.
			closeLayerPanel();
			searchBox.focus();
		}
		else if (hit.isScrollBar())
		{
			searchBox.scrollToFraction(hit.getScrollFraction());
		}
		else if (hit.getRow() >= 0 && hit.getRow() + searchBox.getScrollTop() < searchBox.getResults().size())
		{
			searchBox.select(hit.getRow() + searchBox.getScrollTop());
			activate(searchBox.selectedEntry(), now);
		}
	}

	private void click(int x, int y, @Nullable PlayerPosition position)
	{
		FloorButtons.Button floor = floorButtons.hit(x, y);
		if (floor != null)
		{
			int next = viewer.getFloor() + (floor == FloorButtons.Button.UP ? 1 : -1);
			viewer.setFloor(Math.min(assets.getFloors(), next));
			return;
		}
		MapButtons.Button button = buttons.hit(x, y);
		if (button == null)
		{
			searchBox.blur();
			closeLayerPanel();
			clickHotspot(x, y);
			return;
		}
		switch (button)
		{
			case LAYERS:
				layerPanel().toggle();
				break;
			case LINE_UP:
			{
				boolean on = !config.lineUpWithGameMap();
				if (settings != null)
				{
					settings.setBoolean("lineUpWithGameMap", on);
				}
				routeNotice = on ? "Lined up with the game map. It moves and zooms this map (Ctrl and right click for this map's menu)."
					: "Zoom is yours again.";
				routeNoticeSince = lastFrame;
				break;
			}
			case ZOOM_IN:
				viewer.easeZoom(viewer.targetZoom() * BUTTON_ZOOM_FACTOR);
				break;
			case ZOOM_OUT:
				viewer.easeZoom(viewer.targetZoom() / BUTTON_ZOOM_FACTOR);
				break;
			case LOCATE:
				locate(position);
				break;
			default:
				break;
		}
	}

	/**
	* A middle click on something that leads somewhere opens that place on the floating map: a link to another map, a search
	* result, or a step of the route. Anywhere else it does nothing.
	*/
	private void openInWindow(int x, int y)
	{
		if (windowOpener == null || viewer == null || viewer.getMap() == null)
		{
			return;
		}
		double zoom = viewer.targetZoom();
		SearchBar.Hit hit = searchBar.hit(x, y);
		if (hit.getRow() >= 0)
		{
			int index = searchBox.getScrollTop() + hit.getRow();
			if (index < searchBox.getResults().size())
			{
				SearchEntry entry = nearestTo(searchBox.getResults().get(index), lastPlayer);
				open(entry.getMapId(), entry.getX(), entry.getY(), Math.max(zoom, FOUND_MIN_ZOOM));
			}
			return;
		}
		int step = routeRowAt(x, y);
		if (step >= 0)
		{
			RoutePlan.Step from = routeRowSteps.get(step);
			MapInfo at = assets.getCatalog().mapFor((int) Math.floor(from.getFromX()), (int) Math.floor(from.getFromY()));
			open(at == null ? assets.getCatalog().surface().getId() : at.getId(), from.getFromX(), from.getFromY(),
			Math.max(zoom, FOUND_MIN_ZOOM));
			return;
		}
		if (isControl(x, y) || !config.showHotspots())
		{
			return;
		}
		for (Candidate candidate : candidates(currentView(), viewer.getMap(), x, y))
		{
			if (candidate.what instanceof Hotspot && ((Hotspot) candidate.what).getTarget() != null)
			{
				Hotspot.Target target = ((Hotspot) candidate.what).getTarget();
				open(target.getMapId(), target.getX(), target.getY(), zoom);
				return;
			}
		}
	}

	private void open(int mapId, double x, double y, double zoom)
	{
		String why = windowOpener.open(mapId, x, y, zoom);
		if (why != null)
		{
			say(why);
		}
	}

	/** A click on a hotspot with a target switches the viewer's own map. It never reaches the game. */
	private void clickHotspot(int x, int y)
	{
		MapInfo map = viewer.getMap();
		if (map == null)
		{
			return;
		}
		ViewTransform view = currentView();
		for (Candidate candidate : candidates(view, map, x, y))
		{
			if (!candidate.actionable)
			{
				continue;
			}
			if (candidate.what instanceof Pin)
			{
				routeToPin((Pin) candidate.what);
			}
			else if (candidate.what instanceof ExternalMarker)
			{
				ExternalMarker marker = (ExternalMarker) candidate.what;
				if (marker.getJumpTarget() != null)
				{
					focusOn(marker.getJumpTarget().getX(), marker.getJumpTarget().getY());
				}
				else
				{
					// The plugin that sent the marker asked to hear of a click, and decides itself what it means.
					external.markerClicked(marker);
				}
			}
			else
			{
				Hotspot.Target target = ((Hotspot) candidate.what).getTarget();
				MapInfo targetMap = assets.getCatalog().byId(target.getMapId());
				if (targetMap != null)
				{
					viewer.setFollowing(false);
					viewer.showMap(targetMap, target.getX(), target.getY());
				}
			}
			return;
		}
	}

	/** The transform in force right now. Package visible so tests can aim at things on screen. */
	ViewTransform currentView()
	{
		return new ViewTransform(viewer.getX(), viewer.getY(),
			viewer.pixelsPerTile(), lastBounds.x, lastBounds.y, lastBounds.width, lastBounds.height);
	}

	/** Takes the map to a search result and marks it. */
	private void activate(@Nullable SearchEntry entry, long now)
	{
		if (entry == null)
		{
			return;
		}
		if (entry.getKind() == SearchKind.ROUTE)
		{
			// A saved route found in the search starts as its Start button in the side panel starts it (round 48).
			startRoute(entry.getOwnId());
			searchBox.blur();
			return;
		}
		openResults(entry);
		String kindId = entry.getKindId();
		if (kindId != null)
		{
			entry = KindResults.target(assets.getKinds(), entry, kindPlayerMap(), viewer.getMap() == null ? -1 : viewer.getMap().getId());
			if (entry == null)
			{
				return;
			}
			revealKind(kindId);
		}
		MapInfo target = assets.getCatalog().byId(entry.getMapId());
		if (target == null)
		{
			return;
		}
		entry = chooseAmongPlaces(entry, now);
		viewer.setFollowing(false);
		goTo(target, entry.getX(), entry.getY(), now);

		double zoom = viewer.targetZoom();
		boolean wholeMap = entry.getKind() == SearchKind.PLACE && !entry.isLocation();
		if (wholeMap)
		{
			double fitX = lastBounds.width / (double) (target.getMaxX() - target.getMinX());
			double fitY = lastBounds.height / (double) (target.getMaxY() - target.getMinY());
			zoom = Math.min(fitX, fitY) * 0.9;
		}
		else
		{
			zoom = Math.max(zoom, FOUND_MIN_ZOOM);
		}
		zoomForJump(zoom);

		found = wholeMap ? null : entry;
		foundSince = now;
		searchBox.blur();
	}

	/** The results panel, for tests. */
	ResultsPanel results()
	{
		return results;
	}

	/**
	 * Shows the panel for a result chosen in the search, in place of whatever it showed, or puts it away for a result with one place.
	 * Made once, when the result is chosen.
	 */
	private void openResults(SearchEntry entry)
	{
		ResultView view = config.resultsPanel() != WorldMapPlusConfig.ResultsPanel.OFF ? makeView(entry) : null;
		if (view == null)
		{
			results.close();
		}
		else
		{
			measure(view);
			results.replace(view);
		}
		noteWaiting(entry, view);
	}

	/**
	 * The panel's view for a result, with its drop tab when the setting is on and the drop tables are read. While they are not, it
	 * asks for them (one read on the shared executor) and the view is made without; {@link #finishDrops} makes it again later.
	 */
	@Nullable
	private ResultView makeView(SearchEntry entry)
	{
		DropTables tables = null;
		if (drops() && ResultViews.canHaveDrops(entry))
		{
			DropLoader loader = assets.getDrops();
			tables = loader.ready();
			if (tables == null)
			{
				loader.request();
			}
		}
		ResultView view = ResultViews.forEntry(entry, assets.getKinds(), assets.getSearchEntries(), tables);
		if (view != null && view.drops != null)
		{
			// The prices are read later on the client thread; the rows show them when they are there.
			assets.getPrices().request(view.drops.itemIds);
		}
		return view;
	}

	/** The result whose view was made while the drop tables were still being read, or null. */
	@Nullable
	private SearchEntry dropsWaitingFor;
	/** The view shown for it then (null when it had none) and the panel's change count then. */
	@Nullable
	private ResultView dropsWaitingView;
	private int dropsWaitingChanges;

	/** Remembers that a view was made without the drop tables that would add to it. */
	private void noteWaiting(SearchEntry entry, @Nullable ResultView view)
	{
		boolean waiting = drops() && ResultViews.canHaveDrops(entry)
			&& assets.getDrops().ready() == null;
		dropsWaitingFor = waiting ? entry : null;
		dropsWaitingView = waiting ? view : null;
		dropsWaitingChanges = results.changes();
	}

	/**
	 * Once the drop tables are read, makes the view of the result chosen while they were read again, with its drop tab, but only
	 * when the panel still shows what it showed then (nothing chosen, closed or gone back since). Looked at once a frame: a field
	 * read until the tables are there, then one view made.
	 */
	private void finishDrops()
	{
		SearchEntry entry = dropsWaitingFor;
		if (entry == null || assets.getDrops().ready() == null)
		{
			return;
		}
		ResultView old = dropsWaitingView;
		boolean current = results.changes() == dropsWaitingChanges && results.view() == old;
		dropsWaitingFor = null;
		dropsWaitingView = null;
		if (!current || !drops())
		{
			return;
		}
		ResultView next = makeView(entry);
		if (next == null)
		{
			return;
		}
		measure(next);
		if (old == null)
		{
			results.replace(next);
		}
		else
		{
			results.swap(old, next);
		}
	}

	/** Works out how far each row of a view is from the player now. */
	private void measure(ResultView view)
	{
		int map = kindPlayerMap();
		view.measureFrom(lastPlayer != null && map >= 0, map, lastPlayer == null ? 0 : lastPlayer.getX(),
			lastPlayer == null ? 0 : lastPlayer.getY(), id ->
			{
				MapInfo info = assets.getCatalog().byId(id);
				return info == null ? null : "In " + info.getName();
			});
	}

	/** Lays out and draws the results panel, under the route panel at the left of the map. */
	private void drawResults(Graphics2D g, Rectangle bounds, @Nullable Point mouse, long now)
	{
		if (dropsWaitingFor != null)
		{
			finishDrops();
		}
		if (!results.isOpen())
		{
			return;
		}
		ResultView shown = results.view();
		if (shown != null && shown.drops != null && shown.priceVersion != assets.getPrices().version()
			&& shown.refreshPrices(assets.getPrices()))
		{
			// New prices (a field compare until they come): the drop rows' text and order are made once.
			measure(shown);
		}
		int top = Math.max(bounds.y + 40, routePanelRect.isEmpty() ? 0 : routePanelRect.y + routePanelRect.height + 8);
		results.layout(bounds, top, buttons.block().isEmpty() ? bounds.x + bounds.width : buttons.block().x);
		results.paint(g, mouse, now);
	}

	/** A click that began and ended on the results panel. */
	private void clickResults(int x, int y, long now)
	{
		ResultsPanel.Hit hit = results.hit(x, y);
		ResultView view = results.view();
		if (view == null)
		{
			return;
		}
		switch (hit.part)
		{
			case CLOSE:
				results.close();
				break;
			case BACK:
				results.back();
				if (results.view() != null)
				{
					measure(results.view());
				}
				break;
			case TAB:
				view.selectTab(hit.index);
				break;
			case SORT:
				measure(view);
				ResultView.sort(view.current(), view.current().sorts.get(hit.index));
				break;
			case ROW:
				goToRow(view.current().rows.get(hit.index), now);
				break;
			default:
				break;
		}
	}

	/** Takes the map to a row of the panel and marks its places; a row that stands for another result opens that in the panel. */
	private void goToRow(ResultView.Row row, long now)
	{
		if (viewer == null)
		{
			return;
		}
		// A drop row of an item has no place of its own: it only opens that item.
		MapInfo target = row.mapId < 0 ? null : assets.getCatalog().byId(row.mapId);
		if (target == null && row.drop == null)
		{
			return;
		}
		if (target != null)
		{
			viewer.setFollowing(false);
			goTo(target, row.x, row.y, now);
			zoomForJump(Math.max(viewer.targetZoom(), FOUND_MIN_ZOOM));
			placesKey = "";
			foundNumber = 0;
			foundOf = 0;
			found = new SearchEntry(SearchKind.PIN, row.label, row.count, row.mapId, row.x, row.y, row.mark);
			foundSince = now;
		}
		if (row.follow != null)
		{
			ResultView next = makeView(row.follow);
			if (next != null)
			{
				measure(next);
				results.open(next);
				noteWaiting(row.follow, next);
			}
		}
	}

	/** The map the player is on or nearest to (as when the map opens), or -1, for choosing the nearest place of a kind. */
	private int kindPlayerMap()
	{
		if (lastPlayer == null)
		{
			return -1;
		}
		MapInfo map = assets.getCatalog().mapNear(lastPlayer.getTile().getX(), lastPlayer.getTile().getY(), NEAR_MAP_TILES);
		return map == null ? -1 : map.getId();
	}

	/** Draws the game's icons of a kind at every zoom for a while, as a click on the game's map key does. */
	private void revealKind(String kindId)
	{
		KindIndex.Kind kind = assets.getKinds().byId(kindId);
		if (kind == null || kind.getCategory() == null)
		{
			return;
		}
		List<Integer> numbers = assets.getElements().categoriesNamed(kind.getCategory());
		int[] categories = new int[numbers.size()];
		for (int i = 0; i < categories.length; i++)
		{
			categories[i] = numbers.get(i);
		}
		icons().reveal(categories, System.currentTimeMillis() + FOUND_MILLIS + REVEAL_AFTER_MILLIS);
	}

	/** The result with several places that was last gone to, to tell a second click on it from a click on another. */
	private String placesKey = "";
	private List<double[]> placesOrder = Collections.emptyList();
	private int placesAt;
	private long placesSince;
	/** Which place of how many the highlighted result is, or 0 when it has just the one. */
	private int foundNumber;
	private int foundOf;

	/** A second click on the same result within this long goes to the next place, not back to the first. */
	private static final long NEXT_PLACE_MILLIS = 90_000;

	/**
	 * For a result with several places, such as every spirit tree patch, the first click goes to the one nearest the
	 * player and each click on the same result after that goes to the next nearest, round to the first again. The
	 * highlight says which place it is of how many. A result with one place is unchanged.
	 */
	private SearchEntry chooseAmongPlaces(SearchEntry entry, long now)
	{
		List<double[]> spots = entry.getAlsoAt();
		if (spots.size() < 2)
		{
			placesKey = "";
			foundNumber = 0;
			foundOf = 0;
			return entry;
		}
		String key = entry.getName() + "|" + entry.getMapId() + "|" + spots.size();
		if (key.equals(placesKey) && found != null && now - placesSince < NEXT_PLACE_MILLIS)
		{
			placesAt = (placesAt + 1) % placesOrder.size();
		}
		else
		{
			placesKey = key;
			placesAt = 0;
			placesOrder = nearestFirst(spots, entry, lastPlayer);
		}
		placesSince = now;
		double[] spot = placesOrder.get(placesAt);
		foundNumber = placesAt + 1;
		foundOf = placesOrder.size();
		return new SearchEntry(entry.getKind(), entry.getName(), entry.getDetail(), entry.getMapId(), spot[0], spot[1],
			entry.getAlsoAt());
	}

	/** The places of a result, nearest the player first when the player is on its map, else as the result gives them. */
	static List<double[]> nearestFirst(List<double[]> spots, SearchEntry entry,
		@Nullable PlayerPosition player)
	{
		List<double[]> order = new ArrayList<>(spots);
		if (player != null)
		{
			order.sort(Comparator.comparingDouble(tile -> Math.hypot(tile[0] - player.getX(), tile[1] - player.getY())));
		}
		return order;
	}

	/** Goes to a result, as a click on it does. For tests. */
	void activateForTest(SearchEntry entry, long now)
	{
		activate(entry, now);
	}

	/** Which place of how many the highlighted result is, as {number, count}. For tests. */
	int[] foundPlace()
	{
		return new int[] {foundNumber, foundOf};
	}

	/**
	 * For a result with several places, such as every bank in a town, the one nearest the player when
	 * the player is on the same map, so the jump goes to the closest. Other results are unchanged.
	 */
	static SearchEntry nearestTo(SearchEntry entry, @Nullable PlayerPosition player)
	{
		if (player == null || entry.getAlsoAt().size() < 2)
		{
			return entry;
		}
		double[] best = null;
		double bestDistance = Double.MAX_VALUE;
		for (double[] tile : entry.getAlsoAt())
		{
			double distance = Math.hypot(tile[0] - player.getX(), tile[1] - player.getY());
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = tile;
			}
		}
		if (best == null || (best[0] == entry.getX() && best[1] == entry.getY()))
		{
			return entry;
		}
		return new SearchEntry(entry.getKind(), entry.getName(), entry.getDetail(), entry.getMapId(), best[0], best[1],
			entry.getAlsoAt());
	}

	/** Brings the map back to the player, switching map if they are on a different one. */
	private void locate(@Nullable PlayerPosition position)
	{
		if (position == null)
		{
			say("Your position is not known yet.");
			return;
		}
		MapInfo playerMap = assets.getCatalog().mapNear(position.getTile().getX(), position.getTile().getY(), NEAR_MAP_TILES);
		if (playerMap == null)
		{
			say("You are somewhere this map has no picture of, such as inside an instance.");
			return;
		}
		if (viewer.getMap() == null || viewer.getMap().getId() != playerMap.getId())
		{
			viewer.showMap(playerMap, position.getX(), position.getY());
		}
		followName = null;
		viewer.setFollowing(true);
		lastPlayerFloor = -1;
	}

	/** The line of text now along the bottom, or null. For tests. */
	String notice()
	{
		return routeNotice;
	}

	/** Puts a line of text along the bottom for a few seconds. */
	private void say(String text)
	{
		routeNotice = text;
		routeNoticeSince = lastFrame;
	}

	/** True when this map has floors above the ground to show. Only the surface does. */
	private boolean hasFloors(@Nullable MapInfo map)
	{
		return map != null && map.getId() == MapInfo.SURFACE && assets.getFloors() > 0;
	}

	/** Moves to the floor the player is on whenever the player changes floor, and when the map opens. */
	private void trackFloor(@Nullable PlayerPosition position)
	{
		if (!hasFloors(viewer.getMap()))
		{
			viewer.setFloor(0);
			return;
		}
		if (position == null || !playerIsOnViewedMap(position))
		{
			return;
		}
		int floor = Math.max(0, Math.min(assets.getFloors(), position.getTile().getPlane()));
		if (floor != lastPlayerFloor)
		{
			lastPlayerFloor = floor;
			viewer.setFloor(floor);
		}
	}

	/** A small tag to the left of a button the mouse is on, saying what it does. */
	private void drawTip(Graphics2D g, @Nullable MapButtons.Button button)
	{
		if (button == null)
		{
			return;
		}
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		Rectangle rect = buttons.boundsOf(button);
		String tip = button.tip();
		// To the left of the whole stack of buttons, so it never covers one, and inside the map: a tag too wide for the room
		// there is wrapped onto more lines.
		Rectangle block = buttons.block();
		int room = Math.max(40, block.x - 6 - (lastBounds.x + Box.EDGE) - 16);
		List<String> lines = Box.wrap(tip, metrics, room);
		int width = Box.widest(metrics, lines) + 16;
		int height = lines.size() * metrics.getHeight() + 8;
		int x = Math.max(lastBounds.x + 2, block.x - width - 6);
		int top = lastBounds.y + Box.EDGE;
		int y = Math.max(top, Math.min(rect.y + (rect.height - height) / 2, lastBounds.y + lastBounds.height - Box.EDGE - height));
		Box.lines(g, metrics, lines, x, y, width, height);
	}

	/** The floor buttons, and a label saying which floor is on show. */
	private void drawFloors(Graphics2D g, @Nullable Point mouse)
	{
		if (!floorButtons.isShown())
		{
			return;
		}
		floorButtons.paint(g, viewer.getFloor(), assets.getFloors(),
			mouse == null ? null : floorButtons.hit(mouse.x, mouse.y));
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		String text = FloorButtons.name(viewer.getFloor());
		Rectangle pair = floorButtons.bounds();
		int labelX = pair.x - metrics.stringWidth(text) - 14;
		Rectangle label = new Rectangle(labelX - 6, pair.y, metrics.stringWidth(text) + 12, pair.height);
		if (lastBounds.contains(label) && !buttons.covers(label))
		{
			// Left out where the room beside the pair is another button's or off the map, as on a very small map.
			drawLabel(g, metrics, text, labelX, pair.y + pair.height / 2 + metrics.getAscent() / 2 - 1);
		}
	}

	private boolean playerIsOnViewedMap(PlayerPosition position)
	{
		MapInfo map = viewer.getMap();
		return map != null && map.contains(position.getTile().getX(), position.getTile().getY());
	}

	// ------------------------------------------------------------------
	// Pins, the ruler, the context menu, and what other plugins asked for
	// ------------------------------------------------------------------

	/** How far from a map's imagery a player may be and still open on that map, in tiles: three squares. */
	private static final int NEAR_MAP_TILES = 192;

	/** How long a clicked kind of icon flashes. */
	private static final long FLASH_MILLIS = 8_000;

	/** How long the icons of a clicked kind stay drawn at every zoom once the flashing has faded. */
	private static final long REVEAL_AFTER_MILLIS = 30_000;

	/** A click on the game's map key flashes that kind of icon here, and a second click puts it out. */
	private void keyClicked(int x, int y, long now)
	{
		Integer category = KeyEntries.categoryAt(client, x, y, assets.getElements());
		if (category == null)
		{
			return;
		}
		boolean same = category == flashCategory && now - flashSince < FLASH_MILLIS;
		flashCategory = same ? 0 : category;
		flashSince = now;
		revealFlashing();
		flashExtra = flashCategory == 0 ? Collections.emptyList() : extraSpotsFor(assets.getElements().categoryName(flashCategory));
	}

	/** Draws the icons of the flashing kind at every zoom for a while after the flashing, and puts that out when the flashing is put out. */
	private void revealFlashing()
	{
		MapElements elements = assets.getElements();
		String name = flashCategory == 0 ? null : elements.categoryName(flashCategory);
		List<Integer> numbers = name == null ? Collections.<Integer>emptyList() : elements.categoriesNamed(name);
		int[] categories = new int[numbers.size()];
		for (int i = 0; i < categories.length; i++)
		{
			categories[i] = numbers.get(i);
		}
		icons().reveal(categories, System.currentTimeMillis() + FLASH_MILLIS + REVEAL_AFTER_MILLIS);
	}

	/** Spots this plugin knows of that the game's own icons for a category leave out, such as boats and gliders. */
	private List<SearchEntry> extraSpotsFor(String categoryName)
	{
		if (categoryName == null || !categoryName.equalsIgnoreCase("Transportation"))
		{
			return Collections.emptyList();
		}
		List<SearchEntry> spots = new ArrayList<>();
		for (SearchEntry entry : assets.getSearchEntries())
		{
			if (entry.getKind() == SearchKind.TRANSPORT)
			{
				spots.add(entry);
			}
		}
		return spots;
	}

	private void drawRing(Graphics2D g, double sx, double sy, double phase, float fade)
	{
		double radius = 9 + 13 * phase;
		if (Motion.on())
		{
			g.setComposite(AlphaComposite.SrcOver.derive(fade * (float) (1 - phase * 0.7)));
			g.setStroke(new BasicStroke(3f));
			g.setColor(FLASH);
			g.draw(circle(sx, sy, radius));
		}
		g.setComposite(AlphaComposite.SrcOver.derive(fade));
		g.setStroke(new BasicStroke(2f));
		g.setColor(Color.BLACK);
		g.draw(circle(sx, sy, 9.5));
		g.setColor(FLASH);
		g.draw(circle(sx, sy, 8.5));
	}

	private static final Color FLASH = new Color(255, 214, 0);

	/**
	 * Rings that pulse around every icon of the kind clicked in the map key, and a plate saying what is
	 * flashing and how many there are on this map. They show at any zoom, so a kind of icon can be found from far out.
	 */
	private void drawFlash(Graphics2D g, ViewTransform view, MapInfo map, long now)
	{
		if (flashCategory == 0)
		{
			return;
		}
		if (now - flashSince > FLASH_MILLIS)
		{
			flashCategory = 0;
			return;
		}
		double phase = ((now - flashSince) % 900) / 900.0;
		float fade = (float) Math.min(1.0, (FLASH_MILLIS - (now - flashSince)) / 1500.0);
		int count = 0;
		Composite old = g.getComposite();
		try
		{
			for (MapElements.Spot spot : assets.getElements().spotsOn(map.getId()))
			{
				if (spot.getCategory() != flashCategory)
				{
					continue;
				}
				count++;
				double sx = view.screenX(spot.getX());
				double sy = view.screenY(spot.getY());
				if (!view.contains(sx, sy))
				{
					continue;
				}
				drawRing(g, sx, sy, phase, fade);
			}
			for (SearchEntry extra : flashExtra)
			{
				if (extra.getMapId() != map.getId())
				{
					continue;
				}
				count++;
				double sx = view.screenX(extra.getX());
				double sy = view.screenY(extra.getY());
				if (view.contains(sx, sy))
				{
					drawRing(g, sx, sy, phase, fade);
				}
			}
		}
		finally
		{
			g.setComposite(old);
		}
		String name = assets.getElements().categoryName(flashCategory);
		if (name != null)
		{
			g.setFont(Palette.BODY);
			FontMetrics metrics = g.getFontMetrics();
			String text = name + ": " + (count == 0 ? "none on this map" : count + (count == 1 ? " place" : " places"));
			int x = lastBounds.x + (lastBounds.width - metrics.stringWidth(text)) / 2;
			drawLabel(g, metrics, text, x, lastBounds.y + lastBounds.height - 34);
		}
	}

	/** Rebuilds the search index when the pins changed, so pins can be found by name. */
	/** What the search was last told about finished quests: 0 for none, otherwise the progress version times two plus one. */
	private int searchProgressKey;

	/**
	 * With the setting for it on, quests the player has finished are left out of the search results. The results are worked out again
	 * only when the setting or the player's progress changes.
	 */
	private void refreshSearchFilter()
	{
		Unlocks progress = assets.getUnlocks();
		boolean hide = progress != null && config.hideFinishedQuests();
		int key = hide ? progress.version() * 2 + 1 : 0;
		if (key == searchProgressKey)
		{
			return;
		}
		searchProgressKey = key;
		searchBox.setFilter(hide ? entry -> entry.getKind() != SearchKind.QUEST || !progress.finished(entry.getQuest()) : null);
	}

	private void refreshSearch()
	{
		refreshSearchFilter();
		int version = pins.getVersion();
		int routeVersion = routes.getVersion();
		if (version == searchPinVersion && routeVersion == searchRouteVersion)
		{
			return;
		}
		searchPinVersion = version;
		searchRouteVersion = routeVersion;
		pinList = new ArrayList<>(pins.all());
		pinById.clear();
		for (Pin pin : pinList)
		{
			pinById.put(pin.getId(), pin);
		}
		// The player's own places and routes are found like everything else, and the index is made again only when either store
		// changes (added, renamed, edited, removed), so a result never stands for something gone (round 48).
		List<SearchEntry> entries = new ArrayList<>(assets.getSearchEntries());
		entries.addAll(OwnSearch.entries(pinList, routes.all(), MapSurface::kindText));
		searchBox.setIndex(new SearchIndex(entries));
	}

	/** Brings the map to a place another plugin asked for. */
	private void applyFocus(long now)
	{
		ExternalOverlays.FocusRequest request = external.takeFocus();
		if (request == null || viewer == null)
		{
			return;
		}
		WorldPoint point = request.getPoint();
		MapInfo target = request.isExact() ? assets.getCatalog().mapFor(point.getX(), point.getY())
			: assets.getCatalog().mapNear(point.getX(), point.getY(), NEAR_MAP_TILES);
		if (target == null)
		{
			// The map does not move, and an asker that wanted an answer is told so, so it can use its own way instead.
			external.answerFocus(request, false, "", "", "no_map");
			return;
		}
		external.answerFocus(request, true, target.getName(), ExternalOverlays.kindOf(target.getId()), "ok");
		lastFocusMillis = now;
		viewer.setFollowing(false);
		goTo(target, point.getX() + 0.5, point.getY() + 0.5, now);
		double zoom = request.getZoom() > 0 ? request.getZoom()
			: Math.max(viewer.targetZoom(), FOUND_MIN_ZOOM);
		zoomForJump(zoom);
		String name = request.getName().isEmpty() ? "Requested place" : request.getName();
		found = new SearchEntry(SearchKind.PIN, name, null, target.getId(), point.getX() + 0.5, point.getY() + 0.5);
		foundSince = now;
	}

	/** True when another plugin asked this map to centre on something within the last {@code windowMillis}. */
	public boolean focusedWithin(long nowMillis, long windowMillis)
	{
		return nowMillis - lastFocusMillis < windowMillis;
	}

	/** True when the map being shown has pictures of this tile. */
	public boolean showsTile(int x, int y)
	{
		return viewer != null && viewer.getMap() != null && viewer.getMap().contains(x, y);
	}

	/** Takes the map to a place, on whichever map holds it, keeping the zoom. Used after the game's own map list. */
	public void focusOn(int x, int y)
	{
		MapInfo target = assets.getCatalog().mapNear(x, y, NEAR_MAP_TILES);
		if (target == null || viewer == null)
		{
			return;
		}
		viewer.setFollowing(false);
		viewer.showMap(target, x + 0.5, y + 0.5);
	}

	// ------------------------------------------------------------------
	// Other plugins' markers in the menu, and following a party member
	// ------------------------------------------------------------------


	/** How long a followed member may be missing from the points before following ends, in milliseconds. */
	static final long FOLLOW_GRACE_MILLIS = 1_500;

	/** The marker a menu was opened on, or null. */
	private ExternalMarker menuMarker;

	/** The display name of the party member being followed, or null. */
	private String followName;
	private long followSeenAt;
	private double memberX;
	private double memberY;


	/** The menu entries for the marker under the pointer: the ones its plugin added, Focus on for a jump, Follow for a party member. */
	private void addMarkerItems(List<ContextMenu.Item> items, ViewTransform view, MapInfo map, int x, int y)
	{
		for (Candidate candidate : candidates(view, map, x, y))
		{
			if (!(candidate.what instanceof ExternalMarker))
			{
				continue;
			}
			ExternalMarker marker = (ExternalMarker) candidate.what;
			menuMarker = marker;
			for (String option : marker.getMenu())
			{
				items.add(new ContextMenu.Item(ContextMenu.Action.MARKER_OPTION, option));
			}
			if (marker.getJumpTarget() != null)
			{
				items.add(new ContextMenu.Item(ContextMenu.Action.FOCUS_MARKER, "Focus on " + candidate.title()));
			}
			if (marker.isParty() && !isLinedUp())
			{
				items.add(marker.getName().equals(followName)
					? new ContextMenu.Item(ContextMenu.Action.STOP_FOLLOWING, "Stop following " + marker.getName())
					: new ContextMenu.Item(ContextMenu.Action.FOLLOW_MEMBER, "Follow " + marker.getName()));
			}
			return;
		}
		if (followName != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.STOP_FOLLOWING, "Stop following " + followName));
		}
	}

	/** Keeps the map on a party member until the map is moved, the member goes, or something else takes the map. */
	void startFollowing(String name)
	{
		if (name == null || name.isEmpty() || viewer == null)
		{
			return;
		}
		followName = name;
		followSeenAt = lastFrame;
		viewer.setFollowing(true);
		say("Following " + name + ". Drag the map to stop.");
	}

	private void stopFollowing(@Nullable String why)
	{
		if (followName == null)
		{
			return;
		}
		followName = null;
		if (viewer != null)
		{
			viewer.setFollowing(false);
		}
		if (why != null)
		{
			say(why);
		}
	}

	/** The party member being followed, or null. For tests. */
	@Nullable
	String following()
	{
		return followName;
	}

	/**
	 * Works out where a followed party member is, once a frame. True when the camera should follow {@link #memberX} and
	 * {@link #memberY}. Only the points the Party plugin put on the game's map are read, the same reading the markers come from, and
	 * nothing is made per frame. Following ends when the camera has stopped following (the player dragged the map, or a search, a
	 * focus or Locate me took it), in line up mode, or when the member has been gone for {@link #FOLLOW_GRACE_MILLIS}.
	 */
	private boolean followMember(long now)
	{
		if (followName == null)
		{
			return false;
		}
		if (!viewer.isFollowing() || isLinedUp())
		{
			followName = null;
			return false;
		}
		ExternalMarker member = null;
		if (nativePoints != null && config.showOtherPluginPoints())
		{
			List<ExternalMarker> points = nativePoints.markers(now);
			for (int i = 0; i < points.size(); i++)
			{
				ExternalMarker marker = points.get(i);
				if (marker.isParty() && marker.getName().equals(followName))
				{
					member = marker;
					break;
				}
			}
		}
		MapInfo target = member == null ? null : assets.getCatalog().mapNear((int) member.getX(), (int) member.getY(), NEAR_MAP_TILES);
		if (member == null || target == null)
		{
			if (now - followSeenAt > FOLLOW_GRACE_MILLIS)
			{
				stopFollowing(member == null ? followName + " is no longer on the map, so following stopped."
					: followName + " is somewhere this map has no picture of, so following stopped.");
				return false;
			}
			// Held still for a moment: the points can be caught mid change, and a member can be between two places.
			memberX = viewer.getX();
			memberY = viewer.getY();
			return true;
		}
		followSeenAt = now;
		MapInfo exact = assets.getCatalog().mapFor((int) member.getX(), (int) member.getY());
		MapInfo shown = viewer.getMap();
		if (shown == null || !shown.contains((int) member.getX(), (int) member.getY()) && (exact != null || shown.getId() != target.getId()))
		{
			// The member went to another of this plugin's maps, such as down a ladder: that map is shown, and following goes on.
			viewer.showMap(exact != null ? exact : target, member.getX(), member.getY());
		}
		memberX = member.getX();
		memberY = member.getY();
		return true;
	}

	private void openMenu(int x, int y)
	{
		SearchBar.Hit resultHit = searchBar.hit(x, y);
		if (resultHit.getRow() >= 0)
		{
			openResultMenu(resultHit.getRow(), x, y);
			return;
		}
		menuPlane = -1;
		menuWiki = null;
		menuMarker = null;
		menuWikiSearch = null;
		MapInfo map = viewer.getMap();
		if (map == null || isControl(x, y))
		{
			return;
		}
		ViewTransform view = currentView();
		double wx = view.worldX(x);
		double wy = view.worldY(y);
		int tx = (int) Math.floor(wx);
		int ty = (int) Math.floor(wy);

		Pin pin = pinAt(view, map, x, y);
		menuSave = pin == null ? saveCandidateAt(view, map, x, y) : null;
		menuPinId = pin == null ? 0 : pin.getId();

		List<ContextMenu.Item> items = new ArrayList<>();
		if (pin != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.ROUTE_TO_PIN, "Route to " + pin.getName()));
			items.add(new ContextMenu.Item(ContextMenu.Action.EDIT_PIN, "Rename in side panel"));
			items.add(new ContextMenu.Item(ContextMenu.Action.HIDE_PIN, "Hide " + pin.getName() + " (still used by routes)"));
			items.add(new ContextMenu.Item(ContextMenu.Action.REMOVE_PIN, "Remove " + pin.getName()));
		}
		items.add(new ContextMenu.Item(ContextMenu.Action.ROUTE, actions.getRouteStart() == null
			? "Route here (Shortest Path)" : "Route here from the set start (Shortest Path)"));
		if (!actions.getRouteTargets().isEmpty())
		{
			// As on Shortest Path's own map: a start of your choosing, and more than one target.
			items.add(new ContextMenu.Item(ContextMenu.Action.SET_ROUTE_START, "Set route start here"));
			if (actions.getRouteTargets().size() < MapActions.MAX_TARGETS)
			{
				items.add(new ContextMenu.Item(ContextMenu.Action.ADD_ROUTE_TARGET,
					"Add route target " + (actions.getRouteTargets().size() + 1) + " here"));
			}
		}
		if (actions.getRouteTarget() != null || !external.hops().isEmpty() || routes.active() != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.CLEAR_ROUTE, "Clear route"));
		}
		items.add(new ContextMenu.Item(ContextMenu.Action.COPY_TILE, "Copy tile  " + tx + ", " + ty));
		items.add(new ContextMenu.Item(ContextMenu.Action.NEAREST_TELEPORTS, "Nearest teleports"));
		HoverCard wikiCard = pin == null ? hotspotCardAt(view, map, x, y) : null;
		menuWiki = wikiCard != null && HoverCardPainter.hasPage(wikiCard) ? wikiCard.getWiki() : null;
		menuWikiSearch = wikiCard != null && HoverCardPainter.isSearch(wikiCard) ? wikiCard.getWikiSearch() : null;
		if (menuWiki != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.OPEN_WIKI, "Open wiki page: " + menuWiki));
		}
		else if (menuWikiSearch != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.SEARCH_WIKI, "Search the wiki for " + menuWikiSearch));
		}
		if (pin == null)
		{
			if (menuSave != null)
			{
				items.add(new ContextMenu.Item(ContextMenu.Action.SAVE_PLACE, "Save place: " + menuSave.name));
			}
			items.add(new ContextMenu.Item(ContextMenu.Action.ADD_PIN, "Add pin here"));
		}
		addMarkerItems(items, view, map, x, y);

		// The menu's width needs font metrics, which a throwaway image can supply.
		Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
		probe.setFont(ContextMenu.font());
		int width = ContextMenu.widthFor(items, probe.getFontMetrics());
		probe.dispose();

		menu.open(items, x, y, width, lastBounds, new double[] {tx, ty});
	}

	/** The floor a menu's route or save is for: a search result's own, or -1 to use the floor being shown. */
	private int menuPlane = -1;

	/** The search result a menu was opened on. */
	private SearchEntry menuEntry;

	/** The menu for a search result: go to it, save it as a place, or route to it. */
	private void openResultMenu(int row, int x, int y)
	{
		int index = searchBox.getScrollTop() + row;
		if (index < 0 || index >= searchBox.getResults().size() || viewer == null)
		{
			return;
		}
		SearchEntry entry = searchBox.getResults().get(index);
		if (entry.getKind().isOwn() && actOnOwn(entry))
		{
			return;
		}
		menuEntry = entry;
		menuPlane = 0;
		menuPinId = 0;
		menuWiki = config.showWikiButtons() ? entry.getWiki() : null;
		menuWikiSearch = config.showWikiButtons() && menuWiki == null ? wikiSearchFor(entry) : null;
		menuSave = new SaveCandidate(entry.getName(), kindOf(entry.getKind()), entry.getMapId(), entry.getX(), entry.getY());
		List<ContextMenu.Item> items = new ArrayList<>();
		items.add(new ContextMenu.Item(ContextMenu.Action.SHOW_RESULT, "Show on the map"));
		items.add(new ContextMenu.Item(ContextMenu.Action.SAVE_PLACE, "Save place: " + entry.getName()));
		if (menuWiki != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.OPEN_WIKI, "Open wiki page: " + menuWiki));
		}
		else if (menuWikiSearch != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.SEARCH_WIKI, "Search the wiki for " + menuWikiSearch));
		}
		MapInfo surface = assets.getCatalog().surface();
		if (surface != null && entry.getMapId() == surface.getId())
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.ROUTE, "Route here (Shortest Path)"));
		}
		items.add(new ContextMenu.Item(ContextMenu.Action.COPY_TILE, "Copy tile  " + (int) Math.floor(entry.getX()) + ", "
		+ (int) Math.floor(entry.getY())));
		Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
		probe.setFont(ContextMenu.font());
		int width = ContextMenu.widthFor(items, probe.getFontMetrics());
		probe.dispose();
		menu.open(items, x, y, width, lastBounds, new double[] {entry.getX(), entry.getY()});
	}

	/** What a menu may offer to save: the reference place the mouse is on, or the place last searched for. */
	@RequiredArgsConstructor
	private static final class SaveCandidate
	{
		final String name;
		final String kind;
		final int mapId;
		final double x;
		final double y;
	}

	@Nullable
	private SaveCandidate saveCandidateAt(ViewTransform view, MapInfo map, int x, int y)
	{
		if (found != null && found.getMapId() == map.getId()
			&& Math.hypot(view.screenX(found.getX()) - x, view.screenY(found.getY()) - y) <= FOUND_CLICK_PIXELS
			&& found.getKind() != SearchKind.TILE)
		{
			return new SaveCandidate(found.getName(), kindOf(found.getKind()), map.getId(), found.getX(), found.getY());
		}
		Hotspot hotspot = hoveredHotspot(view, map, new Point(x, y));
		if (hotspot == null || hotspot.getCard().getTitle().isEmpty())
		{
			return null;
		}
		return new SaveCandidate(hotspot.getCard().getTitle(), kindOf(hotspot.getKind()), map.getId(), hotspot.getX(),
			hotspot.getY());
	}

	/** How close to the marker on a found place a right click counts as being on it, in pixels. */
	private static final double FOUND_CLICK_PIXELS = 20;

	/** The kind a saved place gets from the kind of search result it came from. */
	static String kindOf(SearchKind kind)
	{
		switch (kind)
		{
			case SHOP:
			case NPC:
			case MONSTER:
			case QUEST:
			case TRANSPORT:
			case TELEPORT:
			case ITEM:
				return kind.name().toLowerCase(Locale.ROOT);
			default:
				return kind == SearchKind.PIN || kind == SearchKind.TILE ? "pin" : "place";
		}
	}

	/** The kind a saved place gets from the kind of reference place it was saved from. */
	static String kindOf(Hotspot.Kind kind)
	{
		switch (kind)
		{
			case SHOP:
				return "shop";
			case TRANSPORT:
				return "transport";
			case TELEPORT:
				return "teleport";
			default:
				return "place";
		}
	}

	/** The floor shown, which is the floor a place saved or routed to here is on. */
	private int planeNow()
	{
		return hasFloors(viewer.getMap()) ? viewer.getFloor() : 0;
	}

	/**
	 * A right click on one of the player's own things in the results (round 48): a saved place is routed to, as its own Route to entry
	 * does, and a saved route opens its editor in the side panel, as its Edit button does. False when the thing is gone, so the
	 * ordinary result menu opens instead.
	 */
	private boolean actOnOwn(SearchEntry entry)
	{
		if (entry.getKind() == SearchKind.PIN)
		{
			Pin pin = pins.get(entry.getOwnId());
			if (pin == null)
			{
				return false;
			}
			routeToPin(pin);
			searchBox.blur();
			return true;
		}
		if (entry.getKind() == SearchKind.ROUTE && routes.get(entry.getOwnId()) != null)
		{
			launcher.editRoute(entry.getOwnId());
			searchBox.blur();
			return true;
		}
		return false;
	}

	/** Starts a saved route the way the side panel's Start button does, and says on the map what happened. */
	private void startRoute(long routeId)
	{
		SavedRoute route = routes.get(routeId);
		if (route == null)
		{
			return;
		}
		routeNotice = RouteStart.start(route, routes, pins, actions.shortestPathStatus()).getMessage();
		routeNoticeSince = lastFrame;
	}

	/** Asks Shortest Path for a route to a saved place. A route being followed gives way to it. */
	private void routeToPin(Pin pin)
	{
		routes.stopAll();
		WorldPoint at = RouteNavigator.pointOf(pin);
		routeNotice = noticeFor(actions.routeTo(at));
		routeNoticeSince = lastFrame;
	}

	private void perform(ContextMenu.Item item)
	{
		Object subject = menu.getSubject();
		if (!(subject instanceof double[]) || viewer.getMap() == null)
		{
			return;
		}
		double[] at = (double[]) subject;
		int tx = (int) at[0];
		int ty = (int) at[1];
		int mapId = viewer.getMap().getId();
		switch (item.getAction())
		{
			case ROUTE:
				routes.stopAll();
				WorldPoint destination = new WorldPoint(tx, ty, menuPlane >= 0 ? menuPlane : planeNow());
				routeNotice = noticeFor(actions.retarget(destination));
				routeNoticeSince = lastFrame;
				break;
			case SET_ROUTE_START:
			{
				routes.stopAll();
				WorldPoint from = new WorldPoint(tx, ty, menuPlane >= 0 ? menuPlane : planeNow());
				routeNotice = noticeFor(actions.setStart(from));
				routeNoticeSince = lastFrame;
				break;
			}
			case ADD_ROUTE_TARGET:
			{
				routes.stopAll();
				WorldPoint more = new WorldPoint(tx, ty, menuPlane >= 0 ? menuPlane : planeNow());
				routeNotice = noticeFor(actions.addTarget(more));
				routeNoticeSince = lastFrame;
				break;
			}
			case CLEAR_ROUTE:
				routes.stopAll();
				actions.clearRoute();
				routeNotice = null;
				break;
			case COPY_TILE:
				actions.copy(tx + ", " + ty);
				break;
			case NEAREST_TELEPORTS:
				pinnedCard = nearestTeleports(mapId, tx, ty);
				pinnedAt = new Point(menu.getBounds().x, menu.getBounds().y);
				pinnedSince = lastFrame;
				break;
			case ADD_PIN:
				pins.add("Pin at " + tx + ", " + ty, "pin", mapId, tx + 0.5, ty + 0.5, planeNow());
				break;
			case SAVE_PLACE:
				if (menuSave != null)
				{
					pins.add(menuSave.name, menuSave.kind, menuSave.mapId, menuSave.x, menuSave.y, menuPlane >= 0 ? menuPlane : planeNow());
					routeNotice = "Saved " + menuSave.name + " to your places.";
					routeNoticeSince = lastFrame;
				}
				break;
				case SHOW_RESULT:
				activate(menuEntry, lastFrame);
				break;
			case OPEN_WIKI:
				openWiki(menuWiki, true);
				break;
			case SEARCH_WIKI:
				openWiki(menuWikiSearch, false);
				break;
			case ROUTE_TO_PIN:
			{
				Pin pin = pins.get(menuPinId);
				if (pin != null)
				{
					routeToPin(pin);
				}
				break;
			}
			case EDIT_PIN:
				launcher.open(menuPinId);
				break;
			case HIDE_PIN:
				{
					Pin hidePin = pins.get(menuPinId);
					if (hidePin != null)
					{
						pins.restyle(hidePin.getId(), hidePin.getColor(), hidePin.getSize(), true);
					}
					break;
				}
			case REMOVE_PIN:
				pins.remove(menuPinId);
				break;
			case MARKER_OPTION:
				external.markerMenu(menuMarker, item.getLabel());
				break;
			case FOCUS_MARKER:
				if (menuMarker != null && menuMarker.getJumpTarget() != null)
				{
					focusOn(menuMarker.getJumpTarget().getX(), menuMarker.getJumpTarget().getY());
				}
				break;
			case FOLLOW_MEMBER:
				if (menuMarker != null)
				{
					startFollowing(menuMarker.getName());
				}
				break;
			case STOP_FOLLOWING:
				stopFollowing(null);
				break;
			default:
				break;
		}
	}

	// ------------------------------------------------------------------
	// Pins on the screen
	// ------------------------------------------------------------------

	/** True when a saved place is on the map being shown and, on the surface, on the floor being shown. */
	private boolean shown(Pin pin, MapInfo map)
	{
		return pin.getMap() == map.getId() && (!hasFloors(map) || pin.getPlane() == viewer.getFloor());
	}

	/** The drawn pin is this tall, and this wide at its widest, in pixels. */
	private static final double PIN_HEIGHT = 22;
	private static final double PIN_HALF_WIDTH = 9;

	/** True when a pin is drawn and can be pointed at: shown on this map and floor, not hidden, and pins are on. */
	private boolean pinVisible(Pin pin, MapInfo map)
	{
		return config.showPins() && !pin.isHidden() && shown(pin, map);
	}

	/**
	 * The pin whose drawn body is under a point, or null. Hit testing uses the drawn shape on the screen,
	 * so aiming at the round head of a pin works as well as aiming at its tip.
	 */
	@Nullable
	private Pin pinAt(ViewTransform view, @Nullable MapInfo map, int x, int y)
	{
		if (map == null)
		{
			return null;
		}
		Pin best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Pin pin : pinList)
		{
			if (!pinVisible(pin, map))
			{
				continue;
			}
			double scale = pin.getSize() / 100.0;
			double sx = view.screenX(pin.getX());
			double sy = view.screenY(pin.getY());
			if (Math.abs(x - sx) > PIN_HALF_WIDTH * scale + 2 || y < sy - PIN_HEIGHT * scale - 2 || y > sy + 4)
			{
				continue;
			}
			double d = Math.hypot(x - sx, y - (sy - PIN_HEIGHT * scale / 2));
			if (d < bestDistance)
			{
				bestDistance = d;
				best = pin;
			}
		}
		return best;
	}

	private static final Color ROUTE_ACTIVE = new Color(255, 196, 60, 235);
	private static final Color ROUTE_LOOKED_AT = new Color(130, 205, 255, 215);

	/** The saved routes being followed or looked at: a line through their places, in order, with a number at each. */
	private void drawSavedRoutes(Graphics2D g, ViewTransform view, MapInfo map)
	{
		g.setFont(Palette.TINY);
		FontMetrics metrics = g.getFontMetrics();
		SavedRoute following = routes.active();
		for (SavedRoute route : routes.drawn())
		{
			Color color = route == following ? ROUTE_ACTIVE : ROUTE_LOOKED_AT;
			List<Pin> stops = new ArrayList<>(route.getStops().size());
			for (long id : route.getStops())
			{
				Pin stop = pinById.get(id);
				if (stop != null)
				{
					stops.add(stop);
				}
			}
			int n = stops.size();
			for (int i = 1; i < n + (route.isLoop() && n > 1 ? 1 : 0); i++)
			{
				drawLeg(g, view, map, stops.get(i - 1), stops.get(i % n), color);
			}
			// The numbers are the stops' places in the whole route, wherever the other stops are.
			for (int i = 0; i < n; i++)
			{
				Pin stop = stops.get(i);
				double sx = view.screenX(stop.getX());
				double sy = view.screenY(stop.getY());
				if (shown(stop, map) && view.contains(sx, sy))
				{
					numbered(g, metrics, sx, sy + 11, 8, color, false, i + 1);
				}
			}
		}
	}

	/** A disc with a number in it: a stop of a saved route, or a jump of the route Shortest Path found, ringed. */
	private static void numbered(Graphics2D g, FontMetrics metrics, double x, double y, double r, Color fill, boolean ring, int number)
	{
		Ellipse2D disc = circle(x, y, r);
		g.setColor(fill);
		g.fill(disc);
		if (ring)
		{
			g.setColor(PLAYER_RING);
			g.setStroke(new BasicStroke(1.5f));
			g.draw(disc);
		}
		String text = String.valueOf(number);
		g.setColor(Color.BLACK);
		g.drawString(text, (float) (x - metrics.stringWidth(text) / 2.0), (float) (y + (metrics.getAscent() - metrics.getDescent()) / 2.0));
	}

	private void drawLeg(Graphics2D g, ViewTransform view, MapInfo map, Pin from, Pin to, Color color)
	{
		if (!shown(from, map) || !shown(to, map))
		{
			return;
		}
		double x1 = view.screenX(from.getX());
		double y1 = view.screenY(from.getY());
		double x2 = view.screenX(to.getX());
		double y2 = view.screenY(to.getY());
		g.setColor(color);
		g.setStroke(round(2.4f, 9f, 6f, 0f));
		g.draw(new Line2D.Double(x1, y1, x2, y2));
		double length = Math.hypot(x2 - x1, y2 - y1);
		if (length < 30)
		{
			return;
		}
		// An arrowhead at the middle shows which way the route runs.
		double mx = (x1 + x2) / 2;
		double my = (y1 + y2) / 2;
		double ux = (x2 - x1) / length;
		double uy = (y2 - y1) / length;
		Path2D arrow = new Path2D.Double();
		arrow.moveTo(mx + ux * 6, my + uy * 6);
		arrow.lineTo(mx - ux * 5 - uy * 5, my - uy * 5 + ux * 5);
		arrow.lineTo(mx - ux * 5 + uy * 5, my - uy * 5 - ux * 5);
		arrow.closePath();
		g.setStroke(new BasicStroke(1f));
		g.fill(arrow);
	}

	private void drawPins(Graphics2D g, ViewTransform view, MapInfo map)
	{
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		boolean labels = view.getPixelsPerTile() >= 2.5;
		for (Pin pin : pinList)
		{
			if (!pinVisible(pin, map))
			{
				continue;
			}
			double sx = view.screenX(pin.getX());
			double sy = view.screenY(pin.getY());
			if (!view.contains(sx, sy))
			{
				continue;
			}
			double f = pin.getSize() / 100.0;
			Path2D shape = new Path2D.Double();
			shape.moveTo(sx, sy);
			shape.curveTo(sx - 9 * f, sy - 10 * f, sx - 7 * f, sy - 22 * f, sx, sy - 22 * f);
			shape.curveTo(sx + 7 * f, sy - 22 * f, sx + 9 * f, sy - 10 * f, sx, sy);
			g.setColor(pin.getColor() == 0 ? PIN_FILL : new Color(pin.getColor()));
			g.fill(shape);
			g.setColor(PLAYER_RING);
			g.setStroke(new BasicStroke(1.5f));
			g.draw(shape);
			g.fill(new Ellipse2D.Double(sx - 2.5 * f, sy - 16.5 * f, 5 * f, 5 * f));
			if (labels)
			{
				drawLabel(g, metrics, pin.getName(), (int) (sx + 9 * f), (int) (sy - 10 * f));
			}
		}
	}

	private void drawExternalMarkers(Graphics2D g, ViewTransform view, MapInfo map)
	{
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		boolean labels = view.getPixelsPerTile() >= 3;
		for (ExternalMarker marker : allMarkers())
		{
			if (!onThisMap(map, marker.getX(), marker.getY()))
			{
				continue;
			}
			double sx = view.screenX(marker.getX());
			double sy = view.screenY(marker.getY());
			boolean snapped = false;
			if (!view.contains(sx, sy))
			{
				if (!marker.isSnapToEdge())
				{
					continue;
				}
				sx = snapX(view, sx);
				sy = snapY(view, sy);
				snapped = true;
			}
			if (marker.getIcon() != null)
			{
				int[] anchor = snapped ? null : marker.getAnchor();
				int left = anchor == null ? (int) sx - marker.getIcon().getWidth() / 2 : (int) sx - anchor[0];
				int top = anchor == null ? (int) sy - marker.getIcon().getHeight() / 2 : (int) sy - anchor[1];
				g.drawImage(marker.getIcon(), left, top, null);
			}
			else
			{
				Ellipse2D dot = circle(sx, sy, 6);
				g.setColor(marker.getColor());
				g.fill(dot);
				g.setColor(PLAYER_RING);
				g.setStroke(new BasicStroke(2f));
				g.draw(dot);
			}
			if (labels && !marker.getName().isEmpty())
			{
				drawLabel(g, metrics, marker.getName(), (int) sx + 9, (int) sy + 4);
			}
		}
	}

	/**
	 * True when a place belongs on this map: it is on the map's imagery, or no map has imagery of it (a spot underground the pictures leave
	 * out) and this is the nearest map within a few squares. The game's own map shows such a place, so this one does too.
	 */
	private boolean onThisMap(MapInfo map, double x, double y)
	{
		int tx = (int) x;
		int ty = (int) y;
		if (map.contains(tx, ty))
		{
			return true;
		}
		MapCatalog catalog = assets.getCatalog();
		if (catalog.mapFor(tx, ty) != null)
		{
			return false;
		}
		MapInfo near = catalog.mapNear(tx, ty, NEAR_MAP_TILES);
		return near != null && near.getId() == map.getId();
	}

	/** How far from the edge of the view a marker that snaps to the edge is kept, in pixels. */
	private static final int EDGE_MARGIN = 14;

	private static double snapX(ViewTransform view, double sx)
	{
		return Math.max(view.getLeft() + EDGE_MARGIN, Math.min(view.getLeft() + view.getWidth() - EDGE_MARGIN, sx));
	}

	private static double snapY(ViewTransform view, double sy)
	{
		return Math.max(view.getTop() + EDGE_MARGIN, Math.min(view.getTop() + view.getHeight() - EDGE_MARGIN, sy));
	}

	/** What other plugins asked this map to show, and what they put on the game's own map. */
	private List<ExternalMarker> allMarkers()
	{
		List<ExternalMarker> own = external.markers();
		if (nativePoints == null || !config.showOtherPluginPoints())
		{
			return own;
		}
		List<ExternalMarker> theirs = nativePoints.markers(System.currentTimeMillis());
		if (theirs.isEmpty())
		{
			return own;
		}
		List<ExternalMarker> both = new ArrayList<>(own);
		both.addAll(theirs);
		return both;
	}


	/** The markers shown from other plugins and from the game's map now, after the ones the player hid. For tests. */
	List<ExternalMarker> shownMarkers()
	{
		return allMarkers();
	}


	private void drawExternalLines(Graphics2D g, ViewTransform view, MapInfo map)
	{
		g.setFont(Palette.BODY);
		for (ExternalLine line : external.lines())
		{
			List<double[]> points = line.getPoints();
			if (points.size() < 2 || !map.contains((int) points.get(0)[0], (int) points.get(0)[1]))
			{
				continue;
			}
			g.setColor(line.getColor());
			if (line.isHop())
			{
				drawHop(g, view, points.get(0), points.get(1), line.getLabel());
				continue;
			}
			g.setStroke(round(2.5f));
			Path2D path = new Path2D.Double();
			path.moveTo(view.screenX(points.get(0)[0]), view.screenY(points.get(0)[1]));
			for (int i = 1; i < points.size(); i++)
			{
				path.lineTo(view.screenX(points.get(i)[0]), view.screenY(points.get(i)[1]));
			}
			g.draw(path);
		}
	}

	/** A single jump, such as a boat or a teleport, as a curve with an arrowhead and its name. */
	private void drawHop(Graphics2D g, ViewTransform view, double[] from, double[] to, String label)
	{
		double x1 = view.screenX(from[0]);
		double y1 = view.screenY(from[1]);
		double x2 = view.screenX(to[0]);
		double y2 = view.screenY(to[1]);
		double mx = (x1 + x2) / 2;
		double my = (y1 + y2) / 2 - Math.hypot(x2 - x1, y2 - y1) * 0.2;
		g.setStroke(round(2.2f, 7f, 5f, 0f));
		g.draw(new QuadCurve2D.Double(x1, y1, mx, my, x2, y2));
		g.setStroke(new BasicStroke(1f));
		g.fill(circle(x2, y2, 4));
		if (label != null && !label.isEmpty())
		{
			FontMetrics metrics = g.getFontMetrics();
			drawLabel(g, metrics, label, (int) mx - metrics.stringWidth(label) / 2, (int) my);
		}
	}

	/** How close, in tiles, the game's map must come to this map's centre before this map follows it again. */
	private static final double CAUGHT_UP_TILES = 1.5;

	/** How far, in tiles, this map's centre must have moved by itself between frames to count as moved here. */
	private static final double MOVED_HERE_TILES = 0.05;

	/** Frames this map waits for the game's map to arrive where it was sent before taking the game's place anyway. */
	private static final int CATCHING_UP_FRAMES = 240;

	/** Frames the game's map must show another map before this map shows it too. */
	private static final int MAP_CHANGE_FRAMES = 6;

	/** Where this map is, to the tile, is told to the game's map no more often than this. */
	private static final long MOVE_MILLIS = 60;

	/** Where this map looks and how it is zoomed, set by the player or by the game's map. */
	public void setNativeMap(NativeMap nativeMap)
	{
		this.nativeMap = nativeMap;
	}

	/**
	 * True when the game's map should be kept with this one: another plugin has had points on it at any time this session
	 * (such a plugin makes its points only near where the game's map looks, so at a new place there are none yet and the
	 * answer has to outlast them), this is one of the game's own maps (the wiki's extra maps are not places the game's map can look at), and the
	 * setting is on. False while no other plugin has ever had a point, so the game's map is then never touched.
	 */
	public boolean wantsGameMap()
	{
		MapInfo map = viewer.getMap();
		return config.syncGameMap() && nativePoints != null && nativePoints.seenAny() && map != null && map.getId() < 10000;
	}

	/** The world x this map is centred on. */
	public double centerX()
	{
		return viewer.getX();
	}

	/** The world y this map is centred on. */
	public double centerY()
	{
		return viewer.getY();
	}

	/**
	 * Keeps this map exactly where the game's map is, so what other plugins draw on that map, which only they can place,
	 * falls on the right tiles here. The zoom is the game's and so is the place: the game's own controls move and zoom the
	 * map, since clicks and the wheel that are not on this map's controls are left for the game, and this map follows.
	 * <p>
	 * When this map is moved some other way (a search result, a link to another place, Locate me, a click on a pin) the game's
	 * map is sent after it, and this map stays where it was put until the game's map has got there.
	 */
	private double[] lineUpPrepare(Rectangle bounds, long now, @Nullable PlayerPosition position)
	{
		linedUp = false;
		if (nativeMap == null || !config.lineUpWithGameMap())
		{
			catchingUp = false;
			input.setTakes(null);
			putX = Double.NaN;
			return null;
		}
		NativeMap.View theirs = nativeMap.view();
		if (theirs == null || !(theirs.getZoom() > 0)
			|| viewer.clampZoom(theirs.getZoom()) != theirs.getZoom())
		{
			input.setTakes(null);
			return null;
		}
		linedUp = true;
		input.setTakes(this::takesPoint);
		double zoom = theirs.getZoom();
		double[] theirCenter = LineUp.centerFor(theirs.getX(), theirs.getY(), zoom, bounds.width, bounds.height);

		// Following the player is done by sending the game's map to the player once, not by following them every frame,
		// which would take the game's map back each time the player moves it.
		boolean sendToPlayer = viewer.isFollowing() && position != null;
		if (viewer.isFollowing())
		{
			viewer.setFollowing(false);
			if (position != null)
			{
				viewer.setPosition(position.getX(), position.getY());
			}
		}
		boolean movedHere = sendToPlayer || !Double.isNaN(putX)
			&& Math.hypot(viewer.getX() - putX, viewer.getY() - putY) > MOVED_HERE_TILES;
		if (!movedHere && catchingUp
			&& (Math.hypot(theirCenter[0] - viewer.getX(), theirCenter[1] - viewer.getY()) <= CAUGHT_UP_TILES
			|| ++catchingFrames > CATCHING_UP_FRAMES))
		{
			catchingUp = false;
		}
		boolean follow = true;
		if (movedHere || catchingUp)
		{
			follow = false;
			if (!catchingUp)
			{
				catchingFrames = 0;
				sentX = Integer.MIN_VALUE;
			}
			catchingUp = true;
			int[] tile = LineUp.tileFor(viewer.getX(), viewer.getY(), zoom, bounds.width, bounds.height);
			if ((tile[0] != sentX || tile[1] != sentY) && now - sentAt >= MOVE_MILLIS)
			{
				sentX = tile[0];
				sentY = tile[1];
				sentAt = now;
				nativeMap.moveTo(tile[0], tile[1]);
			}
		}
		else
		{
			followGameMapTo(theirs, theirCenter);
			viewer.setPosition(theirCenter[0], theirCenter[1]);
		}
		viewer.jumpZoom(zoom);
		return new double[] {zoom, follow ? 1 : 0, theirCenter[0], theirCenter[1]};
	}

	/** Finishes a frame lined up: the view is exactly where the game's map is, whatever the map's own limits did. */
	private ViewTransform lineUpFinish(double[] state, ViewTransform view, Rectangle bounds)
	{
		if (state[1] != 0)
		{
			viewer.setPosition(state[2], state[3]);
		}
		putX = viewer.getX();
		putY = viewer.getY();
		return new ViewTransform(viewer.getX(), viewer.getY(), state[0], bounds.x, bounds.y, bounds.width, bounds.height);
	}

	/** Takes this map to the map the game's map is showing when it has been somewhere else for a moment. */
	private void followGameMapTo(NativeMap.View theirs, double[] center)
	{
		MapInfo there = assets.getCatalog().mapFor(theirs.getX(), theirs.getY());
		MapInfo here = viewer.getMap();
		if (there != null && here != null && there.getId() != here.getId())
		{
			if (++differentMapFrames >= MAP_CHANGE_FRAMES)
			{
				differentMapFrames = 0;
				viewer.showMap(there, center[0], center[1]);
			}
		}
		else
		{
			differentMapFrames = 0;
		}
	}

	/**
	 * True where a click is this map's own while it is lined up with the game's: its buttons, panels, search bar and menu,
	 * and everywhere while it is measuring or has a card pinned, since those take clicks on the map itself.
	 */
	private boolean takesPoint(int x, int y)
	{
		return buttons.hit(x, y) != null || floorButtons.hit(x, y) != null || isControl(x, y) || menu.isOpen() || pinnedCard != null;
	}

	private static final Color WALK = new Color(90, 200, 255, 235);
	private static final Color JUMP = new Color(255, 120, 40);

	/** The route Shortest Path found, as steps. Empty when there is none. */
	private RoutePlan routePlan(@Nullable PlayerPosition position)
	{
		List<RouteHop> hops = external.hops();
		WorldPoint target = actions.getRouteTarget();
		if (hops.isEmpty() && target == null)
		{
			return RoutePlan.empty();
		}
		WorldPoint chosen = actions.getRouteStart();
		double[] start = chosen != null ? new double[] {chosen.getX() + 0.5, chosen.getY() + 0.5}
			: position == null ? null : new double[] {position.getX(), position.getY()};
		int startPlane = chosen != null ? chosen.getPlane() : position == null ? 0 : position.getTile().getPlane();
		target = endOfRoute(hops, start, startPlane);
		double[] end = target == null ? null : new double[] {target.getX() + 0.5, target.getY() + 0.5};
		return RoutePlan.build(start, startPlane, hops, end, target == null ? 0 : target.getPlane());
	}

	/**
	 * Which target the route ends at. Shortest Path goes to whichever of several is nearest by its own count, which
	 * it does not say, so this takes the one nearest where the last jump comes out, or where the route starts.
	 */
	@Nullable
	private WorldPoint endOfRoute(List<RouteHop> hops, @Nullable double[] start, int startPlane)
	{
		List<WorldPoint> targets = actions.getRouteTargets();
		if (targets.size() < 2)
		{
			return actions.getRouteTarget();
		}
		double fromX;
		double fromY;
		int fromPlane;
		if (!hops.isEmpty())
		{
			RouteHop last = hops.get(hops.size() - 1);
			fromX = last.getToX();
			fromY = last.getToY();
			fromPlane = last.getToPlane();
		}
		else if (start != null)
		{
			fromX = start[0];
			fromY = start[1];
			fromPlane = startPlane;
		}
		else
		{
			return targets.get(0);
		}
		WorldPoint best = targets.get(0);
		double bestDistance = Double.MAX_VALUE;
		for (WorldPoint candidate : targets)
		{
			double distance = Math.max(Math.abs(candidate.getX() + 0.5 - fromX), Math.abs(candidate.getY() + 0.5 - fromY))
				+ (candidate.getPlane() == fromPlane ? 0 : 10000);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	private static final Color WAY_EDGE = new Color(8, 24, 48, 200);
	private static final Color WAY_FLOW = new Color(200, 230, 255, 235);

	/** The frame's time, for things that move on their own. */
	private long animNow;

	/** Length of one light-and-gap of the pulse that runs along a walking line. */
	private static final float FLOW_LENGTH = 38f;

	/** The dash offset for a pulse that travels forward along a path, where a bigger offset moves it back. */
	private float flowPhase()
	{
		return FLOW_LENGTH - (float) ((animNow % 1400) / 1400.0 * FLOW_LENGTH);
	}

	/**
	 * The tiles of the walk of a step, from Shortest Path's own walking data, or null when it is not worked out
	 * yet, has no way, or the step changes floor.
	 */
	@Nullable
	private int[] walkingWay(RoutePlan.Step step, int number)
	{
		if (step.getFromPlane() != step.getToPlane())
		{
			return null;
		}
		int[] way = assets.getWalkPlanner().lookup("step" + number, (int) Math.floor(step.getFromX()),
			(int) Math.floor(step.getFromY()), (int) Math.floor(step.getToX()), (int) Math.floor(step.getToY()),
			step.getFromPlane());
		return way == null || way.length < 4 ? null : way;
	}

	/** A walk as a line through the middle of each tile, with a dark edge so it reads over any terrain. */
	private void drawWay(Graphics2D g, ViewTransform view, int[] way)
	{
		// Zoomed far out the tiles are less than a pixel apart, so only some of them are used.
		int every = Math.max(1, (int) Math.ceil(1.5 / Math.max(0.05, view.getPixelsPerTile())));
		Path2D line = new Path2D.Double();
		int points = way.length / 2;
		for (int i = 0; i < points; i += every)
		{
			addWayPoint(line, view, way, i, i == 0);
		}
		addWayPoint(line, view, way, points - 1, false);
		g.setStroke(round(5.2f));
		g.setColor(WAY_EDGE);
		g.draw(line);
		g.setStroke(round(3f));
		g.setColor(WALK);
		g.draw(line);
		// A pulse of light runs along the way in the direction it is walked.
		g.setStroke(round(1.6f, 7f, FLOW_LENGTH - 7f, flowPhase()));
		g.setColor(WAY_FLOW);
		g.draw(line);
	}

	private static void addWayPoint(Path2D line, ViewTransform view, int[] way, int index, boolean first)
	{
		double sx = view.screenX(way[index * 2] + 0.5);
		double sy = view.screenY(way[index * 2 + 1] + 0.5);
		if (first)
		{
			line.moveTo(sx, sy);
		}
		else
		{
			line.lineTo(sx, sy);
		}
	}

	/** The walking between jumps, along the tiles Shortest Path walks where they are known, with the length. */
	private void drawRouteLegs(Graphics2D g, ViewTransform view, MapInfo map, RoutePlan plan)
	{
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		int number = 0;
		for (RoutePlan.Step step : plan.getSteps())
		{
			number++;
			boolean from = map.contains((int) step.getFromX(), (int) step.getFromY());
			boolean to = map.contains((int) step.getToX(), (int) step.getToY());
			if (step.isWalk())
			{
				if (!from || !to)
				{
					continue;
				}
				double x1 = view.screenX(step.getFromX());
				double y1 = view.screenY(step.getFromY());
				double x2 = view.screenX(step.getToX());
				double y2 = view.screenY(step.getToY());
				int[] way = walkingWay(step, number);
				int tiles = step.getTiles();
				if (way != null)
				{
					drawWay(g, view, way);
					tiles = way.length / 2 - 1;
				}
				else
				{
					// No exact way known yet, or none found: straight towards it, as an estimate.
					g.setColor(WALK);
					g.setStroke(round(2.4f, 3f, 6f, 9f - (float) ((animNow % 700) / 700.0 * 9f)));
					g.draw(new Line2D.Double(x1, y1, x2, y2));
				}
				g.setColor(WALK);
				g.setStroke(new BasicStroke(1f));
				g.fill(circle(x2, y2, 3.5));
				if (Math.hypot(x2 - x1, y2 - y1) > 90)
				{
					String text = tiles + " tiles";
					drawLabel(g, metrics, text, (int) ((x1 + x2) / 2) - metrics.stringWidth(text) / 2,
						(int) ((y1 + y2) / 2) - 4);
				}
			}
			else if (from)
			{
				numbered(g, metrics, view.screenX(step.getFromX()), view.screenY(step.getFromY()), 9, JUMP, true, number);
			}
		}
	}

	/** Lines of the route a panel shows. */
	private static final int ROUTE_PANEL_ROWS = 9;

	/** What a step says. A walk whose exact way is known says how many tiles that way is, and no longer "about". */
	private String stepText(RoutePlan.Step step, int number)
	{
		if (!step.isWalk())
		{
			return step.getText();
		}
		int[] way = walkingWay(step, number);
		return way == null ? step.getText() : step.getText().replaceFirst("about [0-9]+", String.valueOf(way.length / 2 - 1));
	}

	/** Where the route panel is and where each of its rows is, as of the last frame. */
	private Rectangle routePanelRect = new Rectangle();
	private final List<Rectangle> routeRows = new ArrayList<>();
	private final List<RoutePlan.Step> routeRowSteps = new ArrayList<>();
	private final Motion stepFade = new Motion();

	/** The row of the route panel at a point, or -1. */
	private int routeRowAt(int x, int y)
	{
		for (int i = 0; i < routeRows.size(); i++)
		{
			if (routeRows.get(i).contains(x, y))
			{
				return i;
			}
		}
		return -1;
	}

	/** Takes the map to where a step of the route begins, and rings it so it can be seen. */
	private void jumpToStep(int row)
	{
		if (row < 0 || row >= routeRowSteps.size() || viewer == null)
		{
			return;
		}
		RoutePlan.Step step = routeRowSteps.get(row);
		MapInfo target = assets.getCatalog().mapFor((int) Math.floor(step.getFromX()), (int) Math.floor(step.getFromY()));
		if (target == null)
		{
			target = assets.getCatalog().surface();
		}
		viewer.setFollowing(false);
		goTo(target, step.getFromX(), step.getFromY(), lastFrame);
		if (hasFloors(target))
		{
			viewer.setFloor(Math.max(0, Math.min(assets.getFloors(), step.getFromPlane())));
		}
		zoomForJump(Math.max(viewer.targetZoom(), FOUND_MIN_ZOOM));
		found = new SearchEntry(SearchKind.TILE, "Step " + (row + 1), "", target.getId(), step.getFromX(), step.getFromY());
		foundSince = lastFrame;
	}

	/** The route in words, at the top left: each walk and each jump, and which option it uses. */
	private void drawRoutePanel(Graphics2D g, Rectangle bounds, RoutePlan plan)
	{
		routePanelRect = new Rectangle();
		routeRows.clear();
		routeRowSteps.clear();
		if (plan.isEmpty() || bounds.width < 520 || bounds.height < 260)
		{
			return;
		}
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		List<RoutePlan.Step> steps = plan.getSteps();
		int rows = Math.min(steps.size(), ROUTE_PANEL_ROWS);
		int width = metrics.stringWidth("Route");
		for (int i = 0; i < rows; i++)
		{
			width = Math.max(width, metrics.stringWidth((i + 1) + "  " + stepText(steps.get(i), i + 1)));
		}
		width = Math.min(width + 30, bounds.width / 2 - 20);
		int lineHeight = metrics.getHeight() + 2;
		int height = lineHeight * (rows + 1) + 12 + (steps.size() > rows ? lineHeight : 0);
		int x = bounds.x + 10;
		int y = bounds.y + 40;
		Box.panel(g, x, y, width, height);
		routePanelRect = new Rectangle(x, y, width, height);
		g.setColor(colour("ACCENT"));
		g.setFont(Palette.TITLE);
		g.drawString("Route", x + 8, y + 6 + g.getFontMetrics().getAscent());
		int titleWidth = g.getFontMetrics().stringWidth("Route");
		g.setFont(Palette.SMALL);
		if (width > titleWidth + 150)
		{
			g.setColor(colour("TEXT_DIM"));
			g.drawString("click a step to go there", x + 18 + titleWidth, y + 6 + g.getFontMetrics().getAscent());
		}
		g.setFont(Palette.BODY);
		Point pointer = mouse(bounds);
		for (int i = 0; i < rows; i++)
		{
			RoutePlan.Step step = steps.get(i);
			int rowY = y + 6 + lineHeight * (i + 1);
			Rectangle rowRect = new Rectangle(x + 2, rowY, width - 4, lineHeight);
			routeRows.add(rowRect);
			routeRowSteps.add(step);
			Box.warm(g, "HOVER", stepFade.level(i, pointer != null && rowRect.contains(pointer), animNow), rowRect.x, rowRect.y,
				rowRect.width, rowRect.height);
			g.setColor(step.isWalk() ? WALK : JUMP);
			g.fillRect(x + 8, rowY + 4, 4, metrics.getHeight() - 6);
			g.setColor(colour("TEXT"));
			String text = (i + 1) + "  " + stepText(step, i + 1);
			while (text.length() > 4 && metrics.stringWidth(text) > width - 24)
			{
				text = text.substring(0, text.length() - 2) + "\u2026";
				if (metrics.stringWidth(text) <= width - 24)
				{
					break;
				}
			}
			g.drawString(text, x + 18, rowY + metrics.getAscent());
		}
		if (steps.size() > rows)
		{
			g.drawString("and " + (steps.size() - rows) + " more", x + 18, y + 6 + lineHeight * (rows + 1)
				+ metrics.getAscent());
		}
	}

	private String noticeFor(MapActions.Status status)
	{
		switch (status)
		{
			case MISSING:
				return "Shortest Path is not installed. Add it from the Plugin Hub to get routes.";
			case DISABLED:
				return "Shortest Path is turned off. Switch it on to get routes.";
			default:
				return "Route sent to Shortest Path.";
		}
	}

	private int hopCount()
	{
		int hops = 0;
		for (ExternalLine line : external.lines())
		{
			if (line.isHop())
			{
				hops++;
			}
		}
		return hops;
	}

	/** A flag where the requested route ends, which goes once the player is there. */
	private void drawRouteTarget(Graphics2D g, ViewTransform view, MapInfo map, @Nullable PlayerPosition position)
	{
		List<WorldPoint> targets = actions.getRouteTargets();
		WorldPoint start = actions.getRouteStart();
		if (targets.isEmpty())
		{
			return;
		}
		g.setFont(Palette.BODY);
		if (start != null && map.contains(start.getX(), start.getY()))
		{
			ring(g, view, start, ROUTE_START, "Route start");
		}
		int number = 0;
		for (WorldPoint target : targets)
		{
			number++;
			if (map.contains(target.getX(), target.getY()) && (position == null || position.getTile().getPlane() != target.getPlane()
				|| position.getTile().distanceTo2D(target) > MapActions.ARRIVED_TILES))
			{
				ring(g, view, target, ROUTE_TARGET, targets.size() > 1 ? "Route target " + number : "Route target");
			}
		}
	}

	/** A ring with a dot where a route starts or ends, with its name. */
	private static void ring(Graphics2D g, ViewTransform view, WorldPoint at, Color color, String label)
	{
		double sx = view.screenX(at.getX() + 0.5);
		double sy = view.screenY(at.getY() + 0.5);
		g.setColor(color);
		g.setStroke(new BasicStroke(2f));
		g.draw(circle(sx, sy, 9));
		g.fill(circle(sx, sy, 3));
		drawLabel(g, g.getFontMetrics(), label, (int) sx + 13, (int) sy + 4);
	}

	/** A line along the bottom that says what became of a route request. */
	private void drawRouteNotice(Graphics2D g, Rectangle bounds, long now)
	{
		if (routeNotice == null || now - routeNoticeSince > ROUTE_NOTICE_MILLIS)
		{
			return;
		}
		String text = routeNotice;
		int hops = hopCount();
		// The line about a route sent says what came of it; any other note is shown as it is.
		if (text.startsWith("Route sent") && actions.getRouteTarget() != null)
		{
			text = hops > 0 ? "Shortest Path route, " + hops + (hops == 1 ? " jump" : " jumps") + " shown on the map"
				: "Route sent to Shortest Path. Its jumps show here once found.";
		}
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		// Inside the map and clear of the buttons: a line too long for the room is wrapped onto more lines, going up.
		int left = bounds.x + 8;
		int right = Math.min(bounds.x + bounds.width - 8, Math.min(buttons.block().x - 8, bounds.x + bounds.width));
		int room = Math.max(60, right - left - 8);
		List<String> lines = Box.wrap(text, metrics, room);
		int baseline = bounds.y + bounds.height - 34;
		int top = bounds.y + Box.EDGE + metrics.getAscent() + 1;
		for (int i = lines.size() - 1; i >= 0; i--)
		{
			String line = lines.get(i);
			int x = left + 4 + Math.max(0, (room - metrics.stringWidth(line)) / 2);
			int centred = bounds.x + Math.max(8, (bounds.width - metrics.stringWidth(line)) / 2);
			if (centred + metrics.stringWidth(line) + 4 <= right)
			{
				// It fits in the middle of the map without touching a button, which is where it has always been.
				x = centred;
			}
			if (baseline >= top)
			{
				drawLabel(g, metrics, line, x, baseline);
			}
			baseline -= metrics.getHeight() + 3;
		}
	}

	/** A circle round a point. */
	private static Ellipse2D circle(double x, double y, double r)
	{
		return new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
	}

	/** A round stroke; with a dash, a gap and the dash's phase given, dashed. */
	private static BasicStroke round(float width, float... dash)
	{
		return dash.length == 0 ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
			: new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {dash[0], dash[1]}, dash[2]);
	}

	/** A name on a dark plate, so it reads over any terrain. */
	private static void drawLabel(Graphics2D g, FontMetrics metrics, String text, int x, int y)
	{
		Box.plate(g, metrics, text, x - 4, y - metrics.getAscent() - 1, 4, 1, 5);
	}

	/** The card for a marker: its name, and its tooltip, which may run to several lines. */
	private static HoverCard markerCard(ExternalMarker marker)
	{
		String[] lines = marker.getTooltip().isEmpty() ? new String[0] : marker.getTooltip().split("\\r?\\n");
		String title = marker.getName();
		int first = 0;
		if (title.isEmpty())
		{
			title = lines.length > 0 ? lines[0] : "Marker";
			first = lines.length > 0 ? 1 : 0;
		}
		HoverCard.Builder builder = HoverCard.builder(title);
		for (int i = first; i < lines.length && i < first + MARKER_LINES; i++)
		{
			String line = lines[i].trim();
			if (i == first)
			{
				builder.subtitle(line);
			}
			else
			{
				builder.row(line, "");
			}
		}
		if (marker.getJumpTarget() != null)
		{
			builder.hint("Click to go there");
		}
		else if (marker.isClickable())
		{
			builder.hint(marker.getHint().isEmpty() ? "Click to choose" : marker.getHint());
		}
		else if (!marker.getMenu().isEmpty())
		{
			builder.hint("Right-click for more");
		}
		return builder.build();
	}

	/** What a saved place is called on its card. */
	static String kindText(Pin pin)
	{
		return "pin".equals(pin.getKind()) ? "Your pin" : "Saved " + pin.getKind();
	}

	/** Lines of a marker's tooltip a card shows. */
	private static final int MARKER_LINES = 8;

	/** The floor icons are drawn and answered on: the one being looked at on a map with floors, the ground floor elsewhere. */
	private int iconFloor(@Nullable MapInfo map)
	{
		return hasFloors(map) ? viewer.getFloor() : 0;
	}

	/** One thing the mouse is over, and how it ranks: how far its middle is from the mouse, and whether a click on it does anything. */
	@RequiredArgsConstructor
	private static final class Candidate
	{
		private final Object what;
		private final double distance;
		private final double x;
		private final double y;
		private final boolean actionable;
		/** The floor of the game's icon this is, or -1 when it is not one. */
		private int iconFloor = -1;
		private List<IconExtra> extras = Collections.emptyList();
		/** What the game's icon asks of the player, or null. */
		private Need need;

		/** Marks the candidate as one of the game's icons, on a floor, with what the plugin's own points add to its card. */
		Candidate icon(int floor, List<IconExtra> extras, Need need)
		{
			this.iconFloor = floor;
			this.extras = extras;
			this.need = need;
			return this;
		}

		String title()
		{
			if (what instanceof Pin)
			{
				return ((Pin) what).getName();
			}
			if (what instanceof ExternalMarker)
			{
				return markerCard((ExternalMarker) what).getTitle();
			}
			return ((Hotspot) what).getCard().getTitle();
		}
	}

	/**
	 * Everything the mouse is over, nearest middle first: the pins, the markers other plugins sent, the game's icons (where their
	 * drawn picture is) and the other cards. A marker or a pin never takes the mouse from an icon it only partly covers: whichever
	 * has its middle nearest the pointer is first, and the rest are listed after it, so the card can name them and a click can fall to
	 * the first one that does something.
	 */
	private List<Candidate> candidates(ViewTransform view, @Nullable MapInfo map, int x, int y)
	{
		List<Candidate> found = new ArrayList<>(3);
		if (map == null || viewer.isDragging() || overButton(x, y) || isControl(x, y))
		{
			return found;
		}
		Pin pin = pinAt(view, map, x, y);
		if (pin != null)
		{
			double scale = pin.getSize() / 100.0;
			double sx = view.screenX(pin.getX());
			double sy = view.screenY(pin.getY()) - PIN_HEIGHT * scale / 2;
			found.add(new Candidate(pin, Math.hypot(x - sx, y - sy), sx, sy, true));
		}
		for (ExternalMarker marker : allMarkers())
		{
			if (!onThisMap(map, marker.getX(), marker.getY()))
			{
				continue;
			}
			double sx = view.screenX(marker.getX());
			double sy = view.screenY(marker.getY());
			boolean snapped = false;
			if (marker.isSnapToEdge() && !view.contains(sx, sy))
			{
				// A point that sits on the edge of the map while its place is off the view answers there, as it does on the game's map.
				sx = snapX(view, sx);
				sy = snapY(view, sy);
				snapped = true;
			}
			double d = Math.hypot(sx - x, sy - y);
			boolean hit = d <= HOVER_PIXELS;
			BufferedImage icon = marker.getIcon();
			if (!hit && icon != null)
			{
				// The picture drawn around the point counts too, such as an arrow standing above its tile.
				int[] anchor = snapped ? null : marker.getAnchor();
				double left = anchor == null ? (int) sx - icon.getWidth() / 2 : (int) sx - anchor[0];
				double top = anchor == null ? (int) sy - icon.getHeight() / 2 : (int) sy - anchor[1];
				if (x >= left - 2 && x <= left + icon.getWidth() + 2 && y >= top - 2 && y <= top + icon.getHeight() + 2)
				{
					hit = true;
					d = Math.hypot(left + icon.getWidth() / 2.0 - x, top + icon.getHeight() / 2.0 - y);
				}
			}
			if (hit)
			{
				found.add(new Candidate(marker, d, sx, sy, marker.getJumpTarget() != null || marker.isClickable()));
			}
		}
		if (config.showHotspots())
		{
			Hotspot generic = assets.getHotspots().find(map.getId(), view, x, y, h -> active(h, view));
			if (generic != null)
			{
				double sx = view.screenX(generic.getX());
				double sy = view.screenY(generic.getY());
				found.add(new Candidate(generic, Math.hypot(sx - x, sy - y), sx, sy, generic.getTarget() != null));
			}
			GameIconLayer layer = icons();
			int slot = layer.hit(view, map.getId(), iconFloor(map), x, y);
			Hotspot card = layer.cardAt(slot);
			if (card != null)
			{
				double sx = layer.drawnCenterX(slot);
				double sy = layer.drawnCenterY(slot);
				found.add(new Candidate(card, Math.hypot(sx - x, sy - y), sx, sy, card.getTarget() != null)
					.icon(layer.floorAt(slot), layer.extrasAt(slot), layer.needAt(slot)));
			}
		}
		// Stable, so at an equal distance the order above stands: pins, markers, other cards, icons.
		found.sort(Comparator.comparingDouble(c -> c.distance));
		return found;
	}

	/** The card of a candidate, with a row naming the other things under the mouse when there are any. */
	private HoverCard cardOf(Candidate first, List<Candidate> all)
	{
		HoverCard card;
		if (first.what instanceof Pin)
		{
			Pin pin = (Pin) first.what;
			card = HoverCard.builder(pin.getName()).subtitle(kindText(pin)).hint("Click to route here. Right-click for more").build();
		}
		else if (first.what instanceof ExternalMarker)
		{
			card = markerCard((ExternalMarker) first.what);
		}
		else
		{
			card = ((Hotspot) first.what).getCard();
			if (first.iconFloor >= 0)
			{
				card = iconCard(card, first);
			}
			card = withMissing(card, first.iconFloor >= 0 ? first.need : ((Hotspot) first.what).getNeed());
			// Where the card names no page that is certain to exist, its button opens the wiki's search page for its name instead.
			card = card.withWikiSearch(WikiLinks.searchName(card.getTitle()));
		}
		if (all.size() > 1)
		{
			StringBuilder also = new StringBuilder();
			for (Candidate other : all)
			{
				if (other != first && !other.title().isEmpty() && !other.title().equals(first.title()))
				{
					also.append(also.length() == 0 ? "" : ", ").append(other.title());
				}
			}
			if (also.length() > 0)
			{
				card = card.withRow("Also here", also.toString());
			}
		}
		return config.showWikiButtons() ? card : card.withoutWiki();
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	/**
	 * What the card for the thing under the mouse was built from, so a mouse that rests gets the same card and no new one is built.
	 * The card is built again when the thing, what else is under the mouse, the floor or the extras of an icon, what the player is
	 * missing, the layers that are on, or a setting a card reads changes.
	 */
	private static final class CardMemo
	{
		static final int MAX_UNDER = 6;
		final Object[] under = new Object[MAX_UNDER];
		int underCount;
		int iconFloor;
		List<IconExtra> extras;
		Need need;
		String poiText;
		int progress;
		int flags;
		String nameKey;
		String kindKey;
		HoverCard card;
	}

	private final CardMemo cardMemo = new CardMemo();

	/** The card for the first of the things under the mouse, kept while nothing it is made from changes. */
	private HoverCard hoverCard(Candidate first, List<Candidate> under)
	{
		Unlocks progress = assets.getUnlocks();
		int version = progress == null ? -1 : progress.version();
		int flags = config.unusableIcons().ordinal() | (config.showWikiButtons() ? 4 : 0);
		String name = null;
		String kind = null;
		if (first.what instanceof Pin)
		{
			name = ((Pin) first.what).getName();
			kind = ((Pin) first.what).getKind();
		}
		CardMemo m = cardMemo;
		boolean same = m.card != null && under.size() <= CardMemo.MAX_UNDER && m.underCount == under.size()
			&& m.iconFloor == first.iconFloor && m.extras == first.extras && m.need == first.need
			&& m.progress == version && m.flags == flags && Objects.equals(m.poiText, config.poiLayers())
			&& Objects.equals(m.nameKey, name) && Objects.equals(m.kindKey, kind);
		for (int i = 0; same && i < m.underCount; i++)
		{
			same = m.under[i] == under.get(i).what;
		}
		if (same)
		{
			return m.card;
		}
		HoverCard card = cardOf(first, under);
		Arrays.fill(m.under, null);
		m.underCount = Math.min(under.size(), CardMemo.MAX_UNDER);
		for (int i = 0; i < m.underCount; i++)
		{
			m.under[i] = under.get(i).what;
		}
		m.iconFloor = first.iconFloor;
		m.extras = first.extras;
		m.need = first.need;
		m.progress = version;
		m.flags = flags;
		m.poiText = config.poiLayers();
		m.nameKey = name;
		m.kindKey = kind;
		m.card = under.size() > CardMemo.MAX_UNDER ? null : card;
		return card;
	}

	/** The hotspot under the mouse, or null while dragging, over a control, or off the map. */
	@Nullable
	private Hotspot hoveredHotspot(ViewTransform view, MapInfo map, @Nullable Point mouse)
	{
		if (!config.showHotspots() || mouse == null || map == null || viewer.isDragging()
			|| overButton(mouse.x, mouse.y) || isControl(mouse.x, mouse.y))
		{
			return null;
		}
		for (Candidate candidate : candidates(view, map, mouse.x, mouse.y))
		{
			if (candidate.what instanceof Hotspot)
			{
				return (Hotspot) candidate.what;
			}
		}
		return null;
	}

	private void drawHover(Graphics2D g, Rectangle bounds, ViewTransform view, MapInfo map, @Nullable Point mouse)
	{
		if (menu.isOpen() || mouse == null || map == null || viewer.isDragging())
		{
			clearHeld();
			return;
		}
		if (heldCard != null && heldBox.contains(mouse))
		{
			// The pointer has gone from the thing to its card, to press the Wiki button: the card stays where it is.
			paintHeld(g, bounds, mouse);
			return;
		}
		List<Candidate> under = candidates(view, map, mouse.x, mouse.y);
		if (under.isEmpty())
		{
			if (heldCard != null && animNow - heldAt <= HOLD_MILLIS)
			{
				// A moment to cross the gap between the thing and its card.
				paintHeld(g, bounds, mouse);
			}
			else
			{
				clearHeld();
			}
			return;
		}
		Candidate first = under.get(0);
		if (!(first.what instanceof Hotspot))
		{
			HoverCard own = hoverCard(first, under);
			if (cardPainter == null)
			{
				cardPainter = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
			}
			lastCardTitle = own.getTitle();
			lastCard = own;
			cardPainter.paint(g, own, mouse.x, mouse.y, bounds, input.isExpandHeld(), mouse);
			hold(own, mouse);
			return;
		}
		Hotspot hovered = (Hotspot) first.what;

		double hx = first.x;
		double hy = first.y;
		g.setStroke(new BasicStroke(2f));
		g.setColor(HOVER_RING);
		double pulse = 11 + 1.5 * Math.sin(animNow / 220.0);
		g.draw(circle(hx, hy, pulse));

		g.setFont(Palette.BODY);
		for (Hotspot.Link link : hovered.getLinks())
		{
			if (link.getMapId() != map.getId())
			{
				continue;
			}
			double lx = view.screenX(link.getX());
			double ly = view.screenY(link.getY());
			g.setStroke(round(1.6f, 6f, 5f, 11f - (float) ((animNow % 600) / 600.0 * 11f)));
			g.setColor(LINK_LINE);
			g.draw(new Line2D.Double(hx, hy, lx, ly));
			g.setStroke(new BasicStroke(1f));
			g.fill(circle(lx, ly, 3));
			if (!link.getLabel().isEmpty())
			{
				g.setColor(colour("TEXT"));
				g.drawString(link.getLabel(), (float) lx + 6, (float) ly - 5);
			}
		}

		if (cardPainter == null)
		{
			cardPainter = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
		}
		HoverCard card = hoverCard(first, under);
		lastCardTitle = card.getTitle();
		lastCard = card;
		if (!card.getTitle().equals(cardShown))
		{
			cardShown = card.getTitle();
			cardSince = animNow;
		}
		Composite before = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(!Motion.on() ? 1f
			: (float) Motion.ease((animNow - cardSince) / (double) Motion.FADE_MILLIS)));
		cardPainter.paint(g, card, mouse.x, mouse.y, bounds, input.isExpandHeld(), mouse);
		g.setComposite(before);
		hold(card, mouse);
	}

	// ------------------------------------------------------------------
	// The Wiki button on a card, and the menu entry that does the same
	// ------------------------------------------------------------------

	/** How long a card stays where it was after the pointer leaves the thing it is about, so the pointer can reach its Wiki button. */
	static final long HOLD_MILLIS = 700;

	/** The card with a Wiki button that is being kept for the pointer to reach, where it was drawn, and when it was last pointed at. */
	private HoverCard heldCard;
	private Point heldMouse;
	private Rectangle heldBox;
	private Rectangle heldButton;
	private long heldAt;

	/** Opens a wiki address and says whether it did. The real one hands it to the player's browser. */
	private Predicate<String> wikiBrowser = WikiLinks::browse;

	/** The wiki page the open menu offers, or null. */
	private String menuWiki;

	/** The name the open menu offers to look up on the wiki's search page, or null. */
	private String menuWikiSearch;


	/**
	 * The name to look up on the wiki for a search result with no page that is certain to exist: its name, or for every place of a kind
	 * the kind's name. Nothing for the player's own pins and typed tiles, whose names are the player's.
	 */
	@Nullable
	private String wikiSearchFor(SearchEntry entry)
	{
		if (entry.getKind().isOwn() || entry.getKind() == SearchKind.TILE)
		{
			return null;
		}
		KindIndex.Kind kind = assets.getKinds().byId(entry.getKindId());
		return WikiLinks.searchName(kind != null ? kind.getSingular() : entry.getName());
	}

	/** Replaces what opens a wiki address, for tests. */
	void wikiBrowser(Predicate<String> browser)
	{
		this.wikiBrowser = browser;
	}

	/** The Wiki button of the card being kept, or null. For tests. */
	Rectangle wikiButton()
	{
		return heldButton == null ? null : new Rectangle(heldButton);
	}

	/** Remembers the card just drawn when it has a Wiki button, and forgets any other. */
	private void hold(HoverCard card, Point mouse)
	{
		Rectangle button = cardPainter.lastButton();
		if (button == null)
		{
			clearHeld();
			return;
		}
		heldCard = card;
		heldMouse = new Point(mouse);
		heldBox = cardPainter.lastBounds();
		heldButton = button;
		heldAt = animNow;
	}

	/** Draws the kept card again where it was. */
	private void paintHeld(Graphics2D g, Rectangle bounds, Point mouse)
	{
		cardPainter.paint(g, heldCard, heldMouse.x, heldMouse.y, bounds, input.isExpandHeld(), mouse);
		heldBox = cardPainter.lastBounds();
		heldButton = cardPainter.lastButton();
		lastCardTitle = heldCard.getTitle();
		lastCard = heldCard;
	}

	private void clearHeld()
	{
		heldCard = null;
		heldBox = null;
		heldButton = null;
	}

	/** True when a point is on the card being kept for its Wiki button. */
	private boolean overHeldCard(int x, int y)
	{
		return heldCard != null && heldBox != null && heldBox.contains(x, y);
	}

	/** Opens a wiki page by its title, or the wiki's search page for a name, from a card or a menu, and says so on the map. */
	private void openWiki(@Nullable String name, boolean page)
	{
		if (name == null)
		{
			return;
		}
		boolean opened = wikiBrowser.test(page ? WikiLinks.url(name) : WikiLinks.searchUrl(name));
		routeNotice = !opened ? page ? "There is no wiki page to open for " + name + "." : "There is nothing to search the wiki for."
			: (page ? "Opened the wiki page for " : "Opened the wiki's search for ") + name + " in your browser.";
		routeNoticeSince = lastFrame;
	}

	/** The card of the thing of the map's own data the mouse is over at a point, for its wiki page, or null. */
	@Nullable
	private HoverCard hotspotCardAt(ViewTransform view, @Nullable MapInfo map, int x, int y)
	{
		List<Candidate> under = map == null ? Collections.emptyList() : candidates(view, map, x, y);
		return under.isEmpty() || !(under.get(0).what instanceof Hotspot) ? null : cardOf(under.get(0), under);
	}

	/** The title of the card being shown and when it first appeared, so a new card fades in. */
	private String cardShown;
	private long cardSince;

	/** A pulsing ring on the place just searched for, with its name, and dots for other places it is found. */
	private void drawFound(Graphics2D g, ViewTransform view, MapInfo map, long now)
	{
		if (found == null)
		{
			return;
		}
		if (now - foundSince > FOUND_MILLIS)
		{
			found = null;
			return;
		}
		if (found.getMapId() != map.getId())
		{
			drawWayTo(g, view, map, now);
			return;
		}

		g.setStroke(new BasicStroke(1.5f));
		g.setColor(FOUND);
		for (double[] spot : found.getAlsoAt())
		{
			double sx = view.screenX(spot[0]);
			double sy = view.screenY(spot[1]);
			g.fill(circle(sx, sy, 3));
		}

		double sx = view.screenX(found.getX());
		double sy = view.screenY(found.getY());
		double phase = ((now - foundSince) % 1200) / 1200.0;
		if (Motion.on())
		{
			// The pulse. With animations off the steady ring is all there is.
			double radius = 10 + 18 * phase;
			g.setStroke(new BasicStroke(3f));
			g.setColor(new Color(FOUND.getRed(), FOUND.getGreen(), FOUND.getBlue(), (int) (255 * (1 - phase))));
			g.draw(circle(sx, sy, radius));
		}
		g.setStroke(new BasicStroke(2f));
		g.setColor(FOUND);
		g.draw(circle(sx, sy, 8));

		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		String label = foundOf > 1 ? found.getName() + " (" + foundNumber + " of " + foundOf + ")" : found.getName();
		Box.plate(g, metrics, label, (int) sx - (metrics.stringWidth(label) + 12) / 2, (int) sy - 34, 6, 2, 6);
	}

	/**
	 * Marks the doorways that lead toward a place on another map, such as the dungeon a searched
	 * monster lives in, so it can be found from the surface. Once the other map is open the place
	 * itself is marked there.
	 */
	private void drawWayTo(Graphics2D g, ViewTransform view, MapInfo map, long now)
	{
		List<Hotspot> doors = assets.getDoorways().firstSteps(map.getId(), found.getMapId());
		if (doors.isEmpty())
		{
			return;
		}
		double phase = ((now - foundSince) % 1200) / 1200.0;
		double radius = 12 + 14 * phase;
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		boolean labelled = false;
		for (Hotspot door : doors)
		{
			double sx = view.screenX(door.getX());
			double sy = view.screenY(door.getY());
			if (!view.contains(sx, sy))
			{
				continue;
			}
			if (Motion.on())
			{
				g.setStroke(new BasicStroke(3f));
				g.setColor(new Color(LINK_LINE.getRed(), LINK_LINE.getGreen(), LINK_LINE.getBlue(), (int) (255 * (1 - phase))));
				g.draw(circle(sx, sy, radius));
			}
			g.setStroke(new BasicStroke(2f));
			g.setColor(LINK_LINE);
			g.draw(circle(sx, sy, 10));
			if (!labelled)
			{
				labelled = true;
				String text = found.getName() + " is through here";
				drawLabel(g, metrics, text, (int) sx - metrics.stringWidth(text) / 2, (int) sy - 18);
			}
		}
	}

	/** The card of one of the game's icons: its own card, the floor it is on and what the points of interest merged into it add while their layers are on. */
	private HoverCard iconCard(HoverCard own, Candidate icon)
	{
		return IconCards.compose(own, icon.iconFloor, icon.extras, enabledPoi());
	}

	/** Most results whose missing words are kept at once; the list is emptied when it is full or the player's progress changes. */
	private static final int MISSING_KEPT = 2000;

	/** What is missing for each result drawn, for the progress version {@link #missingVersion}; null words for "nothing". */
	private final Map<SearchEntry, String> missingWords = new HashMap<>();
	private int missingVersion = Integer.MIN_VALUE;
	private final Function<SearchEntry, String> resultMissing = this::missingFor;

	/**
	 * What the player is certainly missing for a search result, in the words of a card's Missing row, or null. Worked out once per
	 * result and progress version, so drawing the list each frame makes no new text.
	 */
	@Nullable
	private String missingFor(SearchEntry entry)
	{
		Unlocks progress = assets.getUnlocks();
		Need need = entry.getNeed();
		if (progress == null || need == null)
		{
			return null;
		}
		int version = progress.version();
		if (version != missingVersion || missingWords.size() >= MISSING_KEPT)
		{
			missingWords.clear();
			missingVersion = version;
		}
		if (missingWords.containsKey(entry))
		{
			return missingWords.get(entry);
		}
		Shortfall lack = progress.shortfall(need);
		String words = lack == null ? null : lack.text();
		missingWords.put(entry, words);
		return words;
	}

	/**
	 * The card with a row saying what the player is certainly missing for it, when the settings that mark or hide what cannot be used
	 * yet are on and something is. Anything that cannot be told is left unsaid.
	 */
	private HoverCard withMissing(HoverCard card, @Nullable Need need)
	{
		Unlocks progress = assets.getUnlocks();
		if (progress == null || need == null || !judges())
		{
			return card;
		}
		Shortfall lack = progress.shortfall(need);
		return lack == null ? card : card.withRow("Missing", lack.text());
	}

	/** Rows the wheel moves the search results by. */
	private static final int WHEEL_ROWS = 3;

	/** What a search needs to know about the player and about place names. */
	private SearchContext searchContext()
	{
		MapInfo at = lastPlayer == null ? null : assets.getCatalog().mapFor(lastPlayer.getTile().getX(), lastPlayer.getTile().getY());
		if (lastPlayer == null || at == null)
		{
			return new SearchContext(false, 0, 0, 0, assets.getGazetteer());
		}
		return new SearchContext(true, at.getId(), lastPlayer.getX(), lastPlayer.getY(), assets.getGazetteer());
	}

	/** Teleport destinations this many tiles or fewer from a tile are listed by Nearest teleports. */
	static final int NEAREST_TELEPORTS = 5;

	/** How long the Nearest teleports card stays up. */
	private static final long PINNED_MILLIS = 15_000;

	/**
	 * The nearest places to teleport to from a tile, on the map being viewed, with how many tiles each is
	 * from it and the first way of getting there. It is a list of places, not advice: whether a way is
	 * open to the player is not looked up.
	 */
	HoverCard nearestTeleports(int mapId, int tx, int ty)
	{
		List<Hotspot> found = new ArrayList<>();
		for (Hotspot teleport : assets.getTeleports())
		{
			if (teleport.getMapId() == mapId)
			{
				found.add(teleport);
			}
		}
		found.sort(Comparator.comparingInt(h -> tilesBetween(h, tx, ty)));
		HoverCard.Builder card = HoverCard.builder("Nearest teleports").subtitle("To " + tx + ", " + ty);
		int shown = 0;
		for (Hotspot teleport : found)
		{
			if (shown >= NEAREST_TELEPORTS)
			{
				break;
			}
			shown++;
			List<String> ways = new ArrayList<>();
			for (HoverCard.Row row : teleport.getCard().visibleRows(true))
			{
				if (!row.getLabel().startsWith(" ") && !row.getLabel().startsWith("and "))
				{
					ways.add(row.getLabel());
				}
			}
			String first = ways.isEmpty() ? "Teleport" : ways.get(0);
			String more = ways.size() > 1 ? " and " + (ways.size() - 1) + " more" : "";
			card.row(first + more, tilesBetween(teleport, tx, ty) + " tiles");
		}
		if (shown == 0)
		{
			card.row("None on this map", "");
		}
		card.hint("Click anywhere to close");
		return card.build();
	}

	private static int tilesBetween(Hotspot teleport, int tx, int ty)
	{
		return (int) Math.round(Math.max(Math.abs(teleport.getX() - (tx + 0.5)), Math.abs(teleport.getY() - (ty + 0.5))));
	}

	/** Draws the Nearest teleports card where it was asked for, until it times out or the map is clicked. */
	private void drawPinnedCard(Graphics2D g, Rectangle bounds, long now)
	{
		if (pinnedCard == null)
		{
			return;
		}
		if (now - pinnedSince > PINNED_MILLIS)
		{
			pinnedCard = null;
			return;
		}
		if (cardPainter == null)
		{
			cardPainter = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
		}
		cardPainter.paint(g, pinnedCard, pinnedAt.x, pinnedAt.y, bounds, false);
	}



	/** The layer that draws and hit tests the game's icons, shared by every map: they all draw on the client thread. */
	GameIconLayer icons()
	{
		return assets.getGameIconLayer();
	}

	/** The layer that draws the game's text labels, shared as the icons are. */
	LabelLayer labelLayer()
	{
		return assets.getLabels();
	}

	/**
	 * Tells the icon layer which kinds of icon to leave out, when the setting differs from what the layer was last told. The layer
	 * remembers what it was told, so nothing is redone when nothing has changed.
	 */
	private void applyHiddenGameIcons()
	{
		String text = config.hiddenGameIcons();
		GameIconLayer layer = icons();
		if (text.equals(layer.hiddenKey()))
		{
			return;
		}
		Set<String> hidden = HiddenSources.parse(text);
		MapElements elements = assets.getElements();
		for (int category = 0; category < elements.categoryCount(); category++)
		{
			layer.setCategoryShown(category, !hidden.contains(elements.categoryKind(category)));
		}
		layer.setHiddenKey(text);
	}

	/**
	 * Tells the icon layer what the player's progress should do to the icons, from the three settings. Like the hidden kinds, the layer
	 * remembers what it was told, so nothing is redone when nothing has changed.
	 */
	private void applyProgress()
	{
		icons().setUnlocks(assets.getUnlocks(), config.hideFinishedQuests(), judges(), config.unusableIcons() == WorldMapPlusConfig.Unusable.LEFT_OUT,
			config.colourQuestsByProgress());
	}

	/** The point of interest layers that are on, parsed again only when the setting changes. */
	private Set<String> enabledPoi()
	{
		String text = config.poiLayers();
		if (!text.equals(poiOnText))
		{
			poiOnText = text;
			poiOn = PoiSelection.parse(text);
		}
		return poiOn;
	}

	/** A reference hotspot answers the mouse only if its layer is showing. Points of interest follow their layer. */
	private boolean active(Hotspot hotspot, ViewTransform view)
	{
		if (hotspot.isMerged())
		{
			// The icon of the game it sits on carries what it says.
			return false;
		}
		if (hotspot.isDrawn() || assets.getGameIcons().isCardOfAnIcon(hotspot))
		{
			// The icon is drawn by the game icon layer, which is also what answers the mouse for it.
			return false;
		}
		if (hotspot.getCategory() == null)
		{
			return true;
		}
		return PoiLayer.visibleAt(view.getPixelsPerTile()) && enabledPoi().contains(hotspot.getCategory())
			&& assets.getPoiLayer().isLayer(hotspot.getCategory());
	}

	/** True while a setting asks what the player cannot use yet to be dimmed or left out. */
	private boolean judges()
	{
		return config.unusableIcons() != WorldMapPlusConfig.Unusable.AS_USUAL;
	}

	/** True while the results panel shows drop tabs. */
	private boolean drops()
	{
		return config.resultsPanel() == WorldMapPlusConfig.ResultsPanel.PLACES_AND_DROPS;
	}

	/** True when a point is on one of the corner buttons, floor buttons included. */
	private boolean overButton(int x, int y)
	{
		return buttons.hit(x, y) != null || floorButtons.hit(x, y) != null;
	}

	/** True when a point is on the search bar or the layer panel rather than the map. */
	private boolean isControl(int x, int y)
	{
		LayerPanel shown = layerPanel;
		return searchBar.hit(x, y) != SearchBar.Hit.NONE || shown != null && shown.contains(x, y) || routePanelRect.contains(x, y)
			|| results.contains(x, y)
		|| overGame(x, y) || overHeldCard(x, y);
	}

	/** The places inside the map that belong to the game, for this frame. */
	private List<Rectangle> gameAreas = Collections.emptyList();

	/** False when the map is too narrow for the search bar, which is then neither drawn nor used. */
	private boolean searchAvailable = true;

	private static List<Rectangle> both(List<Rectangle> first, List<Rectangle> second)
	{
		List<Rectangle> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	private boolean overGame(int x, int y)
	{
		for (Rectangle area : gameAreas)
		{
			if (area.contains(x, y))
			{
				return true;
			}
		}
		return false;
	}

	private void drawPlayer(Graphics2D g, ViewTransform view, PlayerPosition position)
	{
		double sx = view.screenX(position.getX());
		double sy = view.screenY(position.getY());
		double radius = Math.max(4.5, Math.min(10.0, view.getPixelsPerTile() * 0.5));
		Ellipse2D ring = circle(sx, sy, radius);
		g.setColor(PLAYER_FILL);
		g.fill(ring);
		g.setColor(PLAYER_RING);
		g.setStroke(new BasicStroke(2f));
		g.draw(ring);
	}

	@Nullable
	private Point mouse(Rectangle bounds)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0)
		{
			return null;
		}
		return bounds.contains(mouse.getX(), mouse.getY()) ? new Point(mouse.getX(), mouse.getY()) : null;
	}
}
