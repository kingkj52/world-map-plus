package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeMapSyncTest
{
	private static NativeMapSync.Spot spot(int x, int y)
	{
		return new NativeMapSync.Spot(x, y);
	}

	private static NativeMapSync.Spot settle(NativeMapSync sync, boolean list, NativeMapSync.Spot at, boolean follow)
	{
		NativeMapSync.Spot found = null;
		for (int i = 0; i < NativeMapSync.STILL_FRAMES + 3 && found == null; i++)
		{
			found = sync.update(list, at, follow);
		}
		return found;
	}

	@Test
	public void aMapThatNeverMovesChangesNothing()
	{
		NativeMapSync sync = new NativeMapSync();
		for (int i = 0; i < 200; i++)
		{
			assertNull(sync.update(false, spot(3200, 3200), true));
			assertFalse(sync.isOpen());
		}
	}

	@Test
	public void whileTheListIsOpenTheMapStaysOutOfTheWay()
	{
		NativeMapSync sync = new NativeMapSync();
		assertNull(sync.update(true, spot(3200, 3200), true));
		assertTrue(sync.isOpen());
		assertNull(sync.update(true, spot(3200, 3200), true));
		assertTrue(sync.isOpen());
		sync.update(false, spot(3200, 3200), true);
		assertFalse(sync.isOpen());
	}

	@Test
	public void aPlaceChosenFromTheListIsFollowedOnceTheGameMapHasArrived()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(true, spot(3200, 3200), true);
		assertNull(sync.update(false, spot(3200, 3200), true));
		// The game's map glides, so it passes through other positions first.
		assertNull(sync.update(false, spot(3100, 3300), true));
		assertNull(sync.update(false, spot(3000, 3400), true));
		assertEquals(spot(2900, 3500), settle(sync, false, spot(2900, 3500), true));
		for (int i = 0; i < 100; i++)
		{
			assertNull("it only follows once", sync.update(false, spot(2900, 3500), true));
		}
	}

	@Test
	public void aListChoiceIsFollowedEvenWhenOtherPluginsAreNot()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(true, spot(3200, 3200), false);
		assertEquals(spot(2900, 3500), settle(sync, false, spot(2900, 3500), false));
	}

	@Test
	public void anotherPluginCentringTheMapIsFollowed()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(false, spot(3200, 3200), true);
		assertEquals(spot(2650, 3300), settle(sync, false, spot(2650, 3300), true));
		assertEquals("and again, to somewhere else", spot(1700, 3600), settle(sync, false, spot(1700, 3600), true));
	}

	@Test
	public void withFollowingOffAnotherPluginsMoveIsIgnored()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(false, spot(3200, 3200), false);
		for (int i = 0; i < 40; i++)
		{
			assertNull(sync.update(false, spot(2650, 3300), false));
		}
		// And it is not held against the map later: a move back is a move too.
		assertNull(settle(sync, false, spot(3200, 3200), false));
	}

	@Test
	public void closingTheListWithoutChoosingMovesNothing()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(true, spot(3200, 3200), true);
		for (int i = 0; i < 100; i++)
		{
			assertNull(sync.update(false, spot(3200, 3200), true));
		}
	}

	@Test
	public void anUnreadablePositionNeverMovesTheMap()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(true, null, true);
		for (int i = 0; i < 120; i++)
		{
			assertNull(sync.update(false, null, true));
		}
	}

	@Test
	public void afterTheWindowClosesTheNextPositionIsJustWhereTheMapIs()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(false, spot(3200, 3200), true);
		sync.update(true, spot(3200, 3200), true);
		sync.reset();
		assertFalse(sync.isOpen());
		for (int i = 0; i < 40; i++)
		{
			assertNull("the map reopened somewhere else, which is not a move", sync.update(false, spot(100, 100), true));
		}
		assertEquals("but a move after that is", spot(500, 500), settle(sync, false, spot(500, 500), true));
	}

	@Test
	public void aMapPickedInTheListThatTheGameMapWasAlreadyOnIsReportedOnceAfterItStaysStill()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(false, spot(3200, 3200), true);
		sync.update(true, spot(3200, 3200), true);
		assertNull("not while the list is open", sync.takeListSelection());
		for (int i = 0; i < NativeMapSync.STILL_FRAMES + 4; i++)
		{
			assertNull(sync.update(false, spot(3200, 3200), true));
		}
		NativeMapSync.Spot picked = sync.takeListSelection();
		assertEquals(3200, picked.getX());
		assertEquals(3200, picked.getY());
		assertNull("once only", sync.takeListSelection());
	}

	@Test
	public void aMapPickedInTheListThatMovesTheGameMapIsNotReportedAsAStayedPut()
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(false, spot(3200, 3200), true);
		sync.update(true, spot(3200, 3200), true);
		sync.update(false, spot(3200, 3200), true);
		NativeMapSync.Spot moved = settle(sync, false, spot(2500, 4000), true);
		assertEquals(2500, moved.getX());
		assertNull(sync.takeListSelection());
	}
}
