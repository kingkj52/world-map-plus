package com.worldmapplus.prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ViewMemoryTest
{
	@Test
	public void roundTrips()
	{
		String text = ViewMemory.format(12, 3168.25, 9632.5, 6.125);
		ViewMemory.Saved saved = ViewMemory.parse(text);
		assertNotNull(saved);
		assertEquals(12, saved.getMapId());
		assertEquals(3168.25, saved.getX(), 1e-9);
		assertEquals(9632.5, saved.getY(), 1e-9);
		assertEquals(6.125, saved.getPixelsPerTile(), 1e-9);
	}

	@Test
	public void rejectsNothingAndNonsense()
	{
		assertNull(ViewMemory.parse(null));
		assertNull(ViewMemory.parse(""));
		assertNull(ViewMemory.parse("1,2,3"));
		assertNull(ViewMemory.parse("a,b,c,d"));
		assertNull(ViewMemory.parse("1,2,3,0"));
		assertNull(ViewMemory.parse("1,2,3,-4"));
		assertNull(ViewMemory.parse("1,NaN,3,4"));
		assertNull(ViewMemory.parse("1,2,Infinity,4"));
	}

	@Test
	public void formatUsesAPointNotAComma()
	{
		String text = ViewMemory.format(0, 1.5, 2.5, 3.5);
		assertEquals(4, text.split(",").length);
	}
}
