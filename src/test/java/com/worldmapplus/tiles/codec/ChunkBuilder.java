package com.worldmapplus.tiles.codec;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Writes a chunk file ({@link ChunkFile}) or a picture file ({@link ImageFile}). Deterministic: the entries are sorted
 * by map and square, so the bytes depend only on what was added, not on the order it was added in. A tile whose pixels
 * equal those of an earlier entry is stored as an alias of it.
 */
public final class ChunkBuilder
{
	private static final class Item
	{
		final int mapId;
		final int x;
		final int y;
		final int[] pixels;
		byte[] payload;
		int alias = -1;

		Item(int mapId, int x, int y, int[] pixels)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.pixels = pixels;
		}
	}

	/** What building a chunk produced, for reports. */
	public static final class Result
	{
		public byte[] bytes;
		public int entries;
		public int aliases;
		public long payloadBytes;
		/** Map id and payload size of each entry, in stored order. An alias has size 0. */
		public int[] mapIds;
		public int[] sizes;
	}

	private final int width;
	private final int height;
	private final boolean dedupe;
	private final List<Item> items = new ArrayList<>();

	public ChunkBuilder(int width, int height)
	{
		this(width, height, true);
	}

	public ChunkBuilder(int width, int height, boolean dedupe)
	{
		this.width = width;
		this.height = height;
		this.dedupe = dedupe;
	}

	/** Adds the pixels (0xAARRGGBB, width by height) of a tile. A square can only be added once. */
	public void add(int mapId, int x, int y, int[] pixels)
	{
		if (pixels.length != width * height)
		{
			throw new IllegalArgumentException("tile has " + pixels.length + " pixels, the chunk wants " + width * height);
		}
		items.add(new Item(mapId, x, y, pixels));
	}

	public int size()
	{
		return items.size();
	}

	private static void varint(ByteArrayOutputStream o, int v)
	{
		while (v >= 128)
		{
			o.write((v & 127) | 128);
			v >>>= 7;
		}
		o.write(v);
	}

	public byte[] build(TileEncoder encoder)
	{
		return buildResult(encoder).bytes;
	}

	public Result buildResult(TileEncoder encoder)
	{
		if (items.isEmpty() || items.size() > ChunkFile.MAX_ENTRIES)
		{
			throw new IllegalStateException(items.size() + " entries");
		}
		List<Item> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.<Item>comparingInt(i -> i.mapId).thenComparingInt(i -> i.x).thenComparingInt(i -> i.y));
		Map<Long, List<Integer>> byHash = new HashMap<>();
		Result result = new Result();
		result.entries = sorted.size();
		TreeSet<Integer> mapSet = new TreeSet<>();
		int originX = Integer.MAX_VALUE;
		int originY = Integer.MAX_VALUE;
		for (int i = 0; i < sorted.size(); i++)
		{
			Item item = sorted.get(i);
			mapSet.add(item.mapId);
			originX = Math.min(originX, item.x);
			originY = Math.min(originY, item.y);
			if (i > 0 && sorted.get(i - 1).mapId == item.mapId && sorted.get(i - 1).x == item.x && sorted.get(i - 1).y == item.y)
			{
				throw new IllegalStateException("square added twice: " + item.mapId + ":" + item.x + "_" + item.y);
			}
			if (dedupe)
			{
				long hash = Arrays.hashCode(item.pixels);
				List<Integer> same = byHash.computeIfAbsent(hash, k -> new ArrayList<>(1));
				for (int j : same)
				{
					if (Arrays.equals(sorted.get(j).pixels, item.pixels))
					{
						item.alias = j;
						break;
					}
				}
				if (item.alias < 0)
				{
					same.add(i);
				}
			}
			if (item.alias < 0)
			{
				item.payload = encoder.encode(item.pixels, width, height);
				result.payloadBytes += item.payload.length;
			}
			else
			{
				result.aliases++;
			}
		}
		List<Integer> maps = new ArrayList<>(mapSet);
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.write(ChunkFile.MAGIC, 0, 4);
		o.write(ChunkFile.VERSION);
		o.write(0);
		o.write(width >> 8);
		o.write(width);
		o.write(height >> 8);
		o.write(height);
		varint(o, sorted.size());
		varint(o, maps.size());
		for (int m : maps)
		{
			varint(o, m);
		}
		varint(o, originX);
		varint(o, originY);
		for (Item item : sorted)
		{
			o.write(maps.indexOf(item.mapId));
			varint(o, item.x - originX);
			varint(o, item.y - originY);
			if (item.alias < 0)
			{
				varint(o, item.payload.length);
			}
			else
			{
				varint(o, 0);
				varint(o, item.alias);
			}
		}
		for (Item item : sorted)
		{
			if (item.alias < 0)
			{
				o.write(item.payload, 0, item.payload.length);
			}
		}
		result.mapIds = new int[sorted.size()];
		result.sizes = new int[sorted.size()];
		for (int i = 0; i < sorted.size(); i++)
		{
			result.mapIds[i] = sorted.get(i).mapId;
			result.sizes[i] = sorted.get(i).payload == null ? 0 : sorted.get(i).payload.length;
		}
		result.bytes = withChecksum(o.toByteArray());
		return result;
	}

	/** The bytes followed by their CRC-32. */
	static byte[] withChecksum(byte[] body)
	{
		CRC32 crc = new CRC32();
		crc.update(body, 0, body.length);
		long v = crc.getValue();
		byte[] all = Arrays.copyOf(body, body.length + 4);
		all[body.length] = (byte) (v >> 24);
		all[body.length + 1] = (byte) (v >> 16);
		all[body.length + 2] = (byte) (v >> 8);
		all[body.length + 3] = (byte) v;
		return all;
	}

	/** A picture file for one picture. */
	public static byte[] imageFile(int[] pixels, int width, int height, TileEncoder encoder)
	{
		byte[] payload = encoder.encode(pixels, width, height);
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.write(ImageFile.MAGIC, 0, 4);
		o.write(ImageFile.VERSION);
		o.write(0);
		o.write(width >> 8);
		o.write(width);
		o.write(height >> 8);
		o.write(height);
		o.write(payload, 0, payload.length);
		return withChecksum(o.toByteArray());
	}
}
