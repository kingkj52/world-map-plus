package com.worldmapplus.ui;

import javax.annotation.*;
import lombok.*;

/**
 * The game's own world map, which is still open underneath this plugin's map. Other plugins draw on that map, and
 * can only draw where it is, so this lets the plugin read where it is looking and move it.
 */
public interface NativeMap
{
	/** Where the game's map is looking and how far in it is zoomed, or null when that cannot be read. */
	@Nullable
	View view();

	/** Asks the game's map to look at a tile, which it glides to over a few frames. */
	void moveTo(int x, int y);

	/** The game's map: the tile at its centre and its zoom in pixels per tile. */
	@Getter
	@RequiredArgsConstructor
	final class View
	{
		private final int x;
		private final int y;
		private final double zoom;
	}
}
