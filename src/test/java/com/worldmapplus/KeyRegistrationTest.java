package com.worldmapplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.worldmapplus.input.MapInput;
import com.worldmapplus.input.ViewerInput;
import com.worldmapplus.ui.HudOverlay;
import com.worldmapplus.ui.ViewerOverlay;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives synthetic key events through a real {@link KeyManager}, which offers each key to its listeners in the order they
 * registered and stops at the first that consumes it, the way the client does. The plugin's only route to the keyboard is that
 * manager, so these tests show what the search bar gets, what a plugin registered earlier or later sees, and that turning the
 * plugin on and off leaves nothing registered.
 */
public class KeyRegistrationTest
{
	private static final JPanel SOURCE = new JPanel();
	private static final Rectangle AREA = new Rectangle(0, 0, 400, 300);

	private KeyManager keyManager;
	private WorldMapPlusPlugin plugin;
	private ViewerInput viewer;
	private ViewerInput hud;
	private com.worldmapplus.input.GameTextEntry textEntry;
	private com.worldmapplus.ui.HudState hudState;
	private final GameFacts game = new GameFacts();

	/** What the pretend game is doing with the keyboard, set by a test and read when a client tick comes. */
	private static final class GameFacts implements com.worldmapplus.input.GameTextEntry.Source
	{
		boolean chatTyping;
		boolean dialog;

		@Override
		public boolean loggedIn()
		{
			return true;
		}

		@Override
		public boolean inputFieldFocused()
		{
			return false;
		}

		@Override
		public boolean chatPromptUp()
		{
			return false;
		}

		@Override
		public boolean worldMapSearching()
		{
			return false;
		}

		@Override
		public boolean chatMessageBeingTyped()
		{
			return chatTyping;
		}

		@Override
		public boolean dialogUp()
		{
			return dialog;
		}

		@Override
		public boolean windowTakesKeys()
		{
			return false;
		}
	}

	/** Stands in for any other plugin: counts what it is offered and consumes what the test tells it to. */
	private static final class Other implements KeyListener
	{
		final List<String> seen = new ArrayList<>();
		int consumeCode = -1;
		boolean remapWToUp;
		private boolean consumingTyped;

		@Override
		public void keyTyped(KeyEvent e)
		{
			seen.add("typed " + e.getKeyChar());
			if (consumingTyped || remapWToUp && e.getKeyChar() == 'w')
			{
				e.consume();
			}
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (remapWToUp && e.getKeyCode() == KeyEvent.VK_W)
			{
				e.setKeyCode(KeyEvent.VK_UP);
				e.setKeyChar(KeyEvent.CHAR_UNDEFINED);
			}
			seen.add("pressed " + e.getKeyCode());
			if (e.getKeyCode() == consumeCode)
			{
				// Like a hotkey listener: the press is taken, and so is the character it types.
				consumingTyped = true;
				e.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			consumingTyped = false;
			if (remapWToUp && e.getKeyCode() == KeyEvent.VK_W)
			{
				e.setKeyCode(KeyEvent.VK_UP);
				e.setKeyChar(KeyEvent.CHAR_UNDEFINED);
			}
			seen.add("released " + e.getKeyCode());
		}
	}

	@Before
	public void setUp() throws Exception
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) -> null);
		keyManager = Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				bind(Client.class).toInstance(client);
				bind(EventBus.class).toInstance(new EventBus());
			}
		}).getInstance(KeyManager.class);
		Injector injector = InjectionTest.injector(keyManager);
		plugin = injector.getInstance(WorldMapPlusPlugin.class);
		viewer = injector.getInstance(ViewerOverlay.class).getInput();
		hud = injector.getInstance(HudOverlay.class).getInput();
		textEntry = injector.getInstance(com.worldmapplus.input.GameTextEntry.class);
		textEntry.setSource(game);
		hudState = injector.getInstance(com.worldmapplus.ui.HudState.class);
	}

	/** Gives the plugin a setting for the show or hide key, as a player who rebinds it would. */
	private void bindToggleTo(net.runelite.client.config.Keybind key) throws Exception
	{
		Field config = WorldMapPlusPlugin.class.getDeclaredField("config");
		config.setAccessible(true);
		config.set(plugin, new WorldMapPlusConfig()
		{
			@Override
			public net.runelite.client.config.Keybind hudToggleKey()
			{
				return key;
			}
		});
	}

	private boolean pressed(int code, char c, int modifiers)
	{
		KeyEvent e = event(KeyEvent.KEY_PRESSED, code, c, modifiers);
		keyManager.processKeyPressed(e);
		KeyEvent typed = event(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c, modifiers);
		if (c != KeyEvent.CHAR_UNDEFINED)
		{
			keyManager.processKeyTyped(typed);
		}
		keyManager.processKeyReleased(event(KeyEvent.KEY_RELEASED, code, c, modifiers));
		return e.isConsumed();
	}

	private int listenerCount() throws Exception
	{
		for (Field field : KeyManager.class.getDeclaredFields())
		{
			if (List.class.isAssignableFrom(field.getType()))
			{
				field.setAccessible(true);
				return ((List<?>) field.get(keyManager)).size();
			}
		}
		throw new AssertionError("KeyManager keeps no list of listeners");
	}

	private static KeyEvent event(int id, int code, char c, int modifiers)
	{
		// The client's events carry the extended key code that a hotkey is matched on; a hand made event does not, so say it.
		return new KeyEvent(SOURCE, id, 1000, modifiers, code, c)
		{
			@Override
			public int getExtendedKeyCode()
			{
				return getKeyCode();
			}
		};
	}

	/** One whole keystroke as the client delivers it: pressed, typed (when it has a character), released. */
	private List<KeyEvent> stroke(int code, char c)
	{
		List<KeyEvent> events = new ArrayList<>();
		KeyEvent pressed = event(KeyEvent.KEY_PRESSED, code, c, 0);
		keyManager.processKeyPressed(pressed);
		events.add(pressed);
		if (c != KeyEvent.CHAR_UNDEFINED)
		{
			KeyEvent typed = event(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c, 0);
			keyManager.processKeyTyped(typed);
			events.add(typed);
		}
		KeyEvent released = event(KeyEvent.KEY_RELEASED, code, c, 0);
		keyManager.processKeyReleased(released);
		events.add(released);
		return events;
	}

	private static int typedCount(List<MapInput> events, char c)
	{
		int count = 0;
		for (MapInput event : events)
		{
			if (event.kind == MapInput.Kind.TYPED && event.character() == c)
			{
				count++;
			}
		}
		return count;
	}

	@Test
	public void startingAndStoppingRepeatedlyLeavesNothingRegistered() throws Exception
	{
		assertEquals(0, listenerCount());
		plugin.registerKeys();
		int registered = listenerCount();
		assertTrue("the two map inputs and the two hotkeys", registered >= 4);
		plugin.unregisterKeys();
		assertEquals(0, listenerCount());
		for (int i = 0; i < 25; i++)
		{
			plugin.registerKeys();
			assertEquals("a restart adds no more than the first start", registered, listenerCount());
			plugin.unregisterKeys();
			assertEquals("a stop takes back everything", 0, listenerCount());
		}
		// A failed start may stop twice or stop without having started. Neither may throw or take another plugin's listener.
		Other other = new Other();
		keyManager.registerKeyListener(other);
		plugin.unregisterKeys();
		plugin.unregisterKeys();
		assertEquals(1, listenerCount());
	}

	@Test
	public void afterAStopTheSearchBarsStateTakesNothing() throws Exception
	{
		plugin.registerKeys();
		viewer.setActive(AREA);
		viewer.setCaptureKeys(true);
		plugin.unregisterKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);
		for (KeyEvent e : stroke(KeyEvent.VK_W, 'w'))
		{
			assertFalse(e.isConsumed());
		}
		assertEquals(3, late.seen.size());
		assertTrue(viewer.getQueue().drain().isEmpty());
	}

	@Test
	public void aFocusedSearchBarGetsEachKeyOnceAndNothingBehindItSeesIt()
	{
		Other early = new Other();
		Other late = new Other();
		keyManager.registerKeyListener(early);
		plugin.registerKeys();
		keyManager.registerKeyListener(late);
		viewer.setActive(AREA);
		viewer.setCaptureKeys(true);

		for (KeyEvent e : stroke(KeyEvent.VK_W, 'w'))
		{
			assertTrue("taken, so the game does not also get it", e.isConsumed());
		}
		List<MapInput> events = viewer.getQueue().drain();
		assertEquals("delivered once, not once per route", 1, typedCount(events, 'w'));
		assertEquals(3, early.seen.size());
		assertTrue("a plugin registered after the bar is never offered what the bar took", late.seen.isEmpty());
		assertTrue("the other map's input is not involved", hud.getQueue().drain().isEmpty());
	}

	@Test
	public void keysAreNeverTakenWhenTheSearchBarDoesNotHaveTheKeyboard()
	{
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);

		// Map closed.
		for (KeyEvent e : stroke(KeyEvent.VK_W, 'w'))
		{
			assertFalse(e.isConsumed());
		}
		// Map showing, search bar not focused.
		viewer.setActive(AREA);
		for (KeyEvent e : stroke(KeyEvent.VK_W, 'w'))
		{
			assertFalse(e.isConsumed());
		}
		assertEquals(6, late.seen.size());
		assertTrue(viewer.getQueue().drain().isEmpty());
		assertTrue(hud.getQueue().drain().isEmpty());
	}

	@Test
	public void controlFStartsASearchOnlyWhileTheMapShows()
	{
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);

		KeyEvent closed = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(closed);
		assertFalse(closed.isConsumed());
		assertEquals(1, late.seen.size());

		viewer.setActive(AREA);
		KeyEvent open = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(open);
		assertTrue(open.isConsumed());
		assertEquals(1, late.seen.size());
		List<MapInput> events = viewer.getQueue().drain();
		assertEquals(1, events.size());
		assertEquals(ViewerInput.KEY_FOCUS_SEARCH, events.get(0).keyCode());
	}

	@Test
	public void controlFTypesNothingAtTheGameOrAPluginRegisteredAfterIt()
	{
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);
		viewer.setActive(AREA);

		// The client delivers a chord as pressed, typed (control F), released. Another key follows before any frame is drawn.
		KeyEvent controlDown = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(controlDown);
		KeyEvent chord = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(chord);
		KeyEvent typed = event(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '', KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyTyped(typed);
		assertTrue(chord.isConsumed());
		assertTrue("the control character of the chord did not leak", typed.isConsumed());
		for (KeyEvent e : stroke(KeyEvent.VK_B, 'b'))
		{
			assertTrue("a key before the next frame is taken too", e.isConsumed());
		}
		keyManager.processKeyReleased(event(KeyEvent.KEY_RELEASED, KeyEvent.VK_F, 'f', 0));
		assertEquals("only the control press was offered to the other plugin", 1, late.seen.size());
		assertEquals("pressed " + KeyEvent.VK_CONTROL, late.seen.get(0));
		assertEquals(1, typedCount(viewer.getQueue().drain(), 'b'));
	}

	@Test
	public void theShowOrHideKeyDoesNothingWhileAMessageIsBeingTypedAndWorksOtherwise() throws Exception
	{
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);

		game.chatTyping = true;
		textEntry.onClientTick();
		assertFalse("the chord is left to the game", pressed(KeyEvent.VK_M, '\r', KeyEvent.CTRL_DOWN_MASK));
		assertFalse(hudState.isVisible());
		assertFalse("the other plugin was offered it", late.seen.isEmpty());

		game.chatTyping = false;
		textEntry.onClientTick();
		assertTrue("taken once nothing is being typed", pressed(KeyEvent.VK_M, '\r', KeyEvent.CTRL_DOWN_MASK));
		assertTrue(hudState.isVisible());
	}

	@Test
	public void aShowOrHideKeyBoundToALetterNeverEatsALetterOfAMessage() throws Exception
	{
		bindToggleTo(new net.runelite.client.config.Keybind(KeyEvent.VK_M, 0));
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);

		game.chatTyping = true;
		textEntry.onClientTick();
		List<KeyEvent> typedM = stroke(KeyEvent.VK_M, 'm');
		for (KeyEvent e : typedM)
		{
			assertFalse("the m reaches the chat box", e.isConsumed());
		}
		assertFalse(hudState.isVisible());
		assertEquals("the later plugin saw the whole key", 3, late.seen.size());

		game.chatTyping = false;
		textEntry.onClientTick();
		for (KeyEvent e : stroke(KeyEvent.VK_M, 'm'))
		{
			assertTrue("with no message under way the letter is the hotkey's, as it was bound", e.isConsumed() || e.getID() == KeyEvent.KEY_RELEASED);
		}
		assertTrue(hudState.isVisible());
	}

	@Test
	public void aDialogLeavesTheLettersAloneButNotTheChord() throws Exception
	{
		bindToggleTo(new net.runelite.client.config.Keybind(KeyEvent.VK_M, 0));
		plugin.registerKeys();
		game.dialog = true;
		textEntry.onClientTick();
		assertFalse("a letter in a dialog is the dialog's", pressed(KeyEvent.VK_M, 'm', 0));
		assertFalse(hudState.isVisible());
		bindToggleTo(new net.runelite.client.config.Keybind(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK));
		assertTrue("a chord cannot type, so it works", pressed(KeyEvent.VK_M, '\r', KeyEvent.CTRL_DOWN_MASK));
		assertTrue(hudState.isVisible());
	}

	@Test
	public void theClickThroughKeyIsGuardedLikeTheShowOrHideKey()
	{
		plugin.registerKeys();
		boolean before = hudState.isClickThrough();
		game.chatTyping = true;
		textEntry.onClientTick();
		assertFalse(pressed(KeyEvent.VK_M, '\r', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		assertEquals(before, hudState.isClickThrough());
		game.chatTyping = false;
		textEntry.onClientTick();
		assertTrue(pressed(KeyEvent.VK_M, '\r', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK));
		assertEquals(!before, hudState.isClickThrough());
	}

	@Test
	public void controlFDoesNotStartASearchWhileAMessageIsBeingTyped()
	{
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);
		viewer.setActive(AREA);
		game.chatTyping = true;
		textEntry.onClientTick();
		KeyEvent chord = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(chord);
		assertFalse("the chord is the game's", chord.isConsumed());
		assertEquals(1, late.seen.size());
		assertTrue("no search was asked for", viewer.getQueue().drain().isEmpty());
		for (KeyEvent e : stroke(KeyEvent.VK_B, 'b'))
		{
			assertFalse("and no key is taken after it", e.isConsumed());
		}

		game.chatTyping = false;
		textEntry.onClientTick();
		KeyEvent again = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(again);
		assertTrue(again.isConsumed());
		assertEquals(ViewerInput.KEY_FOCUS_SEARCH, viewer.getQueue().drain().get(0).keyCode());
	}

	@Test
	public void aFocusedSearchBarStillGetsItsKeysWhileTheGameTakesText()
	{
		// The player chose the bar, and the bar takes every key it is given until Escape or a click away.
		plugin.registerKeys();
		viewer.setActive(AREA);
		viewer.setCaptureKeys(true);
		game.chatTyping = true;
		textEntry.onClientTick();
		for (KeyEvent e : stroke(KeyEvent.VK_W, 'w'))
		{
			assertTrue(e.isConsumed());
		}
		assertEquals(1, typedCount(viewer.getQueue().drain(), 'w'));
	}

	@Test
	public void aPluginRegisteredEarlierCanStillTakeAKeyAndTheBarThenNeverSeesIt()
	{
		// This is the cost of using the key manager alone: it has no priority, so the first listener to consume a key wins.
		Other early = new Other();
		early.consumeCode = KeyEvent.VK_W;
		keyManager.registerKeyListener(early);
		plugin.registerKeys();
		viewer.setActive(AREA);
		viewer.setCaptureKeys(true);

		stroke(KeyEvent.VK_W, 'w');
		assertTrue("the letter another plugin bound did not reach the bar", viewer.getQueue().drain().isEmpty());
		// Every other letter still does.
		stroke(KeyEvent.VK_Q, 'q');
		assertEquals(1, typedCount(viewer.getQueue().drain(), 'q'));
	}

	@Test
	public void aKeyAnotherPluginRemapsReachesTheBarAsWhatItWasRemappedTo()
	{
		Other remapper = new Other();
		remapper.remapWToUp = true;
		keyManager.registerKeyListener(remapper);
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);
		viewer.setActive(AREA);
		viewer.setCaptureKeys(true);

		List<KeyEvent> events = stroke(KeyEvent.VK_W, 'w');
		List<MapInput> queued = viewer.getQueue().drain();
		assertEquals("the letter itself was eaten by the remapper", 0, typedCount(queued, 'w'));
		assertEquals(1, queued.size());
		assertEquals(KeyEvent.VK_UP, queued.get(0).keyCode());
		assertTrue("the remapped press is the bar's, so the game does not move the camera", events.get(0).isConsumed());
		assertTrue(late.seen.isEmpty());
	}

	@Test
	public void noKeyIsLeftHeldInTheGameWhenASearchBeginsOrEnds()
	{
		plugin.registerKeys();
		Other late = new Other();
		keyManager.registerKeyListener(late);
		viewer.setActive(AREA);

		// Control is already down when Ctrl+F starts the search: the game saw its press, so it must see its release.
		KeyEvent controlDown = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(controlDown);
		assertFalse(controlDown.isConsumed());
		KeyEvent chord = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		keyManager.processKeyPressed(chord);
		assertTrue(chord.isConsumed());
		viewer.setCaptureKeys(true);
		KeyEvent controlUp = event(KeyEvent.KEY_RELEASED, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, 0);
		keyManager.processKeyReleased(controlUp);
		assertFalse("the game saw this key go down and must see it come up", controlUp.isConsumed());
		KeyEvent fUp = event(KeyEvent.KEY_RELEASED, KeyEvent.VK_F, 'f', 0);
		keyManager.processKeyReleased(fUp);
		assertTrue("the game never saw this key go down", fUp.isConsumed());

		// Escape ends the search. Its release arrives after the bar has let go of the keyboard and is still the bar's.
		KeyEvent escape = event(KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
		keyManager.processKeyPressed(escape);
		assertTrue(escape.isConsumed());
		viewer.setCaptureKeys(false);
		KeyEvent escapeUp = event(KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
		keyManager.processKeyReleased(escapeUp);
		assertTrue(escapeUp.isConsumed());

		// And afterwards everything goes to the game again.
		KeyEvent after = event(KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
		keyManager.processKeyReleased(after);
		assertFalse("only a release whose press was taken is taken", after.isConsumed());
	}

	@Test
	public void losingTheWindowFocusLetsGoOfHeldKeys()
	{
		plugin.registerKeys();
		viewer.setActive(AREA);
		viewer.setCaptureKeys(true);
		keyManager.processKeyPressed(event(KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.SHIFT_DOWN_MASK));
		keyManager.processKeyPressed(event(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a', 0));
		assertTrue(viewer.isExpandHeld());

		// The client tells every listener when its window loses focus; the release will never arrive.
		viewer.focusLost();
		assertFalse(viewer.isExpandHeld());
		KeyEvent late = event(KeyEvent.KEY_RELEASED, KeyEvent.VK_A, 'a', 0);
		keyManager.processKeyReleased(late);
		assertFalse("a release for a press nobody remembers is left for the game", late.isConsumed());
	}
}
