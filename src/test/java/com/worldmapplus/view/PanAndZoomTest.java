package com.worldmapplus.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.map.MapInfo;
import com.worldmapplus.theme.Motion;
import java.awt.Rectangle;
import org.junit.Test;

/**
 * The drag, inertia, following, glide and wheel rules of {@link MapViewer} on their own, with no map to keep the camera on.
 * Round 75 folded the camera and the zoom model into the viewer; these are their tests, kept case for case.
 */
public class PanAndZoomTest
{
	private static final Rectangle BOUNDS = new Rectangle(0, 0, 800, 600);

	private long now;

	private MapViewer at(double x, double y, double ppt)
	{
		MapViewer v = new MapViewer(null, x, y, ppt);
		v.update(now, BOUNDS, 0, 0);
		return v;
	}

	/** A viewer whose zoom jumps straight to its target. */
	private MapViewer instant(double ppt)
	{
		MapViewer v = at(0, 0, ppt);
		v.setFeel(1, 1, 0);
		return v;
	}

	private double zoomAfterAFrame(MapViewer v)
	{
		now += 16;
		return v.update(now, BOUNDS, 0, 0).getPixelsPerTile();
	}

	private void frames(MapViewer v, int count, double followX, double followY)
	{
		for (int i = 0; i < count; i++)
		{
			now += 16;
			v.update(now, BOUNDS, followX, followY);
		}
	}

	/** Presses at 100,100 and drags by the given pixels at the given times; the first step must pass the drag threshold. */
	private static void fling(MapViewer v, int dx, int count, long start, long step)
	{
		v.onPress(100, 100, start);
		for (int i = 1; i <= count; i++)
		{
			v.onDrag(100 + i * dx, 100, start + i * step);
		}
	}

	@Test
	public void draggingMovesTheCentreAgainstThePointer()
	{
		MapViewer c = at(3200, 3200, 4);
		c.onPress(100, 100, 0);
		c.onDrag(140, 100, 16);
		assertEquals(3190.0, c.getX(), 1e-9);
		c.onDrag(140, 140, 32);
		assertEquals(3210.0, c.getY(), 1e-9);
	}

	@Test
	public void draggingTurnsFollowingOff()
	{
		MapViewer c = at(0, 0, 4);
		c.setFollowing(true);
		c.onPress(100, 100, 0);
		c.onDrag(120, 100, 16);
		assertFalse(c.isFollowing());
	}

	@Test
	public void aFlingCoastsAndThenStops()
	{
		MapViewer c = at(3200, 3200, 4);
		fling(c, 30, 5, 0, 16);
		c.onRelease(5 * 16L + 10);
		assertTrue(c.isCoasting());
		double before = c.getX();
		for (int i = 0; i < 400 && c.isCoasting(); i++)
		{
			frames(c, 1, 0, 0);
		}
		assertFalse(c.isCoasting());
		assertTrue(c.getX() < before);
	}

	@Test
	public void releasingAfterAPauseDoesNotCoast()
	{
		MapViewer c = at(3200, 3200, 4);
		fling(c, 30, 1, 0, 16);
		c.onRelease(16 + MapViewer.RESTING_MILLIS + 50);
		assertFalse(c.isCoasting());
	}

	@Test
	public void followingEasesTowardsTheTarget()
	{
		MapViewer c = at(3200, 3200, 4);
		c.setFollowing(true);
		frames(c, 200, 3300, 3100);
		assertEquals(3300.0, c.getX(), 0.01);
		assertEquals(3100.0, c.getY(), 0.01);
	}

	@Test
	public void jumpToStopsMotion()
	{
		MapViewer c = at(0, 0, 4);
		fling(c, 30, 5, 0, 16);
		c.onRelease(90);
		c.jumpTo(5, 5);
		assertFalse(c.isCoasting());
		assertEquals(5.0, c.getX(), 1e-9);
	}

	@Test
	public void eventsWithTheSameTimestampCannotCauseAnAbsurdFling()
	{
		MapViewer c = at(3200, 3200, 4);
		fling(c, 80, 2, 1000, 0);
		c.onRelease(1000);
		double before = c.getX();
		for (int i = 0; i < 400 && c.isCoasting(); i++)
		{
			frames(c, 1, 0, 0);
		}
		// Even at the speed cap the glide is a bounded distance: cap times the fade time constant.
		double travelled = Math.abs(before - c.getX()) * 4.0;
		assertTrue("glided " + travelled + " px", travelled <= MapViewer.MAX_SPEED * MapViewer.INERTIA_SECONDS * 1.2);
	}

	@Test
	public void pushingTheWheelAwayZoomsIn()
	{
		MapViewer z = instant(4.0);
		z.onWheel(-1, 0, 0, 1000);
		assertTrue(zoomAfterAFrame(z) > 4.0);
	}

	@Test
	public void pullingTheWheelTowardsYouZoomsOut()
	{
		MapViewer z = instant(4.0);
		z.onWheel(1, 0, 0, 1000);
		assertTrue(zoomAfterAFrame(z) < 4.0);
	}

	@Test
	public void aSingleNotchIsGentle()
	{
		MapViewer z = instant(4.0);
		z.onWheel(-1, 0, 0, 1000);
		double ratio = zoomAfterAFrame(z) / 4.0;
		assertTrue("ratio " + ratio, ratio > 1.05 && ratio < 1.2);
	}

	@Test
	public void rapidNotchesAccelerate()
	{
		MapViewer z = instant(1.0);
		double first = 0;
		double last = 0;
		double previous = 1.0;
		for (int i = 0; i < 10; i++)
		{
			z.onWheel(-1, 0, 0, 1000 + i * 40L);
			double shown = zoomAfterAFrame(z);
			double step = Math.log(shown / previous) / Math.log(2);
			if (i == 0)
			{
				first = step;
			}
			last = step;
			previous = shown;
		}
		assertTrue("first " + first + " last " + last, last > first * 3);
	}

	@Test
	public void aPauseResetsTheAcceleration()
	{
		MapViewer z = instant(1.0);
		for (int i = 0; i < 10; i++)
		{
			z.onWheel(-1, 0, 0, 1000 + i * 40L);
		}
		assertTrue(z.momentum() > 5);
		z.onWheel(-1, 0, 0, 5000);
		assertEquals(0.0, z.momentum(), 1e-9);
	}

	@Test
	public void accelerationCanBeTurnedOff()
	{
		MapViewer z = instant(1.0);
		z.setFeel(1, 0, 0);
		double previous = 1.0;
		double firstStep = 0;
		for (int i = 0; i < 8; i++)
		{
			z.onWheel(-1, 0, 0, 1000 + i * 40L);
			double shown = zoomAfterAFrame(z);
			double step = Math.log(shown / previous) / Math.log(2);
			if (i == 0)
			{
				firstStep = step;
			}
			assertEquals(firstStep, step, 1e-9);
			previous = shown;
		}
	}

	@Test
	public void clampsToTheLimits()
	{
		MapViewer z = instant(4.0);
		for (int i = 0; i < 200; i++)
		{
			z.onWheel(-1, 0, 0, 1000 + i * 30L);
		}
		assertEquals(MapViewer.MAX_PIXELS_PER_TILE, zoomAfterAFrame(z), 1e-9);
		for (int i = 0; i < 400; i++)
		{
			z.onWheel(1, 0, 0, 20000 + i * 30L);
		}
		assertEquals(MapViewer.MIN_PIXELS_PER_TILE, zoomAfterAFrame(z), 1e-9);
	}

	@Test
	public void coversTheWholeRangeInUnderAHundredNotches()
	{
		MapViewer z = instant(MapViewer.MAX_PIXELS_PER_TILE);
		int notches = 0;
		while (zoomAfterAFrame(z) > MapViewer.MIN_PIXELS_PER_TILE * 1.001 && notches < 1000)
		{
			z.onWheel(1, 0, 0, 1000 + notches * 35L);
			notches++;
		}
		assertTrue("took " + notches, notches < 100);
	}

	@Test
	public void smoothingApproachesTheTargetWithoutOvershoot()
	{
		MapViewer z = at(0, 0, 1.0);
		z.setFeel(1, 1, 0.1);
		z.easeZoom(8.0);
		double previous = z.update(now, BOUNDS, 0, 0).getPixelsPerTile();
		for (int i = 0; i < 200; i++)
		{
			double shown = zoomAfterAFrame(z);
			assertTrue(shown >= previous - 1e-12);
			assertTrue(shown <= 8.0 + 1e-9);
			previous = shown;
		}
		assertEquals(8.0, previous, 0.01);
		assertFalse(z.isAnimating() && previous < 7.9);
	}

	@Test
	public void jumpZoomIsImmediateAndClamped()
	{
		MapViewer z = new MapViewer(null, 0, 0, 4.0);
		z.jumpZoom(1000.0);
		assertEquals(MapViewer.MAX_PIXELS_PER_TILE, z.pixelsPerTile(), 1e-9);
		z.jumpZoom(0.0001);
		assertEquals(MapViewer.MIN_PIXELS_PER_TILE, z.pixelsPerTile(), 1e-9);
	}

	@Test
	public void ignoresEmptyWheelEvents()
	{
		MapViewer z = instant(4.0);
		z.onWheel(0, 0, 0, 1000);
		z.onWheel(Double.NaN, 0, 0, 1000);
		assertEquals(4.0, zoomAfterAFrame(z), 1e-9);
	}

	@Test
	public void aGlideEndsExactlyWhereAJumpWouldPutTheCamera()
	{
		MapViewer glided = new MapViewer(null, 3000, 3000, 4);
		MapViewer jumped = new MapViewer(null, 3000, 3000, 4);
		glided.glide(3333.25, 3555.75, 1000, 300);
		jumped.jumpTo(3333.25, 3555.75);
		for (long t = 1000; t <= 1400; t += 16)
		{
			glided.advanceGlide(t);
		}
		assertFalse(glided.isGliding());
		assertEquals(jumped.getX(), glided.getX(), 0);
		assertEquals(jumped.getY(), glided.getY(), 0);
	}

	@Test
	public void theCameraIsPartWayBetweenAndMovesOnlyForwardAlongTheLine()
	{
		MapViewer camera = new MapViewer(null, 0, 0, 4);
		camera.glide(100, 0, 0, 400);
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
		MapViewer camera = new MapViewer(null, 0, 0, 4);
		camera.glide(500, 250, 5000, 300);
		camera.advanceGlide(5010);
		camera.advanceGlide(15000);
		assertEquals(500, camera.getX(), 0);
		assertEquals(250, camera.getY(), 0);
		assertFalse(camera.isGliding());
	}

	@Test
	public void aDragTheWheelOrAJumpStopsTheGlideWhereItIs()
	{
		MapViewer dragged = new MapViewer(null, 0, 0, 4);
		dragged.glide(100, 0, 0, 400);
		dragged.advanceGlide(100);
		double at = dragged.getX();
		dragged.onPress(100, 100, 110);
		dragged.onDrag(100, 100 + MapViewer.DRAG_THRESHOLD, 110);
		dragged.advanceGlide(300);
		assertEquals(at, dragged.getX(), 0);
		assertFalse(dragged.isGliding());

		MapViewer wheeled = new MapViewer(null, 0, 0, 4);
		wheeled.glide(100, 0, 0, 400);
		wheeled.onWheel(-1, 0, 0, 0);
		wheeled.advanceGlide(400);
		assertEquals(0, wheeled.getX(), 0);

		MapViewer jumped = new MapViewer(null, 0, 0, 4);
		jumped.glide(100, 0, 0, 400);
		jumped.jumpTo(7, 8);
		jumped.advanceGlide(400);
		assertEquals(7, jumped.getX(), 0);
		assertEquals(8, jumped.getY(), 0);
	}

	@Test
	public void aClockThatGoesBackDoesNotMoveTheCamera()
	{
		MapViewer camera = new MapViewer(null, 10, 10, 4);
		camera.glide(50, 50, 1000, 300);
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
		MapInfo map = new MapInfo(0, "Test", 0, 0, 4000, 4000, 2000, 2000);
		MapViewer glided = new MapViewer(map, 3000, 3000, 4);
		MapViewer jumped = new MapViewer(map, 3000, 3000, 4);
		glided.update(0, BOUNDS, 0, 0);
		jumped.update(0, BOUNDS, 0, 0);
		glided.glideTo(map, 3400, 3300, 16);
		jumped.showMap(map, 3400, 3300);
		glided.update(32, BOUNDS, 0, 0);
		assertTrue("still on its way", glided.getX() < 3400);
		assertTrue(glided.isMoving());
		for (long t = 48; t < 1000; t += 16)
		{
			glided.update(t, BOUNDS, 0, 0);
			jumped.update(t, BOUNDS, 0, 0);
		}
		assertEquals(jumped.getX(), glided.getX(), 0);
		assertEquals(jumped.getY(), glided.getY(), 0);
		assertFalse("nothing moves once it has arrived", glided.isMoving());
	}
}
