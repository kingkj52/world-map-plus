package com.worldmapplus.ui;

import javax.annotation.*;
import lombok.*;

/**
 * Keeps this plugin's map in step with the game's own map, which is still there underneath it.
 * <p>
 * Two things move the game's map without this plugin's map knowing:
 * <ul>
 * <li>the game's own map list, the drop down at the bottom of the window. It opens inside the map
 * area, which this plugin's map covers, so while it is open this plugin's map steps aside, and when
 * it closes the map is taken to the place that was chosen;</li>
 * <li>other plugins that centre the game's map on something, such as a group member or a quest step.
 * They ask the game's map to move, and this plugin's map follows, so "show on map" works whichever
 * map is drawn.</li>
 * </ul>
 * Either way the game's map glides to its new place over a few frames, so a move only counts once the
 * map has stayed still for a moment.
 * <p>
 * This class holds only the bookkeeping. It reads nothing itself: it is told whether the list is open
 * and where the game's map is looking, and says where to take this plugin's map, if anywhere.
 */
public final class NativeMapSync
{
	/** Frames the game's map must stay still before it is taken to be where it was going. */
	static final int STILL_FRAMES = 8;

	/** A place on the game's map, in world tiles. */
	@Getter
	@RequiredArgsConstructor
	public static final class Spot
	{
		private final int x;
		private final int y;

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Spot && ((Spot) other).x == x && ((Spot) other).y == y;
		}

		@Override
		public int hashCode()
		{
			return 31 * x + y;
		}
	}

	/** How far, in tiles, the game's map may be from this map's centre before it is sent after it. */
	static final double DRIVE_TILES = 8;

	/** How far this map's centre may move in a frame and still count as keeping still, in tiles. */
	static final double STILL_TILES = 0.5;

	/** Frames the game's map is given to arrive where it was sent, before the attempt is given up. */
	static final int EXPECT_FRAMES = 240;

	/** Frames after a send that did not arrive in which a move of the game's map is not followed. */
	static final int QUIET_FRAMES = 240;

	/** How close, in tiles, the game's map must get to where it was sent to count as having arrived. */
	static final int ARRIVED_TILES = 2;

	/**
	 * How far, in tiles, the game's map must settle from where it was before a move of its own counts as a jump to follow.
	 * A smaller change is the game's map shifting a little, or settling a little short of where it was sent, and is never
	 * a reason to move this map, which would then seem to jump about by itself.
	 */
	static final double FOLLOW_MIN_TILES = 12;

	/**
	 * Frames after this map last sent the game's map somewhere in which a move of the game's map is not followed. If one
	 * happens anyway, the game's map is not behaving as expected, and it is no longer sent after this map at all, so this
	 * map can never be taken somewhere by its own doing.
	 */
	static final int SUSPECT_FRAMES = 1200;

	/**
	 * Fewest frames between two sends, however far this map is taken in between. A player who keeps dragging and pausing
	 * would otherwise ask the game's map to move at every pause, and the game's map is given time to settle between asks.
	 */
	static final int MIN_SEND_FRAMES = 45;

	/** True while this plugin's map should stay out of the way. */
	@Getter
	private boolean open;
	private Spot known;
	private Spot candidate;
	private int stillFor;
	private Spot expecting;
	private Spot sentFrom;
	private long suspectUntil = Long.MIN_VALUE;
	private long sentAt = Long.MIN_VALUE;
	private boolean givenUp;
	private Spot settleSpot;
	private int settleFor;
	private long frame;
	private long expectedAt;
	private long quietUntil = Long.MIN_VALUE;
	private double lastX = Double.NaN;
	private double lastY = Double.NaN;
	private int steadyFor;

	/**
	 * Called once per frame.
	 *
	 * @param listOpen whether the game's map list is showing
	 * @param position where the game's map is looking, or null when that cannot be read
	 * @param followJumps whether a move made by another plugin is followed. A move made through the
	 * map list always is.
	 * @return where to take this plugin's map, or null for nowhere
	 */
	@Nullable
	public Spot update(boolean listOpen, @Nullable Spot position, boolean followJumps)
	{
		if (listOpen)
		{
			open = true;
			candidate = null;
			stillFor = 0;
			selectionPending = false;
			if (known == null)
			{
				known = position;
			}
			listUsed = true;
			return null;
		}
		open = false;
		if (position == null)
		{
			return null;
		}
		if (known == null)
		{
			known = position;
			return null;
		}
		if (expecting != null || frame < quietUntil)
		{
			// The game's map is on its way to where this map sent it, or has just failed to get there. Where it
			// is now is not a move of someone else's to follow.
			if (frame < quietUntil)
			{
				known = position;
			}
			candidate = null;
			stillFor = 0;
			return null;
		}
		if (position.equals(known))
		{
			candidate = null;
			stillFor = 0;
			if (listUsed)
			{
				// The list was used and the game's map has not moved yet. It may not move at all: the map picked can be the one
				// the game's map was already on, which is the case when this map is on an area the game's map list does not have.
				selectionPending = true;
				selectionFor = 0;
			}
			else if (selectionPending && ++selectionFor >= STILL_FRAMES)
			{
				selectionPending = false;
				listSelection = position;
			}
			listUsed = false;
			return null;
		}
		selectionPending = false;
		if (position.equals(candidate))
		{
			stillFor++;
		}
		else
		{
			candidate = position;
			stillFor = 0;
		}
		if (stillFor < STILL_FRAMES)
		{
			return null;
		}
		Spot arrived = candidate;
		candidate = null;
		stillFor = 0;
		double jump = Math.hypot(arrived.getX() - known.getX(), arrived.getY() - known.getY());
		known = arrived;
		boolean fromList = listUsed;
		listUsed = false;
		if (!fromList && jump <= FOLLOW_MIN_TILES)
		{
			return null;
		}
		if (!fromList && frame < suspectUntil)
		{
			// Soon after this map sent the game's map somewhere, so this is most likely that, not someone else's move.
			givenUp = true;
			return null;
		}
		return followJumps || fromList ? arrived : null;
	}

	private boolean listUsed;
	private boolean selectionPending;
	private int selectionFor;
	private Spot listSelection;

	/**
	 * Where the game's map is, once, after its map list was used and the game's map stayed where it was for a while. That is a pick of
	 * the map the game's map was already on, which changes nothing to follow, and the caller takes this map there when it is not
	 * already showing that place. Null when there is none.
	 */
	@Nullable
	public Spot takeListSelection()
	{
		Spot spot = listSelection;
		listSelection = null;
		return spot;
	}

	/**
	 * Called once per frame, after {@link #update}. Says where to send the game's map so that it keeps to this map's centre,
	 * or null for nowhere.
	 * <p>
	 * Some plugins put things on the game's map only where that map is looking: a plugin that adds a point for each of its
	 * things within a screen's width of the game map's centre, for instance. This map covers the game's, which then stays
	 * where it was, so those plugins would show nothing anywhere else. Sending the game's map after this one fixes that
	 * without changing how the plugins work.
	 * <p>
	 * It only ever follows the player's own moves of this map. It waits until this map has kept still for a moment, does
	 * nothing while the game's map is somewhere that someone else sent it (that is followed by {@link #update} instead),
	 * and after sending it takes no notice of the game's map until it has arrived, so the two never fight.
	 *
	 * @param wanted whether something needs the game's map to keep up, and the game map may be moved at all. It is true
	 * from the moment any other plugin has been seen with points on the game's map, and stays true for the session, because
	 * such a plugin has no points yet at a place the game's map has not looked at
	 * @param position where the game's map is looking, or null when that cannot be read
	 * @param ourX the world x this map is centred on
	 * @param ourY the world y this map is centred on
	 */
	@Nullable
	public Spot drive(boolean wanted, @Nullable Spot position, double ourX, double ourY)
	{
		frame++;
		boolean moved = Double.isNaN(lastX) || Math.hypot(ourX - lastX, ourY - lastY) > STILL_TILES;
		lastX = ourX;
		lastY = ourY;
		steadyFor = moved ? 0 : steadyFor + 1;
		if (!wanted || position == null || open || givenUp)
		{
			expecting = null;
			return null;
		}
		if (expecting != null)
		{
			if (Math.abs(position.getX() - expecting.getX()) <= ARRIVED_TILES
				&& Math.abs(position.getY() - expecting.getY()) <= ARRIVED_TILES)
			{
				known = position;
				expecting = null;
				candidate = null;
				stillFor = 0;
			}
			else if (sentFrom != null && !position.equals(sentFrom) && settled(position))
			{
				// It moved and came to rest somewhere other than where it was sent, perhaps because it cannot look
				// there. That is where it is, and not a move of someone else's to follow.
				known = position;
				expecting = null;
				candidate = null;
				stillFor = 0;
				quietUntil = frame + STILL_FRAMES * 4;
			}
			else if (frame - expectedAt > EXPECT_FRAMES)
			{
				// It did not get there, perhaps because the game's map cannot look at that place. Leave it be.
				expecting = null;
				known = position;
				quietUntil = frame + QUIET_FRAMES;
			}
			return null;
		}
		if (known == null || !position.equals(known) || steadyFor < STILL_FRAMES
			|| Math.hypot(ourX - position.getX(), ourY - position.getY()) <= DRIVE_TILES)
		{
			return null;
		}
		if (sentAt != Long.MIN_VALUE && frame - sentAt < MIN_SEND_FRAMES)
		{
			return null;
		}
		Spot target = new Spot((int) Math.round(ourX), (int) Math.round(ourY));
		expecting = target;
		expectedAt = frame;
		sentAt = frame;
		sentFrom = position;
		settleSpot = null;
		settleFor = 0;
		suspectUntil = frame + SUSPECT_FRAMES;
		return target;
	}

	/** True once the game's map has stayed at one place for the same number of frames that a move of this map must. */
	private boolean settled(Spot position)
	{
		if (position.equals(settleSpot))
		{
			settleFor++;
		}
		else
		{
			settleSpot = position;
			settleFor = 0;
		}
		return settleFor >= STILL_FRAMES;
	}

	/**
	 * Forget everything, for when the window closes. The game's map may start somewhere else the next
	 * time it opens, and that is not a move to follow, so the next position seen is taken as where it is.
	 */
	public void reset()
	{
		known = null;
		open = false;
		candidate = null;
		stillFor = 0;
		listUsed = false;
		expecting = null;
		sentFrom = null;
		settleSpot = null;
		settleFor = 0;
		suspectUntil = Long.MIN_VALUE;
		quietUntil = Long.MIN_VALUE;
		sentAt = Long.MIN_VALUE;
		lastX = Double.NaN;
		lastY = Double.NaN;
		steadyFor = 0;
	}
}
