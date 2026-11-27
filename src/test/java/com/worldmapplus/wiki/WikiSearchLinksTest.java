package com.worldmapplus.wiki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * The address of the wiki's search page, which the button opens for a name that may have no page: always https, the wiki's own host,
 * one fixed path, one query key, and the name bounded and encoded so it cannot add anything.
 */
public class WikiSearchLinksTest
{
	@Test
	public void theNameIsTheValueOfTheOneKey()
	{
		assertEquals("https://oldschool.runescape.wiki/w/Special:Search?search=Herb+patch", WikiLinks.searchUrl("Herb patch"));
		assertEquals("https://oldschool.runescape.wiki/w/Special:Search?search=Bank%3A+Al+Kharid", WikiLinks.searchUrl(" Bank: Al Kharid "));
		assertEquals("https://oldschool.runescape.wiki/w/Special:Search?search=a%26b%3Dc%23d%3Fe%2Ff",
			WikiLinks.searchUrl("a&b=c#d?e/f"));
		assertEquals("https://oldschool.runescape.wiki/w/Special:Search?search=Caf%C3%A9", WikiLinks.searchUrl("Café"));
	}

	@Test
	public void aNameThatCannotBeSearchedGivesNoAddress()
	{
		assertNull(WikiLinks.searchUrl(null));
		assertNull(WikiLinks.searchUrl("   "));
		StringBuilder longest = new StringBuilder();
		for (int i = 0; i < WikiLinks.MAX_TITLE; i++)
		{
			longest.append('a');
		}
		assertNotNull(WikiLinks.searchUrl(longest.toString()));
		assertNull("one over the bound", WikiLinks.searchUrl(longest + "a"));
	}

	@Test
	public void everySearchAddressIsHttpsToTheWikisSearchPageAndNothingElse() throws Exception
	{
		for (String name : new String[] {"Herb patch", "Shark fishing spots in Catherby", "a&b=c", "x#y", "../../etc", "https://evil.example",
			"Tom & Jerry", "%2e%2e", "Café", "\"quoted\"", "<script>", "a+b", "line\nbreak", "tab\there"})
		{
			String address = WikiLinks.searchUrl(name);
			assertNotNull(name, address);
			URI uri = new URI(address);
			assertEquals("https", uri.getScheme());
			assertEquals("oldschool.runescape.wiki", uri.getHost());
			assertEquals(-1, uri.getPort());
			assertNull(uri.getUserInfo());
			assertNull("no fragment", uri.getRawFragment());
			assertEquals("/w/Special:Search", uri.getRawPath());
			String query = uri.getRawQuery();
			assertTrue(query, query.startsWith("search="));
			assertFalse("one key only: " + query, query.contains("&"));
			assertEquals("one key only", 1, query.split("=", -1).length - 1);
			assertEquals("the name comes back whole", name.trim(), URLDecoder.decode(query.substring("search=".length()), StandardCharsets.UTF_8.name()));
		}
	}

	@Test
	public void theSearchNameIsTheCleanedName()
	{
		assertEquals("Herb patch", WikiLinks.searchName("  Herb patch "));
		assertNull(WikiLinks.searchName(null));
		assertNull(WikiLinks.searchName(" "));
	}
}
