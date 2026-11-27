package com.worldmapplus.search;

import com.worldmapplus.unlock.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;
import net.runelite.api.Quest;

/**
 * One thing that can be found: a place, a shop, an item sold somewhere, a quest start.
 * <p>
 * An entry knows where to take the map. Some entries, such as a monster, also carry the other
 * points they can be found at, which the viewer highlights.
 */
public final class SearchEntry
{
	@Getter
	private SearchKind kind;
	@Getter
	private String name;
	@Getter
	private String detail;
	@Getter
	private int mapId;
	@Getter
	private double x;
	@Getter
	private double y;
	/** Further places the thing can be found, as {x, y} pairs on the same map. */
	@Getter
	private List<double[]> alsoAt;
	private transient String normalizedName;
	private transient String normalizedDetail;
	/** What the item costs where it is sold, or -1 when this is not an item with a price. */
	@Getter
	private long price = -1;
	/** The kinds of item this is, comma separated, or empty. */
	@Getter
	private String category;
	/** True when the category lists "location", worked out once since a search asks it of every entry at every key press. */
	@Getter
	private transient boolean location;
	/** Other names it is found by, normalised, such as the singular of a kind ("bank" for Banks). */
	private String[] aliases;
	/** The kind of place this entry lists, for an entry of every place of a kind, or null. */
	private String kindId;
	/** Which town of its kind this entry is, or -1 for every place of the kind. */
	@Getter
	private int group = -1;
	/** The town of a kind at a place, normalised, or empty. An entry with a town is only found by words for both. */
	private String place;
	/** What using the thing found asks of the player, or null; set once while the entries are read (round 47). */
	private transient Need need;
	/** The wiki page of the thing found, worked out at build time, or null when no page is certain to exist. */
	@Getter
	private String wiki;
	/** Where its need is in things.json: its card, the row of a teleport card, the game's icons of the same name nearby. */
	int thing = -1;
	String way;
	int[] icons;
	/** The game's quest of a quest start, or null. */
	@Getter
	private Quest quest;
	/**
	 * What its row in the results panel says, made at build time: the shop of an item sold, or the place of an item on the ground or a
	 * creature; null when the row says the name chosen.
	 */
	@Getter
	private String row;
	/** An item sold: its price and stock in words, as its shop's row says them; null when it is not sold. */
	@Getter
	private String terms;
	/** How many are in stock where it is sold, or -1. */
	@Getter
	private long stock = -1;
	/** True for an item lying on the ground. */
	@Getter
	private boolean ground;
	/** The first and last day an event's point is found, as yyyymmdd; 0 for no limit. */
	private int from;
	private int until;

	private static final String[] NO_ALIASES = new String[0];

	private SearchEntry()
	{
	}

	public SearchEntry(SearchKind kind, String name, @Nullable String detail, int mapId, double x, double y)
	{
		this(kind, name, detail, mapId, x, y, Collections.emptyList());
	}

	public SearchEntry(SearchKind kind, String name, @Nullable String detail, int mapId, double x, double y,
		List<double[]> alsoAt)
	{
		this(kind, name, detail, mapId, x, y, alsoAt, -1, "");
	}

	/**
	 * An entry with a price and kinds of item, for the items a shop sells.
	 *
	 * @param price what it costs, or -1 when it has no price
	 * @param category the kinds of item it is, comma separated, such as {@code melee_weapon,tool}
	 */
	public SearchEntry(SearchKind kind, String name, @Nullable String detail, int mapId, double x, double y,
		List<double[]> alsoAt, long price, String category)
	{
		this.price = price;
		this.category = category;
		this.kind = kind;
		this.name = name;
		this.detail = detail;
		this.mapId = mapId;
		this.x = x;
		this.y = y;
		this.alsoAt = alsoAt;
		ready(0);
	}

	/**
	 * Finishes an entry as made, or as read from {@code search.json}: false when it is not one, or is the point of an event that is
	 * not found on this day (written as yyyymmdd).
	 */
	boolean ready(int today)
	{
		category = category == null ? "" : category;
		location = hasCategory(category, "location");
		detail = detail == null ? "" : detail;
		alsoAt = alsoAt == null ? Collections.emptyList() : Collections.unmodifiableList(alsoAt);
		aliases = aliases == null ? NO_ALIASES : aliases;
		place = place == null ? "" : place;
		normalizedName = normalize(name);
		normalizedDetail = normalize(detail);
		return kind != null && name != null && from <= today && (until == 0 || until >= today);
	}

	/** The id of the player's own pin or route this entry stands for, or 0 (round 48). */
	@Getter
	private long ownId;

	/**
	 * An entry for one of the player's own saved places ({@link SearchKind#PIN}) or routes ({@link SearchKind#ROUTE}), which carries the
	 * id it was made from, so choosing it acts on the thing itself and two of the same name at one place stay apart.
	 */
	public static SearchEntry own(SearchKind kind, long id, String name, @Nullable String detail, int mapId, double x, double y)
	{
		SearchEntry entry = new SearchEntry(kind, name, detail, mapId, x, y);
		entry.ownId = id;
		return entry;
	}

	/** Lower case, with everything that is not a letter or digit turned into a single space. */
	public static String normalize(String text)
	{
		if (text == null)
		{
			return "";
		}
		StringBuilder out = new StringBuilder(text.length());
		boolean space = true;
		for (int i = 0; i < text.length(); i++)
		{
			char c = Character.toLowerCase(text.charAt(i));
			if (c == '\'' || c == '\u2019')
			{
				// Bob's and Bobs are the same word, so an apostrophe never splits one.
				continue;
			}
			if (Character.isLetterOrDigit(c))
			{
				out.append(c);
				space = false;
			}
			else if (!space)
			{
				out.append(' ');
				space = true;
			}
		}
		int end = out.length();
		if (end > 0 && out.charAt(end - 1) == ' ')
		{
			out.setLength(end - 1);
		}
		return out.toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * True for a place that is a name the game's own map prints, such as a region or a town. These are searched like anything else, while
	 * the many other named places wait until places are asked for.
	 */
	public boolean isAreaName()
	{
		return kind == SearchKind.PLACE && "Area name".equals(detail);
	}

	/** True when a comma separated list has the word as one of its items. Makes no garbage. */
	private static boolean hasCategory(String list, String word)
	{
		int start = 0;
		int length = list.length();
		while (start <= length)
		{
			int end = list.indexOf(',', start);
			if (end < 0)
			{
				end = length;
			}
			if (end - start == word.length() && list.startsWith(word, start))
			{
				return true;
			}
			start = end + 1;
		}
		return false;
	}

	/** The kind of place this entry lists, or null when it is not every place of a kind. */
	@Nullable
	public String getKindId()
	{
		return kindId;
	}

	/** What using the thing found asks of the player (levels, quests, diary tiers, quest points), or null when nothing is judged. */
	@Nullable
	public Need getNeed()
	{
		return need;
	}

	/** Sets the need, while the entries are made and before anything else reads them. */
	public void setNeed(@Nullable Need need)
	{
		this.need = need;
	}

	/** True for the places of one kind in one town, which need words for both the kind and the town. */
	public boolean isKindAtPlace()
	{
		return !place.isEmpty();
	}

	String[] normalizedAliases()
	{
		return aliases;
	}

	String normalizedPlace()
	{
		return place;
	}

	String normalizedName()
	{
		return normalizedName;
	}

	String normalizedDetail()
	{
		return normalizedDetail;
	}
}
