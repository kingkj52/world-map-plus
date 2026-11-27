package com.worldmapplus.unlock;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.data.MapElements;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.search.BundledSearch;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 70: what each card, teleport row, icon and quest start of the bundled data asks of the player, as checks, and what the map
 * makes of them for six pretend players. Written before the requirement words moved from {@code unlock/Requirement} into the build
 * tools ({@code golden/requirements.txt}); this checks the checks read from {@code things.json} answer the same. Each check is written
 * as kind, what it names, its number and the words a card shows, in the order a card lists what is missing. What is read now is
 * written to {@code build/requirements-actual.txt}; after a data update that is meant to change requirements, look at the difference
 * and copy that file over the golden one.
 */
public class RequirementsGoldenTest
{
	/** A pretend player: what each read of the game answers. */
	private static final class Player implements Unlocks.Source
	{
		final String name;
		final int kind;

		Player(String name, int kind)
		{
			this.name = name;
			this.kind = kind;
		}

		@Override
		public boolean loggedIn()
		{
			return true;
		}

		@Override
		public int realLevel(Skill skill)
		{
			int o = skill.ordinal();
			switch (kind)
			{
				case 0:
					return 1;
				case 1:
					return 99;
				case 2:
					return 10 + o * 7 % 90;
				case 3:
					return o % 2 == 0 ? -1 : 50;
				default:
					return 70;
			}
		}

		@Override
		public QuestState questState(Quest quest)
		{
			int o = quest.ordinal();
			switch (kind)
			{
				case 0:
					return QuestState.NOT_STARTED;
				case 1:
					return QuestState.FINISHED;
				case 2:
					return o % 3 == 0 ? QuestState.NOT_STARTED : o % 3 == 1 ? QuestState.IN_PROGRESS : QuestState.FINISHED;
				case 3:
					return o % 4 == 0 ? null : QuestState.NOT_STARTED;
				default:
					return o % 2 == 0 ? QuestState.FINISHED : QuestState.NOT_STARTED;
			}
		}

		@Override
		public int combatLevel()
		{
			return new int[] {3, 126, 90, -1, 100}[kind];
		}

		@Override
		public int totalLevel()
		{
			return new int[] {24, 2376, 1500, -1, 1800}[kind];
		}

		@Override
		public int diaryValue(int varbit)
		{
			int tier = -1;
			for (int t = 0; t < Diaries.COUNT; t++)
			{
				if (Diaries.varbit(t) == varbit)
				{
					tier = t;
				}
			}
			switch (kind)
			{
				case 0:
					return 0;
				case 1:
					return 1;
				case 2:
					return tier % 2 == 0 ? 1 : 0;
				case 3:
					return tier % 3 == 0 ? -1 : 0;
				default:
					return tier % 4 < 2 ? 1 : 0;
			}
		}

		@Override
		public int questPoints()
		{
			return new int[] {0, 300, 150, -1, 200}[kind];
		}
	}

	private static final String[] PLAYERS = {"new", "maxed", "mixed", "unknown", "middle"};

	/** The names of the pretend players, in the order {@link #player} takes them; "logged out" is the last. */
	public static final String[] NAMES = {"logged out", "new", "maxed", "mixed", "unknown", "middle"};

	/** One of the pretend players with every look done (by its place in {@link #NAMES}), for the tests of other packages. */
	public static Unlocks player(int index)
	{
		if (index == 0)
		{
			return new Unlocks(new Player("logged out", 0));
		}
		Unlocks unlocks = new Unlocks(new Player(NAMES[index], index - 1));
		unlocks.want(true, true);
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		for (int t = 0; t < Unlocks.SETTLE_TICKS + Quest.values().length / Unlocks.QUESTS_PER_TICK + 5; t++)
		{
			unlocks.onGameTick();
		}
		return unlocks;
	}

	/** The checks of one way, in the order a card lists what is missing: kind, what it names, number, words. */
	static List<String> checks(Need.Check[] way)
	{
		List<String> out = new ArrayList<>();
		for (Need.Check c : way)
		{
			out.add(c.k + " " + (c.s != null ? c.s.name() : c.q != null ? c.q.name() : "-") + " " + c.n + " " + c.t);
		}
		return out;
	}

	/** A need as one line: its ways (each its checks joined by "; "), joined by " | ", and the quest it starts. */
	static String text(Need need)
	{
		if (need == null)
		{
			return "-";
		}
		List<String> ways = new ArrayList<>();
		for (Need.Check[] way : need.alternatives())
		{
			ways.add("[" + String.join("; ", checks(way)) + "]");
		}
		Quest quest = need.startsQuestId();
		return String.join(" | ", ways) + (quest == null ? "" : " starts " + quest.name() + " " + need.startsQuest());
	}

	/** Whether the quest of a search entry of a quest start is finished, by the game's quest it names. */
	static String questOf(SearchEntry entry)
	{
		return entry.getQuest() == null ? "-" : entry.getQuest().name();
	}

	@Test
	public void everyRequirementReadsAndAnswersAsBeforeTheMove() throws IOException
	{
		MapElements elements = MapElements.load(new Gson());
		StringBuilder out = new StringBuilder();
		TreeMap<String, Need> distinct = new TreeMap<>();
		List<Hotspot> made = elements.loaded(Collections.emptyList());
		List<Hotspot> icons = elements.getIcons();
		int count = made.size() + icons.size();
		out.append("# cards: as made, each teleport row, and as shown (with the wiki's requirement where it applies)\n");
		for (int i = 0; i < count; i++)
		{
			Hotspot shown = i < made.size() ? elements.replacementOf(made.get(i)) : icons.get(i - made.size());
			Need asMade = elements.needOf(i, null, null);
			add(out, distinct, "card " + i + " made", asMade);
			if (shown.getNeed() != asMade)
			{
				add(out, distinct, "card " + i + " shown", shown.getNeed());
			}
			for (HoverCard.Row row : shown.getCard().getRows())
			{
				Need way = elements.needOf(i, row.getLabel(), null);
				if (way != null)
				{
					add(out, distinct, "card " + i + " row " + row.getLabel(), way);
				}
			}
		}
		out.append("# the game's icons\n");
		int n = 0;
		for (GameIconSet.Spec spec : elements.getGameIcons())
		{
			add(out, distinct, "icon " + n++, spec.getNeed());
		}
		out.append("# the quest of each quest start in the search\n");
		for (SearchEntry entry : BundledSearch.of(SearchKind.QUEST))
		{
			out.append("quest ").append(entry.getName()).append(" = ").append(questOf(entry)).append('\n');
		}
		out.append("# every need once, and for each pretend player: certainly missing, what a card says is missing, quest progress\n");
		List<Unlocks> players = new ArrayList<>();
		for (int p = 0; p < PLAYERS.length; p++)
		{
			Unlocks unlocks = new Unlocks(new Player(PLAYERS[p], p));
			unlocks.want(true, true);
			unlocks.onGameStateChanged(GameState.LOGGED_IN);
			for (int t = 0; t < Unlocks.SETTLE_TICKS + Quest.values().length / Unlocks.QUESTS_PER_TICK + 5; t++)
			{
				unlocks.onGameTick();
			}
			players.add(unlocks);
		}
		Unlocks nobody = new Unlocks(new Player("logged out", 0));
		for (String key : distinct.keySet())
		{
			Need need = distinct.get(key);
			out.append(key).append('\n');
			out.append("  logged out: ").append(answer(nobody, need)).append('\n');
			for (int p = 0; p < PLAYERS.length; p++)
			{
				out.append("  ").append(PLAYERS[p]).append(": ").append(answer(players.get(p), need)).append('\n');
			}
		}
		String text = out.toString();
		Path actual = Paths.get("build", "requirements-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "requirements.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		if (!expected.equals(text))
		{
			String[] a = expected.split("\n", -1);
			String[] b = text.split("\n", -1);
			for (int i = 0; i < Math.min(a.length, b.length); i++)
			{
				assertEquals("line " + (i + 1), a[i], b[i]);
			}
			assertEquals("lines", a.length, b.length);
		}
	}

	private static void add(StringBuilder out, TreeMap<String, Need> distinct, String where, Need need)
	{
		String text = text(need);
		out.append(where).append(": ").append(text).append('\n');
		if (need != null)
		{
			distinct.putIfAbsent(text, need);
		}
	}

	private static String answer(Unlocks unlocks, Need need)
	{
		Shortfall lack = unlocks.shortfall(need);
		return (unlocks.lacks(need) ? "lacks" : "has") + ", missing " + (lack == null ? "-" : String.join("; ", lack.items()))
			+ ", quest " + unlocks.questProgress(need) + (unlocks.finished(need.startsQuestId()) ? " finished" : "");
	}
}
