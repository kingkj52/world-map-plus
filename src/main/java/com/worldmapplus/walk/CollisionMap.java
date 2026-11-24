package com.worldmapplus.walk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Which steps are possible between tiles, read from the walking data of the Shortest Path plugin.
 * <p>
 * The data is two bits per tile and floor: whether a step north is possible and whether a step east is.
 * Everything else follows from those two, the way that plugin works it out. This is a port of its reading
 * and of its step rules (Shortest Path is BSD 2-Clause, see NOTICE), so a way found here is a way that plugin
 * would also walk.
 * <p>
 * The file is read the first time it is needed and not before. A map that is never asked for a walking route
 * never loads it.
 */
@Slf4j
public final class CollisionMap
{
	private static final String RESOURCE = "/com/worldmapplus/collision-map.zip";

	/** Tiles along the side of a region. */
	static final int REGION_SIZE = 64;

	/** Regions along one side of the table. The game's world is well inside 256 regions each way. */
	private static final int REGIONS = 256;

	private static final int FLAGS_PER_TILE = 2;
	private static final int BITS_PER_PLANE = REGION_SIZE * REGION_SIZE * FLAGS_PER_TILE;

	/** Region data by region, each a bit set stored as bytes, least significant bit first. Null for no data. */
	private final byte[][] regions;

	/** For tests: regions given by (x shifted left sixteen bits) or y of the region. */
	CollisionMap(Map<Integer, byte[]> given)
	{
		this();
		for (Map.Entry<Integer, byte[]> entry : given.entrySet())
		{
			int slot = slotOfRegion(entry.getKey() >>> 16, entry.getKey() & 0xFFFF);
			if (slot >= 0)
			{
				regions[slot] = entry.getValue();
			}
		}
	}

	private CollisionMap()
	{
		this.regions = new byte[REGIONS * REGIONS][];
	}

	/** Reads the bundled data. An unreadable file gives an empty map, in which nothing can be walked. */
	public static CollisionMap load()
	{
		CollisionMap map = new CollisionMap();
		try (InputStream in = CollisionMap.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.debug("World Map Plus: no {}", RESOURCE);
				return map;
			}
			try (ZipInputStream zip = new ZipInputStream(in))
			{
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null)
				{
					String[] parts = entry.getName().split("_");
					if (parts.length != 2)
					{
						continue;
					}
					try
					{
						int slot = slotOfRegion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
						byte[] data = zip.readAllBytes();
						if (slot >= 0)
						{
							map.regions[slot] = data;
						}
					}
					catch (NumberFormatException e)
					{
						// Not a region file.
					}
				}
			}
		}
		catch (IOException e)
		{
			log.debug("World Map Plus: could not read {}", RESOURCE, e);
			return new CollisionMap();
		}
		return map;
	}

	private static int slotOfRegion(int regionX, int regionY)
	{
		return regionX < 0 || regionY < 0 || regionX >= REGIONS || regionY >= REGIONS ? -1 : regionX * REGIONS + regionY;
	}

	private byte[] regionOf(int x, int y)
	{
		if (x < 0 || y < 0)
		{
			return null;
		}
		int slot = slotOfRegion(x / REGION_SIZE, y / REGION_SIZE);
		return slot < 0 ? null : regions[slot];
	}

	/** True when there is walking data for the region a tile is in. */
	public boolean knows(int x, int y)
	{
		return regionOf(x, y) != null;
	}

	private boolean get(int x, int y, int z, int flag)
	{
		if (z < 0)
		{
			return false;
		}
		byte[] data = regionOf(x, y);
		if (data == null)
		{
			return false;
		}
		int bit = (z * REGION_SIZE * REGION_SIZE + (y & (REGION_SIZE - 1)) * REGION_SIZE
			+ (x & (REGION_SIZE - 1))) * FLAGS_PER_TILE + flag;
		int index = bit >> 3;
		return index < data.length && (data[index] >> (bit & 7) & 1) != 0;
	}

	/** A step north from this tile is possible. */
	public boolean n(int x, int y, int z)
	{
		return get(x, y, z, 0);
	}

	public boolean s(int x, int y, int z)
	{
		return n(x, y - 1, z);
	}

	public boolean e(int x, int y, int z)
	{
		return get(x, y, z, 1);
	}

	public boolean w(int x, int y, int z)
	{
		return e(x - 1, y, z);
	}

	boolean ne(int x, int y, int z)
	{
		return n(x, y, z) && e(x, y + 1, z) && e(x, y, z) && n(x + 1, y, z);
	}

	boolean nw(int x, int y, int z)
	{
		return n(x, y, z) && w(x, y + 1, z) && w(x, y, z) && n(x - 1, y, z);
	}

	boolean se(int x, int y, int z)
	{
		return s(x, y, z) && e(x, y - 1, z) && e(x, y, z) && s(x + 1, y, z);
	}

	boolean sw(int x, int y, int z)
	{
		return s(x, y, z) && w(x, y - 1, z) && w(x, y, z) && s(x - 1, y, z);
	}

	/** True when no step out of the tile is possible in any of the four directions. */
	public boolean isBlocked(int x, int y, int z)
	{
		return !n(x, y, z) && !s(x, y, z) && !e(x, y, z) && !w(x, y, z);
	}

	/** Bits in one floor of a region. For tests. */
	static int bitsPerPlane()
	{
		return BITS_PER_PLANE;
	}
}
