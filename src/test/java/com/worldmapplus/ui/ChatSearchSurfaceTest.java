package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.input.ChatSearch;
import com.worldmapplus.input.FakePrompts;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The search typed in the chat area, driven through a whole surface with real input events and a fake chat area that keeps the
 * real one's rules. Covers the setting being off (the chat area is never touched), on (opened on focus, typing mirrored, closed
 * on every way out) and switched while the prompt is open.
 */
public class ChatSearchSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final JPanel SOURCE = new JPanel();

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private final FakePrompts prompts = new FakePrompts();
	private final ChatSearch chat = new ChatSearch(prompts, Runnable::run);
	private boolean chatOn;
	private MapSurface surface;
	private Client client;
	private BufferedImage canvas;
	private long now = 1_000_000;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor);
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	@Before
	public void setUp()
	{
		canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
		client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				Class<?> type = method.getReturnType();
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(-1, -1);
				}
				if (type == boolean.class)
				{
					return false;
				}
				if (type == int.class || type == long.class || type == double.class || type == float.class)
				{
					return 0;
				}
				return null;
			});
		surface = newSurface(chat);
	}

	private MapSurface newSurface(ChatSearch withChat)
	{
		return newSurface(withChat, new WorldMapPlusConfig()
		{
			@Override
			public boolean typeInChatArea()
			{
				return chatOn;
			}
		});
	}

	private MapSurface newSurface(ChatSearch withChat, WorldMapPlusConfig config)
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(new ViewMemory(null)
		{
			@Override
			public void save(String slot, int mapId, double x, double y, double ppt)
			{
			}

			@Override
			public Saved load(String slot)
			{
				return null;
			}
		}, pins, external, new MapActions(new EventBus(), external), new Settings(null), null,
			routes, new PanelLauncher(), withChat);
		return new MapSurface(client, assets, config, services);
	}

	private static PlayerPosition lumbridge()
	{
		return new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
	}

	private static MapSurface.Options options()
	{
		return new MapSurface.Options().followOnOpen(true).openZoom(4.0);
	}

	private void frame()
	{
		frame(surface, BOUNDS, options());
	}

	private void frame(MapSurface on, Rectangle bounds, MapSurface.Options options)
	{
		Graphics2D g = canvas.createGraphics();
		now += 16;
		on.render(g, bounds, lumbridge(), options, now);
		g.dispose();
	}

	private void frames(int count)
	{
		for (int i = 0; i < count; i++)
		{
			frame();
		}
	}

	private MouseEvent mouse(int id, int x, int y)
	{
		return new MouseEvent(SOURCE, id, now, 0, x, y, 1, false, MouseEvent.BUTTON1);
	}

	private KeyEvent controlF(MapSurface on)
	{
		KeyEvent event = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F, 'f');
		on.getInput().keyPressed(event);
		return event;
	}

	/** Starts a search the way a player does, with the map already showing. */
	private void startSearch()
	{
		frames(10);
		controlF(surface);
		frame();
		assertTrue(surface.searchBox().isFocused());
	}

	private KeyEvent typeDirect(char c)
	{
		KeyEvent event = new KeyEvent(SOURCE, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c);
		surface.getInput().keyTyped(event);
		return event;
	}

	@Test
	public void withTheSettingOffTheChatAreaIsNeverTouched()
	{
		chatOn = false;
		startSearch();
		for (char c : "bob's brilliant".toCharArray())
		{
			assertTrue("taken for the map's own field", typeDirect(c).isConsumed());
		}
		frame();
		assertEquals("bob's brilliant", surface.searchBox().getText());
		assertEquals(SearchKind.SHOP, surface.searchBox().getResults().get(0).getKind());

		surface.getInput().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_ENTER, '\n'));
		frames(30);
		assertFalse(surface.searchBox().isFocused());

		controlF(surface);
		frame();
		surface.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 20, 20));
		frame();
		controlF(surface);
		frame();
		surface.suspend();
		frame();
		surface.hide();
		surface.reset();
		chat.closeAll();
		assertEquals("not one call to the chat area", 0, prompts.calls());
	}

	@Test
	public void withTheSettingOnFocusingTheSearchOpensThePromptAndTheMapTakesNoKeys()
	{
		chatOn = true;
		startSearch();
		assertTrue(prompts.isOpen());
		assertEquals(1, prompts.opens);
		assertEquals("Search the map", prompts.title());
		assertEquals("", prompts.initialTexts.get(0));

		KeyEvent typed = typeDirect('a');
		assertFalse("the chat area takes the letter, not the map", typed.isConsumed());
		KeyEvent pressed = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_A, 'a');
		surface.getInput().keyPressed(pressed);
		assertFalse(pressed.isConsumed());
		frame();
		assertEquals("", surface.searchBox().getText());
		assertEquals(1, prompts.opens);
	}

	@Test
	public void clickingTheFieldOpensThePromptWithTheTextAlreadyTyped()
	{
		chatOn = true;
		startSearch();
		prompts.type("bank");
		frame();
		prompts.escape();
		frame();
		assertFalse(surface.searchBox().isFocused());
		assertEquals("the text stays for next time", "bank", surface.searchBox().getText());

		Rectangle field = new Rectangle(BOUNDS.x + BOUNDS.width / 2 - 20, BOUNDS.y + 10 + SearchBar.FIELD_HEIGHT / 2, 40, 4);
		surface.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, field.x, field.y));
		now += 16;
		surface.getInput().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, field.x, field.y));
		frame();
		assertTrue(surface.searchBox().isFocused());
		assertTrue(prompts.isOpen());
		assertEquals("bank", prompts.initialTexts.get(1));
	}

	@Test
	public void typingInThePromptIsMirroredLiveAndAnythingTypedOnTheMapAsWellIsNotCountedTwice()
	{
		chatOn = true;
		startSearch();
		typeDirect('b');
		prompts.type("bob's brilliant");
		frame();
		assertEquals("bob's brilliant", surface.searchBox().getText());
		assertFalse(surface.searchBox().getResults().isEmpty());
		assertEquals(SearchKind.SHOP, surface.searchBox().getResults().get(0).getKind());

		prompts.set("bob");
		frame();
		assertEquals("results follow each change", "bob", surface.searchBox().getText());
		prompts.set("");
		frame();
		assertEquals("", surface.searchBox().getText());
		assertTrue(prompts.isOpen());
	}

	@Test
	public void aLongPromptAndControlCharactersAreKeptWithinWhatTheSearchAccepts()
	{
		chatOn = true;
		startSearch();
		StringBuilder long80 = new StringBuilder("a\tb");
		while (long80.length() < 80)
		{
			long80.append('c');
		}
		prompts.set(long80.toString());
		frame();
		assertEquals(com.worldmapplus.search.SearchBox.MAX_LENGTH, surface.searchBox().getText().length());
		assertFalse(surface.searchBox().getText().contains("\t"));
	}

	@Test
	public void enterInThePromptGoesToTheSelectedResultAndEndsTheSearch()
	{
		chatOn = true;
		startSearch();
		prompts.type("bob's brilliant");
		frame();
		prompts.enter();
		frames(80);
		assertNotNull(surface.found());
		assertFalse(surface.searchBox().isFocused());
		assertFalse(prompts.isOpen());
		assertEquals("the chat area closed itself, nothing was asked of it", 0, prompts.closes);
		assertEquals(1, prompts.opens);
	}

	@Test
	public void escapeInThePromptEndsTheSearchAndKeepsTheText()
	{
		chatOn = true;
		startSearch();
		prompts.type("bank");
		frame();
		prompts.escape();
		frame();
		assertFalse(surface.searchBox().isFocused());
		assertEquals("bank", surface.searchBox().getText());
		assertFalse(prompts.isOpen());
		assertEquals(0, prompts.closes);
		frames(5);
		assertEquals("it does not come back by itself", 1, prompts.opens);
	}

	@Test
	public void aClickAwayFromTheMapEndsTheSearchAndClosesThePromptButAClickInThePromptDoesNot()
	{
		chatOn = true;
		prompts.covered.add(new Rectangle(0, 700, 1200, 100));
		startSearch();
		prompts.type("bank");
		frame();

		surface.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 50, 750));
		frame();
		assertTrue("a click in the prompt moves the cursor, it is not leaving", surface.searchBox().isFocused());
		assertTrue(prompts.isOpen());

		surface.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 20, 20));
		frame();
		assertFalse(surface.searchBox().isFocused());
		assertFalse(prompts.isOpen());
		assertEquals(1, prompts.closes);
	}

	@Test
	public void aClickOnTheMapItselfEndsTheSearchAndClosesThePrompt()
	{
		chatOn = true;
		startSearch();
		int x = BOUNDS.x + BOUNDS.width / 2;
		int y = BOUNDS.y + BOUNDS.height - 60;
		surface.getInput().mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x, y));
		now += 16;
		surface.getInput().mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x, y));
		frame();
		assertFalse(surface.searchBox().isFocused());
		assertFalse(prompts.isOpen());
		assertEquals(1, prompts.closes);
	}

	@Test
	public void theMapClosingAndStoppingBothCloseThePrompt()
	{
		chatOn = true;
		startSearch();
		surface.hide();
		assertFalse("the map closed", prompts.isOpen());
		assertEquals(1, prompts.closes);

		frames(3);
		controlF(surface);
		frame();
		assertTrue(prompts.isOpen());
		surface.reset();
		assertFalse("the plugin stopped", prompts.isOpen());
		assertEquals(2, prompts.closes);
	}

	@Test
	public void aMapThatCannotBeDrawnForAMomentLetsGoOfThePromptAndTakesItBackWithTheTextWhenItReturns()
	{
		chatOn = true;
		startSearch();
		prompts.type("bank");
		frame();
		surface.suspend();
		assertFalse(prompts.isOpen());
		frame();
		assertTrue(surface.searchBox().isFocused());
		assertTrue(prompts.isOpen());
		assertEquals("bank", prompts.initialTexts.get(1));
	}

	@Test
	public void aMapThatStopsBeingInteractiveClosesThePrompt()
	{
		chatOn = true;
		startSearch();
		frame(surface, BOUNDS, options().interactive(false));
		assertFalse(prompts.isOpen());
		assertFalse(surface.searchBox().isFocused());
	}

	@Test
	public void anotherPluginsPromptEndsTheSearchAndIsNotClosedByUs()
	{
		chatOn = true;
		startSearch();
		prompts.type("bank");
		frame();
		prompts.foreignPromptOpens();
		frame();
		assertFalse(surface.searchBox().isFocused());
		assertEquals("Someone else", prompts.title());
		frames(5);
		controlF(surface);
		surface.hide();
		assertEquals("their prompt was never closed from here", "Someone else", prompts.title());
		assertEquals(0, prompts.closes);
	}

	@Test
	public void aMapTooNarrowForTheSearchBarNeverOpensThePrompt()
	{
		chatOn = true;
		Rectangle narrow = new Rectangle(100, 80, 300, 600);
		for (int i = 0; i < 10; i++)
		{
			frame(surface, narrow, options());
		}
		controlF(surface);
		frame(surface, narrow, options());
		assertFalse(surface.searchBox().isFocused());
		assertEquals(0, prompts.calls());
	}

	@Test
	public void theSettingIsOnUntilThePlayerTurnsItOff()
	{
		assertTrue(new WorldMapPlusConfig()
		{
		}.typeInChatArea());
	}

	@Test
	public void aPlayerWhoNeverTouchedTheSettingTypesInTheChatArea()
	{
		surface = newSurface(chat, new WorldMapPlusConfig()
		{
		});
		startSearch();
		assertTrue("the default opens the prompt", prompts.isOpen());
		assertFalse("and the map takes no keys", typeDirect('a').isConsumed());
	}

	@Test
	public void switchingTheSettingWhileSearchingMovesTheTypingBetweenThePromptAndTheMapWithoutLosingAnything()
	{
		chatOn = false;
		startSearch();
		typeDirect('s');
		typeDirect('h');
		frame();
		assertEquals("sh", surface.searchBox().getText());

		chatOn = true;
		frame();
		assertTrue("the prompt opens with what was typed", prompts.isOpen());
		assertEquals("sh", prompts.initialTexts.get(0));
		assertFalse("the map no longer takes keys", typeDirect('x').isConsumed());
		prompts.set("shop");
		frame();
		assertEquals("shop", surface.searchBox().getText());

		chatOn = false;
		frame();
		assertFalse(prompts.isOpen());
		assertTrue("still searching", surface.searchBox().isFocused());
		assertTrue("the map takes keys again", typeDirect('s').isConsumed());
		frame();
		assertEquals("shops", surface.searchBox().getText());
	}

	@Test
	public void switchingTheSettingOverAndOverNeverLeavesTwoPromptsOrAStuckOne()
	{
		chatOn = false;
		startSearch();
		typeDirect('a');
		frame();
		for (int i = 0; i < 40; i++)
		{
			chatOn = i % 2 == 0;
			if (i % 5 == 0)
			{
				// Flip it more than once between two frames too.
				chatOn = !chatOn;
				chatOn = !chatOn;
			}
			frame();
			assertEquals("a prompt exactly when the setting is on", chatOn, prompts.isOpen());
			assertEquals(chatOn ? 1 : 0, prompts.opens - prompts.closes);
			KeyEvent typed = typeDirect('z');
			assertEquals("one route takes the keys at a time", !chatOn, typed.isConsumed());
			frame();
		}
		assertEquals("a", surface.searchBox().getText().substring(0, 1));
		chatOn = false;
		frame();
		assertFalse(prompts.isOpen());
		assertEquals(prompts.opens, prompts.closes);
	}

	@Test
	public void keysTheMapQueuedBeforeTheSettingWasSwitchedOnAreNotApplied()
	{
		chatOn = false;
		startSearch();
		typeDirect('x');
		surface.getInput().keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_BACK_SPACE, '\b'));
		chatOn = true;
		frame();
		assertEquals("neither the letter nor the Backspace was applied", "", surface.searchBox().getText());
		assertTrue(prompts.isOpen());
		assertEquals("", prompts.initialTexts.get(0));
	}

	@Test
	public void controlFWhileThePromptIsAlreadyOpenLeavesOnePrompt()
	{
		chatOn = true;
		startSearch();
		controlF(surface);
		frames(3);
		assertTrue(prompts.isOpen());
		assertEquals(1, prompts.opens);
		assertEquals(0, prompts.closes);
	}
}
