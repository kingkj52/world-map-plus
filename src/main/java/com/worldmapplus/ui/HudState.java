package com.worldmapplus.ui;

import com.worldmapplus.*;
import javax.inject.*;
import lombok.*;

/**
 * Whether the floating map is showing and whether the mouse passes through it.
 * <p>
 * Flipped by the hotkeys, which arrive on the AWT thread, and read by the overlay on the client
 * thread, so both are volatile.
 */
@Singleton
@Getter
public class HudState
{
	private volatile boolean visible;
	private volatile boolean clickThrough;

	@Inject
	public HudState(WorldMapPlusConfig config)
	{
		clickThrough = config.hudStartsClickThrough();
	}

	public void toggleVisible()
	{
		visible = !visible;
	}

	/** Shows the floating map, to open a place on it. */
	public void show()
	{
		visible = true;
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
