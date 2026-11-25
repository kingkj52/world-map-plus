package com.worldmapplus.ui;

import com.worldmapplus.theme.*;
import java.awt.*;

/**
 * Fades something in when it opens. The start is the time of the first frame drawn after {@link #restart}, so a popup made
 * while the map was not being drawn does not appear half faded. With animations off the alpha is always 1, and a finished
 * fade is exactly 1, so the picture is the one an instant open draws.
 */
final class Appear
{
	private long since = Long.MIN_VALUE;

	/** Called when the thing opens or closes. The next frame starts the fade. */
	void restart()
	{
		since = Long.MIN_VALUE;
	}

	/** The opacity to draw with in the frame at {@code now}. */
	float alpha(long now)
	{
		if (!Motion.fades())
		{
			return 1f;
		}
		if (since == Long.MIN_VALUE)
		{
			since = now;
		}
		double t = Motion.progress(since, now, Motion.FADE_MILLIS);
		return t >= 1 ? 1f : (float) Motion.ease(t);
	}

	/** True while a fade is still going, so the caller knows another frame is wanted. */
	boolean moving(long now)
	{
		return Motion.fades() && since != Long.MIN_VALUE && now - since < Motion.FADE_MILLIS;
	}

	/**
	 * Sets the opacity for the frame; returns what to hand back to {@link #end}, or null when nothing was changed (the
	 * ordinary case: no allocation).
	 */
	Composite begin(Graphics2D g, long now)
	{
		float a = alpha(now);
		if (a >= 1f)
		{
			return null;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(a));
		return old;
	}

	static void end(Graphics2D g, Composite old)
	{
		if (old != null)
		{
			g.setComposite(old);
		}
	}
}
