package com.worldmapplus.data;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;
import org.slf4j.*;

/**
 * Reads {@code spawns.bin}, the spawn data {@code tools/spawnsbin.py} writes (its docstring is the format): a string table of place
 * labels, then creatures with their groups of delta coded tiles, then quest starts and music unlocks.
 * <p>
 * Until round 69 the plugin read the file with this class at run time; now {@code tools/build_search.py} makes the search entries
 * from it at build time, and the tests that look through the spawn records read it here.
 * <p>
 * Nothing in the file is trusted. Every count is checked against a limit before a list is made, every string length against the bytes
 * that are left, every coordinate against the map range, and anything wrong, or any byte after the last record, is refused with a
 * {@link Damaged} that says what failed. {@link #bundled()} turns that into a log line and null.
 */
final class SpawnFormat
{
	private static final Logger log = LoggerFactory.getLogger(SpawnFormat.class);

	static final String RESOURCE = "tools/input/spawns.bin";

	/** Limits, each well above what the bundled file holds. */
	static final int MAX_BYTES = 1 << 20;
	static final int MAX_LABELS = 20_000;
	static final int MAX_CREATURES = 20_000;
	static final int MAX_GROUPS = 2_000;
	static final int MAX_TILES = 5_000;
	static final int MAX_TOTAL_TILES = 500_000;
	static final int MAX_POINTS = 20_000;
	static final int MAX_STRING = 512;
	static final int MAX_COORD = (1 << 20) - 1;

	private static final byte[] MAGIC = {'W', 'M', 'S', 1};

	private SpawnFormat()
	{
	}

	/** Signals a file that is not one the tool wrote. */
	static final class Damaged extends IOException
	{
		Damaged(String message)
		{
			super(message, null);
		}

		@Override
		public synchronized Throwable fillInStackTrace()
		{
			return this;
		}
	}

	/** The bundled data, or null when it is missing or damaged. */
	@Nullable
	static SpawnFile bundled()
	{
		try (InputStream in = ToolInputs.stream("spawns.bin"))
		{
			if (in == null)
			{
				log.warn("World Map Plus: {} is missing", RESOURCE);
				return null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(112_000);
			byte[] buffer = new byte[16_384];
			int n;
			while ((n = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, n);
				if (out.size() > MAX_BYTES)
				{
					log.warn("World Map Plus: {} is larger than any file the tool writes", RESOURCE);
					return null;
				}
			}
			return decode(out.toByteArray());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("World Map Plus: could not read {}: {}", RESOURCE, e.toString());
			return null;
		}
	}

	/** The data in the bytes of a spawns file. Throws {@link Damaged} for anything the tool would not have written. */
	static SpawnFile decode(byte[] data) throws Damaged
	{
		if (data == null || data.length > MAX_BYTES)
		{
			throw new Damaged("no data or too large");
		}
		if (data.length < MAGIC.length)
		{
			throw new Damaged("shorter than the header");
		}
		for (int i = 0; i < MAGIC.length; i++)
		{
			if (data[i] != MAGIC[i])
			{
				throw new Damaged("not a spawns file of this version");
			}
		}
		Reader in = new Reader(data, MAGIC.length);
		SpawnFile file = new SpawnFile();
		file.fetched = in.string();

		int labelCount = in.bounded(MAX_LABELS, "place label count");
		String[] labels = new String[labelCount];
		byte[] previous = new byte[0];
		for (int i = 0; i < labelCount; i++)
		{
			int shared = in.bounded(previous.length, "shared prefix");
			int rest = in.bounded(MAX_STRING - shared, "label length");
			byte[] label = new byte[shared + rest];
			System.arraycopy(previous, 0, label, 0, shared);
			in.copy(label, shared, rest);
			labels[i] = new String(label, StandardCharsets.UTF_8);
			previous = label;
		}

		int[] tilesLeft = {MAX_TOTAL_TILES};
		file.monsters = in.creatures(labels, tilesLeft);
		file.npcs = in.creatures(labels, tilesLeft);
		file.items = in.creatures(labels, tilesLeft);
		file.quests = in.points();
		file.music = in.points();
		if (in.at != data.length)
		{
			throw new Damaged("bytes after the last record");
		}
		return file;
	}

	@AllArgsConstructor
	private static final class Reader
	{
		private final byte[] data;
		private int at;

		int varint() throws Damaged
		{
			int value = 0;
			for (int shift = 0; shift < 35; shift += 7)
			{
				if (at >= data.length)
				{
					throw new Damaged("ends inside a number");
				}
				int b = data[at++] & 0xFF;
				if (shift == 28 && (b & 0xF8) != 0)
				{
					throw new Damaged("number over 31 bits");
				}
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0)
				{
					return value;
				}
			}
			throw new Damaged("number too long");
		}

		int bounded(int max, String what) throws Damaged
		{
			int value = varint();
			if (value < 0 || value > max)
			{
				throw new Damaged(what + " " + value + " is over " + max);
			}
			return value;
		}

		void copy(byte[] to, int offset, int length) throws Damaged
		{
			if (length > data.length - at)
			{
				throw new Damaged("string runs past the end");
			}
			System.arraycopy(data, at, to, offset, length);
			at += length;
		}

		String string() throws Damaged
		{
			byte[] bytes = new byte[bounded(MAX_STRING, "string length")];
			copy(bytes, 0, bytes.length);
			return new String(bytes, StandardCharsets.UTF_8);
		}

		int coordinate(String what) throws Damaged
		{
			return bounded(MAX_COORD, what);
		}

		List<Named> creatures(String[] labels, int[] tilesLeft) throws Damaged
		{
			int count = bounded(MAX_CREATURES, "creature count");
			// Lists grow as records are read, so a lying count cannot make a big list before the bytes are there.
			List<Named> list = new ArrayList<>();
			for (int i = 0; i < count; i++)
			{
				Named named = new Named();
				named.name = string();
				int groups = bounded(MAX_GROUPS, "group count");
				named.groups = new ArrayList<>();
				for (int g = 0; g < groups; g++)
				{
					Group group = new Group();
					int label = bounded(labels.length - 1, "place index");
					group.where = labels[label];
					int tiles = bounded(MAX_TILES, "tile count");
					tilesLeft[0] -= tiles;
					if (tilesLeft[0] < 0)
					{
						throw new Damaged("too many tiles in all");
					}
					// Each tile is at least two bytes, so a count the rest of the file cannot hold is refused before the array.
					if (tiles > (data.length - at) / 2)
					{
						throw new Damaged("tile count runs past the end");
					}
					group.pts = new int[tiles * 2];
					int x = 0;
					int y = 0;
					for (int t = 0; t < tiles; t++)
					{
						x += unzigzag(varint());
						y += unzigzag(varint());
						if (x < 0 || x > MAX_COORD || y < 0 || y > MAX_COORD)
						{
							throw new Damaged("tile outside the map range");
						}
						group.pts[t * 2] = x;
						group.pts[t * 2 + 1] = y;
					}
					named.groups.add(group);
				}
				list.add(named);
			}
			return list;
		}

		List<Point> points() throws Damaged
		{
			int count = bounded(MAX_POINTS, "point count");
			List<Point> list = new ArrayList<>();
			for (int i = 0; i < count; i++)
			{
				Point point = new Point();
				point.name = string();
				point.x = coordinate("x");
				point.y = coordinate("y");
				list.add(point);
			}
			return list;
		}
	}

	private static int unzigzag(int n)
	{
		return (n >>> 1) ^ -(n & 1);
	}

	// What spawns.bin holds, filled in by the reader.

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
