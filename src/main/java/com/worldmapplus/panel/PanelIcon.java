package com.worldmapplus.panel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** The side panel's toolbar icon, a map pin, drawn here so the plugin needs no extra image file. */
final class PanelIcon
{
	private static final int SIZE = 16;

	private PanelIcon()
	{
	}

	static BufferedImage create()
	{
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Path2D pin = new Path2D.Double();
			pin.moveTo(8, 15);
			pin.curveTo(3, 9.5, 2.5, 7.5, 2.5, 6);
			pin.curveTo(2.5, 3, 5, 1, 8, 1);
			pin.curveTo(11, 1, 13.5, 3, 13.5, 6);
			pin.curveTo(13.5, 7.5, 13, 9.5, 8, 15);
			pin.closePath();
			g.setColor(new Color(230, 60, 140));
			g.fill(pin);
			g.setColor(new Color(255, 255, 255));
			g.setStroke(new BasicStroke(1.1f));
			g.draw(pin);
			g.fill(new Ellipse2D.Double(6, 4, 4, 4));
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
