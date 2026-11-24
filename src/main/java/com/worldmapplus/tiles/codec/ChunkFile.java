package com.worldmapplus.tiles.codec;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * A chunk: several tiles of one size in one file, each decodable without touching the others. The chunk is read
 * whole from a resource and handed to {@link #parse(byte[])}, which checks every number in it against the length of
 * the data before keeping any of it. Decoding a tile then reads only that tile's bytes.
 * <p>
 * Byte layout, all numbers unsigned, "varint" is an unsigned LEB128 number of at most 31 bits:
 * <pre>
 * 0      4 bytes  magic "WMPC"
 * 4      1 byte   format version, 3
 * 5      1 byte   reserved, 0
 * 6      u16      tile width in pixels, big endian
 * 8      u16      tile height (both at most {@link TileDecoder#MAX_TILE_SIDE})
 * 10     varint   entry count n, 1 to 4096
 *        varint   number of maps m, 1 to 255, then m varints: the map ids used by this chunk
 *        varint   origin x, varint origin y: the smallest square coordinates, which entries count from
 *        n entries, in order:
 *          u8       index into the map ids
 *          varint   x minus origin x
 *          varint   y minus origin y
 *          varint   payload length in bytes, or 0 for an alias
 *          varint   (aliases only) index of an earlier entry that has real data; this entry has the same pixels
 *        the payloads of the entries that are not aliases, back to back, in entry order
 *        u32      CRC-32 of every byte before it, big endian
 * </pre>
 * A payload is the output of the tile encoder, see {@link TileDecoder}. Map ids, x and y are at most 0xFFFFF.
 * Duplicate entries, an alias that does not point at an earlier real entry, an entry that runs past the data and
 * data left over after the last entry are all rejected, so the data of the entries is exactly what is declared.
 * <p>
 * A parsed chunk is immutable and can be shared between threads. It keeps the array it was given and does not copy
 * it, so the caller must not change that array afterwards.
 */
public final class ChunkFile
{
	/** The most entries in a chunk. */
	public static final int MAX_ENTRIES = 4096;

	/** The largest map id and square coordinate. */
	public static final int MAX_ID = 0xFFFFF;

	/** The largest chunk file that is accepted, in bytes. */
	public static final int MAX_BYTES = 64 << 20;

	static final byte[] MAGIC = {'W', 'M', 'P', 'C'};
	static final int VERSION = 3;

	/** One entry of a chunk: the map and the square its tile belongs to. */
	public static final class Entry
	{
		public final int mapId;
		public final int x;
		public final int y;

		Entry(int mapId, int x, int y)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
		}

		@Override
		public String toString()
		{
			return mapId + ":" + x + "_" + y;
		}
	}

	private final byte[] data;
	private final int width;
	private final int height;
	private final int[] mapIds;
	private final int[] xs;
	private final int[] ys;
	private final int[] starts;
	private final int[] lengths;
	private final int[] targets;
	private final Map<Long, Integer> index;

	private ChunkFile(byte[] data, int width, int height, int[] mapIds, int[] xs, int[] ys, int[] starts, int[] lengths,
		int[] targets, Map<Long, Integer> index)
	{
		this.data = data;
		this.width = width;
		this.height = height;
		this.mapIds = mapIds;
		this.xs = xs;
		this.ys = ys;
		this.starts = starts;
		this.lengths = lengths;
		this.targets = targets;
		this.index = index;
	}

	private static long key(int mapId, int x, int y)
	{
		return ((long) mapId << 40) | ((long) x << 20) | y;
	}

	private static boolean inRange(int v)
	{
		return v >= 0 && v <= MAX_ID;
	}

	/** Checks and reads a chunk, including its checksum. Throws {@link CodecException} for anything wrong with it. */
	public static ChunkFile parse(byte[] data)
	{
		return parse(data, true);
	}

	/** As {@link #parse(byte[])}, optionally without the checksum, so tests can reach the checks behind it. */
	static ChunkFile parse(byte[] data, boolean checkSum)
	{
		if (data == null || data.length < 16 || data.length > MAX_BYTES)
		{
			throw new CodecException("not a chunk: bad length");
		}
		int limit = data.length - 4;
		if (checkSum)
		{
			CRC32 crc = new CRC32();
			crc.update(data, 0, limit);
			long stored = ((data[limit] & 255L) << 24) | ((data[limit + 1] & 255) << 16) | ((data[limit + 2] & 255) << 8)
				| (data[limit + 3] & 255);
			if (crc.getValue() != stored)
			{
				throw new CodecException("chunk checksum does not match");
			}
		}
		Cursor c = new Cursor(data, limit);
		for (byte m : MAGIC)
		{
			if (c.u8() != (m & 255))
			{
				throw new CodecException("not a chunk: bad magic");
			}
		}
		if (c.u8() != VERSION)
		{
			throw new CodecException("unsupported chunk version");
		}
		if (c.u8() != 0)
		{
			throw new CodecException("unknown chunk flags");
		}
		int width = c.u16();
		int height = c.u16();
		if (!TileDecoder.tileSizeAllowed(width, height))
		{
			throw new CodecException("tile size " + width + " by " + height + " is not allowed");
		}
		int count = c.varint();
		if (count < 1 || count > MAX_ENTRIES || count > c.remaining() / 4)
		{
			throw new CodecException("bad entry count");
		}
		int mapCount = c.varint();
		if (mapCount < 1 || mapCount > 255 || mapCount > c.remaining())
		{
			throw new CodecException("bad map count");
		}
		int[] maps = new int[mapCount];
		for (int i = 0; i < mapCount; i++)
		{
			maps[i] = c.varint();
			if (!inRange(maps[i]))
			{
				throw new CodecException("map id out of range");
			}
		}
		int originX = c.varint();
		int originY = c.varint();
		if (!inRange(originX) || !inRange(originY))
		{
			throw new CodecException("origin out of range");
		}
		int[] mapIds = new int[count];
		int[] xs = new int[count];
		int[] ys = new int[count];
		int[] lengths = new int[count];
		int[] targets = new int[count];
		Map<Long, Integer> index = new HashMap<>(count * 2);
		for (int i = 0; i < count; i++)
		{
			int mi = c.u8();
			if (mi >= mapCount)
			{
				throw new CodecException("entry names a map that is not listed");
			}
			int dx = c.varint();
			int dy = c.varint();
			if (dx > MAX_ID - originX || dy > MAX_ID - originY)
			{
				throw new CodecException("square out of range");
			}
			mapIds[i] = maps[mi];
			xs[i] = originX + dx;
			ys[i] = originY + dy;
			int len = c.varint();
			if (len == 0)
			{
				int t = c.varint();
				if (t >= i || lengths[t] == 0)
				{
					throw new CodecException("alias does not point at an earlier tile");
				}
				targets[i] = t;
			}
			else
			{
				targets[i] = i;
			}
			lengths[i] = len;
			if (index.put(key(mapIds[i], xs[i], ys[i]), i) != null)
			{
				throw new CodecException("the same square is listed twice");
			}
		}
		int[] starts = new int[count];
		long at = c.position();
		for (int i = 0; i < count; i++)
		{
			starts[i] = (int) at;
			at += lengths[i];
			if (at > limit)
			{
				throw new CodecException("tile data runs past the end of the chunk");
			}
		}
		if (at != limit)
		{
			throw new CodecException("unexpected data after the last tile");
		}
		return new ChunkFile(data, width, height, mapIds, xs, ys, starts, lengths, targets, index);
	}

	/** The length of the bytes this chunk was parsed from and keeps. */
	public int byteLength()
	{
		return data.length;
	}

	/** The number of entries. */
	public int size()
	{
		return mapIds.length;
	}

	public int tileWidth()
	{
		return width;
	}

	public int tileHeight()
	{
		return height;
	}

	public int mapId(int entry)
	{
		return mapIds[entry];
	}

	public int x(int entry)
	{
		return xs[entry];
	}

	public int y(int entry)
	{
		return ys[entry];
	}

	/** True when the entry has no data of its own and shares the pixels of an earlier one. */
	public boolean isAlias(int entry)
	{
		return targets[entry] != entry;
	}

	/** The entries, in the order they are stored. */
	public List<Entry> entries()
	{
		List<Entry> list = new ArrayList<>(mapIds.length);
		for (int i = 0; i < mapIds.length; i++)
		{
			list.add(new Entry(mapIds[i], xs[i], ys[i]));
		}
		return Collections.unmodifiableList(list);
	}

	/** The index of the tile for a square, or -1 when this chunk has none. */
	public int indexOf(int mapId, int x, int y)
	{
		if (!inRange(mapId) || !inRange(x) || !inRange(y))
		{
			return -1;
		}
		Integer found = index.get(key(mapId, x, y));
		return found == null ? -1 : found;
	}

	public boolean contains(int mapId, int x, int y)
	{
		return indexOf(mapId, x, y) >= 0;
	}

	/**
	 * Decodes an entry into out, tileWidth by tileHeight pixels, row by row, as 0xFFRRGGBB with 0 for clear pixels.
	 * Reuse one decoder for many calls.
	 */
	public void decode(int entry, TileDecoder decoder, int[] out)
	{
		if (entry < 0 || entry >= mapIds.length)
		{
			throw new CodecException("no such entry");
		}
		int source = targets[entry];
		decoder.decode(data, starts[source], lengths[source], width, height, out);
	}

	/** Decodes the tile for a square into a new image, TYPE_INT_ARGB when alpha is true, else TYPE_INT_RGB. */
	public BufferedImage decodeImage(int mapId, int x, int y, boolean alpha, TileDecoder decoder)
	{
		int entry = indexOf(mapId, x, y);
		if (entry < 0)
		{
			throw new CodecException("the chunk has no tile for " + mapId + ":" + x + "_" + y);
		}
		int source = targets[entry];
		return decoder.decodeImage(data, starts[source], lengths[source], width, height, alpha);
	}

	/** As {@link #decodeImage(int, int, int, boolean, TileDecoder)} with a decoder made for this one call. */
	public BufferedImage decodeImage(int mapId, int x, int y, boolean alpha)
	{
		return decodeImage(mapId, x, y, alpha, new TileDecoder());
	}
}
