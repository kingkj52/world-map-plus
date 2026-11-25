package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.unlock.Judge;
import com.worldmapplus.unlock.Need;
import com.worldmapplus.unlock.Requirement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * What the bundled data says about the levels and quests the icons ask of the player: every field the data has is read, nothing is
 * guessed, and the icons that carry a need are the kinds this plugin documents.
 */
public class ProgressDataTest
{
	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor);
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	private static String kindOf(GameIconSet.Spec spec)
	{
		return assets.getElements().categoryKind(spec.getCategory());
	}

	/** Icons with a need, counted by the kind of icon they are, and how many of each kind there are in all. */
	private static Map<String, int[]> census()
	{
		Map<String, int[]> counts = new TreeMap<>();
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			int[] count = counts.computeIfAbsent(kindOf(spec), k -> new int[3]);
			count[0]++;
			Need need = spec.getNeed();
			if (need != null && need.hasAlternatives())
			{
				count[1]++;
			}
			if (need != null && need.startsQuest() != null)
			{
				count[2]++;
			}
		}
		return counts;
	}

	@Test
	public void questIconsKnowWhichQuestTheyStart()
	{
		int[] quests = census().get("Quest");
		assertNotNull(quests);
		System.out.println("progress data: Quest icons that name their quest " + quests[2] + " of " + quests[0]);
		assertTrue("quest icons that name their quest: " + quests[2] + " of " + quests[0], quests[2] >= 100);
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			Need need = spec.getNeed();
			if (need != null && need.startsQuest() != null)
			{
				assertEquals("only quest icons start quests", "Quest", kindOf(spec));
				assertNotNull(Judge.start(need));
			}
		}
	}

	@Test
	public void onlyKindsThatCarryLevelsOrQuestsAreEverJudged()
	{
		Map<String, int[]> counts = census();
		List<String> judged = new ArrayList<>();
		for (Map.Entry<String, int[]> entry : counts.entrySet())
		{
			if (entry.getValue()[1] > 0)
			{
				judged.add(entry.getKey());
			}
		}
		// Round 47 added the kinds requirements.json names: agility courses, dungeon entrances, farming patches and minigames.
		List<String> allowed = Arrays.asList("Agility shortcut", "Transportation", "Rare trees", "Salvaging spot", "Docking point",
			"Agility training", "Dungeon", "Farming patch", "Minigame");
		assertTrue("judged kinds: " + judged, allowed.containsAll(judged));
		assertTrue("shortcuts carry levels: " + judged, judged.contains("Agility shortcut"));
		assertTrue("transport carries levels or quests: " + judged, judged.contains("Transportation"));
		for (String kind : judged)
		{
			System.out.println("progress data: " + kind + " " + counts.get(kind)[1] + " of " + counts.get(kind)[0]);
		}
	}

	@Test
	public void printsTheCensusOfEveryKind()
	{
		for (Map.Entry<String, int[]> entry : census().entrySet())
		{
			System.out.println("census: " + entry.getKey() + " " + entry.getValue()[1] + " of " + entry.getValue()[0]);
		}
	}

	/**
	 * Round 47: icons with a need, by kind, before and after the requirements the wiki states were added. Before: Agility training 0,
	 * Dungeon 0, Farming patch 0, Minigame 0, Transportation 67 (fairy rings, spirit trees, hot air balloons and magic mushtrees had
	 * none). The numbers may grow with the data; they must not fall.
	 */
	@Test
	public void theKindsRound47AddedCarryTheWikisRequirements()
	{
		Map<String, int[]> counts = census();
		int[][] expected = {{16}, {18}, {16}, {23}, {130}, {190}};
		String[] kinds = {"Agility training", "Dungeon", "Farming patch", "Minigame", "Transportation", "Agility shortcut"};
		for (int i = 0; i < kinds.length; i++)
		{
			int[] count = counts.get(kinds[i]);
			assertNotNull(kinds[i], count);
			assertTrue(kinds[i] + ": " + count[1] + " of " + count[0], count[1] >= expected[i][0]);
		}
		// What the icons ask is what the wiki says: a rooftop course its level, a guild its quest points, a network its quest.
		boolean ardougne = false;
		boolean champions = false;
		boolean fairy = false;
		boolean gwd = false;
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			Need need = spec.getNeed();
			if (need == null || !need.hasAlternatives() || spec.getCard() == null)
			{
				continue;
			}
			String text = Judge.ways(need)[0].describe();
			StringBuilder card = new StringBuilder(spec.getCard().getCard().getTitle());
			for (com.worldmapplus.layers.HoverCard.Row row : spec.getCard().getCard().getRows())
			{
				card.append(" | ").append(row.getLabel()).append(": ").append(row.getValue());
			}
			ardougne |= text.equals("90 Agility") && card.indexOf("Requires: 90 Agility") > 0;
			champions |= text.equals("32 Quest points") && card.indexOf("Champions' Challenge") >= 0;
			fairy |= text.equals("Fairytale II - Cure a Queen (started)") && card.indexOf("Fairy ring") == 0;
			gwd |= Judge.ways(need).length == 2 && card.indexOf("Requires: 60 Strength or 60 Agility") > 0;
		}
		assertTrue("Ardougne rooftop course", ardougne);
		assertTrue("Champions' Challenge", champions);
		assertTrue("a fairy ring", fairy);
		assertTrue("God Wars Dungeon, either of two levels", gwd);
	}

	/** Every word of a {@code requires} field the bundled data has, in every file, is one the plugin reads: none is left out. */
	@Test
	public void everyRequirementWordInTheDataIsRead() throws Exception
	{
		java.util.Set<String> words = new java.util.TreeSet<>();
		for (String file : new String[] {"teleports.json", "transports.json", "requirements.json"})
		{
			try (java.io.Reader reader = new java.io.InputStreamReader(
				ProgressDataTest.class.getResourceAsStream("/com/worldmapplus/" + file), java.nio.charset.StandardCharsets.UTF_8))
			{
				collect(new Gson().fromJson(reader, com.google.gson.JsonElement.class), words);
			}
		}
		assertTrue("words found: " + words.size(), words.size() >= 20);
		int diaries = 0;
		int points = 0;
		for (String word : words)
		{
			Requirement read = Requirement.of(null, null, Collections.singletonList(word));
			assertTrue("read: " + word, !read.isEmpty());
			diaries += read.diaryCount();
			points += read.questPoints() > 0 ? 1 : 0;
		}
		System.out.println("progress data: requirement words " + words.size() + ", diary tiers " + diaries + ", quest points " + points);
		assertTrue("diary tiers in the data: " + diaries, diaries >= 20);
		assertEquals("quest points in the data", 1, points);
	}

	private static void collect(com.google.gson.JsonElement element, java.util.Set<String> words)
	{
		if (element.isJsonObject())
		{
			for (Map.Entry<String, com.google.gson.JsonElement> entry : element.getAsJsonObject().entrySet())
			{
				if ("requires".equals(entry.getKey()))
				{
					entry.getValue().getAsJsonArray().forEach(e -> words.add(e.getAsString()));
				}
				else if ("ways".equals(entry.getKey()))
				{
					entry.getValue().getAsJsonArray().forEach(way -> way.getAsJsonArray().forEach(e -> words.add(e.getAsString())));
				}
				else
				{
					collect(entry.getValue(), words);
				}
			}
		}
		else if (element.isJsonArray())
		{
			element.getAsJsonArray().forEach(e -> collect(e, words));
		}
	}

	/** Round 47: teleports that a diary tier gates carry it, from the Shortest Path tables' diary varbits. */
	@Test
	public void teleportsGatedByADiaryTierCarryIt()
	{
		int gated = 0;
		int destinations = 0;
		boolean cloak = false;
		for (com.worldmapplus.layers.Hotspot teleport : assets.getTeleports())
		{
			Need need = teleport.getNeed();
			destinations++;
			if (need == null || !need.hasAlternatives())
			{
				continue;
			}
			boolean diary = false;
			for (Requirement way : Judge.ways(need))
			{
				diary |= way.diaryCount() > 0;
			}
			if (diary)
			{
				gated++;
			}
			Need monastery = teleport.wayNeed("Ardougne cloak: Kandarin Monastery");
			if (monastery != null)
			{
				cloak |= Judge.ways(monastery)[0].describe().equals("Easy Ardougne Diary");
			}
		}
		System.out.println("progress data: teleport destinations whose every way needs a diary tier " + gated + " of " + destinations);
		assertTrue("the Ardougne cloak's monastery teleport needs the easy diary", cloak);
		assertTrue("destinations only reached with a diary: " + gated, gated >= 5);
	}

	@Test
	public void aShortcutIconCarriesTheLevelItsTableSays()
	{
		boolean found = false;
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			Need need = spec.getNeed();
			if (need != null && need.hasAlternatives() && Judge.ways(need).length == 1 && Judge.ways(need)[0].skillCount() == 1
				&& Judge.ways(need)[0].skill(0).getName().equals("Agility") && Judge.ways(need)[0].level(0) == 50)
			{
				found = true;
			}
		}
		assertTrue("an Agility 50 shortcut", found);
	}

	@Test
	public void everyQuestNameTheDataUsesIsOneTheGameKnowsOrIsLeftOut()
	{
		// The names the transport, teleport and shortcut tables use all appear in the game's quest list.
		List<String> names = Arrays.asList("Children of the Sun", "Monkey Madness I", "Desert Treasure II - The Fallen Empire",
			"Recipe for Disaster - King Awowogei", "Mourning's End Part II", "Between a Rock...", "Observatory Quest", "Garden of Tranquillity",
			"Legends' Quest", "Dragon Slayer II", "The Fremennik Trials", "Shades of Mort'ton", "Another Slice of H.A.M.");
		for (String name : names)
		{
			assertNotNull(name, Requirement.questNamed(name));
		}
		assertNull(Requirement.questNamed("Recipe for Nonsense"));
		// Max Total (the max cape) is judged from the total level since round 44; Max Quest is still left out.
		assertTrue(Requirement.of(Collections.singletonList("Max Quest"), null).isEmpty());
		assertTrue(!Requirement.of(Collections.singletonList("Max Total"), null).isEmpty());
	}
}
