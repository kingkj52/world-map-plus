package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.worldmapplus.drops.DropTables;
import com.worldmapplus.drops.PriceSource;
import com.worldmapplus.search.BundledSearch;
import com.worldmapplus.search.SearchEntry;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The Drops and Dropped by tabs of every monster and item name, as they were before the drop tables moved from {@code drops.bin} to
 * {@code drops.json} (round 70) and before the tabs were made at build time (round 71): {@code golden/drops.txt}. The tabs of every name
 * are written as a digest of their whole text (rows, rarity, details with and without prices, summary), and in full for one name in
 * forty. The golden file also holds every table and row as the plugin read them until round 71; those lines are history now (the plugin
 * reads finished tabs) and are not compared. What is read now is written to {@code build/drops-actual.txt}; after a data update that is
 * meant to change drops, look at the difference and copy that file over the tab part of the golden one.
 */
public class DropsGoldenTest
{
	/** A made up price for every item, so the details and summaries with prices are written too. */
	private static final PriceSource PRICES = new PriceSource()
	{
		@Override
		public long price(int itemId)
		{
			return itemId % 7 == 0 ? -1 : itemId * 13L % 100_000 + 1;
		}

		@Override
		public void request(int[] itemIds)
		{
		}

		@Override
		public int version()
		{
			return 1;
		}
	};

	private static final Pattern HEAD = Pattern.compile("^(drops|dropped by) (.*), \\d+ rows = [0-9a-f]+$");

	@Test
	public void everyTabIsAsBeforeTheMoves() throws IOException, NoSuchAlgorithmException
	{
		Path golden = Paths.get("src", "test", "resources", "golden", "drops.txt");
		String all = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String expected = all.substring(all.indexOf("\ndrops ") + 1);
		DropTables t = DropTables.load(new Gson());
		List<SearchEntry> entries = BundledSearch.entries();
		MessageDigest sha = MessageDigest.getInstance("SHA-1");
		StringBuilder out = new StringBuilder();
		Set<String> creatures = new HashSet<>();
		Set<String> items = new HashSet<>();
		int n = 0;
		for (String line : expected.split("\n"))
		{
			Matcher m = HEAD.matcher(line);
			if (!m.matches())
			{
				continue;
			}
			String name = m.group(2);
			List<ResultView.Row> rows = new ArrayList<>();
			DropTables.Tab found;
			ResultView.Tab tab;
			DropViews.Part part;
			if (m.group(1).equals("drops"))
			{
				found = t.drops(name);
				creatures.add(name.trim().toLowerCase(Locale.ROOT));
				tab = found == null ? null : DropViews.tab(t, found, null, rows);
				part = found == null ? null : new DropViews.Part(rows, found.getSummary(), found.isTotal());
			}
			else
			{
				found = t.droppedBy(name);
				items.add(name.trim().toLowerCase(Locale.ROOT));
				tab = found == null ? null : DropViews.tab(t, found, entries, rows);
				part = new DropViews.Part(rows, "", false);
			}
			if (tab == null)
			{
				out.append(m.group(1)).append(' ').append(name).append(": no tab\n");
				continue;
			}
			tab(out, sha, m.group(1) + " " + name, tab, part, n++ % 40 == 0);
		}

		String text = out.toString();
		Path actual = Paths.get("build", "drops-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		if (!expected.equals(text))
		{
			String[] a = expected.split("\n", -1);
			String[] b = text.split("\n", -1);
			for (int i = 0; i < Math.min(a.length, b.length); i++)
			{
				assertEquals("line " + (i + 1), a[i], b[i]);
			}
			assertEquals("lines", a.length, b.length);
		}

		// Every tab of the file is one of the golden names, so none is left unchecked.
		try (Reader in = new InputStreamReader(DropTables.class.getResourceAsStream("/com/worldmapplus/drops.json"), StandardCharsets.UTF_8))
		{
			JsonObject file = new Gson().fromJson(in, JsonObject.class);
			assertTrue(creatures.containsAll(file.getAsJsonObject("drops").keySet()));
			assertTrue(items.containsAll(file.getAsJsonObject("droppedBy").keySet()));
		}
	}

	private static void tab(StringBuilder out, MessageDigest sha, String name, ResultView.Tab tab, DropViews.Part part, boolean full)
	{
		StringBuilder text = new StringBuilder();
		text.append(tab.name).append(" sorts ").append(tab.sorts).append('\n');
		for (ResultView.Row row : tab.rows)
		{
			text.append(row.label).append(" | ").append(row.count).append(" | ").append(row.drop).append(" | id ").append(row.itemId)
				.append(" order ").append(row.order).append(" per kill ").append(row.perKill).append(row.each ? " each" : "")
				.append(" | places ").append(row.places == null ? -1 : row.places.size()).append(" | follow ")
				.append(row.follow == null ? "-" : row.follow.getKind() + " " + row.follow.getName()).append('\n');
		}
		String summary = part.apply(PriceSource.NONE);
		text.append("summary ").append(summary).append('\n');
		for (ResultView.Row row : tab.rows)
		{
			text.append("  ").append(row.detail).append('\n');
		}
		summary = part.apply(PRICES);
		text.append("priced ").append(summary).append('\n');
		for (ResultView.Row row : tab.rows)
		{
			text.append("  ").append(row.detail).append(" | price ").append(row.price).append(" value ").append(row.value).append('\n');
		}
		block(out, sha, name + ", " + tab.rows.size() + " rows", text.toString(), full);
	}

	/** A name and the digest of a text, and the text itself when asked. */
	private static void block(StringBuilder out, MessageDigest sha, String name, String text, boolean full)
	{
		StringBuilder hex = new StringBuilder();
		for (byte b : sha.digest(text.getBytes(StandardCharsets.UTF_8)))
		{
			hex.append(String.format("%02x", b));
		}
		out.append(name).append(" = ").append(hex).append('\n');
		if (full)
		{
			for (String line : text.split("\n"))
			{
				out.append("    ").append(line).append('\n');
			}
		}
	}
}
