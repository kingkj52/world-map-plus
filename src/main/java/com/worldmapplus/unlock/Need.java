package com.worldmapplus.unlock;

import javax.annotation.*;
import net.runelite.api.*;

/**
 * What an icon asks of the player before it is any use, and the quest it starts, if it starts one.
 * <p>
 * An icon may stand for several ways of using it (a ship with ten destinations, a stop with many teleports). The player can use it as
 * soon as <i>any</i> one of {@link #alternatives()} is met, so the icon is only counted as out of reach when every one of them is. An
 * icon with no alternatives asks nothing the plugin can judge.
 * <p>
 * Made at build time from the data's words ({@code tools/requirement_checks.py}) and filled by Gson; never changed after.
 */
public final class Need
{
	/** One thing a way asks. Filled by Gson. */
	public static final class Check
	{
		/** s a skill level, c the combat level, t the total level, d a diary tier ({@link Diaries} number), p quest points, q a quest. */
		char k;
		Skill s;
		Quest q;
		/** The level, tier or points asked; for a quest 1 when having started it is enough. */
		int n;
		/** The words a card shows when it is missing. */
		String t;
	}

	private static final Check[][] NONE = new Check[0][];

	private Check[][] any;
	private String starts;
	private Quest quest;

	/** The ways of using it, each its checks, of which one must be met. Empty when the icon asks for nothing that can be judged. */
	public Check[][] alternatives()
	{
		return any == null ? NONE : any;
	}

	public boolean hasAlternatives()
	{
		return any != null && any.length > 0;
	}

	/** The name of the quest that starts here, or null. */
	@Nullable
	public String startsQuest()
	{
		return starts;
	}

	@Nullable
	public Quest startsQuestId()
	{
		return quest;
	}
}
