package com.worldmapplus.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ViewTransformTest
{
	private static final double EPS = 1e-9;

	private static ViewTransform view()
	{
		return new ViewTransform(3200.0, 3200.0, 4.0, 100, 50, 800, 600);
	}

	@Test
	public void centreMapsToTheMiddleOfTheRectangle()
	{
		ViewTransform v = view();
		assertEquals(500.0, v.screenX(3200.0), EPS);
		assertEquals(350.0, v.screenY(3200.0), EPS);
	}

	@Test
	public void northIsUp()
	{
		ViewTransform v = view();
		assertTrue(v.screenY(3210.0) < v.screenY(3200.0));
		assertEquals(40.0, v.screenY(3200.0) - v.screenY(3210.0), EPS);
	}

	@Test
	public void eastIsRight()
	{
		ViewTransform v = view();
		assertEquals(40.0, v.screenX(3210.0) - v.screenX(3200.0), EPS);
	}

	@Test
	public void roundTripsBothWays()
	{
		ViewTransform v = view();
		assertEquals(3187.25, v.worldX(v.screenX(3187.25)), EPS);
		assertEquals(3244.5, v.worldY(v.screenY(3244.5)), EPS);
		assertEquals(321.0, v.screenX(v.worldX(321.0)), EPS);
	}

	@Test
	public void visibleBoundsMatchTheRectangle()
	{
		ViewTransform v = view();
		assertEquals(v.worldX(100), v.minWorldX(), EPS);
		assertEquals(v.worldX(900), v.maxWorldX(), EPS);
		assertEquals(v.worldY(650), v.minWorldY(), EPS);
		assertEquals(v.worldY(50), v.maxWorldY(), EPS);
		assertTrue(v.maxWorldY() > v.minWorldY());
	}

	@Test
	public void zoomingAboutAPixelKeepsItsWorldPointFixed()
	{
		ViewTransform v = view();
		double ax = 640, ay = 200;
		double wx = v.worldX(ax);
		double wy = v.worldY(ay);
		ViewTransform z = v.zoomedAbout(ax, ay, 0.37);
		assertEquals(wx, z.worldX(ax), 1e-7);
		assertEquals(wy, z.worldY(ay), 1e-7);
		assertEquals(0.37, z.getPixelsPerTile(), EPS);
	}

	@Test
	public void zoomingAboutTheCentreKeepsTheCentre()
	{
		ViewTransform v = view();
		ViewTransform z = v.zoomedAbout(500, 350, 16.0);
		assertEquals(3200.0, z.getCenterX(), 1e-7);
		assertEquals(3200.0, z.getCenterY(), 1e-7);
	}

	@Test
	public void panningFollowsThePointer()
	{
		ViewTransform v = view();
		double wx = v.worldX(300);
		double wy = v.worldY(300);
		ViewTransform p = v.pannedByPixels(25, -10);
		assertEquals(325.0, p.screenX(wx), EPS);
		assertEquals(290.0, p.screenY(wy), EPS);
	}

	@Test
	public void containsUsesHalfOpenBounds()
	{
		ViewTransform v = view();
		assertTrue(v.contains(100, 50));
		assertFalse(v.contains(900, 100));
		assertFalse(v.contains(99.9, 100));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsANonPositiveScale()
	{
		new ViewTransform(0, 0, 0, 0, 0, 10, 10);
	}
}
