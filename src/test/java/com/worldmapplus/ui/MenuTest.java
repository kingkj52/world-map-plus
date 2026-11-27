package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MenuTest
{
	private static List<ContextMenu.Item> items()
	{
		List<ContextMenu.Item> items = new ArrayList<>();
		items.add(new ContextMenu.Item(ContextMenu.Action.ROUTE, "Route"));
		items.add(new ContextMenu.Item(ContextMenu.Action.COPY_TILE, "Copy"));
		items.add(new ContextMenu.Item(ContextMenu.Action.ADD_PIN, "Pin"));
		return items;
	}

	@Test
	public void theMenuOpensAtThePointerAndHitTests()
	{
		ContextMenu menu = new ContextMenu();
		Rectangle limit = new Rectangle(0, 0, 800, 600);
		menu.open(items(), 100, 100, 120, limit, "subject");
		assertTrue(menu.isOpen());
		Rectangle b = menu.getBounds();
		assertEquals(100, b.x);
		assertEquals(100, b.y);
		assertEquals("subject", menu.getSubject());

		ContextMenu.Item first = menu.hit(110, b.y + ContextMenu.PADDING / 2 + 3);
		assertEquals(ContextMenu.Action.ROUTE, first.getAction());
		ContextMenu.Item third = menu.hit(110, b.y + ContextMenu.PADDING / 2 + 2 * ContextMenu.ROW_HEIGHT + 3);
		assertEquals(ContextMenu.Action.ADD_PIN, third.getAction());
		assertNull(menu.hit(5, 5));
		assertSame(first, menu.hit(110, b.y + ContextMenu.PADDING / 2 + 3));
	}

	@Test
	public void theMenuStaysInsideTheLimit()
	{
		ContextMenu menu = new ContextMenu();
		Rectangle limit = new Rectangle(50, 50, 400, 300);
		menu.open(items(), 440, 340, 200, limit, null);
		assertTrue(limit.contains(menu.getBounds()));
		menu.open(items(), -20, -20, 200, limit, null);
		assertTrue(limit.contains(menu.getBounds()));
	}

	@Test
	public void closingStopsHitTesting()
	{
		ContextMenu menu = new ContextMenu();
		menu.open(items(), 100, 100, 120, new Rectangle(0, 0, 800, 600), null);
		menu.close();
		assertFalse(menu.isOpen());
		assertNull(menu.hit(110, 110));
	}

	@Test
	public void anEmptyMenuDoesNotOpen()
	{
		ContextMenu menu = new ContextMenu();
		menu.open(new ArrayList<>(), 10, 10, 50, new Rectangle(0, 0, 100, 100), null);
		assertFalse(menu.isOpen());
	}
}
