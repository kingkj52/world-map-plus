package com.worldmapplus.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.map.MapInfo;
import java.awt.Rectangle;
import org.junit.Test;

public class MapViewerTest
{
	private static final MapInfo SURFACE = new MapInfo(0, "Surface", 896, 1984, 4096, 4288, 2496, 3328);
	private static final MapInfo SMALL = new MapInfo(9, "Small", 2000, 5000, 2192, 5128, 2100, 5060);
	private static final Rectangle BOUNDS = new Rectangle(10, 20, 800, 600);

	private static MapViewer viewer()
	{
		MapViewer v = new MapViewer(SURFACE, 3200, 3200, 4.0);
		v.getZoom().setSmoothingSeconds(0);
		v.update(0, BOUNDS, 0, 0);
		return v;
	}

	@Test
	public void theFloorIsKeptInRangeAndResetsOnAnotherMap()
	{
		MapInfo ground = new MapInfo(0, "Ground", 3136, 3136, 3328, 3328, 3200, 3200);
		MapInfo cave = new MapInfo(5, "Cave", 3136, 9600, 3328, 9728, 3200, 9650);
		MapViewer viewer = new MapViewer(ground, 3200, 3200, 4.0);
		assertEquals(0, viewer.getFloor());
		viewer.setFloor(2);
		assertEquals(2, viewer.getFloor());
		viewer.setFloor(9);
		assertEquals(3, viewer.getFloor());
		viewer.setFloor(-4);
		assertEquals(0, viewer.getFloor());

		viewer.setFloor(1);
		viewer.showMap(ground, 3210, 3210);
		assertEquals("the same map keeps its floor", 1, viewer.getFloor());
		viewer.showMap(cave, 3200, 9650);
		assertEquals("another map starts on the ground", 0, viewer.getFloor());
	}

	@Test
	public void wheelZoomKeepsTheWorldPointUnderTheMouseFixed()
	{
		MapViewer v = viewer();
		ViewTransform before = v.update(16, BOUNDS, 0, 0);
		int mx = 600;
		int my = 150;
		double wx = before.worldX(mx);
		double wy = before.worldY(my);
		v.onWheel(-3, mx, my, 100);
		ViewTransform after = v.update(116, BOUNDS, 0, 0);
		assertTrue(after.getPixelsPerTile() > before.getPixelsPerTile());
		assertEquals(wx, after.worldX(mx), 1e-6);
		assertEquals(wy, after.worldY(my), 1e-6);
	}

	@Test
	public void wheelZoomOutAlsoAnchors()
	{
		MapViewer v = viewer();
		ViewTransform before = v.update(16, BOUNDS, 0, 0);
		double wx = before.worldX(200);
		double wy = before.worldY(500);
		v.onWheel(5, 200, 500, 100);
		ViewTransform after = v.update(116, BOUNDS, 0, 0);
		assertTrue(after.getPixelsPerTile() < before.getPixelsPerTile());
		assertEquals(wx, after.worldX(200), 1e-6);
		assertEquals(wy, after.worldY(500), 1e-6);
	}

	@Test
	public void whileFollowingTheZoomAnchorsOnTheCentre()
	{
		MapViewer v = viewer();
		v.getCamera().setFollowing(true);
		v.update(16, BOUNDS, 3200, 3200);
		v.onWheel(-3, 700, 100, 100);
		ViewTransform after = v.update(116, BOUNDS, 3200, 3200);
		assertEquals(3200.0, after.getCenterX(), 0.5);
		assertEquals(3200.0, after.getCenterY(), 0.5);
	}

	@Test
	public void draggingPansTheMap()
	{
		MapViewer v = viewer();
		ViewTransform before = v.update(16, BOUNDS, 0, 0);
		double wx = before.worldX(300);
		v.onPress(300, 300, 20);
		assertTrue(v.onDrag(340, 300, 36));
		ViewTransform after = v.update(52, BOUNDS, 0, 0);
		assertEquals(340.0, after.screenX(wx), 1e-6);
	}

	@Test
	public void aSmallMovementIsStillAClick()
	{
		MapViewer v = viewer();
		v.onPress(300, 300, 20);
		assertFalse(v.onDrag(302, 301, 36));
		assertTrue(v.onRelease(60));
	}

	@Test
	public void aRealDragIsNotAClick()
	{
		MapViewer v = viewer();
		v.onPress(300, 300, 20);
		v.onDrag(330, 300, 36);
		assertFalse(v.onRelease(60));
		assertFalse(v.isDragging());
	}

	@Test
	public void aReleaseWithoutAPressIsNothing()
	{
		assertFalse(viewer().onRelease(10));
	}

	@Test
	public void theCameraCannotLeaveTheMapByMoreThanTheMargin()
	{
		MapViewer v = viewer();
		v.getCamera().jumpTo(-50000, 90000);
		ViewTransform t = v.update(16, BOUNDS, 0, 0);
		assertTrue(t.getCenterX() > SURFACE.getMinX() - BOUNDS.width);
		assertTrue(t.getCenterY() < SURFACE.getMaxY() + BOUNDS.height);
	}

	@Test
	public void aSmallMapCannotBeShrunkToASpeck()
	{
		MapViewer v = viewer();
		v.showMap(SMALL, 2100, 5060);
		v.update(16, BOUNDS, 0, 0);
		// 192 x 128 tiles in an 800 x 600 view: fits at about 4.2 px per tile, so the floor is
		// well above the global minimum.
		assertTrue(v.getZoom().getMinPixelsPerTile() > 1.0);
		for (int i = 0; i < 100; i++)
		{
			v.onWheel(5, 400, 300, 100 + i * 30L);
		}
		ViewTransform t = v.update(5000, BOUNDS, 0, 0);
		assertTrue(t.getPixelsPerTile() >= v.getZoom().getMinPixelsPerTile() - 1e-9);
	}

	@Test
	public void theSurfaceCanBeZoomedOutUntilTheWholeWorldFits()
	{
		MapViewer v = viewer();
		assertTrue(v.getZoom().getMinPixelsPerTile() < 0.25);
		assertEquals(ZoomModel.MAX_PIXELS_PER_TILE, v.getZoom().getMaxPixelsPerTile(), 1e-9);
	}

	@Test
	public void showMapAimsTheCamera()
	{
		MapViewer v = viewer();
		v.showMap(SMALL, 2111, 5077);
		ViewTransform t = v.update(16, BOUNDS, 0, 0);
		assertEquals(2111.0, t.getCenterX(), 1e-6);
		assertEquals(5077.0, t.getCenterY(), 1e-6);
		assertEquals(9, v.getMap().getId());
	}

	@Test
	public void followingTracksTheTarget()
	{
		MapViewer v = viewer();
		v.getCamera().setFollowing(true);
		ViewTransform t = null;
		for (int i = 0; i < 100; i++)
		{
			t = v.update(100 + i * 16L, BOUNDS, 3300, 3100);
		}
		assertEquals(3300.0, t.getCenterX(), 0.05);
		assertEquals(3100.0, t.getCenterY(), 0.05);
	}
}
