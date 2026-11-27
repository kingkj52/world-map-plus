package com.worldmapplus.search;

import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * Every place of each kind (banks, altars, herb patches, shark fishing spots), merged per town, as {@code tools/build_search.py}
 * writes them into {@code search.json} from {@code kinds.json}.
 * <p>
 * Search has a result for every place of a kind ("Banks") and one for the places of a kind in each town ("Banks in Catherby"); this
 * says where those places are. Nothing is worked out at runtime but a lookup and, when a result is chosen, a sort by distance.
 * Immutable once loaded.
 */
public final class KindIndex
{
	/** The places of one kind on one map with one name, such as the two banks of Catherby. Filled by Gson. */
	@Getter
	public static final class Group
	{
		private int mapId;
		/** The name of the town, or "Near" a town, or the name of the map. */
		private String town;
		/** The middle of each place's tile, as {x, y} on the group's map. Never empty. */
		private List<double[]> tiles;
	}

	/** One kind of place and its groups. Filled by Gson. */
	@Getter
	public static final class Kind
	{
		private String id;
		private String singular;
		private String plural;
		/** The category of the game's icons this kind is, to reveal them, or null for a kind from the points of interest. */
		@Nullable
		private String category;
		private List<Group> groups;
		/** How many places it has in all. */
		private int total;

		/** How many of this kind, as a count of things: {@code 1 bank}, {@code 4 banks}. */
		public String count(int n)
		{
			return n + " " + (n == 1 ? singular : plural).toLowerCase(Locale.ROOT);
		}
	}

	private static final KindIndex EMPTY = new KindIndex(Collections.emptyList());

	@Getter
	private final List<Kind> kinds;
	private final Map<String, Kind> byId = new HashMap<>();

	KindIndex(List<Kind> kinds)
	{
		this.kinds = Collections.unmodifiableList(kinds);
		for (Kind kind : kinds)
		{
			byId.put(kind.id, kind);
		}
	}

	/** No kinds, for tests and for a build without the file. */
	public static KindIndex empty()
	{
		return EMPTY;
	}

	@Nullable
	public Kind byId(@Nullable String id)
	{
		return id == null ? null : byId.get(id);
	}
}
