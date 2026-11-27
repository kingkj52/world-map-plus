package com.worldmapplus.drops;

import com.google.gson.*;
import com.worldmapplus.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * Every bundled drop tab: what each monster drops (and what an NPC gives when pickpocketed), and the other way round, what drops an
 * item. Made at build time by {@code tools/build_drops.py} into {@code drops.json} (its docstring is the format), with every word a
 * row shows already worked out, and filled by Gson. Immutable once read, so any thread may read it. A row is an index into the row
 * lists, a word an index into the words.
 */
public final class DropTables
{
	/** Nothing at all, for a file that could not be read. */
	public static final DropTables EMPTY = new DropTables();

	private String[] words = {};
	private int[] label = {};
	private int[] rarity = {};
	private int[] text = {};
	private int[] base = {};
	private int[] id = {};
	private double[] order = {};
	private double[] perKill = {};
	private Map<String, Tab> drops = Collections.emptyMap();
	private Map<String, Tab> droppedBy = Collections.emptyMap();

	/** A tab: its rows and, for a Drops tab, the summary's drop part and whether the coins a kill of every row add up. */
	@Getter
	public static final class Tab
	{
		private String summary = "";
		private boolean total;
		private int[] rows = {};
	}

	private DropTables()
	{
	}

	/** Reads {@code drops.json}. Never throws; a missing or damaged file gives the empty tables. */
	public static DropTables load(Gson gson)
	{
		return checked(MapAssets.json(gson, "drops.json", DropTables.class));
	}

	/** The tables when every list has its length and every index is in range, else the empty tables: a damaged file costs the tabs only. */
	static DropTables checked(@Nullable DropTables t)
	{
		try
		{
			int rows = t.label.length;
			boolean ok = t.rarity.length == rows && t.text.length == rows && t.base.length == rows && t.id.length == rows
				&& t.order.length == rows && t.perKill.length == rows && in(t.label, 0, t.words.length) && in(t.rarity, 0, t.words.length)
				&& in(t.text, 0, t.words.length) && in(t.base, -1, t.words.length) && !Arrays.asList(t.words).contains(null);
			for (Tab tab : t.drops.values())
			{
				ok &= tab.summary != null && in(tab.rows, 0, rows);
			}
			for (Tab tab : t.droppedBy.values())
			{
				ok &= in(tab.rows, 0, rows);
				for (int r : tab.rows)
				{
					// A Dropped by row names its monster's page.
					ok &= t.base[r] >= 0;
				}
			}
			return ok ? t : EMPTY;
		}
		catch (RuntimeException e)
		{
			// A list the file does not have, or no file at all.
			return EMPTY;
		}
	}

	private static boolean in(int[] values, int low, int bound)
	{
		for (int v : values)
		{
			if (v < low || v >= bound)
			{
				return false;
			}
		}
		return true;
	}

	private static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}

	public boolean isEmpty()
	{
		return label.length == 0;
	}

	/** How many monsters and NPCs have a Drops tab. */
	public int tabCount()
	{
		return drops.size();
	}

	/**
	 * The Drops tab of a monster or NPC by the name the spawn data gives it, or null: every table of that page ({@code Goblin} gives both
	 * of Goblin's tables), else every page that is that name with a place in brackets ({@code Bandit}).
	 */
	@Nullable
	public Tab drops(String creature)
	{
		return drops.get(key(creature));
	}

	/** The Dropped by tab of an item by its name, every version of it included, or null. */
	@Nullable
	public Tab droppedBy(String item)
	{
		return droppedBy.get(key(item));
	}

	/** What a row is: the item for a Drops row, the monster's table as a player reads it for a Dropped by row. */
	public String label(int row)
	{
		return words[label[row]];
	}

	/** The rarity as the wiki writes it: {@code 1/128}, {@code Always}, {@code ~Rare}. */
	public String rarity(int row)
	{
		return words[rarity[row]];
	}

	/** The row's details before any price: group, quantity, rolls, pickpocket, which tables; or combat level and the rest. */
	public String text(int row)
	{
		return words[text[row]];
	}

	/** The monster's page of a Dropped by row ({@code Goblin} for {@code Goblin#Drop table 1}), or null for a Drops row. */
	@Nullable
	public String base(int row)
	{
		return base[row] < 0 ? null : words[base[row]];
	}

	/** The game's item id, or -1 when the wiki gives none. */
	public int itemId(int row)
	{
		return id[row];
	}

	/** What the Rarity order sorts by: the chance, or for a rarity in words a chance that puts it among the numbers; -1 when unknown. */
	public double order(int row)
	{
		return order[row];
	}

	/** Items expected per kill at one coin each (chance, rolls, mean quantity), or -1 when it cannot be worked out. */
	public double perKill(int row)
	{
		return perKill[row];
	}
}
