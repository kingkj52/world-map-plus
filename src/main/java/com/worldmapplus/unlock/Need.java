package com.worldmapplus.unlock;

import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.Quest;

/**
 * What an icon asks of the player before it is any use, and the quest it starts, if it starts one.
 * <p>
 * An icon may stand for several ways of using it (a ship with ten destinations, a stop with many teleports). The player can use it as
 * soon as <i>any</i> one of {@link #alternatives()} is met, so the icon is only counted as out of reach when every one of them is. An
 * icon with no alternatives asks nothing the plugin can judge.
 * <p>
 * Immutable.
 */
public final class Need
{
	private static final Requirement[] NO_ALTERNATIVES = new Requirement[0];

	private final Requirement[] alternatives;
	private final String startsQuest;
	private final Quest startsQuestId;

	private Need(Requirement[] alternatives, @Nullable String startsQuest, @Nullable Quest startsQuestId)
	{
		this.alternatives = alternatives;
		this.startsQuest = startsQuest;
		this.startsQuestId = startsQuestId;
	}

	/**
	 * The need of something that can be used in any of several ways. Returns null when there is nothing to judge: when no way is given,
	 * or when one of them asks for nothing, since that one is open to everybody.
	 */
	@Nullable
	public static Need anyOf(List<Requirement> ways)
	{
		if (ways.isEmpty())
		{
			return null;
		}
		for (Requirement way : ways)
		{
			if (way.isEmpty())
			{
				return null;
			}
		}
		return new Need(ways.toArray(new Requirement[0]), null, null);
	}

	/** The need of the icon where a quest starts. A name the game's list does not have gives null: such an icon is never hidden. */
	@Nullable
	public static Need startOf(@Nullable String questName)
	{
		Quest quest = Requirement.questNamed(questName);
		return quest == null ? null : new Need(NO_ALTERNATIVES, questName.trim(), quest);
	}

	/** This need with the quest it starts added. */
	public Need withStart(@Nullable Need start)
	{
		return start == null ? this : new Need(alternatives, start.startsQuest, start.startsQuestId);
	}

	/** The ways of using it, of which one must be met. Empty when the icon asks for nothing that can be judged. */
	public Requirement[] alternatives()
	{
		return alternatives;
	}

	public boolean hasAlternatives()
	{
		return alternatives.length > 0;
	}

	/** The name of the quest that starts here, or null. */
	@Nullable
	public String startsQuest()
	{
		return startsQuest;
	}

	@Nullable
	public Quest startsQuestId()
	{
		return startsQuestId;
	}
}
