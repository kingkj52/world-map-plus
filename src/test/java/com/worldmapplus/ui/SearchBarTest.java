package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import org.junit.Test;

public class SearchBarTest
{
	private static final Rectangle MAP = new Rectangle(20, 30, 900, 600);

	@Test
	public void moreResultsThanRoomMakeAScrollBarThatCanBeHit()
	{
		SearchBar bar = new SearchBar();
		bar.layout(MAP, 150);
		assertTrue(bar.scrolls());
		assertTrue(bar.getVisibleRows() < 150);
		assertEquals(bar.getVisibleRows(), bar.getCapacity());
		Rectangle field = bar.getFieldRect();
		int top = field.y + field.height + 2;
		int right = field.x + field.width - 4;
		SearchBar.Hit hit = bar.hit(right, top + 1);
		assertTrue(hit.isScrollBar());
		assertEquals(0.0, hit.getScrollFraction(), 0.05);
		SearchBar.Hit low = bar.hit(right, top + bar.getVisibleRows() * SearchBar.ROW_HEIGHT - 2);
		assertTrue(low.getScrollFraction() > 0.95);
		SearchBar.Hit row = bar.hit(field.x + 20, top + 1);
		assertFalse(row.isScrollBar());
		assertEquals(0, row.getRow());
		assertTrue(bar.overResults(field.x + 20, top + 1));
		assertFalse(bar.overResults(field.x + 20, field.y));
	}

	@Test
	public void fewResultsNeedNoScrollBar()
	{
		SearchBar bar = new SearchBar();
		bar.layout(MAP, 3);
		assertFalse(bar.scrolls());
		Rectangle field = bar.getFieldRect();
		assertFalse(bar.hit(field.x + field.width - 4, field.y + field.height + 5).isScrollBar());
	}

	@Test
	public void theFieldIsCentredAtTheTop()
	{
		SearchBar bar = new SearchBar();
		bar.layout(MAP, 0);
		Rectangle field = bar.getFieldRect();
		assertEquals(MAP.x + (MAP.width - field.width) / 2, field.x);
		assertEquals(MAP.y + SearchBar.MARGIN, field.y);
		assertTrue(MAP.contains(field));
	}

	@Test
	public void resultsSitBelowTheFieldAndHitTestByRow()
	{
		SearchBar bar = new SearchBar();
		bar.layout(MAP, 5);
		Rectangle field = bar.getFieldRect();
		int firstRowY = field.y + field.height + 2;
		assertEquals(5, bar.getVisibleRows());
		assertEquals(SearchBar.Hit.NONE.getRow(), bar.hit(field.x + 5, field.y - 5).getRow());
		assertTrue(bar.hit(field.x + 5, field.y + 5).isField());
		assertEquals(0, bar.hit(field.x + 5, firstRowY + 3).getRow());
		assertEquals(3, bar.hit(field.x + 5, firstRowY + 3 * SearchBar.ROW_HEIGHT + 3).getRow());
		assertFalse(bar.hit(field.x + 5, firstRowY + 5 * SearchBar.ROW_HEIGHT + 3).isField());
		assertEquals(-1, bar.hit(field.x + 5, firstRowY + 5 * SearchBar.ROW_HEIGHT + 3).getRow());
	}

	@Test
	public void aShortMapShowsFewerRows()
	{
		SearchBar bar = new SearchBar();
		bar.layout(new Rectangle(0, 0, 900, 150), 10);
		assertTrue(bar.getVisibleRows() < 10);
		assertTrue(bar.getCoveredRect().height <= 150);
	}

	@Test
	public void aNarrowMapKeepsTheFieldInside()
	{
		SearchBar bar = new SearchBar();
		Rectangle narrow = new Rectangle(0, 0, 400, 500);
		bar.layout(narrow, 3);
		assertTrue(narrow.contains(bar.getFieldRect()));
	}

	@Test
	public void noResultsMeansTheCoveredAreaIsJustTheField()
	{
		SearchBar bar = new SearchBar();
		bar.layout(MAP, 0);
		assertEquals(bar.getFieldRect(), bar.getCoveredRect());
	}
}
