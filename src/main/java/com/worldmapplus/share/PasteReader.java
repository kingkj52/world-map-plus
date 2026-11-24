package com.worldmapplus.share;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds the share codes in text a player pasted, from Discord or anywhere else (round 52). The code is what counts: it
 * may come with or without {@code !wmp}, inside quotes, backticks or other words, on one line or many, in upper or lower
 * case. A code is a run of letters and digits that starts with a kind letter ({@code p}, {@code r}, {@code s} or
 * {@code c}) and a version digit; anything else is a word and is passed over. The parts of a long code may be pasted
 * one per line, all on one line with spaces, in any order, or run together with nothing between them: the part format
 * gives each part's number and the number of parts, and the split rule of {@code ShareCodec.split} gives every part's
 * length, so parts run together are split in exactly one way or not at all.
 * <p>
 * Bounded: at most {@link #MAX_CHARS} characters are looked at and at most {@link #MAX_CODES} codes taken. Plain logic, no
 * RuneLite types.
 */
public final class PasteReader
{
	/** The longest pasted text looked at. */
	public static final int MAX_CHARS = 20_000;

	/** Most codes taken from one paste. */
	public static final int MAX_CODES = 32;

	/** The shortest run taken as a code: a part's head and one character. */
	static final int MIN_CODE = ShareCodec.PART_HEAD + 1;

	/** What a paste held. */
	public static final class Found
	{
		private final List<String> codes = new ArrayList<>();
		private final List<String> problems = new ArrayList<>();
		private boolean old;
		private boolean cut;

		/** The codes and parts found, in the order they came, in lower case. */
		public List<String> getCodes()
		{
			return Collections.unmodifiableList(codes);
		}

		/** Sentences about runs that look like codes and cannot be taken (newer version, run together, a wrong letter). */
		public List<String> getProblems()
		{
			return Collections.unmodifiableList(problems);
		}

		/** True when the paste holds a code of the old format, which this version does not read. */
		public boolean hasOld()
		{
			return old;
		}

		/** True when there was more than was looked at. */
		public boolean isCut()
		{
			return cut;
		}
	}

	private PasteReader()
	{
	}

	/** Reads pasted text. Null or empty text holds nothing. */
	public static Found read(String text)
	{
		Found found = new Found();
		if (text == null)
		{
			return found;
		}
		int end = Math.min(text.length(), MAX_CHARS);
		found.cut = text.length() > MAX_CHARS;
		int i = 0;
		while (i < end && found.codes.size() < MAX_CODES)
		{
			if (!isWordChar(text.charAt(i)))
			{
				i++;
				continue;
			}
			int start = i;
			while (i < end && isWordChar(text.charAt(i)))
			{
				i++;
			}
			take(text.substring(start, i), found);
		}
		found.cut |= i < end;
		return found;
	}

	private static boolean isWordChar(char c)
	{
		return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
	}

	/** Looks at one run of letters and digits. */
	private static void take(String run, Found found)
	{
		String word = run.toLowerCase(Locale.ROOT);
		// "!wmpp10..." with the space left out: the command is dropped and the rest read.
		if (word.startsWith("wmp") && looksLikeStart(word.substring(3)))
		{
			word = word.substring(3);
		}
		if (!looksLikeStart(word))
		{
			found.old |= word.length() > 8 && word.startsWith("wmp");
			return;
		}
		if (word.length() > ShareCodec.MAX_CODE + ShareCodec.MAX_PARTS * ShareCodec.PART_HEAD)
		{
			found.problems.add("A code is longer than any code can be, so it was not read.");
			return;
		}
		int version = word.charAt(1) - '0';
		if (version > ShareCodec.VERSION)
		{
			if (word.length() >= 10 && allCodeChars(word))
			{
				found.problems.add("A code was made by a newer version of World Map Plus. Update the plugin to read it.");
			}
			return;
		}
		int wrong = firstWrongChar(word);
		if (wrong >= 0)
		{
			found.problems.add("A code has a letter that codes never use (" + word.charAt(wrong) + ") at character " + (wrong + 1)
				+ ". Check what you typed.");
			return;
		}
		if (ShareCodec.isPart(word) && word.length() > ShareCodec.MAX_SINGLE)
		{
			List<String> parts = splitRunTogether(word);
			if (parts == null)
			{
				found.problems.add("Some parts are run together and could not be split. Put each part on its own line.");
				return;
			}
			for (String part : parts)
			{
				if (found.codes.size() < MAX_CODES)
				{
					found.codes.add(part);
				}
			}
			return;
		}
		found.codes.add(word);
	}

	/** A kind letter and a version digit of 1 or more, then enough to be a code. */
	private static boolean looksLikeStart(String word)
	{
		return word.length() >= MIN_CODE && "prsc".indexOf(word.charAt(0)) >= 0 && word.charAt(1) >= '1' && word.charAt(1) <= '9';
	}

	private static boolean allCodeChars(String word)
	{
		return firstWrongChar(word) < 0;
	}

	private static int firstWrongChar(String word)
	{
		for (int i = 0; i < word.length(); i++)
		{
			if (ShareCodec.value(word.charAt(i)) < 0)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Splits parts that were pasted with nothing between them, or returns null when that cannot be done in exactly one
	 * way. Every part starts {@code c1} and three head characters with its number and the number of parts. With {@code T}
	 * parts and the whole code {@code L} long, the run is {@code L + 5T} long, every part but the last carries
	 * {@code ceil(L / T)} characters, the last the rest, and {@code T} must be the number the code's length needs. So the
	 * length of each piece follows from its own head, and the pieces may be in any order.
	 */
	static List<String> splitRunTogether(String run)
	{
		ShareCodec.Part first;
		try
		{
			first = ShareCodec.part(run.substring(0, Math.min(run.length(), ShareCodec.MAX_SINGLE)));
		}
		catch (ShareException e)
		{
			return null;
		}
		int total = first.getTotal();
		int length = run.length() - ShareCodec.PART_HEAD * total;
		if (length <= ShareCodec.MAX_SINGLE || (length + ShareCodec.MAX_PART_DATA - 1) / ShareCodec.MAX_PART_DATA != total)
		{
			return null;
		}
		int size = (length + total - 1) / total;
		int last = length - (total - 1) * size;
		if (last <= 0)
		{
			return null;
		}
		List<String> parts = new ArrayList<>(total);
		boolean[] seen = new boolean[total];
		int at = 0;
		while (at < run.length())
		{
			if (run.length() - at <= ShareCodec.PART_HEAD)
			{
				return null;
			}
			ShareCodec.Part part;
			try
			{
				part = ShareCodec.part(run.substring(at, at + ShareCodec.PART_HEAD + 1));
			}
			catch (ShareException e)
			{
				return null;
			}
			if (part.getTotal() != total || part.getTransfer() != first.getTransfer() || seen[part.getIndex()])
			{
				return null;
			}
			int piece = ShareCodec.PART_HEAD + (part.getIndex() == total - 1 ? last : size);
			if (at + piece > run.length())
			{
				return null;
			}
			seen[part.getIndex()] = true;
			parts.add(run.substring(at, at + piece));
			at += piece;
		}
		return parts.size() == total ? parts : null;
	}
}
