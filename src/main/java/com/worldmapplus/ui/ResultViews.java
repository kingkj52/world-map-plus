package com.worldmapplus.ui;

import com.worldmapplus.drops.DropTables;
import com.worldmapplus.search.KindIndex;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Makes what the results panel shows for a chosen result, from the bundled search entries and the kind index:
 * <ul>
 * <li>every place of a kind: a row per town, "Catherby, 2 banks";</li>
 * <li>an item: the shops that sell it (sorted by nearest, stock, name or price) and the places it lies on the ground;</li>
 * <li>a monster or an NPC: every place it is found, with how many are there;</li>
 * <li>a shop: what it sells, sorted by stock, name or price.</li>
 * </ul>
 * A row of a shop list opens that shop's stock, and a row of a stock list opens where that item is sold, so the panel's Back link has
 * something to go back through. Anything else, and anything with a single place, has no panel. Runs when a result is chosen.
 */
final class ResultViews
{
	/** Most rows a tab holds, so a very common thing cannot make a list without end. */
	static final int MAX_ROWS = 400;

	private static final Pattern SOLD = Pattern.compile("^Sold at (.+) for (.+)$");
	private static final Pattern STOCK = Pattern.compile("(\\d[\\d,]*) in stock");
	private static final Pattern PRICE = Pattern.compile("^([\\d,]+) coins?");
	private static final Pattern SPAWNS = Pattern.compile("\\s*\\((\\d+) spawns?\\)$");
	private static final String FREE = "Free to take. ";

	private ResultViews()
	{
	}

	/** The view for a chosen result, or null when it has none. */
	@Nullable
	static ResultView forEntry(SearchEntry entry, KindIndex kinds, List<SearchEntry> all)
	{
		return forEntry(entry, kinds, all, null);
	}

	/** True for a result whose view can have a drop tab: an item, a monster or an NPC. */
	static boolean canHaveDrops(SearchEntry entry)
	{
		return entry.getKindId() == null && (entry.getKind() == SearchKind.ITEM || entry.getKind() == SearchKind.MONSTER
			|| entry.getKind() == SearchKind.NPC);
	}

	/**
	 * The view for a chosen result, with its drop tab when {@code drops} is given (a monster's Drops, an item's Dropped by), or null
	 * when it has none.
	 */
	@Nullable
	static ResultView forEntry(SearchEntry entry, KindIndex kinds, List<SearchEntry> all, @Nullable DropTables drops)
	{
		if (entry.getKindId() != null)
		{
			return forKind(kinds.byId(entry.getKindId()));
		}
		switch (entry.getKind())
		{
			case ITEM:
				return forItem(entry.getName(), all, drops);
			case MONSTER:
			case NPC:
				return forCreature(entry, all, drops);
			case SHOP:
				return forShop(entry, all);
			default:
				return null;
		}
	}

	@Nullable
	static ResultView forKind(@Nullable KindIndex.Kind kind)
	{
		if (kind == null)
		{
			return null;
		}
		List<ResultView.Row> rows = new ArrayList<>();
		for (KindIndex.Group group : kind.getGroups())
		{
			double[] first = group.getTiles().get(0);
			rows.add(new ResultView.Row(group.getTown(), kind.count(group.getTiles().size()), "", group.getMapId(), first[0], first[1],
				group.getTiles(), -1, -1, null));
		}
		String summary = (kind.getTotal() == 1 ? "1 place" : kind.getTotal() + " places") + " in "
			+ (rows.size() == 1 ? "1 town" : rows.size() + " towns") + ", nearest first";
		return new ResultView(kind.getPlural(), summary, Collections.singletonList(
			new ResultView.Tab("Places", limit(rows), Arrays.asList(ResultView.Sort.NEAREST, ResultView.Sort.NAME))));
	}

	@Nullable
	static ResultView forItem(String name, List<SearchEntry> all)
	{
		return forItem(name, all, null);
	}

	@Nullable
	static ResultView forItem(String name, List<SearchEntry> all, @Nullable DropTables drops)
	{
		List<ResultView.Row> shops = new ArrayList<>();
		List<ResultView.Row> ground = new ArrayList<>();
		for (SearchEntry other : all)
		{
			if (other.getKind() != SearchKind.ITEM || !other.getName().equalsIgnoreCase(name))
			{
				continue;
			}
			Matcher sold = SOLD.matcher(other.getDetail());
			if (sold.matches())
			{
				String shop = sold.group(1);
				String terms = sold.group(2);
				shops.add(new ResultView.Row(shop, terms, "", other.getMapId(), other.getX(), other.getY(), single(other), stockOf(terms),
					priceOf(terms), shopEntry(shop, other, all)));
			}
			else if (other.getDetail().startsWith(FREE))
			{
				String where = SPAWNS.matcher(other.getDetail().substring(FREE.length())).replaceAll("");
				int here = Math.max(1, other.getAlsoAt().size());
				ground.add(new ResultView.Row(where.isEmpty() ? name : where, here + " here", "", other.getMapId(), other.getX(), other.getY(),
					other.getAlsoAt().isEmpty() ? single(other) : other.getAlsoAt(), -1, -1, null));
			}
		}
		List<ResultView.Tab> tabs = new ArrayList<>();
		if (!shops.isEmpty())
		{
			tabs.add(new ResultView.Tab("Shops", limit(shops), Arrays.asList(ResultView.Sort.NEAREST, ResultView.Sort.STOCK,
				ResultView.Sort.NAME, ResultView.Sort.PRICE)));
		}
		if (!ground.isEmpty())
		{
			tabs.add(new ResultView.Tab("On the ground", limit(ground), Arrays.asList(ResultView.Sort.NEAREST, ResultView.Sort.NAME)));
		}
		List<ResultView.Row> dropRows = new ArrayList<>();
		ResultView.Tab droppedBy = drops == null ? null : DropViews.droppedByTab(drops, name, all, dropRows);
		if (droppedBy != null)
		{
			tabs.add(droppedBy);
		}
		if (droppedBy == null && shops.size() + ground.size() < 2)
		{
			return null;
		}
		List<String> parts = new ArrayList<>();
		if (!shops.isEmpty())
		{
			parts.add("sold in " + plural(shops.size(), "shop"));
		}
		if (!ground.isEmpty())
		{
			parts.add("lying in " + plural(ground.size(), "place"));
		}
		if (droppedBy != null)
		{
			int monsters = (int) dropRows.stream().map(r -> r.label).distinct().count();
			parts.add("dropped by " + plural(monsters, "monster"));
		}
		String summary = capital(String.join(", ", parts));
		ResultView view = new ResultView(name, summary, tabs);
		if (droppedBy != null)
		{
			view.drops = new DropViews.Part(dropRows, summary, false);
		}
		return view;
	}

	@Nullable
	static ResultView forCreature(SearchEntry entry, List<SearchEntry> all)
	{
		return forCreature(entry, all, null);
	}

	@Nullable
	static ResultView forCreature(SearchEntry entry, List<SearchEntry> all, @Nullable DropTables drops)
	{
		List<ResultView.Row> rows = new ArrayList<>();
		int total = 0;
		for (SearchEntry other : all)
		{
			if (other.getKind() != entry.getKind() || !other.getName().equalsIgnoreCase(entry.getName()))
			{
				continue;
			}
			int here = Math.max(1, other.getAlsoAt().size());
			total += here;
			String where = SPAWNS.matcher(other.getDetail()).replaceAll("");
			rows.add(new ResultView.Row(where.isEmpty() || where.matches("\\d+ spawns?") ? entry.getName() : where, here + " here", "",
				other.getMapId(), other.getX(), other.getY(), other.getAlsoAt().isEmpty() ? single(other) : other.getAlsoAt(), -1, -1, null));
		}
		List<ResultView.Row> dropRows = new ArrayList<>();
		StringBuilder dropSummary = new StringBuilder();
		boolean[] total1 = new boolean[1];
		ResultView.Tab dropTab = drops == null ? null : DropViews.dropsTab(drops, entry.getName(), dropRows, dropSummary, total1);
		if (dropTab == null)
		{
			if (rows.size() < 2)
			{
				return null;
			}
			return new ResultView(entry.getName(), "Found in " + plural(rows.size(), "place") + ", " + total + " in all, nearest first",
				Collections.singletonList(new ResultView.Tab("Locations", limit(rows), Arrays.asList(ResultView.Sort.NEAREST,
					ResultView.Sort.NAME))));
		}
		List<ResultView.Tab> tabs = new ArrayList<>();
		if (!rows.isEmpty())
		{
			tabs.add(new ResultView.Tab("Locations", limit(rows), Arrays.asList(ResultView.Sort.NEAREST, ResultView.Sort.NAME)));
		}
		tabs.add(dropTab);
		String summary = dropSummary + (rows.isEmpty() ? "" : ", found in " + plural(rows.size(), "place"));
		ResultView view = new ResultView(entry.getName(), summary, tabs);
		view.drops = new DropViews.Part(dropRows, summary, total1[0]);
		return view;
	}

	@Nullable
	static ResultView forShop(SearchEntry shop, List<SearchEntry> all)
	{
		String prefix = "Sold at " + shop.getName() + " for ";
		List<ResultView.Row> rows = new ArrayList<>();
		for (SearchEntry other : all)
		{
			if (other.getKind() == SearchKind.ITEM && other.getMapId() == shop.getMapId() && other.getDetail().startsWith(prefix)
				&& Math.abs(other.getX() - shop.getX()) < 2 && Math.abs(other.getY() - shop.getY()) < 2)
			{
				String terms = other.getDetail().substring(prefix.length());
				rows.add(new ResultView.Row(other.getName(), terms, "", shop.getMapId(), shop.getX(), shop.getY(), single(shop),
					stockOf(terms), priceOf(terms), other));
			}
		}
		if (rows.isEmpty())
		{
			return null;
		}
		return new ResultView(shop.getName(), "Sells " + plural(rows.size(), "item") + ". Click one to see where else it is sold",
			Collections.singletonList(new ResultView.Tab("Stock", limit(rows), Arrays.asList(ResultView.Sort.NAME, ResultView.Sort.STOCK,
				ResultView.Sort.PRICE))));
	}

	/** The shop entry for a row of an item's shops, so clicking the row can open what that shop sells. */
	@Nullable
	private static SearchEntry shopEntry(String name, SearchEntry item, List<SearchEntry> all)
	{
		for (SearchEntry other : all)
		{
			if (other.getKind() == SearchKind.SHOP && other.getName().equals(name) && other.getMapId() == item.getMapId()
				&& Math.abs(other.getX() - item.getX()) < 2 && Math.abs(other.getY() - item.getY()) < 2)
			{
				return other;
			}
		}
		return null;
	}

	static long stockOf(String terms)
	{
		Matcher m = STOCK.matcher(terms);
		return m.find() ? Long.parseLong(m.group(1).replace(",", "")) : -1;
	}

	static long priceOf(String terms)
	{
		Matcher m = PRICE.matcher(terms);
		return m.find() ? Long.parseLong(m.group(1).replace(",", "")) : -1;
	}

	private static List<double[]> single(SearchEntry entry)
	{
		return Collections.singletonList(new double[] {entry.getX(), entry.getY()});
	}

	private static List<ResultView.Row> limit(List<ResultView.Row> rows)
	{
		return rows.size() > MAX_ROWS ? new ArrayList<>(rows.subList(0, MAX_ROWS)) : rows;
	}

	private static String plural(int n, String word)
	{
		return n + " " + word + (n == 1 ? "" : "s");
	}

	private static String capital(String text)
	{
		return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
	}
}
