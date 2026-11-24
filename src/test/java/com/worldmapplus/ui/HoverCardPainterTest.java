package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class HoverCardPainterTest
{
	private static final Rectangle LIMIT = new Rectangle(100, 50, 800, 600);

	private static Graphics2D graphics()
	{
		return new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).createGraphics();
	}

	@Test
	public void aCardGrowsWithItsContent()
	{
		Graphics2D g = graphics();
		Dimension small = HoverCardPainter.measure(g, HoverCard.builder("Title").build(), false);
		Dimension rows = HoverCardPainter.measure(g,
			HoverCard.builder("Title").row("Bronze axe", "16 coins").row("Iron axe", "56 coins").build(), false);
		Dimension preview = HoverCardPainter.measure(g, HoverCard.builder("Title").preview(3).build(), false);
		assertTrue(rows.height > small.height);
		assertTrue(preview.height >= small.height + HoverCardPainter.PREVIEW_HEIGHT);
		assertTrue(preview.width >= HoverCardPainter.PREVIEW_WIDTH);
	}

	@Test
	public void aFocusedPreviewIsZoomedInOnTheDestination()
	{
		MapInfo map = new MapInfo(6, "Dwarven Mines", 2880, 9600, 3328, 9920, 3040, 9824);
		Rectangle frame = new Rectangle(10, 10, 240, 150);
		ViewTransform whole = HoverCardPainter.previewTransform(map, HoverCard.builder("A").preview(6).build(), frame);
		ViewTransform near = HoverCardPainter.previewTransform(map,
			HoverCard.builder("A").preview(6, 3019.5, 9850.5).build(), frame);
		assertTrue(near.getPixelsPerTile() >= HoverCardPainter.FOCUS_SCALE);
		assertTrue(near.getPixelsPerTile() > whole.getPixelsPerTile());
		assertEquals(frame.x + frame.width / 2.0, near.screenX(3019.5), 1e-6);
		assertEquals(frame.y + frame.height / 2.0, near.screenY(9850.5), 1e-6);
	}

	@Test
	public void aColumnNeverStartsOnAnIndentedRow()
	{
		java.util.List<HoverCard.Row> rows = new java.util.ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			rows.add(new HoverCard.Row("Way " + i, "free"));
			rows.add(new HoverCard.Row("   needs", "A quest"));
		}
		java.util.List<java.util.List<HoverCard.Row>> columns = HoverCardPainter.columns(rows);
		assertTrue(columns.size() > 1);
		int total = 0;
		for (java.util.List<HoverCard.Row> column : columns)
		{
			assertTrue(!column.get(0).getLabel().startsWith(" "));
			assertTrue(column.size() <= HoverCardPainter.MAX_COLUMN_ROWS + 1);
			total += column.size();
		}
		assertEquals(rows.size(), total);
	}

	@Test
	public void aLongRowIsWiderThanTheTitle()
	{
		Graphics2D g = graphics();
		Dimension plain = HoverCardPainter.measure(g, HoverCard.builder("A").build(), false);
		Dimension wide = HoverCardPainter.measure(g,
			HoverCard.builder("A").row("A fairly long label", "12,345 coins").build(), false);
		assertTrue(wide.width > plain.width);
	}

	@Test
	public void theCardSitsBesideThePointer()
	{
		Dimension size = new Dimension(200, 100);
		Point at = HoverCardPainter.place(size, 300, 200, LIMIT);
		assertEquals(300 + HoverCardPainter.OFFSET, at.x);
		assertEquals(200 + HoverCardPainter.OFFSET, at.y);
	}

	@Test
	public void theCardFlipsAtTheRightAndBottomEdges()
	{
		Dimension size = new Dimension(200, 100);
		Point at = HoverCardPainter.place(size, 850, 620, LIMIT);
		assertTrue(at.x + size.width <= LIMIT.x + LIMIT.width);
		assertTrue(at.y + size.height <= LIMIT.y + LIMIT.height);
		assertTrue(at.x < 850);
		assertTrue(at.y < 620);
	}

	@Test
	public void theCardNeverLeavesTheLimit()
	{
		Dimension size = new Dimension(700, 500);
		for (int mx = 90; mx < 910; mx += 80)
		{
			for (int my = 40; my < 660; my += 80)
			{
				Point at = HoverCardPainter.place(size, mx, my, LIMIT);
				assertTrue(at.x >= LIMIT.x && at.y >= LIMIT.y);
				assertTrue(at.x + size.width <= LIMIT.x + LIMIT.width);
				assertTrue(at.y + size.height <= LIMIT.y + LIMIT.height);
			}
		}
	}

	@Test
	public void longListsSplitIntoColumnsWhenExpanded()
	{
		java.util.List<HoverCard.Row> rows = new java.util.ArrayList<>();
		for (int i = 0; i < 50; i++)
		{
			rows.add(new HoverCard.Row("Item " + i, "10 coins"));
		}
		java.util.List<java.util.List<HoverCard.Row>> columns = HoverCardPainter.columns(rows);
		assertEquals(3, columns.size());
		int total = 0;
		for (java.util.List<HoverCard.Row> column : columns)
		{
			assertTrue(column.size() <= HoverCardPainter.MAX_COLUMN_ROWS);
			total += column.size();
		}
		assertEquals(50, total);
		assertEquals(1, HoverCardPainter.columns(rows.subList(0, 10)).size());
		assertTrue(HoverCardPainter.columns(new java.util.ArrayList<>()).isEmpty());
	}

	@Test
	public void anExpandedCardIsWiderButNoTaller()
	{
		HoverCard.Builder builder = HoverCard.builder("Big shop");
		for (int i = 0; i < 14; i++)
		{
			builder.row("Item " + i, "10 coins");
		}
		for (int i = 14; i < 60; i++)
		{
			builder.overflowRow("Item " + i, "10 coins");
		}
		HoverCard card = builder.build();
		Graphics2D g = graphics();
		Dimension collapsed = HoverCardPainter.measure(g, card, false);
		Dimension expanded = HoverCardPainter.measure(g, card, true);
		assertTrue(expanded.width > collapsed.width);
		assertTrue(expanded.height > collapsed.height);
		assertTrue(expanded.height < collapsed.height + 25 * 20);
	}
}
