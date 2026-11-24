package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SearchBoxTest
{
	private static SearchBox box()
	{
		List<SearchEntry> entries = new ArrayList<>();
		entries.add(new SearchEntry(SearchKind.PLACE, "Lumbridge", null, 0, 3222, 3218));
		entries.add(new SearchEntry(SearchKind.SHOP, "Lumbridge General Store", null, 0, 3211, 3247));
		entries.add(new SearchEntry(SearchKind.PLACE, "Varrock", null, 0, 3212, 3428));
		SearchBox box = new SearchBox();
		box.setIndex(new SearchIndex(entries));
		return box;
	}

	private static void typeText(SearchBox box, String text)
	{
		for (char c : text.toCharArray())
		{
			box.type(c);
		}
	}

	private static SearchBox manyResults(int count)
	{
		List<SearchEntry> entries = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			entries.add(new SearchEntry(SearchKind.SHOP, "Shop " + (1000 + i), null, 0, 3000 + i * 9, 3000));
		}
		SearchBox box = new SearchBox();
		box.setIndex(new SearchIndex(entries));
		box.focus();
		typeText(box, "shop");
		return box;
	}

	@Test
	public void moreResultsThanFitAreKeptAndScrolledThrough()
	{
		SearchBox box = manyResults(60);
		assertEquals(60, box.getResults().size());
		box.setVisibleRows(8);
		assertEquals(0, box.getScrollTop());
		box.scroll(3);
		assertEquals(3, box.getScrollTop());
		box.scroll(500);
		assertEquals("stops at the last full page", 52, box.getScrollTop());
		box.scroll(-500);
		assertEquals(0, box.getScrollTop());
	}

	@Test
	public void theScrollBarFractionJumpsToThatPartOfTheList()
	{
		SearchBox box = manyResults(60);
		box.setVisibleRows(10);
		box.scrollToFraction(0.5);
		assertEquals(25, box.getScrollTop());
		box.scrollToFraction(1.0);
		assertEquals(50, box.getScrollTop());
		box.scrollToFraction(-3);
		assertEquals(0, box.getScrollTop());
	}

	@Test
	public void movingTheSelectionKeepsItInView()
	{
		SearchBox box = manyResults(60);
		box.setVisibleRows(5);
		for (int i = 0; i < 7; i++)
		{
			box.moveSelection(1);
		}
		assertEquals(7, box.getSelected());
		assertTrue(box.getScrollTop() <= 7 && 7 < box.getScrollTop() + 5);
		for (int i = 0; i < 7; i++)
		{
			box.moveSelection(-1);
			assertTrue(box.getScrollTop() <= box.getSelected());
		}
		assertEquals(0, box.getSelected());
		box.moveSelection(-1);
		assertEquals("wraps to the end and shows it", 59, box.getSelected());
		assertTrue(box.getScrollTop() + 5 > 59);
	}

	@Test
	public void aNewSearchStartsAtTheTop()
	{
		SearchBox box = manyResults(60);
		box.setVisibleRows(5);
		box.scroll(20);
		box.backspace();
		assertEquals(0, box.getScrollTop());
	}

	@Test
	public void aTypedTileComesFirstAndNamesItsMap()
	{
		SearchBox box = box();
		box.setTileMap((x, y) -> x < 4000 ? 0 : -1);
		box.focus();
		typeText(box, "3222, 3218");
		SearchEntry first = box.selectedEntry();
		assertEquals(SearchKind.TILE, first.getKind());
		assertEquals("Tile 3222, 3218", first.getName());
		assertEquals(0, first.getMapId());
		assertEquals(3222.5, first.getX(), 1e-9);
		assertEquals(3218.5, first.getY(), 1e-9);
	}

	@Test
	public void aTileOnNoMapIsNotOffered()
	{
		SearchBox box = box();
		box.setTileMap((x, y) -> -1);
		box.focus();
		typeText(box, "3222 3218");
		assertTrue(box.getResults().isEmpty());
	}

	@Test
	public void wordsAreNotTiles()
	{
		SearchBox box = box();
		box.setTileMap((x, y) -> 0);
		box.focus();
		typeText(box, "lumbridge");
		assertEquals(SearchKind.PLACE, box.selectedEntry().getKind());
	}

	@Test
	public void typingNeedsFocus()
	{
		SearchBox box = box();
		typeText(box, "lum");
		assertEquals("", box.getText());
		box.focus();
		typeText(box, "lum");
		assertEquals("lum", box.getText());
	}

	@Test
	public void resultsFollowTheText()
	{
		SearchBox box = box();
		box.focus();
		typeText(box, "lumbridge");
		assertEquals(2, box.getResults().size());
		assertEquals("Lumbridge", box.selectedEntry().getName());
		box.backspace();
		assertEquals("lumbridg", box.getText());
		// Two letters off the name: one letter off would still be found, as a slip.
		typeText(box, "zq");
		assertTrue(box.getResults().isEmpty());
		assertNull(box.selectedEntry());
	}

	@Test
	public void theSelectionWrapsAround()
	{
		SearchBox box = box();
		box.focus();
		typeText(box, "lumbridge");
		box.moveSelection(1);
		assertEquals("Lumbridge General Store", box.selectedEntry().getName());
		box.moveSelection(1);
		assertEquals("Lumbridge", box.selectedEntry().getName());
		box.moveSelection(-1);
		assertEquals("Lumbridge General Store", box.selectedEntry().getName());
	}

	@Test
	public void newTextResetsTheSelection()
	{
		SearchBox box = box();
		box.focus();
		typeText(box, "lumbridge");
		box.moveSelection(1);
		box.type(' ');
		assertEquals(0, box.getSelected());
	}

	@Test
	public void controlCharactersAndExcessAreIgnored()
	{
		SearchBox box = box();
		box.focus();
		box.type('\n');
		box.type('\t');
		assertEquals("", box.getText());
		for (int i = 0; i < SearchBox.MAX_LENGTH + 10; i++)
		{
			box.type('a');
		}
		assertEquals(SearchBox.MAX_LENGTH, box.getText().length());
	}

	@Test
	public void clearReportsWhetherItDidAnything()
	{
		SearchBox box = box();
		box.focus();
		assertFalse(box.clear());
		typeText(box, "x");
		assertTrue(box.clear());
		assertEquals("", box.getText());
	}

	@Test
	public void blurKeepsTheText()
	{
		SearchBox box = box();
		box.focus();
		typeText(box, "var");
		box.blur();
		assertFalse(box.isFocused());
		assertEquals("var", box.getText());
		assertEquals("Varrock", box.getResults().get(0).getName());
	}

	@Test
	public void selectingARowByMouseIsBoundsChecked()
	{
		SearchBox box = box();
		box.focus();
		typeText(box, "lumbridge");
		box.select(1);
		assertEquals(1, box.getSelected());
		box.select(7);
		assertEquals(1, box.getSelected());
		box.select(-1);
		assertEquals(1, box.getSelected());
	}

	@Test
	public void theListStaysWhereItWasScrolledWhenTheSearchIsWorkedOutAgain()
	{
		java.util.List<SearchEntry> many = new java.util.ArrayList<>();
		for (int i = 0; i < 80; i++)
		{
			many.add(new SearchEntry(SearchKind.SHOP, "Shop " + i, null, 0, 3000 + i, 3000));
		}
		SearchBox box = new SearchBox();
		box.setIndex(new SearchIndex(many));
		box.focus();
		for (char c : "shop".toCharArray())
		{
			box.type(c);
		}
		box.setVisibleRows(10);
		box.scroll(30);
		assertEquals(30, box.getScrollTop());
		// The index is rebuilt, as when a place is saved: the same search, the same place in the list.
		box.setIndex(new SearchIndex(many));
		assertEquals(30, box.getScrollTop());
		box.type('s');
		assertEquals("a new search starts at the top", 0, box.getScrollTop());
	}

	@Test
	public void selectAllLetsTheNextLetterReplaceTheText()
	{
		SearchBox box = new SearchBox();
		box.focus();
		for (char c : "iron arrow".toCharArray())
		{
			box.type(c);
		}
		box.selectAll();
		assertTrue(box.isAllSelected());
		box.type('x');
		assertEquals("x", box.getText());
		assertFalse(box.isAllSelected());
	}

	@Test
	public void selectAllThenBackspaceClearsAndNothingIsSelectedWhenEmpty()
	{
		SearchBox box = new SearchBox();
		box.focus();
		box.selectAll();
		assertFalse(box.isAllSelected());
		box.type('a');
		box.type('b');
		box.selectAll();
		box.backspace();
		assertEquals("", box.getText());
		assertFalse(box.isAllSelected());
		box.type('a');
		box.selectAll();
		box.blur();
		assertFalse(box.isAllSelected());
	}
}
