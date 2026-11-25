package com.worldmapplus.search;

import com.worldmapplus.unlock.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * One thing that can be found: a place, a shop, an item sold somewhere, a quest start.
 * <p>
 * An entry knows where to take the map. Some entries, such as a monster, also carry the other
 * points they can be found at, which the viewer highlights.
 */
public final class SearchEntry
{
	@Getter
	private final SearchKind kind;
	@Getter
	private final String name;
	@Getter
	private final String detail;
	@Getter
	private final int mapId;
	@Getter
	private final double x;
	@Getter
	private final double y;
	/** Further places the thing can be found, as {x, y} pairs on the same map. */
	@Getter
	private final List<double[]> alsoAt;
	private final String normalizedName;
	private final String normalizedDetail;
	/** The kinds of thing this is, comma separated, such as {@code location}, or empty. */
	@Getter
	private final String category;
	/** True when the category lists "location", worked out once since a search asks it of every entry at every key press. */
	@Getter
	private final boolean location;
	/** Other names a partner's result is found by, normalised, such as the singular of a kind ("bank" for Banks). */
	private String[] aliases = new String[0];
	/** The name of the game's icon category to draw at every zoom when this result is chosen (a partner's every-place-of-a-kind result), or null. */
	@Getter
	@Setter
	private String reveal;
	/** What using the thing found asks of the player, or null; set once while the entries are made (round 47). */
	private Need need;

	public SearchEntry(SearchKind kind, String name, @Nullable String detail, int mapId, double x, double y)
	{
		this(kind, name, detail, mapId, x, y, Collections.emptyList());
	}

	public SearchEntry(SearchKind kind, String name, @Nullable String detail, int mapId, double x, double y,
		List<double[]> alsoAt)
	{
		this(kind, name, detail, mapId, x, y, alsoAt, "");
	}

	/** An entry with a category, such as {@code location}, which is only searched when places are asked for. */
	public SearchEntry(SearchKind kind, String name, @Nullable String detail, int mapId, double x, double y,
		List<double[]> alsoAt, String category)
	{
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

	/** Sets the other names the result is found by (a partner's result). */
	public void setAliases(List<String> names)
	{
		aliases = new String[names.size()];
		for (int i = 0; i < aliases.length; i++)
		{
			aliases[i] = normalize(names.get(i));
		}
	}

	String[] normalizedAliases()
	{
		return aliases;
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
