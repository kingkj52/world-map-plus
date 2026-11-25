package com.worldmapplus.unlock;

import java.util.*;
import javax.annotation.*;

/**
 * What the World Map Plus Helper judged of the player's progress for each {@link Need}, as it last pushed it ({@link #take}): for each
 * need number the state of the quest it starts and what the player is certainly missing for it. The Helper reads the game and pushes
 * again when something changed; this class keeps the last good answer, so nothing crosses between the plugins per frame or per icon.
 * Without the Helper, or when the Helper says nothing is known, every answer is "no", so nothing is hidden or marked wrongly. A need
 * made after the last answer is not in it and is not judged.
 * <p>
 * Anything of a wrong shape is ignored as a whole and the last good answer stays.
 */
public class Unlocks implements UnlockView
{
	private static final int MAX_NEEDS = 100_000;
	private static final int MAX_TEXT = 300;

	/** One answer, never changed after it is made: the quest state by need number (0 unknown to 3 finished) and the words missing. */
	private static final class Answer
	{
		private final byte[] quests;
		private final String[] missing;

		Answer(byte[] quests, String[] missing)
		{
			this.quests = quests;
			this.missing = missing;
		}
	}

	/** What other threads read. Replaced as a whole. */
	private volatile Answer answer;
	private volatile int version;

	/** Forgets everything: a new handshake, a goodbye, or a logout. */
	public void clear()
	{
		if (answer != null)
		{
			answer = null;
			version++;
		}
	}

	/**
	 * Takes the answer the Helper pushed (its {@code judged} state, whose sequence number Partners has checked): {@code cleared} true, or
	 * {@code quests} (byte[]) and {@code missing} (String[], null for nothing missing), one place for each need number. A message of
	 * any other shape is ignored whole.
	 */
	public void take(Map<String, Object> data)
	{
		if (Boolean.TRUE.equals(data.get("cleared")))
		{
			clear();
			return;
		}
		Object quests = data.get("quests");
		Object missing = data.get("missing");
		if (!(quests instanceof byte[]) || !(missing instanceof String[]) || ((byte[]) quests).length != ((String[]) missing).length
			|| ((byte[]) quests).length > MAX_NEEDS)
		{
			return;
		}
		String[] words = ((String[]) missing).clone();
		for (int i = 0; i < words.length; i++)
		{
			words[i] = words[i] == null || words[i].isEmpty() ? null : words[i].length() > MAX_TEXT ? words[i].substring(0, MAX_TEXT) : words[i];
		}
		answer = new Answer(((byte[]) quests).clone(), words);
		version++;
	}

	@Override
	public int version()
	{
		return version;
	}

	@Override
	public int questProgress(Need need)
	{
		Answer now = answer;
		return now == null || need.number() >= now.quests.length ? QUEST_UNKNOWN : now.quests[need.number()];
	}

	@Override
	@Nullable
	public String shortfall(Need need)
	{
		Answer now = answer;
		return now == null || need.number() >= now.missing.length ? null : now.missing[need.number()];
	}
}
