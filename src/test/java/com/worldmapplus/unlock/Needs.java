package com.worldmapplus.unlock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Makes {@link Need}s for tests from a few of the words the data uses ("90 Agility", "40 Combat", "Max Total", a quest's name), as
 * checks in the form {@code things.json} holds them. Since round 70 the plugin reads no words: {@code tools/requirement_checks.py} turns
 * them into checks at build time and {@code tools/test_requirement_checks.py} tests every wording. Anything else is left out here.
 */
public final class Needs
{
	private static final Pattern LEVEL = Pattern.compile("(\\d+) (\\w+)");

	private Needs()
	{
	}

	/** A need from the {@code skills} and {@code quests} words of one way of using something, or null when it asks nothing. */
	@Nullable
	public static Need ofWay(@Nullable List<String> skills, @Nullable List<String> quests)
	{
		return new Ways().add(skills, quests).build();
	}

	/** The need of the icon where a quest starts. */
	public static Need startOf(Quest quest)
	{
		JsonObject need = new JsonObject();
		need.addProperty("starts", quest.getName());
		need.addProperty("quest", quest.name());
		return new Gson().fromJson(need, Need.class);
	}

	/**
	 * What a way asks, as {@code Requirement.describe} said it until round 70: the words of its checks joined by ", " with the levels
	 * first, then the combat and total levels, the quests, the diary tiers and the quest points.
	 */
	public static String describe(Need.Check[] way)
	{
		List<String> parts = new ArrayList<>();
		for (char kind : "sctqdp".toCharArray())
		{
			for (Need.Check c : way)
			{
				if (c.k == kind)
				{
					parts.add(c.t);
				}
			}
		}
		return String.join(", ", parts);
	}

	/** The kind of a check: s, c, t, d, p or q. */
	public static char kind(Need.Check check)
	{
		return check.k;
	}

	/** A need of one way with these checks, each as {@code things.json} writes one. */
	public static Need of(String... checks)
	{
		return new Gson().fromJson("{\"any\": [[" + String.join(", ", checks) + "]]}", Need.class);
	}

	/** A need from several ways of using one icon, each given by its {@code skills} and {@code quests} words. */
	public static final class Ways
	{
		private final List<JsonArray> ways = new ArrayList<>();

		/** Adds one way. */
		public Ways add(@Nullable List<String> skills, @Nullable List<String> quests)
		{
			JsonArray checks = new JsonArray();
			for (String text : skills == null ? new ArrayList<String>() : skills)
			{
				for (String part : text.split(","))
				{
					check(checks, part.trim());
				}
			}
			for (String name : quests == null ? new ArrayList<String>() : quests)
			{
				for (Quest quest : Quest.values())
				{
					if (quest.getName().equalsIgnoreCase(name.replace(" (started)", "")))
					{
						checks.add(check("q", null, quest.name(), name.endsWith(" (started)") ? 1 : 0, name));
						break;
					}
				}
			}
			ways.add(checks);
			return this;
		}

		/** The need: null when there is no way, or when any way asks nothing. */
		@Nullable
		public Need build()
		{
			JsonArray any = new JsonArray();
			for (JsonArray way : ways)
			{
				if (way.size() == 0)
				{
					return null;
				}
				any.add(way);
			}
			if (any.size() == 0)
			{
				return null;
			}
			JsonObject need = new JsonObject();
			need.add("any", any);
			return new Gson().fromJson(need, Need.class);
		}
	}

	private static void check(JsonArray checks, String part)
	{
		if (part.equalsIgnoreCase("Max Total"))
		{
			int trained = 0;
			for (Skill skill : Skill.values())
			{
				trained += skill.getName().equalsIgnoreCase("Overall") ? 0 : 1;
			}
			checks.add(check("t", null, null, trained * 99, "Every skill at 99"));
			return;
		}
		Matcher m = LEVEL.matcher(part);
		if (!m.matches())
		{
			return;
		}
		int level = Integer.parseInt(m.group(1));
		if (m.group(2).equalsIgnoreCase("Combat"))
		{
			checks.add(check("c", null, null, level, "Combat level " + level));
			return;
		}
		if (m.group(2).equalsIgnoreCase("Total"))
		{
			checks.add(check("t", null, null, level, "Total level " + level));
			return;
		}
		for (Skill skill : Skill.values())
		{
			if (skill.getName().toLowerCase(Locale.ROOT).equals(m.group(2).toLowerCase(Locale.ROOT)))
			{
				checks.add(check("s", skill.name(), null, level, level + " " + skill.getName()));
			}
		}
	}

	private static JsonObject check(String kind, @Nullable String skill, @Nullable String quest, int n, String text)
	{
		JsonObject c = new JsonObject();
		c.addProperty("k", kind);
		if (skill != null)
		{
			c.addProperty("s", skill);
		}
		if (quest != null)
		{
			c.addProperty("q", quest);
		}
		c.addProperty("n", n);
		c.addProperty("t", text);
		return c;
	}
}
