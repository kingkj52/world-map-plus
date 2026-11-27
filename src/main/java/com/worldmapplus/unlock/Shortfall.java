package com.worldmapplus.unlock;

import java.util.*;

/** What a player is certainly missing for one thing: the levels and the quests, as a card would word them. */
public final class Shortfall
{
	private final List<String> missing;

	Shortfall(List<String> missing)
	{
		this.missing = Collections.unmodifiableList(missing);
	}

	/** Each missing item on its own: {@code 67 Sailing}, {@code Monkey Madness I}. Never empty. */
	public List<String> items()
	{
		return missing;
	}

	/** The items joined for one line of a card. */
	public String text()
	{
		return String.join(", ", missing);
	}
}
