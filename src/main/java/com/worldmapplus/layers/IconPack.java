package com.worldmapplus.layers;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.annotation.*;

/**
 * The point of interest pictures, all in one jar entry ({@code icons.pack}) instead of one entry each: a jar entry costs a header of
 * about 190 bytes for these names, and there are over a hundred small PNG files. Each picture inside is the PNG file, byte for byte.
 * {@code tools/iconpack.py} writes the file and holds the same description.
 * <p>
 * Byte layout, numbers are varints (unsigned LEB128, at most 31 bits):
 * <pre>
 * 0      4 bytes  magic "WMIP"
 * 4      1 byte   version, 1
 *        varint   n, the number of pictures, 1 to {@link #MAX_FILES}
 *        n times: varint shared prefix length with the name before, varint length of the rest of the name, the rest (UTF-8, the
 *                 names sorted by their bytes so each is strictly after the one before), varint length of the picture
 *        the n pictures, back to back, in the same order
 * </pre>
 * Nothing follows the last picture. Every count and length is checked against a limit and against the bytes there are before
 * anything is made, and a file that breaks a rule is refused whole with an {@link IOException} that says which.
 */
final class IconPack
{
	static final String RESOURCE = "/com/worldmapplus/icons.pack";

	static final int MAX_BYTES = 4 << 20;
	static final int MAX_FILES = 4096;
	static final int MAX_NAME = 128;
	static final int MAX_PICTURE = 1 << 20;

	private static final byte[] MAGIC = {'W', 'M', 'I', 'P', 1};

	private final byte[] data;
	private final Map<String, int[]> places;

	private IconPack(byte[] data, Map<String, int[]> places)
	{
		this.data = data;
		this.places = places;
	}

	static IconPack empty()
	{
		return new IconPack(new byte[0], new HashMap<>());
	}

	/** The bundled pack. A missing pack is an IOException too, so the caller can say so once. */
	static IconPack bundled() throws IOException
	{
		try (InputStream in = IconPack.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IOException(RESOURCE + " is missing from the plugin jar");
			}
			byte[] buffer = new byte[Math.min(MAX_BYTES, 64 << 10) + 1];
			int length = 0;
			while (true)
			{
				if (length == buffer.length)
				{
					if (buffer.length > MAX_BYTES)
					{
						throw new IOException(RESOURCE + " is larger than any pack we write");
					}
					buffer = Arrays.copyOf(buffer, (int) Math.min((long) buffer.length * 2, MAX_BYTES + 1L));
				}
				int n = in.read(buffer, length, buffer.length - length);
				if (n < 0)
				{
					break;
				}
				length += n;
			}
			return parse(Arrays.copyOf(buffer, length));
		}
	}

	static IconPack parse(byte[] data) throws IOException
	{
		if (data.length > MAX_BYTES || data.length < MAGIC.length)
		{
			throw new IOException("icon pack has a wrong size");
		}
		for (int i = 0; i < MAGIC.length; i++)
		{
			if (data[i] != MAGIC[i])
			{
				throw new IOException("not an icon pack of this version");
			}
		}
		int[] at = {MAGIC.length};
		int count = varint(data, at);
		if (count < 1 || count > MAX_FILES)
		{
			throw new IOException("icon pack lists " + count + " pictures");
		}
		String[] names = new String[count];
		int[] lengths = new int[count];
		byte[] previous = new byte[0];
		long total = 0;
		for (int i = 0; i < count; i++)
		{
			int shared = varint(data, at);
			int rest = varint(data, at);
			if (shared > previous.length || rest < 0 || shared + (long) rest > MAX_NAME || shared + rest == 0 || rest > data.length - at[0])
			{
				throw new IOException("icon pack name " + i + " is damaged");
			}
			byte[] name = new byte[shared + rest];
			System.arraycopy(previous, 0, name, 0, shared);
			System.arraycopy(data, at[0], name, shared, rest);
			at[0] += rest;
			if (i > 0 && compare(previous, name) >= 0)
			{
				throw new IOException("icon pack names are not in order");
			}
			names[i] = new String(name, StandardCharsets.UTF_8);
			previous = name;
			lengths[i] = varint(data, at);
			if (lengths[i] < 1 || lengths[i] > MAX_PICTURE)
			{
				throw new IOException("icon pack picture " + i + " has " + lengths[i] + " bytes");
			}
			total += lengths[i];
		}
		if (total != data.length - at[0])
		{
			throw new IOException("icon pack pictures do not fill the file");
		}
		Map<String, int[]> places = new HashMap<>();
		int offset = at[0];
		for (int i = 0; i < count; i++)
		{
			places.put(names[i], new int[] {offset, lengths[i]});
			offset += lengths[i];
		}
		return new IconPack(data, places);
	}

	/** The PNG bytes of the named picture as a stream, or null when there is none. */
	@Nullable
	InputStream open(String name)
	{
		int[] place = places.get(name);
		return place == null ? null : new ByteArrayInputStream(data, place[0], place[1]);
	}

	int size()
	{
		return places.size();
	}

	private static int compare(byte[] a, byte[] b)
	{
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; i++)
		{
			int d = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (d != 0)
			{
				return d;
			}
		}
		return a.length - b.length;
	}

	private static int varint(byte[] data, int[] at) throws IOException
	{
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7)
		{
			if (at[0] >= data.length)
			{
				throw new IOException("icon pack ends inside its header");
			}
			int b = data[at[0]++] & 0xFF;
			if (shift == 28 && (b & 0xF8) != 0)
			{
				throw new IOException("icon pack number over 31 bits");
			}
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0)
			{
				return value;
			}
		}
		throw new IOException("icon pack number too long");
	}
}
