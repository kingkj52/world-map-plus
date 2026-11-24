package com.worldmapplus.search;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TileQueryTest
{
	@Test
	public void theUsualWaysOfWritingATileAreRead()
	{
		assertArrayEquals(new int[] {3222, 3218, 0}, TileQuery.parse("3222 3218"));
		assertArrayEquals(new int[] {3222, 3218, 0}, TileQuery.parse("3222,3218"));
		assertArrayEquals(new int[] {3222, 3218, 0}, TileQuery.parse("3222, 3218"));
		assertArrayEquals(new int[] {3222, 3218, 0}, TileQuery.parse("(3222, 3218)"));
		assertArrayEquals(new int[] {3222, 3218, 2}, TileQuery.parse("3222, 3218, 2"));
		assertArrayEquals(new int[] {3222, 3218, 1}, TileQuery.parse("  3222  3218  1  "));
		assertArrayEquals(new int[] {3040, 9820, 0}, TileQuery.parse("3040;9820"));
	}

	@Test
	public void otherTextIsNotATile()
	{
		assertNull(TileQuery.parse(null));
		assertNull(TileQuery.parse(""));
		assertNull(TileQuery.parse("lumbridge"));
		assertNull(TileQuery.parse("3222"));
		assertNull(TileQuery.parse("99 4"));
		assertNull(TileQuery.parse("3222 3218 7"));
		assertNull(TileQuery.parse("3222 3218 0 0"));
		assertNull(TileQuery.parse("bank 3222 3218"));
		assertNull(TileQuery.parse("99999 3218"));
		assertNull(TileQuery.parse("0050 3218"));
	}
}
