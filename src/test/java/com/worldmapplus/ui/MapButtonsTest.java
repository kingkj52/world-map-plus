package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import org.junit.Test;

public class MapButtonsTest
{
	private static final Rectangle MAP = new Rectangle(50, 40, 700, 500);

	@Test
	public void buttonsSitInsideTheBottomRightCorner()
	{
		MapButtons buttons = new MapButtons();
		buttons.setLineUp(true, false);
		buttons.layout(MAP);
		for (MapButtons.Button b : MapButtons.Button.values())
		{
			Rectangle r = buttons.boundsOf(b);
			assertTrue(MAP.contains(r));
			assertTrue(r.x > MAP.x + MAP.width / 2);
			assertTrue(r.y > MAP.y + MAP.height / 2);
		}
	}

	@Test
	public void theOrderIsZoomInZoomOutLocateFromTheTop()
	{
		MapButtons buttons = new MapButtons();
		buttons.layout(MAP);
		assertTrue(buttons.boundsOf(MapButtons.Button.ZOOM_IN).y < buttons.boundsOf(MapButtons.Button.ZOOM_OUT).y);
		assertTrue(buttons.boundsOf(MapButtons.Button.ZOOM_OUT).y < buttons.boundsOf(MapButtons.Button.LOCATE).y);
	}

	@Test
	public void hitTestingFindsTheRightButton()
	{
		MapButtons buttons = new MapButtons();
		buttons.setLineUp(true, false);
		buttons.layout(MAP);
		for (MapButtons.Button b : MapButtons.Button.values())
		{
			Rectangle r = buttons.boundsOf(b);
			assertEquals(b, buttons.hit(r.x + r.width / 2, r.y + r.height / 2));
		}
		assertNull(buttons.hit(MAP.x + 5, MAP.y + 5));
	}

	@Test
	public void buttonsDoNotOverlap()
	{
		MapButtons buttons = new MapButtons();
		buttons.setLineUp(true, false);
		buttons.layout(MAP);
		MapButtons.Button[] all = MapButtons.Button.values();
		for (int i = 0; i < all.length; i++)
		{
			for (int j = i + 1; j < all.length; j++)
			{
				assertTrue(!buttons.boundsOf(all[i]).intersects(buttons.boundsOf(all[j])));
			}
		}
	}

	@Test
	public void beforeLayoutNothingIsHit()
	{
		assertNull(new MapButtons().hit(100, 100));
	}

	@Test
	public void theLineUpButtonIsOnlyThereWhereItIsOffered()
	{
		MapButtons without = new MapButtons();
		without.layout(MAP);
		assertEquals(0, without.boundsOf(MapButtons.Button.LINE_UP).width);
		MapButtons with = new MapButtons();
		with.setLineUp(true, true);
		with.layout(MAP);
		assertTrue(with.boundsOf(MapButtons.Button.LINE_UP).width > 0);
		assertTrue("it sits between the layers and the zoom buttons",
			with.boundsOf(MapButtons.Button.LAYERS).y < with.boundsOf(MapButtons.Button.LINE_UP).y
				&& with.boundsOf(MapButtons.Button.LINE_UP).y < with.boundsOf(MapButtons.Button.ZOOM_IN).y);
		assertEquals("the zoom buttons stay where they were", without.boundsOf(MapButtons.Button.ZOOM_IN),
			with.boundsOf(MapButtons.Button.ZOOM_IN));
	}
}
