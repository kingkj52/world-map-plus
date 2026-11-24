package com.worldmapplus.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.event.KeyEvent;
import javax.swing.JPanel;
import org.junit.Before;
import org.junit.Test;

/** When the game counts as taking typed text, and which keys of this plugin that stops. */
public class GameTextEntryTest
{
	private static final JPanel SOURCE = new JPanel();

	/** The facts of a pretend game, and a count of how often the windows were looked at. */
	private static final class Fake implements GameTextEntry.Source
	{
		boolean loggedIn = true;
		boolean inputField;
		boolean prompt;
		boolean mapSearch;
		boolean chatTyping;
		boolean dialog;
		boolean window;
		boolean fail;
		int windowLooks;

		private boolean answer(boolean value)
		{
			if (fail)
			{
				throw new IllegalStateException("half made widget");
			}
			return value;
		}

		@Override
		public boolean loggedIn()
		{
			return loggedIn;
		}

		@Override
		public boolean inputFieldFocused()
		{
			return answer(inputField);
		}

		@Override
		public boolean chatPromptUp()
		{
			return answer(prompt);
		}

		@Override
		public boolean worldMapSearching()
		{
			return answer(mapSearch);
		}

		@Override
		public boolean chatMessageBeingTyped()
		{
			return answer(chatTyping);
		}

		@Override
		public boolean dialogUp()
		{
			return answer(dialog);
		}

		@Override
		public boolean windowTakesKeys()
		{
			windowLooks++;
			return answer(window);
		}
	}

	private Fake game;
	private GameTextEntry entry;

	@Before
	public void setUp()
	{
		game = new Fake();
		entry = new GameTextEntry(game);
	}

	private static KeyEvent press(int code, char c, int modifiers)
	{
		return new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, 1000, modifiers, code, c);
	}

	@Test
	public void anIdleGameBlocksNothing()
	{
		entry.onClientTick();
		assertFalse(entry.takesText());
		assertFalse(entry.takesKeys());
		assertFalse(entry.blocks(press(KeyEvent.VK_M, 'm', 0)));
		assertFalse(entry.blocks(press(KeyEvent.VK_M, 'm', KeyEvent.CTRL_DOWN_MASK)));
		assertFalse(entry.blocks(null));
	}

	@Test
	public void everySignalOfTypedTextBlocksEveryKeyIncludingChords()
	{
		for (int signal = 0; signal < 4; signal++)
		{
			game = new Fake();
			entry = new GameTextEntry(game);
			game.chatTyping = signal == 0;
			game.inputField = signal == 1;
			game.prompt = signal == 2;
			game.mapSearch = signal == 3;
			entry.onClientTick();
			assertTrue("signal " + signal, entry.takesText());
			assertTrue("a letter", entry.blocks(press(KeyEvent.VK_M, 'm', 0)));
			assertTrue("a chord", entry.blocks(press(KeyEvent.VK_M, 'm', KeyEvent.CTRL_DOWN_MASK)));
			assertTrue("a function key", entry.blocks(press(KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED, 0)));
		}
	}

	@Test
	public void aDialogOrAWindowThatTakesKeysBlocksOnlyTheKeysThatWouldType()
	{
		for (int signal = 0; signal < 2; signal++)
		{
			game = new Fake();
			entry = new GameTextEntry(game);
			game.dialog = signal == 0;
			game.window = signal == 1;
			entry.windowsChanged();
			entry.onClientTick();
			assertFalse(entry.takesText());
			assertTrue(entry.takesKeys());
			assertTrue("a letter would type", entry.blocks(press(KeyEvent.VK_M, 'm', 0)));
			assertTrue("a shifted letter would type", entry.blocks(press(KeyEvent.VK_M, 'M', KeyEvent.SHIFT_DOWN_MASK)));
			assertTrue("a number picks a choice", entry.blocks(press(KeyEvent.VK_1, '1', 0)));
			assertFalse("a chord types nothing", entry.blocks(press(KeyEvent.VK_M, 'm', KeyEvent.CTRL_DOWN_MASK)));
			assertFalse("nor does Alt", entry.blocks(press(KeyEvent.VK_M, 'm', KeyEvent.ALT_DOWN_MASK)));
			assertFalse("nor Meta", entry.blocks(press(KeyEvent.VK_M, 'm', KeyEvent.META_DOWN_MASK)));
			assertFalse("a function key has no character", entry.blocks(press(KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED, 0)));
		}
	}

	@Test
	public void theAnswerFollowsTheGameFromOneTickToTheNext()
	{
		game.chatTyping = true;
		entry.onClientTick();
		assertTrue(entry.blocks(press(KeyEvent.VK_M, 'm', 0)));
		game.chatTyping = false;
		assertTrue("not before the tick", entry.blocks(press(KeyEvent.VK_M, 'm', 0)));
		entry.onClientTick();
		assertFalse(entry.blocks(press(KeyEvent.VK_M, 'm', 0)));
	}

	@Test
	public void loggedOutTheGameTakesNothingAndNothingIsLookedAt()
	{
		game.loggedIn = false;
		game.chatTyping = true;
		game.inputField = true;
		game.window = true;
		for (int i = 0; i < 20; i++)
		{
			entry.onClientTick();
		}
		assertFalse(entry.takesText());
		assertFalse(entry.takesKeys());
		assertEquals(0, game.windowLooks);
	}

	@Test
	public void theOpenWindowsAreLookedAtEveryFewTicksAndWhenOneOpensOrCloses()
	{
		for (int i = 0; i < GameTextEntry.RESCAN_TICKS * 4; i++)
		{
			entry.onClientTick();
		}
		assertEquals("once every " + GameTextEntry.RESCAN_TICKS + " ticks", 4, game.windowLooks);
		game.window = true;
		entry.windowsChanged();
		entry.onClientTick();
		assertEquals(5, game.windowLooks);
		assertTrue("seen at once", entry.takesKeys());
		game.window = false;
		entry.windowsChanged();
		entry.onClientTick();
		assertTrue("for the tick after a window opens or closes keys are assumed to be taken", entry.takesKeys());
		entry.onClientTick();
		assertFalse("and then it is what the windows say", entry.takesKeys());
	}

	@Test
	public void aWidgetThatCannotBeReadYetCountsAsNotTyping()
	{
		game.fail = true;
		entry.onClientTick();
		entry.onClientTick();
		assertFalse(entry.takesText());
		assertFalse(entry.takesKeys());
		assertFalse(entry.blocks(press(KeyEvent.VK_M, 'm', 0)));
	}
}
