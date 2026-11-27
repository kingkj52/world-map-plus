package com.worldmapplus.unlock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

/**
 * What the progress snapshot reads, when, and how little it claims: what cannot be judged counts as met, so nothing is ever marked or
 * hidden wrongly, and the game is only asked when a setting needs the answer and only a few quests a tick.
 */
public class UnlocksTest
{
	/** The facts of a pretend player, and a count of how often they were asked for. */
	private static final class FakeSource implements Unlocks.Source
	{
		boolean loggedIn = true;
		final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
		int levelReads;
		int questReads;

		@Override
		public boolean loggedIn()
		{
			return loggedIn;
		}

		@Override
		public int realLevel(Skill skill)
		{
			levelReads++;
			return levels.getOrDefault(skill, 1);
		}

		@Override
		public QuestState questState(Quest quest)
		{
			questReads++;
			return quests.getOrDefault(quest, QuestState.NOT_STARTED);
		}
	}

	private FakeSource source;
	private Unlocks unlocks;

	@Before
	public void setUp()
	{
		source = new FakeSource();
		unlocks = new Unlocks(source);
	}

	private void ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			unlocks.onGameTick();
		}
	}

	/** Logs in and lets the snapshot settle and finish looking. */
	private void loggedInAndLooked()
	{
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		ticks(Unlocks.SETTLE_TICKS + Quest.values().length / Unlocks.QUESTS_PER_TICK + 3);
	}

	private static Need need(List<String> skills, List<String> quests)
	{
		return Needs.ofWay(skills, quests);
	}

	@Test
	public void whatCannotBeToldCountsAsMet()
	{
		Need level = need(Collections.singletonList("90 Agility"), null);
		Need quest = need(null, Collections.singletonList("Monkey Madness I"));
		unlocks.want(true, true);
		// Nothing has been looked at yet: not logged in, no snapshot.
		assertFalse(unlocks.lacks(level));
		assertNull(unlocks.shortfall(level));
		assertFalse(unlocks.lacks(quest));
		assertFalse(unlocks.finished(Quest.COOKS_ASSISTANT));
		assertEquals(-1, unlocks.fact(Skill.AGILITY.ordinal()));
		// Logged in, but the game has not yet sent its variables: still nothing is claimed.
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		ticks(Unlocks.SETTLE_TICKS - 1);
		assertFalse(unlocks.lacks(level));
		assertFalse(unlocks.lacks(quest));
		assertEquals("nothing is read before the settling ticks are over", 0, source.levelReads + source.questReads);
	}

	@Test
	public void aLevelThatIsMissingIsMissingAndOneThatIsNotIsNot()
	{
		source.levels.put(Skill.AGILITY, 49);
		source.levels.put(Skill.SAILING, 70);
		unlocks.want(true, true);
		loggedInAndLooked();
		Need agility = need(Collections.singletonList("50 Agility"), null);
		Need sailing = need(Collections.singletonList("67 Sailing"), null);
		assertTrue(unlocks.lacks(agility));
		assertEquals("50 Agility", unlocks.shortfall(agility).text());
		assertFalse(unlocks.lacks(sailing));
		assertNull(unlocks.shortfall(sailing));
	}

	@Test
	public void aLevelUpIsTakenAtOnceWithoutLookingAtTheWholeGameAgain()
	{
		source.levels.put(Skill.AGILITY, 49);
		unlocks.want(true, true);
		loggedInAndLooked();
		Need agility = need(Collections.singletonList("50 Agility"), null);
		assertTrue(unlocks.lacks(agility));
		int reads = source.levelReads + source.questReads;
		int version = unlocks.version();
		unlocks.onStatChanged(Skill.AGILITY, 50);
		assertFalse(unlocks.lacks(agility));
		assertEquals(50, unlocks.fact(Skill.AGILITY.ordinal()));
		assertTrue("the version moves so the map works its icons out again", unlocks.version() != version);
		assertEquals("a level up asks the game for nothing", reads, source.levelReads + source.questReads);
		// Experience that is not a new level changes nothing.
		version = unlocks.version();
		unlocks.onStatChanged(Skill.AGILITY, 50);
		assertEquals(version, unlocks.version());
	}

	@Test
	public void aFinishedQuestIsFinishedAndOneNotStartedIsMissingButOneUnderWayIsNotHeldAgainstYou()
	{
		source.quests.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		source.quests.put(Quest.MONKEY_MADNESS_I, QuestState.IN_PROGRESS);
		unlocks.want(false, true);
		loggedInAndLooked();
		assertTrue(unlocks.finished(Quest.COOKS_ASSISTANT));
		assertFalse(unlocks.finished(Quest.MONKEY_MADNESS_I));
		assertFalse("a quest this RuneLite does not have is never finished", unlocks.finished(null));
		assertFalse(unlocks.lacks(need(null, Collections.singletonList("Cook's Assistant"))));
		assertFalse("under way is not held against a need", unlocks.lacks(need(null, Collections.singletonList("Monkey Madness I"))));
		Need notStarted = need(null, Collections.singletonList("Priest in Peril"));
		assertTrue(unlocks.lacks(notStarted));
		assertEquals("Priest in Peril", unlocks.shortfall(notStarted).text());
	}

	@Test
	public void aCheckOfASkillOrQuestThisRuneLiteDoesNotHaveIsMet()
	{
		unlocks.want(true, true);
		loggedInAndLooked();
		Need unknown = new com.google.gson.Gson().fromJson("{\"any\": [[{\"k\": \"s\", \"s\": \"FLYING\", \"n\": 50, \"t\": \"50 Flying\"},"
			+ " {\"k\": \"q\", \"q\": \"A_QUEST_NOBODY_WROTE\", \"t\": \"A Quest Nobody Wrote\"}, {\"k\": \"x\", \"t\": \"?\"},"
			+ " {\"k\": \"d\", \"n\": 99, \"t\": \"?\"}]]}", Need.class);
		assertTrue(unknown.hasAlternatives());
		assertFalse(unlocks.lacks(unknown));
		assertNull(unlocks.shortfall(unknown));
	}

	@Test
	public void aStopIsOutOfReachOnlyWhenEveryWayOfUsingItIs()
	{
		source.levels.put(Skill.MAGIC, 40);
		unlocks.want(true, true);
		loggedInAndLooked();
		Need both = new Needs.Ways().add(Collections.singletonList("90 Magic"), null).add(Collections.singletonList("33 Magic"), null).build();
		assertFalse("one way is open", unlocks.lacks(both));
		Need neither = new Needs.Ways().add(Collections.singletonList("90 Magic"), null)
			.add(Arrays.asList("60 Magic", "75 Agility"), null).build();
		assertTrue(unlocks.lacks(neither));
		assertEquals("the way with the least missing is the one a card names", "90 Magic", unlocks.shortfall(neither).text());
		assertNull("a way that asks nothing makes the whole thing open to everybody",
			new Needs.Ways().add(Collections.singletonList("90 Magic"), null).add(null, null).build());
	}

	@Test
	public void nothingIsReadWhileNoSettingNeedsIt()
	{
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		ticks(40);
		unlocks.variablesChanged();
		unlocks.onStatChanged(Skill.AGILITY, 80);
		ticks(40);
		assertEquals(0, source.levelReads);
		assertEquals(0, source.questReads);
		assertEquals(0, unlocks.version());
		assertFalse(unlocks.isWanted());
		assertFalse(unlocks.lacks(need(Collections.singletonList("99 Agility"), null)));
	}

	@Test
	public void turningASettingOnLooksThenAndTurningThemAllOffLetsGo()
	{
		source.levels.put(Skill.AGILITY, 10);
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		ticks(Unlocks.SETTLE_TICKS + 2);
		assertEquals(0, source.levelReads);
		unlocks.want(true, true);
		ticks(Quest.values().length / Unlocks.QUESTS_PER_TICK + 3);
		assertEquals(10, unlocks.fact(Skill.AGILITY.ordinal()));
		int version = unlocks.version();
		unlocks.want(false, false);
		assertEquals(-1, unlocks.fact(Skill.AGILITY.ordinal()));
		assertTrue(unlocks.version() != version);
		assertFalse(unlocks.lacks(need(Collections.singletonList("99 Agility"), null)));
	}

	@Test
	public void onlyLevelsAreReadWhenOnlyLevelsAreNeededAndOnlyQuestsWhenOnlyQuestsAre()
	{
		unlocks.want(false, true);
		loggedInAndLooked();
		assertEquals(0, source.levelReads);
		assertEquals(Quest.values().length, source.questReads);
		source = new FakeSource();
		unlocks = new Unlocks(source);
		unlocks.want(true, false);
		loggedInAndLooked();
		assertEquals(0, source.questReads);
		assertTrue(source.levelReads > 0);
	}

	@Test
	public void noTickReadsMoreThanAFewDozen()
	{
		unlocks.want(true, true);
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		int most = 0;
		for (int i = 0; i < 60; i++)
		{
			source.levelReads = 0;
			source.questReads = 0;
			unlocks.onGameTick();
			most = Math.max(most, source.levelReads + source.questReads);
			if (i % 3 == 0)
			{
				unlocks.variablesChanged();
			}
		}
		assertTrue("most reads in one tick: " + most, most <= Unlocks.QUESTS_PER_TICK + Skill.values().length);
		assertTrue(most > 0);
	}

	@Test
	public void manyVariableChangesStartOneNewLookAtTheQuestsPerGap()
	{
		unlocks.want(false, true);
		loggedInAndLooked();
		int reads = source.questReads;
		assertEquals(Quest.values().length, reads);
		source.quests.put(Quest.PRIEST_IN_PERIL, QuestState.FINISHED);
		for (int i = 0; i < 500; i++)
		{
			unlocks.variablesChanged();
		}
		ticks(Unlocks.RESCAN_GAP_TICKS + Quest.values().length / Unlocks.QUESTS_PER_TICK + 2);
		assertEquals("one more look at every quest, however many variables changed", reads + Quest.values().length, source.questReads);
		assertTrue("the finished quest is seen", unlocks.finished(Quest.PRIEST_IN_PERIL));
	}

	@Test
	public void nothingIsReadWhileLoggedOutAndNothingOfOneAccountSurvivesToTheNext()
	{
		source.quests.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		unlocks.want(true, true);
		loggedInAndLooked();
		assertTrue(unlocks.finished(Quest.COOKS_ASSISTANT));
		unlocks.onGameStateChanged(GameState.LOGIN_SCREEN);
		assertFalse(unlocks.finished(Quest.COOKS_ASSISTANT));
		assertEquals(-1, unlocks.fact(Skill.AGILITY.ordinal()));
		source.loggedIn = false;
		int reads = source.levelReads + source.questReads;
		ticks(50);
		unlocks.variablesChanged();
		ticks(50);
		assertEquals(reads, source.levelReads + source.questReads);
	}

	@Test
	public void aSourceThatFailsLeavesTheAnswerUnknown()
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
				throw new IllegalStateException("the client is loading");
			}

			@Override
			public QuestState questState(Quest quest)
			{
				throw new IllegalStateException("the client is loading");
			}
		});
		unlocks.want(true, true);
		loggedInAndLooked();
		Need need = new Needs.Ways().add(Collections.singletonList("90 Agility"), Collections.singletonList("Priest in Peril")).build();
		assertNotNull(need);
		assertFalse(unlocks.lacks(need));
		assertFalse(unlocks.finished(Quest.COOKS_ASSISTANT));
	}

	@Test
	public void aWorldHopKeepsTheSnapshotButOnlyTheNextLookCanChangeIt()
	{
		source.quests.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		unlocks.want(true, true);
		loggedInAndLooked();
		unlocks.onGameStateChanged(GameState.HOPPING);
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		assertTrue("still known while the game settles after a hop", unlocks.finished(Quest.COOKS_ASSISTANT));
	}
}
