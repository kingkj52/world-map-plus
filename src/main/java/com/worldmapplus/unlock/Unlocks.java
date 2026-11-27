package com.worldmapplus.unlock;

import java.util.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.gameval.*;

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
 * The facts are one array of numbers ({@link #fact}), replaced as a whole, so any thread may read them. Every answer says "no" when it
 * cannot tell, so a caller that hides or marks things on a "yes" can never hide or mark them wrongly: nothing is known while logged out
 * or for {@link #SETTLE_TICKS} ticks after a login (the game sends its variables a moment after the player appears), a quest that is
 * under way is not held against a requirement, and a level or quest the build tools do not know is left out of the requirement
 * ({@link Need}).
 */
@Singleton
public class Unlocks
{
	/** Ticks to wait after a login before anything is read, because the game sends quest variables and levels a moment late. */
	public static final int SETTLE_TICKS = 6;

	/** Most quests (or diary tiers, after the quests) looked at in one game tick. */
	public static final int QUESTS_PER_TICK = 40;

	/** A new look at all the quests starts no sooner than this many ticks after the last one began. */
	public static final int RESCAN_GAP_TICKS = 5;

	/** How far the player is with a quest: cannot be told, not started, started and not finished, finished. */
	public static final int QUEST_UNKNOWN = 0;
	public static final int QUEST_NOT_STARTED = 1;
	public static final int QUEST_STARTED = 2;
	public static final int QUEST_FINISHED = 3;

	private static final Skill[] SKILLS = Skill.values();
	private static final Quest[] QUEST_LIST = Quest.values();

	/**
	 * Where each fact is in the array: the real level of each skill by its number, the combat level, the total level, the quest points,
	 * each quest's {@code QUEST_} number, then each diary tier (0 not done, 1 done). -1 for anything not read or not told.
	 */
	static final int COMBAT = SKILLS.length;
	static final int TOTAL = COMBAT + 1;
	static final int POINTS = COMBAT + 2;
	static final int QUESTS = COMBAT + 3;
	static final int DIARIES = QUESTS + QUEST_LIST.length;
	/** One past the diaries, which a look uses for its last step, the quest points. */
	private static final int FACTS = DIARIES + Diaries.COUNT;

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

	@RequiredArgsConstructor
	private static final class ClientSource implements Source
	{
		private final Client client;

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
			Player me = client.getLocalPlayer();
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

	/** What other threads read. Replaced as a whole, never changed. */
	private volatile int[] state;
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
	private int[] scan;
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
		int[] now = state;
		if (!wantLevels || !settled || now == null || !trained(skill) || now[skill.ordinal()] == realLevel)
		{
			return;
		}
		int[] next = now.clone();
		next[skill.ordinal()] = realLevel;
		publish(next);
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
		if (refreshAll || questsDirty && wantQuests && !scanning && gap == 0)
		{
			refreshAll = false;
			startLook();
		}
		if (scanning)
		{
			continueScan();
		}
		else if (totalsDirty && wantLevels)
		{
			totalsDirty = false;
			int[] now = state;
			if (now != null)
			{
				int combat = read(COMBAT);
				int total = read(TOTAL);
				if (combat != now[COMBAT] || total != now[TOTAL])
				{
					int[] next = now.clone();
					next[COMBAT] = combat;
					next[TOTAL] = total;
					publish(next);
				}
			}
		}
	}

	private void startLook()
	{
		questsDirty = false;
		gap = RESCAN_GAP_TICKS;
		totalsDirty = false;
		scan = new int[FACTS];
		Arrays.fill(scan, -1);
		for (Skill skill : SKILLS)
		{
			if (wantLevels && trained(skill))
			{
				scan[skill.ordinal()] = read(skill.ordinal());
			}
		}
		// Quests are looked at only when wanted, and the diaries and quest points only with the levels.
		scanAt = wantQuests ? QUESTS : DIARIES;
		scanning = true;
	}

	/** Each step of a look is one read: the quests, the diary tiers, then the quest points; at the end the combat and total levels. */
	private void continueScan()
	{
		int last = wantLevels ? FACTS + 1 : DIARIES;
		int end = Math.min(last, scanAt + QUESTS_PER_TICK);
		for (; scanAt < end; scanAt++)
		{
			scan[scanAt == FACTS ? POINTS : scanAt] = read(scanAt);
		}
		if (scanAt >= last)
		{
			scanning = false;
			if (wantLevels)
			{
				// At the end of a look, the tick with the fewest quest reads, so a tick stays within its bound.
				scan[COMBAT] = read(COMBAT);
				scan[TOTAL] = read(TOTAL);
			}
			publish(scan);
		}
	}

	/** One read of the game, as the number the facts keep, or -1 when it cannot be told. */
	private int read(int fact)
	{
		reads++;
		try
		{
			if (fact < COMBAT)
			{
				int level = source.realLevel(SKILLS[fact]);
				return level >= 1 ? level : -1;
			}
			if (fact == COMBAT || fact == TOTAL)
			{
				int level = fact == COMBAT ? source.combatLevel() : source.totalLevel();
				return level >= (fact == COMBAT ? 3 : 1) ? level : -1;
			}
			if (fact >= QUESTS && fact < DIARIES)
			{
				QuestState found = source.questState(QUEST_LIST[fact - QUESTS]);
				return found == QuestState.NOT_STARTED ? QUEST_NOT_STARTED : found == QuestState.IN_PROGRESS ? QUEST_STARTED
					: found == QuestState.FINISHED ? QUEST_FINISHED : -1;
			}
			int value = fact == FACTS ? source.questPoints() : source.diaryValue(Diaries.varbit(fact - DIARIES));
			return value < 0 ? -1 : fact == FACTS ? value : Math.min(value, 1);
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	private void publish(int[] next)
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

	/** Changes whenever an answer may have changed, so a caller can keep what it worked out until then. */
	public int version()
	{
		return version;
	}

	/** One fact as last read (see {@link #COMBAT} and the others), or -1 when it is not known. */
	int fact(int index)
	{
		int[] now = state;
		return now == null ? -1 : now[index];
	}

	/** How far the player is with the quest this need starts: one of the {@code QUEST_} numbers, unknown when it cannot be told. */
	public int questProgress(Need need)
	{
		Quest quest = need.startsQuestId();
		return quest == null ? QUEST_UNKNOWN : Math.max(QUEST_UNKNOWN, fact(QUESTS + quest.ordinal()));
	}

	/** True only when the quest is known to be finished. */
	public boolean finished(@Nullable Quest quest)
	{
		return quest != null && fact(QUESTS + quest.ordinal()) == QUEST_FINISHED;
	}

	/** True when something is certainly missing for a need. Allocates nothing, so it may be asked for every icon of a view. */
	public boolean lacks(Need need)
	{
		int[] now = state;
		if (now == null || !need.hasAlternatives())
		{
			return false;
		}
		for (Need.Check[] way : need.alternatives())
		{
			if (missing(now, way, null) == 0)
			{
				return false;
			}
		}
		return true;
	}

	/** What is certainly missing for a need, or null when it can be used or when that cannot be told. */
	@Nullable
	public Shortfall shortfall(Need need)
	{
		int[] now = state;
		if (now == null || !need.hasAlternatives())
		{
			return null;
		}
		Shortfall best = null;
		for (Need.Check[] way : need.alternatives())
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

	/** How many of a way's checks are certainly missing, adding their words to {@code out} when it is given. */
	private static int missing(int[] now, Need.Check[] way, @Nullable List<String> out)
	{
		int count = 0;
		for (Need.Check c : way)
		{
			if (unmet(now, c))
			{
				count++;
				if (out != null)
				{
					out.add(c.t);
				}
			}
		}
		return count;
	}

	/**
	 * True when a check is certainly not met. What is not known counts as met: a level, total or points not read, a diary tier not
	 * known to be undone, a quest under way (the data cannot say how far is far enough, and for a quest that only has to be started it
	 * is met), and a skill or quest this RuneLite does not have.
	 */
	private static boolean unmet(int[] now, Need.Check c)
	{
		switch (c.k)
		{
			case 's':
				return c.s != null && below(now[c.s.ordinal()], c.n);
			case 'c':
				return below(now[COMBAT], c.n);
			case 't':
				return below(now[TOTAL], c.n);
			case 'd':
				return c.n >= 0 && c.n < Diaries.COUNT && now[DIARIES + c.n] == 0;
			case 'p':
				return below(now[POINTS], c.n);
			case 'q':
				return c.q != null && now[QUESTS + c.q.ordinal()] == QUEST_NOT_STARTED;
			default:
				return false;
		}
	}

	private static boolean below(int known, int asked)
	{
		return known >= 0 && known < asked;
	}

	/** True for a skill a player trains, which leaves out the total the API lists beside them. */
	private static boolean trained(Skill skill)
	{
		return !"Overall".equalsIgnoreCase(skill.getName());
	}
}
