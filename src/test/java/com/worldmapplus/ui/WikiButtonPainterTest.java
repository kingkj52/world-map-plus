package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.layers.HoverCard;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.Test;

/** Where the Wiki button sits on a card, that the card makes room for it, and that a card with no page has none. */
public class WikiButtonPainterTest
{
	private static final Rectangle LIMIT = new Rectangle(0, 0, 800, 600);

	private final BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);

	private HoverCardPainter paint(HoverCard card, Point pointer)
	{
		HoverCardPainter painter = new HoverCardPainter(null, null);
		Graphics2D g = image.createGraphics();
		painter.paint(g, card, 300, 200, LIMIT, false, pointer);
		g.dispose();
		return painter;
	}

	@Test
	public void aCardWithAPageHasAButtonInsideItsTopRightCorner()
	{
		HoverCard card = HoverCard.builder("Cook's Assistant").subtitle("Quest start").wiki("Cook's Assistant").build();
		HoverCardPainter painter = paint(card, null);
		Rectangle box = painter.lastBounds();
		Rectangle button = painter.lastButton();
		assertNotNull(button);
		assertTrue(box.contains(button));
		assertEquals("flush with the card's inner right edge", box.x + box.width - HoverCardPainter.PADDING, button.x + button.width);
		assertTrue("in the title row", button.y < box.y + HoverCardPainter.PADDING + 16);
	}

	@Test
	public void theTitleHasRoomBesideTheButtonSoTheyNeverOverlap()
	{
		HoverCard plain = HoverCard.builder("A rather long title for a card").build();
		HoverCard withButton = HoverCard.builder("A rather long title for a card").wiki("Zulrah").build();
		Graphics2D g = image.createGraphics();
		int plainWidth = HoverCardPainter.measure(g, plain, false).width;
		int widerWidth = HoverCardPainter.measure(g, withButton, false).width;
		g.dispose();
		assertTrue("the card is wider by the button", widerWidth >= plainWidth + HoverCardPainter.BUTTON_WIDTH);
	}

	@Test
	public void aCardWithNoPageOrAnUnusableTitleHasNoButton()
	{
		assertNull(paint(HoverCard.builder("Bank").build(), null).lastButton());
		assertNull("a title that makes no address", paint(HoverCard.builder("Bank").wiki("a#b").build(), null).lastButton());
		assertFalse(HoverCardPainter.hasButton(HoverCard.builder("Bank").wiki("").build()));
		assertTrue(HoverCardPainter.hasButton(HoverCard.builder("Zulrah").wiki("Zulrah").build()));
	}

	@Test
	public void theButtonLightsUnderThePointerAndNotOtherwise()
	{
		HoverCard card = HoverCard.builder("Zulrah").wiki("Zulrah").build();
		HoverCardPainter painter = paint(card, null);
		Rectangle button = painter.lastButton();
		int idle = image.getRGB(button.x + 2, button.y + 2);
		painter = paint(card, new Point(button.x + 3, button.y + 3));
		assertEquals(button, painter.lastButton());
		int lit = image.getRGB(button.x + 2, button.y + 2);
		assertFalse("the corner pixel changes colour when lit", idle == lit);
	}

	@Test
	public void theWikiTitleSurvivesTheCopiesACardGoes()
	{
		HoverCard card = HoverCard.builder("Zulrah").wiki("Zulrah").row("a", "b").build();
		assertEquals("Zulrah", card.withRow("c", "d").getWiki());
		assertEquals("Zulrah", card.toBuilder().row("e", "f").build().getWiki());
		assertNull(HoverCard.builder("Bank").build().getWiki());
	}
}
