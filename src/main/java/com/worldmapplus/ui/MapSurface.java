package com.worldmapplus.ui;

import com.worldmapplus.*;
import com.worldmapplus.bridge.*;
import com.worldmapplus.compat.*;
import com.worldmapplus.data.*;
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
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
	private static final Color RULER = new Color(120, 255, 160);
	private static final Color ROUTE_TARGET = new Color(255, 214, 0);
	private static final Color ROUTE_START = new Color(90, 220, 120);

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
	/** Where a click that was left for the game began, to tell a click from a drag. */
	private Point gamePressPoint;
	/** The layers list. Built when it is first needed, because its rows come from the bundled data, which loads on first use. */
	private volatile LayerPanel layerPanel;
	private Set<String> poiOn = Collections.emptySet();
	private String poiOnText = "";
	/** The input handler to register with RuneLite for this surface. */
	@Getter
	private final ViewerInput input = new ViewerInput();
	private final ContextMenu menu = new ContextMenu();
	private final Ruler ruler = new Ruler();

	private final MapButtons buttons = new MapButtons();
	private final FloorButtons floorButtons = new FloorButtons();
	private int lastPlayerFloor = -1;
	private final SearchBox searchBox = new SearchBox();
	private final SearchBar searchBar = new SearchBar();
	/** This surface's number for the panels of partners, and where their panel says it is (x, y, width, height, wants a frame). */
	private static final AtomicInteger SURFACES = new AtomicInteger();
	private final int surfaceId = SURFACES.incrementAndGet();
	private final int[] panelBox = new int[5];
	/** True while the thumb of a partner panel's scroll bar is being dragged. */
	private boolean resultsScrollDrag;
	private HoverCardPainter cardPainter;
	private BufferedImage layerImage;

	private MapViewer viewer;
	private boolean showing;
	private String slot = ViewMemory.WORLD_MAP;
	private Rectangle lastBounds = new Rectangle(0, 0, 1, 1);
	private Point pressPoint = new Point();

	/** True while the current press began on the search bar rather than the map. */
	private boolean pressOnControls;

	/** The category of the map key that is flashing, and since when. 0 when none is. */
	private int flashCategory;
	private List<SearchEntry> flashExtra = Collections.emptyList();
	private long flashSince;

	/** True while the thumb of the search results' scroll bar is being dragged. */
	private boolean scrollDrag;

	/** True while the thumb of the layers list's scroll bar, or of the right click menu's, is being dragged. */
	private boolean layerScrollDrag;
	private boolean menuScrollDrag;

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
	private Point rightPress;

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
		return chat != null && !windowed && config.typeInChatArea();
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

	/** Opens a map in a window of its own. */
	public interface WindowOpener
	{
		void open(int mapId, double x, double y, double zoom);
	}

	private WindowOpener windowOpener;

	/** True for the surface of an extra window, which draws on the interface thread and never acts for the game. */
	private boolean windowed;
	private Supplier<Point> pointerSource;
	private Consumer<Runnable> gameThread = Runnable::run;
	private MapInfo startMap;
	private double startX;
	private double startY;
	private double startZoom;
	private Point middlePress;

	/** Where a middle click on a map link, a search result or a step of the route opens a map of its own. */
	public void setWindowOpener(@Nullable WindowOpener opener)
	{
		this.windowOpener = opener;
	}

	/**
	* Makes this the surface of an extra window. The pointer comes from the window and not from the game, requests
	* for another plugin go through {@code onGameThread}, requests from other plugins to move the map are left to the
	* main map, and a window keeps its view however long it is left alone.
	*/
	public void asWindow(Supplier<Point> pointer, Consumer<Runnable> onGameThread)
	{
		this.windowed = true;
		this.pointerSource = pointer;
		this.gameThread = onGameThread;
	}

	/** Where this surface opens, in place of the player or the last view. */
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
		if (!windowed && showing && lastFrame != Long.MIN_VALUE && now - lastFrame > REOPEN_GAP_MILLIS)
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
		slot = options.memorySlot;
		lastBounds = bounds;
		lastPlayer = position;
		refreshSearch();
		applyFocus(now);
		applyShow(now);
		trackFloor(position);

		boolean interactive = options.interactive;
		// Before the layers panel is laid out, since the plugins listed in it come from this.
		syncLayers();
		if (interactive)
		{
			input.setActive(bounds);
			input.setControlFNeedsPointer(options.controlFOverMapOnly);
			input.setPointerOver(mouse(bounds) != null);
			gameAreas = options.passInput.isEmpty() ? options.keepClear : both(options.keepClear, options.passInput);
			input.setKeepClear(gameAreas);
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
			input.setActive(null);
			input.setCaptureKeys(false);
			input.getQueue().clear();
			searchBox.blur();
			menu.close();
			closeLayerPanel();
			clearHeld();
		}
		applySettings();

		pullChat(now);
		for (MapInput event : input.getQueue().drain())
		{
			apply(event, position, now);
		}
		syncChat(interactive);

		double followX = position != null ? position.getX() : viewer.getCamera().getX();
		double followY = position != null ? position.getY() : viewer.getCamera().getY();
		if (followMember(now))
		{
			followX = memberX;
			followY = memberY;
		}
		else if (position != null && !playerIsOnViewedMap(position))
		{
			viewer.getCamera().setFollowing(false);
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
			// The region borders, the grid, the Wilderness lines and the trail are painters of the Helper that draw here, under the icons.
			paintMap = map;
			external.partners().paintUnder(g, view, map.getId(), iconFloor(map), onPaintMap);
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
			drawHops(g, view, map);
			// The Helper's walking lines draw here, under the numbered discs and the pins as they did before the split.
			external.partners().paintRoute(g, view, map.getId(), iconFloor(map), onPaintMap);
			RoutePlan plan = routePlan(position);
			drawRouteLegs(g, view, map, plan);
			drawRouteTarget(g, view, map, position);
			drawFlash(g, view, map, now);
			drawSavedRoutes(g, view, map);
			drawPins(g, view, map);
			external.partners().paint(g, view, map.getId(), iconFloor(map), onPaintMap);
			if (external.partners().searchVersion() != seenSearch)
			{
				// A search provider of another plugin has answered: the list is made again with its results.
				seenSearch = external.partners().searchVersion();
				searchBox.refreshExtras();
			}
			drawFound(g, view, map, now);

			if (position != null && playerIsOnViewedMap(position))
			{
				drawPlayer(g, view, position);
			}

			Point mouse = interactive ? mouse(bounds) : null;
			if (interactive)
			{
				if (config.showCoordinates())
				{
					drawCoordinates(g, bounds, view, map, mouse);
				}
				MapButtons.Button overButton = mouse == null || menu.isOpen() ? null : buttons.hit(mouse.x, mouse.y);
				buttons.paint(g, overButton);
				drawTip(g, overButton);
				drawFloors(g, mouse);
				drawPanels(g, bounds, mouse, now);
				if (bounds.width >= MIN_WIDTH_FOR_SEARCH)
				{
					searchBar.setMissing(config.markUnusable() || config.onlyUsable() ? resultMissing : null);
					searchBar.paint(g, searchBox, mouse, now);
				}
				drawRuler(g, view, map, mouse);
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
			g.setColor(Theme.FRAME);
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

	/** The ruler, for tests. */
	Ruler ruler()
	{
		return ruler;
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
		releaseInput();
		searchBox.blur();
		found = null;
		closePanel();
		if (viewer != null && viewer.getMap() != null)
		{
			memory.save(slot, viewer.getMap().getId(), viewer.getCamera().getX(), viewer.getCamera().getY(),
				viewer.getZoom().targetPixelsPerTile());
		}
	}

	/** Drops all state, for plugin shutdown. */
	public void reset()
	{
		showing = false;
		layerImage = null;
		viewer = null;
		found = null;
		closePanel();
		searchBox.blur();
		releaseInput();
	}

	/** Lets go of the mouse, the keys and the chat area prompt, and forgets the events not yet used. */
	private void releaseInput()
	{
		input.setActive(null);
		input.setCaptureKeys(false);
		input.setCaptureEscape(false);
		input.setWatchClicks(false);
		closeChat();
		input.getQueue().clear();
	}

	/** Forgets which part of the map a button held down was pressed on. */
	private void clearPresses()
	{
		pressOnControls = false;
		scrollDrag = false;
		layerScrollDrag = false;
		menuScrollDrag = false;
		pressOnMenu = false;
	}

	private void applySettings()
	{
		viewer.getZoom().setSpeed(config.zoomSpeed() / 100.0);
		viewer.getZoom().setAcceleration(config.zoomAcceleration() / 100.0);
		viewer.getZoom().setSmoothingSeconds(config.zoomSmoothing() / 1000.0);
		assets.getRenderer().setSmoothMagnify(config.smoothMagnify());
		Motion.use(config.animations());
	}

	private Sounds sounds;

	/** The cues this map plays when the setting is on. Never set for the extra windows, which draw off the client thread. */
	public void setSounds(@Nullable Sounds sounds)
	{
		this.sounds = sounds;
	}

	/** Plays a cue: only on the client thread's surfaces, and only when sounds were handed over. */
	void cue(Sounds.Cue cue)
	{
		Sounds each = sounds;
		if (each != null && !windowed)
		{
			each.cue(cue);
		}
	}

	/** Called when the world map closes. */
	public void closedCue()
	{
		cue(Sounds.Cue.CLOSE);
	}

	/** Goes to a place: a glide on the map already shown when animations are on, a jump otherwise. */
	private void goTo(MapInfo map, double x, double y, long now)
	{
		if (Motion.full())
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
		if (Motion.full())
		{
			viewer.getZoom().easeTo(zoom);
		}
		else
		{
			viewer.getZoom().jumpTo(zoom);
		}
	}

	/** True while something of the map is still moving, so the caller can keep drawing. Nothing is, when the map is still. */
	public boolean isMoving()
	{
		long now = lastFrame;
		return viewer != null && (viewer.isMoving() || menu.moving(now) || panelBox[4] != 0
			|| layerPanel != null && layerPanel.moving(now));
	}

	/** Comes back after a short absence with the view, search and marks exactly as they were. */
	private void resume()
	{
		showing = true;
		input.getQueue().clear();
		searchBox.blur();
		clearPresses();
		menu.close();
		closeLayerPanel();
		viewer.cancelGesture();
	}

	/**
	 * Lets go of the mouse and keyboard without forgetting anything, for a moment when the map cannot
	 * be drawn, such as while the game rebuilds its window. It is drawn again, as it was, when the
	 * frames come back.
	 */
	public void suspend()
	{
		releaseInput();
		clearPresses();
		if (viewer != null)
		{
			viewer.cancelGesture();
		}
	}

	private void begin(@Nullable PlayerPosition position, Options options)
	{
		showing = true;
		cue(Sounds.Cue.OPEN);
		lastPlayerFloor = -1;
		input.getQueue().clear();
		searchBox.setTileMap((x, y) ->
		{
			MapInfo at = assets.getCatalog().mapFor(x, y);
			return at == null ? -1 : at.getId();
		});
		searchBox.setContext(this::searchContext);
		searchBox.setExtra(external.partners()::search);
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
		viewer.getZoom().jumpTo(zoom);
		viewer.getCamera().setFollowing(follow);
		followName = null;
	}

	// ------------------------------------------------------------------
	// Input
	// ------------------------------------------------------------------

	/** An event this map saw but left for the game. Only a click is used, to tell what it landed on. */
	private void applyLeftForGame(MapInput event, @Nullable PlayerPosition position)
	{
		if (event.kind == MapInput.Kind.PRESS && event.button() == MouseEvent.BUTTON1)
		{
			gamePressPoint = new Point(event.x, event.y);
		}
		else if (event.kind == MapInput.Kind.RELEASE && event.button() == MouseEvent.BUTTON1)
		{
			if (isClick(gamePressPoint, event))
			{
				click(event.x, event.y, position);
			}
			gamePressPoint = null;
		}
	}

	/** True when a button is let go within a few pixels of where it went down. */
	private static boolean isClick(@Nullable Point press, MapInput event)
	{
		return press != null && Math.hypot(event.x - press.x, event.y - press.y) <= 5;
	}

	private static int wheelRows(MapInput event)
	{
		return event.amount > 0 ? WHEEL_ROWS : -WHEEL_ROWS;
	}

	private void apply(MapInput event, @Nullable PlayerPosition position, long now)
	{
		if (!event.taken)
		{
			applyLeftForGame(event, position);
			return;
		}
		switch (event.kind)
		{
			case WHEEL:
				if (menu.contains(event.x, event.y))
				{
					// A menu taller than the map scrolls, and the wheel over it never zooms the map under it.
					if (event.amount != 0)
					{
						menu.scroll(wheelRows(event));
					}
				}
				else if (layerPanel != null && layerPanel.contains(event.x, event.y))
				{
					if (event.amount != 0)
					{
						layerPanel.scroll(wheelRows(event));
					}
				}
				else if (Partners.onPanel(panelBox, event.x, event.y) && !searchBar.overResults(event.x, event.y))
				{
					if (event.amount != 0)
					{
						external.partners().panelInput(surfaceId, "wheel", event.x, event.y, wheelRows(event));
					}
				}
				else if (searchBar.overResults(event.x, event.y))
				{
					if (searchBar.scrolls())
					{
						if (event.amount != 0)
						{
							searchBox.scroll(wheelRows(event));
						}
					}
				}
				else if (!isControl(event.x, event.y))
				{
					viewer.onWheel(event.amount, event.x, event.y, event.time);
				}
				break;
			case PRESS:
				if (pinnedCard != null && !menu.isOpen())
				{
					pinnedCard = null;
				}
				if (event.button() == MouseEvent.BUTTON3)
				{
					rightPress = new Point(event.x, event.y);
				}
				else if (event.button() == MouseEvent.BUTTON1 && menu.isOpen())
				{
					menuScrollDrag = menu.scrollBarHit(event.x, event.y);
					if (menuScrollDrag)
					{
						menu.dragBarTo(event.y);
					}
					pressOnMenu = menuScrollDrag || menu.hit(event.x, event.y) != null;
					pressPoint = new Point(event.x, event.y);
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
				else if (event.button() == MouseEvent.BUTTON2)
				{
					middlePress = new Point(event.x, event.y);
				}
				else if (event.button() == MouseEvent.BUTTON1)
				{
					pressOnControls = isControl(event.x, event.y);
					if (pressOnControls)
					{
						pressPoint = new Point(event.x, event.y);
						scrollDrag = searchBar.hit(event.x, event.y).isScrollBar();
						layerScrollDrag = layerPanel != null && layerPanel.scrollBarHit(event.x, event.y);
						resultsScrollDrag = !scrollDrag && !layerScrollDrag && searchBar.hit(event.x, event.y) == SearchBar.Hit.NONE
							&& Partners.onPanel(panelBox, event.x, event.y) && external.partners().panelInput(surfaceId, "press", event.x, event.y, 0);
						if (scrollDrag)
						{
							searchBox.scrollToFraction(searchBar.scrollFractionAt(event.y));
						}
						else if (layerScrollDrag)
						{
							layerPanel.dragBarTo(event.y);
						}
					}
					else
					{
						viewer.onPress(event.x, event.y, event.time);
					}
				}
				break;
			case DRAG:
				if (menuScrollDrag)
				{
					menu.dragBarTo(event.y);
				}
				else if (layerScrollDrag && layerPanel != null)
				{
					layerPanel.dragBarTo(event.y);
				}
				else if (resultsScrollDrag)
				{
					external.partners().panelInput(surfaceId, "drag", event.x, event.y, 0);
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
				if (event.button() == MouseEvent.BUTTON3)
				{
					if (isClick(rightPress, event))
					{
						openMenu(event.x, event.y);
					}
					rightPress = null;
				}
				else if (event.button() == MouseEvent.BUTTON2)
				{
					if (isClick(middlePress, event))
					{
						openInWindow(event.x, event.y);
					}
					middlePress = null;
				}
				else if (event.button() == MouseEvent.BUTTON1)
				{
					if (pressOnMenu)
					{
						pressOnMenu = false;
						if (menuScrollDrag)
						{
							// Letting go of the bar leaves the menu open.
							menuScrollDrag = false;
						}
						else
						{
							ContextMenu.Item item = menu.hit(pressPoint.x, pressPoint.y);
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
						if (resultsScrollDrag)
						{
							resultsScrollDrag = false;
							external.partners().panelInput(surfaceId, "release", event.x, event.y, 0);
						}
						else if (scrollDrag)
						{
							scrollDrag = false;
						}
						else if (layerScrollDrag)
						{
							layerScrollDrag = false;
						}
						else
						{
							clickSearch(pressPoint.x, pressPoint.y, now);
						}
					}
					else if (viewer.onRelease(event.time))
					{
						Point press = viewer.getPressPoint();
						click(press.x, press.y, position);
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
				if (event.keyCode() == KeyEvent.VK_ESCAPE && popupOpen())
				{
					// Escape puts away what is popped up over the map, first, and only then ends a search.
					closeLayerPanel();
					menu.close();
					break;
				}
				if (!usesChat() || event.keyCode() == ViewerInput.KEY_FOCUS_SEARCH)
				{
					key(event.keyCode(), now);
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
			pressButton(heldCard.getButtonKey(), Partners.subject(heldCard.getTitle(), heldCard.getWiki(), heldCard.getWikiSearch()));
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
		if (hit == SearchBar.Hit.NONE && Partners.onPanel(panelBox, x, y))
		{
			external.partners().panelInput(surfaceId, "click", x, y, 0);
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
				viewer.getZoom().easeTo(viewer.getZoom().targetPixelsPerTile() * BUTTON_ZOOM_FACTOR);
				break;
			case ZOOM_OUT:
				viewer.getZoom().easeTo(viewer.getZoom().targetPixelsPerTile() / BUTTON_ZOOM_FACTOR);
				break;
			case LOCATE:
				locate(position);
				break;
			default:
				break;
		}
	}

	/**
	* A middle click on something that leads somewhere opens that place in a window of its own: a link to another map,
	* a search result, or a step of the route. Anywhere else it does nothing.
	*/
	private void openInWindow(int x, int y)
	{
		if (windowOpener == null || viewer == null || viewer.getMap() == null)
		{
			return;
		}
		double zoom = viewer.getZoom().targetPixelsPerTile();
		SearchBar.Hit hit = searchBar.hit(x, y);
		if (hit.getRow() >= 0)
		{
			int index = searchBox.getScrollTop() + hit.getRow();
			if (index < searchBox.getResults().size())
			{
				SearchEntry entry = nearestTo(searchBox.getResults().get(index), lastPlayer);
				windowOpener.open(entry.getMapId(), entry.getX(), entry.getY(), Math.max(zoom, FOUND_MIN_ZOOM));
			}
			return;
		}
		int step = routeRowAt(x, y);
		if (step >= 0)
		{
			RoutePlan.Step from = routeRowSteps.get(step);
			MapInfo at = assets.getCatalog().mapFor((int) Math.floor(from.getFromX()), (int) Math.floor(from.getFromY()));
			windowOpener.open(at == null ? assets.getCatalog().surface().getId() : at.getId(), from.getFromX(), from.getFromY(),
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
				windowOpener.open(target.getMapId(), target.getX(), target.getY(), zoom);
				return;
			}
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
			else if (candidate.what instanceof Partners.Thing)
			{
				external.partners().clicked((Partners.Thing) candidate.what);
			}
			else
			{
				Hotspot.Target target = ((Hotspot) candidate.what).getTarget();
				MapInfo targetMap = assets.getCatalog().byId(target.getMapId());
				if (targetMap != null)
				{
					viewer.getCamera().setFollowing(false);
					viewer.showMap(targetMap, target.getX(), target.getY());
				}
			}
			return;
		}
	}

	/** The transform in force right now. Package visible so tests can aim at things on screen. */
	ViewTransform currentView()
	{
		return new ViewTransform(viewer.getCamera().getX(), viewer.getCamera().getY(),
			viewer.currentPixelsPerTile(), lastBounds.x, lastBounds.y, lastBounds.width, lastBounds.height);
	}

	/** Takes the map to a search result and marks it. */
	private void activate(@Nullable SearchEntry entry, long now)
	{
		if (entry == null)
		{
			return;
		}
		cue(Sounds.Cue.CHOOSE);
		external.partners().selected(entry);
		if (entry.getKind() == SearchKind.ROUTE)
		{
			// A saved route found in the search starts as its Start button in the side panel starts it (round 48).
			startRoute(entry.getOwnId());
			searchBox.blur();
			return;
		}
		external.partners().chosen(surfaceId, entry);
		if (entry.getReveal() != null)
		{
			revealCategory(entry.getReveal(), FOUND_MILLIS);
		}
		MapInfo target = assets.getCatalog().byId(entry.getMapId());
		if (target == null)
		{
			return;
		}
		entry = chooseAmongPlaces(entry, now);
		viewer.getCamera().setFollowing(false);
		goTo(target, entry.getX(), entry.getY(), now);

		double zoom = viewer.getZoom().targetPixelsPerTile();
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

	/** Puts away the panels of partners on this surface, for when the map closes. */
	private void closePanel()
	{
		panelBox[2] = 0;
		panelBox[4] = 0;
		if (external.partners().any(Partners.Kind.PANEL))
		{
			external.partners().panelInput(surfaceId, "close", 0, 0, 0);
		}
	}

	/** Lets the partners' panels (the results panel of the Helper) draw under the route panel at the left of the map. */
	private void drawPanels(Graphics2D g, Rectangle bounds, @Nullable Point mouse, long now)
	{
		if (!external.partners().any(Partners.Kind.PANEL))
		{
			panelBox[2] = 0;
			panelBox[4] = 0;
			return;
		}
		int top = Math.max(bounds.y + 40, routePanelRect.isEmpty() ? 0 : routePanelRect.y + routePanelRect.height + 8);
		int map = kindPlayerMap();
		external.partners().paintPanels(g, surfaceId, panelBox, bounds, top, buttons.block().isEmpty() ? bounds.x + bounds.width : buttons.block().x,
			mouse, now, Motion.fades(), lastPlayer != null && map >= 0 ? map : -1, lastPlayer == null ? 0 : lastPlayer.getX(),
			lastPlayer == null ? 0 : lastPlayer.getY());
	}

	/** Takes the map to a place a partner's panel asked for and marks its tiles, as a chosen search result is marked. */
	private void applyShow(long now)
	{
		Partners.Show show = external.partners().takeShow(surfaceId);
		MapInfo target = show == null || viewer == null ? null : assets.getCatalog().byId(show.mapId);
		if (target == null)
		{
			return;
		}
		viewer.getCamera().setFollowing(false);
		goTo(target, show.x, show.y, now);
		zoomForJump(Math.max(viewer.getZoom().targetPixelsPerTile(), FOUND_MIN_ZOOM));
		placesKey = "";
		foundNumber = 0;
		foundOf = 0;
		found = new SearchEntry(SearchKind.PIN, show.name, show.detail, show.mapId, show.x, show.y, show.marks);
		foundSince = now;
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

	/** Draws the game's icons of the categories with this name at every zoom, from now until a while after the given time. */
	private void revealCategory(@Nullable String name, long millis)
	{
		List<Integer> numbers = name == null ? Collections.<Integer>emptyList() : assets.getElements().categoriesNamed(name);
		int[] categories = new int[numbers.size()];
		for (int i = 0; i < categories.length; i++)
		{
			categories[i] = numbers.get(i);
		}
		icons().reveal(categories, System.currentTimeMillis() + millis + REVEAL_AFTER_MILLIS);
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
		viewer.getCamera().setFollowing(true);
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
		g.setFont(Theme.BODY_FONT);
		FontMetrics metrics = g.getFontMetrics();
		Rectangle rect = buttons.boundsOf(button);
		String tip = button.tip();
		String drawer = external.lineUpTip();
		if (button == MapButtons.Button.LINE_UP && !drawer.isEmpty())
		{
			tip = drawer;
		}
		// To the left of the whole stack of buttons, so it never covers one, and inside the map: a tag too wide for the room
		// there is wrapped onto more lines.
		Rectangle block = buttons.block();
		int room = Math.max(40, block.x - 6 - (lastBounds.x + PopupText.EDGE) - 16);
		List<String> lines = PopupText.wrap(tip, metrics, room);
		int widest = 0;
		for (String line : lines)
		{
			widest = Math.max(widest, metrics.stringWidth(line));
		}
		int width = widest + 16;
		int height = lines.size() * metrics.getHeight() + 8;
		int x = Math.max(lastBounds.x + 2, block.x - width - 6);
		int top = lastBounds.y + PopupText.EDGE;
		int y = Math.max(top, Math.min(rect.y + (rect.height - height) / 2, lastBounds.y + lastBounds.height - PopupText.EDGE - height));
		Theme.panel(g, x, y, width, height);
		g.setColor(Theme.TEXT);
		for (int i = 0; i < lines.size(); i++)
		{
			g.drawString(lines.get(i), x + 8, y + 4 + metrics.getAscent() + i * metrics.getHeight());
		}
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
		revealCategory(flashCategory == 0 ? null : assets.getElements().categoryName(flashCategory), FLASH_MILLIS);
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

	/** The ring round one icon of the flashing kind: a dark edge, so it shows over any ground, then yellow. */
	private void drawRing(Graphics2D g, double sx, double sy)
	{
		g.setColor(Color.BLACK);
		g.draw(new Ellipse2D.Double(sx - 9.5, sy - 9.5, 19, 19));
		g.setColor(FLASH);
		g.draw(new Ellipse2D.Double(sx - 8.5, sy - 8.5, 17, 17));
	}

	private static final Color FLASH = new Color(255, 214, 0);

	/**
	 * Rings round every icon of the kind clicked in the map key, and a plate saying what is flashing and how many there are on this
	 * map. They show at any zoom, so a kind of icon can be found from far out.
	 */
	private void drawFlash(Graphics2D g, ViewTransform view, MapInfo map, long now)
	{
		if (flashCategory == 0 || map == null)
		{
			return;
		}
		if (now - flashSince > FLASH_MILLIS)
		{
			flashCategory = 0;
			return;
		}
		int count = 0;
		g.setStroke(new BasicStroke(2f));
		for (MapElements.Spot spot : assets.getElements().spotsOn(map.getId()))
		{
			if (spot.getCategory() == flashCategory)
			{
				count++;
				ringAt(g, view, spot.getX(), spot.getY());
			}
		}
		for (SearchEntry extra : flashExtra)
		{
			if (extra.getMapId() == map.getId())
			{
				count++;
				ringAt(g, view, extra.getX(), extra.getY());
			}
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

	private void ringAt(Graphics2D g, ViewTransform view, double x, double y)
	{
		double sx = view.screenX(x);
		double sy = view.screenY(y);
		if (view.contains(sx, sy))
		{
			drawRing(g, sx, sy);
		}
	}

	/** Rebuilds the search index when the pins changed, so pins can be found by name. */
	private void refreshSearch()
	{
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
		if (windowed)
		{
			return;
		}
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
		viewer.getCamera().setFollowing(false);
		goTo(target, point.getX() + 0.5, point.getY() + 0.5, now);
		double zoom = request.getZoom() > 0 ? request.getZoom()
			: Math.max(viewer.getZoom().targetPixelsPerTile(), FOUND_MIN_ZOOM);
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
		viewer.getCamera().setFollowing(false);
		viewer.showMap(target, x + 0.5, y + 0.5);
	}

	// ------------------------------------------------------------------
	// Following a point that moves (the Helper sends where a party member is)
	// ------------------------------------------------------------------

	/** The entries other plugins added to the open menu, and what they are told when one is chosen. */
	private List<Partners.Slot> menuPartners = Collections.emptyList();
	private Map<String, Object> menuContext = Collections.emptyMap();
	private int seenSearch;
	/** The map the partners' painters and hit tests are asked about, and the question they may ask: is a place on it. */
	private MapInfo paintMap;
	private final BiPredicate<Double, Double> onPaintMap = (x, y) -> paintMap != null && onThisMap(paintMap, x, y);

	/** The name of the point being followed, or null. */
	private String followName;
	private long followSeenAt;
	private double memberX;
	private double memberY;

	/** How long a followed point may go without news before following ends, in milliseconds. */
	static final long FOLLOW_GRACE_MILLIS = 1_500;

	/** The entries other plugins add to the menu, asked about this place and what is under the pointer. */
	private void addPartnerItems(List<ContextMenu.Item> items, ViewTransform view, MapInfo map, int x, int y, int tx, int ty)
	{
		menuPartners = Collections.emptyList();
		if (external.partners().any(Partners.Kind.MENU))
		{
			Map<String, Object> context = new HashMap<>();
			context.put("tile", new WorldPoint(tx, ty, planeNow()));
			context.put("mapId", map.getId());
			context.put("floor", planeNow());
			List<Candidate> under = candidates(view, map, x, y);
			if (!under.isEmpty())
			{
				context.put("thing", thingOf(under.get(0)));
				List<Map<String, Object>> all = new ArrayList<>(under.size());
				for (int i = 0; i < under.size() && i < 6; i++)
				{
					all.add(thingOf(under.get(i)));
				}
				context.put("things", Collections.unmodifiableList(all));
			}
			menuContext = Collections.unmodifiableMap(context);
			menuPartners = external.partners().menu(menuContext);
			menuPartners.forEach(slot -> items.add(new ContextMenu.Item(ContextMenu.Action.PARTNER_OPTION, slot.title)));
		}
	}

	/** Keeps the map on a point until the map is moved, the point goes, or something else takes the map. */
	private void startFollowing(String name, WorldPoint at)
	{
		if (viewer == null)
		{
			return;
		}
		if (isLinedUp())
		{
			external.followEnded(name);
			say("Following is off while lined up with the game map.");
			return;
		}
		followName = name;
		followSeenAt = lastFrame;
		memberX = at.getX() + 0.5;
		memberY = at.getY() + 0.5;
		viewer.getCamera().setFollowing(true);
		say("Following " + name + ". Drag the map to stop.");
	}

	private void stopFollowing(@Nullable String why)
	{
		if (followName == null)
		{
			return;
		}
		external.followEnded(followName);
		followName = null;
		if (viewer != null)
		{
			viewer.getCamera().setFollowing(false);
		}
		if (why != null)
		{
			say(why);
		}
	}

	/** The point being followed, or null. For tests. */
	@Nullable
	String following()
	{
		return followName;
	}

	/**
	 * Works out where the followed point is, once a frame. True when the camera should follow {@link #memberX} and {@link #memberY}.
	 * The Helper sends the point when it moves; nothing is read here. Following ends when the camera has stopped following (the
	 * player dragged the map, or a search, a focus or Locate me took it), in line up mode, when the Helper says the point is gone, or
	 * when no news has come for {@link #FOLLOW_GRACE_MILLIS}.
	 */
	private boolean followMember(long now)
	{
		ExternalOverlays.FollowRequest request = external.takeFollow();
		if (request != null)
		{
			if ("start".equals(request.getOp()) && request.getPoint() != null)
			{
				startFollowing(request.getName(), request.getPoint());
			}
			else if (followName != null && followName.equals(request.getName()))
			{
				if ("stop".equals(request.getOp()))
				{
					stopFollowing(null);
					return false;
				}
				if ("gone".equals(request.getOp()))
				{
					stopFollowing(followName + " is no longer on the map, so following stopped.");
					return false;
				}
				if (request.getPoint() != null)
				{
					memberX = request.getPoint().getX() + 0.5;
					memberY = request.getPoint().getY() + 0.5;
					followSeenAt = now;
				}
			}
		}
		if (followName == null)
		{
			return false;
		}
		if (!viewer.getCamera().isFollowing() || isLinedUp())
		{
			external.followEnded(followName);
			followName = null;
			return false;
		}
		MapInfo target = assets.getCatalog().mapNear((int) memberX, (int) memberY, NEAR_MAP_TILES);
		if (target == null)
		{
			if (now - followSeenAt > FOLLOW_GRACE_MILLIS)
			{
				stopFollowing(followName + " is somewhere this map has no picture of, so following stopped.");
				return false;
			}
			memberX = viewer.getCamera().getX();
			memberY = viewer.getCamera().getY();
			return true;
		}
		MapInfo exact = assets.getCatalog().mapFor((int) memberX, (int) memberY);
		MapInfo shown = viewer.getMap();
		if (shown == null || !shown.contains((int) memberX, (int) memberY) && (exact != null || shown.getId() != target.getId()))
		{
			// The point went to another of this plugin's maps, such as down a ladder: that map is shown, and following goes on.
			viewer.showMap(exact != null ? exact : target, memberX, memberY);
		}
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
		menuButton = null;
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
		HoverCard buttonCard = pin == null ? buttonCardAt(view, map, x, y) : null;
		if (buttonCard != null)
		{
			menuButton = buttonCard.getButtonKey();
			menuSubject = Partners.subject(buttonCard.getTitle(), buttonCard.getWiki(), buttonCard.getWikiSearch());
			items.add(new ContextMenu.Item(ContextMenu.Action.CARD_BUTTON, buttonCard.getButtonMenu()));
		}
		if (pin == null)
		{
			if (menuSave != null)
			{
				items.add(new ContextMenu.Item(ContextMenu.Action.SAVE_PLACE, "Save place: " + menuSave.name));
			}
			items.add(new ContextMenu.Item(ContextMenu.Action.ADD_PIN, "Add pin here"));
		}
		items.add(ruler.isActive()
			? new ContextMenu.Item(ContextMenu.Action.STOP_MEASURING, "Stop measuring")
			: new ContextMenu.Item(ContextMenu.Action.MEASURE, "Measure from here"));
		addPartnerItems(items, view, map, x, y, tx, ty);

		openMenuAt(items, x, y, tx, ty);
	}

	/** Opens the menu at a point of the screen. Its subject is the place it is about, a tile or a result's spot. */
	private void openMenuAt(List<ContextMenu.Item> items, int x, int y, double placeX, double placeY)
	{
		// The menu's width needs font metrics, which a throwaway image can supply.
		Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
		probe.setFont(ContextMenu.font());
		int width = ContextMenu.widthFor(items, probe.getFontMetrics());
		probe.dispose();
		menu.open(items, x, y, width, lastBounds, new double[] {placeX, placeY});
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
		menuButton = null;
		Partners.Button entryButton = null;
		if (config.showWikiButtons())
		{
			menuSubject = Partners.subject(entry.getName(), wikiOf(entry), wikiSearchFor(entry));
			entryButton = external.partners().button(menuSubject);
			menuButton = entryButton == null ? null : entryButton.key;
		}
		menuSave = new SaveCandidate(entry.getName(), kindOf(entry.getKind()), entry.getMapId(), entry.getX(), entry.getY());
		List<ContextMenu.Item> items = new ArrayList<>();
		items.add(new ContextMenu.Item(ContextMenu.Action.SHOW_RESULT, "Show on the map"));
		items.add(new ContextMenu.Item(ContextMenu.Action.SAVE_PLACE, "Save place: " + entry.getName()));
		if (entryButton != null)
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.CARD_BUTTON, entryButton.menu));
		}
		MapInfo surface = assets.getCatalog().surface();
		if (surface != null && entry.getMapId() == surface.getId())
		{
			items.add(new ContextMenu.Item(ContextMenu.Action.ROUTE, "Route here (Shortest Path)"));
		}
		items.add(new ContextMenu.Item(ContextMenu.Action.COPY_TILE, "Copy tile  " + (int) Math.floor(entry.getX()) + ", "
		+ (int) Math.floor(entry.getY())));
		openMenuAt(items, x, y, entry.getX(), entry.getY());
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
		askShortestPath(RouteNavigator.pointOf(pin), actions::routeTo);
	}

	/** Stops the saved routes and puts a place to Shortest Path in one of the ways of {@link MapActions}, then says how it went. */
	private void askShortestPath(WorldPoint place, Function<WorldPoint, MapActions.Status> way)
	{
		routes.stopAll();
		gameThread.accept(() -> routeNotice = noticeFor(way.apply(place)));
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
				askShortestPath(new WorldPoint(tx, ty, menuPlane >= 0 ? menuPlane : planeNow()), actions::retarget);
				break;
			case SET_ROUTE_START:
				askShortestPath(new WorldPoint(tx, ty, menuPlane >= 0 ? menuPlane : planeNow()), actions::setStart);
				break;
			case ADD_ROUTE_TARGET:
				askShortestPath(new WorldPoint(tx, ty, menuPlane >= 0 ? menuPlane : planeNow()), actions::addTarget);
				break;
			case CLEAR_ROUTE:
				routes.stopAll();
				gameThread.accept(actions::clearRoute);
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
				cue(Sounds.Cue.PIN);
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
			case CARD_BUTTON:
				pressButton(menuButton, menuSubject);
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
			case MEASURE:
				ruler.start(mapId, tx, ty);
				break;
			case STOP_MEASURING:
				ruler.stop();
				break;
			case PARTNER_OPTION:
				menuPartners.stream().filter(slot -> slot.title.equals(item.getLabel())).findFirst()
					.ifPresent(slot -> external.partners().choose(slot, menuContext));
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
		if (map == null)
		{
			return;
		}
		List<SavedRoute> drawn = routes.drawn();
		if (drawn.isEmpty())
		{
			return;
		}
		g.setFont(Palette.TINY);
		FontMetrics metrics = g.getFontMetrics();
		SavedRoute following = routes.active();
		for (SavedRoute route : drawn)
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
			for (int i = 1; i < stops.size(); i++)
			{
				drawLeg(g, view, map, stops.get(i - 1), stops.get(i), color);
			}
			if (route.isLoop() && stops.size() > 1)
			{
				drawLeg(g, view, map, stops.get(stops.size() - 1), stops.get(0), color);
			}
			// The numbers are the stops' places in the whole route, wherever the other stops are.
			for (int i = 0; i < stops.size(); i++)
			{
				Pin stop = stops.get(i);
				if (!shown(stop, map))
				{
					continue;
				}
				double sx = view.screenX(stop.getX());
				double sy = view.screenY(stop.getY());
				if (!view.contains(sx, sy))
				{
					continue;
				}
				Ellipse2D badge = new Ellipse2D.Double(sx - 8, sy + 3, 16, 16);
				g.setColor(color);
				g.fill(badge);
				g.setColor(Color.BLACK);
				String text = String.valueOf(i + 1);
				g.drawString(text, (float) (sx - metrics.stringWidth(text) / 2.0),
					(float) (sy + 11 + (metrics.getAscent() - metrics.getDescent()) / 2.0));
			}
		}
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
		g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {9f, 6f}, 0f));
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
		if (map == null)
		{
			return;
		}
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

	/** The tick boxes the Helper listed under the layers button: plugins with points on the game's map, and layers other plugins named. */
	private void syncLayers()
	{
		LayerPanel panel = layerPanel();
		panel.setPlugins(external.pluginLayers());
		panel.setLayers(external.sentLayers());
	}

	/** What the partners' hit tests are told about a place: whether it belongs on the map being asked about. */
	private BiPredicate<Double, Double> onHitMap(MapInfo map)
	{
		paintMap = map;
		return onPaintMap;
	}

	/** The jumps of the route Shortest Path found, drawn as curves with their names. */
	private void drawHops(Graphics2D g, ViewTransform view, MapInfo map)
	{
		if (map == null)
		{
			return;
		}
		List<RouteHop> hops = external.hops();
		if (hops.isEmpty())
		{
			return;
		}
		g.setFont(Palette.BODY);
		g.setColor(HOP_COLOR);
		for (RouteHop hop : hops)
		{
			if (map.contains((int) hop.getFromX(), (int) hop.getFromY()))
			{
				drawHop(g, view, new double[] {hop.getFromX(), hop.getFromY()}, new double[] {hop.getToX(), hop.getToY()}, hop.getLabel());
			}
		}
	}

	private static final Color HOP_COLOR = new Color(255, 120, 40);

	/** A single jump, such as a boat or a teleport, as a curve with an arrowhead and its name. */
	private void drawHop(Graphics2D g, ViewTransform view, double[] from, double[] to, String label)
	{
		double x1 = view.screenX(from[0]);
		double y1 = view.screenY(from[1]);
		double x2 = view.screenX(to[0]);
		double y2 = view.screenY(to[1]);
		double mx = (x1 + x2) / 2;
		double my = (y1 + y2) / 2 - Math.hypot(x2 - x1, y2 - y1) * 0.2;
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {7f, 5f}, 0f));
		g.draw(new QuadCurve2D.Double(x1, y1, mx, my, x2, y2));
		g.setStroke(new BasicStroke(1f));
		g.fill(new Ellipse2D.Double(x2 - 4, y2 - 4, 8, 8));
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
		return config.syncGameMap() && external.needsGameMap() && map != null && map.getId() < 10000;
	}

	/** The world x this map is centred on. */
	public double centerX()
	{
		return viewer.getCamera().getX();
	}

	/** The world y this map is centred on. */
	public double centerY()
	{
		return viewer.getCamera().getY();
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
			|| viewer.getZoom().clamp(theirs.getZoom()) != theirs.getZoom())
		{
			input.setTakes(null);
			return null;
		}
		linedUp = true;
		input.setTakes(this::takesPoint);
		double zoom = theirs.getZoom();
		Camera camera = viewer.getCamera();
		double[] theirCenter = LineUp.centerFor(theirs.getX(), theirs.getY(), zoom, bounds.width, bounds.height);

		// Following the player is done by sending the game's map to the player once, not by following them every frame,
		// which would take the game's map back each time the player moves it.
		boolean sendToPlayer = camera.isFollowing() && position != null;
		if (camera.isFollowing())
		{
			camera.setFollowing(false);
			if (position != null)
			{
				camera.setPosition(position.getX(), position.getY());
			}
		}
		boolean movedHere = sendToPlayer || !Double.isNaN(putX)
			&& Math.hypot(camera.getX() - putX, camera.getY() - putY) > MOVED_HERE_TILES;
		if (!movedHere && catchingUp
			&& (Math.hypot(theirCenter[0] - camera.getX(), theirCenter[1] - camera.getY()) <= CAUGHT_UP_TILES
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
			int[] tile = LineUp.tileFor(camera.getX(), camera.getY(), zoom, bounds.width, bounds.height);
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
			camera.setPosition(theirCenter[0], theirCenter[1]);
		}
		viewer.getZoom().jumpTo(zoom);
		return new double[] {zoom, follow ? 1 : 0, theirCenter[0], theirCenter[1]};
	}

	/** Finishes a frame lined up: the view is exactly where the game's map is, whatever the map's own limits did. */
	private ViewTransform lineUpFinish(double[] state, ViewTransform view, Rectangle bounds)
	{
		Camera camera = viewer.getCamera();
		if (state[1] != 0)
		{
			camera.setPosition(state[2], state[3]);
		}
		putX = camera.getX();
		putY = camera.getY();
		return new ViewTransform(camera.getX(), camera.getY(), state[0], bounds.x, bounds.y, bounds.width, bounds.height);
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
		return buttons.hit(x, y) != null || floorButtons.hit(x, y) != null || isControl(x, y) || menu.isOpen()
			|| ruler.isActive() || pinnedCard != null;
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

	/** The frame's time, for things that move on their own. */
	private long animNow;

	/** What was last told to the partners of the route (a hash of the steps) and when, so a route that did not change is not sent again. */
	private long routeSent;
	private long routeSentAt;
	private static final long ROUTE_PUSH_MILLIS = 250;

	/**
	 * The jumps of the route as numbered discs. The walks between them are drawn by the Helper, which is told the steps when they change
	 * (at most four times a second): this plugin alone shows the route as these discs and the step list.
	 */
	private void drawRouteLegs(Graphics2D g, ViewTransform view, MapInfo map, RoutePlan plan)
	{
		shareRoute(plan);
		if (map == null || plan.isEmpty())
		{
			return;
		}
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		int number = 0;
		for (RoutePlan.Step step : plan.getSteps())
		{
			number++;
			if (!step.isWalk() && map.contains((int) step.getFromX(), (int) step.getFromY()))
			{
				double sx = view.screenX(step.getFromX());
				double sy = view.screenY(step.getFromY());
				Ellipse2D disc = new Ellipse2D.Double(sx - 9, sy - 9, 18, 18);
				g.setColor(JUMP);
				g.fill(disc);
				g.setColor(PLAYER_RING);
				g.setStroke(new BasicStroke(1.5f));
				g.draw(disc);
				String text = String.valueOf(number);
				g.setColor(Color.BLACK);
				g.drawString(text, (float) (sx - metrics.stringWidth(text) / 2.0),
					(float) (sy + (metrics.getAscent() - metrics.getDescent()) / 2.0));
			}
		}
	}

	/** Tells the partners the steps of the route when they are not what they were last told. */
	private void shareRoute(RoutePlan plan)
	{
		long hash = plan.getSteps().size();
		for (RoutePlan.Step step : plan.getSteps())
		{
			hash = hash * 31 + Double.doubleToLongBits(step.getFromX() + step.getFromY() * 7 + step.getToX() * 13 + step.getToY() * 17)
				+ step.getFromPlane() * 5 + step.getToPlane() * 3 + (step.isWalk() ? 1 : 0);
		}
		long now = System.currentTimeMillis();
		if (hash == routeSent || now - routeSentAt < ROUTE_PUSH_MILLIS)
		{
			return;
		}
		routeSent = hash;
		routeSentAt = now;
		List<double[]> steps = new ArrayList<>(plan.getSteps().size());
		for (RoutePlan.Step step : plan.getSteps())
		{
			steps.add(new double[] {step.getFromX(), step.getFromY(), step.getToX(), step.getToY(), step.getFromPlane(), step.getToPlane(),
				step.isWalk() ? 1 : 0, step.getTiles()});
		}
		external.partners().route(steps);
	}

	/** Lines of the route a panel shows. */
	private static final int ROUTE_PANEL_ROWS = 9;

	/** Where the route panel is and where each of its rows is, as of the last frame. */
	private Rectangle routePanelRect = new Rectangle();
	private final List<Rectangle> routeRows = new ArrayList<>();
	private final List<RoutePlan.Step> routeRowSteps = new ArrayList<>();
	private final Fade stepFade = new Fade();

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
		viewer.getCamera().setFollowing(false);
		goTo(target, step.getFromX(), step.getFromY(), lastFrame);
		if (hasFloors(target))
		{
			viewer.setFloor(Math.max(0, Math.min(assets.getFloors(), step.getFromPlane())));
		}
		zoomForJump(Math.max(viewer.getZoom().targetPixelsPerTile(), FOUND_MIN_ZOOM));
		found = new SearchEntry(SearchKind.TILE, "Step " + (row + 1), "", target.getId(), step.getFromX(), step.getFromY());
		foundSince = lastFrame;
	}

	/** The route in words, under the coordinates: each walk and each jump, and which option it uses. */
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
			width = Math.max(width, metrics.stringWidth((i + 1) + "  " + steps.get(i).getText()));
		}
		width = Math.min(width + 30, bounds.width / 2 - 20);
		int lineHeight = metrics.getHeight() + 2;
		int height = lineHeight * (rows + 1) + 12 + (steps.size() > rows ? lineHeight : 0);
		int x = bounds.x + 10;
		int y = bounds.y + 40;
		Theme.panel(g, x, y, width, height);
		routePanelRect = new Rectangle(x, y, width, height);
		g.setColor(Theme.ACCENT);
		g.setFont(Theme.TITLE_FONT);
		g.drawString("Route", x + 8, y + 6 + g.getFontMetrics().getAscent());
		int titleWidth = g.getFontMetrics().stringWidth("Route");
		g.setFont(Theme.SMALL_FONT);
		if (width > titleWidth + 150)
		{
			g.setColor(Theme.TEXT_DIM);
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
			double warmth = stepFade.level(i, pointer != null && rowRect.contains(pointer), animNow);
			if (warmth > 0)
			{
				g.setColor(Fade.withAlpha(Theme.HOVER, warmth));
				g.fillRect(rowRect.x, rowRect.y, rowRect.width, rowRect.height);
			}
			g.setColor(step.isWalk() ? WALK : JUMP);
			g.fillRect(x + 8, rowY + 4, 4, metrics.getHeight() - 6);
			g.setColor(Theme.TEXT);
			String text = (i + 1) + "  " + step.getText();
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
		return external.hops().size();
	}

	/** A flag where the requested route ends, which goes once the player is there. */
	private void drawRouteTarget(Graphics2D g, ViewTransform view, MapInfo map, @Nullable PlayerPosition position)
	{
		List<WorldPoint> targets = actions.getRouteTargets();
		WorldPoint start = actions.getRouteStart();
		if (map == null || targets.isEmpty())
		{
			return;
		}
		g.setFont(Palette.BODY);
		if (start != null && map.contains(start.getX(), start.getY()))
		{
			double sx = view.screenX(start.getX() + 0.5);
			double sy = view.screenY(start.getY() + 0.5);
			g.setColor(ROUTE_START);
			g.setStroke(new BasicStroke(2f));
			g.draw(new Ellipse2D.Double(sx - 9, sy - 9, 18, 18));
			g.fill(new Ellipse2D.Double(sx - 3, sy - 3, 6, 6));
			drawLabel(g, g.getFontMetrics(), "Route start", (int) sx + 13, (int) sy + 4);
		}
		int number = 0;
		for (WorldPoint target : targets)
		{
			number++;
			if (position != null && position.getTile().getPlane() == target.getPlane()
				&& position.getTile().distanceTo2D(target) <= MapActions.ARRIVED_TILES)
			{
				continue;
			}
			if (!map.contains(target.getX(), target.getY()))
			{
				continue;
			}
			double sx = view.screenX(target.getX() + 0.5);
			double sy = view.screenY(target.getY() + 0.5);
			g.setColor(ROUTE_TARGET);
			g.setStroke(new BasicStroke(2f));
			g.draw(new Ellipse2D.Double(sx - 9, sy - 9, 18, 18));
			g.fill(new Ellipse2D.Double(sx - 3, sy - 3, 6, 6));
			drawLabel(g, g.getFontMetrics(), targets.size() > 1 ? "Route target " + number : "Route target",
				(int) sx + 13, (int) sy + 4);
		}
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
		if (text.startsWith("Saved ") || text.startsWith("Lined up") || text.startsWith("Zoom is yours")
			|| text.startsWith("You are somewhere") || text.startsWith("Your position"))
		{
			// A note about saving a place is shown as it is.
		}
		else if (hops > 0 && actions.getRouteTarget() != null)
		{
			text = "Shortest Path route, " + hops + (hops == 1 ? " jump" : " jumps") + " shown on the map";
		}
		else if (actions.getRouteTarget() != null)
		{
			text = "Route sent to Shortest Path. Its jumps show here once found.";
		}
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		// Inside the map and clear of the buttons: a line too long for the room is wrapped onto more lines, going up.
		int left = bounds.x + 8;
		int right = Math.min(bounds.x + bounds.width - 8, Math.min(buttons.block().x - 8, bounds.x + bounds.width));
		int room = Math.max(60, right - left - 8);
		List<String> lines = PopupText.wrap(text, metrics, room);
		int baseline = bounds.y + bounds.height - 34;
		int top = bounds.y + PopupText.EDGE + metrics.getAscent() + 1;
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

	private void drawRuler(Graphics2D g, ViewTransform view, MapInfo map, @Nullable Point mouse)
	{
		if (!ruler.isActive() || map == null || ruler.getMapId() != map.getId())
		{
			return;
		}
		double sx = view.screenX(ruler.getStartX());
		double sy = view.screenY(ruler.getStartY());
		g.setColor(RULER);
		g.setStroke(new BasicStroke(2f));
		g.fill(new Ellipse2D.Double(sx - 4, sy - 4, 8, 8));
		if (mouse == null || menu.isOpen())
		{
			return;
		}
		g.draw(new Line2D.Double(sx, sy, mouse.x, mouse.y));
		String text = ruler.describe(view.worldX(mouse.x), view.worldY(mouse.y));
		if (text != null)
		{
			g.setFont(Palette.BODY);
			drawLabel(g, g.getFontMetrics(), text, mouse.x + 14, mouse.y - 8);
		}
	}

	/** A name on a dark plate, so it reads over any terrain. */
	private static void drawLabel(Graphics2D g, FontMetrics metrics, String text, int x, int y)
	{
		int width = metrics.stringWidth(text) + 8;
		g.setColor(Theme.PLATE);
		g.fillRoundRect(x - 4, y - metrics.getAscent() - 1, width, metrics.getHeight() + 2, 5, 5);
		g.setColor(Theme.TEXT);
		g.drawString(text, x, y);
	}

	/** What a saved place is called on its card. */
	static String kindText(Pin pin)
	{
		return "pin".equals(pin.getKind()) ? "Your pin" : "Saved " + pin.getKind();
	}

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
			if (what instanceof Partners.Thing)
			{
				return ((Partners.Thing) what).name;
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
		boolean shopThing = false;
		for (Partners.Thing thing : external.partners().hits(view, map.getId(), iconFloor(map), x, y, onHitMap(map)))
		{
			shopThing |= SHOP.equals(thing.data().get("kind"));
			WorldPoint at = thing.point;
			double sx = at == null ? x : view.screenX(at.getX() + 0.5);
			double sy = at == null ? y : view.screenY(at.getY() + 0.5);
			found.add(new Candidate(thing, Math.hypot(sx - x, sy - y), sx, sy, thing.clickable));
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
			// A shop the Helper found under the pointer already has its whole card: the icon's plain one would only sit beside it.
			if (card != null && !(shopThing && MapElements.kindFor(card.getCard().getTitle()) == Hotspot.Kind.SHOP))
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
		else if (first.what instanceof Partners.Thing)
		{
			card = ((Partners.Thing) first.what).card();
		}
		else
		{
			card = liveCard((Hotspot) first.what);
			if (first.iconFloor >= 0)
			{
				card = iconCard(card, first);
			}
			card = withMissing(card, first.iconFloor >= 0 ? first.need : ((Hotspot) first.what).getNeed());
			// Where the Helper finds no page that is certain to exist, its button opens the wiki's search page for the title instead.
			card = card.withWikiSearch(card.getTitle());
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
		if (external.partners().any(Partners.Kind.CARD))
		{
			for (String line : external.partners().lines(thingOf(first)))
			{
				card = card.withRow(line, "");
			}
		}
		if (!config.showWikiButtons())
		{
			return card.withoutWiki();
		}
		Partners.Button button = external.partners().button(Partners.subject(card.getTitle(), card.getWiki(), card.getWikiSearch()));
		return button == null ? card : card.withButton(button.label, button.menu, button.key);
	}

	/** What other plugins are told of a thing under the pointer: its kind, name, tile and id. */
	private Map<String, Object> thingOf(Candidate c)
	{
		ViewTransform view = currentView();
		if (c.what instanceof Partners.Thing)
		{
			return ((Partners.Thing) c.what).data();
		}
		String kind = c.what instanceof Pin ? "pin" : c.what instanceof Hotspot && ((Hotspot) c.what).getCategory() != null
			? ((Hotspot) c.what).getCategory() : "place";
		return Partners.describe(kind, c.title(), new WorldPoint((int) Math.floor(view.worldX(c.x)), (int) Math.floor(view.worldY(c.y)),
			iconFloor(viewer.getMap())), c.what instanceof Pin ? String.valueOf(((Pin) c.what).getId()) : "");
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	/**
	 * What the card for the thing under the mouse was built from, so a mouse that rests gets the same card and no new one is built.
	 * The card is built again when the thing, what else is under the mouse, the floor or the extras of an icon, what the player is
	 * missing, the layers that are on, or a setting a card reads changes. Cards whose body a partner plugin gives (farming) are kept for a second only, because they change with time.
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
		long builtAt;
		HoverCard card;
	}

	/** How long a card that a partner plugin gives the body of is kept for a mouse that rests. */
	private static final long BODY_KEPT_NANOS = 1_000_000_000L;

	private final CardMemo cardMemo = new CardMemo();

	/** The card for the first of the things under the mouse, kept while nothing it is made from changes. */
	private HoverCard hoverCard(Candidate first, List<Candidate> under)
	{
		UnlockView progress = assets.getUnlocks();
		int version = progress == null ? -1 : progress.version();
		int flags = (config.markUnusable() ? 1 : 0) | (config.onlyUsable() ? 2 : 0) | (config.showWikiButtons() ? 4 : 0) | external.partners().version() << 3;
		String name = null;
		String kind = null;
		if (first.what instanceof Pin)
		{
			name = ((Pin) first.what).getName();
			kind = ((Pin) first.what).getKind();
		}
		CardMemo m = cardMemo;
		long now = System.nanoTime();
		boolean same = m.card != null && (!usesFarming(first) || now - m.builtAt < BODY_KEPT_NANOS) && under.size() <= CardMemo.MAX_UNDER && m.underCount == under.size()
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
		m.builtAt = now;
		return card;
	}

	/** True when the first thing's card may be a farming card, which a partner plugin gives and which changes with time. */
	private boolean usesFarming(Candidate first)
	{
		if (!external.partners().owns(FARMING))
		{
			return false;
		}
		if (first.what instanceof Hotspot)
		{
			return "farming".equals(((Hotspot) first.what).getCategory()) || (first.iconFloor >= 0 && !first.extras.isEmpty());
		}
		return false;
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
		g.draw(new Ellipse2D.Double(hx - pulse, hy - pulse, pulse * 2, pulse * 2));

		g.setFont(Palette.BODY);
		for (Hotspot.Link link : hovered.getLinks())
		{
			if (link.getMapId() != map.getId())
			{
				continue;
			}
			double lx = view.screenX(link.getX());
			double ly = view.screenY(link.getY());
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
				new float[] {6f, 5f}, 11f - (float) ((animNow % 600) / 600.0 * 11f)));
			g.setColor(LINK_LINE);
			g.draw(new Line2D.Double(hx, hy, lx, ly));
			g.setStroke(new BasicStroke(1f));
			g.fill(new Ellipse2D.Double(lx - 3, ly - 3, 6, 6));
			if (!link.getLabel().isEmpty())
			{
				g.setColor(Theme.TEXT);
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
		g.setComposite(AlphaComposite.SrcOver.derive(!Motion.fades() ? 1f
			: (float) Fade.ease((animNow - cardSince) / (double) Motion.FADE_MILLIS)));
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

	/** The key of the partner button the open menu offers, and what that button is told, or null. */
	private String menuButton;
	private Map<String, Object> menuSubject;

	/** Presses a partner's card button and shows the notice it answers with on the map. */
	private void pressButton(@Nullable String key, Map<String, Object> subject)
	{
		String notice = key == null ? null : external.partners().press(key, subject);
		if (notice != null)
		{
			routeNotice = notice;
			routeNoticeSince = lastFrame;
		}
	}

	/** The card of the place the mouse is over when a partner put a button on it, or null. */
	@Nullable
	private HoverCard buttonCardAt(ViewTransform view, @Nullable MapInfo map, int x, int y)
	{
		if (map == null)
		{
			return null;
		}
		List<Candidate> under = candidates(view, map, x, y);
		if (under.isEmpty() || !(under.get(0).what instanceof Hotspot) && !(under.get(0).what instanceof Partners.Thing))
		{
			return null;
		}
		HoverCard card = cardOf(under.get(0), under);
		return card.getButtonLabel() == null ? null : card;
	}

	/**
	 * The name to look up on the wiki for a search result with no page that is certain to exist: its name. Nothing for the player's own
	 * pins and typed tiles, whose names are the player's.
	 */
	@Nullable
	private String wikiSearchFor(SearchEntry entry)
	{
		if (entry.getKind().isOwn() || entry.getKind() == SearchKind.TILE)
		{
			return null;
		}
		return entry.getName();
	}

	/** What a search result is, for the Helper to name a wiki page from: its kind (an area name apart from other places), name and detail. */
	private static String[] wikiOf(SearchEntry entry)
	{
		String detail = entry.getDetail().replace(" (" + entry.getAlsoAt().size() + " places)", "").trim();
		return new String[] {entry.getKind() == SearchKind.PLACE && entry.isAreaName() ? "AREA" : entry.getKind().name(), entry.getName(), detail};
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

	/** The title of the card being shown and when it first appeared, so a new card fades in. */
	private String cardShown;
	private long cardSince;

	/** A pulsing ring on the place just searched for, with its name, and dots for other places it is found. */
	private void drawFound(Graphics2D g, ViewTransform view, MapInfo map, long now)
	{
		if (found == null || map == null)
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
			g.fill(new Ellipse2D.Double(sx - 3, sy - 3, 6, 6));
		}

		double sx = view.screenX(found.getX());
		double sy = view.screenY(found.getY());
		double phase = ((now - foundSince) % 1200) / 1200.0;
		if (Motion.full())
		{
			// The pulse. With animations reduced or off the steady ring is all there is.
			double radius = 10 + 18 * phase;
			g.setStroke(new BasicStroke(3f));
			g.setColor(new Color(FOUND.getRed(), FOUND.getGreen(), FOUND.getBlue(), (int) (255 * (1 - phase))));
			g.draw(new Ellipse2D.Double(sx - radius, sy - radius, radius * 2, radius * 2));
		}
		g.setStroke(new BasicStroke(2f));
		g.setColor(FOUND);
		g.draw(new Ellipse2D.Double(sx - 8, sy - 8, 16, 16));

		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		String label = foundOf > 1 ? found.getName() + " (" + foundNumber + " of " + foundOf + ")" : found.getName();
		int width = metrics.stringWidth(label) + 12;
		int lx = (int) sx - width / 2;
		int ly = (int) sy - 34;
		g.setColor(Theme.PLATE);
		g.fillRoundRect(lx, ly, width, metrics.getHeight() + 4, 6, 6);
		g.setColor(Theme.TEXT);
		g.drawString(label, lx + 6, ly + 2 + metrics.getAscent());
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
			if (Motion.full())
			{
				g.setStroke(new BasicStroke(3f));
				g.setColor(new Color(LINK_LINE.getRed(), LINK_LINE.getGreen(), LINK_LINE.getBlue(), (int) (255 * (1 - phase))));
				g.draw(new Ellipse2D.Double(sx - radius, sy - radius, radius * 2, radius * 2));
			}
			g.setStroke(new BasicStroke(2f));
			g.setColor(LINK_LINE);
			g.draw(new Ellipse2D.Double(sx - 10, sy - 10, 20, 20));
			if (!labelled)
			{
				labelled = true;
				String text = found.getName() + " is through here";
				drawLabel(g, metrics, text, (int) sx - metrics.stringWidth(text) / 2, (int) sy - 18);
			}
		}
	}

	/**
	 * The card of one of the game's icons: its own card (a farming patch's live card while that layer is on), the floor it is on and
	 * what the points of interest merged into it add while their layers are on.
	 */
	private HoverCard iconCard(HoverCard own, Candidate icon)
	{
		Set<String> on = enabledPoi();
		List<IconExtra> extras = icon.extras;
		HoverCard base = own;
		if (on.contains(FARMING) && !extras.isEmpty() && external.partners().owns(FARMING))
		{
			List<IconExtra> rest = new ArrayList<>(extras.size());
			for (IconExtra extra : extras)
			{
				HoverCard live = base == own && FARMING.equals(extra.getLayer())
					? external.partners().body(Partners.describe(FARMING, extra.getName(),
						new WorldPoint(extra.getTileX(), extra.getTileY(), 0), "")) : null;
				if (live != null)
				{
					base = live;
				}
				else
				{
					rest.add(extra);
				}
			}
			extras = rest;
		}
		return IconCards.compose(base, icon.iconFloor, extras, on);
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
		UnlockView progress = assets.getUnlocks();
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
		String words = progress.shortfall(need);
		missingWords.put(entry, words);
		return words;
	}

	/**
	 * The card with a row saying what the player is certainly missing for it, when the settings that mark or hide what cannot be used
	 * yet are on and something is. Anything that cannot be told is left unsaid.
	 */
	private HoverCard withMissing(HoverCard card, @Nullable Need need)
	{
		UnlockView progress = assets.getUnlocks();
		if (progress == null || need == null || !(config.markUnusable() || config.onlyUsable()))
		{
			return card;
		}
		String lack = progress.shortfall(need);
		return lack == null ? card : card.withRow("Missing", lack);
	}

	/**
	 * The card to show for a hotspot. Farming points show what was last seen in their patches, which
	 * changes, so it is built when needed. Everything else has its card ready.
	 */
	private HoverCard liveCard(Hotspot hotspot)
	{
		// The Helper's card of a shop replaces the plain card of the game's shop icon (asked when the card is built, then kept).
		if (hotspot.getKind() == Hotspot.Kind.POI && MapElements.kindFor(hotspot.getCard().getTitle()) == Hotspot.Kind.SHOP
			&& external.partners().owns(SHOP))
		{
			Map<String, Object> thing = new HashMap<>(Partners.describe(SHOP, hotspot.getCard().getTitle(),
				new WorldPoint((int) Math.floor(hotspot.getX()), (int) Math.floor(hotspot.getY()), 0), ""));
			thing.put("mapId", hotspot.getMapId());
			HoverCard shop = external.partners().body(thing);
			if (shop != null)
			{
				return shop;
			}
		}
		if (FARMING.equals(hotspot.getCategory()) && hotspot.getKind() == Hotspot.Kind.POI && external.partners().owns(FARMING))
		{
			HoverCard live = external.partners().body(Partners.describe(FARMING, hotspot.getCard().getTitle(),
				new WorldPoint((int) Math.floor(hotspot.getX()), (int) Math.floor(hotspot.getY()), 0), ""));
			if (live != null)
			{
				return live;
			}
		}
		return hotspot.getCard();
	}

	/** The kind of thing (and the layer) of the farming points, whose cards the Helper plugin gives. */
	private static final String FARMING = "farming";

	/** The kind of thing whose card the Helper plugin gives in place of the plain card of a shop icon. */
	private static final String SHOP = "shop";

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

	/** The icon layer of a window of its own, made on first use. The main map uses the one in the assets. */
	private GameIconLayer ownIcons;

	/** The label layer of a window of its own, made on first use. */
	private LabelLayer ownLabels;

	/**
	 * The layer that draws and hit tests this surface's game icons. A layer keeps what it laid out in its own fields, and a window
	 * paints on the Swing thread while the main map paints on the client thread, so a window gets a layer of its own (the pictures
	 * are shared) and never touches the one the main map uses.
	 */
	GameIconLayer icons()
	{
		GameIconLayer shared = assets.getGameIconLayer();
		if (!windowed)
		{
			return shared;
		}
		GameIconLayer own = ownIcons;
		if (own == null)
		{
			own = new GameIconLayer(shared.getSet(), shared.getSprites());
			ownIcons = own;
		}
		return own;
	}

	/** The layer that draws the game's text labels for this surface. Windows have their own, for the reason given at {@link #icons()}. */
	LabelLayer labelLayer()
	{
		if (!windowed)
		{
			return assets.getLabels();
		}
		LabelLayer own = ownLabels;
		if (own == null)
		{
			own = new LabelLayer(assets.getElements());
			ownLabels = own;
		}
		return own;
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
		icons().setUnlocks(assets.getUnlocks(), config.hideFinishedQuests(), config.markUnusable(), config.onlyUsable(),
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
			|| Partners.onPanel(panelBox, x, y) || overGame(x, y) || overHeldCard(x, y);
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
		Ellipse2D ring = new Ellipse2D.Double(sx - radius, sy - radius, radius * 2, radius * 2);
		g.setColor(PLAYER_FILL);
		g.fill(ring);
		g.setColor(PLAYER_RING);
		g.setStroke(new BasicStroke(2f));
		g.draw(ring);
	}

	private void drawCoordinates(Graphics2D g, Rectangle bounds, ViewTransform view, MapInfo map, @Nullable Point mouse)
	{
		StringBuilder text = new StringBuilder(map == null ? "No map" : map.getName());
		if (mouse != null && bounds.contains(mouse))
		{
			text.append("   ")
				.append((int) Math.floor(view.worldX(mouse.x)))
				.append(", ")
				.append((int) Math.floor(view.worldY(mouse.y)));
		}
		g.setFont(Palette.BODY);
		FontMetrics metrics = g.getFontMetrics();
		// Never wider than the map, however long the map's name is.
		String shown = PopupText.clip(text.toString(), metrics, Math.max(20, bounds.width - 20 - 14));
		int width = metrics.stringWidth(shown) + 14;
		int height = metrics.getHeight() + 6;
		int x = bounds.x + 10;
		int y = bounds.y + 10;
		g.setColor(Theme.PLATE);
		g.fillRoundRect(x, y, width, height, 6, 6);
		g.setColor(Theme.TEXT);
		g.drawString(shown, x + 7, y + 3 + metrics.getAscent());
	}

	@Nullable
	private Point mouse(Rectangle bounds)
	{
		if (pointerSource != null)
		{
			Point own = pointerSource.get();
			return own != null && bounds.contains(own) ? own : null;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0)
		{
			return null;
		}
		return bounds.contains(mouse.getX(), mouse.getY()) ? new Point(mouse.getX(), mouse.getY()) : null;
	}
}
