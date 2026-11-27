package com.worldmapplus.typing;

/**
 * Where the game's chat input line is, as read on the client thread once a client tick. Plain numbers the overlay reads on
 * the same thread when it draws the guide just above the line; nothing here refers to the game.
 */
public final class ChatLine
{
	/** True when the line is shown and free, so the guide may draw above it. */
	public boolean usable;

	/** The game shows its "Press Enter to Chat" invitation instead of a line being typed. */
	public boolean idle;

	/** The line's box on the canvas. */
	public int x;
	public int y;
	public int width;
	public int height;
}
