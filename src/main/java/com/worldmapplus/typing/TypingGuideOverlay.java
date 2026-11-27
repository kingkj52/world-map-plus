package com.worldmapplus.typing;

import com.worldmapplus.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.inject.*;
import lombok.*;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;

/**
 * Draws the typing guide just above the game's chat input line: a strip with the whole message, each letter green when the
 * player typed it right and red when wrong (the strip shows the letter that was meant), the next letter picked out, and
 * above the strip a small card with the part number, a one line hint and the Back, Skip and Stop buttons.
 * <p>
 * Only draws: it reads where the line is ({@link ChatLineReader}, client thread) and the guide's state, and paints over the
 * canvas. Nothing of the game is changed, and nothing is drawn over the player's own letters. A frame makes nothing: fonts
 * are asked for once, the guide's state is copied into a holder made once, letters are drawn from character arrays, and the
 * card's texts are made again only when what they say changes. The card's buttons are the only input it takes, and only
 * presses on the card.
 */
@Singleton
public class TypingGuideOverlay extends Overlay
{
	static final Color GHOST = new Color(255, 255, 255, 175);
	static final Color RIGHT = new Color(90, 235, 110);
	static final Color RIGHT_BACK = new Color(40, 200, 70, 70);
	static final Color WRONG = new Color(255, 90, 80);
	static final Color WRONG_BACK = new Color(200, 30, 30, 150);
	static final Color NEXT = new Color(255, 214, 120);
	static final Color CARD = new Color(24, 21, 17, 235);
	static final Color CARD_EDGE = new Color(94, 82, 62);
	static final Color CARD_TEXT = new Color(235, 222, 190);
	static final Color CARD_DIM = new Color(165, 150, 122);
	static final Color BUTTON = new Color(58, 50, 38);
	static final Color BUTTON_EDGE = new Color(120, 104, 76);
	static final Color HINT_OK = new Color(120, 220, 130);
	static final Color HINT_BAD = new Color(255, 120, 100);

	static final int CARD_HEIGHT = 18;
	static final int STRIP_HEIGHT = 19;
	static final int BUTTON_HEIGHT = 14;
	static final int PAD = 6;
	static final int GAP = 8;
	static final String[] BUTTONS = {"Back", "Skip", "Stop"};

	private final TypingGuide guide;
	private final Supplier<ChatLine> line;
	private final BooleanSupplier enabled;
	private final TypingGuide.View view = new TypingGuide.View();
	/** The card's buttons. Registered with RuneLite's mouse manager while the plugin runs. */
	@Getter
	private final Input input = new Input();

	private Font font;
	private Font small;
	private FontMetrics fontMetrics;
	private FontMetrics smallMetrics;

	// The card's texts, made again only when what they say changes.
	private String label = "";
	private String hint = "";
	private Color hintColour = CARD_DIM;
	/** The widths of the label and the hint, and of each button's word, measured when they are made: a frame measures nothing. */
	private int labelWidth;
	private int hintWidth;
	private final int[] wordWidth = new int[3];
	private long textKey = Long.MIN_VALUE;

	// The card and its buttons as last drawn (a button not shown has width 0). Written under the input's lock.
	private final int[] buttonX = new int[3];
	private final int[] buttonWidth = new int[3];
	private int buttonY;
	private int cardX;
	private int cardY;
	private int cardWidth;
	private boolean cardShown;

	@Inject
	public TypingGuideOverlay(TypingGuide guide, ChatLineReader reader, WorldMapPlusConfig config)
	{
		this(guide, reader::line, config::typingGuide);
	}

	/** For tests and pictures: the guide, where the line comes from, and the setting. */
	public TypingGuideOverlay(TypingGuide guide, Supplier<ChatLine> line, BooleanSupplier enabled)
	{
		this.guide = guide;
		this.line = line;
		this.enabled = enabled;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		guide.fill(view);
		ChatLine shown = line.get();
		if (!enabled.getAsBoolean() || !view.active || shown == null || !shown.usable)
		{
			hideCard();
			return null;
		}
		if (font == null)
		{
			font = FontManager.getRunescapeFont();
			small = FontManager.getRunescapeSmallFont();
			fontMetrics = g.getFontMetrics(font);
			smallMetrics = g.getFontMetrics(small);
			for (int b = 0; b < 3; b++)
			{
				wordWidth[b] = smallMetrics.stringWidth(BUTTONS[b]);
			}
		}
		updateTexts(shown.idle);
		Object antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		Shape clip = g.getClip();
		// The game's letters are not smoothed, so the guide's are not either.
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		int stripY = shown.y - STRIP_HEIGHT - 1;
		drawStrip(g, shown, stripY);
		g.setClip(clip);
		drawCard(g, shown, stripY - CARD_HEIGHT - 2);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antialias);
		g.setClip(clip);
		return null;
	}

	/** The message in the strip, in the normal font, or the small one when the message is too wide for the line. */
	private void drawStrip(Graphics2D g, ChatLine shown, int y)
	{
		int wide = width(fontMetrics);
		boolean useSmall = wide > Math.max(40, shown.width - 2 * PAD);
		FontMetrics measure = useSmall ? smallMetrics : fontMetrics;
		int x = shown.x + 2;
		int stripWidth = Math.min(shown.width - 4, (useSmall ? width(smallMetrics) : wide) + 2 * PAD);
		g.setColor(CARD);
		g.fillRoundRect(x, y, stripWidth, STRIP_HEIGHT, 6, 6);
		g.setColor(CARD_EDGE);
		g.drawRoundRect(x, y, stripWidth - 1, STRIP_HEIGHT - 1, 6, 6);
		g.setFont(useSmall ? small : font);
		g.clipRect(x, y, stripWidth, STRIP_HEIGHT);
		// Room below the baseline for the tails of q, j, g, y and p and, under them, the next letter's line.
		int baseline = y + STRIP_HEIGHT - 6;
		int done = Math.max(0, view.typedLength - view.channel);
		x += PAD;
		for (int j = 0; j < view.messageLength; j++)
		{
			int w = measure.charWidth(view.message[j]);
			Color colour = j == done ? NEXT : GHOST;
			if (j < done)
			{
				boolean right = view.marks[j + view.channel] == TypingGuide.RIGHT;
				g.setColor(right ? RIGHT_BACK : WRONG_BACK);
				g.fillRect(x, y + 2, w, STRIP_HEIGHT - 4);
				colour = right ? RIGHT : WRONG;
			}
			g.setColor(colour);
			g.drawChars(view.message, j, 1, x, baseline);
			if (j == done)
			{
				g.fillRect(x, y + STRIP_HEIGHT - 2, Math.max(2, w), 1);
			}
			x += w;
		}
	}

	private int width(FontMetrics metrics)
	{
		int total = 0;
		for (int j = 0; j < view.messageLength; j++)
		{
			total += metrics.charWidth(view.message[j]);
		}
		return total;
	}

	/** The card at the right of the line: the label, the hint, and the buttons (Back and Skip only for a code in parts). */
	private void drawCard(Graphics2D g, ChatLine shown, int y)
	{
		int width = PAD + labelWidth + GAP + hintWidth + GAP + PAD;
		synchronized (input)
		{
			for (int b = 0; b < 3; b++)
			{
				buttonWidth[b] = b == 2 || view.parts > 1 ? wordWidth[b] + 12 : 0;
				width += buttonWidth[b] > 0 ? buttonWidth[b] + 4 : 0;
			}
			cardWidth = Math.min(width - 4, Math.max(60, shown.width));
			cardX = Math.max(shown.x, shown.x + shown.width - cardWidth - 2);
			cardY = y;
			buttonY = y + (CARD_HEIGHT - BUTTON_HEIGHT) / 2;
			int x = cardX + cardWidth - PAD;
			for (int b = 2; b >= 0; b--)
			{
				x -= buttonWidth[b];
				buttonX[b] = x;
				x -= buttonWidth[b] > 0 ? 4 : 0;
			}
			cardShown = true;
		}
		g.setColor(CARD);
		g.fillRoundRect(cardX, y, cardWidth, CARD_HEIGHT, 8, 8);
		g.setColor(CARD_EDGE);
		g.drawRoundRect(cardX, y, cardWidth - 1, CARD_HEIGHT - 1, 8, 8);
		g.setColor(NEXT);
		g.fillRect(cardX + 1, y + 4, 2, CARD_HEIGHT - 8);
		g.clipRect(cardX, y, cardWidth, CARD_HEIGHT);
		g.setFont(small);
		g.setColor(CARD_TEXT);
		int baseline = y + CARD_HEIGHT - 5;
		g.drawString(label, cardX + PAD, baseline);
		g.setColor(hintColour);
		g.drawString(hint, cardX + PAD + labelWidth + GAP, baseline);
		for (int b = 0; b < 3; b++)
		{
			if (buttonWidth[b] > 0)
			{
				g.setColor(BUTTON);
				g.fillRoundRect(buttonX[b], buttonY, buttonWidth[b], BUTTON_HEIGHT, 6, 6);
				g.setColor(BUTTON_EDGE);
				g.drawRoundRect(buttonX[b], buttonY, buttonWidth[b] - 1, BUTTON_HEIGHT - 1, 6, 6);
				g.setColor(CARD_TEXT);
				g.drawString(BUTTONS[b], buttonX[b] + 6, buttonY + BUTTON_HEIGHT - 3);
			}
		}
	}

	private void hideCard()
	{
		synchronized (input)
		{
			cardShown = false;
		}
	}

	/** Makes the card's label and hint again when what they say has changed. */
	private void updateTexts(boolean idle)
	{
		long key = ((long) view.hint.ordinal() << 56) ^ ((long) view.part << 48) ^ ((long) view.parts << 40)
			^ ((long) (view.firstWrong + 1) << 28) ^ ((long) view.right << 16) ^ ((long) view.extra << 4) ^ (idle ? 1 : 0);
		if (key == textKey)
		{
			return;
		}
		textKey = key;
		label = labelFor(view);
		hint = hintFor(view, idle);
		labelWidth = smallMetrics.stringWidth(label);
		hintWidth = smallMetrics.stringWidth(hint);
		hintColour = view.hint == TypingGuide.Hint.MATCH ? HINT_OK
			: view.hint == TypingGuide.Hint.TYPO || view.hint == TypingGuide.Hint.TOO_LONG ? HINT_BAD : CARD_DIM;
	}

	static String labelFor(TypingGuide.View view)
	{
		return view.parts > 1 ? "Part " + (view.part + 1) + " of " + view.parts : "Share code";
	}

	/** The one line hint: how far the player is, the first typo, or that the line matches. */
	static String hintFor(TypingGuide.View view, boolean idle)
	{
		switch (view.hint)
		{
			case TYPO:
				char wanted = view.expected(view.firstWrong);
				return "Typo at " + (view.firstWrong + 1) + ": wanted " + (wanted == ' ' ? "a space" : String.valueOf(wanted));
			case TOO_LONG:
				return view.extra + (view.extra == 1 ? " letter too many" : " letters too many");
			case MATCH:
				return view.parts > 1 ? "Matches. Press Enter to send" : "Matches, checksum ok. Press Enter";
			case GOING:
				return view.right + " of " + view.messageLength + " right";
			default:
				return idle ? "Press Enter, then type it" : "Type the line shown";
		}
	}

	/** Presses on the card's buttons, and on the card so a press there does not reach the chat under it. */
	public final class Input extends MouseAdapter
	{
		private boolean pressed;

		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			int button = -1;
			synchronized (this)
			{
				// Every release and click follows a press, so this decides for them too.
				pressed = cardShown && inCard(e.getX(), e.getY());
				if (!pressed)
				{
					return e;
				}
				for (int b = 0; b < 3; b++)
				{
					if (e.getX() >= buttonX[b] && e.getX() < buttonX[b] + buttonWidth[b] && e.getY() >= buttonY
						&& e.getY() < buttonY + BUTTON_HEIGHT)
					{
						button = b;
					}
				}
			}
			e.consume();
			if (button == 0)
			{
				guide.back();
			}
			else if (button == 1)
			{
				guide.skip();
			}
			else if (button == 2)
			{
				guide.stop();
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			return takeIfPressed(e);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e)
		{
			return takeIfPressed(e);
		}

		private MouseEvent takeIfPressed(MouseEvent e)
		{
			boolean ours;
			synchronized (this)
			{
				ours = pressed;
			}
			if (ours)
			{
				e.consume();
			}
			return e;
		}

		private boolean inCard(int x, int y)
		{
			return x >= cardX && x < cardX + cardWidth && y >= cardY && y < cardY + CARD_HEIGHT;
		}
	}
}
