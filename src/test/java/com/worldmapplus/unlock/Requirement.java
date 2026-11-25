package com.worldmapplus.unlock;

// A copy of the World Map Plus Helper's class of the same name (its `progress` package), for the tests that need a pretend Helper.

import java.util.*;
import java.util.regex.*;
import javax.annotation.*;
import net.runelite.api.*;

/**
 * Everything one way of using something asks of the player: some skill levels, some finished quests, some finished achievement diary
 * tiers and a number of quest points.
 * <p>
 * Made from the strings the bundled data already carries ("67 Sailing", "Monkey Madness I", "Hard Ardougne Diary", "32 Quest points"). A string this class cannot make sense of
 * is left out, so a requirement it does not understand is never held against the player: only what is certain is kept. The names
 * of quests are matched to the game's {@link Quest} list once, here, and the data's own spelling is kept for the text a card shows.
 * <p>
 * Immutable.
 */
public final class Requirement
{
	/** Longest level that is taken as one. */
	private static final int MAX_LEVEL = 126;

	private static final Pattern LEVEL = Pattern.compile("^\\s*(\\d{1,3})\\s+([A-Za-z]+)\\s*$");

	/** A combat level: {@code 40 Combat} or {@code Combat level 40}. */
	private static final Pattern COMBAT = Pattern.compile("^\\s*(?:(\\d{1,3})\\s+Combat(?:\\s+level)?|Combat\\s+level\\s+(\\d{1,3}))\\s*$",
		Pattern.CASE_INSENSITIVE);

	/** A total level: {@code 1500 Total}, {@code 1500 Total level} or {@code Total level 1500}. */
	private static final Pattern TOTAL = Pattern.compile("^\\s*(?:(\\d{1,4})\\s+Total(?:\\s+level)?|Total\\s+level\\s+(\\d{1,4}))\\s*$",
		Pattern.CASE_INSENSITIVE);

	/** Every skill at 99, as the data writes what the max cape asks. */
	private static final String MAX_TOTAL = "max total";

	/** Quest points: {@code 32 Quest points}, {@code 32 QP} or {@code Quest points: 32}. */
	private static final Pattern QUEST_POINTS = Pattern.compile(
		"^\\s*(?:(\\d{1,3})\\s+(?:Quest\\s+points?|QP)|Quest\\s+points?:?\\s+(\\d{1,3}))\\s*$", Pattern.CASE_INSENSITIVE);

	/** The most quest points taken as a number of them: more than the game has ever had. */
	private static final int MAX_QUEST_POINTS = 500;

	/** What a quest name ends with when having started the quest is enough. */
	private static final String STARTED = " (started)";

	/** The highest combat level there is. */
	private static final int MAX_COMBAT = 126;

	/** The total level of a player with every skill at 99, which is what {@code Max Total} asks for. */
	public static final int MAX_TOTAL_LEVEL;

	private static final Map<String, Skill> SKILLS = new HashMap<>();
	private static final Map<String, Quest> QUESTS = new HashMap<>();

	static
	{
		int trained = 0;
		for (Skill skill : Skill.values())
		{
			if (isTrained(skill))
			{
				SKILLS.put(skill.getName().toLowerCase(Locale.ROOT), skill);
				trained++;
			}
		}
		MAX_TOTAL_LEVEL = trained * 99;
		for (Quest quest : Quest.values())
		{
			QUESTS.put(squash(quest.getName()), quest);
		}
	}

	/** No requirement at all. */
	public static final Requirement NONE = new Requirement(new Skill[0], new int[0], new Quest[0], new String[0], new boolean[0], 0, 0,
		new int[0], 0);

	private final Skill[] skills;
	private final int[] levels;
	private final Quest[] quests;
	private final String[] questNames;
	/** For each quest, true when having started it is enough. */
	private final boolean[] startedEnough;
	private final int combatLevel;
	private final int totalLevel;
	/** The achievement diary tiers that must be done, by {@link Diaries} number. */
	private final int[] diaries;
	private final int questPoints;

	private Requirement(Skill[] skills, int[] levels, Quest[] quests, String[] questNames, boolean[] startedEnough, int combatLevel,
		int totalLevel, int[] diaries, int questPoints)
	{
		this.diaries = diaries;
		this.questPoints = questPoints;
		this.skills = skills;
		this.levels = levels;
		this.quests = quests;
		this.questNames = questNames;
		this.startedEnough = startedEnough;
		this.combatLevel = combatLevel;
		this.totalLevel = totalLevel;
	}

	/** Lower case letters and digits only, so the spelling of an apostrophe or a dash never makes two names differ. */
	static String squash(String name)
	{
		StringBuilder out = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++)
		{
			char c = name.charAt(i);
			if (Character.isLetterOrDigit(c))
			{
				out.append(Character.toLowerCase(c));
			}
		}
		return out.toString();
	}

	/** True for a skill a player trains, which leaves out the total the API lists beside them. */
	public static boolean isTrained(Skill skill)
	{
		return !"Overall".equalsIgnoreCase(skill.getName());
	}

	/** The game's quest with this name, or null when the name is not one the game's list has. */
	@Nullable
	public static Quest questNamed(@Nullable String name)
	{
		return name == null ? null : QUESTS.get(squash(name));
	}

	/**
	 * A requirement from the data's strings.
	 *
	 * @param skills strings such as {@code 67 Sailing} or {@code 11 Agility, 19 Ranged}, a combat level ({@code 40 Combat}), a total
	 * level ({@code 1500 Total}) or {@code Max Total} (every skill at 99); anything else is left out
	 * @param questNames the names of quests that must be finished, or started when the name ends in {@code (started)}; a name the
	 * game's list does not have is left out
	 */
	public static Requirement of(@Nullable List<String> skills, @Nullable List<String> questNames)
	{
		return of(skills, questNames, null);
	}

	/**
	 * A requirement from the data's strings, with a third list of things asked in the wiki's own words, each one string: a level, a
	 * quest, an achievement diary tier ({@code Hard Ardougne Diary}) or quest points ({@code 32 Quest points}). The first two lists
	 * take diary tiers and quest points too. Anything not understood is left out.
	 */
	public static Requirement of(@Nullable List<String> skills, @Nullable List<String> questNames, @Nullable List<String> requires)
	{
		List<Skill> skillList = new ArrayList<>();
		List<Integer> levelList = new ArrayList<>();
		List<Integer> diaryList = new ArrayList<>();
		int combat = 0;
		int total = 0;
		int points = 0;
		// The levels and the other things a level list may hold, then the names: quests, diary tiers and quest points.
		List<String> levelTexts = new ArrayList<>();
		List<String> named = new ArrayList<>();
		if (skills != null)
		{
			levelTexts.addAll(skills);
		}
		if (questNames != null)
		{
			named.addAll(questNames);
		}
		if (requires != null)
		{
			for (String text : requires)
			{
				// One wiki wording is one thing: a level (or a diary or quest points), else a quest name.
				if (text != null && isLevelText(text))
				{
					levelTexts.add(text);
				}
				else
				{
					named.add(text);
				}
			}
		}
		for (String text : levelTexts)
		{
			if (text == null)
			{
				continue;
			}
			for (String part : text.split(","))
			{
				int diary = Diaries.parse(part);
				if (diary >= 0)
				{
					addDiary(diaryList, diary);
					continue;
				}
				int count = questPointsOf(part);
				if (count > 0)
				{
					points = Math.max(points, count);
					continue;
				}
				Matcher c = COMBAT.matcher(part);
				Matcher t = TOTAL.matcher(part);
				if (part.trim().equalsIgnoreCase(MAX_TOTAL))
				{
					total = Math.max(total, MAX_TOTAL_LEVEL);
					continue;
				}
				if (c.matches())
				{
					int level = Integer.parseInt(c.group(1) != null ? c.group(1) : c.group(2));
					if (level >= 3 && level <= MAX_COMBAT)
					{
						combat = Math.max(combat, level);
					}
					continue;
				}
				if (t.matches())
				{
					int level = Integer.parseInt(t.group(1) != null ? t.group(1) : t.group(2));
					if (level >= 1 && level <= MAX_TOTAL_LEVEL)
					{
						total = Math.max(total, level);
					}
					continue;
				}
				Matcher m = LEVEL.matcher(part);
				if (!m.matches())
				{
					continue;
				}
				Skill skill = SKILLS.get(m.group(2).toLowerCase(Locale.ROOT));
				int level = Integer.parseInt(m.group(1));
				if (skill != null && level >= 1 && level <= MAX_LEVEL)
				{
					add(skillList, levelList, skill, level);
				}
			}
		}
		List<Quest> questList = new ArrayList<>();
		List<String> nameList = new ArrayList<>();
		List<Boolean> startedList = new ArrayList<>();
		for (String raw : named)
		{
			if (raw == null)
			{
				continue;
			}
			String name = raw.trim();
			int diary = Diaries.parse(name);
			if (diary >= 0)
			{
				addDiary(diaryList, diary);
				continue;
			}
			int count = questPointsOf(name);
			if (count > 0)
			{
				points = Math.max(points, count);
				continue;
			}
			boolean started = name.toLowerCase(Locale.ROOT).endsWith(STARTED);
			Quest quest = questNamed(started ? name.substring(0, name.length() - STARTED.length()) : name);
			if (quest == null)
			{
				continue;
			}
			int at = questList.indexOf(quest);
			if (at < 0)
			{
				questList.add(quest);
				nameList.add(name);
				startedList.add(started);
			}
			else if (startedList.get(at) && !started)
			{
				// Finishing it asks more than starting it.
				nameList.set(at, name);
				startedList.set(at, false);
			}
		}
		if (skillList.isEmpty() && questList.isEmpty() && combat == 0 && total == 0 && diaryList.isEmpty() && points == 0)
		{
			return NONE;
		}
		int[] diaries = new int[diaryList.size()];
		for (int i = 0; i < diaries.length; i++)
		{
			diaries[i] = diaryList.get(i);
		}
		boolean[] startedEnough = new boolean[startedList.size()];
		for (int i = 0; i < startedEnough.length; i++)
		{
			startedEnough[i] = startedList.get(i);
		}
		int[] levels = new int[levelList.size()];
		for (int i = 0; i < levels.length; i++)
		{
			levels[i] = levelList.get(i);
		}
		return new Requirement(skillList.toArray(new Skill[0]), levels, questList.toArray(new Quest[0]),
			nameList.toArray(new String[0]), startedEnough, combat, total, diaries, points);
	}

	/** True for a wiki wording that is a level, a combat or total level, a diary tier or quest points, so not a quest's name. */
	private static boolean isLevelText(String text)
	{
		if (Diaries.parse(text) >= 0 || questPointsOf(text) > 0 || COMBAT.matcher(text).matches() || TOTAL.matcher(text).matches()
			|| text.trim().equalsIgnoreCase(MAX_TOTAL))
		{
			return true;
		}
		Matcher m = LEVEL.matcher(text);
		return m.matches() && SKILLS.containsKey(m.group(2).toLowerCase(Locale.ROOT));
	}

	/** The quest points a text asks for, or 0 when it asks for none. */
	private static int questPointsOf(String text)
	{
		Matcher m = QUEST_POINTS.matcher(text);
		if (!m.matches())
		{
			return 0;
		}
		int count = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
		return count >= 1 && count <= MAX_QUEST_POINTS ? count : 0;
	}

	private static void addDiary(List<Integer> diaries, int diary)
	{
		if (!diaries.contains(diary))
		{
			diaries.add(diary);
		}
	}

	private static void add(List<Skill> skills, List<Integer> levels, Skill skill, int level)
	{
		int at = skills.indexOf(skill);
		if (at < 0)
		{
			skills.add(skill);
			levels.add(level);
		}
		else if (levels.get(at) < level)
		{
			levels.set(at, level);
		}
	}

	/** True when it asks for nothing the plugin can judge. */
	public boolean isEmpty()
	{
		return skills.length == 0 && quests.length == 0 && combatLevel == 0 && totalLevel == 0 && diaries.length == 0 && questPoints == 0;
	}

	/** How many achievement diary tiers it asks for. */
	public int diaryCount()
	{
		return diaries.length;
	}

	/** An achievement diary tier it asks for, by {@link Diaries} number. */
	public int diary(int i)
	{
		return diaries[i];
	}

	/** The quest points asked for, or 0 for none. */
	public int questPoints()
	{
		return questPoints;
	}

	/** The words for quest points, as a card says them. */
	static String questPointsText(int points)
	{
		return points + " Quest points";
	}

	/** The combat level asked for, or 0 for none. */
	public int combatLevel()
	{
		return combatLevel;
	}

	/** The total level asked for, or 0 for none. {@link #MAX_TOTAL_LEVEL} for every skill at 99. */
	public int totalLevel()
	{
		return totalLevel;
	}

	/** True when having started the quest is enough, as for {@code Swan Song (started)}. */
	public boolean startedIsEnough(int i)
	{
		return startedEnough[i];
	}

	/** The words for a combat level, as a card says them. */
	static String combatText(int level)
	{
		return "Combat level " + level;
	}

	/** The words for a total level, as a card says them. */
	static String totalText(int level)
	{
		return level >= MAX_TOTAL_LEVEL ? "Every skill at 99" : "Total level " + level;
	}

	public int skillCount()
	{
		return skills.length;
	}

	public Skill skill(int i)
	{
		return skills[i];
	}

	public int level(int i)
	{
		return levels[i];
	}

	public int questCount()
	{
		return quests.length;
	}

	public Quest quest(int i)
	{
		return quests[i];
	}

	/** The quest's name as the data spells it, for a card. */
	public String questName(int i)
	{
		return questNames[i];
	}

	/** What the requirement says, as a card would: {@code 67 Sailing, Monkey Madness I}. */
	public String describe()
	{
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < skills.length; i++)
		{
			parts.add(levels[i] + " " + skills[i].getName());
		}
		if (combatLevel > 0)
		{
			parts.add(combatText(combatLevel));
		}
		if (totalLevel > 0)
		{
			parts.add(totalText(totalLevel));
		}
		Collections.addAll(parts, questNames);
		for (int diary : diaries)
		{
			parts.add(Diaries.text(diary));
		}
		if (questPoints > 0)
		{
			parts.add(questPointsText(questPoints));
		}
		return String.join(", ", parts);
	}
}
