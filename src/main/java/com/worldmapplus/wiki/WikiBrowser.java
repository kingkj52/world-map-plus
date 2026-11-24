package com.worldmapplus.wiki;

import javax.annotation.Nullable;
import net.runelite.client.util.LinkBrowser;

/**
 * Opens a page of the Old School RuneScape Wiki in the player's own web browser.
 * <p>
 * The one place in the plugin that uses RuneLite's {@link LinkBrowser}, and the only call it makes is {@code browse}, which the Plugin
 * Hub allows (its rejected features page forbids {@code open} and {@code Desktop}, and says so by name). The player's browser makes the
 * request, never the plugin. The address is made by {@link WikiLinks} and checked again here, so nothing but a page of the wiki's own
 * article path can ever be opened, and nothing happens at all for a title that is not a clean page name.
 */
public final class WikiBrowser
{
	private WikiBrowser()
	{
	}

	/** Opens the page with this title. Returns false, and does nothing, when the title does not make an address. */
	public static boolean open(@Nullable String title)
	{
		return browse(WikiLinks.url(title));
	}

	/**
	 * Opens the wiki's own search page for a name, for something that may not have a page of that name, so the player lands on a list
	 * of results and never on a missing page. Returns false, and does nothing, when the name does not make an address.
	 */
	public static boolean search(@Nullable String name)
	{
		return browse(WikiLinks.searchUrl(name));
	}

	/** Hands an address to the player's browser after checking once more that it is one of the two kinds the wiki links make. */
	private static boolean browse(@Nullable String address)
	{
		if (!WikiLinks.isWikiAddress(address) && !WikiLinks.isWikiSearchAddress(address))
		{
			return false;
		}
		LinkBrowser.browse(address);
		return true;
	}
}
