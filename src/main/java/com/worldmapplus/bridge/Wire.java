package com.worldmapplus.bridge;

import java.util.*;
import java.util.List;
import javax.annotation.*;
import net.runelite.api.coords.*;

/**
 * The one place values from other plugins are read. Every value is untrusted: a value of the wrong type reads as missing (an empty
 * text, null, an empty list), lists are copied before they are read, texts are cleaned and cut, and counts are capped by the caller.
 */
final class Wire
{
	static final int MAX_TEXT = 120;
	static final int MAX_ID = 64;

	private Wire()
	{
	}

	/** A text cut to {@value #MAX_TEXT} characters and trimmed. Anything but a String gives an empty text. */
	static String text(@Nullable Object value)
	{
		return label(value, MAX_TEXT);
	}

	/**
	 * A short text for a menu entry, a layer, a hint or a card: no control or format characters, runs of white space made one space,
	 * the bar that separates names in a setting taken out, and cut to {@code max}. Anything but a String gives an empty text.
	 */
	static String label(@Nullable Object value, int max)
	{
		if (!(value instanceof String))
		{
			return "";
		}
		String raw = (String) value;
		StringBuilder out = new StringBuilder(Math.min(raw.length(), max));
		boolean space = false;
		for (int i = 0; i < raw.length() && out.length() < max; i++)
		{
			char c = raw.charAt(i);
			if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '|' || Character.getType(c) == Character.FORMAT)
			{
				space = out.length() > 0;
				continue;
			}
			if (space)
			{
				if (out.length() >= max - 1)
				{
					break;
				}
				out.append(' ');
			}
			space = false;
			out.append(c);
		}
		return out.toString();
	}

	/** An id: 1 to {@value #MAX_ID} characters from {@code A-Z a-z 0-9 _ . : -}, else an empty text. */
	static String id(@Nullable Object value)
	{
		if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > MAX_ID)
		{
			return "";
		}
		String id = (String) value;
		for (int i = 0; i < id.length(); i++)
		{
			char c = id.charAt(i);
			if (!(Character.isLetterOrDigit(c) && c < 128) && c != '_' && c != '-' && c != '.' && c != ':')
			{
				return "";
			}
		}
		return id;
	}

	@Nullable
	static WorldPoint point(@Nullable Object value)
	{
		return value instanceof WorldPoint ? (WorldPoint) value : null;
	}

	/** A copy of a list's (or any collection's) items (an int array's too), or none for anything else. The sender's list may change while it is read. */
	static Object[] list(@Nullable Object value)
	{
		try
		{
			return value instanceof int[] ? Arrays.stream((int[]) value).boxed().toArray() : value instanceof Collection ? ((Collection<?>) value).toArray()
				: new Object[0];
		}
		catch (RuntimeException e)
		{
			return new Object[0];
		}
	}

	/** The texts of a list, empty ones left out, at most {@code max}. */
	static List<String> strings(@Nullable Object value, int max)
	{
		List<String> result = new ArrayList<>();
		for (Object item : list(value))
		{
			String text = text(item);
			if (!text.isEmpty() && result.size() < max)
			{
				result.add(text);
			}
		}
		return result;
	}

	/** The whole numbers of a list, or null when any item is not a number or there are more than {@code max}. */
	@Nullable
	static List<Long> longs(@Nullable Object value, int max)
	{
		Object[] items = list(value);
		List<Long> result = new ArrayList<>(items.length);
		for (Object item : items)
		{
			if (!(item instanceof Number) || result.size() >= max)
			{
				return null;
			}
			result.add(((Number) item).longValue());
		}
		return result;
	}

	/** The tiles of a list of WorldPoints, or an empty list when any item is not one or there are more than {@code max}. */
	static List<WorldPoint> tiles(@Nullable Object value, int max)
	{
		List<WorldPoint> tiles = new ArrayList<>();
		for (Object item : list(value))
		{
			if (!(item instanceof WorldPoint) || tiles.size() >= max)
			{
				return Collections.emptyList();
			}
			tiles.add((WorldPoint) item);
		}
		return tiles;
	}

	/** A finite number, or NaN for anything else. */
	static double number(@Nullable Object value)
	{
		double d = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
		return Double.isInfinite(d) ? Double.NaN : d;
	}

	/** A map of the keys and values given in turn; a null value is left out. */
	static Map<String, Object> map(Object... keysAndValues)
	{
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i + 1 < keysAndValues.length; i += 2)
		{
			if (keysAndValues[i + 1] != null)
			{
				map.put((String) keysAndValues[i], keysAndValues[i + 1]);
			}
		}
		return map;
	}

	/** A copy of a message's data, or null when it cannot be read whole. */
	@Nullable
	static Map<String, Object> copy(@Nullable Map<String, Object> data)
	{
		try
		{
			return data == null ? Collections.emptyMap() : new HashMap<>(data);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** At most this many uses in any one second. */
	static final class Gate
	{
		private final long[] times;
		private int next;

		Gate(int perSecond)
		{
			times = new long[perSecond];
		}

		synchronized boolean allow(long now)
		{
			if (times[next] != 0 && now - times[next] < 1_000)
			{
				return false;
			}
			times[next] = now;
			next = (next + 1) % times.length;
			return true;
		}
	}
}
