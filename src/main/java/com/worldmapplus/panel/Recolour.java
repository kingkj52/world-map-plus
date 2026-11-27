package com.worldmapplus.panel;

import com.worldmapplus.theme.*;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import net.runelite.client.ui.components.*;

/**
 * Moves a built panel from one theme to another: every colour that is a palette colour of the old theme becomes the same
 * name's colour in the new one. Colours that are not in the old palette (a place's own colour, the look and feel's)
 * are left alone. Runs on the interface thread, once, when the theme changes.
 */
final class Recolour
{
	private final Palette from;
	private final Palette to;

	private Recolour(Palette from, Palette to)
	{
		this.from = from;
		this.to = to;
	}

	static void apply(Component root, Palette from, Palette to)
	{
		if (from != to)
		{
			new Recolour(from, to).walk(root);
		}
	}

	Color map(Color colour)
	{
		if (colour == null)
		{
			return null;
		}
		String role = from.nameOf(colour);
		return role == null ? colour : to.get(role);
	}

	private void walk(Component c)
	{
		if (c.isForegroundSet())
		{
			c.setForeground(map(c.getForeground()));
		}
		if (c.isBackgroundSet())
		{
			c.setBackground(map(c.getBackground()));
		}
		if (c instanceof PanelTheme.Card)
		{
			((PanelTheme.Card) c).recolour(this::map);
		}
		if (c instanceof JTextComponent)
		{
			JTextComponent text = (JTextComponent) c;
			text.setCaretColor(map(text.getCaretColor()));
		}
		if (c instanceof IconTextField)
		{
			((IconTextField) c).setHoverBackgroundColor(to.get("CARD_HOVER"));
		}
		if (c instanceof JScrollPane)
		{
			Border border = ((JScrollPane) c).getBorder();
			if (border instanceof LineBorder)
			{
				((JScrollPane) c).setBorder(BorderFactory.createLineBorder(map(((LineBorder) border).getLineColor())));
			}
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				walk(child);
			}
		}
		c.repaint();
	}
}
