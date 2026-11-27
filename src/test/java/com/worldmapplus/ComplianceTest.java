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
				if (name.equals("WikiLinks.java") && line.contains("PREFIX = \"https://oldschool.runescape.wiki/w/\";"))
				{
					// The wiki page addresses the Wiki button hands to RuneLite's LinkBrowser.browse: opened in the player's browser, never fetched.
					continue;
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
		// One exception, round 49: ShareChat adds "Open in World Map Plus" to a chat line that holds a share code, the way
		// Chat History adds "Copy to clipboard". theOnlyMenuEntryIsTheClientSideOpenEntryOnChatLines holds it to that.
		assertAbsentExcept("Jagex prohibits new menu entries that send actions to the server",
			(file, line) -> file.equals("ShareChat.java") && (line.equals("client.getMenu().createMenuEntry(1)")
				|| line.contains("MenuEntryAdded"))
				|| file.equals("WorldMapPlusPlugin.java") && line.contains("MenuEntryAdded"),
			"createMenuEntry", "addMenuEntry", "setMenuEntries", "MenuEntryAdded", "MenuOpened",
			"MenuOptionClicked", "getMenuEntries", "swapEntries", "Overlay.addMenuEntry");
	}

	@Test
	public void theOnlyMenuEntryIsTheClientSideOpenEntryOnChatLines() throws IOException
	{
		String chat = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/bridge/ShareChat.java")),
			StandardCharsets.UTF_8);
		assertEquals("one entry is made", 1, occurrences(chat, "createMenuEntry("));
		assertEquals("of one type", 1, occurrences(chat, ".setType("));
		assertTrue("RuneLite's own type, which never reaches the game server", chat.contains(".setType(MenuAction.RUNELITE)"));
		assertTrue("only on the Report entry of a chat line", chat.contains("\"Report\".equals(event.getOption())")
			&& chat.contains("InterfaceID.CHATBOX") && chat.contains("InterfaceID.Chatbox.SCROLLAREA"));
		assertTrue("its click only opens the share in this plugin", chat.contains(".onClick(e -> open.accept(share));"));
		for (String banned : new String[] {"setIdentifier", "setParam0", "setParam1", "setItemId", "setWidget", "setForceLeftClick",
			"invokeMenuAction", "setMenuEntries", "removeMenuEntry", ".setText(", ".setHidden(", "MenuAction.CC_", "MenuAction.WIDGET"})
		{
			assertFalse("ShareChat must not use " + banned, chat.contains(banned));
		}
		String plugin = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/WorldMapPlusPlugin.java")),
			StandardCharsets.UTF_8);
		assertTrue("the plugin only passes the event on", plugin.contains("shareChat.onMenuEntryAdded(event);"));
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
	public void readsGameVariablesOnlyWhereListed() throws IOException
	{
		// No part of the plugin may read a game variable except the few listed here. The one other thing is the news that some
		// variable changed (the event, with nothing read from it): the plugin class passes it on to the progress snapshot, which then
		// asks the API's quest list, see theProgressSnapshotAsksTheApiAndNothingElse.
		List<String> hits = new ArrayList<>();
		for (Path path : sources())
		{
			boolean pluginClass = path.getFileName().toString().equals("WorldMapPlusPlugin.java");
			boolean textEntry = path.getFileName().toString().equals("GameTextEntry.java");
			boolean snapshot = path.getFileName().toString().equals("Unlocks.java");
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
					if (snapshot && (token.equals("getVarbit") || token.equals("getVarp")))
					{
						// Round 47: the diary tiers and the quest points, see theProgressSnapshotAsksTheApiAndNothingElse.
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
			fail("Game variables may only be read where listed\n" + String.join("\n", hits));
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
	public void theTypingGuideOnlyReadsTheChatLine() throws IOException
	{
		// Round 50. The typing guide compares the chat input line with the share code the player asked to type. One class reads the
		// game for it, with plain getters on the client thread: the typed text (the client string Key Remapping reads), the prompt mode,
		// and the input line component's visibility, box and text (round 72: the guide is drawn above the line, so no font or
		// alignment is read). Nothing is written, typed, sent or listened to.
		Path file = SOURCE_ROOT.resolve("com/worldmapplus/typing/ChatLineReader.java");
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		assertEquals("one read of the typed text", 1, occurrences(text, "getVarcStrValue("));
		assertTrue(text.contains("client.getVarcStrValue(VarClientID.CHATINPUT)"));
		assertEquals("one read of the prompt mode", 1, occurrences(text, "getVarcIntValue("));
		assertTrue(text.contains("client.getVarcIntValue(VarClientID.MESLAYERMODE) != 0"));
		assertEquals("three components, all of the chat box, read", 3, occurrences(text, "InterfaceID.Chatbox."));
		for (String component : new String[] {"InterfaceID.Chatbox.INPUT", "InterfaceID.Chatbox.CHATDISPLAY", "InterfaceID.Chatbox.MES_LAYER_HIDE"})
		{
			assertTrue(component, text.contains(component));
		}
		for (String banned : new String[] {"setVarc", "setVarbit", "setVarp", ".setText(", ".setHidden(", ".setFontId(", ".setTextColor(",
			".setXTextAlignment(", ".setYTextAlignment(", ".setOpacity(", "runScript", "invokeMenuAction", "KeyListener", "keyManager",
			"KeyEvent", "Robot", "dispatchEvent", "MenuEntry", "ChatMessage", "WidgetInfo", "WidgetID", "getPlayers"})
		{
			assertFalse("the chat line reader must not use " + banned, text.contains(banned));
		}
		// Nothing else in the plugin reads the typed text.
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String all = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			if (!name.equals("ChatLineReader.java"))
			{
				assertFalse(name + " reads the typed chat text", all.contains("CHATINPUT") || all.contains("CHATBOX_TYPED_TEXT")
					|| all.contains("getVarcStrValue"));
			}
		}
		// The guide itself and the overlay hold no game types: they compare and draw. The overlay takes presses on its card only.
		String guide = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/typing/TypingGuide.java")), StandardCharsets.UTF_8);
		assertFalse(guide.contains("net.runelite"));
		String overlay = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/typing/TypingGuideOverlay.java")),
			StandardCharsets.UTF_8);
		assertFalse("the overlay reads nothing of the game", overlay.contains("net.runelite.api") || overlay.contains("Client client"));
		for (String banned : new String[] {"KeyListener", "KeyEvent", "keyManager", "MenuEntry", "OverlayMenuEntry", "Robot", "dispatchEvent"})
		{
			assertFalse("the overlay must not use " + banned, overlay.contains(banned));
		}
		assertTrue("presses are taken only on the card", overlay.contains("pressed = cardShown && inCard(e.getX(), e.getY());"));
		// The plugin reads the line once a client tick, only with the setting on, and stops the guide when it stops.
		String plugin = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/WorldMapPlusPlugin.java")), StandardCharsets.UTF_8);
		int tick = plugin.indexOf("public void onClientTick(ClientTick tick)");
		assertTrue(tick > 0);
		String handler = plugin.substring(tick, plugin.indexOf("postAnswers();", tick));
		assertTrue(handler.contains("if (config.typingGuide())") && handler.contains("chatLine.onClientTick();"));
		assertEquals("one read site", 1, occurrences(plugin, "chatLine.onClientTick()"));
		assertTrue(plugin.contains("typingGuide.stop();") && plugin.contains("overlayManager.remove(typingOverlay);")
			&& plugin.contains("mouseManager.unregisterMouseListener(typingOverlay.getInput());"));
	}

	@Test
	public void theWikiButtonOnlyEverHandsAWikiAddressToRuneLitesLinkBrowser() throws IOException
	{
		// LinkBrowser.browse is allowed by the Hub's rejected features page (LinkBrowser.open and Desktop are not). One class calls it,
		// with an address it made from the wiki's fixed prefix (and checked starts with it), and the plugin itself makes no request:
		// the player's browser does.
		int users = 0;
		for (Path path : sources())
		{
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			String name = path.getFileName().toString();
			if (text.contains("LinkBrowser"))
			{
				users++;
				assertEquals("only the wiki links use the link browser", "WikiLinks.java", name);
				assertEquals("one call", 1, text.split("LinkBrowser\\.browse\\(", -1).length - 1);
				assertFalse(text.contains("LinkBrowser.open"));
				assertTrue("every address starts with the wiki's article path", text.contains("new StringBuilder(prefix)")
					&& text.contains("address(PREFIX, ") && text.contains("address(PREFIX + \"Special:Search?search=\", "));
				assertTrue("and that is checked first", text.indexOf("address.startsWith(PREFIX)") > 0
					&& text.indexOf("address.startsWith(PREFIX)") < text.indexOf("LinkBrowser.browse("));
			}
			assertFalse(name + " names a host", !name.equals("WikiLinks.java") && text.contains("oldschool.runescape.wiki"));
		}
		assertEquals(1, users);
	}

	@Test
	public void itemPricesComeOnlyFromRuneLitesItemManagerOnTheClientThread() throws IOException
	{
		// Round 46: the drop tabs show values from RuneLite's own item manager (the prices RuneLite already holds; this plugin asks
		// nothing of any server). The plugin class hands one method of it, getItemPrice, to ItemPrices with the client thread's queue,
		// and ItemPrices calls it only inside the task it gives that queue. No other use of the item manager exists.
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			if (name.equals("WorldMapPlusPlugin.java"))
			{
				assertEquals("one use of the item manager", 1, text.split("itemManager::", -1).length - 1);
				assertEquals("only its prices", 1, text.split("itemManager::getItemPrice, clientThread::invokeLater", -1).length - 1);
				assertFalse("nothing else of it", text.contains("itemManager."));
			}
			else
			{
				assertFalse(name + " uses the item manager", text.contains("ItemManager") || text.contains("getItemPrice")
					|| text.contains("getItemComposition") || text.contains("getItemDefinition"));
			}
		}
		Path prices = new File("src/main/java/com/worldmapplus/drops/ItemPrices.java").toPath();
		String text = new String(Files.readAllBytes(prices), StandardCharsets.UTF_8);
		assertEquals("the price is read in one place", 1, text.split("reader\\.applyAsLong\\(", -1).length - 1);
		int reading = text.indexOf("private void readOnClientThread(");
		assertTrue(reading > 0 && text.indexOf("reader.applyAsLong(") > reading);
		assertEquals("which only the client thread's task calls", 2, text.split("readOnClientThread\\(", -1).length - 1);
		assertTrue(text.contains("clientThread.accept(() -> readOnClientThread(wanted));"));
		assertTrue("a request is bounded", text.contains("wanted.size() >= MAX_PER_REQUEST"));
	}

	@Test
	public void theProgressSnapshotAsksTheApiAndNothingElse() throws IOException
	{
		// The snapshot of levels and quests reads the real level of a skill and the state of a quest through the API, on the client
		// thread, and nowhere else in the plugin does.
		Path unlocks = new File("src/main/java/com/worldmapplus/unlock/Unlocks.java").toPath();
		String text = new String(Files.readAllBytes(unlocks), StandardCharsets.UTF_8);
		assertEquals("one read of a level", 1, text.split("getRealSkillLevel", -1).length - 1);
		assertEquals("one read of a quest", 1, text.split("quest\\.getState\\(client\\)", -1).length - 1);
		assertFalse("no boosted level", text.contains("getBoostedSkillLevel"));
		// The local player's combat level and the total level, one read each, through the API.
		assertEquals("one read of the total level", 1, text.split("getTotalLevel\\(\\)", -1).length - 1);
		assertEquals("one read of a combat level", 1, text.split("getCombatLevel\\(\\)", -1).length - 1);
		assertTrue("the combat level is the local player's", text.contains("client.getLocalPlayer();")
			&& text.indexOf("client.getLocalPlayer();") < text.indexOf("getCombatLevel()"));
		// Round 47: the achievement diary tiers and the quest points, one plain read each, of the varbits Diaries names (RuneLite's
		// own VarbitID constants) and of the quest point counter, through one method each; nothing is ever set.
		assertEquals("one varbit read", 1, text.split("getVarbitValue\\(", -1).length - 1);
		assertTrue(text.contains("return client.getVarbitValue(varbit);"));
		assertEquals("the varbit is always one Diaries names", 1, text.split("source\\.diaryValue\\(", -1).length - 1);
		assertTrue(text.contains("source.diaryValue(Diaries.varbit(fact - DIARIES))"));
		assertEquals("one varp read, the quest points", 1, text.split("getVarpValue\\(", -1).length - 1);
		assertTrue(text.contains("client.getVarpValue(VarPlayerID.QP)"));
		for (String banned : new String[] {"getVarcIntValue", "getVarcStrValue", "setVarbit", "setVarp", "setVarc", "getVarps()",
			"runScript", "invokeMenuAction"})
		{
			assertFalse("the snapshot does not use " + banned, text.contains(banned));
		}
		String diaries = new String(Files.readAllBytes(new File("src/main/java/com/worldmapplus/unlock/Diaries.java").toPath()),
			StandardCharsets.UTF_8);
		assertFalse("Diaries only names varbits", diaries.contains("client") || diaries.contains("getVar"));
		assertEquals("48 tiers, each one of RuneLite's own names", 48, diaries.split("VarbitID\\.", -1).length - 1);
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String all = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			if (!name.equals("Unlocks.java"))
			{
				assertFalse(name + " reads a skill level", all.contains("getRealSkillLevel") || all.contains("getBoostedSkillLevel"));
				assertFalse(name + " reads a quest", all.contains(".getState(client"));
				assertFalse(name + " reads a combat or total level", all.contains("getCombatLevel(") || all.contains("getTotalLevel("));
			}
		}
		// The variable change event is only passed on, and never looked into.
		Path plugin = new File("src/main/java/com/worldmapplus/WorldMapPlusPlugin.java").toPath();
		String body = new String(Files.readAllBytes(plugin), StandardCharsets.UTF_8);
		int at = body.indexOf("public void onVarbitChanged(VarbitChanged event)");
		assertTrue(at > 0);
		String handler = body.substring(at, body.indexOf("}", at));
		assertTrue(handler.contains("unlocks.variablesChanged();"));
		assertFalse("the event's contents are not read", handler.contains("event."));
	}

	@Test
	public void theProgressSnapshotIsOnlyEverFedFromTheClientThreadsEvents() throws IOException
	{
		// Every call that changes the snapshot comes from an event handler of the plugin class or from a task given to the client thread.
		Path plugin = new File("src/main/java/com/worldmapplus/WorldMapPlusPlugin.java").toPath();
		String body = new String(Files.readAllBytes(plugin), StandardCharsets.UTF_8);
		for (String call : new String[] {"unlocks.want(", "unlocks.onGameTick()", "unlocks.onStatChanged(", "unlocks.onGameStateChanged("})
		{
			assertTrue(call, body.contains(call));
		}
		assertTrue("a change of setting is applied on the client thread", body.contains("clientThread.invokeLater(this::wantProgress)"));
		assertFalse("no loop that polls", body.contains("while (") && body.contains("unlocks"));
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
		assertTrue("expected the route messages and one answer site, found " + found, found == 4);
		assertEquals("one answer site", 1, answers);
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
	public void onlyLooksAtTheWorldMapPointsOtherPluginsAdded() throws IOException
	{
		// The point manager has no public way to list its points, but removeIf shows each point to a
		// predicate. Answering "no" every time reads them and removes none. Nothing may ever add or remove one.
		assertAbsent("Other plugins' world map points may be read and never changed",
			"worldMapPointManager.add(", "worldMapPointManager.remove(", "manager.add(", "manager.remove(");
		Path points = new File("src/main/java/com/worldmapplus/bridge/NativePoints.java").toPath();
		String text = new String(Files.readAllBytes(points), StandardCharsets.UTF_8);
		int at = text.indexOf("manager.removeIf(");
		assertTrue("the points are read through removeIf", at > 0);
		String body = text.substring(at, text.indexOf("}));", at));
		assertTrue("and the predicate always answers no", body.contains("return false;") && !body.contains("return true;"));
		assertTrue("one read site", occurrences(text, "removeIf(") == 1);
	}

	@Test
	public void neverLooksAtTheOverlaysOrPluginsOfOthers() throws IOException
	{
		// Since round 73 (the census went) other plugins' overlays are not read at all (the plugin list is read only by MapActions,
		// to find Shortest Path). This plugin only ever adds and removes its own overlays.
		assertAbsent("Other plugins overlays are not read or changed",
			"overlayManager.removeIf(", "overlayManager.clear(", "overlayManager.anyMatch(");
		for (String[] line : linesUnder("/worldmapplus/"))
		{
			if (line[1].contains("overlayManager.remove("))
			{
				assertTrue("only this plugin's own overlays are removed: " + line[0],
					line[1].contains("hudOverlay") || line[1].contains("viewerOverlay") || line[1].contains("typingOverlay"));
			}
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
	public void chatIsOnlyReadByTheShareHookAndOnlyForItsText() throws IOException
	{
		for (Path path : sources())
		{
			String name = path.getFileName().toString();
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			boolean mentions = text.contains("ChatMessage") || text.contains("ChatMessageType");
			boolean allowed = name.equals("ShareChat.java");
			if (mentions && !allowed)
			{
				fail(path + " reads chat, which only the share hook may do");
			}
		}
		Path hook = SOURCE_ROOT.resolve("com/worldmapplus/bridge/ShareChat.java");
		List<String> lines = Files.readAllLines(hook, StandardCharsets.UTF_8);
		for (String line : lines)
		{
			String trimmed = line.trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
			{
				continue;
			}
			for (String banned : new String[] {"getSender(", "getTimestamp(", "getMessageNode().getName", "sendMessage",
				"addChatMessage", "ChatMessageManager", "sendChat", "setSender", "setName("})
			{
				assertFalse("the hook must not use " + banned, line.contains(banned));
			}
		}
		// A sender's name is looked at for one reason only: to keep the parts of one player's code apart from another's and
		// to tell the player's own lines (labelled, never listed) from others'. That is two calls, the line's sender and the
		// local player, both in senderKey.
		int names = 0;
		for (String line : lines)
		{
			String trimmed = line.trim();
			if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*") && line.contains("getName("))
			{
				names++;
			}
		}
		assertEquals("the hook reads names only to compare the sender with the local player", 2, names);
		String hookText = new String(Files.readAllBytes(hook), StandardCharsets.UTF_8);
		// Round 51: the player's own lines get the label too (on the stored message, after the text, never what is sent),
		// and their codes are never listed as shared with them.
		assertTrue("the hook tells the player's own lines apart", hookText.contains("String sender = senderKey(event);")
			&& hookText.contains("boolean mine = sender.startsWith(MINE);"));
		assertTrue("and does not list them", hookText.contains("line, !mine);"));
		assertFalse("no input hook on the command, so nothing typed is held or changed",
			hookText.contains("ChatboxInput") || hookText.contains("ChatInput") || hookText.contains("registerCommand(ShareCodec.COMMAND, this::onCommand,"));
		// Round 52: the line shows a description in place of the code through the node's RuneLite format message, as Chat
		// Commands shows the answer to !lvl. The message itself is never changed.
		assertEquals("the stored message itself is never changed", 0, occurrences(hookText, ".setValue("));
		assertEquals("the shown text is set in one place, the description code", 1,
			occurrences(hookText, "line.node.setRuneLiteFormatMessage(format);"));
		assertEquals(1, occurrences(hookText, "setRuneLiteFormatMessage("));
		assertTrue("the player can keep their own lines as typed",
			hookText.contains("Shown line = mine && config.shareCodes() != WorldMapPlusConfig.ShareCodes.FROM_EVERYONE ? null : new Shown(node);"));
		String plugin = new String(Files.readAllBytes(SOURCE_ROOT.resolve("com/worldmapplus/WorldMapPlusPlugin.java")),
			StandardCharsets.UTF_8);
		assertFalse("the plugin has no chat handler of its own", plugin.contains("onChatMessage"));
		assertTrue("codes arrive through RuneLite's chat command manager",
			hookText.contains("commands.registerCommand(ShareCodec.COMMAND, this::onCommand);")
			&& hookText.contains("commands.unregisterCommand(ShareCodec.COMMAND);"));
		assertTrue("and the plugin signs the command up and takes it back", plugin.contains("shareChat.start(")
			&& plugin.contains("shareChat.stop();"));
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
}
