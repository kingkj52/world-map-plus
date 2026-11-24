package com.worldmapplus.ui;

import com.worldmapplus.drops.DropTables;
import com.worldmapplus.drops.PriceSource;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The drop tabs of the results panel: "Drops" for a monster or NPC (what it drops, by rarity or by value) and "Dropped by" for an
 * item (every monster that drops it, how rare, and the nearest place of that monster). Made when a result is chosen, from the bundled
 * drop tables; prices are worked in later, when RuneLite's item manager has answered ({@link Part#apply}).
 */
final class DropViews
{
	static final String DROPS = "Drops";
	static final String DROPPED_BY = "Dropped by";

	private DropViews()
	{
	}

	/** What a drop row needs to work out its value once a price is known. Made once per row. */
	static final class Line
	{
		/** The item's id for its price, or -1. */
		final int itemId;
		/** Chance per kill for the Rarity order, or -1 for a rarity in words. */
		final double order;
		/** Items expected per kill at one coin each (chance, rolls, mean quantity), or -1 when it cannot be worked out. */
		final double perKill;
		/** The row's text before any price. */
		final String text;
		/** True when the price of one item is shown ("2.1k each"): a Drops row, where each row is another item. */
		final boolean each;

		Line(int itemId, double order, double perKill, String text, boolean each)
		{
			this.itemId = itemId;
			this.order = order;
			this.perKill = perKill;
			this.text = text;
			this.each = each;
		}
	}

	/** The drop rows of a view and the parts of its summary, for working prices in. */
	static final class Part
	{
		final List<ResultView.Row> rows;
		final String summary;
		/** True when the coins per kill of all rows are added up in the summary (a monster with one table). */
		final boolean total;
		final int[] itemIds;

		Part(List<ResultView.Row> rows, String summary, boolean total)
		{
			this.rows = rows;
			this.summary = summary;
			this.total = total;
			int[] ids = new int[rows.size()];
			int n = 0;
			for (ResultView.Row row : rows)
			{
				if (row.drop != null && row.drop.itemId >= 0)
				{
					ids[n++] = row.drop.itemId;
				}
			}
			this.itemIds = Arrays.stream(Arrays.copyOf(ids, n)).distinct().toArray();
		}

		/** Works the prices known now into every row and returns the summary to show. */
		String apply(PriceSource prices)
		{
			double sum = 0;
			boolean any = false;
			for (ResultView.Row row : rows)
			{
				Line line = row.drop;
				long price = line == null || line.itemId < 0 ? -1 : prices.price(line.itemId);
				row.price = price > 0 ? price : -1;
				row.value = price > 0 && line.perKill >= 0 ? line.perKill * price : -1;
				StringBuilder text = new StringBuilder(line == null ? "" : line.text);
				if (price > 0 && line.each)
				{
					append(text, coins(price) + " each");
				}
				if (row.value >= 0)
				{
					append(text, coins(row.value) + " a kill");
					sum += row.value;
					any = true;
				}
				row.detail = text.toString();
			}
			return total && any ? summary + ", about " + coins(sum) + " coins a kill" : summary;
		}
	}

	private static void append(StringBuilder text, String part)
	{
		if (text.length() > 0)
		{
			text.append(", ");
		}
		text.append(part);
	}

	/** Coins, short: {@code 0.4}, {@code 12}, {@code 1,250}, {@code 125.0k}, {@code 12.3M}. */
	static String coins(double value)
	{
		if (value < 10)
		{
			return String.format(Locale.ROOT, "%.1f", value);
		}
		if (value < 100_000)
		{
			return String.format(Locale.ROOT, "%,d", Math.round(value));
		}
		if (value < 10_000_000)
		{
			return String.format(Locale.ROOT, "%.1fk", value / 1000);
		}
		return String.format(Locale.ROOT, "%.1fM", value / 1_000_000);
	}

	/** Items expected per kill from a row, at one coin each, or -1. */
	private static double perKill(DropTables tables, int row)
	{
		double chance = tables.chance(row);
		int high = tables.quantityHigh(row);
		if (chance < 0 || high <= 0)
		{
			return -1;
		}
		return chance * tables.rowRolls(row) / 100.0 * (tables.quantityLow(row) + high) / 2.0;
	}

	/** The combat levels of some tables in words, or an empty text: {@code Combat 2}, {@code Combat 2-13}. */
	static String combat(DropTables tables, int... sources)
	{
		int low = Integer.MAX_VALUE;
		int high = -1;
		for (int s : sources)
		{
			if (tables.combatLow(s) >= 0)
			{
				low = Math.min(low, tables.combatLow(s));
				high = Math.max(high, tables.combatHigh(s));
			}
		}
		if (high < 0)
		{
			return "";
		}
		return low == high ? "Combat " + low : "Combat " + low + "-" + high;
	}

	/** What tells one table of a monster from its others: {@code Drop table 1} for {@code Goblin#Drop table 1}. */
	static String versionLabel(String creature, String source)
	{
		String shown = DropTables.display(source);
		String prefix = creature + " (";
		if (shown.regionMatches(true, 0, prefix, 0, prefix.length()) && shown.endsWith(")"))
		{
			return shown.substring(prefix.length(), shown.length() - 1);
		}
		return shown;
	}

	/**
	 * The Drops tab of a monster or NPC, or null when it has no table. Rows the same in every table of the monster are shown once;
	 * a row only some tables have says which. Sets {@code parts} to the rows and the summary's drop part.
	 */
	@Nullable
	static ResultView.Tab dropsTab(DropTables tables, String creature, List<ResultView.Row> rowsOut, StringBuilder summaryOut,
		boolean[] totalOut)
	{
		int[] sources = tables.sourcesFor(creature);
		if (sources.length == 0)
		{
			return null;
		}
		Map<String, int[]> firstRow = new LinkedHashMap<>();
		Map<String, List<String>> versions = new HashMap<>();
		for (int s : sources)
		{
			String label = versionLabel(creature, tables.sourceName(s));
			for (int r = tables.rowsStart(s); r < tables.rowsEnd(s); r++)
			{
				String key = tables.rowItem(r) + "|" + tables.quantityText(r) + "|" + tables.rarityText(r) + "|" + tables.rowFlags(r) + "|"
					+ tables.rowRolls(r);
				firstRow.putIfAbsent(key, new int[] {r});
				versions.computeIfAbsent(key, k -> new ArrayList<>()).add(label);
			}
		}
		for (Map.Entry<String, int[]> e : firstRow.entrySet())
		{
			int r = e.getValue()[0];
			List<String> in = versions.get(e.getKey());
			StringBuilder text = new StringBuilder();
			double chance = tables.chance(r);
			text.append(DropTables.group(chance, tables.rarityKind(r)));
			String quantity = tables.quantityText(r);
			if (!quantity.isEmpty())
			{
				text.append(", ").append(quantity);
			}
			if (tables.rowRolls(r) != 100)
			{
				text.append(", ").append(rolls(tables.rowRolls(r)));
			}
			if ((tables.rowFlags(r) & DropTables.PICKPOCKET) != 0)
			{
				text.append(", pickpocket");
			}
			if (sources.length > 1 && in.size() < sources.length)
			{
				text.append(", ").append(in.size() <= 2 ? String.join(", ", in) : in.get(0) + " and " + (in.size() - 1) + " more");
			}
			int item = tables.rowItem(r);
			String name = DropTables.display(tables.itemName(item));
			Line line = new Line(tables.itemId(item), DropTables.orderOf(chance, tables.rarityKind(r)), perKill(tables, r),
				text.toString(), true);
			SearchEntry follow = new SearchEntry(SearchKind.ITEM, name, "", -1, 0, 0);
			rowsOut.add(new ResultView.Row(name, tables.rarityText(r), line, null, follow));
		}
		int items = (int) rowsOut.stream().map(row -> row.label).distinct().count();
		String level = combat(tables, sources);
		summaryOut.append(level);
		if (summaryOut.length() > 0)
		{
			summaryOut.append(". ");
		}
		summaryOut.append("Drops ").append(items).append(items == 1 ? " item" : " items");
		if (sources.length > 1)
		{
			summaryOut.append(" in ").append(sources.length).append(" tables");
		}
		totalOut[0] = sources.length == 1;
		return new ResultView.Tab(DROPS, limit(rowsOut), Arrays.asList(ResultView.Sort.RARITY, ResultView.Sort.VALUE, ResultView.Sort.NAME));
	}

	private static String rolls(int hundredths)
	{
		return hundredths % 100 == 0 ? (hundredths / 100) + " rolls" : String.format(Locale.ROOT, "%.2f rolls", hundredths / 100.0);
	}

	/**
	 * The Dropped by tab of an item, or null when nothing drops it: a row per table that drops it, with its monster's places from the
	 * spawn data so the row goes to the nearest. Rows go into {@code rowsOut}.
	 */
	@Nullable
	static ResultView.Tab droppedByTab(DropTables tables, String itemName, List<SearchEntry> all, List<ResultView.Row> rowsOut)
	{
		int[] rows = tables.rowsOfName(itemName);
		if (rows.length == 0)
		{
			return null;
		}
		Map<String, List<SearchEntry>> creatures = creatures(all);
		for (int r : rows)
		{
			int source = tables.rowSource(r);
			String sourceName = tables.sourceName(source);
			String base = DropTables.base(sourceName);
			List<SearchEntry> places = creatures.get(key(base));
			if (places == null)
			{
				places = creatures.getOrDefault(key(withoutBrackets(base)), Collections.emptyList());
			}
			StringBuilder text = new StringBuilder(combat(tables, source));
			String quantity = tables.quantityText(r);
			if (!quantity.isEmpty())
			{
				append(text, quantity);
			}
			if (tables.rowRolls(r) != 100)
			{
				append(text, rolls(tables.rowRolls(r)));
			}
			if ((tables.rowFlags(r) & DropTables.PICKPOCKET) != 0)
			{
				append(text, "pickpocket");
			}
			double chance = tables.chance(r);
			Line line = new Line(tables.itemId(tables.rowItem(r)), DropTables.orderOf(chance, tables.rarityKind(r)), perKill(tables, r),
				text.toString(), false);
			SearchEntry follow = places.isEmpty() ? new SearchEntry(SearchKind.MONSTER, base, "", -1, 0, 0) : places.get(0);
			rowsOut.add(new ResultView.Row(DropTables.display(sourceName), tables.rarityText(r), line, places, follow));
		}
		return new ResultView.Tab(DROPPED_BY, limit(rowsOut), Arrays.asList(ResultView.Sort.RARITY, ResultView.Sort.NEAREST,
			ResultView.Sort.NAME));
	}

	/** Every monster and NPC entry of the search, by its name. */
	private static Map<String, List<SearchEntry>> creatures(List<SearchEntry> all)
	{
		Map<String, List<SearchEntry>> out = new HashMap<>();
		for (SearchEntry entry : all)
		{
			if ((entry.getKind() == SearchKind.MONSTER || entry.getKind() == SearchKind.NPC) && entry.getMapId() >= 0)
			{
				out.computeIfAbsent(key(entry.getName()), k -> new ArrayList<>()).add(entry);
			}
		}
		return out;
	}

	private static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}

	private static String withoutBrackets(String name)
	{
		int open = name.lastIndexOf(" (");
		return open > 0 && name.endsWith(")") ? name.substring(0, open) : name;
	}

	private static List<ResultView.Row> limit(List<ResultView.Row> rows)
	{
		if (rows.size() > ResultViews.MAX_ROWS)
		{
			List<ResultView.Row> kept = new ArrayList<>(rows.subList(0, ResultViews.MAX_ROWS));
			rows.clear();
			rows.addAll(kept);
		}
		return rows;
	}
}
