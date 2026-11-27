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
 * Round 68: the cards moved from Java into {@code tools/build_cards.py}. The results of searches that read the cards (shops and what they
 * sell, shopkeepers, transports, teleports, entrances, points, quest icons, agility shortcuts and what a result says is missing) were
 * written down before the move in {@code golden/card-search.txt}; this checks they are the same after it. What the search gives now is
 * written to {@code build/card-search-actual.txt}. After a data update that is meant to change cards, look at the difference and copy that
 * file over the golden one.
 */
public class CardSearchGoldenTest
{
	private static final String[] QUERIES = {
		"rune scimitar", "lobster", "type:shop varrock", "general store", "aubury", "zaff", "type:item price:<100 region:falador",
		"charter ship", "fairy ring", "spirit tree", "gnome glider", "magic carpet", "canoe", "type:transport karamja",
		"varrock teleport", "type:teleport ardougne", "teleport to house", "games necklace", "ring of dueling", "type:teleport fishing",
		"slayer tower entrance", "type:doorway taverley", "god wars dungeon", "dungeon", "stronghold of security", "type:entrance",
		"agility shortcut", "type:point bank", "fishing spot", "rare trees", "yew tree", "magic tree", "anvil", "altar", "furnace",
		"type:point region:kandarin", "rooftop", "barbarian assault", "pest control", "cook's assistant", "dragon slayer",
		"type:quest region:misthalin", "obelisk", "balloon", "mushtree", "minecart", "ship to port sarim", "rellekka",
		"type:shop price:>100000", "shop keeper", "bob's brilliant axes", "potion", "type:item free", "zanaris", "crandor",
		"type:transport", "mining site", "hunter", "farming patch", "port task board", "salvaging",
	};

	@Test
	public void cardSearchesGiveWhatTheyGaveBeforeTheMove() throws IOException
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		List<String> lines = new ArrayList<>();
		try
		{
			MapAssets assets = new MapAssets(new Gson(), executor);
			SearchIndex index = assets.getSearchIndex();
			SearchContext here = new SearchContext(true, 0, 3212.5, 3428.5, assets.getGazetteer());
			for (String query : QUERIES)
			{
				lines.add("? " + query);
				for (SearchEntry e : index.search(query, 40, here))
				{
					lines.add(e.getKind() + " | " + e.getName() + " | " + e.getDetail() + " | " + e.getMapId() + " | " + e.getX() + ","
						+ e.getY() + " | " + e.getAlsoAt().size() + " | " + (e.getNeed() == null ? "-" : needText(e)));
				}
			}
		}
		finally
		{
			executor.shutdownNow();
		}
		Path actual = Paths.get("build", "card-search-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, lines, StandardCharsets.UTF_8);
		Path golden = Paths.get("src", "test", "resources", "golden", "card-search.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		assertEquals(String.join("\n", Files.readAllLines(golden, StandardCharsets.UTF_8)), String.join("\n", lines));
	}

	private static String needText(SearchEntry e)
	{
		List<String> ways = new ArrayList<>();
		for (com.worldmapplus.unlock.Need.Check[] way : e.getNeed().alternatives())
		{
			ways.add(com.worldmapplus.unlock.Needs.describe(way));
		}
		return String.join(" / ", ways) + (e.getNeed().startsQuest() == null ? "" : " starts " + e.getNeed().startsQuest());
	}
}
