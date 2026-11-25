package com.worldmapplus.unlock;

import java.util.*;
import javax.annotation.*;

/**
 * What an icon asks of the player before it is any use, and the quest it starts, if it starts one, in the data's own words. This class
 * judges nothing: it is a number for a set of requirements. The numbers and what they stand for are kept in one table, sent to the
 * World Map Plus Helper, which reads the player's progress and answers for each number what is missing ({@link Unlocks}).
 * <p>
 * An icon may stand for several ways of using it (a ship with ten destinations, a stop with many teleports). The player can use it as
 * soon as <i>any</i> one of the ways is met, so the icon is only counted as out of reach when every one of them is. Each way is three
 * lists of texts: levels, quests and the wiki's own wording ("67 Sailing", "Monkey Madness I", "Hard Ardougne Diary").
 * <p>
 * Immutable. Two needs with the same words are the same number.
 */
public final class Need
{
	private static final Map<List<Object>, Integer> NUMBERS = new HashMap<>();
	private static final List<List<Object>> TABLE = new ArrayList<>();

	/** The ways of using it and the name of the quest it starts: the table's row. */
	private final List<Object> row;
	private final int number;

	private Need(List<Object> row)
	{
		this.row = row;
		synchronized (TABLE)
		{
			Integer known = NUMBERS.get(row);
			if (known == null)
			{
				known = TABLE.size();
				NUMBERS.put(row, known);
				TABLE.add(row);
			}
			number = known;
		}
	}

	/** How many needs there are, which changes when one is made that is new. */
	public static int count()
	{
		synchronized (TABLE)
		{
			return TABLE.size();
		}
	}

	/** Every need as a row of the table, in number order: a List of ways (each a List of three Lists of Strings) and the quest's name. */
	public static List<List<Object>> table()
	{
		synchronized (TABLE)
		{
			return new ArrayList<>(TABLE);
		}
	}

	/**
	 * The need of something that can be used in any of several ways, each given as by {@link Needs#way}. Returns null when there is
	 * nothing to judge: when no way is given, or when one of them asks for nothing, since that one is open to everybody.
	 */
	@Nullable
	public static Need anyOf(List<List<List<String>>> ways)
	{
		if (ways.isEmpty() || ways.contains(Needs.NO_WAY))
		{
			return null;
		}
		return new Need(Arrays.asList(Collections.unmodifiableList(new ArrayList<>(ways)), ""));
	}

	/** The need of the icon where a quest starts. A blank name gives null. */
	@Nullable
	public static Need startOf(@Nullable String questName)
	{
		return questName == null || questName.trim().isEmpty() ? null : new Need(Arrays.asList(Collections.emptyList(), questName.trim()));
	}

	/** This need with the quest it starts added. */
	public Need withStart(@Nullable Need start)
	{
		return start == null ? this : new Need(Arrays.asList(row.get(0), start.row.get(1)));
	}

	/** The number the Helper's answers are given under. */
	public int number()
	{
		return number;
	}

	public boolean hasAlternatives()
	{
		return !((List<?>) row.get(0)).isEmpty();
	}

	/** The name of the quest that starts here, or null. */
	@Nullable
	public String startsQuest()
	{
		String name = (String) row.get(1);
		return name.isEmpty() ? null : name;
	}
}
