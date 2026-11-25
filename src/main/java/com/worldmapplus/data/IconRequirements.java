package com.worldmapplus.data;

import com.google.gson.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.unlock.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.annotation.*;
import lombok.extern.slf4j.*;

/**
 * What the wiki says an icon of the game's map asks of the player before it is any use, for the kinds whose other data says nothing:
 * dungeon entrances, minigames, agility courses, farming patches and the like. Made at build time by {@code
 * tools/build_requirements.py} into {@code requirements.json}. An entry names the kind of icon and the name of its card or of a point
 * merged into it (every icon of that kind and name), or the icon's exact place ({@code at}: map, x, y, floor).
 * <p>
 * Each entry gives what is asked in the wiki's own words ({@code requires}, all of them), or several ways of which any one is
 * enough ({@code ways}). The words are judged by the Helper; what it does not understand is left out, so the icon is never
 * held to more than the words say. The card gets a row "Requires" with the words as they are.
 */
@Slf4j
public final class IconRequirements
{
	static final String RESOURCE = "/com/worldmapplus/requirements.json";

	/** The most entries read, far more than the file holds, so a damaged file cannot fill the memory. */
	private static final int MAX_ENTRIES = 20_000;

	private static final IconRequirements NONE = new IconRequirements(Collections.emptyMap(), Collections.emptyMap());

	private final Map<String, Entry> byPlace;
	private final Map<String, Entry> byName;

	static final class File
	{
		String fetched;
		List<Entry> icons;
	}

	static final class Entry
	{
		double[] at;
		String kind;
		String name;
		String page;
		List<String> requires;
		List<List<String>> ways;
	}

	private IconRequirements(Map<String, Entry> byPlace, Map<String, Entry> byName)
	{
		this.byPlace = byPlace;
		this.byName = byName;
	}

	/** No requirements at all. */
	public static IconRequirements none()
	{
		return NONE;
	}

	static String key(int map, double x, double y, int floor)
	{
		return map + ":" + x + ":" + y + ":" + floor;
	}

	static String nameKey(String kind, String name)
	{
		return kind + "|" + name;
	}

	/** Reads the bundled file. Anything wrong gives no requirements, never an error. */
	public static IconRequirements load(Gson gson)
	{
		try (InputStream in = IconRequirements.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				return NONE;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return of(gson.fromJson(reader, File.class));
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("World Map Plus: could not read {}", RESOURCE, e);
			return NONE;
		}
	}

	static IconRequirements of(@Nullable File file)
	{
		if (file == null || file.icons == null)
		{
			return NONE;
		}
		Map<String, Entry> byPlace = new HashMap<>();
		Map<String, Entry> byName = new HashMap<>();
		for (Entry entry : file.icons)
		{
			if (byPlace.size() + byName.size() >= MAX_ENTRIES)
			{
				break;
			}
			if (entry == null || entry.kind == null || !(entry.requires != null && !entry.requires.isEmpty()
				|| entry.ways != null && !entry.ways.isEmpty()))
			{
				continue;
			}
			if (entry.at != null)
			{
				if (entry.at.length == 4)
				{
					byPlace.put(entry.kind + "@" + key((int) entry.at[0], entry.at[1], entry.at[2], (int) entry.at[3]), entry);
				}
			}
			else if (entry.name != null)
			{
				byName.put(nameKey(entry.kind, entry.name), entry);
			}
		}
		return new IconRequirements(byPlace, byName);
	}

	/** How many entries were read. */
	public int size()
	{
		return byPlace.size() + byName.size();
	}

	/** The entry for an icon: by its place, else by the name of its card, else by the name of a point merged into it. */
	@Nullable
	private Entry find(Hotspot card, String kind, int map, double x, double y, int floor, @Nullable List<IconExtra> extras)
	{
		Entry entry = byPlace.get(kind + "@" + key(map, x, y, floor));
		if (entry == null && card.getCard() != null && card.getCard().getTitle() != null)
		{
			entry = byName.get(nameKey(kind, card.getCard().getTitle()));
		}
		if (entry == null && extras != null)
		{
			for (IconExtra extra : extras)
			{
				if (extra.getName() != null)
				{
					entry = byName.get(nameKey(kind, extra.getName()));
					if (entry != null)
					{
						break;
					}
				}
			}
		}
		return entry;
	}

	/**
	 * The icon's card with what the wiki says it asks: the need, and a row "Requires" in the wiki's words. The card is returned as it
	 * was when there is no entry, when the card already asks something the plugin judges, or when the entry gives nothing that can be
	 * judged. A quest the card's icon starts is kept.
	 */
	public Hotspot apply(Hotspot card, String kind, int map, double x, double y, int floor, @Nullable List<IconExtra> extras)
	{
		Entry entry = find(card, kind, map, x, y, floor, extras);
		Need had = card.getNeed();
		if (entry == null || had != null && had.hasAlternatives())
		{
			return card;
		}
		List<List<List<String>>> ways = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		if (entry.ways != null && !entry.ways.isEmpty())
		{
			for (List<String> way : entry.ways)
			{
				ways.add(Needs.way(null, null, way));
				texts.add(way == null ? "" : String.join(", ", way));
			}
		}
		else
		{
			ways.add(Needs.way(null, null, entry.requires));
			texts.add(String.join(", ", entry.requires));
		}
		Need need = Need.anyOf(ways);
		if (need == null)
		{
			return card;
		}
		HoverCard shown = card.getCard();
		boolean hasRow = false;
		for (HoverCard.Row row : shown.getRows())
		{
			hasRow |= "Requires".equals(row.getLabel());
		}
		Hotspot result = hasRow ? card : card.withCard(shown.withRow("Requires", String.join(" or ", texts)));
		return result.withNeed(need.withStart(had));
	}
}
