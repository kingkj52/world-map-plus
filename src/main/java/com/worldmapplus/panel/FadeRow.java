package com.worldmapplus.panel;

import java.awt.*;
import javax.swing.*;

/**
 * A row of the side panel that warms up while the pointer is over it and cools down after, and can glow for a moment
 * when it is new, as the rows of the game client's own panels do. The colour eases from frame to frame on the
 * interface thread and the timer stops by itself once the row has settled, so a row at rest costs nothing. It is painted as
 * one of the theme's cards, with an accent bar down its left edge.
 */
final class FadeRow extends PanelTheme.Card
{
	private static final int FRAME_MILLIS = 30;

	/** How much of the way to its target the hover moves each frame. */
	private static final double HOVER_RATE = 0.28;

	/** How much a glow fades each frame, so one lasts about a second. */
	private static final double GLOW_RATE = 0.035;

	private final Color idle;
	private final Color lit;
	private final Color glow;
	private double hover;
	private double hoverTarget;
	private double pulse;
	private final Timer timer = new Timer(FRAME_MILLIS, e -> step());

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
		hoverTarget = on ? 1 : 0;
		timer.start();
	}

	/** Makes the row glow for a moment, to show it has just arrived. */
	void flash()
	{
		pulse = 1;
		timer.start();
	}

	private void step()
	{
		double gap = hoverTarget - hover;
		hover = Math.abs(gap) < 0.03 ? hoverTarget : hover + gap * HOVER_RATE;
		pulse = Math.max(0, pulse - GLOW_RATE);
		setFill(mix(mix(idle, lit, hover), glow, pulse * 0.35));
		if (hover == hoverTarget && pulse == 0)
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

	/** The row's colour right now, for tests. */
	Color shown()
	{
		return getFill();
	}

	private static Color mix(Color from, Color to, double amount)
	{
		double a = Math.max(0, Math.min(1, amount));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * a),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * a),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * a));
	}
}
