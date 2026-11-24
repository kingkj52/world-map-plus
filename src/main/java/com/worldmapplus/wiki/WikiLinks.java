package com.worldmapplus.wiki;

import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Makes the address of a page of the Old School RuneScape Wiki from a title, and recognises one.
 * <p>
 * This builds a text and nothing else: it makes no request, and the plugin makes none either (the address is handed to RuneLite's link
 * browser when the player presses a button, see {@link WikiBrowser}). There is exactly one place such an address can point to: the
 * wiki's own article path. A title is bounded in length, may only hold letters, digits, spaces and a few marks that real titles use,
 * has its spaces turned into underscores and every other mark encoded, and anything else gives no address at all.
 */
public final class WikiLinks
{
	/** The only host an address is made for. */
	public static final String HOST = "oldschool.runescape.wiki";

	/** What every address starts with. */
	public static final String PREFIX = "https://" + HOST + "/w/";

	/** What every address of the wiki's search page starts with: a fixed path and the one query key, then the name. */
	public static final String SEARCH_PREFIX = "https://" + HOST + "/w/Special:Search?search=";

	/** The longest title that is turned into an address, in characters. Wiki titles are far shorter than this. */
	public static final int MAX_TITLE = 100;

	/** Marks other than letters, digits and spaces that a title may hold. They are encoded where the address needs it. */
	private static final String MARKS = "'’-._,()!&:/+?\"";

	/** Marks written as they are in an address. */
	private static final String PLAIN = "-_.~'(),!:/";

	private WikiLinks()
	{
	}

	/**
	 * The address of the wiki page with this title, or null when the title is empty, too long or holds anything that is not allowed.
	 * Spaces become underscores and nothing else about the spelling is changed.
	 */
	@Nullable
	public static String url(@Nullable String title)
	{
		if (title == null)
		{
			return null;
		}
		String page = title.trim();
		if (page.isEmpty() || page.length() > MAX_TITLE || page.startsWith(":") || page.startsWith("/") || page.contains("//"))
		{
			return null;
		}
		for (String segment : page.split("/", -1))
		{
			// A segment that is only dots would climb out of the article path.
			if (segment.equals(".") || segment.equals(".."))
			{
				return null;
			}
		}
		StringBuilder out = new StringBuilder(PREFIX.length() + page.length() + 8);
		out.append(PREFIX);
		for (int i = 0; i < page.length(); i++)
		{
			char c = page.charAt(i);
			if (c == ' ')
			{
				out.append('_');
			}
			else if (c < 128 && (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || PLAIN.indexOf(c) >= 0))
			{
				out.append(c);
			}
			else if (Character.isLetterOrDigit(c) || MARKS.indexOf(c) >= 0)
			{
				for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8))
				{
					out.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4 & 0xF, 16)))
						.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
				}
			}
			else
			{
				return null;
			}
		}
		return out.toString();
	}

	/**
	 * The address of the wiki's search page for a name, or null when the name is empty, too long or holds a control character. The name
	 * is the value of the one query key: letters, digits and {@code -_.~} as they are, a space as {@code +}, and every other character
	 * percent encoded from its UTF-8 bytes, so nothing in the name can add a key, a fragment or a path.
	 */
	@Nullable
	public static String searchUrl(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		String text = name.trim();
		if (text.isEmpty() || text.length() > MAX_TITLE)
		{
			return null;
		}
		StringBuilder out = new StringBuilder(SEARCH_PREFIX.length() + text.length() * 3);
		out.append(SEARCH_PREFIX);
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (Character.isISOControl(c) || Character.isSurrogate(c))
			{
				return null;
			}
			if (c == ' ')
			{
				out.append('+');
			}
			else if (isUnreserved(c))
			{
				out.append(c);
			}
			else
			{
				for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8))
				{
					out.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4 & 0xF, 16)))
						.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
				}
			}
		}
		return out.toString();
	}

	private static boolean isUnreserved(char c)
	{
		return c < 128 && (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.'
			|| c == '~');
	}

	/**
	 * True only for an address {@link #searchUrl} could have made: {@link #SEARCH_PREFIX}, and after it a value of bounded length made
	 * of letters, digits, {@code -_.~}, {@code +} and percent signs followed by two hexadecimal digits. So there is no other host, path,
	 * key, fragment, user or port. {@link WikiBrowser} checks this once more before handing an address on.
	 */
	public static boolean isWikiSearchAddress(@Nullable String address)
	{
		if (address == null || !address.startsWith(SEARCH_PREFIX) || address.length() > SEARCH_PREFIX.length() + MAX_TITLE * 9)
		{
			return false;
		}
		String value = address.substring(SEARCH_PREFIX.length());
		if (value.isEmpty())
		{
			return false;
		}
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (c == '%')
			{
				if (i + 2 >= value.length() || Character.digit(value.charAt(i + 1), 16) < 0 || Character.digit(value.charAt(i + 2), 16) < 0)
				{
					return false;
				}
				i += 2;
			}
			else if (c != '+' && !isUnreserved(c))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * True only for an address that this class could have made: {@link #PREFIX}, and after it a page name of no more than the bounded
	 * length made of the marks {@link #url} writes as they are and percent signs followed by two hexadecimal digits. So there is no user,
	 * port, query or fragment, no other host, and no path segment that climbs. {@link WikiBrowser} checks this once more before
	 * handing an address on.
	 */
	public static boolean isWikiAddress(@Nullable String address)
	{
		if (address == null || !address.startsWith(PREFIX) || address.length() > PREFIX.length() + MAX_TITLE * 9)
		{
			return false;
		}
		String page = address.substring(PREFIX.length());
		if (page.isEmpty() || page.startsWith("/") || page.contains("//"))
		{
			return false;
		}
		for (int i = 0; i < page.length(); i++)
		{
			char c = page.charAt(i);
			if (c == '%')
			{
				if (i + 2 >= page.length())
				{
					return false;
				}
				if (Character.digit(page.charAt(i + 1), 16) < 0 || Character.digit(page.charAt(i + 2), 16) < 0)
				{
					return false;
				}
				i += 2;
			}
			else if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || PLAIN.indexOf(c) >= 0))
			{
				return false;
			}
		}
		for (String segment : page.split("/", -1))
		{
			if (segment.equals(".") || segment.equals("..") || segment.equalsIgnoreCase("%2e") || segment.equalsIgnoreCase("%2e%2e"))
			{
				return false;
			}
		}
		return true;
	}
}
