package com.worldmapplus.wiki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import org.junit.Test;

/**
 * The address a Wiki button opens: always https, always the wiki's own host and article path, built from a bounded and encoded title,
 * and nothing else is ever made.
 */
public class WikiLinksTest
{
	@Test
	public void spacesBecomeUnderscoresAndTheSpellingIsKept()
	{
		assertEquals("https://oldschool.runescape.wiki/w/Cook's_Assistant", WikiLinks.url("Cook's Assistant"));
		assertEquals("https://oldschool.runescape.wiki/w/Aberrant_spectre", WikiLinks.url("Aberrant spectre"));
		assertEquals("https://oldschool.runescape.wiki/w/Between_a_Rock...", WikiLinks.url("Between a Rock..."));
		assertEquals("https://oldschool.runescape.wiki/w/Desert_Treasure_II_-_The_Fallen_Empire",
			WikiLinks.url("Desert Treasure II - The Fallen Empire"));
		assertEquals("https://oldschool.runescape.wiki/w/Ranging_cape(t)", WikiLinks.url("Ranging cape(t)"));
		assertEquals("https://oldschool.runescape.wiki/w/Recipe_for_Disaster/Another_Cook's_Quest",
			WikiLinks.url("Recipe for Disaster/Another Cook's Quest"));
	}

	@Test
	public void marksThatMeanSomethingInAnAddressAreEncoded()
	{
		assertEquals("https://oldschool.runescape.wiki/w/Tom_%26_Jerry", WikiLinks.url("Tom & Jerry"));
		assertEquals("https://oldschool.runescape.wiki/w/What%3F", WikiLinks.url("What?"));
		assertEquals("https://oldschool.runescape.wiki/w/Cook%E2%80%99s", WikiLinks.url("Cook’s"));
		assertEquals("https://oldschool.runescape.wiki/w/Caf%C3%A9", WikiLinks.url("Café"));
		assertEquals("https://oldschool.runescape.wiki/w/1%2B1", WikiLinks.url("1+1"));
		assertEquals("https://oldschool.runescape.wiki/w/Say_%22hi%22", WikiLinks.url("Say \"hi\""));
	}

	@Test
	public void onlyAnEmptyOrTooLongTitleGivesNoAddress()
	{
		for (String none : new String[] {null, "", "   "})
		{
			assertNull("[" + none + "]", WikiLinks.url(none));
		}
		StringBuilder longest = new StringBuilder();
		for (int i = 0; i < WikiLinks.MAX_TITLE; i++)
		{
			longest.append('a');
		}
		assertNotNull(WikiLinks.url(longest.toString()));
		assertNull("one over the bound", WikiLinks.url(longest + "a"));
	}

	@Test
	public void everyAddressMadeIsHttpsToTheWikiWhateverTheTitleHolds() throws Exception
	{
		// Since round 73 the page names come from the bundled data alone (checked at build time and by WikiAddressGoldenTest); a
		// title with marks no real title has is encoded, and still cannot reach another host, a query, a fragment, a user or a port.
		for (String title : new String[] {"Cook's Assistant", "Tom & Jerry", "What?", "Caf\u00e9", "Recipe for Disaster/Another Cook's Quest",
			"Zulrah", "Between a Rock...", "a", "X: Y", "a#b", "a?b#c", "a<b", "a>b", "a|b", "a[b]", "a{b}", "100%", "a\\b", "a\nb",
			"a\tb", "..", "../x", "a/../b", "/etc/passwd", "//evil.example/x", ":Special", "a;b", "a=b", "a@b", "a*b", "a$b", "a^b", "a`b",
			"https://evil.example", "\ud83d\ude00"})
		{
			String address = WikiLinks.url(title);
			assertNotNull(title, address);
			assertTrue(address, address.startsWith(WikiLinks.PREFIX));
			URI uri = new URI(address);
			assertEquals("https", uri.getScheme());
			assertEquals("oldschool.runescape.wiki", uri.getHost());
			assertEquals("oldschool.runescape.wiki", uri.normalize().getHost());
			assertEquals(-1, uri.getPort());
			assertNull(uri.getUserInfo());
			assertNull("no query", uri.getRawQuery());
			assertNull("no fragment", uri.getRawFragment());
			assertTrue(uri.getRawPath().startsWith("/w/"));
		}
	}

	@Test
	public void onlyAnAddressMadeHereIsHandedOn()
	{
		assertFalse(WikiLinks.browse(null));
		assertFalse(WikiLinks.browse("https://evil.example/w/Zulrah"));
		assertFalse(WikiLinks.browse("javascript:alert(1)"));
	}
}
