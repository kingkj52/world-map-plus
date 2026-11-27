package com.worldmapplus.typing;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Draws the typing guide over a made up chat box, opaque and see-through, so its look can be checked without a client. The
 * game's own letters are drawn by the test with RuneLite's copy of the game font. Pictures are written only when
 * WORLDMAPPLUS_SHOTS names a folder; the checks run always.
 */
public class TypingGuideShotTest
{
	private static final int WIDTH = 540;
	private static final int HEIGHT = 190;
	private static final int CHAT_Y = 40;
	private static final int LINE_X = 7;
	private static final int LINE_Y = CHAT_Y + 118;
	private static final String NAME = "Jordan7";
	private static final Color OPAQUE = new Color(198, 180, 142);
	private static final Color SCENE = new Color(46, 58, 40);

	private static ChatLine line(boolean idle, int width)
	{
		ChatLine line = new ChatLine();
		line.usable = true;
		line.idle = idle;
		line.x = LINE_X;
		line.y = LINE_Y;
		line.width = width;
		line.height = 20;
		return line;
	}

	/** A frame: the chat box, the game's typed letters, and, when {@code overlay} is given, the guide. */
	private static BufferedImage frame(TypingGuide guide, String typed, boolean transparent, boolean idle,
		TypingGuideOverlay overlay)
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
		g.setFont(font);
		g.setColor(transparent ? Color.WHITE : Color.BLACK);
		g.drawString("Welcome to Old School RuneScape.", 9, CHAT_Y + 60);
		g.drawString("Friend: send me your calquat run", 9, CHAT_Y + 76);
		g.setColor(transparent ? new Color(255, 255, 255, 90) : new Color(128, 112, 80));
		g.fillRect(7, CHAT_Y + 117, 506, 1);
		guide.update(guide.generation(), typed);
		// The game's own line: the name, then the typed text and its cursor in blue.
		int baseline = LINE_Y + 15;
		String prefix = NAME + ": ";
		g.setColor(transparent ? Color.WHITE : Color.BLACK);
		g.drawString(prefix, LINE_X, baseline);
		g.setColor(transparent ? new Color(144, 144, 255) : Color.BLUE);
		g.drawString(idle ? "Press Enter to Chat..." : typed + "*", LINE_X + m.stringWidth(prefix), baseline);
		if (overlay != null)
		{
			overlay.render(g);
		}
		g.dispose();
		return image;
	}

	private static BufferedImage frame(TypingGuide guide, String typed, boolean transparent, boolean idle)
	{
		ChatLine line = line(idle, 506);
		return frame(guide, typed, transparent, idle, new TypingGuideOverlay(guide, () -> line, () -> true));
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
		BufferedImage typing = frame(guide, "!wmp p10k", false, false);
		assertTrue("right letters are green", count(typing, TypingGuideOverlay.RIGHT) > 20);
		write(typing, "guide-pin-opaque.png");

		BufferedImage typo = frame(guide, "!wmp p10kgxm8", false, false);
		assertTrue("a wrong letter is red", count(typo, TypingGuideOverlay.WRONG) > 5);
		write(typo, "guide-pin-typo.png");

		write(frame(guide, "!wmp p10kgkm86a", true, false), "guide-pin-match-transparent.png");

		guide.start(parts(), 0);
		guide.update(guide.generation(), parts().get(0));
		guide.update(guide.generation(), "");
		write(frame(guide, parts().get(1).substring(0, 30), false, false), "guide-part2-opaque.png");
		write(frame(guide, parts().get(1).substring(0, 12) + "x", true, false), "guide-part2-transparent.png");
		write(frame(guide, "", false, true), "guide-part2-idle.png");
		ChatLine narrow = line(false, 300);
		write(frame(guide, parts().get(1).substring(0, 20), false, false, new TypingGuideOverlay(guide, () -> narrow, () -> true)),
			"guide-part2-narrow.png");
	}

	/** The middle guide: everything is drawn above the chat line, and the player's own letters are left as the game drew them. */
	@Test
	public void nothingIsDrawnOnTheLineItself()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(parts(), 1);
		String typed = parts().get(1).substring(0, 20) + "x";
		BufferedImage plain = frame(guide, typed, false, false, null);
		BufferedImage guided = frame(guide, typed, false, false);
		int changedAbove = 0;
		for (int y = 0; y < HEIGHT; y++)
		{
			for (int x = 0; x < WIDTH; x++)
			{
				boolean changed = plain.getRGB(x, y) != guided.getRGB(x, y);
				if (y >= LINE_Y)
				{
					assertFalse("the line is left alone at " + x + ", " + y, changed);
				}
				changedAbove += changed ? 1 : 0;
			}
		}
		assertTrue(changedAbove > 1000);
		// The strip is the row just above the line, the card above the strip.
		int stripTop = LINE_Y - TypingGuideOverlay.STRIP_HEIGHT - 1;
		assertTrue(count(rows(guided, stripTop, LINE_Y), TypingGuideOverlay.RIGHT) > 20);
		assertTrue(count(rows(guided, stripTop - TypingGuideOverlay.CARD_HEIGHT - 2, stripTop), TypingGuideOverlay.BUTTON) > 100);
	}

	private static BufferedImage rows(BufferedImage image, int from, int to)
	{
		return image.getSubimage(0, from, image.getWidth(), to - from);
	}

	@Test
	public void theCardsButtonsStepAndStopTheGuideAndTakeOnlyPressesOnTheCard()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(parts(), 1);
		ChatLine line = line(false, 506);
		TypingGuideOverlay overlay = new TypingGuideOverlay(guide, () -> line, () -> true);
		frame(guide, "", false, false, overlay);
		FontMetrics small = new Canvas().getFontMetrics(FontManager.getRunescapeSmallFont());
		int y = LINE_Y - TypingGuideOverlay.STRIP_HEIGHT - 1 - TypingGuideOverlay.CARD_HEIGHT - 2 + TypingGuideOverlay.CARD_HEIGHT / 2;
		int stopRight = LINE_X + 506 - 2 - TypingGuideOverlay.PAD;
		int skipRight = stopRight - small.stringWidth("Stop") - 12 - 4;
		int backRight = skipRight - small.stringWidth("Skip") - 12 - 4;
		assertTrue(press(overlay, skipRight - 3, y).isConsumed());
		assertEquals(2, guide.current());
		assertTrue(press(overlay, backRight - 3, y).isConsumed());
		assertEquals(1, guide.current());
		assertFalse("a press on the chat is left to the game", press(overlay, LINE_X + 20, LINE_Y + 5).isConsumed());
		assertTrue("a press on the card's text is taken", press(overlay, backRight - 80, y).isConsumed());
		assertTrue(guide.isActive());
		press(overlay, stopRight - 3, y);
		assertFalse(guide.isActive());
		frame(guide, "", false, false, overlay);
		assertFalse("a stopped guide's card takes nothing", press(overlay, stopRight - 3, y).isConsumed());
	}

	private static MouseEvent press(TypingGuideOverlay overlay, int x, int y)
	{
		MouseEvent e = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1);
		return overlay.getInput().mousePressed(e);
	}

	@Test
	public void aStoppedGuideDrawsNothing()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList("!wmp p10kgkm86a"), 0);
		BufferedImage before = frame(guide, "", false, false);
		assertTrue(count(before, TypingGuideOverlay.NEXT) > 0);
		guide.stop();
		BufferedImage after = frame(guide, "", false, false);
		assertEquals(0, count(after, TypingGuideOverlay.NEXT));
		assertEquals(0, count(after, TypingGuideOverlay.CARD_EDGE));
	}

	@Test
	public void aFrameOfTheGuideMakesNothingOfItsOwn()
	{
		Assume.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		TypingGuide guide = new TypingGuide();
		guide.start(parts(), 1);
		guide.update(guide.generation(), parts().get(1).substring(0, 20));
		ChatLine line = line(false, 506);
		TypingGuideOverlay overlay = new TypingGuideOverlay(guide, () -> line, () -> true);
		TypingGuide.View view = new TypingGuide.View();
		guide.fill(view);
		long guided = madeBy(() -> overlay.render(graphics));
		long java2d = madeBy(() -> sameDrawing(graphics, view));
		// Java2D makes a little for each font, clip and string it is given, the same for the guide as for the same drawing done
		// plainly; what the guide makes of its own on top of that is nothing per frame.
		assertTrue("a thousand frames made " + guided + " bytes, the same drawing " + java2d, guided < java2d + 64_000);
	}

	private static final Graphics2D graphics = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB).createGraphics();

	/** Bytes a thousand runs make, after three thousand to warm up. */
	private static long madeBy(Runnable frame)
	{
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		for (int i = 0; i < 3000; i++)
		{
			frame.run();
		}
		long thread = Thread.currentThread().getId();
		long before = threads.getThreadAllocatedBytes(thread);
		for (int i = 0; i < 1000; i++)
		{
			frame.run();
		}
		return threads.getThreadAllocatedBytes(thread) - before;
	}

	/** The Java2D calls a frame of the guide makes, with nothing of the guide's own. */
	private static void sameDrawing(Graphics2D g, TypingGuide.View view)
	{
		Font font = FontManager.getRunescapeFont();
		Font small = FontManager.getRunescapeSmallFont();
		Object antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		java.awt.Shape clip = g.getClip();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(Color.BLACK);
		g.fillRoundRect(9, 138, 400, 19, 6, 6);
		g.drawRoundRect(9, 138, 400, 19, 6, 6);
		g.setFont(font);
		g.clipRect(9, 138, 400, 19);
		for (int j = 0; j < view.messageLength; j++)
		{
			// The guide's colours, see-through ones among them, which Java2D blends.
			if (j < 20)
			{
				g.setColor(TypingGuideOverlay.RIGHT_BACK);
				g.fillRect(9 + 5 * j, 140, 5, 15);
			}
			g.setColor(j < 20 ? TypingGuideOverlay.RIGHT : TypingGuideOverlay.GHOST);
			g.drawChars(view.message, j, 1, 9 + 5 * j, 151);
		}
		g.setClip(clip);
		g.fillRoundRect(200, 118, 300, 18, 8, 8);
		g.drawRoundRect(200, 118, 300, 18, 8, 8);
		g.clipRect(200, 118, 300, 18);
		g.setFont(small);
		g.drawString("Part 2 of 3", 206, 131);
		g.drawString("20 of 76 right", 260, 131);
		for (int b = 0; b < 3; b++)
		{
			g.fillRoundRect(400 + 30 * b, 120, 28, 14, 6, 6);
			g.drawRoundRect(400 + 30 * b, 120, 28, 14, 6, 6);
			g.drawString("Back", 406 + 30 * b, 131);
		}
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antialias);
		g.setClip(clip);
	}
}
