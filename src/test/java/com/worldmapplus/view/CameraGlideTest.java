package com.worldmapplus.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.theme.Motion;
import org.junit.Test;

/** A glide is a function of the frame's time: it ends where a jump would, late frames skip ahead, and a drag stops it. */
public class CameraGlideTest
{
	@Test
	public void aGlideEndsExactlyWhereAJumpWouldPutTheCamera()
	{
		Camera glided = new Camera(3000, 3000);
		Camera jumped = new Camera(3000, 3000);
		glided.glideTo(3333.25, 3555.75, 1000, 300);
		jumped.jumpTo(3333.25, 3555.75);
		for (long now = 1000; now <= 1400; now += 16)
		{
			glided.advanceGlide(now);
		}
		assertFalse(glided.isGliding());
		assertEquals(jumped.getX(), glided.getX(), 0);
		assertEquals(jumped.getY(), glided.getY(), 0);
	}

	@Test
	public void theCameraIsPartWayBetweenAndMovesOnlyForwardAlongTheLine()
	{
		Camera camera = new Camera(0, 0);
		camera.glideTo(100, 0, 0, 400);
		camera.advanceGlide(100);
		double early = camera.getX();
		assertTrue("moved", early > 0 && early < 50);
		camera.advanceGlide(200);
		double middle = camera.getX();
		assertEquals("half way at half time", 50, middle, 1e-9);
		camera.advanceGlide(300);
		assertTrue(camera.getX() > middle && camera.getX() < 100);
		assertEquals(0, camera.getY(), 0);
		assertTrue(camera.isGliding());
	}

	@Test
	public void aLateFrameSkipsAheadToTheEnd()
	{
		Camera camera = new Camera(0, 0);
		camera.glideTo(500, 250, 5000, 300);
		camera.advanceGlide(5010);
		camera.advanceGlide(15000);
		assertEquals(500, camera.getX(), 0);
		assertEquals(250, camera.getY(), 0);
		assertFalse(camera.isGliding());
	}

	@Test
	public void aDragTheWheelOrAJumpStopsTheGlideWhereItIs()
	{
		Camera dragged = new Camera(0, 0);
		dragged.glideTo(100, 0, 0, 400);
		dragged.advanceGlide(100);
		double at = dragged.getX();
		dragged.beginDrag(110);
		dragged.advanceGlide(300);
		assertEquals(at, dragged.getX(), 0);
		assertFalse(dragged.isGliding());

		Camera wheeled = new Camera(0, 0);
		wheeled.glideTo(100, 0, 0, 400);
		wheeled.cancelGlide();
		wheeled.advanceGlide(400);
		assertEquals(0, wheeled.getX(), 0);

		Camera jumped = new Camera(0, 0);
		jumped.glideTo(100, 0, 0, 400);
		jumped.jumpTo(7, 8);
		jumped.advanceGlide(400);
		assertEquals(7, jumped.getX(), 0);
		assertEquals(8, jumped.getY(), 0);
	}

	@Test
	public void aClockThatGoesBackDoesNotMoveTheCamera()
	{
		Camera camera = new Camera(10, 10);
		camera.glideTo(50, 50, 1000, 300);
		camera.advanceGlide(900);
		assertEquals(10, camera.getX(), 0);
		assertEquals(10, camera.getY(), 0);
	}

	@Test
	public void theTimeAGlideTakesGrowsWithTheDistanceWithinLimits()
	{
		assertEquals(Motion.GLIDE_MIN_MILLIS, Motion.glideMillis(0));
		assertTrue(Motion.glideMillis(600) > Motion.glideMillis(60));
		assertEquals(Motion.GLIDE_MAX_MILLIS, Motion.glideMillis(1_000_000));
	}

	@Test
	public void theViewerGlidesOnTheSameMapAndEndsWhereShowMapWould()
	{
		com.worldmapplus.map.MapInfo map = new com.worldmapplus.map.MapInfo(0, "Test", 0, 0, 4000, 4000, 2000, 2000);
		MapViewer glided = new MapViewer(map, 3000, 3000, 4);
		MapViewer jumped = new MapViewer(map, 3000, 3000, 4);
		java.awt.Rectangle bounds = new java.awt.Rectangle(0, 0, 800, 600);
		glided.update(0, bounds, 0, 0);
		jumped.update(0, bounds, 0, 0);
		glided.glideTo(map, 3400, 3300, 16);
		jumped.showMap(map, 3400, 3300);
		glided.update(32, bounds, 0, 0);
		assertTrue("still on its way", glided.getCamera().getX() < 3400);
		assertTrue(glided.isMoving());
		for (long now = 48; now < 1000; now += 16)
		{
			glided.update(now, bounds, 0, 0);
			jumped.update(now, bounds, 0, 0);
		}
		assertEquals(jumped.getCamera().getX(), glided.getCamera().getX(), 0);
		assertEquals(jumped.getCamera().getY(), glided.getCamera().getY(), 0);
		assertFalse("nothing moves once it has arrived", glided.isMoving());
	}
}
