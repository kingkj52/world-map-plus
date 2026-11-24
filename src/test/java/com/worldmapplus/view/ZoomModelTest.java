package com.worldmapplus.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ZoomModelTest
{
	private static ZoomModel instant(double ppt)
	{
		ZoomModel z = new ZoomModel(ppt);
		z.setSmoothingSeconds(0);
		return z;
	}

	@Test
	public void pushingTheWheelAwayZoomsIn()
	{
		ZoomModel z = instant(4.0);
		z.onWheel(-1, 1000);
		assertTrue(z.update(0.016) > 4.0);
	}

	@Test
	public void pullingTheWheelTowardsYouZoomsOut()
	{
		ZoomModel z = instant(4.0);
		z.onWheel(1, 1000);
		assertTrue(z.update(0.016) < 4.0);
	}

	@Test
	public void aSingleNotchIsGentle()
	{
		ZoomModel z = instant(4.0);
		z.onWheel(-1, 1000);
		double ratio = z.update(0.016) / 4.0;
		assertTrue("ratio " + ratio, ratio > 1.05 && ratio < 1.2);
	}

	@Test
	public void rapidNotchesAccelerate()
	{
		ZoomModel z = instant(1.0);
		double first = 0;
		double last = 0;
		double previous = 1.0;
		for (int i = 0; i < 10; i++)
		{
			z.onWheel(-1, 1000 + i * 40L);
			double now = z.update(0.016);
			double step = Math.log(now / previous) / Math.log(2);
			if (i == 0)
			{
				first = step;
			}
			last = step;
			previous = now;
		}
		assertTrue("first " + first + " last " + last, last > first * 3);
	}

	@Test
	public void aPauseResetsTheAcceleration()
	{
		ZoomModel z = instant(1.0);
		for (int i = 0; i < 10; i++)
		{
			z.onWheel(-1, 1000 + i * 40L);
		}
		assertTrue(z.momentum() > 5);
		z.onWheel(-1, 5000);
		assertEquals(0.0, z.momentum(), 1e-9);
	}

	@Test
	public void accelerationCanBeTurnedOff()
	{
		ZoomModel z = instant(1.0);
		z.setAcceleration(0);
		double previous = 1.0;
		double firstStep = 0;
		for (int i = 0; i < 8; i++)
		{
			z.onWheel(-1, 1000 + i * 40L);
			double now = z.update(0.016);
			double step = Math.log(now / previous) / Math.log(2);
			if (i == 0)
			{
				firstStep = step;
			}
			assertEquals(firstStep, step, 1e-9);
			previous = now;
		}
	}

	@Test
	public void clampsToTheLimits()
	{
		ZoomModel z = instant(4.0);
		for (int i = 0; i < 200; i++)
		{
			z.onWheel(-1, 1000 + i * 30L);
		}
		assertEquals(ZoomModel.MAX_PIXELS_PER_TILE, z.update(0.016), 1e-9);
		for (int i = 0; i < 400; i++)
		{
			z.onWheel(1, 20000 + i * 30L);
		}
		assertEquals(ZoomModel.MIN_PIXELS_PER_TILE, z.update(0.016), 1e-9);
	}

	@Test
	public void coversTheWholeRangeInUnderAHundredNotches()
	{
		ZoomModel z = instant(ZoomModel.MAX_PIXELS_PER_TILE);
		int notches = 0;
		while (z.update(0.016) > ZoomModel.MIN_PIXELS_PER_TILE * 1.001 && notches < 1000)
		{
			z.onWheel(1, 1000 + notches * 35L);
			notches++;
		}
		assertTrue("took " + notches, notches < 100);
	}

	@Test
	public void smoothingApproachesTheTargetWithoutOvershoot()
	{
		ZoomModel z = new ZoomModel(1.0);
		z.setSmoothingSeconds(0.1);
		z.easeTo(8.0);
		double previous = z.update(0.0);
		for (int i = 0; i < 200; i++)
		{
			double now = z.update(0.016);
			assertTrue(now >= previous - 1e-12);
			assertTrue(now <= 8.0 + 1e-9);
			previous = now;
		}
		assertEquals(8.0, previous, 0.01);
		assertFalse(z.isAnimating() && previous < 7.9);
	}

	@Test
	public void jumpToIsImmediateAndClamped()
	{
		ZoomModel z = new ZoomModel(4.0);
		z.jumpTo(1000.0);
		assertEquals(ZoomModel.MAX_PIXELS_PER_TILE, z.pixelsPerTile(), 1e-9);
		z.jumpTo(0.0001);
		assertEquals(ZoomModel.MIN_PIXELS_PER_TILE, z.pixelsPerTile(), 1e-9);
	}

	@Test
	public void ignoresEmptyWheelEvents()
	{
		ZoomModel z = instant(4.0);
		z.onWheel(0, 1000);
		z.onWheel(Double.NaN, 1000);
		assertEquals(4.0, z.update(0.016), 1e-9);
	}
}
