package com.worldmapplus.typing;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The reader of the chat input line, with a fake game: what it reads, when it stands down, and how it measures. */
public class ChatLineReaderTest
{
	/** A chat line whose letters are all 6 pixels wide, with a name of 7 letters. */
	static class FakeLine implements ChatLineReader.Source
	{
		boolean loggedIn = true;
		boolean prompt;
		boolean shown = true;
		String typed = "";
		String name = "Jordan7";
		int align;
		int reads;
		int measures;
		boolean broken;

		@Override
		public boolean loggedIn()
		{
			return loggedIn;
		}

		@Override
		public String typedText()
		{
			reads++;
			return typed;
		}

		@Override
		public boolean chatPromptUp()
		{
			return prompt;
		}

		@Override
		public boolean findLine()
		{
			if (broken)
			{
				throw new IllegalStateException("half made");
			}
			return shown;
		}

		@Override
		public Rectangle lineBounds()
		{
			return new Rectangle(7, 440, 506, 20);
		}

		String cursor = "*";

		@Override
		public String lineText()
		{
			return name + ": <col=0000ff>" + typed + cursor + "</col>";
		}

		@Override
		public int textWidth(String text)
		{
			measures++;
			// Letters are 6 wide and the game's underscore cursor 8, so the cursor's own width can be told apart.
			String plain = text.replaceAll("<[^>]*>", "");
			int underscores = plain.length() - plain.replace("_", "").length();
			return 6 * plain.length() + 2 * underscores;
		}

		@Override
		public int ascent()
		{
			return 12;
		}

		@Override
		public int xAlignment()
		{
			return align;
		}

		@Override
		public int yAlignment()
		{
			return 1;
		}
	}

	@Test
	public void theGamesCursorIsMeasuredAndItsBlinkDoesNotMoveTheGuide()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		game.typed = "!wmp p1";
		// The cursor is blinked off at the first read: the wider of the two cursor marks is kept free.
		game.cursor = "";
		reader.onClientTick();
		assertEquals(8, reader.line().cursorWidth);
		// An underscore cursor shown: its width, measured from the line, is kept.
		game.cursor = "_";
		reader.onClientTick();
		assertEquals(8, reader.line().cursorWidth);
		game.cursor = "";
		reader.onClientTick();
		assertEquals("blinking off does not move the ghost", 8, reader.line().cursorWidth);
		// Something that cannot be a cursor after the typed text is not taken for one.
		game.cursor = " (and a long note after the text)";
		reader.onClientTick();
		assertEquals(8, reader.line().cursorWidth);
		guide.stop();
		reader.onClientTick();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		game.cursor = "**";
		reader.onClientTick();
		assertEquals("a new guide measures again", 12, reader.line().cursorWidth);
	}

	@Test
	public void nothingIsReadWhileNoGuideRuns()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		for (int i = 0; i < 10; i++)
		{
			reader.onClientTick();
		}
		assertEquals(0, game.reads);
		assertEquals(0, game.measures);
		assertFalse(reader.line().usable);
	}

	@Test
	public void theLineIsMeasuredWithTheGamesFont()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		game.typed = "!wmp";
		reader.onClientTick();
		ChatLine line = reader.line();
		assertTrue(line.usable);
		assertTrue(line.aligned);
		assertEquals("after the name, the colon and the space", 7 + 6 * 9, line.textX);
		assertEquals(4, line.typedCount);
		assertEquals(24, line.typedEdges[4]);
		assertEquals(15, line.messageCount);
		assertEquals(90, line.messageEdges[15]);
		assertEquals(6, line.cursorWidth);
		assertEquals(ChatLineReader.baseline(440, 20, 12, 1), line.baseline);
		assertEquals(guide.generation(), line.generation);
		TypingGuide.View view = new TypingGuide.View();
		guide.fill(view);
		assertEquals(4, view.right);

		int measures = game.measures;
		reader.onClientTick();
		assertEquals("a tick in which nothing changed measures nothing", measures, game.measures);
	}

	@Test
	public void theGuideStandsDownForPromptsTheChatSearchAndAHiddenLine()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		boolean[] searching = {false};
		reader.setOtherInput(() -> searching[0]);
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		reader.onClientTick();
		assertTrue(reader.line().usable);

		game.prompt = true;
		game.typed = "500";
		reader.onClientTick();
		assertFalse("another plugin's prompt", reader.line().usable);
		game.prompt = false;
		searching[0] = true;
		reader.onClientTick();
		assertFalse("this plugin's chat search", reader.line().usable);
		searching[0] = false;
		game.shown = false;
		reader.onClientTick();
		assertFalse("the chat is hidden", reader.line().usable);
		game.shown = true;
		game.loggedIn = false;
		reader.onClientTick();
		assertFalse(reader.line().usable);
		assertEquals("the typed text was read once, while the line was free", 1, game.reads);
	}

	@Test
	public void aHalfMadeLineDrawsNothingAndAnotherAlignmentIsMarked()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		game.broken = true;
		reader.onClientTick();
		assertFalse(reader.line().usable);
		game.broken = false;
		game.align = 1;
		reader.onClientTick();
		assertTrue(reader.line().usable);
		assertFalse("letters cannot be put on a centred line", reader.line().aligned);
	}

	@Test
	public void sendingAPartMovesTheGuideOnThroughTheReader()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		guide.start(Arrays.asList("!wmp c1aaaa", "!wmp c1bbbb"), 0);
		game.typed = "!wmp c1aaaa";
		reader.onClientTick();
		game.typed = "";
		reader.onClientTick();
		assertEquals(1, guide.current());
		assertEquals("the second message is measured", 66, reader.line().messageEdges[11]);
		guide.stop();
		reader.onClientTick();
		assertFalse(reader.line().usable);
		assertEquals(-1, reader.line().generation);
	}

	@Test
	public void theIdleInvitationIsNoticed()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide)
		{
		};
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		game.name = "Jordan7";
		game.typed = "";
		FakeLine idle = new FakeLine()
		{
			@Override
			public String lineText()
			{
				return "Jordan7: <col=0000ff>Press Enter to Chat...</col>";
			}
		};
		ChatLineReader idleReader = new ChatLineReader(idle, guide);
		idleReader.onClientTick();
		assertTrue(idleReader.line().idle);
		reader.onClientTick();
		assertFalse(reader.line().idle);
	}

	@Test
	public void baselinesFollowTheLinesAlignment()
	{
		assertEquals(452, ChatLineReader.baseline(440, 20, 12, 0));
		assertEquals(457, ChatLineReader.baseline(440, 20, 12, 2));
		assertEquals(440 + (20 - 12 - 3) / 2 + 12, ChatLineReader.baseline(440, 20, 12, 1));
	}
}
