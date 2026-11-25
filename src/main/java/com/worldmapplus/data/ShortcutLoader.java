package com.worldmapplus.data;

import com.google.gson.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.map.*;
import com.worldmapplus.unlock.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import lombok.extern.slf4j.*;

/**
 * The cards of the agility shortcut icons: what each shortcut is called, which levels and items it needs, how long it
 * takes and where it comes out, with a line from the icon to each end while it is hovered. The table is made by
 * tools/build_shortcuts.py from Shortest Path's list and says which icon each shortcut belongs to, so a card sits on
 * the icon it is about. An icon the table has nothing for keeps the plain card the game's own name gives it.
 */
@Slf4j
public final class ShortcutLoader
{
	private static final String SHORTCUTS = "/com/worldmapplus/shortcuts.json";

	/** Pixel radius of an icon's card. */
	private static final int RADIUS = 11;

	/** Rows a card shows before the rest wait for Shift. */
	private static final int MAX_ROWS = 12;

	/** Ends nearer than this to the icon are the icon's own spot, and no line is drawn to them. */
	private static final double OWN_SPOT_TILES = 1.2;

	private static final double SECONDS_PER_TICK = 0.6;

	private ShortcutLoader()
	{
	}

	static final class File
	{
		List<Entry> shortcuts;
	}

	static final class Entry
	{
		double[] pin;
		List<Option> options;
	}

	static final class Option
	{
		String name;
		String action;
		List<String> skills;
		List<String> items;
		List<String> quests;
		/** Other needs in the wiki's words: achievement diary tiers and quest points (round 47). */
		List<String> requires;
		int ticks;
		boolean oneWay;
		int[] a;
		int[] b;
	}

	/** The cards for every shortcut icon the table covers. */
	public static List<Hotspot> load(Gson gson, MapCatalog catalog)
	{
		File file = read(gson);
		if (file == null || file.shortcuts == null)
		{
			return new ArrayList<>();
		}
		return hotspots(file, catalog);
	}

	static List<Hotspot> hotspots(File file, MapCatalog catalog)
	{
		List<Hotspot> result = new ArrayList<>();
		for (Entry entry : file.shortcuts)
		{
			if (entry == null || entry.pin == null || entry.pin.length < 2 || entry.options == null || entry.options.isEmpty())
			{
				continue;
			}
			MapInfo map = catalog.mapFor((int) Math.floor(entry.pin[0]), (int) Math.floor(entry.pin[1]));
			if (map == null)
			{
				continue;
			}
			// The icon's position is already the middle of its tile.
			double x = entry.pin[0];
			double y = entry.pin[1];
			List<Hotspot.Link> links = new ArrayList<>();
			for (Option option : entry.options)
			{
				addEnd(links, catalog, option.a, x, y);
				addEnd(links, catalog, option.b, x, y);
			}
			Hotspot.Target target = null;
			HoverCard.Builder card = entry.options.size() == 1 ? single(entry.options.get(0)) : several(entry.options);
			if (links.size() == 1)
			{
				target = new Hotspot.Target(links.get(0).getMapId(), links.get(0).getX(), links.get(0).getY());
				card.hint("Click to see where it comes out");
			}
			Needs.Ways ways = new Needs.Ways();
			for (Option option : entry.options)
			{
				ways.add(option.skills, option.quests, option.requires);
			}
			result.add(new Hotspot(map.getId(), x, y, RADIUS, card.build(), target, links).withNeed(ways.build()));
		}
		return result;
	}

	private static void addEnd(List<Hotspot.Link> links, MapCatalog catalog, int[] end, double x, double y)
	{
		if (end == null || end.length < 2 || Math.hypot(end[0] + 0.5 - x, end[1] + 0.5 - y) <= OWN_SPOT_TILES)
		{
			return;
		}
		for (Hotspot.Link link : links)
		{
			if (Math.hypot(link.getX() - end[0] - 0.5, link.getY() - end[1] - 0.5) < 1.0)
			{
				return;
			}
		}
		MapInfo map = catalog.mapFor(end[0], end[1]);
		if (map != null)
		{
			links.add(new Hotspot.Link(map.getId(), end[0] + 0.5, end[1] + 0.5, ""));
		}
	}

	private static HoverCard.Builder single(Option option)
	{
		HoverCard.Builder card = HoverCard.builder(clean(option.name)).subtitle("Agility shortcut");
		for (HoverCard.Row row : details(option))
		{
			card.row(row.getLabel(), row.getValue());
		}
		return card;
	}

	private static HoverCard.Builder several(List<Option> options)
	{
		HoverCard.Builder card = HoverCard.builder("Agility shortcuts").subtitle(options.size() + " here");
		int shown = 0;
		for (Option option : options)
		{
			boolean overflow = shown >= MAX_ROWS;
			shown++;
			String need = requirement(option);
			add(card, overflow, clean(option.name), need.isEmpty() ? "no requirement" : need);
			if (option.items != null && !option.items.isEmpty())
			{
				add(card, overflow, "   needs", String.join(", ", option.items));
			}
			if (option.quests != null && !option.quests.isEmpty())
			{
				add(card, overflow, "   quest", String.join(", ", option.quests));
			}
		}
		return card;
	}

	private static void add(HoverCard.Builder card, boolean overflow, String label, String value)
	{
		if (overflow)
		{
			card.overflowRow(label, value);
		}
		else
		{
			card.row(label, value);
		}
	}

	/** The rows under the title of a shortcut that is alone on its icon. */
	private static List<HoverCard.Row> details(Option option)
	{
		List<HoverCard.Row> rows = new ArrayList<>();
		if (option.action != null && !option.action.isEmpty())
		{
			rows.add(new HoverCard.Row("Action", option.action));
		}
		String need = requirement(option);
		rows.add(new HoverCard.Row("Requires", need.isEmpty() ? "nothing" : need));
		if (option.items != null && !option.items.isEmpty())
		{
			rows.add(new HoverCard.Row("Needs", String.join(", ", option.items)));
		}
		if (option.quests != null && !option.quests.isEmpty())
		{
			rows.add(new HoverCard.Row("Quest", String.join(", ", option.quests)));
		}
		if (option.ticks > 0)
		{
			rows.add(new HoverCard.Row("Takes", about(option.ticks)));
		}
		rows.add(new HoverCard.Row("Direction", option.oneWay ? "one way" : "both ways"));
		return rows;
	}

	private static String about(int ticks)
	{
		double seconds = ticks * SECONDS_PER_TICK;
		return seconds < 10 ? String.format(Locale.ROOT, "about %.1f seconds", seconds)
			: "about " + Math.round(seconds) + " seconds";
	}

	/**
	 * The levels a shortcut asks for, such as "5 Agility" or "11 Agility, 19 Ranged, 37 Strength", and any other need in the wiki's
	 * words after them ("86 Agility, Elite Desert Diary").
	 */
	static String requirement(Option option)
	{
		List<String> parts = new ArrayList<>();
		if (option.skills != null)
		{
			parts.addAll(option.skills);
		}
		if (option.requires != null)
		{
			parts.addAll(option.requires);
		}
		return String.join(", ", parts);
	}

	/** A name from the table with nothing in it that could be read as markup. */
	private static String clean(String text)
	{
		String name = text == null ? "" : text.replaceAll("[<>\\p{Cntrl}]", "").trim();
		return name.isEmpty() ? "Shortcut" : name;
	}

	private static File read(Gson gson)
	{
		try (InputStream in = ShortcutLoader.class.getResourceAsStream(SHORTCUTS))
		{
			if (in == null)
			{
				log.debug("World Map Plus: {} is missing from the plugin jar", SHORTCUTS);
				return null;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return gson.fromJson(reader, File.class);
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("World Map Plus: could not read {}", SHORTCUTS, e);
			return null;
		}
	}
}
