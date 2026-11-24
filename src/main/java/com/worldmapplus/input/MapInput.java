package com.worldmapplus.input;

/**
 * One mouse event, recorded on the AWT thread to be applied on the render thread.
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
	/** Wheel rotation in notches for a wheel event, otherwise the mouse button number. */
	public final double amount;
	public final long time;
	/**
	 * False for a mouse event this map saw but left for the game, which happens while the map is lined up with the game's
	 * own and the game is the one moving and zooming it. This map then only uses it to tell where a click landed.
	 */
	public final boolean taken;

	private MapInput(Kind kind, int x, int y, double amount, long time, boolean taken)
	{
		this.kind = kind;
		this.x = x;
		this.y = y;
		this.amount = amount;
		this.time = time;
		this.taken = taken;
	}

	private MapInput(Kind kind, int x, int y, double amount, long time)
	{
		this(kind, x, y, amount, time, true);
	}

	public static MapInput wheel(int x, int y, double rotation, long time)
	{
		return wheel(x, y, rotation, time, true);
	}

	public static MapInput wheel(int x, int y, double rotation, long time, boolean taken)
	{
		return new MapInput(Kind.WHEEL, x, y, rotation, time, taken);
	}

	public static MapInput press(int x, int y, int button, long time)
	{
		return press(x, y, button, time, true);
	}

	public static MapInput press(int x, int y, int button, long time, boolean taken)
	{
		return new MapInput(Kind.PRESS, x, y, button, time, taken);
	}

	public static MapInput drag(int x, int y, long time)
	{
		return drag(x, y, time, true);
	}

	public static MapInput drag(int x, int y, long time, boolean taken)
	{
		return new MapInput(Kind.DRAG, x, y, 0, time, taken);
	}

	public static MapInput release(int x, int y, int button, long time)
	{
		return release(x, y, button, time, true);
	}

	public static MapInput release(int x, int y, int button, long time, boolean taken)
	{
		return new MapInput(Kind.RELEASE, x, y, button, time, taken);
	}

	public static MapInput typed(char c, long time)
	{
		return new MapInput(Kind.TYPED, 0, 0, c, time);
	}

	public static MapInput key(int keyCode, long time)
	{
		return new MapInput(Kind.KEY, 0, 0, keyCode, time);
	}

	public static MapInput gamePress(int x, int y, long time)
	{
		return new MapInput(Kind.GAME_PRESS, x, y, 0, time);
	}

	public static MapInput outsidePress(long time)
	{
		return outsidePress(0, 0, time);
	}

	/** As {@link #outsidePress(long)}, with where the press was, so a click on the chat area can be told from one elsewhere. */
	public static MapInput outsidePress(int x, int y, long time)
	{
		return new MapInput(Kind.OUTSIDE_PRESS, x, y, 0, time);
	}

	/** The character of a typed event. */
	public char character()
	{
		return (char) (int) amount;
	}

	/** The key code of a key event. */
	public int keyCode()
	{
		return (int) amount;
	}

	/** The mouse button of a press or release. */
	public int button()
	{
		return (int) amount;
	}
}
