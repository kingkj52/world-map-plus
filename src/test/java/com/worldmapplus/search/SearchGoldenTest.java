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
 * Round 69: the search entries moved from Java into {@code tools/build_search.py}. The results of a broad set of searches (monsters,
 * NPCs, items sold in shops and lying on the ground, music, quest starts, every place of a kind and a kind in a town, {@code type:}
 * filters, prices, places and typos), in order, with the wiki page and what each says is missing, were written down before the move in
 * {@code golden/search.txt}; this checks they are the same after it. Ranking, typo tolerance and the query language stay in Java. What
 * the search gives now is written to {@code build/search-actual.txt}. After a data update that is meant to change entries, look at the
 * difference and copy that file over the golden one.
 */
public class SearchGoldenTest
{
	private static final String[] QUERIES = {
		// Monsters
		"goblin", "cow", "chicken", "giant rat", "hill giant", "moss giant", "lesser demon", "greater demon", "black demon",
		"blue dragon", "green dragon", "black dragon", "king black dragon", "aberrant spectre", "abyssal demon", "cave crawler",
		"desert wolf", "elf warrior", "giant bat", "jailer", "loar shade", "monk", "ogre chieftain", "poison scorpion", "revenant ork",
		"skeleton fremennik", "thrower troll", "werewolf", "zombie rat", "imp", "guard", "man", "woman", "dark wizard", "barbarian",
		"type:monster dragon", "type:monster region:morytania", "type:monster near:me", "type:monster giant", "type:monster sort:near",
		"skeleton", "zombie", "spider", "bear", "wolf", "scorpion", "crab", "rock crab", "sand crab", "ammonite crab", "hellhound",
		"bloodveld", "gargoyle", "nechryael", "kurask", "turoth", "dagannoth", "fire giant", "ice giant", "ice warrior", "troll",
		// NPCs
		"hans", "duke horacio", "aubury", "wise old man", "banker", "father aereck", "academic", "baby custodian stalker", "capybara",
		"citizen", "crimson swift", "farmer", "ferret", "fisherman", "golden warbler", "harpy eagle", "mountain goat", "pelican",
		"puffin", "rainbow crab", "rod fishing spot", "sailor", "spotted kebbit", "type:npc", "type:npc region:varrock",
		"type:npc fishing", "shopkeeper", "shop assistant", "bartender", "gerrant", "horvik", "lowe", "thessalia", "zaff", "bob",
		// Items in shops
		"rune scimitar", "bronze axe", "bronze pickaxe", "feather", "fishing rod", "lobster pot", "chisel", "hammer", "law rune",
		"nature rune", "air rune", "potato seed", "vial of water", "eye of newt", "bucket", "pot", "tinderbox", "rope", "shears",
		"leather gloves", "iron platebody", "steel full helm", "maple shortbow", "oak longbow", "bronze arrow", "iron dart",
		"staff of air", "battlestaff", "amulet of strength", "ring of recoil", "games necklace", "beer", "kebab", "bread", "cake",
		"swordfish", "shark", "trout", "cooked chicken", "jug of wine", "harralander potion (unf)", "antipoison", "super attack",
		"type:item price:<10", "type:item price:>50000", "type:item sort:cost", "type:item sort:price rune", "cost:<100 sword",
		"type:shop", "type:shop region:kandarin", "type:shop region:\"al kharid\"", "type:shop magic", "general store region:falador",
		"type:shop sort:near", "price:<5 type:item region:varrock",
		// Items on the ground
		"type:item free", "free bones", "bones", "ashes", "egg", "flour", "red spiders' eggs", "wine of zamorak", "banana",
		"black scimitar", "box trap", "bronze arrowtips", "bronze sq shield", "burnt meat", "crumbling tome", "dwarven stout",
		"fish food", "grail bell", "insect repellent", "lamp", "magic staff", "oil can", "picture", "pungent pot", "redberry pie",
		"sapphire bolt tips", "small fishing net", "steel knife", "sunbeam ale", "tiles", "waterskin", "address form", "free knife",
		"free region:lumbridge", "type:item free near:me",
		// Music
		"type:music", "harmony", "newbie melody", "autumn voyage", "a taste of hope", "arcane", "cabin fever", "dream", "fishing",
		"gnomeball", "guardians of the rift", "iban", "mistrock", "morytania", "quest", "revenants", "sarachnis", "song of the elves",
		"spirit", "temple of light", "the fallen empire", "the spurned demon", "warrior", "well of voyage", "type:music region:misthalin",
		"type:music near:me",
		// Quest starts
		"type:quest", "cook's assistant", "dragon slayer", "dragon slayer ii", "rune mysteries", "a kingdom divided",
		"animal magnetism", "black knights' fortress", "contact!", "death to the dorgeshuun", "elemental workshop i",
		"fairytale ii", "gertrude's cat", "hazeel cult", "imp catcher", "lost city", "merlin's crystal", "mourning's end part i",
		"olaf's quest", "prying times", "regicide", "scorpion catcher", "sheep shearer", "spirits of the elid",
		"the ascent of arceuus", "the forsaken tower", "the golem", "the queen of thieves", "tower of life", "vale totems",
		"type:quest region:kandarin", "type:quest near:me", "miniquest",
		// Every place of a kind, and a kind in a town
		"bank", "banks", "every bank", "all banks", "banks in catherby", "bank catherby", "catherby bank", "banks near",
		"altar", "altars", "altar varrock", "anvil", "anvils", "furnace", "furnaces in falador", "herb patch", "herb patches",
		"herb patch catherby", "allotment patch", "fruit tree patch", "tree patch", "shark fishing spot", "shark catherby",
		"fishing spot", "fishing spots in catherby", "general store", "general stores", "general store varrock", "spinning wheel",
		"cooking range", "range", "water source", "dairy cow", "mining site", "coal rocks", "coal rocks in the mining guild",
		"adamantite rocks", "gem rock", "agility shortcut", "agility shortcuts", "agility training", "bird house site", "brewery",
		"dungeon", "dungeons", "docking point", "estate agent", "hairdresser", "apothecary", "archery shop", "axe shop",
		"candle shop", "chainmail shop", "clothes shop", "crafting shop", "food shop", "gem shop", "helmet shop", "herbalist",
		"every altar", "all furnaces", "type:kind", "type:point bank", "type:point altar region:varrock", "banks region:varrock",
		"bank near:me", "nearest bank", "bank sort:near", "camphor tree", "bloodwood tree", "chinchompa hunting area",
		// type: filters by item kind
		"type:melee_weapon", "type:ranged_weapon", "type:magic_weapon", "type:ammo", "type:armour", "type:food", "type:potion",
		"type:tool", "type:rune", "type:seed", "type:ore", "type:bar", "type:log", "type:jewellery", "type:melee_weapon dragon",
		"type:ranged_weapon region:kandarin", "type:tool price:<20", "type:food sort:cost", "type:potion region:varrock",
		"type:armour rune", "type:seed farming", "type:rune region:varrock", "type:jewellery ring", "type:ore", "type:log price:<100",
		"type:melee type:tool", "type:transport", "type:teleport", "type:entrance", "type:place", "type:doorway",
		// Transports, teleports, entrances, places
		"charter ship", "fairy ring", "spirit tree", "varrock teleport", "teleport to house", "slayer tower", "god wars dungeon",
		"lumbridge", "varrock", "falador", "ardougne", "catherby", "draynor village", "port sarim", "edgeville", "al kharid",
		"grand exchange", "castle wars", "barbarian assault", "pest control", "zanaris", "crandor", "rellekka", "prifddinas",
		"varlamore", "civitas illa fortis", "fossil island", "map:surface bank", "map:\"karamja underground\"",
		// Typos and partial words
		"goblim", "lumbrige", "varock", "draynor vilage", "dragn", "scimtar", "lobstr", "fally", "ardougne castl", "abysal demon",
		"wise old mna", "chiken", "lesser demn", "rune scimiter", "faladr", "edgevile", "kebbab", "harmonny", "dragon slayr",
		"cooks assistant", "bob's axes", "bobs brilliant", "gnome stronghold", "gnome", "stron", "sea", "ape atoll", "shilo",
		"karam", "kar", "mor", "a", "rune", "dragon", "steel", "mithril", "adamant",
	};

	/** Queries asked again from other places, as the answers about distance and nearness depend on where the player is. */
	private static final String[] ELSEWHERE = {
		"bank", "banks in catherby", "type:monster near:me", "type:item free near:me", "type:shop sort:near", "nearest bank",
		"bank sort:near", "fishing spot", "goblin", "type:quest near:me", "shark", "altar", "general store", "type:music near:me",
	};

	@Test
	public void searchesGiveWhatTheyGaveBeforeTheMove() throws IOException
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		List<String> lines = new ArrayList<>();
		try
		{
			MapAssets assets = new MapAssets(new Gson(), executor);
			SearchIndex index = assets.getSearchIndex();
			Gazetteer gazetteer = assets.getGazetteer();
			SearchContext varrock = new SearchContext(true, 0, 3212.5, 3428.5, gazetteer);
			for (String query : QUERIES)
			{
				ask(lines, index, query, varrock, "");
			}
			SearchContext catherby = new SearchContext(true, 0, 2809.5, 3440.5, gazetteer);
			for (String query : ELSEWHERE)
			{
				ask(lines, index, query, catherby, "catherby ");
				ask(lines, index, query, SearchContext.NONE, "nowhere ");
			}
		}
		finally
		{
			executor.shutdownNow();
		}
		Path actual = Paths.get("build", "search-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, lines, StandardCharsets.UTF_8);
		Path golden = Paths.get("src", "test", "resources", "golden", "search.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		assertEquals(String.join("\n", Files.readAllLines(golden, StandardCharsets.UTF_8)), String.join("\n", lines));
	}

	private static void ask(List<String> lines, SearchIndex index, String query, SearchContext where, String label)
	{
		lines.add("? " + label + query);
		for (SearchEntry e : index.search(query, 40, where))
		{
			String need = SearchDump.need(e.getNeed());
			lines.add(e.getKind() + " | " + e.getName() + " | " + e.getDetail() + " | " + e.getMapId() + " | " + e.getX() + "," + e.getY()
				+ " | " + e.getAlsoAt().size() + " | " + (e.getKindId() == null ? "-" : e.getKindId() + "/" + e.getGroup()) + " | "
				+ (need == null ? "-" : need) + " | " + SearchDump.wiki(e));
		}
	}
}
