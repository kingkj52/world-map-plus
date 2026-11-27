package com.worldmapplus.ui;

import com.worldmapplus.drops.*;
import com.worldmapplus.search.*;
import java.util.*;
import javax.annotation.*;

/**
 * The drop tabs of the results panel: "Drops" for a monster or NPC (what it drops, by rarity or by value) and "Dropped by" for an
 * item (every monster that drops it, how rare, and the nearest place of that monster). Made when a result is chosen, from the tabs
 * made at build time; prices are worked in later, when RuneLite's item manager has answered ({@link Part#apply}).
 */
final class DropViews
{
	static final String DROPS = "Drops";
	static final String DROPPED_BY = "Dropped by";

	private DropViews()
	{
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
			this.itemIds = rows.stream().mapToInt(row -> row.itemId).filter(id -> id >= 0).distinct().toArray();
		}

		/** Works the prices known now into every row and returns the summary to show. */
		String apply(PriceSource prices)
		{
			double sum = 0;
			boolean any = false;
			for (ResultView.Row row : rows)
			{
				long price = row.itemId < 0 ? -1 : prices.price(row.itemId);
				row.price = price > 0 ? price : -1;
				row.value = price > 0 && row.perKill >= 0 ? row.perKill * price : -1;
				StringBuilder text = new StringBuilder(row.drop);
				if (price > 0 && row.each)
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

	/**
	 * A drop tab from the tab made at build time, its rows also put in {@code rowsOut}: the Drops tab of a monster or NPC when
	 * {@code all} is null (each row an item, which a click opens), else the Dropped by tab of an item (each row a monster's table, going
	 * to the nearest place of that monster in the search entries).
	 */
	static ResultView.Tab tab(DropTables t, DropTables.Tab tab, @Nullable List<SearchEntry> all, List<ResultView.Row> rowsOut)
	{
		boolean each = all == null;
		Map<String, List<SearchEntry>> creatures = new HashMap<>();
		for (SearchEntry entry : each ? Collections.<SearchEntry>emptyList() : all)
		{
			if ((entry.getKind() == SearchKind.MONSTER || entry.getKind() == SearchKind.NPC) && entry.getMapId() >= 0)
			{
				creatures.computeIfAbsent(key(entry.getName()), k -> new ArrayList<>()).add(entry);
			}
		}
		for (int r : tab.getRows())
		{
			String label = t.label(r);
			String base = t.base(r);
			List<SearchEntry> places = null;
			SearchEntry follow;
			if (each)
			{
				follow = new SearchEntry(SearchKind.ITEM, label, "", -1, 0, 0);
			}
			else
			{
				places = creatures.get(key(base));
				if (places == null)
				{
					int open = base.lastIndexOf(" (");
					places = creatures.getOrDefault(key(open > 0 && base.endsWith(")") ? base.substring(0, open) : base),
						Collections.emptyList());
				}
				follow = places.isEmpty() ? new SearchEntry(SearchKind.MONSTER, base, "", -1, 0, 0) : places.get(0);
			}
			ResultView.Row row = new ResultView.Row(label, t.rarity(r), -1, 0, 0, Collections.emptyList(), -1, -1, follow);
			row.detail = row.drop = t.text(r);
			row.each = each;
			row.itemId = t.itemId(r);
			row.order = t.order(r);
			row.perKill = t.perKill(r);
			row.places = places;
			rowsOut.add(row);
		}
		return new ResultView.Tab(each ? DROPS : DROPPED_BY, rowsOut, Arrays.asList(ResultView.Sort.RARITY,
			each ? ResultView.Sort.VALUE : ResultView.Sort.NEAREST, ResultView.Sort.NAME));
	}

	private static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
