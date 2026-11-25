package com.worldmapplus.tiles;

import com.google.gson.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/**
 * The list of map packs a release holds, read from the release's {@code manifest.json}. Every pack has a name, an exact size, a
 * SHA-256 and the squares it covers (rectangles, per map). Nothing from the manifest is used before it has been checked here;
 * {@link #parse} throws IllegalArgumentException for anything that is not exactly what the format allows.
 */
final class MapManifest
{
	static final int FORMAT = 1;
	static final int MAX_BYTES = 1 << 20;
	static final int MAX_PACKS = 1000;
	static final int MAX_AREAS = 2048;
	static final long MAX_PACK_BYTES = 16L << 20;
	private static final int MAX_AREA_SQUARES = 1 << 16;
	private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}\\.zip");
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

	/** One pack. The first four fields are filled by Gson (the transient ones never are), the rest is what the plugin knows about it, guarded by its owner's lock. */
	static final class Pack
	{
		String name;
		long size;
		String sha256;
		int[][] areas;
		/** The name the pack is kept under on disk: the hash is in it, so a changed pack is a different file. */
		transient String file;
		transient boolean onDisk;
		transient boolean flying;
		transient boolean dead;
		transient int failures;
		transient long retryAt;
		transient long askedAt;
		transient double distance;
	}

	int format;
	int version;
	Pack[] packs;
	/** Sorted square keys of {@link #squareKey} and the index in {@link #packs} of the pack holding each. */
	private transient long[] keys;
	private transient int[] owners;

	static long squareKey(int mapId, int x, int y)
	{
		return ((long) mapId << 40) | ((long) x << 20) | y;
	}

	/** Reads and checks a manifest. The squares each pack covers are the squares of the index inside its rectangles. */
	static MapManifest parse(Gson gson, byte[] bytes, TileIndex index)
	{
		if (bytes.length > MAX_BYTES)
		{
			throw new IllegalArgumentException("manifest too large");
		}
		MapManifest m = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), MapManifest.class);
		if (m == null || m.format != FORMAT || m.packs == null || m.packs.length > MAX_PACKS)
		{
			throw new IllegalArgumentException("not a manifest of format " + FORMAT);
		}
		TreeMap<Long, Integer> found = new TreeMap<>();
		Set<String> names = new HashSet<>();
		for (int i = 0; i < m.packs.length; i++)
		{
			Pack p = m.packs[i];
			if (p == null || p.name == null || !NAME.matcher(p.name).matches() || !names.add(p.name) || p.sha256 == null
				|| !HASH.matcher(p.sha256).matches() || p.size < 1 || p.size > MAX_PACK_BYTES || p.areas == null
				|| p.areas.length > MAX_AREAS)
			{
				throw new IllegalArgumentException("bad pack entry " + i);
			}
			p.file = p.name.substring(0, p.name.length() - 4) + "-" + p.sha256.substring(0, 12) + ".zip";
			for (int[] a : p.areas)
			{
				if (a == null || a.length != 5 || a[0] < 0 || a[1] < 0 || a[2] < 0 || a[3] < a[1] || a[4] < a[2] || a[3] > 0xFFFFF
					|| a[4] > 0xFFFFF || a[0] > 0xFFFFF || (long) (a[3] - a[1] + 1) * (a[4] - a[2] + 1) > MAX_AREA_SQUARES)
				{
					throw new IllegalArgumentException("bad area in pack " + p.name);
				}
				for (int x = a[1]; x <= a[3]; x++)
				{
					for (int y = a[2]; y <= a[4]; y++)
					{
						if (index.exists(a[0], x, y) && found.put(squareKey(a[0], x, y), i) != null)
						{
							throw new IllegalArgumentException("a square is in two packs");
						}
					}
				}
			}
		}
		m.keys = new long[found.size()];
		m.owners = new int[found.size()];
		int at = 0;
		for (Map.Entry<Long, Integer> entry : found.entrySet())
		{
			m.keys[at] = entry.getKey();
			m.owners[at++] = entry.getValue();
		}
		return m;
	}

	/** The pack holding a square, or null. Allocates nothing. */
	Pack find(int mapId, int x, int y)
	{
		if (mapId < 0 || mapId > 0xFFFFF || x < 0 || x > 0xFFFFF || y < 0 || y > 0xFFFFF)
		{
			return null;
		}
		int at = Arrays.binarySearch(keys, squareKey(mapId, x, y));
		return at < 0 ? null : packs[owners[at]];
	}
}
