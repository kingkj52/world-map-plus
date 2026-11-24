package com.worldmapplus.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * The scroll position of a list that is taller than the room for it, with a thin bar to show and drag it.
 * <p>
 * The owner says how tall the whole list is and how much of it shows; this keeps the offset in pixels between 0 and the
 * difference, turns wheel notches into offsets, and maps the bar's track to the offset and back.
 */
final class Scroller
{
	/** Width of the bar. */
	static final int WIDTH = 8;


	private int total;
	private int view;
	private int offset;

	/** Sets the sizes, in pixels, keeping the offset inside the new range. */
	void set(int totalPixels, int viewPixels)
	{
		total = Math.max(0, totalPixels);
		view = Math.max(0, viewPixels);
		offset = Math.max(0, Math.min(offset, max()));
	}

	/** True when part of the list is out of view. */
	boolean isScrollable()
	{
		return total > view;
	}

	/** The offset of the top of the view from the top of the list. */
	int offset()
	{
		return offset;
	}

	/** The largest offset. */
	int max()
	{
		return Math.max(0, total - view);
	}

	void by(int pixels)
	{
		offset = Math.max(0, Math.min(offset + pixels, max()));
	}

	void reset()
	{
		offset = 0;
	}

	/** Sets the offset so that the thumb is centred on {@code y} of {@code track}. */
	void toTrack(int y, Rectangle track)
	{
		if (!isScrollable())
		{
			return;
		}
		int thumb = thumbHeight(track);
		double room = Math.max(1, track.height - thumb);
		double fraction = Math.max(0, Math.min(1, (y - track.y - thumb / 2.0) / room));
		offset = (int) Math.round(fraction * max());
	}

	private int thumbHeight(Rectangle track)
	{
		if (total <= 0)
		{
			return track.height;
		}
		return Math.max(16, Math.min(track.height, (int) Math.round(track.height * (view / (double) total))));
	}

	/** The thumb inside a track. */
	Rectangle thumb(Rectangle track)
	{
		int height = thumbHeight(track);
		int room = track.height - height;
		int y = track.y + (max() == 0 ? 0 : (int) Math.round(room * (offset / (double) max())));
		return new Rectangle(track.x, y, track.width, height);
	}

	void paint(Graphics2D g, Rectangle track)
	{
		if (!isScrollable())
		{
			return;
		}
		g.setColor(Theme.SCROLL_TRACK);
		g.fillRoundRect(track.x, track.y, track.width, track.height, 4, 4);
		Rectangle thumb = thumb(track);
		g.setColor(Theme.SCROLL_THUMB);
		g.fillRoundRect(thumb.x, thumb.y, thumb.width, thumb.height, 4, 4);
	}
}
