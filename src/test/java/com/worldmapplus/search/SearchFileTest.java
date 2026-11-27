package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 69: the search entries (maps, entrances, transports, teleports, shops with their keepers and wares, points, monsters, NPCs,
 * ground items, quest starts, music, named places and every place of a kind) are made by {@code tools/build_search.py} into
 * {@code search.json}. Until then the plugin made them itself; {@code golden/search-entries.txt} is what that Java code made from the
 * data of the time, written out by {@link SearchDump}, and what the plugin has now must write out exactly the same. The text as it is
 * now is written to {@code build/search-entries-read.txt}. After a data update that is meant to change entries, look at the difference
 * and copy that file over the golden one.
 */
public class SearchFileTest
{
	@Test
	public void theEntriesAreWhatTheOldJavaCodeMade() throws IOException
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		try
		{
			String text = SearchDump.dump(new MapAssets(new Gson(), executor));
			Path out = Paths.get("build", "search-entries-read.txt");
			Files.createDirectories(out.getParent());
			Files.write(out, text.getBytes(StandardCharsets.UTF_8));
			Path golden = Paths.get("src", "test", "resources", "golden", "search-entries.txt");
			Assume.assumeTrue("no golden file yet", Files.exists(golden));
			String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
			if (!expected.equals(text))
			{
				// The first line that differs, so a failure says what changed without printing megabytes.
				String[] a = expected.split("\n", -1);
				String[] b = text.split("\n", -1);
				for (int i = 0; i < Math.min(a.length, b.length); i++)
				{
					assertEquals("line " + (i + 1), a[i], b[i]);
				}
				assertEquals("lines", a.length, b.length);
			}
		}
		finally
		{
			executor.shutdownNow();
		}
	}
}
