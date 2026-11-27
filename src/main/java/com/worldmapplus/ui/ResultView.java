package com.worldmapplus.ui;

import com.worldmapplus.drops.*;
import com.worldmapplus.search.*;
import java.awt.FontMetrics;
import java.util.*;
import java.util.function.IntFunction;
import javax.annotation.*;

/**
 * What the results panel shows for one chosen result: a title, a line saying what it is, and one or more tabs of rows, each row a
 * place (or a thing sold) with a count of how many are there and how far it is. Pure data with its sorting, so it is tested without a
 * game. Made when a result is chosen, never per frame.
 */
final class ResultView
{
	/** Orders a tab's rows can be put in. */
	enum Sort
	{
		NEAREST("Nearest"),
		STOCK("Stock"),
		NAME("Name"),
		PRICE("Price"),
		/** Most likely first: always, then by chance per kill. */
		RARITY("Rarity"),
		/** Most coins per kill first, from the item's price, its chance and its quantity. */
		VALUE("Value");

		private final String label;

		Sort(String label)
		{
			this.label = label;
		}

		String label()
		{
			return label;
		}
	}

	/** One row: where it is, what it says, how many are there, and what to mark on the map when it is clicked. */
	static final class Row
	{
		final String label;
		final String count;
		/** The row's own part of its second line. A drop row's changes when its price arrives. */
		String detail = "";
		/** Where the row goes; -1 for a row with no place of its own (a drop). A row with {@link #places} takes its nearest. */
		int mapId;
		double x;
		double y;
		List<double[]> mark;
		/** How many are in stock, or -1 when it is not a thing sold or the stock is not known. */
		final long stock;
		/** What it costs, or -1. */
		long price;
		/** The places the row may stand for, nearest chosen by {@link ResultView#measureFrom}; null for a row with one fixed place. */
		@Nullable
		List<SearchEntry> places;
		/** A drop row's details before any price, or null for other rows. */
		@Nullable
		String drop;
		/** True when a drop row shows the price of one item ("2.1k each"): a Drops row, where each row is another item. */
		boolean each;
		/** A drop row's item id for its price, or -1. */
		int itemId = -1;
		/** What the Rarity order sorts by (the chance per kill), or -1. */
		double order = -1;
		/** Items a kill gives at one coin each, or -1 when it cannot be worked out. */
		double perKill = -1;
		/** Coins per kill, or -1 when it cannot be worked out. */
		double value = -1;
		/** A result to open in the panel when the row is clicked, such as the shop a row of an item's shops stands for, or null. */
		final SearchEntry follow;
		/** Tiles from the player, or -1 when the player is not on the row's map. Set by {@link ResultView#measureFrom}. */
		double distance = -1;
		/** How far and which way in words ("515 tiles west"), or the map's name. Set by {@link ResultView#measureFrom}. */
		String where = "";
		/** The second line of the row, made with {@link #where}. */
		String below = "";
		/** The label, count and second line as last cut to fit, and the widths they were cut for, so a frame makes no new text. */
		final String[] cut = new String[3];
		final int[] cutFor = {-1, -1, -1};

		/** A text of the row cut to fit a width, worked out again only when the width or the text changes. */
		String fitted(int which, String text, FontMetrics metrics, int width)
		{
			if (cutFor[which] != width || cut[which] == null)
			{
				cut[which] = Box.clip(text, metrics, width);
				cutFor[which] = width;
			}
			return cut[which];
		}

		Row(String label, String count, int mapId, double x, double y, List<double[]> mark, long stock, long price,
			@Nullable SearchEntry follow)
		{
			this.label = label;
			this.count = count;
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.mark = mark;
			this.stock = stock;
			this.price = price;
			this.follow = follow;
		}
	}

	/** One tab: a name, its rows, the orders it offers and the one in force. */
	static final class Tab
	{
		final String name;
		/** The name with how many rows the tab has, as its button says it. */
		final String label;
		final List<Row> rows;
		final List<Sort> sorts;
		Sort sort;

		Tab(String name, List<Row> rows, List<Sort> sorts)
		{
			this.name = name;
			this.label = name + " " + rows.size();
			this.rows = new ArrayList<>(rows);
			this.sorts = Collections.unmodifiableList(sorts);
			this.sort = sorts.isEmpty() ? null : sorts.get(0);
		}
	}

	final String title;
	/** The line under the title. A view with drops adds its value per kill when the prices arrive. */
	String summary;
	final List<Tab> tabs;
	private int tab;
	/** The drop rows of this view and how its summary is made, or null when it has none. */
	@Nullable
	DropViews.Part drops;
	/** The version of the prices last worked into the drop rows; -1 before the first time. */
	int priceVersion = -1;

	ResultView(String title, String summary, List<Tab> tabs)
	{
		this.title = title;
		this.summary = summary;
		this.tabs = Collections.unmodifiableList(tabs);
	}

	/** Selects the tab of this name, if there is one. */
	void selectTab(String name)
	{
		for (int i = 0; i < tabs.size(); i++)
		{
			if (tabs.get(i).name.equals(name))
			{
				tab = i;
				return;
			}
		}
	}

	/**
	 * Works the prices known now into the drop rows (each, per kill) and the summary. Nothing is asked for here: the prices were
	 * requested when the view was made. Returns true when the rows changed.
	 */
	boolean refreshPrices(PriceSource prices)
	{
		if (drops == null)
		{
			return false;
		}
		int version = prices.version();
		if (version == priceVersion)
		{
			return false;
		}
		priceVersion = version;
		summary = drops.apply(prices);
		return true;
	}

	Tab current()
	{
		return tabs.get(tab);
	}

	int tabIndex()
	{
		return tab;
	}

	void selectTab(int index)
	{
		if (index >= 0 && index < tabs.size())
		{
			tab = index;
		}
	}

	/**
	 * Works out how far each row is from the player, in tiles and in words, and puts every tab back in its order. The player's map is
	 * the one they are on or nearest to; a row on another map says that map's name instead.
	 */
	void measureFrom(boolean known, int playerMap, double px, double py, IntFunction<String> mapName)
	{
		for (Tab t : tabs)
		{
			for (Row row : t.rows)
			{
				if (row.places != null)
				{
					nearest(row, known, playerMap, px, py);
				}
				row.distance = -1;
				if (row.places != null && row.places.isEmpty())
				{
					row.where = "No known place";
				}
				else if (row.mapId < 0)
				{
					row.where = "";
				}
				else if (known && row.mapId == playerMap)
				{
					row.distance = Math.hypot(row.x - px, row.y - py);
					row.where = words(row.x - px, row.y - py);
				}
				else
				{
					String name = mapName.apply(row.mapId);
					row.where = name == null ? "" : name;
				}
				row.below = row.detail.isEmpty() ? row.where : row.where.isEmpty() ? row.detail : row.where + ", " + row.detail;
				row.cutFor[2] = -1;
			}
			sort(t, t.sort);
		}
	}

	/** Points a row at the place of its monster nearest the player, else its first place. */
	private static void nearest(Row row, boolean known, int playerMap, double px, double py)
	{
		SearchEntry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (SearchEntry place : row.places)
		{
			if (!known || place.getMapId() != playerMap)
			{
				continue;
			}
			for (double[] p : tiles(place))
			{
				double d = Math.hypot(p[0] - px, p[1] - py);
				if (d < bestDistance)
				{
					bestDistance = d;
					best = place;
					row.x = p[0];
					row.y = p[1];
				}
			}
		}
		if (best == null)
		{
			if (row.places.isEmpty())
			{
				row.mapId = -1;
				return;
			}
			best = row.places.get(0);
			row.x = best.getX();
			row.y = best.getY();
		}
		row.mapId = best.getMapId();
		row.mark = tiles(best);
	}

	/** Every place an entry marks: its own, or all it is also at. */
	static List<double[]> tiles(SearchEntry entry)
	{
		return entry.getAlsoAt().isEmpty() ? Collections.singletonList(new double[] {entry.getX(), entry.getY()}) : entry.getAlsoAt();
	}

	/** Puts a tab in an order. Ties keep the order the rows were made in, so nothing shuffles. */
	static void sort(Tab t, @Nullable Sort sort)
	{
		if (sort == null)
		{
			return;
		}
		t.sort = sort;
		Comparator<Row> order;
		switch (sort)
		{
			case NEAREST:
				order = Comparator.comparingDouble(r -> r.distance < 0 ? Double.MAX_VALUE : r.distance);
				break;
			case STOCK:
				order = Comparator.comparingLong(r -> r.stock < 0 ? Long.MAX_VALUE : -r.stock);
				break;
			case PRICE:
				order = Comparator.comparingLong(r -> r.price < 0 ? Long.MAX_VALUE : r.price);
				break;
			case RARITY:
				order = Comparator.comparingDouble(r -> r.order < 0 ? Double.MAX_VALUE : -r.order);
				break;
			case VALUE:
				order = Comparator.comparingDouble(r -> r.value < 0 ? Double.MAX_VALUE : -r.value);
				break;
			default:
				order = Comparator.comparing(r -> r.label.toLowerCase(Locale.ROOT));
				break;
		}
		t.rows.sort(order);
	}

	private static final String[] DIRECTIONS = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};

	/** How far and which way, in words: {@code here}, {@code 40 tiles north}, {@code 1.6k tiles south-west}. North is up the map. */
	static String words(double dx, double dy)
	{
		double d = Math.hypot(dx, dy);
		if (d < 6)
		{
			return "here";
		}
		int octant = (int) Math.floorMod(Math.round(Math.atan2(dy, dx) / (Math.PI / 4)), 8L);
		long tiles = Math.round(d);
		String amount = tiles >= 1000 ? String.format(Locale.ROOT, "%.1fk", tiles / 1000.0) : String.valueOf(tiles);
		return amount + " tiles " + DIRECTIONS[octant];
	}
}
