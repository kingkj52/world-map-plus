package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.data.MapElements;
import com.worldmapplus.search.BundledSearch;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.unlock.RequirementsGoldenTest;
import com.worldmapplus.unlock.Unlocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 73: how every game icon of the bundled data looks to each of the six pretend players of round 70 under each mix of the progress
 * settings (normal, dimmed with a padlock, left out, a quest dot), and which quest starts the search leaves out for a finished quest,
 * written before the progress runtime was made smaller ({@code golden/progress-marks.txt}). What is made now is written to
 * {@code build/progress-marks-actual.txt}. A look is one letter: n normal, l locked, h hidden, then 1, 2 and 3 for a quest not started,
 * started and finished.
 */
public class ProgressMarksGoldenTest
{
	/** Hide finished quests, mark what cannot be used, only what can be used, colour quests by progress. */
	private static final boolean[][] SETTINGS = {
		{false, false, false, false},
		{true, false, false, false},
		{false, true, false, false},
		{false, false, true, false},
		{false, false, false, true},
		{true, true, false, true},
		{true, true, true, true},
	};

	private static final String LETTERS = "nlh123";

	@Test
	public void theIconsLookToEachPlayerAsBeforeTheSlimming() throws IOException
	{
		MapElements elements = MapElements.load(new Gson());
		List<GameIconSet.Spec> specs = elements.getGameIcons();
		GameIconLayer layer = new GameIconLayer(new GameIconSet(specs), null);
		List<SearchEntry> quests = BundledSearch.of(SearchKind.QUEST);
		StringBuilder out = new StringBuilder("# " + specs.size() + " icons; per player and settings the looks, 100 a line\n");
		for (int p = 0; p < RequirementsGoldenTest.NAMES.length; p++)
		{
			Unlocks unlocks = RequirementsGoldenTest.player(p);
			for (boolean[] s : SETTINGS)
			{
				layer.setUnlocks(unlocks, s[0], s[1], s[2], s[3]);
				int[] counts = new int[LETTERS.length()];
				StringBuilder looks = new StringBuilder();
				for (GameIconSet.Spec spec : specs)
				{
					byte look = layer.lookOf(spec.getNeed());
					counts[look]++;
					looks.append(LETTERS.charAt(look));
				}
				out.append(RequirementsGoldenTest.NAMES[p]).append(" hide ").append(s[0]).append(" mark ").append(s[1])
					.append(" only ").append(s[2]).append(" colour ").append(s[3]).append(':');
				for (int i = 0; i < counts.length; i++)
				{
					out.append(' ').append(LETTERS.charAt(i)).append(' ').append(counts[i]);
				}
				out.append('\n');
				for (int i = 0; i < looks.length(); i += 100)
				{
					out.append("  ").append(looks, i, Math.min(looks.length(), i + 100)).append('\n');
				}
			}
			out.append(RequirementsGoldenTest.NAMES[p]).append(" search leaves out:");
			for (SearchEntry entry : quests)
			{
				if (unlocks.finished(entry.getQuest()))
				{
					out.append(' ').append(entry.getName()).append(';');
				}
			}
			out.append('\n');
		}
		String text = out.toString();
		Path actual = Paths.get("build", "progress-marks-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "progress-marks.txt");
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
}
