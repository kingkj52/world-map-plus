package com.worldmapplus.typing;

/**
 * Where everything of the typing guide goes in a frame: the letters on the chat input line, the strip just above it, and the
 * small card with the part number, the hint and the Back, Skip and Stop buttons. Plain arithmetic on {@link ChatLine} and
 * {@link TypingGuide.View}, kept apart from the drawing so it is tested with fakes. Every result is a field or an array made
 * once, so a frame makes nothing.
 */
final class GuideLayout
{
	/** Widths of text in the fonts the guide draws with. */
	interface Measure
	{
		int textWidth(String text);

		int charWidth(char c);
	}

	enum Mode
	{
		/** Nothing is drawn. */
		NONE,
		/** The letters sit on the game's own, on the input line. */
		OVER,
		/** The message is drawn in a strip just above the line. */
		ABOVE
	}

	static final int CARD_HEIGHT = 18;
	static final int STRIP_HEIGHT = 19;
	static final int BUTTON_HEIGHT = 14;
	static final int PAD = 6;
	static final int GAP = 8;
	static final int BUTTON_GAP = 4;
	/** Free space between the game's cursor and the first ghost letter. */
	static final int CURSOR_GAP = 2;

	static final int BACK = 0;
	static final int SKIP = 1;
	static final int STOP = 2;
	static final String[] BUTTONS = {"Back", "Skip", "Stop"};

	Mode mode = Mode.NONE;

	/** OVER: where each typed character is drawn. ABOVE: where each message character is drawn. Then the baseline. */
	final int[] typedX = new int[TypingGuide.MAX_TYPED];
	final int[] messageX = new int[TypingGuide.MAX_MESSAGE];
	int baseline;

	/** OVER: the first message character drawn as the ghost (the ones before it are typed), and where the ghost may not go past. */
	int ghostFrom;
	int clipRight;

	/** The strip above the line, used in ABOVE mode, and in OVER mode when the message does not fit on the line. */
	boolean strip;
	int stripX;
	int stripY;
	int stripWidth;
	int stripBaseline;
	final int[] stripCharX = new int[TypingGuide.MAX_MESSAGE];
	/** The strip's letters are drawn in the small font, because the message is too wide for the normal one. */
	boolean stripSmall;

	/** The card. */
	int cardX;
	int cardY;
	int cardWidth;
	int labelX;
	int hintX;
	int textBaseline;
	final int[] buttonX = new int[3];
	final int[] buttonWidth = new int[3];
	final boolean[] buttonShown = new boolean[3];
	int buttonY;

	/**
	 * Lays a frame out. {@code above} is the setting "Just above the typing line". {@code font} measures the guide's letters,
	 * {@code small} the card and, when needed, the strip. {@code label} and {@code hint} are the card's two texts.
	 */
	void compute(ChatLine line, TypingGuide.View view, boolean above, Measure font, Measure small, String label, String hint)
	{
		mode = Mode.NONE;
		strip = false;
		if (!view.active || !line.usable || line.generation != view.generation || line.messageVersion != view.messageVersion)
		{
			return;
		}
		boolean over = !above && line.aligned && !line.idle;
		int top = line.y;
		if (over)
		{
			mode = Mode.OVER;
			baseline = line.baseline;
			int typed = Math.min(view.typedLength, line.typedCount);
			for (int i = 0; i < typed; i++)
			{
				typedX[i] = line.textX + line.typedEdges[i];
			}
			int done = Math.max(0, Math.min(view.messageLength, typed - view.channel));
			ghostFrom = done;
			// The ghost starts after the game's cursor and a small gap, so the cursor never sits on the next letter.
			int caret = line.textX + line.typedEdges[typed] + line.cursorWidth + CURSOR_GAP;
			int start = line.messageEdges[Math.min(done, line.messageCount)];
			for (int j = done; j < view.messageLength && j <= line.messageCount; j++)
			{
				messageX[j] = caret + line.messageEdges[Math.min(j, line.messageCount)] - start;
			}
			clipRight = line.x + line.width;
			int end = view.messageLength > done ? caret + line.messageEdges[Math.min(view.messageLength, line.messageCount)] - start : caret;
			// A message that runs past the end of the line is also shown whole just above it.
			strip = end > clipRight;
		}
		else
		{
			mode = Mode.ABOVE;
			strip = true;
		}
		if (strip)
		{
			layStrip(line, view, font, small);
			top = stripY;
		}
		layCard(line, view, small, label, hint, top);
	}

	private void layStrip(ChatLine line, TypingGuide.View view, Measure font, Measure small)
	{
		int room = Math.max(40, line.width - 2 * PAD);
		int wide = width(view, font);
		stripSmall = wide > room;
		Measure use = stripSmall ? small : font;
		int total = stripSmall ? width(view, small) : wide;
		stripX = line.x + 2;
		stripWidth = Math.min(line.width - 4, total + 2 * PAD);
		stripY = line.y - STRIP_HEIGHT - 1;
		// Room below the baseline for the tails of q, j, g, y and p and, under them, the next letter's line.
		stripBaseline = stripY + STRIP_HEIGHT - 6;
		int x = stripX + PAD;
		for (int j = 0; j < view.messageLength; j++)
		{
			stripCharX[j] = x;
			x += use.charWidth(view.message[j]);
		}
	}

	private static int width(TypingGuide.View view, Measure font)
	{
		int total = 0;
		for (int j = 0; j < view.messageLength; j++)
		{
			total += font.charWidth(view.message[j]);
		}
		return total;
	}

	private void layCard(ChatLine line, TypingGuide.View view, Measure small, String label, String hint, int above)
	{
		buttonShown[BACK] = view.parts > 1;
		buttonShown[SKIP] = view.parts > 1;
		buttonShown[STOP] = true;
		int width = PAD + small.textWidth(label) + GAP + small.textWidth(hint) + GAP;
		for (int b = 0; b < 3; b++)
		{
			buttonWidth[b] = buttonShown[b] ? small.textWidth(BUTTONS[b]) + 12 : 0;
			width += buttonShown[b] ? buttonWidth[b] + BUTTON_GAP : 0;
		}
		width += PAD - BUTTON_GAP;
		cardWidth = Math.min(width, Math.max(60, line.width));
		cardX = Math.max(line.x, line.x + line.width - cardWidth - 2);
		cardY = above - CARD_HEIGHT - 2;
		textBaseline = cardY + CARD_HEIGHT - 5;
		labelX = cardX + PAD;
		hintX = labelX + small.textWidth(label) + GAP;
		int x = cardX + cardWidth - PAD;
		for (int b = 2; b >= 0; b--)
		{
			if (buttonShown[b])
			{
				x -= buttonWidth[b];
				buttonX[b] = x;
				x -= BUTTON_GAP;
			}
		}
		buttonY = cardY + (CARD_HEIGHT - BUTTON_HEIGHT) / 2;
	}

	/** The button under a point of the canvas, or -1. */
	int buttonAt(int x, int y)
	{
		if (mode == Mode.NONE || y < buttonY || y >= buttonY + BUTTON_HEIGHT)
		{
			return -1;
		}
		for (int b = 0; b < 3; b++)
		{
			if (buttonShown[b] && x >= buttonX[b] && x < buttonX[b] + buttonWidth[b])
			{
				return b;
			}
		}
		return -1;
	}

	/** True when a point of the canvas is on the card. */
	boolean onCard(int x, int y)
	{
		return mode != Mode.NONE && x >= cardX && x < cardX + cardWidth && y >= cardY && y < cardY + CARD_HEIGHT;
	}
}
