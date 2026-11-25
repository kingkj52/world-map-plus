package com.worldmapplus.unlock;

import javax.annotation.Nullable;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * A stand-in for the Helper in tests: the facts of a pretend player are read from a {@link Source} on the same calls the Helper's reader
 * has (a login, a game tick, a level up, a changed variable), and what is lacking for each {@link Need} is judged by a copy of the
 * Helper's {@link Judge}, so tests can say "the player levels up" without a second plugin. It answers the map's questions directly; the
 * cache the real answer lands in is {@link Unlocks}, which has its own test.
 */
public final class FakeProgress implements UnlockView
{
	/** The facts of a pretend player. */
	public interface Source
	{
		boolean loggedIn();

		int realLevel(Skill skill);

		QuestState questState(Quest quest);

		default int diaryValue(int tier)
		{
			return -1;
		}

		default int questPoints()
		{
			return -1;
		}

		default int combatLevel()
		{
			return -1;
		}

		default int totalLevel()
		{
			return -1;
		}
	}

	private final Source source;
	private boolean logged;
	private boolean wanted;
	private boolean dirty;
	private int version;
	private Judge.State state;
	private final int[] levels = new int[Skill.values().length];
	private Judge.Row[] rows = new Judge.Row[0];
	private String[] words = new String[0];
	private int[] wordsAt = new int[0];

	public FakeProgress(Source source)
	{
		this.source = source;
	}

	/** Anything wanted at all makes the player known on the next tick. */
	public void want(boolean levelsWanted, boolean questsWanted)
	{
		wanted = levelsWanted || questsWanted;
		dirty = true;
		if (!wanted)
		{
			clear();
		}
	}

	public void clear()
	{
		if (state != null)
		{
			state = null;
			version++;
		}
	}

	public void onGameStateChanged(GameState now)
	{
		logged = now == GameState.LOGGED_IN;
		if (now == GameState.LOGIN_SCREEN)
		{
			clear();
		}
		dirty |= logged;
	}

	public void onGameTick()
	{
		if (wanted && logged && dirty && source.loggedIn())
		{
			dirty = false;
			for (Skill skill : Skill.values())
			{
				levels[skill.ordinal()] = skill.getName().equals("Overall") ? -1 : source.realLevel(skill);
			}
			look();
		}
	}

	public void variablesChanged()
	{
		dirty = true;
	}

	public void onStatChanged(Skill skill, int level)
	{
		if (wanted && logged)
		{
			levels[skill.ordinal()] = level;
			look();
		}
	}

	private static byte quest(QuestState now)
	{
		return (byte) (now == QuestState.NOT_STARTED ? 1 : now == QuestState.IN_PROGRESS ? 2 : now == QuestState.FINISHED ? 3 : 0);
	}

	private void look()
	{
		byte[] quests = new byte[Quest.values().length];
		for (Quest quest : Quest.values())
		{
			quests[quest.ordinal()] = quest(source.questState(quest));
		}
		byte[] diaries = new byte[Diaries.COUNT];
		for (int tier = 0; tier < diaries.length; tier++)
		{
			int value = source.diaryValue(tier);
			diaries[tier] = (byte) (value < 0 ? 0 : value == 0 ? 1 : 2);
		}
		state = new Judge.State(levels.clone(), quests, source.combatLevel() >= 3 ? source.combatLevel() : -1,
			source.totalLevel() >= 1 ? source.totalLevel() : -1, diaries, source.questPoints());
		version++;
	}

	private Judge.Row row(Need need)
	{
		if (need.number() >= rows.length)
		{
			rows = java.util.Arrays.copyOf(rows, Math.max(need.number() + 1, rows.length * 2));
			words = java.util.Arrays.copyOf(words, rows.length);
			wordsAt = java.util.Arrays.copyOf(wordsAt, rows.length);
		}
		if (rows[need.number()] == null)
		{
			rows[need.number()] = Judge.table(java.util.Collections.singletonList(Need.table().get(need.number()))).get(0);
		}
		return rows[need.number()];
	}

	@Override
	public int version()
	{
		return version;
	}

	@Override
	public int questProgress(Need need)
	{
		Judge.State now = state;
		return now == null ? QUEST_UNKNOWN : Judge.questState(row(need), now);
	}

	@Override
	@Nullable
	public String shortfall(Need need)
	{
		Judge.State now = state;
		if (now == null)
		{
			return null;
		}
		Judge.Row row = row(need);
		// Worked out once for each need and look at the player, as the Helper's answer is.
		if (wordsAt[need.number()] != version)
		{
			wordsAt[need.number()] = version;
			words[need.number()] = Judge.shortfall(row, now);
		}
		return words[need.number()];
	}
}
