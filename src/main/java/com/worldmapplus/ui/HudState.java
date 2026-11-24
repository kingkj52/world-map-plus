package com.worldmapplus.ui;

import com.worldmapplus.WorldMapPlusConfig;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Whether the floating map is showing and whether the mouse passes through it.
 * <p>
 * Flipped by the hotkeys, which arrive on the AWT thread, and read by the overlay on the client
 * thread, so both are volatile.
 */
@Singleton
public class HudState
{
	private volatile boolean visible;
	private volatile boolean clickThrough;

	@Inject
	public HudState(WorldMapPlusConfig config)
	{
		clickThrough = config.hudStartsClickThrough();
	}

	public boolean isVisible()
	{
		return visible;
	}

	public boolean isClickThrough()
	{
		return clickThrough;
	}

	public void toggleVisible()
	{
		visible = !visible;
	}

	public void toggleClickThrough()
	{
		clickThrough = !clickThrough;
	}

	/** Sets whether the mouse passes through the floating map, as the setting does when it is changed. */
	public void setClickThrough(boolean through)
	{
		clickThrough = through;
	}

	/** Hides the floating map, for plugin shutdown. */
	public void hide()
	{
		visible = false;
	}
}
