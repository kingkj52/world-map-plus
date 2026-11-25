package com.worldmapplus.unlock;

import javax.annotation.*;

/**
 * What the map may ask about the player's progress, as the Helper judged it for each {@link Need}. Every answer is safe to ask on any
 * thread and says "no" when it cannot tell, so a caller that hides or marks things on a "yes" can never hide or mark them wrongly.
 */
public interface UnlockView
{
	/** Changes whenever an answer below may have changed, so a caller can keep what it worked out until then. */
	int version();

	/** The state of a quest cannot be told. */
	int QUEST_UNKNOWN = 0;
	/** The quest has not been started. */
	int QUEST_NOT_STARTED = 1;
	/** The quest has been started and is not finished. */
	int QUEST_STARTED = 2;
	/** The quest is finished. */
	int QUEST_FINISHED = 3;

	/** How far the player is with the quest this need starts: one of the {@code QUEST_} numbers, unknown when it cannot be told. */
	default int questProgress(Need need)
	{
		return QUEST_UNKNOWN;
	}

	/** True only when the quest this need starts is known to be finished. */
	default boolean finished(Need need)
	{
		return questProgress(need) == QUEST_FINISHED;
	}

	/** True when something is certainly missing for a need. Allocates nothing, so it may be asked for every icon of a view. */
	default boolean lacks(Need need)
	{
		return shortfall(need) != null;
	}

	/** What is certainly missing for a need, as a card words it ({@code 67 Sailing, Monkey Madness I}), or null when it can be used or when that cannot be told. */
	@Nullable
	String shortfall(Need need);
}
