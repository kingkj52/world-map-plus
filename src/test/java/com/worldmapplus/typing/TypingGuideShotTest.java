package com.worldmapplus.typing;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import net.runelite.client.ui.FontManager;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Draws the typing guide over a made up chat box, opaque and see-through, so its look can be checked without a client. The
 * game's own letters are drawn by the test with RuneLite's copy of the game font and measured with the same font, which is
 * what the reader does with the game's font in the client. Pictures are written only when WORLDMAPPLUS_SHOTS names a
 * folder; the checks run always.
 */
public class TypingGuideShotTest
{
	private static final int WIDTH = 540;
	private static final int HEIGHT = 190;
	private static final int CHAT_Y = 40;
	private static final String NAME = "Jordan7";
	private static final Color OPAQUE = new Color(198, 180, 142);
	private static final Color SCENE = new Color(46, 58, 40);

	/** A frame: the chat box, the game's typed letters, and the guide over them. */
	private static BufferedImage frame(TypingGuide guide, String typed, boolean transparent, boolean above, boolean idle)
	{
		return frame(guide, typed, transparent, above, idle, "*", 20);
	}

	/** A frame with the game's cursor drawn as {@code cursor} and an input line {@code height} tall. */
	private static BufferedImage frame(TypingGuide guide, String typed, boolean transparent, boolean above, boolean idle,
		String cursor, int height)
	{
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(SCENE);
		g.fillRect(0, 0, WIDTH, HEIGHT);
		Font font = FontManager.getRunescapeFont();
		FontMetrics m = g.getFontMetrics(font);
		if (!transparent)
		{
			g.setColor(OPAQUE);
			g.fillRect(0, CHAT_Y, 519, 142);
			g.setColor(new Color(120, 100, 70));
			g.drawRect(0, CHAT_Y, 518, 141);
		}
		// A few chat lines above, as the game would show them.
		g.setFont(font);
		g.setColor(transparent ? Color.WHITE : Color.BLACK);
		g.drawString("Welcome to Old School RuneScape.", 9, CHAT_Y + 60);
		g.drawString("Friend: send me your calquat run", 9, CHAT_Y + 76);
		g.setColor(transparent ? new Color(255, 255, 255, 90) : new Color(128, 112, 80));
		g.fillRect(7, CHAT_Y + 117, 506, 1);

		ChatLine line = new ChatLine();
		line.usable = true;
		line.aligned = true;
		line.idle = idle;
		line.x = 7;
		line.y = CHAT_Y + 118;
		line.width = 506;
		line.height = height;
		String prefix = NAME + ": ";
		line.textX = line.x + m.stringWidth(prefix);
		line.baseline = ChatLineReader.baseline(line.y, line.height, m.getAscent(), 1);
		line.cursorWidth = m.stringWidth(cursor);
		line.typedCount = typed.length();
		for (int i = 0; i <= typed.length(); i++)
		{
			line.typedEdges[i] = m.stringWidth(typed.substring(0, i));
		}
		TypingGuide.View view = new TypingGuide.View();
		guide.update(guide.generation(), typed);
		guide.fill(view);
		String message = new String(view.message, 0, view.messageLength);
		line.messageCount = message.length();
		for (int i = 0; i <= message.length(); i++)
		{
			line.messageEdges[i] = m.stringWidth(message.substring(0, i));
		}
		line.generation = view.generation;
		line.messageVersion = view.messageVersion;

		// The game's own line: the name, then the typed text and its cursor in blue.
		g.setColor(transparent ? Color.WHITE : Color.BLACK);
		g.drawString(prefix, line.x, line.baseline);
		g.setColor(transparent ? new Color(144, 144, 255) : Color.BLUE);
		g.drawString((idle ? "Press Enter to Chat..." : typed + cursor), line.textX, line.baseline);

		TypingGuideOverlay overlay = new TypingGuideOverlay(guide, () -> line, () -> true, () -> above);
		overlay.render(g);
		g.dispose();
		return image;
	}

	private static void write(BufferedImage image, String name) throws Exception
	{
		String folder = System.getenv("WORLDMAPPLUS_SHOTS");
		if (folder != null && !folder.isEmpty())
		{
			ImageIO.write(image, "png", new File(folder, name));
		}
	}

	private static int count(BufferedImage image, Color colour)
	{
		int found = 0;
		int want = colour.getRGB();
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				found += image.getRGB(x, y) == want ? 1 : 0;
			}
		}
		return found;
	}

	private static List<String> parts()
	{
		return Arrays.asList("!wmp c1322rlegbhgv362fd79sgah1pqc2y0fs71h141er6r0wm62sm66zgf7s9zc6m2eeb1x5j3",
			"!wmp c132a7r8y8mx63j35nqgffhgtc35edf31gr26kr9vcspe15q7x7gs1sxpc4gma8b1fq2k2r",
			"!wmp c132jc6mrw62cs5kzgth9ndw2c0cj71263ncrdmgxfe1wj5nngh319tc6sgbdq0sd3j5sz");
	}

	@Test
	public void drawsTheGuideInEveryState() throws Exception
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		BufferedImage typing = frame(guide, "!wmp p10k", false, false, false);
		assertTrue("right letters are green", count(typing, TypingGuideOverlay.RIGHT) > 20);
		write(typing, "guide-pin-opaque.png");

		BufferedImage typo = frame(guide, "!wmp p10kgxm8", false, false, false);
		assertTrue("a wrong letter is red", count(typo, TypingGuideOverlay.WRONG) > 5);
		write(typo, "guide-pin-typo.png");

		write(frame(guide, "!wmp p10kgkm86a", true, false, false), "guide-pin-match-transparent.png");

		guide.start(parts(), 0);
		guide.update(guide.generation(), parts().get(0));
		guide.update(guide.generation(), "");
		write(frame(guide, parts().get(1).substring(0, 30), false, false, false), "guide-part2-opaque.png");
		write(frame(guide, parts().get(1).substring(0, 30), true, false, false), "guide-part2-transparent.png");
		write(frame(guide, parts().get(1).substring(0, 12), false, true, false), "guide-part2-above.png");
		write(frame(guide, "", false, false, true), "guide-part2-idle.png");
	}

	/**
	 * The next letter is one with a tail below the line (j, q, y, g, p): the tail is drawn whole, the gold line under the
	 * letter is below the tail with a free row between them, and the ghost starts after the game's cursor, an underscore here.
	 */
	@Test
	public void lettersWithTailsAreNeverCutAndTheGhostStartsAfterTheCursor() throws Exception
	{
		Font font = FontManager.getRunescapeFont();
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		FontMetrics m = probe.createGraphics().getFontMetrics(font);
		String message = parts().get(1);
		TypingGuide guide = new TypingGuide();
		guide.start(parts(), 1);
		String[] names = {"guide-descender-j-opaque.png", "guide-descender-q-transparent.png", "guide-descender-y-tight.png",
			"guide-descender-g-opaque.png"};
		char[] letters = {'j', 'q', 'y', 'g'};
		int[] heights = {20, 20, 15, 17};
		for (int k = 0; k < letters.length; k++)
		{
			int typed = message.indexOf(letters[k]);
			assertTrue(typed > 0);
			String cursor = k % 2 == 0 ? "_" : "*";
			BufferedImage image = frame(guide, message.substring(0, typed), k == 1, false, false, cursor, heights[k]);
			write(image, names[k]);
			int lineY = CHAT_Y + 118;
			int baseline = ChatLineReader.baseline(lineY, heights[k], m.getAscent(), 1);
			int x = 7 + m.stringWidth(NAME + ": ") + m.stringWidth(message.substring(0, typed)) + m.stringWidth(cursor)
				+ GuideLayout.CURSOR_GAP;
			int w = m.charWidth(letters[k]);
			int lowestLetter = -1;
			int line = -1;
			for (int y = lineY; y < HEIGHT; y++)
			{
				int gold = 0;
				for (int px = x; px < x + w; px++)
				{
					gold += image.getRGB(px, y) == TypingGuideOverlay.NEXT.getRGB() ? 1 : 0;
				}
				if (gold == w)
				{
					line = y;
				}
				else if (gold > 0 && line < 0)
				{
					lowestLetter = y;
				}
			}
			assertTrue(letters[k] + ": the tail is drawn below the baseline (" + lowestLetter + ", " + baseline + ")",
				lowestLetter > baseline);
			assertTrue(letters[k] + ": the gold line is under the tail with a free row between them", line > lowestLetter + 1);
		}
	}

	@Test
	public void aStoppedGuideDrawsNothing()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		BufferedImage before = frame(guide, "", false, false, false);
		assertTrue(count(before, TypingGuideOverlay.NEXT) > 0);
		guide.stop();
		BufferedImage after = frame(guide, "", false, false, false);
		assertEquals(0, count(after, TypingGuideOverlay.NEXT));
		assertEquals(0, count(after, TypingGuideOverlay.CARD_EDGE));
	}

	@Test
	public void aFrameOfTheGuideMakesNothing()
	{
		Assume.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		TypingGuide guide = new TypingGuide();
		guide.start(parts(), 1);
		guide.update(guide.generation(), parts().get(1).substring(0, 20));
		TypingGuide.View view = new TypingGuide.View();
		GuideLayout layout = new GuideLayout();
		ChatLine line = GuideLayoutTest.line(guide, parts().get(1).substring(0, 20), 506);
		for (int i = 0; i < 20000; i++)
		{
			guide.fill(view);
			layout.compute(line, view, false, GuideLayoutTest.SIX, GuideLayoutTest.FOUR, "Part 2 of 3", "20 of 76 right");
		}
		long thread = Thread.currentThread().getId();
		long before = threads.getThreadAllocatedBytes(thread);
		for (int i = 0; i < 10000; i++)
		{
			guide.fill(view);
			layout.compute(line, view, false, GuideLayoutTest.SIX, GuideLayoutTest.FOUR, "Part 2 of 3", "20 of 76 right");
		}
		long made = threads.getThreadAllocatedBytes(thread) - before;
		assertTrue("ten thousand frames made " + made + " bytes", made < 4096);
	}
}
