package com.worldmapplus.panel;

import com.worldmapplus.theme.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;
import javax.swing.*;
import javax.swing.border.*;

/**
 * The look of the side panel, in the colours of the map's own cards, menus and results (warm dark fills, a thin bronze edge,
 * a gold accent): one set of colours, fonts and spacing, and the few pieces every page is built from (cards with an accent
 * bar, pill buttons, section headers, count badges, tick boxes drawn in code). Everything is made once: colours, fonts and
 * strokes are constants, and a gradient is kept by the component that paints it until its height changes, so painting makes
 * nothing. Nothing here is an image; the shapes are drawn with Java 2D, which scales on high density screens.
 */
public final class PanelTheme
{
	/*
	 * The colours are copies of the palette in use (Palettes.current()), refreshed by apply() when the theme changes, so
	 * painting reads a field and makes nothing. Fonts are the palette's, the same in every theme.
	 */
	/** The page behind everything. */
	public static Color PAGE;
	/** A card: one place, route, share or group of controls. */
	public static Color CARD;
	public static Color CARD_HOVER;
	/** A card that is the one being used (the route being followed, a share just opened). */
	public static Color CARD_ACTIVE;
	public static Color EDGE;
	/** A field to type in, and the inside of a code. */
	public static Color FIELD;

	/** The map's accent: titles, the selected tab, the main button. */
	public static Color ACCENT;
	public static Color ACCENT_DEEP;

	public static Color TEXT;
	public static Color TEXT_DIM;
	public static Color TEXT_FAINT;
	public static Color GOOD;
	public static Color BAD;

	static Color BUTTON;
	static Color BUTTON_TOP;
	static Color BUTTON_HOVER;
	static Color BUTTON_PRESSED;
	static Color BUTTON_EDGE;
	static Color PRIMARY;
	static Color PRIMARY_TOP;
	static Color PRIMARY_HOVER;
	static Color PRIMARY_PRESSED;
	static Color PRIMARY_TEXT;
	static Color DISABLED_TEXT;
	static Color BADGE;
	/** The ring round the control the keyboard is on. */
	static Color FOCUS;
	private static Stroke EDGE_STROKE;
	private static int edgeInset;

	public static final Font TITLE = Palette.TITLE;
	public static final Font BODY = Palette.BODY;
	public static final Font BODY_BOLD = Palette.BODY_BOLD;
	public static final Font SMALL = Palette.SMALL;
	public static final Font SMALL_BOLD = Palette.SMALL_BOLD;
	/** Codes: fixed width, so letters line up in their groups. */
	public static final Font CODE = Palette.CODE;

	public static final int GAP = 6;
	public static final int PAD = 8;
	public static final int ARC = 10;
	static final int BAR = 3;

	private static final Stroke ONE = Palette.ONE;
	private static final Stroke FOCUS_RING = new BasicStroke(2f);
	private static final Stroke TICK = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	static
	{
		apply(Palettes.current());
	}

	private PanelTheme()
	{
	}

	/** Takes the colours of a palette. Called when the theme is chosen. */
	public static void apply(Palette p)
	{
		PAGE = p.get(Role.PAGE);
		CARD = p.get(Role.CARD);
		CARD_HOVER = p.get(Role.CARD_HOVER);
		CARD_ACTIVE = p.get(Role.CARD_ACTIVE);
		EDGE = p.get(Role.EDGE);
		FIELD = p.get(Role.FIELD);
		ACCENT = p.get(Role.ACCENT);
		ACCENT_DEEP = p.get(Role.ACCENT_DEEP);
		TEXT = p.get(Role.TEXT);
		TEXT_DIM = p.get(Role.TEXT_DIM);
		TEXT_FAINT = p.get(Role.TEXT_FAINT);
		GOOD = p.get(Role.GOOD);
		BAD = p.get(Role.BAD);
		BUTTON = p.get(Role.BUTTON);
		BUTTON_TOP = p.get(Role.BUTTON_TOP);
		BUTTON_HOVER = p.get(Role.BUTTON_HOVER);
		BUTTON_PRESSED = p.get(Role.BUTTON_PRESSED);
		BUTTON_EDGE = p.get(Role.BUTTON_EDGE);
		PRIMARY = p.get(Role.PRIMARY);
		PRIMARY_TOP = p.get(Role.PRIMARY_TOP);
		PRIMARY_HOVER = p.get(Role.PRIMARY_HOVER);
		PRIMARY_PRESSED = p.get(Role.PRIMARY_PRESSED);
		PRIMARY_TEXT = p.get(Role.PRIMARY_TEXT);
		DISABLED_TEXT = p.get(Role.DISABLED_TEXT);
		BADGE = p.get(Role.BADGE);
		FOCUS = p.get(Role.FOCUS);
		EDGE_STROKE = p.edge();
		edgeInset = (int) p.edgeWidth() / 2;
	}

	/** Smooth shapes and text, for the pieces drawn here. */
	static void smooth(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	/** A rounded card: fill, a one pixel edge, and an accent bar down the left when {@code bar} is not null. */
	static void paintCard(Graphics2D g, int width, int height, Color fill, Color edge, Color bar)
	{
		smooth(g);
		g.setColor(fill);
		g.fillRoundRect(0, 0, width, height, ARC, ARC);
		g.setStroke(EDGE_STROKE);
		g.setColor(edge);
		g.drawRoundRect(edgeInset, edgeInset, width - 1 - 2 * edgeInset, height - 1 - 2 * edgeInset, ARC, ARC);
		if (bar != null)
		{
			g.setColor(bar);
			g.fillRoundRect(1, 3, BAR, height - 6, BAR, BAR);
		}
	}

	/** A plain label in one of the theme's fonts and colours. */
	public static JLabel label(String text, Font font, Color colour)
	{
		JLabel label = new JLabel();
		label.putClientProperty("html.disable", Boolean.TRUE);
		label.setText(text);
		label.setFont(font);
		label.setForeground(colour);
		return label;
	}

	/** A label whose text wraps to the panel's width. The text is escaped, so it is never read as markup. */
	public static JLabel wrapped(String text, Font font, Color colour, int width)
	{
		JLabel label = new JLabel("<html><body style='width:" + width + "px'>" + escape(text) + "</body></html>");
		label.setFont(font);
		label.setForeground(colour);
		return label;
	}

	static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** An empty border of the theme's padding. */
	static Border pad(int top, int left, int bottom, int right)
	{
		return BorderFactory.createEmptyBorder(top, left, bottom, right);
	}

	/** A transparent panel, to hold other pieces on a card. */
	public static JPanel clear(LayoutManager layout)
	{
		JPanel panel = new JPanel(layout);
		panel.setOpaque(false);
		return panel;
	}

	/** Styles a drop down list for the dark page. */
	static void style(JComboBox<?> box)
	{
		box.setFont(BODY);
		box.setForeground(TEXT);
		box.setBackground(FIELD);
		box.setFocusable(false);
	}

	/** Gives a tick box the theme's look: a rounded box drawn in code, gold when ticked. */
	public static JCheckBox style(JCheckBox box, Font font, Color colour)
	{
		box.setOpaque(false);
		box.setFocusable(false);
		box.setFont(font);
		box.setForeground(colour);
		box.setIcon(Tick.OFF);
		box.setSelectedIcon(Tick.ON);
		box.setRolloverIcon(Tick.HOVER);
		box.setRolloverSelectedIcon(Tick.ON);
		box.setIconTextGap(6);
		box.setBorder(pad(1, 0, 1, 0));
		return box;
	}

	/** A tick box drawn in code. */
	static final class Tick implements Icon
	{
		static final Tick OFF = new Tick(false, false);
		static final Tick HOVER = new Tick(false, true);
		static final Tick ON = new Tick(true, false);
		private static final int SIZE = 14;

		private final boolean on;
		private final boolean hover;

		private Tick(boolean on, boolean hover)
		{
			this.on = on;
			this.hover = hover;
		}

		@Override
		public void paintIcon(Component c, Graphics graphics, int x, int y)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			smooth(g);
			g.setColor(on ? PRIMARY : FIELD);
			g.fillRoundRect(x, y, SIZE, SIZE, 5, 5);
			g.setStroke(ONE);
			g.setColor(on ? PRIMARY_TOP : hover ? ACCENT_DEEP : BUTTON_EDGE);
			g.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 5, 5);
			if (on)
			{
				g.setStroke(TICK);
				g.setColor(PRIMARY_TEXT);
				g.drawLine(x + 3, y + 7, x + 6, y + 10);
				g.drawLine(x + 6, y + 10, x + 11, y + 4);
			}
			g.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}
	}

	/** A section heading: gold title, an optional count badge, and a thin line under it. */
	public static JComponent header(String title, int count)
	{
		return new Header(title, count);
	}

	private static final class Header extends JComponent
	{
		private final String title;
		private final String count;

		Header(String title, int count)
		{
			this.title = title;
			this.count = count < 0 ? null : String.valueOf(count);
			setPreferredSize(new Dimension(10, 22));
			setAlignmentX(LEFT_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			smooth(g);
			g.setFont(TITLE);
			FontMetrics m = g.getFontMetrics();
			int base = 15;
			g.setColor(ACCENT);
			g.drawString(title, 2, base);
			int x = 2 + m.stringWidth(title) + 6;
			if (count != null)
			{
				x = paintBadge(g, x, base - 11, count) + 6;
			}
			g.setColor(EDGE);
			g.fillRect(x, base - 4, Math.max(0, getWidth() - x - 2), 1);
			g.dispose();
		}
	}

	/** Paints a count badge with its left edge at {@code x} and its top at {@code y}; returns its right edge. */
	static int paintBadge(Graphics2D g, int x, int y, String text)
	{
		g.setFont(SMALL_BOLD);
		FontMetrics m = g.getFontMetrics();
		int width = Math.max(16, m.stringWidth(text) + 10);
		g.setColor(BADGE);
		g.fillRoundRect(x, y, width, 14, 14, 14);
		g.setColor(ACCENT);
		g.drawString(text, x + (width - m.stringWidth(text)) / 2, y + 11);
		return x + width;
	}

	/** A small pill shaped button. */
	public static Pill pill(String text)
	{
		return new Pill(text, false);
	}

	/** The main button of a card, in gold. */
	public static Pill primary(String text)
	{
		return new Pill(text, true);
	}

	/**
	 * A pill shaped button drawn in code, with hover and pressed states and a gentle top to bottom shade. The shade is made
	 * when the button's height changes and kept, so painting makes nothing.
	 */
	public static final class Pill extends JButton
	{
		private final boolean primary;
		private GradientPaint shade;
		private int shadeHeight = -1;

		Pill(String text, boolean primary)
		{
			super(text);
			this.primary = primary;
			putClientProperty("html.disable", Boolean.TRUE);
			setFont(primary ? SMALL_BOLD : SMALL);
			setForeground(primary ? PRIMARY_TEXT : TEXT);
			// Reachable with the keyboard (Tab, then Space), with a ring while it has the focus, and a hand over it.
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setMargin(new Insets(0, 0, 0, 0));
			setBorder(pad(0, 0, 0, 0));
		}

		@Override
		public Dimension getPreferredSize()
		{
			FontMetrics m = getFontMetrics(getFont());
			return new Dimension(m.stringWidth(getText()) + 18, 22);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			smooth(g);
			int w = getWidth();
			int h = getHeight();
			ButtonModel model = getModel();
			boolean enabled = isEnabled();
			if (shadeHeight != h)
			{
				shade = new GradientPaint(0, 0, primary ? PRIMARY_TOP : BUTTON_TOP, 0, h, primary ? PRIMARY : BUTTON);
				shadeHeight = h;
			}
			if (!enabled)
			{
				g.setColor(BUTTON_PRESSED);
			}
			else if (model.isPressed())
			{
				g.setColor(primary ? PRIMARY_PRESSED : BUTTON_PRESSED);
			}
			else if (model.isRollover())
			{
				g.setColor(primary ? PRIMARY_HOVER : BUTTON_HOVER);
			}
			else
			{
				g.setPaint(shade);
			}
			g.fillRoundRect(0, 0, w, h, h, h);
			g.setStroke(ONE);
			g.setColor(primary && enabled ? ACCENT_DEEP : BUTTON_EDGE);
			g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
			g.setFont(getFont());
			FontMetrics m = g.getFontMetrics();
			String text = getText();
			g.setColor(enabled ? getForeground() : DISABLED_TEXT);
			g.drawString(text, (w - m.stringWidth(text)) / 2, (h - m.getHeight()) / 2 + m.getAscent());
			if (isFocusOwner())
			{
				g.setColor(FOCUS);
				g.setStroke(FOCUS_RING);
				g.drawRoundRect(1, 1, w - 3, h - 3, h - 2, h - 2);
			}
			g.dispose();
		}
	}

	/**
	 * Tabs as one rounded bar of equal segments, the chosen one in gold, each with an optional count badge. A click on a
	 * segment chooses it and tells the listener.
	 */
	public static final class Segments extends JComponent
	{
		private String[] names;
		private String[] counts;
		private int chosen;
		private IntConsumer listener = i -> { };

		public Segments(String... names)
		{
			this.names = names.clone();
			this.counts = new String[names.length];
			setPreferredSize(new Dimension(10, 28));
			setAlignmentX(LEFT_ALIGNMENT);
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					int width = Math.max(1, getWidth());
					choose(Math.max(0, Math.min(Segments.this.names.length - 1, e.getX() * Segments.this.names.length / width)));
				}
			});
		}

		/** Replaces the names, for tabs that other plugins add. A choice past the new end goes back to the last. */
		public void setNames(String... names)
		{
			this.names = names.clone();
			this.counts = Arrays.copyOf(counts, names.length);
			chosen = Math.min(chosen, names.length - 1);
			repaint();
		}

		public void onChoose(IntConsumer listener)
		{
			this.listener = listener == null ? i -> { } : listener;
		}

		public void choose(int index)
		{
			chosen = index;
			repaint();
			listener.accept(index);
		}

		public int chosen()
		{
			return chosen;
		}

		/** Sets the count shown on a segment; a negative count shows none. */
		public void setCount(int index, int count)
		{
			String text = count < 0 ? null : String.valueOf(count);
			if (!Objects.equals(text, counts[index]))
			{
				counts[index] = text;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			smooth(g);
			int w = getWidth();
			int h = getHeight();
			g.setColor(FIELD);
			g.fillRoundRect(0, 0, w, h, h, h);
			g.setStroke(ONE);
			g.setColor(EDGE);
			g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
			int each = w / names.length;
			for (int i = 0; i < names.length; i++)
			{
				int x = i * each;
				int segment = i == names.length - 1 ? w - x : each;
				boolean on = i == chosen;
				if (on)
				{
					g.setColor(CARD_ACTIVE);
					g.fillRoundRect(x + 2, 2, segment - 4, h - 4, h - 4, h - 4);
					g.setColor(ACCENT_DEEP);
					g.drawRoundRect(x + 2, 2, segment - 5, h - 5, h - 4, h - 4);
				}
				g.setFont(on ? BODY_BOLD : BODY);
				FontMetrics m = g.getFontMetrics();
				int textWidth = m.stringWidth(names[i]);
				int badgeWidth = counts[i] == null ? 0 : Math.max(16, g.getFontMetrics(SMALL_BOLD).stringWidth(counts[i]) + 10) + 5;
				int tx = x + (segment - textWidth - badgeWidth) / 2;
				g.setColor(on ? ACCENT : TEXT_DIM);
				g.drawString(names[i], tx, (h - m.getHeight()) / 2 + m.getAscent());
				if (counts[i] != null)
				{
					paintBadge(g, tx + textWidth + 5, (h - 14) / 2, counts[i]);
				}
			}
			g.dispose();
		}
	}

	/** A panel painted as a rounded card, with an optional accent bar down its left edge. */
	public static class Card extends JPanel
	{
		private Color fill;
		private Color bar;

		public Card(LayoutManager layout, Color fill, Color bar)
		{
			super(layout);
			this.fill = fill;
			this.bar = bar;
			setOpaque(false);
			setBorder(pad(PAD - 1, PAD + (bar == null ? 0 : 2), PAD - 1, PAD));
			setAlignmentX(LEFT_ALIGNMENT);
		}

		/** Changes the fill, for hover and glow. */
		void setFill(Color fill)
		{
			this.fill = fill;
			repaint();
		}

		Color getFill()
		{
			return fill;
		}

		/** Takes the colours a theme change maps the fill and the bar to. */
		void recolour(UnaryOperator<Color> map)
		{
			fill = map.apply(fill);
			bar = map.apply(bar);
			repaint();
		}

		/**
		 * As wide as the column and never taller than what it holds, so a column with room to spare leaves it below the
		 * last card instead of stretching the cards.
		 */
		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			paintCard(g, getWidth(), getHeight(), fill, EDGE, bar);
			g.dispose();
		}
	}
}
