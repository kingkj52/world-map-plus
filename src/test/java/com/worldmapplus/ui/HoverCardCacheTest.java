package com.worldmapplus.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Assume;
import org.junit.Test;

/** The hover card keeps what it worked out while the mouse rests, and draws the same pixels as when it works it out. */
public class HoverCardCacheTest
{
	private static final Rectangle LIMIT = new Rectangle(0, 0, 800, 600);

	private static HoverCard card(String title, String value)
	{
		return HoverCard.builder(title).subtitle("Sub").row("Row one", value).row("Row two", "2").hint("Click to go there").build();
	}

	private static BufferedImage canvas()
	{
		return new BufferedImage(LIMIT.width, LIMIT.height, BufferedImage.TYPE_INT_ARGB);
	}

	private static Graphics2D graphics(BufferedImage image)
	{
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}

	private static int[] pixels(BufferedImage image)
	{
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	@Test
	public void sameContentComparesWhatTheCardShows()
	{
		assertTrue(card("A", "1").sameContent(card("A", "1")));
		assertFalse(card("A", "1").sameContent(card("A", "2")));
		assertFalse(card("A", "1").sameContent(card("B", "1")));
		assertFalse(card("A", "1").sameContent(card("A", "1").withRow("More", "x")));
		assertFalse(card("A", "1").sameContent(null));
		assertFalse(HoverCard.builder("A").preview(3, 10, 10).build().sameContent(HoverCard.builder("A").preview(3, 11, 10).build()));
		assertFalse(card("A", "1").sameContent(card("A", "1").withWikiSearch("A")));
	}

	@Test
	public void aCardRebuiltWithTheSameContentIsPaintedAsBefore()
	{
		BufferedImage one = canvas();
		BufferedImage two = canvas();
		BufferedImage three = canvas();
		HoverCardPainter painter = new HoverCardPainter(null, null);
		Graphics2D g = graphics(one);
		painter.paint(g, card("A card", "1"), 300, 200, LIMIT, false, new Point(300, 200));
		Rectangle first = painter.lastBounds();
		g.dispose();
		g = graphics(two);
		painter.paint(g, card("A card", "1"), 300, 200, LIMIT, false, new Point(300, 200));
		g.dispose();
		assertEquals(first, painter.lastBounds());
		assertArrayEquals("a copy of the card looks the same", pixels(one), pixels(two));
		g = graphics(three);
		painter.paint(g, card("A longer card title than before", "12345678"), 300, 200, LIMIT, false, new Point(300, 200));
		g.dispose();
		assertNotEquals("other words make another box", first, painter.lastBounds());
	}

	@Test
	public void theBoxFollowsTheMouseAndFlipsAtTheEdge()
	{
		HoverCardPainter painter = new HoverCardPainter(null, null);
		Graphics2D g = graphics(canvas());
		HoverCard card = card("A card", "1");
		painter.paint(g, card, 100, 100, LIMIT, false, null);
		Rectangle near = painter.lastBounds();
		assertEquals(100 + HoverCardPainter.OFFSET, near.x);
		painter.paint(g, card, 790, 590, LIMIT, false, null);
		Rectangle far = painter.lastBounds();
		assertTrue(far.x + far.width <= LIMIT.width && far.y + far.height <= LIMIT.height);
		assertTrue(far.x < 790 && far.y < 590);
		assertEquals(near.width, far.width);
		g.dispose();
	}

	@Test
	public void aKeptPictureIsDrawnWithTheSamePixelsAsOneDrawnLive() throws Exception
	{
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		try
		{
			MapAssets assets = new MapAssets(new Gson(), executor);
			HoverCard picture = null;
			for (Hotspot h : assets.getHotspots().all())
			{
				if (picture == null && h.getCard().hasPreview() && h.getCard().hasPreviewFocus())
				{
					picture = h.getCard();
				}
			}
			Assume.assumeNotNull(picture);
			// Let the tiles of the picture load: draw until a painter has kept the picture.
			HoverCardPainter warm = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
			BufferedImage scratch = canvas();
			long end = System.currentTimeMillis() + 20_000;
			while (warm.picturesKept() == 0 && System.currentTimeMillis() < end)
			{
				Graphics2D g = graphics(scratch);
				warm.paint(g, picture, 300, 200, LIMIT, false, null);
				g.dispose();
				Thread.sleep(20);
			}
			Assume.assumeTrue("the tiles of the picture loaded", warm.picturesKept() > 0);

			HoverCardPainter fresh = new HoverCardPainter(assets.getCatalog(), assets.getRenderer());
			BufferedImage live = canvas();
			Graphics2D g = graphics(live);
			fresh.paint(g, picture, 300, 200, LIMIT, false, null);
			g.dispose();
			assertEquals("drawn live, then kept", 1, fresh.picturesKept());
			BufferedImage kept = canvas();
			g = graphics(kept);
			fresh.paint(g, picture, 300, 200, LIMIT, false, null);
			g.dispose();
			assertEquals(1, fresh.picturesKept());
			assertArrayEquals("the kept picture looks exactly as the live one", pixels(live), pixels(kept));
			// Held at another place the picture still follows the box.
			BufferedImage elsewhere = canvas();
			g = graphics(elsewhere);
			fresh.paint(g, picture, 500, 350, LIMIT, false, null);
			g.dispose();
			assertEquals(1, fresh.picturesKept());
		}
		finally
		{
			executor.shutdownNow();
		}
	}
}
