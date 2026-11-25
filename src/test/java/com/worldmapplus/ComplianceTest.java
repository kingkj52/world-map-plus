package com.worldmapplus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Enforces the promises the README makes about this plugin, so they cannot quietly regress.
 * <p>
 * The plugin's case for being publishable is that it is mechanically verifiable: its one network use
 * is a small downloader of map pictures, it adds no menu entries, never changes a game widget, uses no reflection, never
 * touches the camera, never reads other players and never acts for the player. Each of those is
 * a grep, so each is a test.
 */
public class ComplianceTest
{
	private static final Path SOURCE_ROOT = new File("src/main/java").toPath();

	private static List<Path> sources() throws IOException
	{
		try (Stream<Path> paths = Files.walk(SOURCE_ROOT))
		{
			return paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
	}

	/**
	 * Fails if any source file contains a banned token. Occurrences inside a {@code //} or
	 * {@code *} comment line are ignored so the rules can be discussed in documentation.
	 */
	private void assertAbsent(String reason, String... tokens) throws IOException
	{
		assertAbsentExcept(reason, (file, line) -> false, tokens);
	}

	/** {@link #assertAbsent}, leaving out the lines {@code allowed} accepts (given the file name and the line). */
	private void assertAbsentExcept(String reason, java.util.function.BiPredicate<String, String> allowed, String... tokens)
		throws IOException
	{
		List<String> hits = new ArrayList<>();

		for (Path path : sources())
		{
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				String trimmed = lines.get(i).trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
				{
					continue;
				}
				for (String token : tokens)
				{
					if (lines.get(i).contains(token) && !allowed.test(path.getFileName().toString(), trimmed))
					{
						hits.add(path + ":" + (i + 1) + " contains '" + token + "'");
					}
				}
			}
		}

		if (!hits.isEmpty())
		{
			fail(reason + "\n" + String.join("\n", hits));
		}
	}

	@Test
	public void sourcesWereFound() throws IOException
	{
		assertTrue("no sources found under " + SOURCE_ROOT.toAbsolutePath(), sources().size() >= 4);
	}

	@Test
	public void theOnlyNetworkUseIsOneDownloaderClassOnTheInjectedClientToOneAddress() throws IOException
	{
		// Since round 59H the map pictures are downloaded (see docs/MAP-HOSTING.md). Network code is one small class, MapDownloader, which
		// is handed RuneLite's own OkHttpClient and fetches files by name from the one release address. No other class builds a request
		// or a client, and no other way of reaching the network is used anywhere.
		assertAbsent("No other way of reaching the network than RuneLite's OkHttpClient",
			"java.net", "HttpURLConnection", "java.net.http", "URLConnection", "Socket", "openConnection", "openStream", "WebSocket",
			"new OkHttpClient", "OkHttpClient.Builder", "newBuilder(", "Interceptor", "Dns", "Proxy");
		List<String> hits = new ArrayList<>();
		int addresses = 0;
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			boolean downloader = name.equals("MapDownloader.java");
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
			{
				String trimmed = line.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
				{
					continue;
				}
				if (!downloader && (line.contains("newCall(") || line.contains("enqueue(") || line.contains("Request.Builder")
					|| line.contains("okhttp3.Request") || line.contains("okhttp3.Call") || line.contains("okhttp3.Response")))
				{
					hits.add(path + " sends or reads a request outside MapDownloader: " + trimmed);
				}
				if (!downloader && line.contains("okhttp3.") && !line.contains("okhttp3.OkHttpClient"))
				{
					hits.add(path + " uses okhttp beyond naming the injected client: " + trimmed);
				}
				if (line.contains("http://") || line.contains("https://"))
				{
					addresses++;
					if (!downloader || !line.contains("https://github.com/KingKj52/world-map-plus-maps/releases/"))
					{
						hits.add(path + " names an address other than the map release: " + trimmed);
					}
				}
			}
		}
		assertEquals("exactly one address in the plugin", 1, addresses);
		if (!hits.isEmpty())
		{
			fail(String.join("\n", hits));
		}
		// The downloader takes its client from its caller, only fetches files of the release by name, and caps what it reads.
		String downloader = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/tiles/MapDownloader.java")), StandardCharsets.UTF_8);
		assertTrue(downloader.contains("MapDownloader(OkHttpClient client, String base"));
		assertTrue(downloader.contains("source.request(maxBytes + 1L)"));
		assertTrue(downloader.contains("trusted(r.request().url())"));
		assertFalse("only GET", downloader.contains(".post(") || downloader.contains(".put(") || downloader.contains(".delete(")
			|| downloader.contains("addHeader") || downloader.contains(".method("));
	}

	@Test
	public void usesNoReflection() throws IOException
	{
		assertAbsent("Plugin Hub forbids reflection",
			"java.lang.reflect", "java.lang.management", "setAccessible", "Class.forName", "TypeToken", "MethodHandle",
			"getDeclaredField", "getDeclaredMethod");
	}

	@Test
	public void addsNoMenuEntries() throws IOException
	{
		// Since round 56B the one client side entry ("Open in World Map Plus" on a chat line) belongs to the Helper plugin, which
		// is held to it by its own ComplianceTest. This plugin adds none.
		assertAbsent("Jagex prohibits new menu entries that send actions to the server",
			"createMenuEntry", "addMenuEntry", "setMenuEntries", "MenuEntryAdded", "MenuOpened",
			"MenuOptionClicked", "getMenuEntries", "swapEntries", "Overlay.addMenuEntry");
	}

	@Test
	public void neverChangesAGameWidget() throws IOException
	{
		assertAbsent("The plugin may read widget bounds and must never change a widget",
			".setHidden(", ".createChild(", ".setOpacity(", ".setOnClickListener", ".setOnOpListener",
			".setOriginalX(", ".setOriginalY(", ".setOriginalWidth(", ".setOriginalHeight(",
			".setSpriteId(", ".revalidate(", ".deleteAllChildren(", ".setPos(", ".setSize(",
			".setHasListener(", ".setAction(", ".setNoClickThrough(", ".setNoScrollThrough(");
	}

	@Test
	public void neverTouchesTheCameraOrRunsGameScripts() throws IOException
	{
		assertAbsent("No camera control and no game scripts",
			"setCameraPosition", "setCameraYawTarget", "setCameraPitchTarget", "setCameraFocus",
			"runScript(", "setOculusOrbState", "setFreeCam");
	}

	/** Source lines of the files under a folder of the plugin, without comment lines, as path and line. */
	private static List<String[]> linesUnder(String folder) throws IOException
	{
		List<String[]> found = new ArrayList<>();
		for (Path path : sources())
		{
			if (!path.toString().replace(File.separatorChar, '/').contains(folder))
			{
				continue;
			}
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				String trimmed = lines.get(i).trim();
				if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*"))
				{
					found.add(new String[] {path.getFileName() + ":" + (i + 1), lines.get(i)});
				}
			}
		}
		return found;
	}

	@Test
	public void theGameMapIsOnlyEverToldWhereToLookFromTheViewerOverlay() throws IOException
	{
		List<String> hits = new ArrayList<>();
		for (Path path : sources())
		{
			boolean allowed = path.getFileName().toString().equals("ViewerOverlay.java");
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
			{
				String trimmed = line.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
				{
					continue;
				}
				if (line.contains("initializeWorldMap") || line.contains("getWorldMapRenderer"))
				{
					hits.add(path + " uses the game map's data or renderer: " + trimmed);
				}
				if (!allowed && line.contains("setWorldMapPositionTarget"))
				{
					hits.add(path + " moves the game's map outside the viewer overlay: " + trimmed);
				}
			}
		}
		if (!hits.isEmpty())
		{
			fail(String.join("\n", hits));
		}
	}

	@Test
	public void neverReadsOtherPlayers() throws IOException
	{
		assertAbsent("Other players are never drawn or read",
			"getPlayers(", "getCachedPlayers", "PlayerSpawned", "PlayerDespawned", "Player.class");
	}

	@Test
	public void neverActsForThePlayer() throws IOException
	{
		assertAbsent("The plugin never acts for the player or injects input",
			"invokeMenuAction", "menuAction(", "java.awt.Robot", "dispatchEvent", "postEvent",
			"setMouseCanvasPosition", "sendKey");
	}

	@Test
	public void neverCapturesTheGameFrame() throws IOException
	{
		assertAbsent("The plugin draws its own imagery and never reads the game's pixels",
			"getBufferProvider", "getPixels(", "DrawManager", "Screenshot");
	}

	@Test
	public void runsNoExternalProgramsOrThreads() throws IOException
	{
		assertAbsent("No external programs, no private threads",
			"ProcessBuilder", "Runtime.getRuntime", "new Thread", "Executors.", "ForkJoinPool",
			"System.load", "System.exit");
	}

	@Test
	public void theOnlyDiskUseIsTheMapCacheThroughFilepath() throws IOException
	{
		// Since round 59H downloaded map pictures are kept on disk, in a folder of the plugin's own, in one class (PackCache) that goes
		// through RuneLite's Filepath, which cannot leave that folder. Nothing else touches a file, and Filepath.Unchecked is never used.
		assertAbsentExcept("The plugin reads and writes no files but its map cache",
			(file, line) -> file.equals("PackCache.java") && (line.equals("import java.nio.file.StandardCopyOption;")
				|| line.contains("StandardCopyOption.REPLACE_EXISTING")),
			"java.io.File", "java.nio.file", "FileInputStream", "FileOutputStream", "RUNELITE_DIR", "FileReader", "FileWriter",
			"RandomAccessFile", "Unchecked", "Files.", "Paths.", "ImageIO.setUseCache", "createTempFile", "getResourceAsFile");
		List<String> hits = new ArrayList<>();
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			// The plugin class asks RuneLite for its folder; MapAssets and PackedTiles only hand the folder on.
			boolean mayName = name.equals("PackCache.java") || name.equals("MapAssets.java") || name.equals("PackedTiles.java");
			if (text.contains("Filepath") && !mayName)
			{
				hits.add(name + " names Filepath");
			}
			if (name.equals("PackCache.java") && !text.contains("import net.runelite.client.util.Filepath;"))
			{
				hits.add("PackCache does not use Filepath");
			}
			if (text.contains("ImageIO.read(") && !text.contains("MemoryCacheImageInputStream"))
			{
				hits.add(name + " reads an image through ImageIO's default stream, which may use a temporary file");
			}
		}
		if (!hits.isEmpty())
		{
			fail(String.join("\n", hits));
		}
	}

	@Test
	public void readsResourcesAsStreams() throws IOException
	{
		assertAbsent("Plugin jars are not unpacked on disk, so resources must be read as streams",
			".getResource(", "getResources(");
	}

	@Test
	public void neverWritesGameState() throws IOException
	{
		assertAbsent("The plugin only reads the game. Nothing it does may change a game variable, the "
				+ "client's state or its settings, because that is how a plugin ends up sending something "
				+ "to the server",
			"setVarbit", "setVarp", "setVarc", "queueChangedVarp", "setGameState", "setUsername", "setPassword",
			"setStretchedEnabled", "setStretchedFast", "setStretchedKeepAspectRatio", "setLowCpu",
			"setIdleTimeout", "setMinimapZoom", "setOculus", "setPlayerAttackOption", "setNpcAttackOption",
			"setAllWidgetsAreOpTargetable", "setHideFriendAttackOptions", "setUnlockedFps", "setOutdatedScript",
			"setInventoryDragDelay", "setDraggedOnWidget", "setSelectedSceneTile", "setCameraSpeed");
	}

	@Test
	public void readsNoGameVariablesButTheChatOnes() throws IOException
	{
		// Reading the variables of the farming patches next to the player is the same read RuneLite's
		// own Time Tracking plugin does. No other part of the plugin may read a game variable. The one other thing is the news that some
		// variable changed (the event, with nothing read from it): the plugin class no longer listens for it since round 58E.
		List<String> hits = new ArrayList<>();
		for (Path path : sources())
		{
			if (path.toString().replace('\\', '/').contains("/farming/"))
			{
				continue;
			}
			boolean pluginClass = path.getFileName().toString().equals("WorldMapPlusPlugin.java");
			boolean textEntry = path.getFileName().toString().equals("GameTextEntry.java");
			boolean chatLine = path.getFileName().toString().equals("ChatLineReader.java");
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				String trimmed = lines.get(i).trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
				{
					continue;
				}
				for (String token : new String[] {"getVarbit", "getVarp", "getVarc", "VarbitChanged", "VarClientInt"})
				{
					if (pluginClass && token.equals("VarbitChanged"))
					{
						continue;
					}
					if (chatLine && token.equals("getVarc"))
					{
						// Round 50: the typing guide reads the typed text and the prompt mode, see theTypingGuideOnlyReadsTheChatLine.
						continue;
					}
					if (textEntry && token.equals("getVarc"))
					{
						// The two chat variables that say the game is taking typed text, see theGameTextEntryReadsOnlyTheSignalsKeyRemappingReads.
						continue;
					}
					if (lines.get(i).contains(token))
					{
						hits.add(path + ":" + (i + 1) + " contains '" + token + "'");
					}
				}
			}
		}
		if (!hits.isEmpty())
		{
			fail("Game variables may only be read by the farming tracker\n" + String.join("\n", hits));
		}
	}

	@Test
	public void theGameTextEntryReadsOnlyTheSignalsKeyRemappingReads() throws IOException
	{
		// Whether the game is taking typed text is read on the client thread from state RuneLite's own Key Remapping plugin reads too:
		// the chat prompt mode and the world map search flag (two client variables), the focused input field, and the chat windows.
		Path file = new File("src/main/java/com/worldmapplus/input/GameTextEntry.java").toPath();
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		assertEquals("two client variables", 2, text.split("getVarcIntValue\\(", -1).length - 1);
		assertTrue(text.contains("getVarcIntValue(VarClientID.MESLAYERMODE)"));
		assertTrue(text.contains("getVarcIntValue(VarClientID.WORLDMAP_SEARCHING)"));
		for (String banned : new String[] {"getVarbit", "getVarp", "setVarc", "setVarp", "runScript", "invokeMenuAction", "setHidden",
			"WidgetInfo", "WidgetID", "Robot", "dispatchEvent"})
		{
			assertFalse(banned, text.contains(banned));
		}
		// Only the plugin class and the keys' own code ask it anything, and only to leave a key to the game.
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String all = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			if (!name.equals("GameTextEntry.java") && !name.equals("WorldMapPlusPlugin.java"))
			{
				assertFalse(name + " asks whether the game takes text", all.contains("GameTextEntry") || all.contains("textEntry."));
			}
		}
	}

	@Test
	public void theTypedChatTextIsNeverRead() throws IOException
	{
		// Since round 56B the typing guide belongs to the Helper plugin. This plugin reads no typed text and holds no guide.
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String all = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			assertFalse(name + " reads the typed chat text", all.contains("CHATINPUT") || all.contains("CHATBOX_TYPED_TEXT")
				|| all.contains("getVarcStrValue"));
			assertFalse(name + " holds a typing guide", all.contains("TypingGuide") || all.contains("ChatLineReader"));
		}
	}

	@Test
	public void noLinkBrowserAndNoWikiHostHere() throws IOException
	{
		// The Wiki button moved to World Map Plus Helper in round 58F: it alone opens a wiki address in the player's browser. This plugin
		// only names a page; it never opens an address and names no host.
		for (Path path : sources())
		{
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			String name = path.getFileName().toString();
			assertFalse(name + " uses the link browser", text.contains("LinkBrowser"));
			assertFalse(name + " names a host", text.contains("oldschool.runescape.wiki"));
		}
	}

	@Test
	public void theItemManagerIsNotUsedHere() throws IOException
	{
		// Item prices (the drop tabs) moved to World Map Plus Helper in round 57D; this plugin reads nothing of the item manager.
		for (Path path : sources())
		{
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			assertFalse(path.getFileName() + " uses the item manager", text.contains("ItemManager") || text.contains("getItemPrice")
				|| text.contains("getItemComposition") || text.contains("getItemDefinition"));
		}
	}

	@Test
	public void theProgressCacheReadsNoGameState() throws IOException
	{
		// Since round 58E the Helper reads the player's levels, quests, diaries and quest points, and since round 61C it also judges the
		// requirements; this plugin only keeps the last answer. Nothing here asks the game.
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String all = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			assertFalse(name + " reads a skill level", all.contains("getRealSkillLevel") || all.contains("getBoostedSkillLevel"));
			assertFalse(name + " reads a quest", all.contains(".getState(client"));
			assertFalse(name + " reads a combat or total level", all.contains("getCombatLevel(") || all.contains("getTotalLevel("));
			assertFalse(name + " listens for variable changes", all.contains("VarbitChanged") || all.contains("StatChanged"));
		}
		String unlocks = new String(Files.readAllBytes(new File("src/main/java/com/worldmapplus/unlock/Unlocks.java").toPath()),
			StandardCharsets.UTF_8);
		assertFalse("the cache holds no client", unlocks.contains("Client ") || unlocks.contains("getVar"));
	}

	@Test
	public void theSidePanelNeverReachesTheGameOrOtherPluginsItself() throws IOException
	{
		// The panel is Swing code on its own thread. Anything that goes to the game or to Shortest Path
		// goes through the map's own actions, posted on the client thread.
		List<String> hits = new ArrayList<>();
		for (Path path : sources())
		{
			if (!path.toString().replace(File.separatorChar, '/').contains("/worldmapplus/panel/"))
			{
				continue;
			}
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
			{
				String trimmed = line.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
				{
					continue;
				}
				for (String token : new String[] {"net.runelite.api", "EventBus", "eventBus", "PluginMessage", "ConfigManager"})
				{
					if (line.contains(token))
					{
						hits.add(path + " uses " + token + ": " + trimmed);
					}
				}
			}
		}
		if (!hits.isEmpty())
		{
			fail(String.join(System.lineSeparator(), hits));
		}
	}

	@Test
	public void noMarkupThatCouldFetchAnImageIsBuilt() throws IOException
	{
		// Swing renders HTML in labels, and an image tag in it would be a network request.
		assertAbsent("a label must not be given an image or a link", "<img", "<link", "<a href", "src=");
	}

	@Test
	public void pluginMessagesGoOnlyToShortestPath() throws IOException
	{
		// A message is the only way this plugin talks to another plugin. It asks Shortest Path to draw a route, and it answers other
		// plugins in its own namespace from one place, the plugin class, so a message can never choose where an answer goes.
		List<String> hits = new ArrayList<>();
		int found = 0;
		int answers = 0;
		for (Path path : sources())
		{
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
			{
				if (line.contains("PluginMessage(") && line.contains("new ") && !line.trim().startsWith("*"))
				{
					found++;
					boolean ours = line.contains("new PluginMessage(ExternalOverlays.NAMESPACE,")
						&& path.getFileName().toString().equals("WorldMapPlusPlugin.java");
					answers += ours ? 1 : 0;
					if (!line.contains("SHORTEST_PATH") && !ours)
					{
						hits.add(path + " sends a message that names neither Shortest Path nor this plugin's own namespace: " + line.trim());
					}
				}
			}
		}
		assertTrue("expected the route messages and two answer sites, found " + found, found == 5);
		assertEquals("two answer sites: the tick answers and the goodbye when the plugin stops", 2, answers);
		if (!hits.isEmpty())
		{
			fail(String.join("\n", hits));
		}
		String actions = new String(Files.readAllBytes(new File("src/main/java/com/worldmapplus/bridge/MapActions.java").toPath()),
			StandardCharsets.UTF_8);
		assertTrue(actions.contains("SHORTEST_PATH = \"shortestpath\""));
	}

	@Test
	public void settingsAreOnlyWrittenToThePluginsOwnGroup() throws IOException
	{
		List<String> hits = new ArrayList<>();
		for (Path path : sources())
		{
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
			{
				String trimmed = line.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*"))
				{
					continue;
				}
				boolean writes = line.contains("setConfiguration(") || line.contains("setRSProfileConfiguration(")
					|| line.contains("unsetConfiguration(") || line.contains("unsetRSProfileConfiguration(");
				boolean reads = line.contains("getConfiguration(") || line.contains("getRSProfileConfiguration(");
				if ((writes || reads) && !line.contains("WorldMapPlusConfig.GROUP"))
				{
					hits.add(path + " touches a setting outside the plugin's own group: " + trimmed);
				}
			}
		}
		if (!hits.isEmpty())
		{
			fail(String.join("\n", hits));
		}
	}

	@Test
	public void sendsNothingToTheGameFromTheMapItself() throws IOException
	{
		assertAbsentExcept("Clicks on the map move the map's own camera. They never become game input",
			(file, line) -> file.equals("ShareChat.java") && (line.equals(".setType(MenuAction.RUNELITE)")
				|| line.equals("import net.runelite.api.MenuAction;")),
			"MenuAction", "CC_OP", "WIDGET_TARGET", "setWidgetTarget", "sendMenuAction");
	}

	@Test
	public void readsNothingOfOtherPluginsSinceSupportForThemMovedToTheHelper() throws IOException
	{
		// The points other plugins put on the game's map, the lists of plugins and overlays, and the messages of Plugin API version 1
		// belong to the Helper plugin (see docs/PLAN.md, round 57C). This plugin reads none of them.
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			assertFalse(name + " reads world map points", text.contains("WorldMapPointManager") || text.contains("WorldMapPoint"));
			// The one reading of the list of plugins is the check whether Shortest Path is installed and on.
			assertFalse(name + " looks plugins or overlays up", !name.equals("MapActions.java") && (text.contains("PluginManager")
				|| text.contains("getPlugins()")) || text.contains("overlayManager.anyMatch(") || text.contains("getOverlays"));
		}
		String external = new String(Files.readAllBytes(new File("src/main/java/com/worldmapplus/bridge/ExternalOverlays.java").toPath()),
			StandardCharsets.UTF_8);
		for (String v1 : new String[] {"\"marker\"", "\"marker_clear\"", "\"line\"", "\"line_clear\"", "\"hello\"", "\"covers\"", "\"focus\""})
		{
			assertFalse("version 1 message " + v1 + " is answered by the Helper only", external.contains("case " + v1 + ":"));
		}
	}

	@Test
	public void theMouseQueueIsOnlyReorderedForTheMapsOwnListeners() throws IOException
	{
		Path overlay = new File("src/main/java/com/worldmapplus/ui/ViewerOverlay.java").toPath();
		String text = new String(Files.readAllBytes(overlay), StandardCharsets.UTF_8);
		assertTrue(text.contains("mouseManager.registerMouseListener(surface.getInput())"));
		assertTrue("nothing else is registered or removed", !text.contains("registerMouseListener(1")
			&& occurrences(text, "mouseManager.registerMouseListener(") == 1);
	}

	private static int occurrences(String text, String what)
	{
		int count = 0;
		for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + 1))
		{
			count++;
		}
		return count;
	}

	@Test
	public void chatIsNeverRead() throws IOException
	{
		// Since round 56B the !wmp chat command and the line that shows what a code holds belong to the Helper plugin. This plugin
		// reads no chat line, signs up no chat command and subscribes to no chat event.
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			assertFalse(name + " reads chat", text.contains("ChatMessage") || text.contains("ChatCommandManager")
				|| text.contains("ChatMessageType") || text.contains("setRuneLiteFormatMessage"));
		}
	}

	@Test
	public void theChatAreaIsOnlyUsedForTheSearchPromptAndEverythingElseGoesThroughOneClass() throws IOException
	{
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			boolean mentions = text.contains("ChatboxPanelManager") || text.contains("ChatboxTextInput")
				|| text.contains("ChatboxItemSearch") || text.contains("game.chatbox");
			assertFalse(path + " uses the chat area, which only ChatboxPrompts may", mentions && !name.equals("ChatboxPrompts.java"));
		}
		for (String[] line : linesUnder("/worldmapplus/input/ChatboxPrompts"))
		{
			for (String banned : new String[] {"openTextMenuInput", "openInput(", "openTextInput(", "getCurrentInput", "runScript",
				".setText(", ".setHidden(", ".createChild(", "sendMessage", "addChatMessage"})
			{
				if (banned.equals("openTextInput(") && line[1].contains("manager.openTextInput(title)"))
				{
					continue;
				}
				// The one look at the manager's current input, to close the prompt only while it is still this plugin's own.
				if (banned.equals("getCurrentInput") && line[1].contains("manager.getCurrentInput() == ours"))
				{
					continue;
				}
				assertFalse(line[0] + " must not use " + banned, line[1].contains(banned));
			}
		}
		String plugin = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/WorldMapPlusPlugin.java")),
			StandardCharsets.UTF_8);
		assertTrue("the plugin closes the search prompt when it stops", plugin.contains("chatSearch.closeAll();"));
	}

	@Test
	public void extraWindowsReadNothingFromTheGame() throws IOException
	{
		Path windows = SOURCE_ROOT.resolve("com/worldmapplus/ui/MapWindows.java");
		for (String line : Files.readAllLines(windows, StandardCharsets.UTF_8))
		{
			String trimmed = line.trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("import "))
			{
				continue;
			}
			// The client is handed on to the surface and never asked anything. The game thread is only given work.
			if (line.contains("client.") && !line.contains("this.client = client"))
			{
				fail("a window must not read the game: " + trimmed);
			}
			for (String banned : new String[] {"getLocalPlayer", "getNpcs", "getWidget", "getVarbit", "getWorldMap", "getGameState"})
			{
				assertFalse("a window must not read the game: " + banned, line.contains(banned));
			}
		}
	}

	/**
	 * Fails if any non-comment line of the main sources matches a pattern. The Plugin Hub packager and its reviewers
	 * reject these, so a hit here is a hit there.
	 */
	private void assertNoMatch(String reason, String... regexes) throws IOException
	{
		List<String> hits = new ArrayList<>();
		for (Path path : sources())
		{
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				String trimmed = lines.get(i).trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
				{
					continue;
				}
				for (String regex : regexes)
				{
					if (Pattern.compile(regex).matcher(lines.get(i)).find())
					{
						hits.add(path + ":" + (i + 1) + " matches /" + regex + "/: " + trimmed);
					}
				}
			}
		}
		if (!hits.isEmpty())
		{
			fail(reason + "\n" + String.join("\n", hits));
		}
	}

	@Test
	public void createsNoGsonOrOkHttpOfItsOwn() throws IOException
	{
		// The packager's disallowed list makes the constructors of these a build error: inject the client's instance.
		assertNoMatch("Do not create your own Gson, GsonBuilder or OkHttpClient; take the client's through injection",
			"new\\s+Gson\\s*\\(", "new\\s+GsonBuilder\\s*\\(", "new\\s+OkHttpClient", "OkHttpClient\\.Builder");
	}

	@Test
	public void usesNoApiTheHubListsAsDisallowed() throws IOException
	{
		// Mirrors disallowed-apis.txt of the Plugin Hub packager (plugin-hub-tooling).
		assertNoMatch("The Plugin Hub packager lists these as disallowed (see docs/RULES-AUDIT.md)",
			"\\bChatMessageManager\\b", "\\bWidgetInfo\\b", "\\bWidgetID\\b", "\\.getItemStats\\(", "\\bAccountClient\\b",
			"\\bAccountSession\\b", "\\bSessionManager\\b");
	}

	@Test
	public void usesNoLanguageFeatureTheHubForbids() throws IOException
	{
		// Reflection is covered by usesNoReflection. These are the others the Hub's rules list: JNI and JNA, native
		// memory, serialization, class loading, runtime code generation and the java.lang.Runtime class.
		assertNoMatch("The Plugin Hub forbids these language features",
			"\\b(public|private|protected|static|final)\\s+native\\b", "System\\.load", "com\\.sun\\.jna", "net\\.java\\.dev\\.jna", "sun\\.misc", "\\bUnsafe\\b", "org\\.lwjgl",
			"\\bObjectInputStream\\b", "\\bObjectOutputStream\\b", "\\bSerializable\\b", "\\bExternalizable\\b",
			"\\bClassLoader\\b", "\\bServiceLoader\\b", "javax\\.script", "javax\\.tools", "java\\.lang\\.invoke",
			"\\bProxy\\.newProxyInstance", "\\bdefineClass\\b", "java\\.lang\\.Runtime\\b", "\\bRuntime\\.");
	}

	@Test
	public void usesNoForbiddenJavaFunction() throws IOException
	{
		// The Hub's list of forbidden functions: opening links through the desktop, Thread.interrupt and Thread.sleep,
		// menuAction, java.io file access and any private timer thread.
		assertNoMatch("The Plugin Hub forbids these functions",
			"\\bDesktop\\.", "java\\.awt\\.Desktop", "LinkBrowser\\.open\\(", "\\bThread\\.", "\\.interrupt\\(", "\\bmenuAction\\(",
			"java\\.io\\.File", "java\\.util\\.Timer", "\\bTimerTask\\b", "\\bnew\\s+Thread\\b", "\\bnew\\s+Timer\\s*\\(\\s*\\)");
	}

	@Test
	public void doesNotUseTheNetRuneLitePackage() throws IOException
	{
		assertNoMatch("A plugin may not put its own classes in net.runelite", "^\\s*package\\s+net\\.runelite");
	}

	@Test
	public void hasNoGlobalKeyOrEventHookAtAll() throws IOException
	{
		// java.awt.KeyboardFocusManager is on the Hub's list of forbidden classes. Toolkit.addAWTEventListener, a key event
		// dispatcher or post processor, and a replacement event queue would see the same keys by another route, so none is
		// used. Keys reach the plugin through RuneLite's KeyManager alone (see docs/RULES-AUDIT.md, "KeyboardFocusManager").
		assertNoMatch("Add no global key or event hook; take keys from RuneLite's KeyManager",
			"KeyboardFocusManager", "addAWTEventListener", "AWTEventListener\\b", "KeyEventDispatcher", "KeyEventPostProcessor",
			"getSystemEventQueue", "EventQueue\\.push");
	}

	@Test
	public void theCodeFormatsOnlyEverTouchTheClipboardFromThePanel() throws IOException
	{
		for (Path path : sources())
		{
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			boolean clipboard = text.contains("getSystemClipboard");
			String name = path.getFileName().toString();
			boolean allowed = name.equals("ShareDialog.java") || name.equals("MapActions.java");
			assertFalse(path + " uses the clipboard", clipboard && !allowed);
		}
	}

	@Test
	public void partnerCodeIsOnlyEverReceivedInMessagesNeverLookedUp() throws IOException
	{
		String text = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/bridge/Partners.java")), StandardCharsets.UTF_8);
		for (String banned : new String[] {"PluginManager", "getPlugins", "ServiceLoader", "ClassLoader", "Class.forName", "getClass()",
			"Executors", "new Thread", "invokeAndWait", "Robot", "net.runelite.client.ui", "net.runelite.api.Client", "MouseListener", "KeyListener"})
		{
			assertFalse("the partner registry must not use " + banned, text.contains(banned));
		}
		// Every partner callback is made from one guarded place, Partners.call, which has one line for each shape of callback.
		int calls = 0;
		for (String line : text.split("\n"))
		{
			String code = line.trim();
			if (!code.startsWith("*") && !code.startsWith("//") && (code.contains(".apply(") || code.contains(".accept(")) && !code.contains("say.accept(")
				&& !code.contains("thread.accept(") && !code.contains("background.accept(") && !code.contains(") callback).")
				// The plugin's own hooks, handed over by the plugin class: not partner code.
				&& !code.contains("toEdt.accept(") && !code.contains("toClient.accept(") && !code.contains("statusLine.accept(")
				&& !code.contains("goer.apply(") && !code.contains("mapNames.apply("))
			{
				fail("an unguarded call into partner code: " + code);
			}
			calls += code.contains(") callback).") ? 1 : 0;
		}
		assertEquals("a partner callback is called only in Partners.call: a Function, a BiConsumer and a Consumer", 3, calls);
		// Only the bridge hands messages to the registry, and only the plugin class posts what it queues.
		for (Path path : sources())
		{
			String other = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			String name = path.getFileName().toString();
			if (!name.equals("Partners.java") && !name.equals("ExternalOverlays.java"))
			{
				assertFalse(name + " feeds the registry", other.contains(".onMessage("));
			}
		}
	}
}
