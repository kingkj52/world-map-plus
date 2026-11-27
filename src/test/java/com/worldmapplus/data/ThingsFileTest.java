package com.worldmapplus.data;

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
 * Round 68: the cards of the map (entrances, transports, teleports, shops, agility shortcuts, the game's icons with what the wiki says
 * they ask, the links between maps, the labels) are made by {@code tools/build_cards.py} into {@code things.json}. Until then the plugin
 * worked them out itself; {@code golden/things-derived.json} is what that Java code made from the data of the time, written out by
 * {@link ThingsDump}, and what the plugin reads now must write out exactly the same. The text as read now is written to
 * {@code build/things-read.json}. After a data update that is meant to change cards, look at the difference and copy that file over the
 * golden one.
 */
public class ThingsFileTest
{
	@Test
	public void whatThePluginReadsIsWhatTheOldJavaCodeMade() throws IOException
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		try
		{
			String text = ThingsDump.dump(new MapAssets(new Gson(), executor));
			Path out = Paths.get("build", "things-read.json");
			Files.createDirectories(out.getParent());
			Files.write(out, text.getBytes(StandardCharsets.UTF_8));
			Path golden = Paths.get("src", "test", "resources", "golden", "things-derived.json");
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
