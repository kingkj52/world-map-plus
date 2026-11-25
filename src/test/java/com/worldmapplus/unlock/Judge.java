package com.worldmapplus.unlock;

// A copy of the World Map Plus Helper's class of the same name (its `progress` package), for the tests that need a pretend Helper.

import java.util.*;
import javax.annotation.*;
import net.runelite.api.*;

/**
 * Judges what the player lacks for each thing World Map Plus asks about. World Map Plus keeps a table of needs (what icons and cards
 * ask of the player, in the data's own words, see its {@code Need}) and sends it; this class reads each row once ({@link #table}) and
 * works out, for a snapshot of the player's progress, the state of the quest a row starts and what is certainly missing for it
 * ({@link #judge}). The answer is one place for each row, which World Map Plus looks up by the row's number.
 * <p>
 * Only what is certain is held against the player: a level or quest the words do not name in a way {@link Requirement} knows is left
 * out, an unknown progress counts as met, and a quest under way is not held against a requirement.
 */
public final class Judge
{
	/** The most rows read, ways for a row and texts for a way: a table of another size than the data's is cut. */
	static final int MAX_ROWS = 100_000;
	static final int MAX_WAYS = 64;
	static final int MAX_TEXTS = 64;

	private static final byte NOT_STARTED = 1;
	private static final byte DIARY_NOT_DONE = 1;

	/** One row of the table: the ways of using something, of which one must be met, and the quest it starts (or null). */
	public static final class Row
	{
		private final Requirement[] ways;
		@Nullable
		private final Quest starts;

		Row(Requirement[] ways, @Nullable Quest starts)
		{
			this.ways = ways;
			this.starts = starts;
		}
	}

	/** The facts of a pretend player, as the Helper's reader holds them. */
	public static final class State
	{
		final int[] levels;
		final byte[] quests;
		final int combat;
		final int total;
		final byte[] diaries;
		final int questPoints;

		State(int[] levels, byte[] quests, int combat, int total, byte[] diaries, int questPoints)
		{
			this.levels = levels;
			this.quests = quests;
			this.combat = combat;
			this.total = total;
			this.diaries = diaries;
			this.questPoints = questPoints;
		}
	}

	/** For the tests of the map: the ways a need asks, as the Helper reads them. */
	public static Requirement[] ways(Need need)
	{
		return row(Need.table().get(need.number())).ways;
	}

	/** The quest a need starts, as the Helper reads it. */
	@Nullable
	public static Quest start(Need need)
	{
		return row(Need.table().get(need.number())).starts;
	}

	private Judge()
	{
	}

	/**
	 * Reads the table World Map Plus sent: a List of rows, each a List of the ways (each a List of three Lists of texts: levels, quests
	 * and the wiki's own wording) and the name of the quest the row starts. A row that is not whole is a row that asks nothing.
	 */
	public static List<Row> table(@Nullable Object raw)
	{
		List<Row> rows = new ArrayList<>();
		if (!(raw instanceof List))
		{
			return rows;
		}
		for (Object item : (List<?>) raw)
		{
			if (rows.size() >= MAX_ROWS)
			{
				break;
			}
			rows.add(row(item));
		}
		return rows;
	}

	private static Row row(Object item)
	{
		List<Requirement> ways = new ArrayList<>();
		Quest starts = null;
		if (item instanceof List && ((List<?>) item).size() == 2)
		{
			Object given = ((List<?>) item).get(0);
			Object start = ((List<?>) item).get(1);
			starts = start instanceof String ? Requirement.questNamed((String) start) : null;
			for (Object way : given instanceof List ? (List<?>) given : Collections.emptyList())
			{
				List<?> parts = way instanceof List && ((List<?>) way).size() == 3 ? (List<?>) way : Collections.emptyList();
				if (ways.size() < MAX_WAYS)
				{
					ways.add(parts.isEmpty() ? Requirement.NONE : Requirement.of(texts(parts.get(0)), texts(parts.get(1)), texts(parts.get(2))));
				}
			}
		}
		return new Row(ways.toArray(new Requirement[0]), starts);
	}

	private static List<String> texts(Object raw)
	{
		List<String> texts = new ArrayList<>();
		for (Object text : raw instanceof List ? (List<?>) raw : Collections.emptyList())
		{
			if (text instanceof String && texts.size() < MAX_TEXTS)
			{
				texts.add((String) text);
			}
		}
		return texts;
	}

	/**
	 * The answer for a snapshot, as the data of a {@code judged} state: {@code quests} (byte[], the state of the quest each row starts,
	 * 0 for unknown or none) and {@code missing} (String[], what is certainly missing for each row as one line of words, null for
	 * nothing).
	 */
	public static Map<String, Object> judge(List<Row> rows, Judge.State now)
	{
		byte[] quests = new byte[rows.size()];
		String[] missing = new String[rows.size()];
		for (int i = 0; i < quests.length; i++)
		{
			Row row = rows.get(i);
			quests[i] = questState(row, now);
			missing[i] = shortfall(row, now);
		}
		Map<String, Object> data = new HashMap<>();
		data.put("quests", quests);
		data.put("missing", missing);
		return data;
	}

	/** The state of the quest a row starts: 0 unknown or none, 1 not started, 2 started, 3 finished. */
	static byte questState(Row row, Judge.State now)
	{
		return row.starts == null ? 0 : now.quests[row.starts.ordinal()];
	}

	/** What is certainly missing for a row: the words of the way with the fewest things missing, or null when it can be used. */
	@Nullable
	static String shortfall(Row row, Judge.State now)
	{
		List<String> best = null;
		for (Requirement way : row.ways)
		{
			List<String> missing = new ArrayList<>();
			if (missing(now, way, missing) == 0)
			{
				return null;
			}
			if (best == null || missing.size() < best.size())
			{
				best = missing;
			}
		}
		return best == null ? null : String.join(", ", best);
	}

	/** How many of a way's levels and quests are certainly missing, adding their words to {@code out}. */
	private static int missing(Judge.State now, Requirement way, List<String> out)
	{
		int count = 0;
		for (int i = 0; i < way.skillCount(); i++)
		{
			int have = now.levels[way.skill(i).ordinal()];
			if (have >= 0 && have < way.level(i))
			{
				count++;
				out.add(way.level(i) + " " + way.skill(i).getName());
			}
		}
		if (way.combatLevel() > 0 && now.combat >= 0 && now.combat < way.combatLevel())
		{
			count++;
			out.add(Requirement.combatText(way.combatLevel()));
		}
		if (way.totalLevel() > 0 && now.total >= 0 && now.total < way.totalLevel())
		{
			count++;
			out.add(Requirement.totalText(way.totalLevel()));
		}
		for (int i = 0; i < way.diaryCount(); i++)
		{
			// Only a tier known not to be done is missing: unknown counts as done.
			if (now.diaries[way.diary(i)] == DIARY_NOT_DONE)
			{
				count++;
				out.add(Diaries.text(way.diary(i)));
			}
		}
		if (way.questPoints() > 0 && now.questPoints >= 0 && now.questPoints < way.questPoints())
		{
			count++;
			out.add(Requirement.questPointsText(way.questPoints()));
		}
		for (int i = 0; i < way.questCount(); i++)
		{
			// A quest that is under way is not held against the requirement: the data cannot say how far is far enough, and for a
			// quest that only has to be started ("Swan Song (started)") it is met.
			if (now.quests[way.quest(i).ordinal()] == NOT_STARTED)
			{
				count++;
				out.add(way.questName(i));
			}
		}
		return count;
	}
}
