package com.worldmapplus.wiki;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.data.MapElements;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.IconExtra;
import com.worldmapplus.search.BundledSearch;
import com.worldmapplus.search.KindIndex;
import com.worldmapplus.search.SearchEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 73: the wiki addresses the plugin makes for every page and every search name the bundled data can offer (cards, merged points,
 * search entries, kinds), written before the wiki button was reduced to building the address ({@code golden/wiki-addresses.txt}). What
 * is made now is written to {@code build/wiki-addresses-actual.txt}.
 */
public class WikiAddressGoldenTest
{
	@Test
	public void everyAddressOfTheBundledDataIsAsBeforeTheSlimming() throws IOException
	{
		TreeSet<String> pages = new TreeSet<>();
		TreeSet<String> searches = new TreeSet<>();
		MapElements elements = MapElements.load(new Gson());
		for (Hotspot spot : elements.loaded(Collections.emptyList()))
		{
			add(pages, spot.getCard().getWiki());
			add(searches, spot.getCard().getTitle());
		}
		for (Hotspot spot : elements.getIcons())
		{
			add(pages, spot.getCard().getWiki());
			add(searches, spot.getCard().getTitle());
		}
		for (GameIconSet.Spec icon : elements.getGameIcons())
		{
			List<IconExtra> extras = icon.getExtras() == null ? Collections.emptyList() : icon.getExtras();
			for (IconExtra extra : extras)
			{
				add(pages, extra.getWiki());
			}
		}
		for (SearchEntry entry : BundledSearch.entries())
		{
			add(pages, entry.getWiki());
			add(searches, entry.getName());
		}
		for (KindIndex.Kind kind : BundledSearch.kinds().getKinds())
		{
			add(searches, kind.getSingular());
		}
		StringBuilder out = new StringBuilder("# pages: " + pages.size() + "\n");
		for (String title : pages)
		{
			out.append(title).append(" = ").append(WikiLinks.url(title)).append('\n');
		}
		out.append("# search names: ").append(searches.size()).append('\n');
		for (String name : searches)
		{
			out.append(name).append(" = ").append(WikiLinks.searchUrl(name)).append('\n');
		}
		String text = out.toString();
		Path actual = Paths.get("build", "wiki-addresses-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "wiki-addresses.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String[] a = expected.split("\n", -1);
		String[] b = text.split("\n", -1);
		for (int i = 0; i < Math.min(a.length, b.length); i++)
		{
			assertEquals("line " + (i + 1), a[i], b[i]);
		}
		assertEquals("lines", a.length, b.length);
	}

	private static void add(TreeSet<String> set, String value)
	{
		if (value != null)
		{
			set.add(value);
		}
	}
}
