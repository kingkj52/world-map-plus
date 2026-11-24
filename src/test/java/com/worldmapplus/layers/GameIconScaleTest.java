package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameIconScaleTest
{
	private static final double[] ZOOMS = {0.05, 0.08, 0.1, 0.15, 0.2, 0.25, 0.3, 0.5, 0.75, 1, 1.5, 2, 3, 4, 8, 16, 32};

	@Test
	public void theSideGrowsWithTheZoomAndStaysBetweenTheLimits()
	{
		double previous = 0;
		for (double zoom : ZOOMS)
		{
			double side = GameIconScale.side(zoom);
			assertTrue("zoom " + zoom, side >= GameIconScale.MIN_SIDE - 1e-9 && side <= GameIconScale.MAX_SIDE + 1e-9);
			assertTrue("never smaller as the zoom grows at " + zoom, side >= previous - 1e-9);
			previous = side;
		}
		assertEquals(GameIconScale.MIN_SIDE, GameIconScale.side(0.001), 0);
		assertEquals(GameIconScale.MAX_SIDE, GameIconScale.side(1000), 0);
		assertEquals(GameIconScale.MAX_SIDE, GameIconScale.side(GameIconScale.FULL_ZOOM), 0);
		assertEquals(GameIconScale.MIN_SIDE, GameIconScale.side(GameIconScale.SMALL_ZOOM), 0);
	}

	@Test
	public void aPictureIsAWholeNumberOfPixelsAndNeverTooSmallToRead()
	{
		for (double zoom : ZOOMS)
		{
			int side = GameIconScale.pictureSize(zoom, GameIconSprites.SIZE);
			assertTrue("zoom " + zoom, side >= GameIconScale.MIN_SIDE && side <= GameIconScale.MAX_SIDE);
			assertEquals(side, GameIconScale.drawnSize(zoom, GameIconSprites.SIZE));
		}
		assertEquals(GameIconScale.MAX_SIDE, GameIconScale.pictureSize(GameIconScale.FULL_ZOOM, GameIconSprites.SIZE));
		assertEquals(GameIconScale.MAX_SIDE, GameIconScale.pictureSize(16, GameIconSprites.SIZE));
	}

	@Test
	public void nothingIsDrawnBelowTheHideZoomAndTheMapNeverGoesThere()
	{
		assertFalse(GameIconScale.shown(GameIconScale.HIDE_ZOOM / 2));
		assertEquals(0, GameIconScale.pictureSize(GameIconScale.HIDE_ZOOM / 2, GameIconSprites.SIZE));
		assertTrue(GameIconScale.shown(0.05));
	}

	@Test
	public void theHitAreaIsThePictureAndAMarginAndAtLeastTheLeast()
	{
		assertEquals(26 + 2 * GameIconScale.HIT_MARGIN, GameIconScale.hitSize(26));
		assertEquals(GameIconScale.MIN_HIT_SIZE, GameIconScale.hitSize(3));
	}

	@Test
	public void theRoomAnIconKeepsFallsAsTheMapComesIn()
	{
		int previous = Integer.MAX_VALUE;
		for (double zoom : ZOOMS)
		{
			int size = GameIconScale.drawnSize(zoom, GameIconSprites.SIZE);
			int room = GameIconScale.footprint(zoom, size);
			if (zoom >= GameIconScale.CROWD_ZOOM)
			{
				assertEquals("no thinning from the crowd zoom up, at " + zoom, 0, room);
			}
			else
			{
				assertTrue("some room at " + zoom, room > 0 && room <= size);
				assertTrue("room never grows with the zoom, at " + zoom, room <= previous || zoom <= GameIconScale.ROOMY_ZOOM);
				previous = room;
			}
		}
		assertEquals(Math.round(26 * GameIconScale.ROOMY_SHARE), GameIconScale.footprint(1.0, 26));
		assertEquals(0, GameIconScale.footprint(16, 26));
	}

	@Test
	public void minorIconsLeaveInOneOrderAndAreAllBackFromTheAllZoom()
	{
		assertEquals(0.0, GameIconScale.minorShare(GameIconScale.MINOR_NONE_ZOOM), 0);
		assertEquals(0.0, GameIconScale.minorShare(0.05), 0);
		assertEquals(1.0, GameIconScale.minorShare(GameIconScale.MINOR_ALL_ZOOM), 0);
		assertEquals(1.0, GameIconScale.minorShare(10), 0);
		double previous = 0;
		for (double zoom : ZOOMS)
		{
			double share = GameIconScale.minorShare(zoom);
			assertTrue("share never falls as the zoom grows, at " + zoom, share >= previous - 1e-12);
			previous = share;
		}
	}
}
