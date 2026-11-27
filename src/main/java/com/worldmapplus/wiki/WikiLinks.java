package com.worldmapplus.wiki;

import java.nio.charset.*;
import javax.annotation.*;
import net.runelite.client.util.*;

/**
 * Makes the address of a page of the Old School RuneScape Wiki, or of its search page, and hands it to the player's own browser.
 * <p>
 * The page names come from the bundled data, worked out at build time. An address is the wiki's fixed article path (or its search
 * page and the one query key) followed by the name with letters, digits and a few marks as they are, a space as {@code _} (or
 * {@code +} for a search) and every other byte of the name percent encoded, so nothing in a name can change the host, add a key or a
 * fragment. The plugin makes no request: {@link #browse} hands the address to RuneLite's {@link LinkBrowser}, whose {@code browse}
 * the Plugin Hub allows (its rejected features page forbids {@code open} and {@code Desktop} by name), and the player's browser opens it.
 */
public final class WikiLinks
{
	/** What every address starts with. */
	static final String PREFIX = "https://oldschool.runescape.wiki/w/";

	/** The longest name that is turned into an address, in characters. Wiki titles are far shorter than this. */
	static final int MAX_TITLE = 100;

	private static final String HEX = "0123456789ABCDEF";

	private WikiLinks()
	{
	}

	/** The address of the wiki page with this title, or null when the title is empty or too long. Spaces become underscores. */
	@Nullable
	public static String url(@Nullable String title)
	{
		return address(PREFIX, title, '_', "-_.~'(),!:/");
	}

	/** The address of the wiki's search page for a name, or null when the name is empty or too long. */
	@Nullable
	public static String searchUrl(@Nullable String name)
	{
		return address(PREFIX + "Special:Search?search=", name, '+', "-_.~");
	}

	/** The name to look up on the wiki's search page, which never answers with a missing page, or null when it makes no address. */
	@Nullable
	public static String searchName(@Nullable String name)
	{
		return searchUrl(name) == null ? null : name.trim();
	}

	@Nullable
	private static String address(String prefix, @Nullable String name, char space, String plain)
	{
		String text = name == null ? "" : name.trim();
		if (text.isEmpty() || text.length() > MAX_TITLE)
		{
			return null;
		}
		StringBuilder out = new StringBuilder(prefix);
		for (byte b : text.getBytes(StandardCharsets.UTF_8))
		{
			int c = b & 0xFF;
			if (c == ' ')
			{
				out.append(space);
			}
			else if (c < 128 && (Character.isLetterOrDigit(c) || plain.indexOf(c) >= 0))
			{
				out.append((char) c);
			}
			else
			{
				out.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
			}
		}
		return out.toString();
	}

	/** Opens an address made here in the player's browser. Returns false, and does nothing, for none. */
	public static boolean browse(@Nullable String address)
	{
		if (address == null || !address.startsWith(PREFIX))
		{
			return false;
		}
		LinkBrowser.browse(address);
		return true;
	}
}
