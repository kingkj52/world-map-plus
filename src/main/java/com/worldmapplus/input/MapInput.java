package com.worldmapplus.input;

/**
 * One mouse or key event, recorded on the AWT thread to be applied on the render thread.
 * <p>
 * The viewer's state belongs to the render thread. Input arrives on a different thread, so it is
 * copied into these small immutable records and queued rather than touching that state directly.
 */
public final class MapInput
{
	public enum Kind
	{
		WHEEL,
		PRESS,
		DRAG,
		RELEASE,
		/** A character typed while the search field has the keyboard. */
		TYPED,
		/** A non-character key pressed while the search field has the keyboard. */
		KEY,
		/** A mouse press outside the map while the search field has the keyboard, which ends the search. */
		OUTSIDE_PRESS,
		/** A press on one of the game's own controls inside the map's area. The game still gets it. */
		GAME_PRESS
	}

	public final Kind kind;
	public final int x;
	public final int y;
	/** Wheel rotation in notches for a wheel event, the mouse button, the character typed or the key code. */
	public final double amount;
	public final long time;
	/**
	 * False for a mouse event this map saw but left for the game, which happens while the map is lined up with the game's
	 * own and the game is the one moving and zooming it. This map then only uses it to tell where a click landed.
	 */
	public final boolean taken;

	public MapInput(Kind kind, int x, int y, double amount, long time, boolean taken)
	{
		this.kind = kind;
		this.x = x;
		this.y = y;
		this.amount = amount;
		this.time = time;
		this.taken = taken;
	}

	/** The character of a typed event. */
	public char character()
	{
		return (char) code();
	}

	/** The key code of a key event, or the mouse button of a press or release. */
	public int code()
	{
		return (int) amount;
	}
}
