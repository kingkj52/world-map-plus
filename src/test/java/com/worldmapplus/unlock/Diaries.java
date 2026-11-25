package com.worldmapplus.unlock;

// A copy of the World Map Plus Helper's class of the same name (its `progress` package), for the tests that need a pretend Helper.

import java.util.*;

/**
 * The achievement diary tiers: twelve regions with four tiers each, numbered {@code region * 4 + tier}, and the game variable that
 * says whether each tier is done.
 * <p>
 * One canonical spelling is used on cards, the wiki's own: {@code Hard Ardougne Diary}. The spellings the wiki and other data use
 * are all read ({@code Ardougne Hard Diary}, {@code Ardougne Hard}, {@code Ardougne Diary (Hard)}, {@code Hard Ardougne Achievement
 * Diary}, {@code Kourend & Kebos Elite}, {@code Western Provinces Medium} and the like); anything else is left out, so a text this
 * class does not understand is never held against the player.
 */
public final class Diaries
{
	/** How many tiers there are in all. */
	public static final int COUNT = 48;

	/** The tiers of one region, in order. */
	public static final String[] TIERS = {"Easy", "Medium", "Hard", "Elite"};

	/** The regions, as the wiki names them on its pages and in diary names. */
	public static final String[] REGIONS = {"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend & Kebos", "Lumbridge & Draynor", "Morytania", "Varrock", "Western Provinces", "Wilderness"};

	/**
	 * Each region's names, as squashed words: the first is the full name, the others the short forms the wiki and other data use.
	 * The full names of two regions have two words, written with "&" or "and", which squashing leaves out.
	 */
	private static final String[][] ALIASES = {
		{"ardougne"},
		{"desert", "kharidiandesert"},
		{"falador"},
		{"fremennik", "fremennikprovince"},
		{"kandarin"},
		{"karamja"},
		{"kourendkebos", "kourend", "kebos"},
		{"lumbridgedraynor", "lumbridge"},
		{"morytania"},
		{"varrock"},
		{"westernprovinces", "western"},
		{"wilderness"},
	};

	/** Words that may stand around a diary's name and say nothing more. */
	private static final String[] FILLER = {"achievement", "diaries", "diary", "tasks", "task", "the", "and", "completed", "complete",
		"completion", "of"};

	private Diaries()
	{
	}

	/** The words a card uses for a tier: {@code Hard Ardougne Diary}. */
	public static String text(int tier)
	{
		return TIERS[tier % 4] + " " + REGIONS[tier / 4] + " Diary";
	}

	/**
	 * The tier a text names, or -1 when it names none. Exactly one tier word (Easy, Medium, Med, Hard or Elite) and one region must
	 * be there, with nothing else but the words that usually stand around a diary's name.
	 */
	public static int parse(String text)
	{
		if (text == null)
		{
			return -1;
		}
		String[] words = text.toLowerCase(Locale.ROOT).replace('&', ' ').replaceAll("[^a-z ]", " ").trim().split("\\s+");
		int tier = -1;
		StringBuilder region = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty() || isFiller(word))
			{
				continue;
			}
			int found = tierOf(word);
			if (found >= 0)
			{
				if (tier >= 0)
				{
					return -1;
				}
				tier = found;
				continue;
			}
			region.append(word);
		}
		if (tier < 0 || region.length() == 0)
		{
			return -1;
		}
		String name = region.toString();
		for (int r = 0; r < ALIASES.length; r++)
		{
			for (String alias : ALIASES[r])
			{
				if (alias.equals(name))
				{
					return r * 4 + tier;
				}
			}
		}
		return -1;
	}

	private static boolean isFiller(String word)
	{
		for (String filler : FILLER)
		{
			if (filler.equals(word))
			{
				return true;
			}
		}
		return false;
	}

	private static int tierOf(String word)
	{
		switch (word)
		{
			case "easy":
				return 0;
			case "medium":
			case "med":
				return 1;
			case "hard":
				return 2;
			case "elite":
				return 3;
			default:
				return -1;
		}
	}
}
