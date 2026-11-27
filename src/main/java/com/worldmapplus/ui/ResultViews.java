package com.worldmapplus.ui;

import com.worldmapplus.drops.*;
import com.worldmapplus.search.*;
import java.util.*;
import javax.annotation.*;

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

	private ResultViews()
	{
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
			rows.add(new ResultView.Row(group.getTown(), kind.count(group.getTiles().size()), group.getMapId(), first[0], first[1],
				group.getTiles(), -1, -1, null));
		}
		String summary = (kind.getTotal() == 1 ? "1 place" : kind.getTotal() + " places") + " in "
			+ (rows.size() == 1 ? "1 town" : rows.size() + " towns") + ", nearest first";
		return new ResultView(kind.getPlural(), summary, Collections.singletonList(
			new ResultView.Tab("Places", limit(rows), Arrays.asList(ResultView.Sort.NEAREST, ResultView.Sort.NAME))));
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
			if (other.getTerms() != null)
			{
				shops.add(new ResultView.Row(other.getRow(), other.getTerms(), other.getMapId(), other.getX(), other.getY(),
					ResultView.tiles(other), other.getStock(), other.getPrice(), shopEntry(other.getRow(), other, all)));
			}
			else if (other.isGround())
			{
				int here = Math.max(1, other.getAlsoAt().size());
				ground.add(new ResultView.Row(other.getRow() == null ? name : other.getRow(), here + " here", other.getMapId(), other.getX(),
					other.getY(), ResultView.tiles(other), -1, -1, null));
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
		DropTables.Tab dropped = drops == null ? null : drops.droppedBy(name);
		ResultView.Tab droppedBy = dropped == null ? null : DropViews.tab(drops, dropped, all, dropRows);
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
			rows.add(new ResultView.Row(other.getRow() == null ? entry.getName() : other.getRow(), here + " here",
				other.getMapId(), other.getX(), other.getY(), ResultView.tiles(other), -1, -1, null));
		}
		List<ResultView.Row> dropRows = new ArrayList<>();
		DropTables.Tab drop = drops == null ? null : drops.drops(entry.getName());
		ResultView.Tab dropTab = drop == null ? null : DropViews.tab(drops, drop, null, dropRows);
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
		String summary = drop.getSummary() + (rows.isEmpty() ? "" : ", found in " + plural(rows.size(), "place"));
		ResultView view = new ResultView(entry.getName(), summary, tabs);
		view.drops = new DropViews.Part(dropRows, summary, drop.isTotal());
		return view;
	}

	@Nullable
	static ResultView forShop(SearchEntry shop, List<SearchEntry> all)
	{
		List<ResultView.Row> rows = new ArrayList<>();
		for (SearchEntry other : all)
		{
			if (other.getTerms() != null && shop.getName().equals(other.getRow()) && other.getMapId() == shop.getMapId()
				&& Math.abs(other.getX() - shop.getX()) < 2 && Math.abs(other.getY() - shop.getY()) < 2)
			{
				rows.add(new ResultView.Row(other.getName(), other.getTerms(), shop.getMapId(), shop.getX(), shop.getY(),
					ResultView.tiles(shop), other.getStock(), other.getPrice(), other));
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
