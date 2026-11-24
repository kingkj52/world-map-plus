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
	public void aTitleThatCouldDoAnythingButNameAPageGivesNoAddress()
	{
		for (String bad : new String[] {null, "", "   ", "a#b", "a?b#c", "a<b", "a>b", "a|b", "a[b]", "a{b}", "100%", "a\\b", "a\nb", "a\tb",
			"..", "../x", "a/../b", "/etc/passwd", "//evil.example/x", ":Special", "a;b", "a=b", "a@b", "a*b", "a$b", "a^b", "a`b"})
		{
			assertNull("[" + bad + "]", WikiLinks.url(bad));
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
	public void everyAddressMadeIsHttpsToTheWikiAndNothingElse() throws Exception
	{
		for (String title : new String[] {"Cook's Assistant", "Tom & Jerry", "What?", "Café", "Recipe for Disaster/Another Cook's Quest",
			"Zulrah", "Between a Rock...", "a", "X: Y"})
		{
			String address = WikiLinks.url(title);
			assertNotNull(title, address);
			URI uri = new URI(address);
			assertEquals("https", uri.getScheme());
			assertEquals("oldschool.runescape.wiki", uri.getHost());
			assertEquals(-1, uri.getPort());
			assertNull(uri.getUserInfo());
			assertNull("no query", uri.getRawQuery());
			assertNull("no fragment", uri.getRawFragment());
			assertTrue(uri.getRawPath().startsWith("/w/"));
			assertTrue(address, WikiLinks.isWikiAddress(address));
		}
	}

	@Test
	public void onlyTheWikisOwnAddressesAreRecognised()
	{
		assertTrue(WikiLinks.isWikiAddress("https://oldschool.runescape.wiki/w/Zulrah"));
		for (String other : new String[] {null, "", "http://oldschool.runescape.wiki/w/Zulrah", "https://runescape.wiki/w/Zulrah",
			"https://oldschool.runescape.wiki.evil.example/w/Zulrah", "https://evil.example/https://oldschool.runescape.wiki/w/Zulrah",
			"https://user@oldschool.runescape.wiki/w/Zulrah", "https://oldschool.runescape.wiki:8443/w/Zulrah",
			"https://oldschool.runescape.wiki/w/Zulrah?action=raw", "https://oldschool.runescape.wiki/w/Zulrah#Drops",
			"https://oldschool.runescape.wiki/index.php?title=Zulrah", "https://oldschool.runescape.wiki/w/", "https://oldschool.runescape.wiki/w/../x",
			"https://oldschool.runescape.wiki/w/%2e%2e/x", "https://oldschool.runescape.wiki/w/a b", "file:///etc/passwd",
			"javascript:alert(1)", "ftp://oldschool.runescape.wiki/w/Zulrah"})
		{
			assertFalse("[" + other + "]", WikiLinks.isWikiAddress(other));
		}
	}
}
