package com.worldmapplus.search;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * One thing that can be found: a place, a shop, an item sold somewhere, a quest start.
 * <p>
 * An entry knows where to take the map. Some entries, such as a monster, also carry the other
 * points they can be found at, which the viewer highlights.
 */
public final class SearchEntry
{
	private final SearchKind kind;
	private final String name;
	private final String detail;
	private final int mapId;
	private final double x;
	private final double y;
	private final List<double[]> alsoAt;
	private final String normalizedName;
	private final String normalizedDetail;
	private final long price;
	private final String category;
	/** True when the category lists "location", worked out once since a search asks it of every entry at every key press. */
	private final boolean location;
	/** Other names it is found by, normalised, such as the singular of a kind ("bank" for Banks). */
	private String[] aliases = NO_ALIASES;
	/** The kind of place this entry lists, for an entry made from the kind index, or null. */
	private String kindId;
	/** Which town of its kind this entry is, or -1 for every place of the kind. */
	private int group = -1;
	/** The town of a kind at a place, normalised, or empty. An entry with a town is only found by words for both. */
	private String place = "";
	/** What using the thing found asks of the player, or null; set once while the entries are made (round 47). */
	private com.worldmapplus.unlock.Need need;

	private static final String[] NO_ALIASES = new String[0];

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
		this.category = category == null ? "" : category;
		this.location = hasCategory(this.category, "location");
		this.kind = kind;
		this.name = name;
		this.detail = detail == null ? "" : detail;
		this.mapId = mapId;
		this.x = x;
		this.y = y;
		this.alsoAt = Collections.unmodifiableList(alsoAt);
		this.normalizedName = normalize(name);
		this.normalizedDetail = normalize(this.detail);
	}

	/**
	 * An entry for the places of one kind, made from the kind index: every place of it ({@code group} -1), or the places of it in one
	 * town ({@code group} and {@code town} given), which is only found when the words typed fit both the kind and the town.
	 */
	public static SearchEntry ofKind(String name, String detail, int mapId, double x, double y, List<double[]> tiles,
		List<String> aliases, String kindId, int group, @Nullable String town)
	{
		SearchEntry entry = new SearchEntry(SearchKind.KIND, name, detail, mapId, x, y, tiles);
		String[] own = new String[aliases.size()];
		for (int i = 0; i < own.length; i++)
		{
			own[i] = normalize(aliases.get(i));
		}
		entry.aliases = own;
		entry.kindId = kindId;
		entry.group = group;
		entry.place = town == null ? "" : normalize(town);
		return entry;
	}

	/** The id of the player's own pin or route this entry stands for, or 0 (round 48). */
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

	/** The id of the player's own pin or route, or 0 for anything else. */
	public long getOwnId()
	{
		return ownId;
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

	/** True for a named place that is only searched when places are asked for, as with type:place. */
	public boolean isLocation()
	{
		return location;
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

	public SearchKind getKind()
	{
		return kind;
	}

	public String getName()
	{
		return name;
	}

	public String getDetail()
	{
		return detail;
	}

	public int getMapId()
	{
		return mapId;
	}

	public double getX()
	{
		return x;
	}

	public double getY()
	{
		return y;
	}

	/** What the item costs where it is sold, or -1 when this is not an item with a price. */
	public long getPrice()
	{
		return price;
	}

	/** The kinds of item this is, comma separated, or empty. */
	public String getCategory()
	{
		return category;
	}

	/** Further places the thing can be found, as {x, y} pairs on the same map. */
	public List<double[]> getAlsoAt()
	{
		return alsoAt;
	}

	/** The kind of place this entry lists, or null when it is not made from the kind index. */
	@Nullable
	public String getKindId()
	{
		return kindId;
	}

	/** Which town of its kind this entry is, or -1 for every place of the kind. */
	public int getGroup()
	{
		return group;
	}

	/** What using the thing found asks of the player (levels, quests, diary tiers, quest points), or null when nothing is judged. */
	@Nullable
	public com.worldmapplus.unlock.Need getNeed()
	{
		return need;
	}

	/** Sets the need, while the entries are made and before anything else reads them. */
	public void setNeed(@Nullable com.worldmapplus.unlock.Need need)
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
