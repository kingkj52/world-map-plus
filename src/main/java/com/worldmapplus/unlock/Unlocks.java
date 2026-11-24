package com.worldmapplus.unlock;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;

/**
 * A snapshot of what the player has done and can do: real skill levels, the state of every quest, which achievement diary tiers are
 * done and the quest points, kept so the map can mark icons the player cannot use yet and hide quests that are finished.
 * <p>
 * The game is read on the client thread only, and only while one of the settings that need it is on. Nothing is polled: a level up
 * (StatChanged) updates the one level, a change of any game variable (VarbitChanged) asks for the quests to be looked at again, a
 * login (GameStateChanged) asks for everything, and a change of setting (ConfigChanged, through {@link #want}) asks for what was not
 * needed before. The looking is done a few quests at a time on game ticks ({@link #QUESTS_PER_TICK}), and a new look at the quests is
 * started no more often than every {@link #RESCAN_GAP_TICKS} ticks, however many variables change.
 * <p>
 * The diary tiers and the quest points are read with the levels, only while a setting that judges what can be used is on: one plain
 * read of a game variable each ({@link Diaries#varbit}, the quest point counter), at the end of each look, inside the same bound of
 * reads per tick. They change rarely, and a look is only started by the events above, so nothing is read more often than the quests.
 * <p>
 * The result is an immutable {@link State} that is replaced as a whole, so any thread may read it ({@link UnlockView}). What cannot be
 * judged counts as met: nothing is known while logged out or for {@link #SETTLE_TICKS} ticks after a login (the game sends its
 * variables a moment after the player appears), a quest that is under way is not held against a requirement, and a level or quest the
 * data names in a way this class does not know is left out of the requirement ({@link Requirement}).
 */
@Singleton
public class Unlocks implements UnlockView
{
	/** Ticks to wait after a login before anything is read, because the game sends quest variables and levels a moment late. */
	public static final int SETTLE_TICKS = 6;

	/** Most quests (or diary tiers, after the quests) looked at in one game tick. */
	public static final int QUESTS_PER_TICK = 40;

	/** A new look at all the quests starts no sooner than this many ticks after the last one began. */
	public static final int RESCAN_GAP_TICKS = 5;

	private static final byte UNKNOWN = 0;
	private static final byte NOT_STARTED = 1;
	private static final byte IN_PROGRESS = 2;
	private static final byte FINISHED = 3;

	private static final byte DIARY_UNKNOWN = 0;
	private static final byte DIARY_NOT_DONE = 1;
	private static final byte DIARY_DONE = 2;

	private static final Skill[] SKILLS = Skill.values();
	private static final int QUEST_COUNT = Quest.values().length;
	/** The steps of a look: each quest, then each diary tier, then the quest points. */
	private static final int STEPS = QUEST_COUNT + Diaries.COUNT + 1;

	/** Where the facts come from. Asked on the client thread only. */
	public interface Source
	{
		boolean loggedIn();

		int realLevel(Skill skill);

		@Nullable
		QuestState questState(Quest quest);

		/** The local player's combat level, or -1 when it cannot be told. */
		default int combatLevel()
		{
			return -1;
		}

		/** The total level, or -1 when it cannot be told. */
		default int totalLevel()
		{
			return -1;
		}

		/** The value of the varbit that says a diary tier is done, or -1 when it cannot be told. */
		default int diaryValue(int varbit)
		{
			return -1;
		}

		/** The player's quest points, or -1 when they cannot be told. */
		default int questPoints()
		{
			return -1;
		}
	}

	/** The facts as of one look. Never changed after it is made. */
	private static final class State
	{
		/** The real level of each skill by its number, or -1 when it was not read. */
		private final int[] levels;
		/** The state of each quest by its number: 0 unknown, 1 not started, 2 in progress, 3 finished. */
		private final byte[] quests;
		/** The local player's combat level and the total level, or -1 when not read. */
		private final int combat;
		private final int total;
		/** Each diary tier by its {@link Diaries} number: 0 unknown, 1 not done, 2 done. */
		private final byte[] diaries;
		/** The quest points, or -1 when not read. */
		private final int questPoints;

		State(int[] levels, byte[] quests, int combat, int total, byte[] diaries, int questPoints)
		{
			this.levels = levels;
			this.quests = quests;
			this.combat = combat;
			this.total = total;
			this.diaries = diaries;
			this.questPoints = questPoints;
		}

		State withTotals(int combat, int total)
		{
			return new State(levels, quests, combat, total, diaries, questPoints);
		}
	}

	private static final class ClientSource implements Source
	{
		private final Client client;

		ClientSource(Client client)
		{
			this.client = client;
		}

		@Override
		public boolean loggedIn()
		{
			return client.getGameState() == GameState.LOGGED_IN;
		}

		@Override
		public int realLevel(Skill skill)
		{
			return client.getRealSkillLevel(skill);
		}

		@Override
		public QuestState questState(Quest quest)
		{
			return quest.getState(client);
		}

		@Override
		public int combatLevel()
		{
			// The local player only: other players are never read.
			net.runelite.api.Player me = client.getLocalPlayer();
			return me == null ? -1 : me.getCombatLevel();
		}

		@Override
		public int totalLevel()
		{
			return client.getTotalLevel();
		}

		@Override
		public int diaryValue(int varbit)
		{
			return client.getVarbitValue(varbit);
		}

		@Override
		public int questPoints()
		{
			return client.getVarpValue(VarPlayerID.QP);
		}
	}

	private final Source source;

	/** What other threads read. Replaced as a whole. */
	private volatile State state;
	private volatile int version;

	// The rest is for the client thread.
	private boolean wantLevels;
	private boolean wantQuests;
	private int settle;
	private boolean settled;
	private boolean refreshAll;
	private boolean questsDirty;
	private int gap;
	private boolean scanning;
	private int scanAt;
	private byte[] scan;
	private int[] scanLevels;
	private int scanCombat = -1;
	private int scanTotal = -1;
	private byte[] scanDiaries;
	private int scanPoints = -1;
	/** True after a level up, so the combat and total levels are read again at the next tick. */
	private boolean totalsDirty;
	private int reads;

	@Inject
	public Unlocks(Client client)
	{
		this(new ClientSource(client));
	}

	/** For tests, which supply their own facts. */
	public Unlocks(Source source)
	{
		this.source = source;
	}

	/**
	 * Says what the settings need: levels (to mark what cannot be used yet) and quests (to mark that, or to hide finished quests). With
	 * neither wanted nothing is read and nothing is kept. Called on the client thread.
	 */
	public void want(boolean levels, boolean quests)
	{
		boolean more = levels && !wantLevels || quests && !wantQuests;
		wantLevels = levels;
		wantQuests = quests;
		if (!levels && !quests)
		{
			drop();
		}
		else if (more)
		{
			refreshAll = true;
		}
	}

	/** True when any setting needs the snapshot. */
	public boolean isWanted()
	{
		return wantLevels || wantQuests;
	}

	/** Called on the client thread when the game state changes. */
	public void onGameStateChanged(GameState gameState)
	{
		if (gameState == GameState.LOGGED_IN)
		{
			settle = SETTLE_TICKS;
			settled = false;
			scanning = false;
		}
		else if (gameState == GameState.LOGIN_SCREEN || gameState == GameState.LOGGING_IN)
		{
			// Another account may follow, so nothing of this one is kept.
			settle = 0;
			settled = false;
			refreshAll = false;
			questsDirty = false;
			scanning = false;
			drop();
		}
	}

	/** Called on the client thread when a skill's experience or level changes. Only a change of real level does anything. */
	public void onStatChanged(Skill skill, int realLevel)
	{
		State now = state;
		if (!wantLevels || !settled || now == null || !Requirement.isTrained(skill) || now.levels[skill.ordinal()] == realLevel)
		{
			return;
		}
		int[] levels = now.levels.clone();
		levels[skill.ordinal()] = realLevel;
		publish(new State(levels, now.quests, now.combat, now.total, now.diaries, now.questPoints));
		// A new level can raise the total and the combat level; they are read once at the next tick.
		totalsDirty = true;
	}

	/** Called on the client thread when any game variable changes. A quest may have moved on, so the quests are looked at again soon. */
	public void variablesChanged()
	{
		if (wantQuests && settled)
		{
			questsDirty = true;
		}
	}

	/** Called on the client thread once a game tick. Does a bounded piece of the looking. */
	public void onGameTick()
	{
		reads = 0;
		if (settle > 0 && --settle == 0)
		{
			settled = true;
			refreshAll = true;
		}
		if (gap > 0)
		{
			gap--;
		}
		if (!settled || !isWanted() || !source.loggedIn())
		{
			return;
		}
		if (refreshAll)
		{
			refreshAll = false;
			startLook();
		}
		else if (questsDirty && wantQuests && !scanning && gap == 0)
		{
			startLook();
		}
		if (scanning)
		{
			continueScan();
		}
		else if (totalsDirty && wantLevels)
		{
			totalsDirty = false;
			State now = state;
			if (now != null)
			{
				int combat = combat();
				int total = total();
				if (combat != now.combat || total != now.total)
				{
					publish(now.withTotals(combat, total));
				}
			}
		}
	}

	private void startLook()
	{
		questsDirty = false;
		gap = RESCAN_GAP_TICKS;
		scanLevels = new int[SKILLS.length];
		java.util.Arrays.fill(scanLevels, -1);
		scanCombat = -1;
		scanTotal = -1;
		totalsDirty = false;
		if (wantLevels)
		{
			for (Skill skill : SKILLS)
			{
				if (Requirement.isTrained(skill))
				{
					scanLevels[skill.ordinal()] = level(skill);
				}
			}
		}
		scan = new byte[QUEST_COUNT];
		scanDiaries = new byte[Diaries.COUNT];
		scanPoints = -1;
		// Quests are looked at only when wanted, and the diaries and quest points only with the levels.
		scanAt = wantQuests ? 0 : QUEST_COUNT;
		scanning = true;
	}

	private int combat()
	{
		reads++;
		try
		{
			int level = source.combatLevel();
			return level >= 3 ? level : -1;
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	private int total()
	{
		reads++;
		try
		{
			int level = source.totalLevel();
			return level >= 1 ? level : -1;
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	private int level(Skill skill)
	{
		reads++;
		try
		{
			int level = source.realLevel(skill);
			return level >= 1 ? level : -1;
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	/** Reads the combat and total levels at the end of a look, the tick with the fewest quest reads, so a tick stays within its bound. */
	private void readTotals()
	{
		if (wantLevels)
		{
			scanCombat = combat();
			scanTotal = total();
		}
	}

	private void continueScan()
	{
		int last = wantLevels ? STEPS : QUEST_COUNT;
		int end = Math.min(last, scanAt + QUESTS_PER_TICK);
		Quest[] quests = scanAt < QUEST_COUNT ? Quest.values() : null;
		for (; scanAt < end; scanAt++)
		{
			if (scanAt < QUEST_COUNT)
			{
				scan[scanAt] = stateOf(quests[scanAt]);
			}
			else if (scanAt < QUEST_COUNT + Diaries.COUNT)
			{
				scanDiaries[scanAt - QUEST_COUNT] = diaryOf(scanAt - QUEST_COUNT);
			}
			else
			{
				scanPoints = readQuestPoints();
			}
		}
		if (scanAt >= last)
		{
			scanning = false;
			readTotals();
			publish(new State(scanLevels, scan, scanCombat, scanTotal, scanDiaries, scanPoints));
		}
	}

	private byte diaryOf(int tier)
	{
		reads++;
		try
		{
			int value = source.diaryValue(Diaries.varbit(tier));
			return value < 0 ? DIARY_UNKNOWN : value == 0 ? DIARY_NOT_DONE : DIARY_DONE;
		}
		catch (RuntimeException e)
		{
			return DIARY_UNKNOWN;
		}
	}

	private int readQuestPoints()
	{
		reads++;
		try
		{
			int points = source.questPoints();
			return points >= 0 ? points : -1;
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	private byte stateOf(Quest quest)
	{
		reads++;
		try
		{
			QuestState found = source.questState(quest);
			if (found == null)
			{
				return UNKNOWN;
			}
			switch (found)
			{
				case NOT_STARTED:
					return NOT_STARTED;
				case IN_PROGRESS:
					return IN_PROGRESS;
				case FINISHED:
					return FINISHED;
				default:
					return UNKNOWN;
			}
		}
		catch (RuntimeException e)
		{
			return UNKNOWN;
		}
	}

	private void publish(State next)
	{
		state = next;
		version++;
	}

	private void drop()
	{
		if (state != null)
		{
			state = null;
			version++;
		}
	}

	/** How many reads of the game the last game tick did, for tests. */
	int lastTickReads()
	{
		return reads;
	}

	@Override
	public int version()
	{
		return version;
	}

	/** The real level of a skill as last read, or -1 when it is not known. */
	public int levelOf(Skill skill)
	{
		State now = state;
		return now == null || !Requirement.isTrained(skill) ? -1 : now.levels[skill.ordinal()];
	}

	/** True only when the quest is known to be finished. */
	public boolean isFinished(Quest quest)
	{
		State now = state;
		return now != null && now.quests[quest.ordinal()] == FINISHED;
	}

	/** The combat level as last read, or -1 when it is not known. */
	public int combatLevel()
	{
		State now = state;
		return now == null ? -1 : now.combat;
	}

	/** The total level as last read, or -1 when it is not known. */
	public int totalLevel()
	{
		State now = state;
		return now == null ? -1 : now.total;
	}

	/** The quest points as last read, or -1 when they are not known. */
	public int questPoints()
	{
		State now = state;
		return now == null ? -1 : now.questPoints;
	}

	/** True only when the diary tier (a {@link Diaries} number) is known to be done. */
	public boolean diaryDone(int tier)
	{
		State now = state;
		return now != null && now.diaries[tier] == DIARY_DONE;
	}

	/** True only when the diary tier (a {@link Diaries} number) is known not to be done. */
	public boolean diaryNotDone(int tier)
	{
		State now = state;
		return now != null && now.diaries[tier] == DIARY_NOT_DONE;
	}

	@Override
	public int questProgress(Need need)
	{
		State now = state;
		Quest quest = need.startsQuestId();
		return now == null || quest == null ? QUEST_UNKNOWN : now.quests[quest.ordinal()];
	}

	@Override
	public boolean finishedQuest(@Nullable String questName)
	{
		Quest quest = Requirement.questNamed(questName);
		return quest != null && isFinished(quest);
	}

	@Override
	public boolean finished(Need need)
	{
		Quest quest = need.startsQuestId();
		return quest != null && isFinished(quest);
	}

	@Override
	public boolean lacks(Need need)
	{
		State now = state;
		if (now == null || !need.hasAlternatives())
		{
			return false;
		}
		for (Requirement way : need.alternatives())
		{
			if (missing(now, way, null) == 0)
			{
				return false;
			}
		}
		return true;
	}

	@Override
	@Nullable
	public Shortfall shortfall(Need need)
	{
		State now = state;
		if (now == null || !need.hasAlternatives())
		{
			return null;
		}
		Shortfall best = null;
		for (Requirement way : need.alternatives())
		{
			List<String> missing = new ArrayList<>();
			if (missing(now, way, missing) == 0)
			{
				return null;
			}
			if (best == null || missing.size() < best.items().size())
			{
				best = new Shortfall(missing);
			}
		}
		return best;
	}

	/** How many of a way's levels and quests are certainly missing, adding their words to {@code out} when it is given. */
	private static int missing(State now, Requirement way, @Nullable List<String> out)
	{
		int count = 0;
		for (int i = 0; i < way.skillCount(); i++)
		{
			int have = now.levels[way.skill(i).ordinal()];
			if (have >= 0 && have < way.level(i))
			{
				count++;
				if (out != null)
				{
					out.add(way.level(i) + " " + way.skill(i).getName());
				}
			}
		}
		if (way.combatLevel() > 0 && now.combat >= 0 && now.combat < way.combatLevel())
		{
			count++;
			if (out != null)
			{
				out.add(Requirement.combatText(way.combatLevel()));
			}
		}
		if (way.totalLevel() > 0 && now.total >= 0 && now.total < way.totalLevel())
		{
			count++;
			if (out != null)
			{
				out.add(Requirement.totalText(way.totalLevel()));
			}
		}
		for (int i = 0; i < way.diaryCount(); i++)
		{
			// Only a tier known not to be done is missing: unknown counts as done.
			if (now.diaries[way.diary(i)] == DIARY_NOT_DONE)
			{
				count++;
				if (out != null)
				{
					out.add(Diaries.text(way.diary(i)));
				}
			}
		}
		if (way.questPoints() > 0 && now.questPoints >= 0 && now.questPoints < way.questPoints())
		{
			count++;
			if (out != null)
			{
				out.add(Requirement.questPointsText(way.questPoints()));
			}
		}
		for (int i = 0; i < way.questCount(); i++)
		{
			// A quest that is under way is not held against the requirement: the data cannot say how far is far enough, and for a
			// quest that only has to be started ("Swan Song (started)") it is met.
			if (now.quests[way.quest(i).ordinal()] == NOT_STARTED)
			{
				count++;
				if (out != null)
				{
					out.add(way.questName(i));
				}
			}
		}
		return count;
	}
}
