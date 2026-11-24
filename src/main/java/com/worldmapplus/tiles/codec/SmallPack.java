package com.worldmapplus.tiles.codec;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * One file that holds the many small chunk files of the maps other than the ground map, so the jar pays for one entry instead of
 * about two hundred (each entry costs a header of about 150 bytes, more than many of these files are). Every chunk inside is a
 * complete chunk file, byte for byte what it would be on its own, with its own CRC-32, so {@link ChunkFile#parse(byte[])} reads it
 * and checks it exactly as before. The pack adds nothing to decoding.
 * <p>
 * Byte layout, all numbers varints (unsigned LEB128, at most 31 bits):
 * <pre>
 * 0      4 bytes  magic "WMPK"
 * 4      1 byte   version, 1
 *        varint   n, the number of chunk files, 1 to {@link #MAX_FILES}
 *        n times: varint map id minus the one before (the first counts from 0; ids strictly rising, so every delta after the
 *                 first is at least 1), varint length of that chunk file in bytes (1 to {@link #MAX_FILE_BYTES})
 *        the n chunk files, back to back, in the same order
 * </pre>
 * The map id is the base map of the chunk file's name ({@code 12.wmc} is map 12). Nothing follows the last chunk file. Reading
 * the header costs a few hundred bytes of the stream; reading one chunk file then skips to its place and reads just its bytes.
 */
public final class SmallPack
{
	public static final String NAME = "small.pack";

	/** Chunk files below this many bytes are packed by the packer. */
	public static final int PACK_BELOW = 8192;

	public static final int VERSION = 1;
	public static final int MAX_FILES = 4096;
	public static final int MAX_FILE_BYTES = 1 << 20;
	public static final int MAX_MAP = ChunkFile.MAX_ID;

	/** Most bytes a pack may hold in all, so a damaged header cannot ask for an offset beyond any real file. */
	public static final int MAX_TOTAL_BYTES = 16 << 20;

	private static final byte[] MAGIC = {'W', 'M', 'P', 'K'};

	private SmallPack()
	{
	}

	/** Where each chunk file of a pack is. Immutable. */
	public static final class Index
	{
		private final int[] maps;
		private final int[] offsets;
		private final int[] lengths;

		Index(int[] maps, int[] offsets, int[] lengths)
		{
			this.maps = maps;
			this.offsets = offsets;
			this.lengths = lengths;
		}

		public int size()
		{
			return maps.length;
		}

		public int map(int i)
		{
			return maps[i];
		}

		public int offset(int i)
		{
			return offsets[i];
		}

		public int length(int i)
		{
			return lengths[i];
		}

		/** The position of that map's chunk file in the pack, or -1 when the pack has none. */
		public int find(int map)
		{
			int low = 0;
			int high = maps.length - 1;
			while (low <= high)
			{
				int mid = (low + high) >>> 1;
				if (maps[mid] < map)
				{
					low = mid + 1;
				}
				else if (maps[mid] > map)
				{
					high = mid - 1;
				}
				else
				{
					return mid;
				}
			}
			return -1;
		}
	}

	/** The bytes of a pack of these chunk files, by map id in rising order. */
	public static byte[] build(Map<Integer, byte[]> files)
	{
		if (files.isEmpty() || files.size() > MAX_FILES)
		{
			throw new IllegalArgumentException("a pack holds 1 to " + MAX_FILES + " files, not " + files.size());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(MAGIC, 0, MAGIC.length);
		out.write(VERSION);
		varint(out, files.size());
		int previous = -1;
		for (Map.Entry<Integer, byte[]> e : files.entrySet())
		{
			int map = e.getKey();
			if (map <= previous || map < 0 || map > MAX_MAP)
			{
				throw new IllegalArgumentException("map ids must rise and stay within range: " + map);
			}
			int length = e.getValue().length;
			if (length < 1 || length > MAX_FILE_BYTES)
			{
				throw new IllegalArgumentException("chunk file of map " + map + " has " + length + " bytes");
			}
			varint(out, previous < 0 ? map : map - previous);
			varint(out, length);
			previous = map;
		}
		for (byte[] bytes : files.values())
		{
			out.write(bytes, 0, bytes.length);
		}
		return out.toByteArray();
	}

	private static void varint(ByteArrayOutputStream out, int value)
	{
		while (value >= 0x80)
		{
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	/** Reads the header from the start of a stream, which is left wherever the header ended. Throws for anything a packer would not write. */
	public static Index readIndex(InputStream stream) throws IOException
	{
		Counting in = new Counting(new BufferedInputStream(stream, 2048));
		for (int i = 0; i < MAGIC.length; i++)
		{
			if (in.read() != (MAGIC[i] & 0xFF))
			{
				throw new IOException("not a chunk pack");
			}
		}
		if (in.read() != VERSION)
		{
			throw new IOException("chunk pack of another version");
		}
		int count = in.varint();
		if (count < 1 || count > MAX_FILES)
		{
			throw new IOException("chunk pack lists " + count + " files");
		}
		int[] maps = new int[count];
		int[] lengths = new int[count];
		long total = 0;
		int map = 0;
		for (int i = 0; i < count; i++)
		{
			int delta = in.varint();
			if (i > 0 && delta < 1)
			{
				throw new IOException("chunk pack map ids do not rise");
			}
			map += delta;
			if (map < 0 || map > MAX_MAP)
			{
				throw new IOException("chunk pack map id out of range");
			}
			int length = in.varint();
			if (length < 1 || length > MAX_FILE_BYTES)
			{
				throw new IOException("chunk pack file length " + length);
			}
			total += length;
			if (total > MAX_TOTAL_BYTES)
			{
				throw new IOException("chunk pack is larger than any we write");
			}
			maps[i] = map;
			lengths[i] = length;
		}
		int[] offsets = new int[count];
		int at = in.consumed;
		for (int i = 0; i < count; i++)
		{
			offsets[i] = at;
			at += lengths[i];
		}
		return new Index(maps, offsets, lengths);
	}

	/**
	 * The bytes of one chunk file: reads a stream from its start, skips to the offset and reads the length. Null when the stream ends
	 * before the file does.
	 */
	@Nullable
	public static byte[] read(InputStream in, int offset, int length) throws IOException
	{
		byte[] scratch = new byte[(int) Math.min(Math.max(offset, 1), 16384)];
		long left = offset;
		while (left > 0)
		{
			int n = in.read(scratch, 0, (int) Math.min(left, scratch.length));
			if (n < 0)
			{
				return null;
			}
			left -= n;
		}
		byte[] out = new byte[length];
		int at = 0;
		while (at < length)
		{
			int n = in.read(out, at, length - at);
			if (n < 0)
			{
				return null;
			}
			at += n;
		}
		return out;
	}

	/** A stream that counts what has been read and reads varints. */
	private static final class Counting
	{
		private final InputStream in;
		int consumed;

		Counting(InputStream in)
		{
			this.in = in;
		}

		int read() throws IOException
		{
			int b = in.read();
			if (b < 0)
			{
				throw new IOException("chunk pack ends inside its header");
			}
			consumed++;
			return b;
		}

		int varint() throws IOException
		{
			int value = 0;
			for (int shift = 0; shift < 35; shift += 7)
			{
				int b = read();
				if (shift == 28 && (b & 0xF8) != 0)
				{
					throw new IOException("number over 31 bits");
				}
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0)
				{
					return value;
				}
			}
			throw new IOException("number too long");
		}
	}
}
