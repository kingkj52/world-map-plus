package com.worldmapplus.typing;

import com.worldmapplus.WorldMapPlusConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the typing guide on the game's chat input line: the message as a see-through ghost where the typed letters go, each
 * typed letter in green when it is right and in red when it is wrong (with the wanted letter small above it), the next letter
 * picked out, and a small card above the line with the part number, a one line hint and the Back, Skip and Stop buttons.
 * <p>
 * Only draws: it reads the line the {@link ChatLineReader} measured on the client thread and the guide's state, and paints
 * over the canvas. Nothing of the game is changed. A frame makes nothing: fonts, colours and strokes are made once, the
 * guide's state is copied into a holder made once, letters are drawn from character arrays, and the card's texts are made
 * again only when what they say changes. The card's buttons are the only input it takes, and only presses on the card.
 */
@Singleton
public class TypingGuideOverlay extends Overlay
{
	static final Color GHOST = new Color(255, 255, 255, 175);
	static final Color LINE_BACK = new Color(16, 14, 11, 165);
	static final Color RIGHT = new Color(90, 235, 110);
	static final Color RIGHT_BACK = new Color(40, 200, 70, 70);
	static final Color WRONG = new Color(255, 90, 80);
	static final Color WRONG_BACK = new Color(200, 30, 30, 150);
	static final Color NEXT = new Color(255, 214, 120);
	static final Color NEXT_BACK = new Color(255, 214, 120, 60);
	static final Color CHANNEL = new Color(200, 200, 200, 200);
	static final Color CARD = new Color(24, 21, 17, 235);
	static final Color CARD_EDGE = new Color(94, 82, 62);
	static final Color CARD_TEXT = new Color(235, 222, 190);
	static final Color CARD_DIM = new Color(165, 150, 122);
	static final Color BUTTON = new Color(58, 50, 38);
	static final Color BUTTON_EDGE = new Color(120, 104, 76);
	static final Color HINT_OK = new Color(120, 220, 130);
	static final Color HINT_BAD = new Color(255, 120, 100);
	private static final Stroke ONE = new BasicStroke(1f);

	private final TypingGuide guide;
	private final Supplier<ChatLine> line;
	private final BooleanSupplier enabled;
	private final BooleanSupplier above;
	private final TypingGuide.View view = new TypingGuide.View();
	private final GuideLayout layout = new GuideLayout();
	private final Input input = new Input();

	private Font font;
	private Font small;
	private Metrics fontMetrics;
	private Metrics smallMetrics;
	/** How far the guide font's letters reach below the baseline. */
	private int descent;

	// The card's texts, made again only when what they say changes.
	private String label = "";
	private String hint = "";
	private Color hintColour = CARD_DIM;
	private long textKey = Long.MIN_VALUE;

	// The card's buttons as last drawn, for the interface thread's clicks. Guarded by the input.
	private final int[] hitX = new int[3];
	private final int[] hitWidth = new int[3];
	private final boolean[] hitShown = new boolean[3];
	private int hitY;
	private int cardX;
	private int cardY;
	private int cardWidth;
	private boolean cardShown;

	@Inject
	public TypingGuideOverlay(TypingGuide guide, ChatLineReader reader, WorldMapPlusConfig config)
	{
		this(guide, reader::line, config::typingGuide, () -> config.typingGuidePosition() == WorldMapPlusConfig.GuidePosition.ABOVE_LINE);
	}

	/** For tests and pictures: the guide, where the line comes from, and the two settings. */
	public TypingGuideOverlay(TypingGuide guide, Supplier<ChatLine> line, BooleanSupplier enabled, BooleanSupplier above)
	{
		this.guide = guide;
		this.line = line;
		this.enabled = enabled;
		this.above = above;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	/** The card's buttons. Registered with RuneLite's mouse manager while the plugin runs. */
	public Input getInput()
	{
		return input;
	}

	/** Width measures over a font's metrics, asked once and kept. */
	private static final class Metrics implements GuideLayout.Measure
	{
		private final FontMetrics metrics;

		Metrics(FontMetrics metrics)
		{
			this.metrics = metrics;
		}

		@Override
		public int textWidth(String text)
		{
			return metrics.stringWidth(text);
		}

		@Override
		public int charWidth(char c)
		{
			return metrics.charWidth(c);
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		guide.fill(view);
		ChatLine shown = line.get();
		if (!enabled.getAsBoolean() || !view.active || shown == null)
		{
			hideCard();
			return null;
		}
		if (font == null)
		{
			font = FontManager.getRunescapeFont();
			small = FontManager.getRunescapeSmallFont();
			fontMetrics = new Metrics(g.getFontMetrics(font));
			smallMetrics = new Metrics(g.getFontMetrics(small));
			descent = g.getFontMetrics(font).getDescent();
		}
		updateTexts(shown);
		layout.compute(shown, view, above.getAsBoolean(), fontMetrics, smallMetrics, label, hint);
		if (layout.mode == GuideLayout.Mode.NONE)
		{
			hideCard();
			return null;
		}
		Object antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		Shape clip = g.getClip();
		Stroke stroke = g.getStroke();
		// The game's letters are not smoothed, so the guide's are not either.
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setStroke(ONE);
		if (layout.mode == GuideLayout.Mode.OVER)
		{
			drawOver(g, shown);
		}
		g.setClip(clip);
		if (layout.strip)
		{
			drawStrip(g);
			g.setClip(clip);
		}
		drawCard(g);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antialias);
		g.setStroke(stroke);
		g.setClip(clip);
		return null;
	}

	private void drawOver(Graphics2D g, ChatLine shown)
	{
		int top = shown.y + 2;
		// The band reaches below the lowest point of q, j, g, y and p, so no letter is cut, and every mark under a letter
		// (a wrong letter's red line, the next letter's gold one) goes below that point, never through a letter.
		int bottom = Math.max(shown.y + shown.height - 2, layout.baseline + descent + 2);
		int height = bottom - top;
		g.clipRect(shown.x, shown.y - 14, layout.clipRight - shown.x, bottom + 2 - (shown.y - 14));
		g.setFont(font);
		int typed = Math.min(view.typedLength, shown.typedCount);
		int from = layout.ghostFrom;
		boolean ghost = from < view.messageLength;
		// One dark band behind the typed letters and the ghost, so both read the same on the opaque and the see-through chat.
		int startX = typed > 0 ? layout.typedX[0] : ghost ? layout.messageX[from] : shown.textX;
		int endX = ghost ? layout.messageX[view.messageLength - 1] + fontMetrics.charWidth(view.message[view.messageLength - 1])
			: shown.textX + shown.typedEdges[typed];
		g.setColor(LINE_BACK);
		g.fillRoundRect(startX - 3, top, endX - startX + 6, height, 6, 6);
		// The typed letters, over the game's own, in their colour; a wrong one also gets a red cell and a line under it.
		for (int i = 0; i < typed; i++)
		{
			byte mark = view.marks[i];
			int x = layout.typedX[i];
			if (mark == TypingGuide.CHANNEL)
			{
				g.setColor(CHANNEL);
				g.drawChars(view.typed, i, 1, x, layout.baseline);
				continue;
			}
			int w = Math.max(1, shown.typedEdges[i + 1] - shown.typedEdges[i]);
			boolean right = mark == TypingGuide.RIGHT;
			if (!right)
			{
				g.setColor(WRONG_BACK);
				g.fillRect(x, top, w, height);
				g.setColor(WRONG);
				g.fillRect(x, bottom - 1, w, 1);
			}
			g.setColor(right ? RIGHT : WRONG);
			g.drawChars(view.typed, i, 1, x, layout.baseline);
		}
		// The letter that was meant, in a small tag above the first wrong one.
		if (view.firstWrong >= 0 && view.firstWrong + view.channel < typed)
		{
			int x = layout.typedX[view.firstWrong + view.channel];
			int w = smallMetrics.charWidth(view.message[view.firstWrong]) + 6;
			g.setColor(CARD);
			g.fillRoundRect(x - 2, shown.y - 13, w, 12, 5, 5);
			g.setColor(WRONG);
			g.drawRoundRect(x - 2, shown.y - 13, w - 1, 11, 5, 5);
			g.setFont(small);
			g.setColor(NEXT);
			g.drawChars(view.message, view.firstWrong, 1, x + 1, shown.y - 3);
			g.setFont(font);
		}
		if (!ghost)
		{
			return;
		}
		// The next letter: a warm cell behind it and a thin line below it, both drawn so they never cover the letter itself.
		int nextX = layout.messageX[from];
		int nextWidth = Math.max(2, fontMetrics.charWidth(view.message[from]));
		g.setColor(NEXT_BACK);
		g.fillRect(nextX - 1, top, nextWidth + 2, height);
		g.setColor(NEXT);
		g.fillRect(nextX, bottom - 1, nextWidth, 1);
		for (int j = from; j < view.messageLength; j++)
		{
			g.setColor(j == from ? NEXT : GHOST);
			g.drawChars(view.message, j, 1, layout.messageX[j], layout.baseline);
		}
	}

	private void drawStrip(Graphics2D g)
	{
		g.setColor(CARD);
		g.fillRoundRect(layout.stripX, layout.stripY, layout.stripWidth, GuideLayout.STRIP_HEIGHT, 6, 6);
		g.setColor(CARD_EDGE);
		g.drawRoundRect(layout.stripX, layout.stripY, layout.stripWidth - 1, GuideLayout.STRIP_HEIGHT - 1, 6, 6);
		Font use = layout.stripSmall ? small : font;
		Metrics measure = layout.stripSmall ? smallMetrics : fontMetrics;
		g.setFont(use);
		g.clipRect(layout.stripX, layout.stripY, layout.stripWidth, GuideLayout.STRIP_HEIGHT);
		int done = Math.max(0, view.typedLength - view.channel);
		for (int j = 0; j < view.messageLength; j++)
		{
			int x = layout.stripCharX[j];
			int w = measure.charWidth(view.message[j]);
			Color colour;
			if (j < done)
			{
				boolean right = view.marks[j + view.channel] == TypingGuide.RIGHT;
				g.setColor(right ? RIGHT_BACK : WRONG_BACK);
				g.fillRect(x, layout.stripY + 2, w, GuideLayout.STRIP_HEIGHT - 4);
				colour = right ? RIGHT : WRONG;
			}
			else
			{
				colour = j == done ? NEXT : GHOST;
			}
			g.setColor(colour);
			g.drawChars(view.message, j, 1, x, layout.stripBaseline);
			if (j == done)
			{
				g.fillRect(x, layout.stripY + GuideLayout.STRIP_HEIGHT - 2, Math.max(2, w), 1);
			}
		}
	}

	private void drawCard(Graphics2D g)
	{
		int x = layout.cardX;
		int y = layout.cardY;
		g.setColor(CARD);
		g.fillRoundRect(x, y, layout.cardWidth, GuideLayout.CARD_HEIGHT, 8, 8);
		g.setColor(CARD_EDGE);
		g.drawRoundRect(x, y, layout.cardWidth - 1, GuideLayout.CARD_HEIGHT - 1, 8, 8);
		g.setColor(NEXT);
		g.fillRect(x + 1, y + 4, 2, GuideLayout.CARD_HEIGHT - 8);
		g.clipRect(x, y, layout.cardWidth, GuideLayout.CARD_HEIGHT);
		g.setFont(small);
		g.setColor(CARD_TEXT);
		g.drawString(label, layout.labelX, layout.textBaseline);
		g.setColor(hintColour);
		g.drawString(hint, layout.hintX, layout.textBaseline);
		for (int b = 0; b < 3; b++)
		{
			if (!layout.buttonShown[b])
			{
				continue;
			}
			int bx = layout.buttonX[b];
			g.setColor(BUTTON);
			g.fillRoundRect(bx, layout.buttonY, layout.buttonWidth[b], GuideLayout.BUTTON_HEIGHT, 6, 6);
			g.setColor(BUTTON_EDGE);
			g.drawRoundRect(bx, layout.buttonY, layout.buttonWidth[b] - 1, GuideLayout.BUTTON_HEIGHT - 1, 6, 6);
			g.setColor(CARD_TEXT);
			g.drawString(GuideLayout.BUTTONS[b], bx + 6, layout.buttonY + GuideLayout.BUTTON_HEIGHT - 3);
		}
		synchronized (input)
		{
			for (int b = 0; b < 3; b++)
			{
				hitX[b] = layout.buttonX[b];
				hitWidth[b] = layout.buttonWidth[b];
				hitShown[b] = layout.buttonShown[b];
			}
			hitY = layout.buttonY;
			cardX = layout.cardX;
			cardY = layout.cardY;
			cardWidth = layout.cardWidth;
			cardShown = true;
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
	private void updateTexts(ChatLine shown)
	{
		long key = ((long) view.hint.ordinal() << 56) ^ ((long) view.part << 48) ^ ((long) view.parts << 40)
			^ ((long) (view.firstWrong + 1) << 28) ^ ((long) view.right << 16) ^ ((long) view.extra << 4) ^ (shown.idle ? 1 : 0);
		if (key == textKey)
		{
			return;
		}
		textKey = key;
		label = labelFor(view);
		hint = hintFor(view, shown.idle);
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
					if (hitShown[b] && e.getX() >= hitX[b] && e.getX() < hitX[b] + hitWidth[b] && e.getY() >= hitY
						&& e.getY() < hitY + GuideLayout.BUTTON_HEIGHT)
					{
						button = b;
					}
				}
			}
			e.consume();
			if (button == GuideLayout.BACK)
			{
				guide.back();
			}
			else if (button == GuideLayout.SKIP)
			{
				guide.skip();
			}
			else if (button == GuideLayout.STOP)
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
			return x >= cardX && x < cardX + cardWidth && y >= cardY && y < cardY + GuideLayout.CARD_HEIGHT;
		}
	}
}
