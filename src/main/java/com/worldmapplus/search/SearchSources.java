package com.worldmapplus.search;

import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the search entries from what the plugin already has: the list of maps and the hotspots.
 * <p>
 * Shops contribute the shop itself, its keeper as an NPC, and every item it sells. Transports and
 * doorways contribute their names, so searching for a ship or a dungeon finds where it starts.
 */
public final class SearchSources
{
	/** The layer the teleport destinations belong to. */
	public static final String TELEPORT_LAYER = "teleport";

	private SearchSources()
	{
	}

	public static List<SearchEntry> build(MapCatalog catalog, List<Hotspot> hotspots)
	{
		List<SearchEntry> entries = new ArrayList<>();
		Map<String, List<Hotspot>> points = new LinkedHashMap<>();

		for (MapInfo map : catalog.getMaps())
		{
			entries.add(new SearchEntry(SearchKind.PLACE, map.getName(), "Map", map.getId(),
				map.getCenterX(), map.getCenterY()));
		}

		for (Hotspot hotspot : hotspots)
		{
			HoverCard card = hotspot.getCard();
			switch (hotspot.getKind())
			{
				case DOORWAY:
					entries.add(new SearchEntry(SearchKind.DOORWAY, card.getTitle(), card.getSubtitle(),
						hotspot.getMapId(), hotspot.getX(), hotspot.getY()));
					break;
				case TRANSPORT:
					SearchEntry transport = new SearchEntry(SearchKind.TRANSPORT, card.getTitle(), destinations(card),
						hotspot.getMapId(), hotspot.getX(), hotspot.getY());
					transport.setNeed(hotspot.getNeed());
					entries.add(transport);
					break;
				case SHOP:
					addShop(entries, hotspot);
					break;
				case TELEPORT:
					addTeleports(entries, hotspot);
					break;
				case POI:
					if (TELEPORT_LAYER.equals(hotspot.getCategory()))
					{
						// Searched through the teleport cards, which say how to get there.
						break;
					}
					points.computeIfAbsent(hotspot.getMapId() + "|" + card.getTitle() + "|" + card.getSubtitle(),
						k -> new ArrayList<>()).add(hotspot);
					break;
				default:
					break;
			}
		}
		for (List<Hotspot> group : points.values())
		{
			entries.add(pointEntry(group));
		}
		return entries;
	}

	/** How far a result may be from an icon of the same name to take the icon's need, in tiles. */
	private static final double ICON_TILES = 3.0;

	/**
	 * Gives the results for doorways, points and transport that have no need of their own the need of the game's icon they stand
	 * for: an icon with a need on the same map, within {@link #ICON_TILES} tiles, whose card or a point merged into it has the same
	 * name. So a search result says what is missing exactly where the icon's card does (round 47).
	 */
	public static void attachIconNeeds(List<SearchEntry> entries, List<com.worldmapplus.layers.GameIconSet.Spec> icons)
	{
		Map<String, List<com.worldmapplus.layers.GameIconSet.Spec>> byName = new java.util.HashMap<>();
		for (com.worldmapplus.layers.GameIconSet.Spec icon : icons)
		{
			if (icon.getNeed() == null || !icon.getNeed().hasAlternatives())
			{
				continue;
			}
			List<String> names = new ArrayList<>();
			if (icon.getCard() != null && icon.getCard().getCard().getTitle() != null)
			{
				names.add(icon.getCard().getCard().getTitle());
			}
			if (icon.getExtras() != null)
			{
				for (com.worldmapplus.layers.IconExtra extra : icon.getExtras())
				{
					if (extra.getName() != null)
					{
						names.add(extra.getName());
					}
				}
			}
			for (String name : names)
			{
				byName.computeIfAbsent(icon.getMapId() + "|" + name, k -> new ArrayList<>()).add(icon);
			}
		}
		for (SearchEntry entry : entries)
		{
			SearchKind kind = entry.getKind();
			if (entry.getNeed() != null || kind != SearchKind.DOORWAY && kind != SearchKind.POINT && kind != SearchKind.TRANSPORT)
			{
				continue;
			}
			List<com.worldmapplus.layers.GameIconSet.Spec> same = byName.get(entry.getMapId() + "|" + entry.getName());
			if (same == null)
			{
				continue;
			}
			for (com.worldmapplus.layers.GameIconSet.Spec icon : same)
			{
				if (Math.abs(icon.getX() - entry.getX()) <= ICON_TILES && Math.abs(icon.getY() - entry.getY()) <= ICON_TILES)
				{
					entry.setNeed(icon.getNeed());
					break;
				}
			}
		}
	}

	/**
	 * One result for all the places of the same name on a map, such as every bank in a town, with each
	 * place attached so the map marks them all and a jump can choose the nearest.
	 */
	public static SearchEntry pointEntry(List<Hotspot> group)
	{
		Hotspot first = group.get(0);
		List<double[]> tiles = new ArrayList<>();
		for (Hotspot hotspot : group)
		{
			// Several points merged into one icon of the game (the booths of a bank) are one place.
			boolean repeat = false;
			for (double[] seen : tiles)
			{
				repeat |= seen[0] == hotspot.getX() && seen[1] == hotspot.getY();
			}
			if (!repeat)
			{
				tiles.add(new double[] {hotspot.getX(), hotspot.getY()});
			}
		}
		String detail = first.getCard().getSubtitle() == null ? "" : first.getCard().getSubtitle();
		if (tiles.size() > 1)
		{
			detail += (detail.isEmpty() ? "" : " ") + "(" + tiles.size() + " places)";
		}
		return new SearchEntry(SearchKind.POINT, first.getCard().getTitle(), detail, first.getMapId(),
			first.getX(), first.getY(), tiles);
	}

	private static String destinations(HoverCard card)
	{
		StringBuilder text = new StringBuilder();
		for (HoverCard.Row row : card.visibleRows(true))
		{
			if (!row.getLabel().startsWith(" ") && !row.getLabel().startsWith("and "))
			{
				text.append(text.length() > 0 ? ", " : "").append(row.getLabel());
			}
			if (text.length() > 160)
			{
				break;
			}
		}
		return text.toString();
	}

	/** Each way of teleporting to a place is a result of its own, found by its name. */
	private static void addTeleports(List<SearchEntry> entries, Hotspot hotspot)
	{
		for (HoverCard.Row row : hotspot.getCard().visibleRows(true))
		{
			if (row.getLabel().startsWith(" ") || row.getLabel().startsWith("and "))
			{
				continue;
			}
			SearchEntry way = new SearchEntry(SearchKind.TELEPORT, row.getLabel(), row.getValue(),
				hotspot.getMapId(), hotspot.getX(), hotspot.getY());
			// The way's own need: another way to the same place may ask nothing.
			way.setNeed(hotspot.wayNeed(row.getLabel()));
			entries.add(way);
		}
	}

	private static final java.util.regex.Pattern PRICE = java.util.regex.Pattern.compile("^([\\d,]+) coins?\\b");

	/** The price in coins at the start of a shop row such as {@code 1,200 coins, 10 in stock}, or -1. */
	static long coinPrice(String value)
	{
		java.util.regex.Matcher m = PRICE.matcher(value == null ? "" : value);
		if (!m.find())
		{
			return -1;
		}
		try
		{
			return Long.parseLong(m.group(1).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static void addShop(List<SearchEntry> entries, Hotspot hotspot)
	{
		HoverCard card = hotspot.getCard();
		String subtitle = card.getSubtitle();
		entries.add(new SearchEntry(SearchKind.SHOP, card.getTitle(), subtitle,
			hotspot.getMapId(), hotspot.getX(), hotspot.getY()));

		String prefix = "Run by ";
		if (subtitle != null && subtitle.startsWith(prefix))
		{
			String keeper = subtitle.substring(prefix.length());
			int comma = keeper.indexOf(',');
			if (comma > 0)
			{
				keeper = keeper.substring(0, comma);
			}
			entries.add(new SearchEntry(SearchKind.NPC, keeper, "Runs " + card.getTitle(),
				hotspot.getMapId(), hotspot.getX(), hotspot.getY()));
		}

		for (HoverCard.Row row : card.visibleRows(true))
		{
			entries.add(new SearchEntry(SearchKind.ITEM, row.getLabel(),
				"Sold at " + card.getTitle() + " for " + row.getValue(),
				hotspot.getMapId(), hotspot.getX(), hotspot.getY(), java.util.Collections.emptyList(),
				coinPrice(row.getValue()), ItemKinds.join(ItemKinds.classify(row.getLabel()))));
		}
	}
}
