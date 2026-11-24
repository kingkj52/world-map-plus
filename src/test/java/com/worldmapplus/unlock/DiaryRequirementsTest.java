package com.worldmapplus.unlock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
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

/** Round 47: achievement diary tiers and quest points, read from the words of the data and judged from the snapshot. */
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

	private static Need needs(String... requires)
	{
		return Need.anyOf(Collections.singletonList(Requirement.of(null, null, Arrays.asList(requires))));
	}

	@Test
	public void everySpellingOfADiaryTierIsRead()
	{
		int hardArdougne = 2;
		for (String text : new String[] {"Hard Ardougne Diary", "Ardougne Hard Diary", "Ardougne Hard", "Hard Ardougne",
			"Ardougne Diary (Hard)", "Ardougne diary hard", "Hard Ardougne Achievement Diary", "the Hard Ardougne Diary",
			"Completion of the Hard Ardougne Diary", "ardougne hard diary", "Ardougne: Hard"})
		{
			assertEquals(text, hardArdougne, Diaries.parse(text));
		}
		assertEquals(10 * 4 + 2, Diaries.parse("Hard Western Provinces Diary"));
		assertEquals(10 * 4 + 1, Diaries.parse("Western Medium"));
		assertEquals(5 * 4 + 1, Diaries.parse("Karamja Medium"));
		assertEquals(5 * 4 + 1, Diaries.parse("Karamja Med"));
		assertEquals(3, Diaries.parse("Ardougne Elite"));
		assertEquals(6 * 4 + 3, Diaries.parse("Kourend & Kebos Elite"));
		assertEquals(6 * 4 + 3, Diaries.parse("Elite Kourend and Kebos Diary"));
		assertEquals(6 * 4, Diaries.parse("Kourend Easy"));
		assertEquals(7 * 4 + 2, Diaries.parse("Lumbridge & Draynor Hard"));
		assertEquals(7 * 4 + 2, Diaries.parse("Hard Lumbridge Diary"));
		assertEquals(1 * 4 + 3, Diaries.parse("Elite Desert Diary"));
		assertEquals(3 * 4, Diaries.parse("Easy Fremennik Diary"));
		assertEquals(11 * 4 + 3, Diaries.parse("Wilderness Elite"));
		// Not a diary tier: no tier, two tiers, an unknown region, a quest or a level.
		for (String text : new String[] {"Ardougne Diary", "Hard", "Easy Medium Ardougne", "Hard Misthalin Diary", "Desert Treasure I",
			"Monkey Madness I", "60 Agility", "", "Elite", "Hard Ardougne Varrock Diary"})
		{
			assertEquals(text, -1, Diaries.parse(text));
		}
		assertEquals(-1, Diaries.parse(null));
	}

	@Test
	public void everyTierHasACanonicalNameAndItsOwnVarbit()
	{
		Set<Integer> seen = new HashSet<>();
		for (int tier = 0; tier < Diaries.COUNT; tier++)
		{
			assertEquals(tier, Diaries.parse(Diaries.text(tier)));
			assertTrue("one varbit a tier", seen.add(Diaries.varbit(tier)));
		}
		assertEquals("Hard Ardougne Diary", Diaries.text(2));
		assertEquals("Elite Kourend & Kebos Diary", Diaries.text(27));
		assertEquals(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, Diaries.varbit(2));
		assertEquals(VarbitID.ATJUN_EASY_DONE, Diaries.varbit(20));
		assertEquals(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, Diaries.varbit(23));
		assertEquals(VarbitID.KOUREND_DIARY_ELITE_COMPLETE, Diaries.varbit(27));
	}

	@Test
	public void questPointsAreRead()
	{
		for (String text : new String[] {"32 Quest points", "32 quest points", "32 Quest point", "32 QP", "Quest points: 32",
			"Quest points 32"})
		{
			assertEquals(text, 32, Requirement.of(null, null, Collections.singletonList(text)).questPoints());
		}
		assertEquals(60, Requirement.of(Collections.singletonList("60 Quest points"), null).questPoints());
		assertEquals(60, Requirement.of(null, Collections.singletonList("60 Quest points")).questPoints());
		assertTrue(Requirement.of(null, null, Collections.singletonList("900 Quest points")).isEmpty());
		assertTrue(Requirement.of(null, null, Collections.singletonList("0 Quest points")).isEmpty());
		assertEquals("32 Quest points", Requirement.of(null, null, Collections.singletonList("32 QP")).describe());
	}

	@Test
	public void theWikiWordsListTakesEveryKindAndLeavesTheRestOut()
	{
		Requirement way = Requirement.of(null, null, Arrays.asList("90 Agility", "Song of the Elves", "Elite Desert Diary",
			"32 Quest points", "40 Combat", "a rope", "Partial completion of something"));
		assertEquals(1, way.skillCount());
		assertEquals(Skill.AGILITY, way.skill(0));
		assertEquals(1, way.questCount());
		assertEquals(Quest.SONG_OF_THE_ELVES, way.quest(0));
		assertEquals(1, way.diaryCount());
		assertEquals(7, way.diary(0));
		assertEquals(32, way.questPoints());
		assertEquals(40, way.combatLevel());
		assertEquals("90 Agility, Combat level 40, Song of the Elves, Elite Desert Diary, 32 Quest points", way.describe());
		// Diaries written in the quests list of older data are read too.
		Requirement old = Requirement.of(null, Arrays.asList("Hard Ardougne Diary", "Plague City"));
		assertEquals(1, old.diaryCount());
		assertEquals(1, old.questCount());
		// The same tier twice is one.
		assertEquals(1, Requirement.of(null, null, Arrays.asList("Ardougne Hard", "Hard Ardougne Diary")).diaryCount());
	}

	@Test
	public void unknownCountsAsMetForDiariesAndQuestPoints()
	{
		Need hard = needs("Hard Ardougne Diary");
		// Nothing read yet.
		assertFalse(unlocks.lacks(hard));
		assertNull(unlocks.shortfall(hard));
		// The reads fail: unknown.
		failDiaries = true;
		questPoints = -1;
		unlocks.want(true, true);
		look();
		assertFalse(unlocks.lacks(hard));
		assertFalse(unlocks.lacks(needs("300 Quest points")));
		assertEquals(-1, unlocks.questPoints());
		assertFalse(unlocks.diaryDone(2));
		assertFalse(unlocks.diaryNotDone(2));
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
		assertTrue(unlocks.diaryDone(0));
		assertTrue(unlocks.diaryDone(1));
		assertTrue(unlocks.diaryNotDone(2));
		assertFalse(unlocks.lacks(needs("Ardougne Medium")));
		assertTrue(unlocks.lacks(needs("Ardougne Hard")));
		assertEquals("Hard Ardougne Diary", unlocks.shortfall(needs("Ardougne Hard")).text());
		// Karamja's own varbits: any value above 0 is done, so "under way" never marks anything.
		assertFalse(unlocks.lacks(needs("Karamja Medium")));
		assertFalse(unlocks.lacks(needs("Karamja Hard")));
		assertTrue(unlocks.lacks(needs("Karamja Easy")));
		// A tier that could not be read is unknown.
		assertFalse(unlocks.lacks(needs("Elite Desert Diary")));
		// Quest points.
		assertEquals(120, unlocks.questPoints());
		assertFalse(unlocks.lacks(needs("32 Quest points")));
		assertTrue(unlocks.lacks(needs("200 Quest points")));
		assertEquals("200 Quest points", unlocks.shortfall(needs("200 Quest points")).text());
		// Several missing things are listed, the nearest way wins.
		Need two = Need.anyOf(Arrays.asList(Requirement.of(null, null, Arrays.asList("Hard Ardougne Diary", "200 Quest points")),
			Requirement.of(null, null, Collections.singletonList("Elite Ardougne Diary"))));
		assertEquals("Elite Ardougne Diary", unlocks.shortfall(two).text());
		assertEquals(Arrays.asList("Hard Ardougne Diary", "200 Quest points"),
			unlocks.shortfall(needs("Hard Ardougne Diary", "200 Quest points")).items());
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
		assertTrue(unlocks.diaryDone(2));
		// Logging out forgets everything.
		unlocks.onGameStateChanged(GameState.LOGIN_SCREEN);
		assertFalse(unlocks.diaryDone(2));
		assertEquals(-1, unlocks.questPoints());
	}
}
