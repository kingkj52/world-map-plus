package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.WorldMapPlusConfig.HudCorner;
import java.awt.Rectangle;
import org.junit.Test;

public class HudLayoutTest
{
	private static final Rectangle VIEW = new Rectangle(4, 4, 1000, 700);

	@Test
	public void theWholeViewIsExactlyTheViewport()
	{
		assertEquals(VIEW, HudLayout.compute(VIEW, 100, HudCorner.TOP_LEFT));
	}

	@Test
	public void cornersSitInsideTheViewWithAMargin()
	{
		for (HudCorner corner : HudCorner.values())
		{
			Rectangle r = HudLayout.compute(VIEW, 40, corner);
			assertTrue(corner + " " + r, VIEW.contains(r));
			assertEquals(400, r.width);
			assertEquals(280, r.height);
		}
		Rectangle topLeft = HudLayout.compute(VIEW, 40, HudCorner.TOP_LEFT);
		assertEquals(VIEW.x + HudLayout.MARGIN, topLeft.x);
		assertEquals(VIEW.y + HudLayout.MARGIN, topLeft.y);
		Rectangle bottomRight = HudLayout.compute(VIEW, 40, HudCorner.BOTTOM_RIGHT);
		assertEquals(VIEW.x + VIEW.width - 400 - HudLayout.MARGIN, bottomRight.x);
		assertEquals(VIEW.y + VIEW.height - 280 - HudLayout.MARGIN, bottomRight.y);
	}

	@Test
	public void theCentreIsCentred()
	{
		Rectangle r = HudLayout.compute(VIEW, 40, HudCorner.CENTRE);
		assertEquals(VIEW.x + (VIEW.width - r.width) / 2, r.x);
		assertEquals(VIEW.y + (VIEW.height - r.height) / 2, r.y);
	}

	@Test
	public void aTinyViewStillGetsAUsableMapInsideIt()
	{
		Rectangle tiny = new Rectangle(0, 0, 150, 100);
		Rectangle r = HudLayout.compute(tiny, 25, HudCorner.TOP_RIGHT);
		assertTrue(tiny.contains(r));
		assertTrue(r.width <= 150 && r.height <= 100);
	}

	@Test
	public void sizesGrowWithThePercentage()
	{
		Rectangle small = HudLayout.compute(VIEW, 25, HudCorner.TOP_LEFT);
		Rectangle large = HudLayout.compute(VIEW, 60, HudCorner.TOP_LEFT);
		assertTrue(large.width > small.width && large.height > small.height);
	}

	@Test
	public void absurdPercentagesAreClamped()
	{
		assertTrue(VIEW.contains(HudLayout.compute(VIEW, 0, HudCorner.TOP_LEFT)));
		assertTrue(VIEW.contains(HudLayout.compute(VIEW, -50, HudCorner.CENTRE)));
		assertEquals(VIEW, HudLayout.compute(VIEW, 400, HudCorner.TOP_LEFT));
	}
}
