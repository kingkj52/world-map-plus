package com.worldmapplus.ui;

import com.worldmapplus.data.*;
import java.awt.*;
import java.util.function.*;
import javax.annotation.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;

/**
 * Reads which entry of the game's own map key was clicked.
 * <p>
 * The key lists the kinds of icon the map has, and clicking an entry makes the game flash that kind on its own
 * map. That map is under this one and cannot be seen, so the click is read here and the same kind is flashed
 * on this map. Each entry carries, in the click handler the game gave it, the number of the category it stands
 * for. Reading it is all this does: nothing on the game's side is changed or clicked.
 */
@Slf4j
final class KeyEntries
{
	/** Presses on the key that matched nothing, which are described in the log so that a missing entry can be traced. */
	private static int unmatchedLogged;

	private static final int MAX_UNMATCHED_LOGGED = 6;

	/** The tallest a widget can be and still be taken for a row of the key. */
	private static final int MAX_ROW_HEIGHT = 48;

	/** How deep into the entry widgets to look. The entries are a layer holding a few children. */
	private static final int MAX_DEPTH = 4;

	private KeyEntries()
	{
	}

	/**
	 * The category of the key entry at a canvas point, or null when the point is not on one.
	 *
	 * @param known says whether a number is one of the categories this map has, which keeps a click handler
	 * that is not an entry's from being taken for one
	 */
	@Nullable
	static Integer categoryAt(Client client, int x, int y, MapElements elements)
	{
		Widget entries = client.getWidget(InterfaceID.Worldmap.KEY_ENTRIES);
		if (entries == null || entries.isHidden())
		{
			return null;
		}
		Integer found = search(entries, x, y, elements, 0);
		if (found == null)
		{
			// The click may have landed on the row's icon while the words and the handler sit on other widgets of the
			// same row, so every widget level with the click is tried.
			found = inRow(entries, y, elements, 0);
		}
		if (found == null && unmatchedLogged < MAX_UNMATCHED_LOGGED)
		{
			unmatchedLogged++;
			StringBuilder seen = new StringBuilder();
			describe(entries, y, seen, 0);
			log.info("World Map Plus: a press at {},{} in the map key matched no category. Widgets in that row: {}", x, y, seen);
		}
		return found;
	}

	/** The category of any widget in the row at a height, found by its handler or its words. */
	@Nullable
	private static Integer inRow(Widget widget, int y, MapElements elements, int depth)
	{
		if (widget == null || widget.isHidden() || depth > MAX_DEPTH)
		{
			return null;
		}
		if (levelWith(widget, y))
		{
			Integer own = categoryOf(widget.getOnOpListener(), elements::isCategory);
			if (own == null)
			{
				own = elements.categoryNamed(plainText(widget));
			}
			if (own != null)
			{
				return own;
			}
		}
		for (Widget[] group : new Widget[][] {widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()})
		{
			if (group == null)
			{
				continue;
			}
			for (Widget child : group)
			{
				Integer found = inRow(child, y, elements, depth + 1);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static boolean levelWith(Widget widget, int y)
	{
		Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.height > 0 && bounds.height <= MAX_ROW_HEIGHT && y >= bounds.y && y < bounds.y + bounds.height;
	}

	/** Writes what a row of the key holds, for the log. */
	private static void describe(Widget widget, int y, StringBuilder out, int depth)
	{
		if (widget == null || depth > MAX_DEPTH || out.length() > 600)
		{
			return;
		}
		if (levelWith(widget, y))
		{
			Object[] handler = widget.getOnOpListener();
			out.append('[').append(widget.getType()).append(" text=").append(plainText(widget)).append(" handler=")
				.append(handler == null ? "none" : Integer.toString(handler.length)).append("] ");
		}
		for (Widget[] group : new Widget[][] {widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()})
		{
			if (group != null)
			{
				for (Widget child : group)
				{
					describe(child, y, out, depth + 1);
				}
			}
		}
	}

	@Nullable
	private static Integer search(Widget widget, int x, int y, MapElements elements, int depth)
	{
		if (widget == null || widget.isHidden() || depth > MAX_DEPTH)
		{
			return null;
		}
		Rectangle bounds = widget.getBounds();
		boolean inside = bounds != null && bounds.contains(x, y);
		if (inside)
		{
			Integer own = categoryOf(widget.getOnOpListener(), elements::isCategory);
			if (own == null)
			{
				// The handler may sit on another widget of the row, so the row's own words are tried: the entry's
				// text is the name the map key gives the category.
				own = elements.categoryNamed(plainText(widget));
			}
			if (own != null)
			{
				return own;
			}
		}
		for (Widget[] group : new Widget[][] {widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()})
		{
			if (group == null)
			{
				continue;
			}
			for (Widget child : group)
			{
				Integer found = search(child, x, y, elements, depth + 1);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** The words of a widget with any colour tags taken out, or null when it has none. */
	@Nullable
	static String plainText(Widget widget)
	{
		String text = widget.getText();
		if (text == null || text.isEmpty())
		{
			return null;
		}
		String plain = text.replaceAll("<[^>]*>", "").trim();
		return plain.isEmpty() ? null : plain;
	}

	/**
	 * The category in a click handler. The handler is the number of a script followed by the arguments it is
	 * given, and the category is one of those arguments, so the first whole number after the script that is a
	 * category this map knows is taken.
	 */
	@Nullable
	static Integer categoryOf(@Nullable Object[] handler, IntPredicate known)
	{
		if (handler == null)
		{
			return null;
		}
		for (int i = 1; i < handler.length; i++)
		{
			if (handler[i] instanceof Integer && known.test((Integer) handler[i]))
			{
				return (Integer) handler[i];
			}
		}
		return null;
	}
}
