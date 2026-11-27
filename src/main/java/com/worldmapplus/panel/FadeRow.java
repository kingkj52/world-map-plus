package com.worldmapplus.panel;

import com.worldmapplus.theme.*;
import java.awt.*;
import javax.swing.*;

/**
 * A row of the side panel that warms up while the pointer is over it and cools down after, and can glow for a moment when it is new,
 * as the rows of the game client's own panels do. It warms with the plugin's one fade ({@link Motion}), driven by a timer on the
 * interface thread that stops by itself once the row has settled, so a row at rest costs nothing. It is painted as one of the theme's
 * cards, with an accent bar down its left edge.
 */
final class FadeRow extends PanelTheme.Card
{
	private static final int FRAME_MILLIS = 30;

	/** How long a glow takes to fade away. */
	private static final long GLOW_MILLIS = 860;

	private final Color idle;
	private final Color lit;
	private final Color glow;
	private final Motion fade = new Motion();
	private boolean hover;
	private long glowSince = Long.MIN_VALUE;
	private final Timer timer = new Timer(FRAME_MILLIS, e -> step(System.currentTimeMillis()));

	FadeRow(LayoutManager layout, Color idle, Color lit, Color glow, Color bar)
	{
		super(layout, idle, bar);
		this.idle = idle;
		this.lit = lit;
		this.glow = glow;
	}

	/** Warms the row while the pointer is on it. */
	void hover(boolean on)
	{
		// Brings the fade up to now first, so the change starts from here (the first one too).
		fade.level(this, hover, System.currentTimeMillis());
		hover = on;
		timer.start();
	}

	/** Makes the row glow for a moment, to show it has just arrived. */
	void flash()
	{
		glowSince = System.currentTimeMillis();
		timer.start();
	}

	/** One frame of the row's fade at {@code now}: the timer calls it, and tests. */
	void step(long now)
	{
		double warm = fade.level(this, hover, now);
		double pulse = Motion.on() ? 1 - Motion.progress(glowSince, now, GLOW_MILLIS) : 0;
		setFill(Motion.mix(Motion.mix(idle, lit, warm), glow, pulse * 0.35));
		if (warm == (hover ? 1 : 0) && pulse == 0)
		{
			timer.stop();
		}
	}

	@Override
	public void removeNotify()
	{
		timer.stop();
		super.removeNotify();
	}
}
