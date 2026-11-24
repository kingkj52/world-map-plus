package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class NativeMapDriveTest
{
	private static NativeMapSync.Spot spot(int x, int y)
	{
		return new NativeMapSync.Spot(x, y);
	}

	/** A sync that has seen the game's map at a place and taken it as where it is. */
	private static NativeMapSync started(NativeMapSync.Spot at)
	{
		NativeMapSync sync = new NativeMapSync();
		sync.update(false, at, true);
		return sync;
	}

	private static NativeMapSync.Spot steadyDrive(NativeMapSync sync, boolean wanted, NativeMapSync.Spot native_, double x,
		double y)
	{
		NativeMapSync.Spot sent = null;
		for (int i = 0; i < NativeMapSync.STILL_FRAMES + 4 && sent == null; i++)
		{
			sync.update(false, native_, true);
			sent = sync.drive(wanted, native_, x, y);
		}
		return sent;
	}

	@Test
	public void theGameMapIsSentAfterThisMapOnceItHasStoppedMovingAndIsFarOff()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		NativeMapSync.Spot sent = steadyDrive(sync, true, spot(3222, 3218), 3300.4, 3260.6);
		assertNotNull(sent);
		assertEquals(spot(3300, 3261), sent);
	}

	@Test
	public void nothingIsSentWhileThisMapIsStillMoving()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		for (int i = 0; i < 60; i++)
		{
			sync.update(false, spot(3222, 3218), true);
			assertNull(sync.drive(true, spot(3222, 3218), 3300 + i * 2.0, 3260));
		}
	}

	@Test
	public void nothingIsSentWhenTheGameMapIsCloseEnough()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNull(steadyDrive(sync, true, spot(3222, 3218), 3226, 3220));
	}

	@Test
	public void nothingIsSentWhenNothingNeedsIt()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNull(steadyDrive(sync, false, spot(3222, 3218), 3300, 3260));
	}

	@Test
	public void whileTheGameMapIsOnItsWayItIsNotSentAgainNorFollowed()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNotNull(steadyDrive(sync, true, spot(3222, 3218), 3300, 3260));
		// It glides there over many frames. Nothing is sent and this map is not told to follow it.
		for (int i = 0; i < 50; i++)
		{
			NativeMapSync.Spot on = spot(3222 + i, 3218 + i / 2);
			assertNull(sync.update(false, on, true));
			assertNull(sync.drive(true, on, 3300, 3260));
		}
		// It arrives, a tile off. That is not a move to follow either, and it is not sent again.
		for (int i = 0; i < 30; i++)
		{
			assertNull(sync.update(false, spot(3299, 3262), true));
			assertNull(sync.drive(true, spot(3299, 3262), 3300, 3260));
		}
	}

	@Test
	public void aMoveSomeoneElseMakesIsFollowedAndNotUndone()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		NativeMapSync.Spot followed = null;
		double ourX = 3222;
		double ourY = 3218;
		for (int i = 0; i < NativeMapSync.STILL_FRAMES + 40; i++)
		{
			NativeMapSync.Spot found = sync.update(false, spot(2900, 3500), true);
			if (found != null)
			{
				followed = found;
				// This map goes where it was told to follow.
				ourX = found.getX();
				ourY = found.getY();
			}
			assertNull("this map does not send the game map back", sync.drive(true, spot(2900, 3500), ourX, ourY));
		}
		assertEquals(spot(2900, 3500), followed);
	}

	@Test
	public void aSendThatNeverArrivesIsGivenUpAndTheGameMapLeftWhereItEnded()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNotNull(steadyDrive(sync, true, spot(3222, 3218), 3300, 3260));
		// The game's map ends up somewhere else, and stays there.
		NativeMapSync.Spot ended = spot(3240, 3230);
		for (int i = 0; i < NativeMapSync.EXPECT_FRAMES + 20; i++)
		{
			sync.update(false, ended, true);
			sync.drive(true, ended, 3300, 3260);
		}
		for (int i = 0; i < NativeMapSync.QUIET_FRAMES + 20; i++)
		{
			assertNull("it is not followed to where it ended", sync.update(false, ended, true));
		}
	}

	@Test
	public void nothingIsSentWhileTheGameMapsListIsOpen()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		for (int i = 0; i < 40; i++)
		{
			sync.update(true, spot(3222, 3218), true);
			assertNull(sync.drive(true, spot(3222, 3218), 3300, 3260));
		}
	}

	@Test
	public void aSendThatLandsShortOfItsTargetIsAcceptedAndNeverFollowedBack()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNotNull(steadyDrive(sync, true, spot(3222, 3218), 3300, 3260));
		// The game's map moves part of the way and stops, ten tiles short of where it was sent.
		NativeMapSync.Spot short_ = spot(3290, 3252);
		for (int i = 0; i < 300; i++)
		{
			assertNull("this map is not taken back to where the game's map is", sync.update(false, short_, true));
			sync.drive(true, short_, 3300, 3260);
		}
	}

	@Test
	public void aSmallShiftOfTheGameMapIsNotAJumpToFollow()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		for (int i = 0; i < 100; i++)
		{
			assertNull(sync.update(false, spot(3222 + (i / 20), 3218), true));
		}
	}

	@Test
	public void aLongJumpByAnotherPluginIsStillFollowed()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		NativeMapSync.Spot followed = null;
		for (int i = 0; i < NativeMapSync.STILL_FRAMES + 3 && followed == null; i++)
		{
			followed = sync.update(false, spot(3222 + 40, 3218), true);
		}
		assertEquals(spot(3262, 3218), followed);
	}

	@Test
	public void aFarMoveSoonAfterASendIsNotFollowedAndStopsFurtherSends()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNotNull(steadyDrive(sync, true, spot(3222, 3218), 3300, 3260));
		// The game's map arrives, then is somewhere far away a few seconds later, which is not what was sent.
		for (int i = 0; i < 30; i++)
		{
			sync.update(false, spot(3300, 3260), true);
			sync.drive(true, spot(3300, 3260), 3300, 3260);
		}
		for (int i = 0; i < NativeMapSync.STILL_FRAMES + 10; i++)
		{
			assertNull("this map does not jump", sync.update(false, spot(3600, 3500), true));
			assertNull("and does not send again", sync.drive(true, spot(3600, 3500), 3350, 3300));
		}
		for (int i = 0; i < 100; i++)
		{
			sync.update(false, spot(3600, 3500), true);
			assertNull(sync.drive(true, spot(3600, 3500), 3400, 3300));
		}
	}

	@Test
	public void twoSendsAreNeverCloserThanTheLeastGap()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNotNull(steadyDrive(sync, true, spot(3222, 3218), 3300, 3260));
		// It arrives at once, and this map is taken far away and kept still straight after.
		int quiet = 0;
		NativeMapSync.Spot second = null;
		for (int i = 0; i < NativeMapSync.MIN_SEND_FRAMES * 2 && second == null; i++)
		{
			sync.update(false, spot(3300, 3260), true);
			second = sync.drive(true, spot(3300, 3260), 3500, 3400);
			quiet = second == null ? quiet + 1 : quiet;
		}
		assertNotNull("it is sent in the end", second);
		assertEquals(spot(3500, 3400), second);
		assertEquals(true, quiet >= NativeMapSync.MIN_SEND_FRAMES - 8);
	}

	@Test
	public void afterAResetNothingIsSentUntilTheGameMapHasBeenSeenAgain()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		sync.reset();
		for (int i = 0; i < 40; i++)
		{
			assertNull(sync.drive(true, null, 3300, 3260));
		}
		sync.update(false, spot(3222, 3218), true);
		assertNotNull(steadyDrive(sync, true, spot(3222, 3218), 3300, 3260));
	}

	@Test
	public void aFarMoveLongAfterASendIsFollowed()
	{
		NativeMapSync sync = started(spot(3222, 3218));
		assertNotNull(steadyDrive(sync, true, spot(3222, 3218), 3300, 3260));
		for (int i = 0; i < NativeMapSync.SUSPECT_FRAMES + 50; i++)
		{
			sync.update(false, spot(3300, 3260), true);
			sync.drive(true, spot(3300, 3260), 3300, 3260);
		}
		NativeMapSync.Spot followed = null;
		for (int i = 0; i < NativeMapSync.STILL_FRAMES + 3 && followed == null; i++)
		{
			followed = sync.update(false, spot(3600, 3500), true);
		}
		assertEquals(spot(3600, 3500), followed);
	}
}
