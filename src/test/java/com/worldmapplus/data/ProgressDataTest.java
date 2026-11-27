package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.unlock.Need;
import com.worldmapplus.unlock.Needs;
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
				assertNotNull(need.startsQuestId());
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
			String text = Needs.describe(need.alternatives()[0]);
			StringBuilder card = new StringBuilder(spec.getCard().getCard().getTitle());
			for (com.worldmapplus.layers.HoverCard.Row row : spec.getCard().getCard().getRows())
			{
				card.append(" | ").append(row.getLabel()).append(": ").append(row.getValue());
			}
			ardougne |= text.equals("90 Agility") && card.indexOf("Requires: 90 Agility") > 0;
			champions |= text.equals("32 Quest points") && card.indexOf("Champions' Challenge") >= 0;
			fairy |= text.equals("Fairytale II - Cure a Queen (started)") && card.indexOf("Fairy ring") == 0;
			gwd |= need.alternatives().length == 2 && card.indexOf("Requires: 60 Strength or 60 Agility") > 0;
		}
		assertTrue("Ardougne rooftop course", ardougne);
		assertTrue("Champions' Challenge", champions);
		assertTrue("a fairy ring", fairy);
		assertTrue("God Wars Dungeon, either of two levels", gwd);
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
			for (Need.Check[] way : need.alternatives())
			{
				for (Need.Check check : way)
				{
					diary |= Needs.kind(check) == 'd';
				}
			}
			if (diary)
			{
				gated++;
			}
			Need monastery = teleport.wayNeed("Ardougne cloak: Kandarin Monastery");
			if (monastery != null)
			{
				cloak |= Needs.describe(monastery.alternatives()[0]).equals("Easy Ardougne Diary");
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
			if (need != null && need.alternatives().length == 1 && Needs.describe(need.alternatives()[0]).equals("50 Agility"))
			{
				found = true;
			}
		}
		assertTrue("an Agility 50 shortcut", found);
	}
}
