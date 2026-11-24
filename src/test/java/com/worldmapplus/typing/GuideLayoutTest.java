package com.worldmapplus.typing;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Where the typing guide puts its letters and its card, with fake widths. */
public class GuideLayoutTest
{
	/** Every letter 6 pixels wide. */
	static final GuideLayout.Measure SIX = new GuideLayout.Measure()
	{
		@Override
		public int textWidth(String text)
		{
			return 6 * text.length();
		}

		@Override
		public int charWidth(char c)
		{
			return 6;
		}
	};

	/** Every letter 4 pixels wide, the small font. */
	static final GuideLayout.Measure FOUR = new GuideLayout.Measure()
	{
		@Override
		public int textWidth(String text)
		{
			return 4 * text.length();
		}

		@Override
		public int charWidth(char c)
		{
			return 4;
		}
	};

	/** A line as the reader would leave it, for a guide and a typed text, all letters 6 wide. */
	static ChatLine line(TypingGuide guide, String typed, int width)
	{
		TypingGuide.View view = new TypingGuide.View();
		guide.fill(view);
		ChatLine line = new ChatLine();
		line.usable = true;
		line.aligned = true;
		line.x = 7;
		line.y = 440;
		line.width = width;
		line.height = 20;
		line.textX = 61;
		line.baseline = 454;
		line.cursorWidth = 6;
		line.typedCount = typed.length();
		for (int i = 0; i <= typed.length(); i++)
		{
			line.typedEdges[i] = 6 * i;
		}
		line.messageCount = view.messageLength;
		for (int i = 0; i <= view.messageLength; i++)
		{
			line.messageEdges[i] = 6 * i;
		}
		line.generation = view.generation;
		line.messageVersion = view.messageVersion;
		return line;
	}

	private static TypingGuide.View view(TypingGuide guide)
	{
		TypingGuide.View view = new TypingGuide.View();
		guide.fill(view);
		return view;
	}

	@Test
	public void overTheLineTheGhostStartsAfterTheTypedTextAndTheCursor()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		guide.update(guide.generation(), "!wmp");
		ChatLine line = line(guide, "!wmp", 506);
		GuideLayout layout = new GuideLayout();
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "4 of 15 right");
		assertEquals(GuideLayout.Mode.OVER, layout.mode);
		assertEquals(61, layout.typedX[0]);
		assertEquals(61 + 18, layout.typedX[3]);
		assertEquals(4, layout.ghostFrom);
		assertEquals("the next letter goes after the cursor and a small gap", 61 + 24 + 6 + GuideLayout.CURSOR_GAP, layout.messageX[4]);
		assertEquals(61 + 24 + 6 + GuideLayout.CURSOR_GAP + 6, layout.messageX[5]);
		assertFalse("the whole message fits on the line", layout.strip);
		assertEquals(454, layout.baseline);
	}

	@Test
	public void theCardSitsAboveTheLineAtItsRightEdgeAndOnlyStopsASingleMessage()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		ChatLine line = line(guide, "", 506);
		GuideLayout layout = new GuideLayout();
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "Type the line shown");
		assertTrue(layout.cardY + GuideLayout.CARD_HEIGHT <= line.y);
		assertEquals(line.x + line.width - 2, layout.cardX + layout.cardWidth);
		assertFalse(layout.buttonShown[GuideLayout.BACK]);
		assertFalse(layout.buttonShown[GuideLayout.SKIP]);
		assertTrue(layout.buttonShown[GuideLayout.STOP]);
		int middle = layout.buttonY + GuideLayout.BUTTON_HEIGHT / 2;
		assertEquals(GuideLayout.STOP, layout.buttonAt(layout.buttonX[GuideLayout.STOP] + 2, middle));
		assertEquals(-1, layout.buttonAt(layout.labelX, middle));
		assertTrue(layout.onCard(layout.labelX, middle));
		assertFalse(layout.onCard(line.x + 2, line.y + 5));
	}

	@Test
	public void aCodeInPartsHasBackSkipAndStop()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Arrays.asList("!wmp c1aaaa", "!wmp c1bbbb"), 0);
		ChatLine line = line(guide, "", 506);
		GuideLayout layout = new GuideLayout();
		layout.compute(line, view(guide), false, SIX, FOUR, "Part 1 of 2", "Type the line shown");
		assertTrue(layout.buttonShown[GuideLayout.BACK] && layout.buttonShown[GuideLayout.SKIP]);
		assertTrue(layout.buttonX[GuideLayout.BACK] < layout.buttonX[GuideLayout.SKIP]);
		assertTrue(layout.buttonX[GuideLayout.SKIP] < layout.buttonX[GuideLayout.STOP]);
		assertTrue("the hint ends before the buttons", layout.hintX + FOUR.textWidth("Type the line shown") < layout.buttonX[GuideLayout.BACK]);
	}

	@Test
	public void aMessageTooLongForTheLineIsAlsoShownWholeAbove()
	{
		TypingGuide guide = new TypingGuide();
		StringBuilder code = new StringBuilder("!wmp c1");
		while (code.length() < 76)
		{
			code.append('a');
		}
		guide.start(Collections.singletonList(code.toString()), 0);
		ChatLine line = line(guide, "", 300);
		GuideLayout layout = new GuideLayout();
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "x");
		assertEquals(GuideLayout.Mode.OVER, layout.mode);
		assertTrue(layout.strip);
		assertTrue("too wide for the normal font, so the small one is used", layout.stripSmall);
		assertTrue(layout.stripY + GuideLayout.STRIP_HEIGHT <= line.y);
		assertTrue("the card is above the strip", layout.cardY + GuideLayout.CARD_HEIGHT <= layout.stripY);
		assertEquals(layout.stripCharX[0] + 4, layout.stripCharX[1]);
	}

	@Test
	public void theAboveSettingAndTheIdleLineUseTheStrip()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		ChatLine line = line(guide, "", 506);
		GuideLayout layout = new GuideLayout();
		layout.compute(line, view(guide), true, SIX, FOUR, "Share code", "x");
		assertEquals(GuideLayout.Mode.ABOVE, layout.mode);
		assertFalse(layout.stripSmall);
		line.idle = true;
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "x");
		assertEquals("over the invitation the letters would clash", GuideLayout.Mode.ABOVE, layout.mode);
		line.idle = false;
		line.aligned = false;
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "x");
		assertEquals(GuideLayout.Mode.ABOVE, layout.mode);
	}

	@Test
	public void aStaleOrMissingLineDrawsNothing()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		ChatLine line = line(guide, "", 506);
		GuideLayout layout = new GuideLayout();
		guide.start(Collections.singletonList("!wmp p11jb34j2v"), 0);
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "x");
		assertEquals("measured for the guide before the restart", GuideLayout.Mode.NONE, layout.mode);
		assertEquals(-1, layout.buttonAt(layout.buttonX[GuideLayout.STOP] + 1, layout.buttonY + 2));
		line = line(guide, "", 506);
		line.usable = false;
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "x");
		assertEquals(GuideLayout.Mode.NONE, layout.mode);
		guide.stop();
		layout.compute(line(guide, "", 506), view(guide), false, SIX, FOUR, "Share code", "x");
		assertEquals(GuideLayout.Mode.NONE, layout.mode);
	}

	@Test
	public void wrongAndExtraLettersKeepTheirPlaces()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p1"), 0);
		guide.update(guide.generation(), "!wmp p1xyz");
		ChatLine line = line(guide, "!wmp p1xyz", 506);
		GuideLayout layout = new GuideLayout();
		layout.compute(line, view(guide), false, SIX, FOUR, "Share code", "x");
		assertEquals(61 + 6 * 9, layout.typedX[9]);
		assertEquals("nothing is left to show as the ghost", 7, layout.ghostFrom);
	}
}
