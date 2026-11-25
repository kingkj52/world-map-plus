package com.worldmapplus.unlock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * What the cache of the Helper's answer claims, and how little: a need that was not answered counts as met, so nothing is ever marked or
 * hidden wrongly, and an answer of a wrong shape changes nothing. The judging itself is the Helper's and is tested there.
 */
public class UnlocksTest
{
	private static Need level(String text)
	{
		return Needs.ofWay(Collections.singletonList(text), null);
	}

	/** An answer for need numbers 0 up to {@code last}: the quest states and the words missing, with a gap for any number not given. */
	private static Map<String, Object> answer(int last, Need lacking, String words, Need quest, int state)
	{
		byte[] quests = new byte[last + 1];
		String[] missing = new String[last + 1];
		if (lacking != null)
		{
			missing[lacking.number()] = words;
		}
		if (quest != null)
		{
			quests[quest.number()] = (byte) state;
		}
		Map<String, Object> data = new HashMap<>();
		data.put("quests", quests);
		data.put("missing", missing);
		return data;
	}

	private static int last(Need... needs)
	{
		return Arrays.stream(needs).mapToInt(Need::number).max().getAsInt();
	}

	@Test
	public void whatWasNotAnsweredCountsAsMet()
	{
		Unlocks cache = new Unlocks();
		Need need = level("90 Agility");
		assertFalse(cache.lacks(need));
		assertNull(cache.shortfall(need));
		assertEquals(Unlocks.QUEST_UNKNOWN, cache.questProgress(Need.startOf("Cook's Assistant")));
		cache.take(answer(need.number(), need, "90 Agility", null, 0));
		Need later = level("91 Agility");
		assertFalse("a need made after the answer is not in it", cache.lacks(later));
		assertNull(cache.shortfall(later));
	}

	@Test
	public void theWordsAreKeptAndTheQuestStateIsLookedUpByNumber()
	{
		Unlocks cache = new Unlocks();
		Need agility = level("50 Agility");
		Need start = Need.startOf("Cook's Assistant");
		Need none = level("51 Agility");
		Map<String, Object> data = answer(last(agility, start, none), agility, "50 Agility", start, 3);
		int before = cache.version();
		cache.take(data);
		assertTrue(cache.version() != before);
		assertTrue(cache.lacks(agility));
		assertEquals("50 Agility", cache.shortfall(agility));
		assertFalse(cache.lacks(none));
		assertEquals(Unlocks.QUEST_FINISHED, cache.questProgress(start));
		assertTrue(cache.finished(start));
		assertFalse(cache.finished(agility));
	}

	@Test
	public void sameWordsAreTheSameNumberAndCardsCannotGrowTheAnswer()
	{
		assertEquals(level("77 Agility").number(), level("77 Agility").number());
		assertTrue(level("77 Agility").number() != level("78 Agility").number());
		Unlocks cache = new Unlocks();
		Need need = level("50 Agility");
		StringBuilder long300 = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			long300.append('x');
		}
		cache.take(answer(need.number(), need, long300.toString(), null, 0));
		assertEquals("the words are cut", 300, cache.shortfall(need).length());
	}

	@Test
	public void anAnswerOfTheWrongShapeIsIgnoredWholeAndTheLastGoodOneStays()
	{
		Unlocks cache = new Unlocks();
		Need need = level("20 Agility");
		cache.take(answer(need.number(), need, "20 Agility", null, 0));
		int version = cache.version();
		Map<String, Object> bad = answer(need.number(), need, "x", null, 0);
		bad.put("missing", new String[need.number() + 2]);
		cache.take(bad);
		bad = answer(need.number(), need, "x", null, 0);
		bad.put("quests", "nothing");
		cache.take(bad);
		bad = answer(need.number(), need, "x", null, 0);
		bad.remove("missing");
		cache.take(bad);
		bad = answer(need.number(), need, "x", null, 0);
		bad.put("missing", new Object[need.number() + 1]);
		cache.take(bad);
		cache.take(new HashMap<>());
		assertEquals(version, cache.version());
		assertEquals("the last good answer stays", "20 Agility", cache.shortfall(need));
	}

	@Test
	public void aClearedAnswerForgetsEverything()
	{
		Unlocks cache = new Unlocks();
		Need need = level("20 Agility");
		cache.take(answer(need.number(), need, "20 Agility", null, 0));
		assertTrue(cache.lacks(need));
		int version = cache.version();
		Map<String, Object> cleared = new HashMap<>();
		cleared.put("cleared", Boolean.TRUE);
		cache.take(cleared);
		assertFalse(cache.lacks(need));
		assertTrue(cache.version() != version);
		int again = cache.version();
		cache.clear();
		assertEquals("clearing nothing changes nothing", again, cache.version());
	}
}
