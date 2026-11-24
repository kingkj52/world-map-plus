package com.worldmapplus.data;

import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the bundled spawn data into search entries: where monsters and NPCs are found, where free
 * items lie on the ground, where quests start and where music tracks unlock.
 * <p>
 * Every entry for a creature is one place it is found, with all that place's spawn tiles attached so
 * the map can mark each of them. This is static reference information: nothing here is read from
 * the game, and there are no respawn timers.
 */
public final class SpawnLoader
{
	private SpawnLoader()
	{
	}

	public static List<SearchEntry> load(MapCatalog catalog)
	{
		return read(SpawnFormat.bundled(), catalog);
	}

	/** The entries for already read data; none for null. */
	static List<SearchEntry> read(SpawnFile file, MapCatalog catalog)
	{
		List<SearchEntry> entries = new ArrayList<>();
		if (file == null)
		{
			return entries;
		}
		creatures(entries, file.monsters, SearchKind.MONSTER, "", catalog);
		creatures(entries, file.npcs, SearchKind.NPC, "", catalog);
		creatures(entries, file.items, SearchKind.ITEM, "Free to take. ", catalog);
		points(entries, file.quests, SearchKind.QUEST, "Quest start", catalog);
		points(entries, file.music, SearchKind.MUSIC, "Music track unlock", catalog);
		return entries;
	}

	private static void creatures(List<SearchEntry> entries, List<Named> named, SearchKind kind, String prefix,
		MapCatalog catalog)
	{
		if (named == null)
		{
			return;
		}
		for (Named creature : named)
		{
			if (creature == null || creature.name == null || creature.groups == null)
			{
				continue;
			}
			for (Group group : creature.groups)
			{
				SearchEntry entry = entry(creature.name, kind, prefix, group, catalog);
				if (entry != null)
				{
					entries.add(entry);
				}
			}
		}
	}

	/** One entry for a group of spawn tiles, on whichever map the first tile is on. */
	static SearchEntry entry(String name, SearchKind kind, String prefix, Group group, MapCatalog catalog)
	{
		if (group == null || group.pts == null || group.pts.length < 2)
		{
			return null;
		}
		MapInfo map = catalog.mapFor(group.pts[0], group.pts[1]);
		if (map == null)
		{
			return null;
		}

		List<double[]> tiles = new ArrayList<>();
		double sumX = 0;
		double sumY = 0;
		for (int i = 0; i + 1 < group.pts.length; i += 2)
		{
			if (map.contains(group.pts[i], group.pts[i + 1]))
			{
				tiles.add(new double[] {group.pts[i] + 0.5, group.pts[i + 1] + 0.5});
				sumX += group.pts[i] + 0.5;
				sumY += group.pts[i + 1] + 0.5;
			}
		}
		if (tiles.isEmpty())
		{
			return null;
		}

		// Aim at the spawn nearest the middle of the group so the view lands on a real tile.
		double cx = sumX / tiles.size();
		double cy = sumY / tiles.size();
		double[] best = tiles.get(0);
		double bestDistance = Double.MAX_VALUE;
		for (double[] tile : tiles)
		{
			double d = Math.hypot(tile[0] - cx, tile[1] - cy);
			if (d < bestDistance)
			{
				bestDistance = d;
				best = tile;
			}
		}

		String where = group.where == null ? "" : group.where.trim();
		String count = tiles.size() == 1 ? "1 spawn" : tiles.size() + " spawns";
		String detail = prefix + (where.isEmpty() ? count : where + " (" + count + ")");
		if (kind == SearchKind.ITEM && !prefix.isEmpty())
		{
			// An item lying on the ground is free to take, so it is priced at nothing and can be asked for as free.
			return new SearchEntry(kind, name, detail, map.getId(), best[0], best[1], tiles, 0, "free,ground_item");
		}
		return new SearchEntry(kind, name, detail, map.getId(), best[0], best[1], tiles);
	}

	private static void points(List<SearchEntry> entries, List<Point> list, SearchKind kind, String detail,
		MapCatalog catalog)
	{
		if (list == null)
		{
			return;
		}
		for (Point point : list)
		{
			if (point == null || point.name == null)
			{
				continue;
			}
			MapInfo map = catalog.mapFor(point.x, point.y);
			if (map != null)
			{
				entries.add(new SearchEntry(kind, point.name, detail, map.getId(), point.x + 0.5, point.y + 0.5));
			}
		}
	}

	// ------------------------------------------------------------------
	// What spawns.bin holds, filled in by SpawnFormat
	// ------------------------------------------------------------------

	static final class SpawnFile
	{
		String fetched;
		List<Named> monsters;
		List<Named> npcs;
		List<Named> items;
		List<Point> quests;
		List<Point> music;
	}

	static final class Named
	{
		String name;
		List<Group> groups;
	}

	static final class Group
	{
		String where;
		int[] pts;
	}

	static final class Point
	{
		String name;
		int x;
		int y;
	}
}
