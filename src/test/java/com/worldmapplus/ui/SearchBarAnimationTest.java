package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import org.junit.Test;

/** The results of the search bar fold away when it is left alone and open again when the pointer comes back. */
public class SearchBarAnimationTest
{
	private static final Rectangle MAP = new Rectangle(0, 0, 900, 600);

	private static SearchBar bar()
	{
		SearchBar bar = new SearchBar();
		bar.layout(MAP, 8);
		return bar;
	}

	private static void run(SearchBar bar, boolean open, long from, long to)
	{
		for (long t = from; t <= to; t += 16)
		{
			bar.animate(open, t);
		}
	}

	@Test
	public void theResultsStartOpenAndFoldAwayWhenLeftAlone()
	{
		SearchBar bar = bar();
		int full = bar.getResultsRect().height;
		assertTrue(full > 0);
		bar.animate(false, 1000);
		run(bar, false, 1016, 1100);
		int partway = bar.getResultsRect().height;
		assertTrue("partway " + partway, partway > 0 && partway < full);
		run(bar, false, 1116, 1500);
		assertEquals(0, bar.getResultsRect().height);
		assertEquals("only the field is left to click", bar.getFieldRect(), bar.getCoveredRect());
		assertFalse(bar.overResults(bar.getFieldRect().x + 20, bar.getFieldRect().y + bar.getFieldRect().height + 10));
	}

	@Test
	public void thePointerOnTheFieldBringsThemBack()
	{
		SearchBar bar = bar();
		bar.animate(false, 0);
		run(bar, false, 16, 600);
		assertEquals(0, bar.getResultsRect().height);
		Rectangle field = bar.getFieldRect();
		assertTrue(bar.keepsOpen(field.x + 5, field.y + 5));
		assertFalse("where the rows will be does not reopen a folded list", bar.keepsOpen(field.x + 5, field.y + field.height + 20));
		run(bar, true, 616, 1200);
		assertTrue(bar.isOpen());
		assertTrue(bar.getResultsRect().height > 0);
		assertTrue("once open, the rows keep it open", bar.keepsOpen(field.x + 5, field.y + field.height + 20));
	}

	@Test
	public void aStallInTheGameDoesNotSkipTheAnimation()
	{
		SearchBar bar = bar();
		int full = bar.getResultsRect().height;
		bar.animate(false, 1);
		bar.animate(false, 10_000_000);
		int height = bar.getResultsRect().height;
		assertTrue("height " + height, height > 0 && height < full);
	}
}
