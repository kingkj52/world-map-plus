package com.worldmapplus.typing;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The reader of the chat input line, with a fake game: what it reads, and when it stands down. */
public class ChatLineReaderTest
{
	static class FakeLine implements ChatLineReader.Source
	{
		boolean loggedIn = true;
		boolean prompt;
		boolean shown = true;
		String typed = "";
		String text;
		int reads;
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

		@Override
		public String lineText()
		{
			return text != null ? text : "Jordan7: <col=0000ff>" + typed + "*</col>";
		}
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
		assertFalse(reader.line().usable);
	}

	@Test
	public void theLinesBoxIsReadAndTheTypedTextCompared()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		game.typed = "!wmp";
		reader.onClientTick();
		ChatLine line = reader.line();
		assertTrue(line.usable);
		assertEquals(7, line.x);
		assertEquals(440, line.y);
		assertEquals(506, line.width);
		assertEquals(20, line.height);
		TypingGuide.View view = new TypingGuide.View();
		guide.fill(view);
		assertEquals(4, view.right);
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
	public void aHalfMadeLineDrawsNothing()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		game.broken = true;
		reader.onClientTick();
		assertFalse(reader.line().usable);
		game.broken = false;
		reader.onClientTick();
		assertTrue(reader.line().usable);
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
		guide.stop();
		reader.onClientTick();
		assertFalse(reader.line().usable);
	}

	@Test
	public void theIdleInvitationIsNoticed()
	{
		FakeLine game = new FakeLine();
		TypingGuide guide = new TypingGuide();
		ChatLineReader reader = new ChatLineReader(game, guide);
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		reader.onClientTick();
		assertFalse(reader.line().idle);
		game.text = "Jordan7: <col=0000ff>Press Enter to Chat...</col>";
		reader.onClientTick();
		assertTrue(reader.line().idle);
	}
}
