package com.worldmapplus.unlock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

/** Combat level, total level, "Max Total", quests that only need starting, and the state of the quest an icon starts. */
public class MoreRequirementsTest
{
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
	private int combat = 50;
	private int total = 1200;
	private int combatReads;
	private int totalReads;
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
				return levels.getOrDefault(skill, 1);
			}

			@Override
			public QuestState questState(Quest quest)
			{
				return quests.getOrDefault(quest, QuestState.NOT_STARTED);
			}

			@Override
			public int combatLevel()
			{
				combatReads++;
				return combat;
			}

			@Override
			public int totalLevel()
			{
				totalReads++;
				return total;
			}
		});
	}

	private void look()
	{
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		for (int i = 0; i < Unlocks.SETTLE_TICKS + Quest.values().length / Unlocks.QUESTS_PER_TICK + 3; i++)
		{
			unlocks.onGameTick();
			assertTrue("reads in a tick " + unlocks.lastTickReads(), unlocks.lastTickReads() <= 64);
		}
	}

	private static Need need(String skills, String quest)
	{
		return Needs.ofWay(skills == null ? null : Collections.singletonList(skills), quest == null ? null : Collections.singletonList(quest));
	}

	@Test
	public void combatAndTotalLevelsAreJudgedFromTheLocalPlayer()
	{
		unlocks.want(true, true);
		look();
		assertEquals(50, unlocks.fact(Unlocks.COMBAT));
		assertEquals(1200, unlocks.fact(Unlocks.TOTAL));
		assertTrue(unlocks.lacks(need("70 Combat", null)));
		assertFalse(unlocks.lacks(need("40 Combat", null)));
		assertTrue(unlocks.lacks(need("Max Total", null)));
		assertFalse(unlocks.lacks(need("1000 Total", null)));
		assertEquals("Combat level 70", unlocks.shortfall(need("70 Combat", null)).text());
		assertEquals("Every skill at 99", unlocks.shortfall(need("Max Total", null)).text());
		assertNull(unlocks.shortfall(need("1000 Total", null)));
	}

	@Test
	public void unknownCombatAndTotalCountAsMet()
	{
		combat = -1;
		total = 0;
		unlocks.want(true, true);
		look();
		assertFalse(unlocks.lacks(need("126 Combat", null)));
		assertFalse(unlocks.lacks(need("Max Total", null)));
	}

	@Test
	public void nothingIsReadWhileOnlyQuestsAreWanted()
	{
		unlocks.want(false, true);
		look();
		assertEquals(0, combatReads + totalReads);
		assertFalse(unlocks.lacks(need("70 Combat", null)));
	}

	@Test
	public void aLevelUpReadsTheTotalsOnceMore()
	{
		unlocks.want(true, false);
		look();
		int before = combatReads + totalReads;
		total = 1201;
		unlocks.onStatChanged(Skill.AGILITY, 2);
		unlocks.onGameTick();
		assertEquals(before + 2, combatReads + totalReads);
		assertEquals(1201, unlocks.fact(Unlocks.TOTAL));
		unlocks.onGameTick();
		assertEquals("only once", before + 2, combatReads + totalReads);
	}

	@Test
	public void aQuestThatOnlyNeedsStartingIsMetOnceStarted()
	{
		unlocks.want(true, true);
		look();
		Need started = need(null, "Priest in Peril (started)");
		assertNotNull(started);
		assertTrue(unlocks.lacks(started));
		assertEquals("Priest in Peril (started)", unlocks.shortfall(started).text());
		quests.put(Quest.PRIEST_IN_PERIL, QuestState.IN_PROGRESS);
		look();
		assertFalse(unlocks.lacks(started));
	}

	@Test
	public void theQuestAnIconStartsHasThreeStatesAndUnknown()
	{
		Need start = Needs.startOf(Quest.PRIEST_IN_PERIL);
		assertEquals(Unlocks.QUEST_UNKNOWN, unlocks.questProgress(start));
		unlocks.want(false, true);
		look();
		assertEquals(Unlocks.QUEST_NOT_STARTED, unlocks.questProgress(start));
		quests.put(Quest.PRIEST_IN_PERIL, QuestState.IN_PROGRESS);
		look();
		assertEquals(Unlocks.QUEST_STARTED, unlocks.questProgress(start));
		quests.put(Quest.PRIEST_IN_PERIL, QuestState.FINISHED);
		look();
		assertEquals(Unlocks.QUEST_FINISHED, unlocks.questProgress(start));
		unlocks.onGameStateChanged(GameState.LOGIN_SCREEN);
		assertEquals(Unlocks.QUEST_UNKNOWN, unlocks.questProgress(start));
	}
}
