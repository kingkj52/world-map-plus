package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 67: places and town areas moved from Java into {@code tools/build_places.py}. The results of searches that use them (place
 * searches, {@code region:} and {@code near:} filters, the area names offered while typing) were written down before the move in
 * {@code golden/place-search.txt}; this checks they are the same after it. What the search gives now is written to
 * {@code build/place-search-actual.txt}. After a data update that is meant to change places, look at the difference and copy that file
 * over the golden one.
 */
public class PlaceSearchGoldenTest
{
	private static final String[] QUERIES = {
		"region:varrock", "bank region:varrock", "in:falador shop", "region:kandarin altar", "region:\"kebos lowlands\"",
		"near:lumbridge", "type:place varrock", "type:place region:misthalin", "pub", "type:place pub", "inn region:ardougne",
		"region:\"farming guild\"", "-region:varrock bank", "region:wilderness type:place", "near:me bank", "loc:nearby",
		"region:\"dwarven mines\"", "region:keldagrim", "type:place castle", "slayer tower", "in:catherby fish",
		"region:\"grand exchange\"", "type:place guild", "region:taverley", "region:\"port sarim\" shop", "area:karamja",
		"region:\"al kharid\"", "type:place cave", "region:morytania type:place", "at:draynor", "region:prifddinas",
		"region:varlamore", "type:place death's office", "estate agent", "region:aldarin", "type:place alchemical society",
		"region:yanille", "dungeon region:edgeville", "near:\"slayer tower\"", "region:\"jolly boar\"", "type:place inn",
		"region:canifis", "near:varrock type:place", "region:tirannwn", "type:place region:asgarnia", "type:place sawmill",
		"rune region:\"varrock\"", "region:\"lumbridge swamp\"", "region:civitas", "region:\"fossil island\"",
	};

	private static final String[] TYPED = {"var", "lum", "guild", "k", "west", "the", "port", "isle", "mount", "swamp"};

	@Test
	public void placeSearchesGiveWhatTheyGaveBeforeTheMove() throws IOException
	{
		MapAssets assets = new MapAssets(new Gson(), new ScheduledThreadPoolExecutor(1));
		SearchIndex index = assets.getSearchIndex();
		Gazetteer gazetteer = assets.getGazetteer();
		SearchContext here = new SearchContext(true, 0, 3212.5, 3428.5, gazetteer);
		List<String> lines = new ArrayList<>();
		for (String query : QUERIES)
		{
			lines.add("? " + query);
			for (SearchEntry e : index.search(query, 40, here))
			{
				lines.add(e.getKind() + " | " + e.getName() + " | " + e.getDetail() + " | " + e.getMapId() + " | " + e.getX() + ","
					+ e.getY());
			}
		}
		for (String typed : TYPED)
		{
			lines.add("suggest " + typed + ": " + gazetteer.suggest(typed, 30));
		}
		Path actual = Paths.get("build", "place-search-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, lines, StandardCharsets.UTF_8);
		Path golden = Paths.get("src", "test", "resources", "golden", "place-search.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		assertEquals(String.join("\n", Files.readAllLines(golden, StandardCharsets.UTF_8)), String.join("\n", lines));
	}
}
