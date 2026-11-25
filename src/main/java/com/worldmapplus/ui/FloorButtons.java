package com.worldmapplus.ui;

import com.worldmapplus.layers.*;
import java.awt.*;
import javax.annotation.*;
import lombok.*;

/**
 * Two buttons above the corner column that move the map up and down a floor.
 * <p>
 * Like the other buttons they are the plugin's own drawing and hit testing. They are shown only when
 * the viewed map has floors above the ground. Nothing here touches the game.
 */
public final class FloorButtons
{
	public enum Button
	{
		UP,
		DOWN
	}

	private final Rectangle[] rects = new Rectangle[Button.values().length];
	@Getter
	private boolean shown;

	/** Whether the buttons are in use. Hidden buttons are neither drawn nor hit. */
	public void setShown(boolean shown)
	{
		this.shown = shown;
	}

	/** Stacks the pair directly above another button, in the same column. */
	public void layout(Rectangle above)
	{
		int step = MapButtons.SIZE + MapButtons.GAP;
		rects[Button.UP.ordinal()] = new Rectangle(above.x, above.y - 2 * step, MapButtons.SIZE, MapButtons.SIZE);
		rects[Button.DOWN.ordinal()] = new Rectangle(above.x, above.y - step, MapButtons.SIZE, MapButtons.SIZE);
	}

	/** Places each button in the slot the corner stack gave it. An empty rectangle leaves a button out. */
	public void layout(Rectangle up, Rectangle down)
	{
		rects[Button.UP.ordinal()] = up == null || up.isEmpty() ? null : new Rectangle(up);
		rects[Button.DOWN.ordinal()] = down == null || down.isEmpty() ? null : new Rectangle(down);
	}

	@Nullable
	public Button hit(int x, int y)
	{
		if (!shown)
		{
			return null;
		}
		for (Button button : Button.values())
		{
			Rectangle rect = rects[button.ordinal()];
			if (rect != null && rect.contains(x, y))
			{
				return button;
			}
		}
		return null;
	}

	public Rectangle boundsOf(Button button)
	{
		Rectangle rect = rects[button.ordinal()];
		return rect == null ? new Rectangle() : new Rectangle(rect);
	}

	/** The rectangle around both buttons, for placing a label beside them. */
	public Rectangle bounds()
	{
		Rectangle up = rects[Button.UP.ordinal()];
		Rectangle down = rects[Button.DOWN.ordinal()];
		return up == null || down == null ? new Rectangle() : up.union(down);
	}

	/**
	 * Draws the pair. An arrow is dim when there is no floor in that direction.
	 *
	 * @param floor the floor on show
	 * @param top the highest floor there is
	 */
	public void paint(Graphics2D g, int floor, int top, @Nullable Button hovered)
	{
		if (!shown)
		{
			return;
		}
		g.setStroke(new BasicStroke(1f));
		for (Button button : Button.values())
		{
			Rectangle rect = rects[button.ordinal()];
			if (rect == null)
			{
				continue;
			}
			boolean usable = button == Button.UP ? floor < top : floor > 0;
			g.setColor(button == hovered && usable ? Theme.FLOOR_HOVER : Theme.FLOOR_FILL);
			g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 6, 6);
			g.setColor(Theme.BORDER);
			g.drawRoundRect(rect.x, rect.y, rect.width - 1, rect.height - 1, 6, 6);

			int cx = rect.x + rect.width / 2;
			int cy = rect.y + rect.height / 2;
			int dir = button == Button.UP ? -1 : 1;
			int[] xs = {cx - 7, cx + 7, cx};
			int[] ys = {cy - 3 * dir, cy - 3 * dir, cy + 5 * dir};
			g.setColor(usable ? Theme.TEXT : Theme.ARROW_OFF);
			g.fillPolygon(xs, ys, 3);
		}
	}

	/** What the floor is called on the map, in the game's UK words: Ground floor, 1st floor, 2nd floor, 3rd floor. */
	public static String name(int floor)
	{
		return FloorNames.name(floor);
	}
}
