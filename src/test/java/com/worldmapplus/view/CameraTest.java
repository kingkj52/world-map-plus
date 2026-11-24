package com.worldmapplus.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CameraTest
{
	@Test
	public void draggingMovesTheCentreAgainstThePointer()
	{
		Camera c = new Camera(3200, 3200);
		c.beginDrag(0);
		c.drag(40, 0, 4.0, 16);
		assertEquals(3190.0, c.getX(), 1e-9);
		c.drag(0, 40, 4.0, 32);
		assertEquals(3210.0, c.getY(), 1e-9);
	}

	@Test
	public void draggingTurnsFollowingOff()
	{
		Camera c = new Camera(0, 0);
		c.setFollowing(true);
		c.beginDrag(0);
		assertFalse(c.isFollowing());
	}

	@Test
	public void aFlingCoastsAndThenStops()
	{
		Camera c = new Camera(3200, 3200);
		c.beginDrag(0);
		for (int i = 1; i <= 5; i++)
		{
			c.drag(30, 0, 4.0, i * 16L);
		}
		c.endDrag(5 * 16L + 10);
		assertTrue(c.isCoasting());
		double before = c.getX();
		for (int i = 0; i < 400 && c.isCoasting(); i++)
		{
			c.update(0.016, 4.0, 0, 0);
		}
		assertFalse(c.isCoasting());
		assertTrue(c.getX() < before);
	}

	@Test
	public void releasingAfterAPauseDoesNotCoast()
	{
		Camera c = new Camera(3200, 3200);
		c.beginDrag(0);
		c.drag(30, 0, 4.0, 16);
		c.endDrag(16 + Camera.RESTING_MILLIS + 50);
		assertFalse(c.isCoasting());
	}

	@Test
	public void followingEasesTowardsTheTarget()
	{
		Camera c = new Camera(3200, 3200);
		c.setFollowing(true);
		for (int i = 0; i < 200; i++)
		{
			c.update(0.016, 4.0, 3300, 3100);
		}
		assertEquals(3300.0, c.getX(), 0.01);
		assertEquals(3100.0, c.getY(), 0.01);
	}

	@Test
	public void jumpToStopsMotion()
	{
		Camera c = new Camera(0, 0);
		c.beginDrag(0);
		for (int i = 1; i <= 5; i++)
		{
			c.drag(30, 0, 4.0, i * 16L);
		}
		c.endDrag(90);
		c.jumpTo(5, 5);
		assertFalse(c.isCoasting());
		assertEquals(5.0, c.getX(), 1e-9);
	}

	@Test
	public void eventsWithTheSameTimestampCannotCauseAnAbsurdFling()
	{
		Camera c = new Camera(3200, 3200);
		c.beginDrag(1000);
		c.drag(80, 0, 4.0, 1000);
		c.drag(80, 0, 4.0, 1000);
		c.endDrag(1000);
		double before = c.getX();
		for (int i = 0; i < 400 && c.isCoasting(); i++)
		{
			c.update(0.016, 4.0, 0, 0);
		}
		// Even at the speed cap the glide is a bounded distance: cap times the fade time constant.
		double travelled = Math.abs(before - c.getX()) * 4.0;
		assertTrue("glided " + travelled + " px", travelled <= Camera.MAX_SPEED * Camera.INERTIA_SECONDS * 1.2);
	}
}
