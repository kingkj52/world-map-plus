package com.worldmapplus.unlock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

/**
 * Round 47: achievement diary tiers and quest points judged from the snapshot. The words of the data are read into checks at build
 * time since round 70 ({@code tools/test_requirement_checks.py}).
 */
public class DiaryRequirementsTest
{
	/** Varbit values by id; a varbit not in the map answers {@link #otherVarbits}. */
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private int otherVarbits = 0;
	private int questPoints = 120;
	private boolean failDiaries;
	private final Set<Integer> varbitsRead = new HashSet<>();
	private int diaryReads;
	private int pointReads;
	private Unlocks unlocks;

	@Before
	public void setUp()
	{
		unlocks = new Unlocks(new Unlocks.Source()
		{
			@Override
			public boolean loggedIn()
			{
				return true;
			}

			@Override
			public int realLevel(Skill skill)
			{
				return 50;
			}

			@Override
			public QuestState questState(Quest quest)
			{
				return QuestState.FINISHED;
			}

			@Override
			public int diaryValue(int varbit)
			{
				diaryReads++;
				varbitsRead.add(varbit);
				if (failDiaries)
				{
					throw new IllegalStateException("not ready");
				}
				return varbits.getOrDefault(varbit, otherVarbits);
			}

			@Override
			public int questPoints()
			{
				pointReads++;
				return questPoints;
			}
		});
	}

	private void look()
	{
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		for (int i = 0; i < Unlocks.SETTLE_TICKS + Quest.values().length / Unlocks.QUESTS_PER_TICK + 5; i++)
		{
			unlocks.onGameTick();
			assertTrue("reads in a tick " + unlocks.lastTickReads(), unlocks.lastTickReads() <= 64);
		}
	}

	/** The check of a diary tier, as things.json writes it. */
	private static String tier(int tier, String text)
	{
		return "{\"k\": \"d\", \"n\": " + tier + ", \"t\": \"" + text + "\"}";
	}

	private static String points(int count)
	{
		return "{\"k\": \"p\", \"n\": " + count + ", \"t\": \"" + count + " Quest points\"}";
	}

	private static final String ARDOUGNE_MEDIUM = tier(1, "Medium Ardougne Diary");
	private static final String ARDOUGNE_HARD = tier(2, "Hard Ardougne Diary");
	private static final String ARDOUGNE_ELITE = tier(3, "Elite Ardougne Diary");
	private static final String DESERT_ELITE = tier(7, "Elite Desert Diary");
	private static final String KARAMJA_EASY = tier(20, "Easy Karamja Diary");
	private static final String KARAMJA_MEDIUM = tier(21, "Medium Karamja Diary");
	private static final String KARAMJA_HARD = tier(22, "Hard Karamja Diary");

	@Test
	public void everyTierHasItsOwnVarbit()
	{
		Set<Integer> seen = new HashSet<>();
		for (int tier = 0; tier < Diaries.COUNT; tier++)
		{
			assertTrue("one varbit a tier", seen.add(Diaries.varbit(tier)));
		}
		assertEquals(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, Diaries.varbit(2));
		assertEquals(VarbitID.ATJUN_EASY_DONE, Diaries.varbit(20));
		assertEquals(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, Diaries.varbit(23));
		assertEquals(VarbitID.KOUREND_DIARY_ELITE_COMPLETE, Diaries.varbit(27));
	}

	@Test
	public void unknownCountsAsMetForDiariesAndQuestPoints()
	{
		Need hard = Needs.of(ARDOUGNE_HARD);
		// Nothing read yet.
		assertFalse(unlocks.lacks(hard));
		assertNull(unlocks.shortfall(hard));
		// The reads fail: unknown.
		failDiaries = true;
		questPoints = -1;
		unlocks.want(true, true);
		look();
		assertFalse(unlocks.lacks(hard));
		assertFalse(unlocks.lacks(Needs.of(points(300))));
		assertEquals(-1, unlocks.fact(Unlocks.POINTS));
		assertFalse((unlocks.fact(Unlocks.DIARIES + 2) == 1));
		assertFalse((unlocks.fact(Unlocks.DIARIES + 2) == 0));
	}

	@Test
	public void tiersArePartlyKnownAndJudgedEachOnItsOwn()
	{
		varbits.put(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, 0);
		varbits.put(VarbitID.ATJUN_MED_DONE, 2);
		varbits.put(VarbitID.ATJUN_HARD_DONE, 1);
		varbits.put(VarbitID.DESERT_DIARY_ELITE_COMPLETE, -1);
		otherVarbits = 0;
		unlocks.want(true, true);
		look();
		assertTrue((unlocks.fact(Unlocks.DIARIES + 0) == 1));
		assertTrue((unlocks.fact(Unlocks.DIARIES + 1) == 1));
		assertTrue((unlocks.fact(Unlocks.DIARIES + 2) == 0));
		assertFalse(unlocks.lacks(Needs.of(ARDOUGNE_MEDIUM)));
		assertTrue(unlocks.lacks(Needs.of(ARDOUGNE_HARD)));
		assertEquals("Hard Ardougne Diary", unlocks.shortfall(Needs.of(ARDOUGNE_HARD)).text());
		// Karamja's own varbits: any value above 0 is done, so "under way" never marks anything.
		assertFalse(unlocks.lacks(Needs.of(KARAMJA_MEDIUM)));
		assertFalse(unlocks.lacks(Needs.of(KARAMJA_HARD)));
		assertTrue(unlocks.lacks(Needs.of(KARAMJA_EASY)));
		// A tier that could not be read is unknown.
		assertFalse(unlocks.lacks(Needs.of(DESERT_ELITE)));
		// Quest points.
		assertEquals(120, unlocks.fact(Unlocks.POINTS));
		assertFalse(unlocks.lacks(Needs.of(points(32))));
		assertTrue(unlocks.lacks(Needs.of(points(200))));
		assertEquals("200 Quest points", unlocks.shortfall(Needs.of(points(200))).text());
		// Several missing things are listed, the nearest way wins.
		Need two = new com.google.gson.Gson().fromJson("{\"any\": [[" + ARDOUGNE_HARD + ", " + points(200) + "], [" + ARDOUGNE_ELITE + "]]}",
			Need.class);
		assertEquals("Elite Ardougne Diary", unlocks.shortfall(two).text());
		assertEquals(Arrays.asList("Hard Ardougne Diary", "200 Quest points"),
			unlocks.shortfall(Needs.of(ARDOUGNE_HARD, points(200))).items());
		// Every tier's varbit was read, once a look, and the quest points once.
		assertEquals(Diaries.COUNT, varbitsRead.size());
		assertEquals(Diaries.COUNT, diaryReads);
		assertEquals(1, pointReads);
	}

	@Test
	public void diariesAreReadOnlyWhileASettingJudgesWhatCanBeUsed()
	{
		unlocks.want(false, true);
		look();
		assertEquals("hiding finished quests needs no diary", 0, diaryReads);
		assertEquals(0, pointReads);
		unlocks.want(false, false);
		look();
		assertEquals(0, diaryReads);
		unlocks.want(true, true);
		unlocks.onGameTick();
		for (int i = 0; i < 10; i++)
		{
			unlocks.onGameTick();
		}
		assertEquals(Diaries.COUNT, diaryReads);
	}

	@Test
	public void aVariableChangeReadsTheDiariesAgainAtTheQuestsPace()
	{
		unlocks.want(true, true);
		look();
		assertEquals(Diaries.COUNT, diaryReads);
		varbits.put(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, 1);
		otherVarbits = 0;
		// Many changes in one tick start one look.
		for (int i = 0; i < 50; i++)
		{
			unlocks.variablesChanged();
		}
		for (int i = 0; i < 12; i++)
		{
			unlocks.onGameTick();
			assertTrue(unlocks.lastTickReads() <= 64);
		}
		assertEquals(2 * Diaries.COUNT, diaryReads);
		assertTrue((unlocks.fact(Unlocks.DIARIES + 2) == 1));
		// Logging out forgets everything.
		unlocks.onGameStateChanged(GameState.LOGIN_SCREEN);
		assertFalse((unlocks.fact(Unlocks.DIARIES + 2) == 1));
		assertEquals(-1, unlocks.fact(Unlocks.POINTS));
	}
}
