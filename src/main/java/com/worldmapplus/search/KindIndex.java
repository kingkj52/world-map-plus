package com.worldmapplus.search;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Every place of each kind (banks, altars, herb patches, shark fishing spots), merged per town, from the bundled
 * {@code kinds.json} that {@code tools/build_kinds.py} makes from the game's icons and the points of interest.
 * <p>
 * It gives search a result for every place of a kind ("Banks") and one for the places of a kind in each town ("Banks in
 * Catherby", found only by words for both). Nothing is worked out at runtime but a lookup and, when a result is chosen, a sort by
 * distance. Immutable once loaded.
 */
@Slf4j
public final class KindIndex
{
	private static final String RESOURCE = "/com/worldmapplus/kinds.json";

	/** The places of one kind on one map with one name, such as the two banks of Catherby. */
	public static final class Group
	{
		private final int mapId;
		private final String town;
		private final List<double[]> tiles;

		Group(int mapId, String town, List<double[]> tiles)
		{
			this.mapId = mapId;
			this.town = town;
			this.tiles = Collections.unmodifiableList(tiles);
		}

		public int getMapId()
		{
			return mapId;
		}

		/** The name of the town, or "Near" a town, or the name of the map. */
		public String getTown()
		{
			return town;
		}

		/** The middle of each place's tile, as {x, y} on the group's map. Never empty. */
		public List<double[]> getTiles()
		{
			return tiles;
		}
	}

	/** One kind of place and its groups. */
	public static final class Kind
	{
		private final String id;
		private final String singular;
		private final String plural;
		private final String category;
		private final List<Group> groups;
		private final int total;

		Kind(String id, String singular, String plural, @Nullable String category, List<Group> groups)
		{
			this.id = id;
			this.singular = singular;
			this.plural = plural;
			this.category = category;
			this.groups = Collections.unmodifiableList(groups);
			int count = 0;
			for (Group group : groups)
			{
				count += group.tiles.size();
			}
			this.total = count;
		}

		public String getId()
		{
			return id;
		}

		public String getSingular()
		{
			return singular;
		}

		public String getPlural()
		{
			return plural;
		}

		/** The category of the game's icons this kind is, to reveal them, or null for a kind from the points of interest. */
		@Nullable
		public String getCategory()
		{
			return category;
		}

		public List<Group> getGroups()
		{
			return groups;
		}

		/** How many places it has in all. */
		public int getTotal()
		{
			return total;
		}

		/** How many of this kind, as a count of things: {@code 1 bank}, {@code 4 banks}. */
		public String count(int n)
		{
			return n + " " + (n == 1 ? singular : plural).toLowerCase(Locale.ROOT);
		}
	}

	private static final KindIndex EMPTY = new KindIndex(Collections.emptyList());

	private final List<Kind> kinds;
	private final Map<String, Kind> byId = new LinkedHashMap<>();

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

	public List<Kind> getKinds()
	{
		return kinds;
	}

	@Nullable
	public Kind byId(@Nullable String id)
	{
		return id == null ? null : byId.get(id);
	}

	/** Reads the bundled file. A missing or unreadable file gives no kinds. */
	public static KindIndex load(Gson gson, MapCatalog catalog)
	{
		try (InputStream in = KindIndex.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.debug("World Map Plus: no {}", RESOURCE);
				return EMPTY;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return read(gson.fromJson(reader, KindFile.class), catalog);
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("World Map Plus: could not read {}", RESOURCE, e);
			return EMPTY;
		}
	}

	static KindIndex read(@Nullable KindFile file, MapCatalog catalog)
	{
		List<Kind> kinds = new ArrayList<>();
		if (file == null || file.kinds == null)
		{
			return new KindIndex(kinds);
		}
		for (RawKind raw : file.kinds)
		{
			if (raw == null || raw.id == null || raw.s == null || raw.p == null || raw.g == null)
			{
				continue;
			}
			List<Group> groups = new ArrayList<>();
			for (RawGroup g : raw.g)
			{
				if (g == null || g.t == null || g.p == null || g.p.length < 2 || catalog.byId(g.m) == null)
				{
					continue;
				}
				List<double[]> tiles = new ArrayList<>(g.p.length / 2);
				for (int i = 0; i + 1 < g.p.length; i += 2)
				{
					tiles.add(new double[] {g.p[i] + 0.5, g.p[i + 1] + 0.5});
				}
				groups.add(new Group(g.m, g.t, tiles));
			}
			if (!groups.isEmpty())
			{
				kinds.add(new Kind(raw.id, raw.s, raw.p, raw.c, groups));
			}
		}
		return new KindIndex(kinds);
	}

	/**
	 * The search entries: one for every place of each kind, and one for each town of each kind. An entry stands at its first place;
	 * the map works out the nearest when it is chosen.
	 */
	public List<SearchEntry> entries(MapCatalog catalog)
	{
		List<SearchEntry> entries = new ArrayList<>();
		for (Kind kind : kinds)
		{
			String one = kind.singular.toLowerCase(Locale.ROOT);
			List<String> words = Arrays.asList(kind.singular, kind.plural);
			List<String> every = Arrays.asList(kind.singular, kind.plural, "every " + one, "all " + kind.plural);
			Group first = kind.groups.get(0);
			String towns = kind.groups.size() == 1 ? "1 town" : kind.groups.size() + " towns";
			String detail = "Every " + one + ": " + (kind.total == 1 ? "1 place" : kind.total + " places") + " in " + towns
				+ ", marked on the map, nearest first";
			entries.add(SearchEntry.ofKind(kind.plural, detail, first.mapId, first.tiles.get(0)[0], first.tiles.get(0)[1],
				first.tiles, every, kind.id, -1, null));
			for (int i = 0; i < kind.groups.size(); i++)
			{
				Group group = kind.groups.get(i);
				String name = group.town.startsWith("Near ") ? kind.plural + " near " + group.town.substring(5)
					: kind.plural + " in " + group.town;
				MapInfo map = catalog.byId(group.mapId);
				String where = map == null || map.getId() == MapInfo.SURFACE || map.getName().equals(group.town) ? ""
					: ", " + map.getName();
				entries.add(SearchEntry.ofKind(name, kind.count(group.tiles.size()) + where, group.mapId, group.tiles.get(0)[0],
					group.tiles.get(0)[1], group.tiles, words, kind.id, i, group.town));
			}
		}
		return entries;
	}

	// The shape of kinds.json, filled by Gson.

	static final class KindFile
	{
		List<RawKind> kinds;
	}

	static final class RawKind
	{
		String id;
		String s;
		String p;
		String c;
		List<RawGroup> g;
	}

	static final class RawGroup
	{
		int m;
		String t;
		int[] p;
	}
}
