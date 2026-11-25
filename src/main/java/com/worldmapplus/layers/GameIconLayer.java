package com.worldmapplus.layers;

import com.worldmapplus.map.*;
import com.worldmapplus.unlock.*;
import com.worldmapplus.view.*;
import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import javax.annotation.*;
import lombok.*;

/**
 * Draws the icons of the game's own map list over the tiles, each at a size that follows the zoom ({@link GameIconScale}), and
 * answers where the mouse is on them.
 * <p>
 * One pass, {@link #layout}, decides what is drawn and where: the icons of the map and floor in view, the ones whose category is
 * showing, the picture for the zoom, and, when pictures would overlap, only the first of each crowd (the more important
 * categories first, and farther out only those). {@link #paint} draws that result and {@link #hit} tests the mouse against that same
 * result, so a hit area is always the drawn picture with a margin and never a different shape from somewhere else in the code.
 * The result is kept while the view, the floor and the set of shown categories stay the same.
 * <p>
 * What the player has done is applied after all of that. A finished quest, or an icon the player certainly cannot use yet, is chosen by
 * the same fixed order as every other icon and keeps its cell, and then it is drawn dimmed with a padlock or left undrawn. So it never
 * lets another icon take its cell: progress changes how the winner of a cell looks and which icon wins stays as it was.
 * <p>
 * In steady state nothing is allocated: the arrays are reused and grow only when a view holds more icons than any before it, the
 * scaled pictures are cached per picture and size.
 */
@RequiredArgsConstructor
public final class GameIconLayer
{
	@Getter
	private final GameIconSet set;
	@Getter
	private final GameIconSprites sprites;

	/** Categories (by the number the data gives them) that are switched off. */
	private boolean[] hidden = new boolean[0];
	private int hiddenVersion;
	private String hiddenKey = "";

	// What the last layout was made for.
	private boolean laid;
	private int keyMap;
	private int keyFloor;
	private int keyVersion;
	private int keyReveal = -2;
	private int keyLeft;
	private int keyTop;
	private int keyWidth;
	private int keyHeight;
	private double keyCenterX;
	private double keyCenterY;
	private double keyZoom;

	// The result of the layout, in the order they are drawn.
	private GameIconSet.MapIcons drawnOn;
	private int[] found = new int[256];
	private int[] drawnIndex = new int[256];
	private int[] drawnLeft = new int[256];
	private int[] drawnTop = new int[256];
	private int drawnCount;
	private int drawnSize;

	// Scratch for the crowd thinning: the icons that may be drawn, and per cell of the world the best of them.
	private int[] candidate = new int[256];
	private long[] candidateScore = new long[256];
	private long[] cellKey = new long[1024];
	private long[] cellBest = new long[1024];

	/** The categories whose icons are all drawn for a while whatever the zoom, and until when (milliseconds of the clock). */
	private int[] revealCategories = new int[0];
	private long revealUntil;
	private int revealVersion;

	/** How an icon looks to this player. */
	static final byte NORMAL = 0;
	static final byte LOCKED = 1;
	static final byte HIDDEN = 2;
	/** A quest start with a dot for how far the player is: not started, started, finished. */
	static final byte QUEST_NOT_STARTED = 3;
	static final byte QUEST_STARTED = 4;
	static final byte QUEST_FINISHED = 5;

	private UnlockView unlocks;
	private boolean hideFinished;
	private boolean markMissing;
	private boolean onlyUsable;
	private boolean colourQuests;
	private int optionsVersion;
	/** The look of the icon in each slot of the last layout, and what that was worked out for. */
	private byte[] slotLook = new byte[256];
	private int layoutGeneration;
	private int lookGeneration = -1;
	private int lookUnlocks = -1;
	private int lookOptions = -1;

	/**
	 * Says what the player's progress does to the icons: finished quests hidden, icons that cannot be used yet marked, or those hidden too.
	 * With all three off, or no view of the progress, every icon looks as it always did.
	 */
	public void setUnlocks(UnlockView view, boolean hideFinishedQuests, boolean markMissing, boolean onlyUsable)
	{
		setUnlocks(view, hideFinishedQuests, markMissing, onlyUsable, false);
	}

	/** As above, and whether a quest start carries a dot for how far the player is with it (red, yellow, green). */
	public void setUnlocks(UnlockView view, boolean hideFinishedQuests, boolean markMissing, boolean onlyUsable, boolean colourQuests)
	{
		if (view != unlocks || hideFinishedQuests != hideFinished || markMissing != this.markMissing || onlyUsable != this.onlyUsable
			|| colourQuests != this.colourQuests)
		{
			unlocks = view;
			hideFinished = hideFinishedQuests;
			this.markMissing = markMissing;
			this.onlyUsable = onlyUsable;
			this.colourQuests = colourQuests;
			optionsVersion++;
		}
	}

	/** True when any of the settings that follow the player's progress is on. */
	public boolean followsProgress()
	{
		return unlocks != null && (hideFinished || markMissing || onlyUsable || colourQuests);
	}

	/**
	 * Works out how each icon of the last layout looks to the player, again only when the layout, the player's progress or the settings
	 * have changed since. Allocates nothing.
	 */
	private void settleLooks()
	{
		boolean follow = followsProgress();
		int progress = follow ? unlocks.version() : 0;
		if (lookGeneration == layoutGeneration && lookUnlocks == progress && lookOptions == optionsVersion)
		{
			return;
		}
		lookGeneration = layoutGeneration;
		lookUnlocks = progress;
		lookOptions = optionsVersion;
		if (slotLook.length < drawnCount)
		{
			slotLook = new byte[Math.max(drawnCount, slotLook.length * 2)];
		}
		for (int slot = 0; slot < drawnCount; slot++)
		{
			slotLook[slot] = follow ? lookOf(drawnOn.need(drawnIndex[slot])) : NORMAL;
		}
	}

	private byte lookOf(Need need)
	{
		if (need == null)
		{
			return NORMAL;
		}
		if (hideFinished && need.startsQuest() != null && unlocks.finished(need))
		{
			return HIDDEN;
		}
		if ((markMissing || onlyUsable) && need.hasAlternatives() && unlocks.lacks(need))
		{
			return onlyUsable ? HIDDEN : LOCKED;
		}
		if (colourQuests && need.startsQuest() != null)
		{
			switch (unlocks.questProgress(need))
			{
				case UnlockView.QUEST_NOT_STARTED:
					return QUEST_NOT_STARTED;
				case UnlockView.QUEST_STARTED:
					return QUEST_STARTED;
				case UnlockView.QUEST_FINISHED:
					return QUEST_FINISHED;
				default:
					return NORMAL;
			}
		}
		return NORMAL;
	}

	/** How the icon in a slot of the last layout looks: normal, locked (dimmed with a padlock), hidden, or a quest start with a dot. */
	byte lookAt(int slot)
	{
		settleLooks();
		return slot < 0 || slot >= drawnCount ? NORMAL : slotLook[slot];
	}

	/** True when the icon in a slot is drawn dimmed with a padlock. */
	public boolean isLockedAt(int slot)
	{
		return lookAt(slot) == LOCKED;
	}

	/** What the icon in a slot asks of the player, or null. */
	@Nullable
	public Need needAt(int slot)
	{
		return slot < 0 || slot >= drawnCount ? null : drawnOn.need(drawnIndex[slot]);
	}

	/**
	 * Makes the icons of some categories all show whatever the zoom until a time, so a kind of icon a player asked about can be found
	 * from far out. They win a crowded spot against every other icon. No categories puts the reveal out.
	 */
	public void reveal(int[] categories, long untilMillis)
	{
		this.revealCategories = categories.clone();
		this.revealUntil = untilMillis;
		this.revealVersion++;
	}

	/** True while a reveal is on. */
	public boolean revealing()
	{
		return revealCategories.length > 0 && System.currentTimeMillis() < revealUntil;
	}

	private boolean isRevealed(int category)
	{
		for (int revealed : revealCategories)
		{
			if (revealed == category)
			{
				return true;
			}
		}
		return false;
	}

	/** Shows or hides every icon of a category (the number the data gives it). All are shown to begin with. */
	public void setCategoryShown(int category, boolean shown)
	{
		if (category < 0)
		{
			return;
		}
		if (category >= hidden.length)
		{
			if (shown)
			{
				return;
			}
			hidden = Arrays.copyOf(hidden, category + 1);
		}
		if (hidden[category] != !shown)
		{
			hidden[category] = !shown;
			hiddenVersion++;
		}
	}

	/** The setting the hidden categories were last made from, so a caller can tell whether it has changed since. */
	public String hiddenKey()
	{
		return hiddenKey;
	}

	public void setHiddenKey(String key)
	{
		this.hiddenKey = key;
	}

	public boolean isCategoryShown(int category)
	{
		return category < 0 || category >= hidden.length || !hidden[category];
	}

	/** Draws the icons of a map and floor. */
	public void paint(Graphics2D g, ViewTransform view, @Nullable MapInfo map, int floor)
	{
		if (map == null)
		{
			return;
		}
		layout(view, map.getId(), floor);
		settleLooks();
		// Most important last, so an icon that touches another is the one that matters more.
		for (int i = drawnCount - 1; i >= 0; i--)
		{
			byte look = slotLook[i];
			if (look == HIDDEN)
			{
				continue;
			}
			int index = drawnOn.picture(drawnIndex[i]);
			BufferedImage picture = look == LOCKED ? sprites.locked(index, drawnSize)
				: look >= QUEST_NOT_STARTED ? sprites.questDot(index, drawnSize, look - QUEST_NOT_STARTED)
				: sprites.get(index, drawnSize);
			if (picture != null)
			{
				g.drawImage(picture, drawnLeft[i], drawnTop[i], null);
			}
		}
	}

	/**
	 * Works out what is drawn. Does nothing when the view, floor, shown categories and revealed category are those of the last call.
	 * The floor is the one being looked at: the ground floor's view shows every icon of the map, an upper floor's view shows that
	 * floor's icons (see {@link GameIconSet.MapIcons#shownOn}).
	 * <p>
	 * What is drawn depends only on the zoom, never on where the view is, so panning moves the same icons and none flicker. Minor
	 * categories fade out as the map zooms out in a fixed order, and where pictures would overlap the world is cut into cells of a
	 * whole number of doublings of a tile and each cell keeps one icon: the best by priority and then by place in the data. The
	 * cells are laid on the world, not on the screen, and the icons of the cells that touch the view are all looked at, so the
	 * winner of a cell is the same wherever the view is.
	 */
	public void layout(ViewTransform view, int mapId, int floor)
	{
		boolean revealOn = revealing();
		int reveal = revealOn ? revealVersion : 0;
		if (laid && keyMap == mapId && keyFloor == floor && keyVersion == hiddenVersion && keyReveal == reveal
			&& keyLeft == view.getLeft() && keyTop == view.getTop() && keyWidth == view.getWidth() && keyHeight == view.getHeight()
			&& keyCenterX == view.getCenterX() && keyCenterY == view.getCenterY() && keyZoom == view.getPixelsPerTile())
		{
			return;
		}
		laid = true;
		layoutGeneration++;
		keyMap = mapId;
		keyFloor = floor;
		keyVersion = hiddenVersion;
		keyReveal = reveal;
		keyLeft = view.getLeft();
		keyTop = view.getTop();
		keyWidth = view.getWidth();
		keyHeight = view.getHeight();
		keyCenterX = view.getCenterX();
		keyCenterY = view.getCenterY();
		keyZoom = view.getPixelsPerTile();
		drawnCount = 0;
		drawnOn = set.on(mapId);
		drawnSize = GameIconScale.drawnSize(keyZoom, GameIconSprites.SIZE);
		if (drawnOn == null || drawnSize == 0)
		{
			return;
		}
		int cellTiles = GameIconScale.cellTiles(keyZoom, drawnSize);
		// A picture can overhang the edge of the view by half its side, and a cell that touches the view is looked at whole.
		double reach = (drawnSize + GameIconScale.HIT_MARGIN * 2) / keyZoom + cellTiles;
		int n = drawnOn.query(view.minWorldX() - reach, view.minWorldY() - reach, view.maxWorldX() + reach, view.maxWorldY() + reach,
			found);
		if (n > found.length)
		{
			found = new int[Math.max(n, found.length * 2)];
			n = drawnOn.query(view.minWorldX() - reach, view.minWorldY() - reach, view.maxWorldX() + reach, view.maxWorldY() + reach,
				found);
		}
		if (candidate.length < n)
		{
			candidate = new int[Math.max(n, candidate.length * 2)];
			candidateScore = new long[candidate.length];
		}
		double minorShare = GameIconScale.minorShare(keyZoom);
		int count = 0;
		for (int k = 0; k < n; k++)
		{
			int i = found[k];
			if (!drawnOn.shownOn(i, floor) || !isCategoryShown(drawnOn.category(i)))
			{
				continue;
			}
			boolean revealed = revealOn && isRevealed(drawnOn.category(i));
			int priority = revealed ? -1 : drawnOn.priority(i);
			if (!revealed && priority >= GameIconScale.MINOR_PRIORITY && fixedShare(i) >= minorShare)
			{
				continue;
			}
			candidate[count] = i;
			candidateScore[count] = ((long) (priority + 1) << 32) | i;
			count++;
		}
		boolean thin = cellTiles > 0;
		int mask = 0;
		if (thin)
		{
			int size = Integer.highestOneBit(Math.max(16, count * 2)) << 1;
			if (cellKey.length < size)
			{
				cellKey = new long[size];
				cellBest = new long[size];
			}
			Arrays.fill(cellKey, 0, size, EMPTY);
			mask = size - 1;
			for (int c = 0; c < count; c++)
			{
				long key = cellOf(drawnOn.x(candidate[c]), drawnOn.y(candidate[c]), cellTiles);
				int slot = cellSlot(key, mask);
				while (cellKey[slot] != EMPTY && cellKey[slot] != key)
				{
					slot = (slot + 1) & mask;
				}
				if (cellKey[slot] == EMPTY)
				{
					cellKey[slot] = key;
					cellBest[slot] = candidateScore[c];
				}
				else if (candidateScore[c] < cellBest[slot])
				{
					cellBest[slot] = candidateScore[c];
				}
			}
		}
		double half = drawnSize / 2.0;
		for (int c = 0; c < count; c++)
		{
			int i = candidate[c];
			if (thin)
			{
				long key = cellOf(drawnOn.x(i), drawnOn.y(i), cellTiles);
				int slot = cellSlot(key, mask);
				while (cellKey[slot] != key)
				{
					slot = (slot + 1) & mask;
				}
				if (cellBest[slot] != candidateScore[c])
				{
					continue;
				}
			}
			double sx = view.screenX(drawnOn.x(i));
			double sy = view.screenY(drawnOn.y(i));
			int left = (int) Math.round(sx - half);
			int top = (int) Math.round(sy - half);
			if (left + drawnSize < view.getLeft() || top + drawnSize < view.getTop()
				|| left > view.getLeft() + view.getWidth() || top > view.getTop() + view.getHeight())
			{
				continue;
			}
			if (drawnCount == drawnIndex.length)
			{
				int bigger = drawnCount * 2;
				drawnIndex = Arrays.copyOf(drawnIndex, bigger);
				drawnLeft = Arrays.copyOf(drawnLeft, bigger);
				drawnTop = Arrays.copyOf(drawnTop, bigger);
			}
			drawnIndex[drawnCount] = i;
			drawnLeft[drawnCount] = left;
			drawnTop[drawnCount] = top;
			drawnCount++;
		}
	}

	private static final long EMPTY = Long.MIN_VALUE;

	/** The cell of the world a place falls in, as one number, for cells {@code cellTiles} tiles wide. */
	private static long cellOf(double x, double y, int cellTiles)
	{
		long cx = (long) Math.floor(x / cellTiles);
		long cy = (long) Math.floor(y / cellTiles);
		return (cx << 32) ^ (cy & 0xFFFFFFFFL);
	}

	private static int cellSlot(long key, int mask)
	{
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h >>> 40) & mask;
	}

	/**
	 * A fixed number from 0 up to but not including 1 for an icon of the data, spread evenly over the icons, so minor icons leave the
	 * map in the same order every time.
	 */
	private static double fixedShare(int index)
	{
		int h = index * 0x9E3779B1;
		h ^= h >>> 15;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h & 0xFFFFFF) / (double) 0x1000000;
	}

	/** How many icons the last layout drew. */
	public int drawnCount()
	{
		return drawnCount;
	}

	/** The side the last layout drew each icon at, in pixels, or 0 when nothing is drawn. */
	public int drawnSize()
	{
		return drawnCount == 0 ? 0 : drawnSize;
	}

	/** The left edge on the screen of the picture drawn in a slot of the last layout. */
	public int drawnLeft(int slot)
	{
		return drawnLeft[slot];
	}

	/** The top edge on the screen of the picture drawn in a slot of the last layout. */
	public int drawnTop(int slot)
	{
		return drawnTop[slot];
	}

	/** The middle of the picture drawn in a slot, in screen pixels. */
	public double drawnCenterX(int slot)
	{
		return drawnLeft[slot] + drawnSize / 2.0;
	}

	public double drawnCenterY(int slot)
	{
		return drawnTop[slot] + drawnSize / 2.0;
	}

	/** The icons of the map of the last layout, with the index of the one a slot drew. */
	@Nullable
	public GameIconSet.MapIcons drawnOn()
	{
		return drawnOn;
	}

	public int drawnIconIndex(int slot)
	{
		return drawnIndex[slot];
	}

	/** The side of a hit area at the last layout's zoom, in pixels. */
	public int hitSize()
	{
		return GameIconScale.hitSize(drawnSize);
	}

	/**
	 * The slot of the drawn icon whose hit area holds a point, or -1. The hit area is the drawn picture grown by a margin, at least
	 * as big as the least touch size, with the same middle. When several hold the point the one whose middle is nearest wins.
	 */
	public int hit(ViewTransform view, int mapId, int floor, double px, double py)
	{
		layout(view, mapId, floor);
		if (drawnCount == 0)
		{
			return -1;
		}
		settleLooks();
		double reach = GameIconScale.hitSize(drawnSize) / 2.0;
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int slot = 0; slot < drawnCount; slot++)
		{
			if (slotLook[slot] == HIDDEN)
			{
				continue;
			}
			double dx = px - drawnCenterX(slot);
			double dy = py - drawnCenterY(slot);
			if (Math.abs(dx) <= reach && Math.abs(dy) <= reach)
			{
				double d = Math.hypot(dx, dy);
				if (d < bestDistance)
				{
					bestDistance = d;
					best = slot;
				}
			}
		}
		return best;
	}

	/** What the points of interest merged into the icon in a slot add to its card, and the icon's floor, for {@link IconCards}. */
	public List<IconExtra> extrasAt(int slot)
	{
		return slot < 0 || slot >= drawnCount ? Collections.emptyList() : drawnOn.extras(drawnIndex[slot]);
	}

	/** The floor of the icon drawn in a slot of the last layout, or 0. */
	public int floorAt(int slot)
	{
		return slot < 0 || slot >= drawnCount ? 0 : drawnOn.floor(drawnIndex[slot]);
	}

	/** The card answering for the icon in a slot of the last layout, or null when the plugin has nothing to say about it. */
	@Nullable
	public Hotspot cardAt(int slot)
	{
		return slot < 0 || slot >= drawnCount ? null : drawnOn.card(drawnIndex[slot]);
	}
}
