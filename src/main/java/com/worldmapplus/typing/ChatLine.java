package com.worldmapplus.typing;

/**
 * Where the game's chat input line is and how wide its letters are, as read on the client thread once a client tick. Plain
 * numbers the overlay reads on the same thread when it draws; nothing here refers to the game.
 * <p>
 * Letter positions are measured with the game's own font for the line, so a letter of the guide sits on the letter the game
 * draws. {@code typedEdges[i]} is the width of the first {@code i} typed characters, {@code messageEdges[i]} the width of the
 * first {@code i} characters of the message.
 */
public final class ChatLine
{
	/** True when the line is shown and free, so the guide may draw on it. */
	public boolean usable;

	/** The game shows its "Press Enter to Chat" invitation instead of a line being typed. */
	public boolean idle;

	/** The line's text starts at its left edge, so letters can be put exactly on the game's own. */
	public boolean aligned;

	/** The line's box on the canvas. */
	public int x;
	public int y;
	public int width;
	public int height;

	/** Where the typed text starts (after the player's name and the colon) and the baseline of its letters. */
	public int textX;
	public int baseline;

	/** The width of the cursor the game draws after the typed text. */
	public int cursorWidth;

	public final int[] typedEdges = new int[TypingGuide.MAX_TYPED + 1];
	public int typedCount;
	public final int[] messageEdges = new int[TypingGuide.MAX_MESSAGE + 1];
	public int messageCount;

	/** The guide's generation and message version these numbers were read for. */
	public long generation = -1;
	public int messageVersion = -1;

	/** Forgets the line, so nothing is drawn until the next read. */
	public void clear()
	{
		usable = false;
		idle = false;
		typedCount = 0;
		messageCount = 0;
		generation = -1;
		messageVersion = -1;
	}
}
