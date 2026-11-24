package com.worldmapplus.search;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Reads a typed tile such as {@code 3222 3218}, {@code 3222, 3218, 1} or {@code (3222,3218)}, the way
 * coordinates are written on the wiki and by other tools, so the search bar can jump straight to them.
 */
public final class TileQuery
{
	/** The smallest x or y that is taken for a coordinate, so a short number typed to search is not one. */
	static final int MIN_COORDINATE = 100;

	/** The largest, which is past every region of the game. */
	static final int MAX_COORDINATE = 16_383;

	private static final Pattern TILE = Pattern.compile(
		"^\\(?\\s*(\\d{3,5})\\s*[,;\\s]\\s*(\\d{3,5})\\s*(?:[,;\\s]\\s*([0-3]))?\\s*\\)?$");

	private TileQuery()
	{
	}

	/** The tile typed, as {x, y, plane}, or null when the text is not one. */
	@Nullable
	public static int[] parse(@Nullable String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = TILE.matcher(text.trim());
		if (!m.matches())
		{
			return null;
		}
		int x = Integer.parseInt(m.group(1));
		int y = Integer.parseInt(m.group(2));
		int plane = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
		if (x < MIN_COORDINATE || y < MIN_COORDINATE || x > MAX_COORDINATE || y > MAX_COORDINATE)
		{
			return null;
		}
		return new int[] {x, y, plane};
	}
}
