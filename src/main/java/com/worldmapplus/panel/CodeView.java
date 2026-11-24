package com.worldmapplus.panel;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;

/**
 * One chat message of a share code, drawn so it is easy to read and copy by eye: fixed width letters, the command, a mark
 * for the space after it, then the code in groups of four with a thin gap between groups, wrapped at group edges to the
 * panel's width. The message itself is unchanged (the Copy button next to it copies it exactly). Digits are drawn in the
 * accent colour so they stand apart from letters. While the typing guide is on this message, typed letters show green when
 * right and red when wrong, and the next letter is underlined.
 * <p>
 * The positions are worked out when the text or the width changes and kept, so painting makes nothing.
 */
final class CodeView extends JComponent
{
	/** Letters a group holds. */
	static final int GROUP = 4;

	/** The width used before the panel has been laid out. */
	private static final int DEFAULT_WIDTH = 183;
	private static final int INSET = 6;

	private final String message;
	private final char[] chars;
	private final int[] column;
	private final int[] row;
	private int rows;
	private int laidOutFor = -1;
	private int cell;
	private int lineHeight;
	private int ascent;

	/** The guide's marks for the letters typed so far (see {@code TypingGuide}), or none. */
	private final byte[] marks;
	private int marked;
	private boolean current;

	CodeView(String message)
	{
		this.message = message;
		this.chars = message.toCharArray();
		this.column = new int[chars.length];
		this.row = new int[chars.length];
		this.marks = new byte[chars.length];
		setFont(PanelTheme.CODE);
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setToolTipText(message);
	}

	String getMessage()
	{
		return message;
	}

	/**
	 * Shows how the typing guide compares the line with this message: {@code marks} by message position (right, wrong, or
	 * untyped), {@code count} letters typed. {@code current} is false for a message the guide is not on.
	 */
	void setProgress(boolean current, byte[] marks, int count)
	{
		int n = Math.min(count, this.marks.length);
		boolean same = current == this.current && n == marked;
		for (int i = 0; same && i < n; i++)
		{
			same = this.marks[i] == marks[i];
		}
		if (same)
		{
			return;
		}
		this.current = current;
		marked = current ? n : 0;
		if (current)
		{
			System.arraycopy(marks, 0, this.marks, 0, n);
		}
		repaint();
	}

	/** Where each letter goes for a width: groups of four after the command, wrapped at group edges. */
	private void layOut(int width)
	{
		if (width == laidOutFor)
		{
			return;
		}
		laidOutFor = width;
		FontMetrics m = getFontMetrics(getFont());
		cell = m.charWidth('m');
		lineHeight = m.getHeight() + 2;
		ascent = m.getAscent();
		int gap = Math.max(3, cell / 2);
		int room = Math.max(cell * GROUP, width - 2 * INSET);
		int x = 0;
		int y = 0;
		int i = 0;
		while (i < chars.length)
		{
			int end = groupEnd(i);
			int groupWidth = (end - i) * cell;
			if (x > 0 && x + groupWidth > room)
			{
				x = 0;
				y++;
			}
			for (int j = i; j < end; j++)
			{
				column[j] = x + (j - i) * cell;
				row[j] = y;
			}
			x += groupWidth + gap;
			i = end;
		}
		rows = y + 1;
	}

	/** The end of the group that starts at {@code i}: the command, the space after it, then four letters at a time. */
	private int groupEnd(int i)
	{
		int space = message.indexOf(' ');
		if (space > 0 && i < space)
		{
			return space;
		}
		if (i == space)
		{
			return i + 1;
		}
		int codeStart = space < 0 ? 0 : space + 1;
		return Math.min(chars.length, codeStart + ((i - codeStart) / GROUP + 1) * GROUP);
	}

	@Override
	public Dimension getPreferredSize()
	{
		int width = getWidth() > 0 ? getWidth() : DEFAULT_WIDTH;
		layOut(width);
		return new Dimension(DEFAULT_WIDTH, rows * lineHeight + 2 * INSET - 2);
	}

	@Override
	public void setBounds(int x, int y, int width, int height)
	{
		int before = rows;
		super.setBounds(x, y, width, height);
		layOut(width);
		if (rows != before)
		{
			// A new width that changes the number of lines needs a new height.
			revalidate();
		}
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics;
		layOut(getWidth());
		PanelTheme.paintCard(g, getWidth(), getHeight(), PanelTheme.FIELD, PanelTheme.EDGE, null);
		g.setFont(getFont());
		int space = message.indexOf(' ');
		for (int i = 0; i < chars.length; i++)
		{
			int x = INSET + column[i];
			int top = INSET - 1 + row[i] * lineHeight;
			int base = top + ascent;
			if (current && i < marked && marks[i] != 0)
			{
				boolean right = marks[i] == 1;
				g.setColor(right ? PanelTheme.GOOD : PanelTheme.BAD);
				if (!right)
				{
					g.fillRect(x, base + 2, cell - 1, 2);
				}
			}
			else if (current && i == marked)
			{
				g.setColor(PanelTheme.ACCENT);
				g.fillRect(x, base + 2, cell - 1, 2);
			}
			else if (i < space)
			{
				g.setColor(PanelTheme.TEXT_DIM);
			}
			else
			{
				g.setColor(Character.isDigit(chars[i]) ? PanelTheme.ACCENT : PanelTheme.TEXT);
			}
			if (i == space)
			{
				// The space after the command, as a small mark so it is not missed.
				g.fillRect(x + 1, base - 1, cell - 2, 1);
				g.fillRect(x + 1, base - 3, 1, 2);
				g.fillRect(x + cell - 2, base - 3, 1, 2);
			}
			else
			{
				g.drawChars(chars, i, 1, x, base);
			}
		}
	}

	/** The number of lines for the last width, for tests. */
	int rows()
	{
		layOut(getWidth() > 0 ? getWidth() : DEFAULT_WIDTH);
		return rows;
	}

	/** The line a letter is on, for tests. */
	int rowOf(int index)
	{
		rows();
		return row[index];
	}
}
