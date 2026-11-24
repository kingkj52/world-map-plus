package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class FloorButtonsTest
{
	private static final Rectangle MAP = new Rectangle(50, 40, 700, 500);

	private static FloorButtons laidOut()
	{
		MapButtons buttons = new MapButtons();
		buttons.layout(MAP);
		FloorButtons floors = new FloorButtons();
		floors.setShown(true);
		floors.layout(buttons.boundsOf(MapButtons.Button.LAYERS));
		return floors;
	}

	@Test
	public void theTwoSitAboveTheLayersButtonInTheSameColumn()
	{
		MapButtons buttons = new MapButtons();
		buttons.layout(MAP);
		Rectangle layers = buttons.boundsOf(MapButtons.Button.LAYERS);
		FloorButtons floors = laidOut();
		Rectangle up = floors.boundsOf(FloorButtons.Button.UP);
		Rectangle down = floors.boundsOf(FloorButtons.Button.DOWN);
		assertEquals(layers.x, up.x);
		assertEquals(layers.x, down.x);
		assertTrue(up.y < down.y);
		assertTrue(down.y + down.height <= layers.y);
		assertTrue(MAP.contains(up));
		assertFalse(up.intersects(down));
		for (MapButtons.Button b : MapButtons.Button.values())
		{
			assertFalse(buttons.boundsOf(b).intersects(up));
			assertFalse(buttons.boundsOf(b).intersects(down));
		}
	}

	@Test
	public void hitTestingFindsEachButton()
	{
		FloorButtons floors = laidOut();
		for (FloorButtons.Button b : FloorButtons.Button.values())
		{
			Rectangle r = floors.boundsOf(b);
			assertEquals(b, floors.hit(r.x + r.width / 2, r.y + r.height / 2));
		}
		assertNull(floors.hit(MAP.x + 3, MAP.y + 3));
	}

	@Test
	public void hiddenButtonsAreNeverHit()
	{
		FloorButtons floors = laidOut();
		Rectangle r = floors.boundsOf(FloorButtons.Button.UP);
		floors.setShown(false);
		assertNull(floors.hit(r.x + 2, r.y + 2));
	}

	@Test
	public void beforeLayoutNothingIsHit()
	{
		FloorButtons floors = new FloorButtons();
		floors.setShown(true);
		assertNull(floors.hit(100, 100));
	}

	@Test
	public void floorsAreNamedFromTheGroundUp()
	{
		assertEquals("Ground floor", FloorButtons.name(0));
		assertEquals("1st floor", FloorButtons.name(1));
		assertEquals("2nd floor", FloorButtons.name(2));
		assertEquals("3rd floor", FloorButtons.name(3));
	}

	@Test
	public void paintingNeverFails()
	{
		FloorButtons floors = laidOut();
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		floors.paint(g, 0, 3, FloorButtons.Button.UP);
		floors.paint(g, 3, 3, FloorButtons.Button.DOWN);
		floors.setShown(false);
		floors.paint(g, 1, 3, null);
		g.dispose();
	}
}
