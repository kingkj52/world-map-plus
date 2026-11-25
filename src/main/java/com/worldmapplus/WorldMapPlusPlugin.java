package com.worldmapplus;

import com.google.inject.*;
import com.worldmapplus.bridge.*;
import com.worldmapplus.game.*;
import com.worldmapplus.input.*;
import com.worldmapplus.map.*;
import com.worldmapplus.panel.*;
import com.worldmapplus.pins.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.ui.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.client.callback.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.input.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.*;

/**
 * A replacement world map viewer.
 * <p>
 * <b>What this plugin does:</b> it draws its own map, from map pictures downloaded from the plugin's own GitHub release and kept on disk, over
 * the world map interface and, optionally, as a see-through map over the game scene. It adds
 * a much larger zoom range, search, hover information and nearby NPC markers.
 * <p>
 * <b>What it deliberately does not do:</b> its only network use is downloading map pictures (see MapDownloader), it adds no menu entries
 * and never creates, hides, moves or resizes any game widget. It never draws other players and
 * offers no combat information.
 */
@Slf4j
@PluginDescriptor(
	name = "World Map Plus",
	internalName = "world-map-plus",
	description = "A replacement world map with a huge zoom range, search and hover cards",
	tags = {"map", "world map", "zoom", "search", "route", "pins", "dungeon", "shop", "transparent"}
)
public class WorldMapPlusPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Client client;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private WorldMapPlusConfig config;

	@Inject
	private MapAssets assets;

	@Inject
	private ViewerOverlay viewerOverlay;

	@Inject
	private HudOverlay hudOverlay;

	@Inject
	private HudState hudState;

	@Inject
	private ExternalOverlays external;

	@Inject
	private MapActions actions;

	@Inject
	private PlayerLocator locator;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;
	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private PinStore pins;

	@Inject
	private RouteStore routes;

	@Inject
	private RouteRunner routeRunner;

	@Inject
	private PanelLauncher panelLauncher;

	@Inject
	private MapWindows windows;

	@Inject
	private ChatSearch chatSearch;

	@Inject
	private GameTextEntry textEntry;

	private PlacesPanel panel;
	private NavigationButton navButton;

	private final HotkeyListener hudToggle = new HotkeyListener(() -> config.hudToggleKey())
	{
		@Override
		public void keyPressed(KeyEvent event)
		{
			// A key that would type, or any key while text is being typed, belongs to the game.
			if (!textEntry.blocks(event))
			{
				super.keyPressed(event);
			}
		}

		@Override
		public void hotkeyPressed()
		{
			if (config.hudEnabled())
			{
				hudState.toggleVisible();
			}
		}
	};

	private final HotkeyListener hudClickThrough = new HotkeyListener(() -> config.hudClickThroughKey())
	{
		@Override
		public void keyPressed(KeyEvent event)
		{
			if (!textEntry.blocks(event))
			{
				super.keyPressed(event);
			}
		}

		@Override
		public void hotkeyPressed()
		{
			if (config.hudEnabled())
			{
				hudState.toggleClickThrough();
			}
		}
	};

	@Provides
	WorldMapPlusConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WorldMapPlusConfig.class);
	}

	/**
	 * Other plugins can ask the map to show markers, lines and places. See {@link ExternalOverlays}
	 * for the contract. Everything in a message is checked before it is used.
	 */
	@Inject
	private EventBus eventBus;

	/** Made once, so the client tick hands the same object over and makes nothing. */
	private final ExternalOverlays.Coverage coverage = this::coverOf;

	/** How the maps cover a tile, for the answers to other plugins' questions. */
	private ExternalOverlays.Cover coverOf(int x, int y)
	{
		MapCatalog catalog = assets.getCatalog();
		boolean exact = catalog.mapFor(x, y) != null;
		MapInfo map = catalog.mapNear(x, y, 192);
		return map == null ? new ExternalOverlays.Cover(false, false, "", "")
			: new ExternalOverlays.Cover(true, exact, map.getName(), ExternalOverlays.kindOf(map.getId()));
	}

	@Subscribe
	public void onPluginMessage(PluginMessage message)
	{
		external.onPluginMessage(message);
		// Other plugins' route requests are always counted, so a saved route being followed gives way to them.
		// Marking their target and drawing their jumps is the part the setting switches off.
		actions.onPluginMessage(message, config.showOtherPluginRoutes());
	}

	/**
	 * Saved places and routes are read once and kept. If something other than this plugin changes them, such
	 * as a switch of RuneLite profile or an import of settings, they are read again.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!WorldMapPlusConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		if ("theme".equals(key))
		{
			// The colours of the map change at once; the panel is recoloured on the interface thread.
			Palette before = Palettes.current();
			Look.use(config.theme());
			external.partners().themeChanged();
			Palette after = Palettes.current();
			PlacesPanel shown = panel;
			if (shown != null)
			{
				SwingUtilities.invokeLater(() -> shown.restyle(before, after));
			}
		}
		if ("hudStartsClickThrough".equals(key))
		{
			// The setting is the state of the floating map's mouse, so changing it takes effect at once.
			hudState.setClickThrough(config.hudStartsClickThrough());
		}
		if (key == null || "pins".equals(key))
		{
			pins.reloadIfChanged();
		}
		if (key == null || "routes".equals(key))
		{
			routes.reloadIfChanged();
		}
	}

	/** Once a client tick: finds out whether the game is taking typed text, so no key of this plugin acts while the player types. */
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		textEntry.onClientTick();
		postAnswers();
	}

	/**
	 * Posts the answers to other plugins' messages, on a later tick than the question, so no plugin is ever called back from inside its
	 * own post. A client tick runs at the login screen too, so a plugin that asks before the player logs in still hears back. Only ever
	 * in this plugin's own namespace: a message can never choose where its answer goes. Nothing is made when nothing waits.
	 */
	private void postAnswers()
	{
		if (assets.isLoaded())
		{
			external.setCoverage(coverage);
		}
		external.partners().tick(System.currentTimeMillis());
		if (external.idle())
		{
			return;
		}
		for (ExternalOverlays.Reply reply : external.drainReplies(System.currentTimeMillis()))
		{
			eventBus.post(new PluginMessage(ExternalOverlays.NAMESPACE, reply.getName(), reply.getData()));
		}
	}

	/** A window opening may be one that takes keys, so the next client tick looks at the open windows again. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		textEntry.windowsChanged();
	}

	/** Lets the map go of the mouse and keyboard, and saves its view, the moment the world map closes. */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		textEntry.windowsChanged();
		if (event.getGroupId() == InterfaceID.WORLDMAP)
		{
			viewerOverlay.mapClosed();
		}
	}

	/** Once a game tick: lets go of map tiles nobody uses and gives a followed route its next stop. */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		PlayerPosition player = locator.get();
		// The decoded map tiles are let go of once the map has been closed for a while. The tick is the clock; no timer is made.
		assets.releaseIfIdle();
		// A saved route being followed asks Shortest Path for its next stop once the player has reached the last.
		locator.remember(player);
		if (player == null)
		{
			routeRunner.tick(Double.NaN, Double.NaN, -1);
		}
		else
		{
			routeRunner.tick(player.getX(), player.getY(), player.getTile().getPlane());
			actions.checkArrived(player.getTile());
		}
	}

	/** The farming tracker forgets what it holds in memory when the account changes. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN && event.getGameState() != GameState.LOADING)
		{
			// Following a route is never carried across a log out.
			routes.stopAll();
			locator.remember(null);
			if (event.getGameState() == GameState.LOGIN_SCREEN)
			{
				actions.routeArrived();
			}
		}
	}

	/**
	 * Gives the plugin API's messages that act on this plugin what they need: whether a map holds a tile, the panel's status line and how
	 * to open it, how to ask Shortest Path for a route, and whether the game's chat area is taken by this plugin's search. Features that
	 * live in the Helper plugin (share codes, the typing guide) are what these are for, so the panel says once if it is missing.
	 */
	private void connectPartners()
	{
		PlacesPanel shown = panel;
		external.partners().connect(
			(map, x, y) ->
			{
				MapInfo info = assets.isLoaded() ? assets.getCatalog().byId(map) : null;
				return info != null && info.contains(x, y);
			},
			text -> shown.showStatus(text),
			() ->
			{
				if (navButton != null)
				{
					clientToolbar.openPanel(navButton);
				}
			},
			at ->
			{
				MapActions.Status status = actions.shortestPathStatus();
				if (status == MapActions.Status.MISSING)
				{
					return "missing";
				}
				if (status == MapActions.Status.DISABLED)
				{
					return "disabled";
				}
				routes.stopAll();
				actions.routeTo(at);
				return "ok";
			},
			chatSearch::isOpen);
		external.partners().mapNames(id ->
		{
			MapInfo info = assets.isLoaded() ? assets.getCatalog().byId(id) : null;
			return info == null ? null : info.getName();
		});
		if (config.shareCodesInChat() || config.typingGuide())
		{
			external.partners().expectHelper();
		}
	}

	/** The map that holds a tile, or -1 for none or while the maps are still loading. */
	private int mapIdAt(int x, int y)
	{
		MapInfo at = assets.isLoaded() ? assets.getCatalog().mapFor(x, y) : null;
		return at == null ? -1 : at.getId();
	}

	private String areaName(int mapId)
	{
		MapInfo map = assets.getCatalog().byId(mapId);
		return map == null ? "Unknown area" : map.getName();
	}

	@Override
	protected void startUp()
	{
		try
		{
			start();
		}
		catch (RuntimeException e)
		{
			// RuneLite may not call shutDown after a start that failed part way, so undo what was done.
			stopEverything();
			throw e;
		}
	}

	private void start()
	{
		try
		{
			assets.setCacheFolder(getPluginDirectory());
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.warn("World Map Plus: no folder to keep map pictures in, they are kept in memory only", e);
		}
		actions.setActive(true);
		actions.setLater(clientThread::invokeLater);
		// Other plugins learn that the map is there, and send again what a restart of this plugin forgot.
		external.announceReady();
		external.partners().attach(SwingUtilities::invokeLater, clientThread::invokeLater, executor::execute, pins, routes, this::mapIdAt);
		assets.setUnlocks(external.partners().unlocks());
		overlayManager.add(viewerOverlay);
		overlayManager.add(hudOverlay);

		mouseManager.registerMouseListener(viewerOverlay.getInput());
		mouseManager.registerMouseWheelListener(viewerOverlay.getInput());
		mouseManager.registerMouseListener(hudOverlay.getInput());
		mouseManager.registerMouseWheelListener(hudOverlay.getInput());
		registerKeys();

		Look.use(config.theme());
		Sounds sounds = new Sounds(config::sounds, client::playSoundEffect,
			System::currentTimeMillis);
		viewerOverlay.setSounds(sounds);
		panel = new PlacesPanel(pins, routes, routeRunner, actions, external, clientThread, this::areaName);
		connectPartners();
		navButton = NavigationButton.builder()
			.tooltip("World Map Plus places and routes")
			.icon(panelIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		windows.setEnabled(true);
		viewerOverlay.setWindowOpener(windows::open);
		hudOverlay.setWindowOpener(windows::open);
		panelLauncher.setOpener(id -> SwingUtilities.invokeLater(() ->
		{
			if (panel != null && navButton != null)
			{
				clientToolbar.openPanel(navButton);
				panel.editPin(id);
			}
		}));
		panelLauncher.setRouteEditor(id -> SwingUtilities.invokeLater(() ->
		{
			if (panel != null && navButton != null)
			{
				clientToolbar.openPanel(navButton);
				panel.editRoute(id);
			}
		}));

		// The bundled data is read on the shared executor, not here and not on the render thread.
		assets.preload();
		log.debug("World Map Plus started");
	}

	/**
	 * Signs the key listeners up with RuneLite's key manager, which is the only way keys reach this plugin. The manager offers
	 * each key to its listeners in the order they registered and stops at the first that consumes it, so a letter another plugin
	 * has bound, or that Key Remapping has taken, can be gone before the search bar sees it. The two map inputs come first so
	 * that, while a search bar has the keyboard, its keys are not also taken for this plugin's own hotkeys.
	 */
	void registerKeys()
	{
		// Ctrl+F is left to the game while it takes text. The input only reads what the client thread worked out in the last tick.
		viewerOverlay.getInput().setTextGuard(textEntry::blocks);
		hudOverlay.getInput().setTextGuard(textEntry::blocks);
		keyManager.registerKeyListener(viewerOverlay.getInput());
		keyManager.registerKeyListener(hudOverlay.getInput());
		keyManager.registerKeyListener(hudToggle);
		keyManager.registerKeyListener(hudClickThrough);
	}

	/** Takes back everything {@link #registerKeys()} did. Safe to call more than once, or when only part of it was done. */
	void unregisterKeys()
	{
		keyManager.unregisterKeyListener(hudClickThrough);
		keyManager.unregisterKeyListener(hudToggle);
		keyManager.unregisterKeyListener(hudOverlay.getInput());
		keyManager.unregisterKeyListener(viewerOverlay.getInput());
		viewerOverlay.getInput().setTextGuard(null);
		hudOverlay.getInput().setTextGuard(null);
	}

	private static BufferedImage panelIcon()
	{
		return PlacesPanel.icon();
	}

	@Override
	protected void shutDown()
	{
		stopEverything();
	}

	/** Undoes everything startUp does. Safe to call when only part of it was done. */
	private void stopEverything()
	{
		// Partner plugins hear that the map is going, so they stop sending until it says ready again.
		eventBus.post(new PluginMessage(ExternalOverlays.NAMESPACE, "main_goodbye", new HashMap<>()));
		actions.setActive(false);
		actions.setLater(null);
		assets.setUnlocks(null);
		panelLauncher.setOpener(null);
		panelLauncher.setRouteEditor(null);
		viewerOverlay.setWindowOpener(null);
		viewerOverlay.setSounds(null);
		hudOverlay.setWindowOpener(null);
		windows.setEnabled(false);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			panel.shutDown();
			panel = null;
		}
		if (routes.active() != null)
		{
			routes.stopAll();
			clientThread.invokeLater(actions::clearRoute);
		}
		actions.routeArrived();
		unregisterKeys();
		mouseManager.unregisterMouseWheelListener(hudOverlay.getInput());
		mouseManager.unregisterMouseListener(hudOverlay.getInput());

		mouseManager.unregisterMouseWheelListener(viewerOverlay.getInput());
		mouseManager.unregisterMouseListener(viewerOverlay.getInput());

		overlayManager.remove(hudOverlay);
		overlayManager.remove(viewerOverlay);

		// The search prompt in the chat area, if it is open, must not outlive the plugin.
		chatSearch.closeAll();
		hudState.hide();
		external.clear();
		hudOverlay.reset();
		viewerOverlay.reset();
		assets.release();
		log.debug("World Map Plus stopped");
	}
}
