package com.worldmapplus.ui;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;

/** Lets the preview tools press the layers button, which only code in this package can place. */
public final class MapButtonsProbe
{
	private MapButtonsProbe()
	{
	}

	/** Presses the layers button where the surface put it in its last frame, so it is right at any map size. */
	public static void openLayers(MapSurface surface, JPanel source, Rectangle bounds)
	{
		Rectangle layers = surface.buttons().boundsOf(MapButtons.Button.LAYERS);
		if (layers.isEmpty())
		{
			MapButtons probe = new MapButtons();
			probe.layout(bounds);
			layers = probe.boundsOf(MapButtons.Button.LAYERS);
		}
		int x = layers.x + 5;
		int y = layers.y + 5;
		surface.getInput().mousePressed(new MouseEvent(source, MouseEvent.MOUSE_PRESSED, 1000, 0, x, y, 1, false,
			MouseEvent.BUTTON1));
		surface.getInput().mouseReleased(new MouseEvent(source, MouseEvent.MOUSE_RELEASED, 1016, 0, x, y, 1, false,
			MouseEvent.BUTTON1));
	}
}
