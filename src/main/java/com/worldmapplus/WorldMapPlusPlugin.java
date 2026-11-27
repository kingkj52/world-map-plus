package com.worldmapplus;

import com.google.inject.*;
import com.worldmapplus.bridge.*;
import com.worldmapplus.drops.*;
import com.worldmapplus.game.*;
import com.worldmapplus.input.*;
import com.worldmapplus.map.*;
import com.worldmapplus.panel.*;
import com.worldmapplus.pins.*;
import com.worldmapplus.prefs.*;
import com.worldmapplus.share.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.typing.*;
import com.worldmapplus.ui.*;
import com.worldmapplus.unlock.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
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
 * a much larger zoom range, search and hover information.
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
	private ItemManager itemManager;

	/** Item prices for the drop tabs, read from RuneLite's item manager on the client thread while the plugin runs. */
	private ItemPrices prices;

	@Inject
	private PinStore pins;

	@Inject
	private RouteStore routes;

	@Inject
	private RouteRunner routeRunner;

	@Inject
	private PanelLauncher panelLauncher;

	@Inject
	private ShareChat shareChat;

	@Inject
	private ShareInbox shareInbox;



	@Inject
	private ChatSearch chatSearch;

	@Inject
	private Unlocks unlocks;

	@Inject
	private GameTextEntry textEntry;

	/** The typing guide for share codes: the guide itself, the reader of the chat input line, and the overlay that draws it. */
	@Inject
	private TypingGuide typingGuide;

	@Inject
	private ChatLineReader chatLine;

	@Inject
	private TypingGuideOverlay typingOverlay;

	private PlacesPanel panel;
	private NavigationButton navButton;

	private final HotkeyListener hudToggle = hudKey(() -> config.hudToggleKey(), () -> hudState.toggleVisible());
	private final HotkeyListener hudClickThrough = hudKey(() -> config.hudClickThroughKey(), () -> hudState.toggleClickThrough());

	/** A hotkey of the floating map. A key that would type, or any key while text is being typed, belongs to the game. */
	private HotkeyListener hudKey(Supplier<Keybind> key, Runnable action)
	{
		return new HotkeyListener(key)
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
				action.run();
			}
		};
	}

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
		if (key == null || "hideFinishedQuests".equals(key) || "unusableIcons".equals(key) || "colourQuestsByProgress".equals(key))
		{
			// Switching a setting on is what makes the plugin look at the player's progress, so it is asked then and not before.
			clientThread.invokeLater(this::wantProgress);
		}
		if ("theme".equals(key))
		{
			// The colours of the map change at once; the panel is recoloured on the interface thread.
			Palette before = Palette.current();
			Palette.use(config.theme());
			Palette after = Palette.current();
			PlacesPanel shown = panel;
			if (shown != null)
			{
				SwingUtilities.invokeLater(() -> shown.restyle(before, after));
			}
		}
		if ("typingGuide".equals(key) && !config.typingGuide())
		{
			// Switching the guide off removes it at once.
			typingGuide.stop();
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

	/**
	 * Tells the progress snapshot what the settings need. Quests are wanted by all three settings, levels only by the two that judge
	 * what can be used. With all three off nothing is read. Runs on the client thread.
	 */
	private void wantProgress()
	{
		boolean judge = config.unusableIcons() != WorldMapPlusConfig.Unusable.AS_USUAL;
		unlocks.want(judge, judge || config.hideFinishedQuests() || config.colourQuestsByProgress());
	}

	/** A level up updates the snapshot's level of that skill. Nothing happens unless a setting needs the snapshot. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		unlocks.onStatChanged(event.getSkill(), event.getLevel());
	}

	/** Any change of a game variable may be a quest moving on, so the snapshot looks at the quests again soon, at its own pace. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		unlocks.variablesChanged();
	}

	/**
	 * Share codes arrive through RuneLite's chat command manager (see {@code ShareChat.start}); this only offers "Open in
	 * World Map Plus" on a chat line that holds one, the way Chat History offers its copy entry.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		shareChat.onMenuEntryAdded(event);
	}

	/** Shows a share someone sent: listed and marked in the side panel, marked on the map, the map centred on it. */
	private void openShare(Share share)
	{
		shareInbox.open(share);
		showShare(share);
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null && navButton != null)
			{
				clientToolbar.openPanel(navButton);
				panel.showOpenedShare();
			}
		});
	}

	/** Asks Shortest Path for the way to a share's first point, as the side panel's Go does for a saved place. Nothing is saved. */
	private void goToShare(Share share)
	{
		SharePoint first = share.getPoints().get(0);
		clientThread.invokeLater(() ->
		{
			routes.stopAll();
			actions.routeTo(new WorldPoint(first.getX(), first.getY(), first.getPlane()));
		});
	}

	/** Marks a share on the map, nothing saved, and asks the map to centre on its first point. */
	private void showShare(Share share)
	{
		List<WorldPoint> points = new ArrayList<>();
		for (SharePoint p : share.getPoints())
		{
			points.add(new WorldPoint(p.getX(), p.getY(), p.getPlane()));
		}
		String title = "Shared " + share.describe();
		external.showShare(points, share.getKind() == Share.Kind.ROUTE, title);
		external.focusOn(points.get(0), title);
	}

	/** Once a client tick: finds out whether the game is taking typed text, so no key of this plugin acts while the player types. */
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		textEntry.onClientTick();
		// Reads the chat input line only while a typing guide runs (and the setting is on), once a client tick.
		if (config.typingGuide())
		{
			chatLine.onClientTick();
		}
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

	/** Once a game tick: the player's progress, the idle release of the tiles and the saved route being followed. */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		unlocks.onGameTick();
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

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		unlocks.onGameStateChanged(event.getGameState());
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
		assets.setUnlocks(unlocks);
		// Prices are only read when a drop tab asks, a bounded batch on the client thread, from what RuneLite already holds.
		prices = new ItemPrices(itemManager::getItemPrice, clientThread::invokeLater);
		assets.setPrices(prices);
		clientThread.invokeLater(this::wantProgress);
		overlayManager.add(viewerOverlay);
		overlayManager.add(hudOverlay);
		overlayManager.add(typingOverlay);
		chatLine.setOtherInput(chatSearch::isOpen);

		mouseManager.registerMouseListener(viewerOverlay.getInput());
		mouseManager.registerMouseWheelListener(viewerOverlay.getInput());
		mouseManager.registerMouseListener(hudOverlay.getInput());
		mouseManager.registerMouseWheelListener(hudOverlay.getInput());
		mouseManager.registerMouseListener(typingOverlay.getInput());
		registerKeys();

		Palette.use(config.theme());
		ShareImport shareImport = new ShareImport(pins, routes, assets::getCatalog);
		panel = new PlacesPanel(pins, routes, routeRunner, actions, external, clientThread, this::areaName, shareInbox, shareImport);
		panel.setShareHandlers(this::showShare, this::goToShare);
		panel.setTypingGuide(typingGuide, config::typingGuide);
		shareChat.start(this::openShare, shareImport::knownName);
		navButton = NavigationButton.builder()
			.tooltip("World Map Plus places and routes")
			.icon(panelIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		viewerOverlay.setWindowOpener(hudOverlay::open);
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
		actions.setActive(false);
		actions.setLater(null);
		assets.setUnlocks(null);
		assets.setPrices(null);
		if (prices != null)
		{
			prices.clear();
			prices = null;
		}
		clientThread.invokeLater(() -> unlocks.want(false, false));
		panelLauncher.setOpener(null);
		panelLauncher.setRouteEditor(null);
		shareChat.stop();
		shareInbox.clear();
		viewerOverlay.setWindowOpener(null);

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
		mouseManager.unregisterMouseListener(typingOverlay.getInput());

		mouseManager.unregisterMouseWheelListener(viewerOverlay.getInput());
		mouseManager.unregisterMouseListener(viewerOverlay.getInput());

		overlayManager.remove(typingOverlay);
		overlayManager.remove(hudOverlay);
		overlayManager.remove(viewerOverlay);

		// The search prompt in the chat area, if it is open, must not outlive the plugin.
		chatSearch.closeAll();
		typingGuide.stop();
		chatLine.setOtherInput(null);
		hudState.hide();
		external.clear();
		hudOverlay.reset();
		viewerOverlay.reset();
		assets.release();
		log.debug("World Map Plus stopped");
	}
}
