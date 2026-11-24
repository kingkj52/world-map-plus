package com.worldmapplus.drops;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every bundled drop table: what each monster drops and what an NPC gives when pickpocketed, and the other way round, what drops an
 * item. Read from {@code drops.bin} by {@link DropFile}; immutable once made, so any thread may read it. The tables are kept as flat
 * arrays (a row is an index into each), and the list of what drops each item is worked out here from the same rows, so nothing is
 * stored twice.
 */
public final class DropTables
{
	/** Rarity kinds, as the file stores them. */
	public static final int FRACTION = 0;
	public static final int ALWAYS = 1;
	public static final int COMMON = 2;
	public static final int UNCOMMON = 3;
	public static final int RARE = 4;
	public static final int VERY_RARE = 5;
	public static final int VARIES = 6;
	public static final int UNKNOWN = 7;

	/** Row flags. */
	public static final int NOTED = 1;
	public static final int PICKPOCKET = 2;
	public static final int ROLLS = 4;

	/** Nothing at all, for a file that could not be read. */
	public static final DropTables EMPTY = new DropTables("", new String[0], new int[0], new int[0], new int[0], new int[0],
		new long[0], new long[0], new String[0], new int[0], new int[0], new int[] {0}, new int[0], new int[0], new int[0], new int[0],
		new int[0]);

	private final String fetched;
	private final String[] itemNames;
	private final int[] itemIds;
	private final int[] quantityLow;
	private final int[] quantityHigh;
	private final int[] rarityHead;
	private final long[] rarityNumerator;
	private final long[] rarityDenominator;
	private final String[] sourceNames;
	private final int[] combatLow;
	private final int[] combatHigh;
	/** Where each source's rows start; one more entry than sources, the last is the row count. */
	private final int[] rowStart;
	private final int[] rowItem;
	private final int[] rowQuantity;
	private final int[] rowRarity;
	private final int[] rowFlags;
	private final int[] rowRolls;
	/** Every row, by item: {@code byItem[byItemStart[i] .. byItemStart[i + 1])} are the rows of item {@code i}. */
	private final int[] byItemStart;
	private final int[] byItem;
	private final int[] rowSource;
	private final Map<String, Integer> itemByName;
	private final Map<String, int[]> sourcesByName;
	private final Map<String, int[]> sourcesLoose;

	DropTables(String fetched, String[] itemNames, int[] itemIds, int[] quantityLow, int[] quantityHigh, int[] rarityHead,
		long[] rarityNumerator, long[] rarityDenominator, String[] sourceNames, int[] combatLow, int[] combatHigh, int[] rowStart,
		int[] rowItem, int[] rowQuantity, int[] rowRarity, int[] rowFlags, int[] rowRolls)
	{
		this.fetched = fetched;
		this.itemNames = itemNames;
		this.itemIds = itemIds;
		this.quantityLow = quantityLow;
		this.quantityHigh = quantityHigh;
		this.rarityHead = rarityHead;
		this.rarityNumerator = rarityNumerator;
		this.rarityDenominator = rarityDenominator;
		this.sourceNames = sourceNames;
		this.combatLow = combatLow;
		this.combatHigh = combatHigh;
		this.rowStart = rowStart;
		this.rowItem = rowItem;
		this.rowQuantity = rowQuantity;
		this.rowRarity = rowRarity;
		this.rowFlags = rowFlags;
		this.rowRolls = rowRolls;

		int rows = rowItem.length;
		rowSource = new int[rows];
		for (int s = 0; s < sourceNames.length; s++)
		{
			Arrays.fill(rowSource, rowStart[s], rowStart[s + 1], s);
		}
		byItemStart = new int[itemNames.length + 1];
		for (int r = 0; r < rows; r++)
		{
			byItemStart[rowItem[r] + 1]++;
		}
		for (int i = 0; i < itemNames.length; i++)
		{
			byItemStart[i + 1] += byItemStart[i];
		}
		byItem = new int[rows];
		int[] fill = Arrays.copyOf(byItemStart, itemNames.length);
		for (int r = 0; r < rows; r++)
		{
			byItem[fill[rowItem[r]]++] = r;
		}

		itemByName = new HashMap<>(itemNames.length * 3);
		for (int i = 0; i < itemNames.length; i++)
		{
			itemByName.putIfAbsent(key(itemNames[i]), i);
			itemByName.putIfAbsent(key(display(itemNames[i])), i);
		}
		Map<String, int[]> exact = new HashMap<>();
		Map<String, int[]> loose = new HashMap<>();
		for (int s = 0; s < sourceNames.length; s++)
		{
			String base = base(sourceNames[s]);
			add(exact, key(base), s);
			String plain = withoutBrackets(base);
			if (!plain.equals(base))
			{
				add(loose, key(plain), s);
			}
		}
		sourcesByName = Collections.unmodifiableMap(exact);
		sourcesLoose = Collections.unmodifiableMap(loose);
	}

	private static void add(Map<String, int[]> map, String key, int value)
	{
		int[] old = map.get(key);
		int[] next = old == null ? new int[1] : Arrays.copyOf(old, old.length + 1);
		next[next.length - 1] = value;
		map.put(key, next);
	}

	static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}

	/** The name of a page without its table: {@code Goblin#Drop table 1} is {@code Goblin}. */
	public static String base(String name)
	{
		int hash = name.indexOf('#');
		return hash < 0 ? name : name.substring(0, hash);
	}

	/** The table's own part of a name, or an empty text: {@code Goblin#Drop table 1} gives {@code Drop table 1}. */
	public static String version(String name)
	{
		int hash = name.indexOf('#');
		return hash < 0 ? "" : name.substring(hash + 1);
	}

	/** A name as a player reads it: {@code Lesser demon#Chasm of Fire} is {@code Lesser demon (Chasm of Fire)}. */
	public static String display(String name)
	{
		int hash = name.indexOf('#');
		return hash < 0 ? name : name.substring(0, hash) + " (" + name.substring(hash + 1) + ")";
	}

	/** A name without a closing part in brackets: {@code Bandit (Pollnivneach)} is {@code Bandit}. */
	static String withoutBrackets(String name)
	{
		int open = name.lastIndexOf(" (");
		return open > 0 && name.endsWith(")") ? name.substring(0, open) : name;
	}

	public boolean isEmpty()
	{
		return rowItem.length == 0;
	}

	/** The date the data was taken from the wiki. */
	public String fetched()
	{
		return fetched;
	}

	public int itemCount()
	{
		return itemNames.length;
	}

	public int sourceCount()
	{
		return sourceNames.length;
	}

	public int rowCount()
	{
		return rowItem.length;
	}

	public String itemName(int item)
	{
		return itemNames[item];
	}

	/** The game's item id, or -1 when the wiki gives none. */
	public int itemId(int item)
	{
		return itemIds[item];
	}

	/** An item by its name (case does not matter, with or without its version), or -1. */
	public int item(String name)
	{
		Integer found = itemByName.get(key(name));
		return found == null ? -1 : found;
	}

	public String sourceName(int source)
	{
		return sourceNames[source];
	}

	/** The lowest combat level of the source's page, or -1 when none is known. */
	public int combatLow(int source)
	{
		return combatLow[source];
	}

	public int combatHigh(int source)
	{
		return combatHigh[source];
	}

	/**
	 * The tables of a monster or NPC by the name the spawn data gives it: every table of that page ({@code Goblin} gives both of
	 * Goblin's tables), else every page that is that name with a place in brackets ({@code Bandit} gives {@code Bandit (Pollnivneach)}
	 * and the others). Never null.
	 */
	public int[] sourcesFor(String creature)
	{
		String k = key(creature);
		int[] exact = sourcesByName.get(k);
		if (exact != null)
		{
			return exact.clone();
		}
		int[] loose = sourcesLoose.get(k);
		return loose == null ? new int[0] : loose.clone();
	}

	public int rowsStart(int source)
	{
		return rowStart[source];
	}

	public int rowsEnd(int source)
	{
		return rowStart[source + 1];
	}

	public int rowItem(int row)
	{
		return rowItem[row];
	}

	public int rowSource(int row)
	{
		return rowSource[row];
	}

	public int rowFlags(int row)
	{
		return rowFlags[row];
	}

	/** How many times the row is rolled on each kill, times 100 (100 is once). */
	public int rowRolls(int row)
	{
		return rowRolls[row];
	}

	public int quantityLow(int row)
	{
		return quantityLow[rowQuantity[row]];
	}

	public int quantityHigh(int row)
	{
		return quantityHigh[rowQuantity[row]];
	}

	public int rarityKind(int row)
	{
		return rarityHead[rowRarity[row]] & 7;
	}

	public boolean rarityApproximate(int row)
	{
		return (rarityHead[rowRarity[row]] & 8) != 0;
	}

	/** The chance of the row on one roll, or -1 when the wiki gives a word other than Always. */
	public double chance(int row)
	{
		int r = rowRarity[row];
		switch (rarityHead[r] & 7)
		{
			case ALWAYS:
				return 1;
			case FRACTION:
				return Math.min(1.0, rarityNumerator[r] / (double) rarityDenominator[r]);
			default:
				return -1;
		}
	}

	/** The rarity as the wiki writes it: {@code 1/128}, {@code 1/5,461.33}, {@code Always}, {@code ~1/50}. */
	public String rarityText(int row)
	{
		int r = rowRarity[row];
		int head = rarityHead[r];
		String text;
		switch (head & 7)
		{
			case FRACTION:
				boolean scaled = (head & 16) != 0;
				text = number(rarityNumerator[r], scaled) + "/" + number(rarityDenominator[r], scaled);
				break;
			case ALWAYS:
				text = "Always";
				break;
			case COMMON:
				text = "Common";
				break;
			case UNCOMMON:
				text = "Uncommon";
				break;
			case RARE:
				text = "Rare";
				break;
			case VERY_RARE:
				text = "Very rare";
				break;
			case VARIES:
				text = "Varies";
				break;
			default:
				text = "Unknown";
				break;
		}
		return (head & 8) != 0 ? "~" + text : text;
	}

	private static String number(long value, boolean scaled)
	{
		if (!scaled)
		{
			return String.format(Locale.ROOT, "%,d", value);
		}
		long whole = value / 100;
		long part = value % 100;
		if (part == 0)
		{
			return String.format(Locale.ROOT, "%,d", whole);
		}
		String digits = part % 10 == 0 ? String.valueOf(part / 10) : String.format(Locale.ROOT, "%02d", part);
		return String.format(Locale.ROOT, "%,d", whole) + "." + digits;
	}

	/** The quantity as text: {@code 1}, {@code 5-15}, {@code 3 (noted)}, or an empty text when unknown. */
	public String quantityText(int row)
	{
		int low = quantityLow(row);
		int high = quantityHigh(row);
		String text = high == 0 ? "" : low == high ? String.format(Locale.ROOT, "%,d", low)
			: String.format(Locale.ROOT, "%,d-%,d", low, high);
		return (rowFlags[row] & NOTED) != 0 && !text.isEmpty() ? text + " (noted)" : text;
	}

	/** Rows that drop an item, by row number; empty for an unknown item. */
	public int[] rowsOf(int item)
	{
		if (item < 0 || item >= itemNames.length)
		{
			return new int[0];
		}
		return Arrays.copyOfRange(byItem, byItemStart[item], byItemStart[item + 1]);
	}

	/**
	 * Rows that drop an item named so, by its exact name and every version of it ({@code Bird nest (egg)} also finds the versions
	 * stored as {@code Bird nest (egg)#Red egg}).
	 */
	public int[] rowsOfName(String name)
	{
		String k = key(name);
		int[] out = new int[0];
		for (int i = 0; i < itemNames.length; i++)
		{
			if (key(itemNames[i]).equals(k) || key(display(itemNames[i])).equals(k) || key(base(itemNames[i])).equals(k))
			{
				int[] more = rowsOf(i);
				int at = out.length;
				out = Arrays.copyOf(out, at + more.length);
				System.arraycopy(more, 0, out, at, more.length);
			}
		}
		return out;
	}

	/** Which group a chance falls in, as the wiki colours them. */
	public static String group(double chance, int kind)
	{
		if (kind == ALWAYS || chance >= 1)
		{
			return "Always";
		}
		if (chance < 0)
		{
			switch (kind)
			{
				case COMMON:
					return "Common";
				case UNCOMMON:
					return "Uncommon";
				case RARE:
					return "Rare";
				case VERY_RARE:
					return "Very rare";
				default:
					return "Varies";
			}
		}
		if (chance >= 1 / 25.0)
		{
			return "Common";
		}
		if (chance >= 1 / 99.99)
		{
			return "Uncommon";
		}
		if (chance >= 1 / 999.99)
		{
			return "Rare";
		}
		return "Very rare";
	}

	/** An order for rarities words without a number: always first, then the words from common to rare, varies and unknown last. */
	public static double orderOf(double chance, int kind)
	{
		if (chance >= 0)
		{
			return chance;
		}
		switch (kind)
		{
			case COMMON:
				return 1 / 16.0;
			case UNCOMMON:
				return 1 / 64.0;
			case RARE:
				return 1 / 256.0;
			case VERY_RARE:
				return 1 / 1024.0;
			default:
				return -1;
		}
	}
}
