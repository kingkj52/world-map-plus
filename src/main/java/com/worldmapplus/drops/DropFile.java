package com.worldmapplus.drops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code drops.bin}, the drop tables {@code tools/build_drops.py} writes (its docstring is the format). The file is one raw
 * DEFLATE stream of varints and short strings, with an item dictionary, quantity and rarity dictionaries and the rows of each source
 * sorted by item for delta coding.
 * <p>
 * Reading never throws and never trusts a size: every count and length is checked against a limit before anything is made, the
 * inflated size is capped, and anything wrong gives {@link DropTables#EMPTY}, so a damaged file costs the drop tabs and nothing else.
 */
public final class DropFile
{
	private static final Logger log = LoggerFactory.getLogger(DropFile.class);

	static final String RESOURCE = "/com/worldmapplus/drops.bin";

	/** Limits, each well above what the bundled file holds. */
	static final int MAX_PACKED = 1 << 20;
	static final int MAX_INFLATED = 4 << 20;
	static final int MAX_ITEMS = 20_000;
	static final int MAX_QUANTITIES = 20_000;
	static final int MAX_RARITIES = 50_000;
	static final int MAX_SOURCES = 10_000;
	static final int MAX_ROWS_PER_SOURCE = 4_000;
	static final int MAX_ROWS = 200_000;
	static final int MAX_NAME = 256;

	private static final byte[] MAGIC = {'W', 'M', 'D', '1'};

	private DropFile()
	{
	}

	/** Reads the bundled file. Never throws; a missing or damaged file gives the empty tables. */
	public static DropTables load()
	{
		try (InputStream in = DropFile.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("World Map Plus has no drop tables bundled");
				return DropTables.EMPTY;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(160_000);
			byte[] buffer = new byte[16_384];
			int n;
			while ((n = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, n);
				if (out.size() > MAX_PACKED)
				{
					return DropTables.EMPTY;
				}
			}
			return decode(out.toByteArray());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("World Map Plus could not read its drop tables", e);
			return DropTables.EMPTY;
		}
	}

	/** Reads the packed bytes of a drops file. Never throws: damaged input gives {@link DropTables#EMPTY}. */
	public static DropTables decode(byte[] packed)
	{
		if (packed == null || packed.length == 0 || packed.length > MAX_PACKED)
		{
			return DropTables.EMPTY;
		}
		try
		{
			byte[] data = inflate(packed);
			return data == null ? DropTables.EMPTY : parse(data);
		}
		catch (RuntimeException e)
		{
			return DropTables.EMPTY;
		}
	}

	/** The inflated bytes, or null when they are damaged or larger than allowed. */
	static byte[] inflate(byte[] packed)
	{
		Inflater inflater = new Inflater(true);
		try
		{
			// A raw stream may need one extra byte at its end to finish; the buffer has it.
			byte[] input = java.util.Arrays.copyOf(packed, packed.length + 1);
			inflater.setInput(input);
			ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 3);
			byte[] buffer = new byte[16_384];
			int stalls = 0;
			while (!inflater.finished())
			{
				int n = inflater.inflate(buffer);
				if (n == 0)
				{
					if (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 4)
					{
						return null;
					}
				}
				out.write(buffer, 0, n);
				if (out.size() > MAX_INFLATED)
				{
					return null;
				}
			}
			return out.toByteArray();
		}
		catch (DataFormatException e)
		{
			return null;
		}
		finally
		{
			inflater.end();
		}
	}

	/** Signals damaged input inside the parser; caught by {@link #decode}. */
	private static final class Damaged extends RuntimeException
	{
		Damaged()
		{
			super(null, null, false, false);
		}
	}

	private static final class Reader
	{
		private final byte[] data;
		private int at;

		Reader(byte[] data)
		{
			this.data = data;
		}

		int varint()
		{
			int value = 0;
			for (int shift = 0; shift < 35; shift += 7)
			{
				if (at >= data.length)
				{
					throw new Damaged();
				}
				int b = data[at++] & 0xFF;
				if (shift == 28 && (b & 0xF8) != 0)
				{
					// More than 31 bits: never written by the tool.
					throw new Damaged();
				}
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0)
				{
					return value;
				}
			}
			throw new Damaged();
		}

		int bounded(int max)
		{
			int value = varint();
			if (value < 0 || value > max)
			{
				throw new Damaged();
			}
			return value;
		}

		byte[] bytes(int length)
		{
			if (length < 0 || length > data.length - at)
			{
				throw new Damaged();
			}
			byte[] out = java.util.Arrays.copyOfRange(data, at, at + length);
			at += length;
			return out;
		}

		/** A front coded name: shared prefix length with the name before, then the rest. */
		byte[] frontCoded(byte[] previous)
		{
			int shared = bounded(previous.length);
			int rest = bounded(MAX_NAME - shared);
			if (shared + rest == 0)
			{
				throw new Damaged();
			}
			byte[] out = new byte[shared + rest];
			System.arraycopy(previous, 0, out, 0, shared);
			if (rest > data.length - at)
			{
				throw new Damaged();
			}
			System.arraycopy(data, at, out, shared, rest);
			at += rest;
			return out;
		}
	}

	static DropTables parse(byte[] data)
	{
		if (data.length < MAGIC.length)
		{
			return DropTables.EMPTY;
		}
		for (int i = 0; i < MAGIC.length; i++)
		{
			if (data[i] != MAGIC[i])
			{
				return DropTables.EMPTY;
			}
		}
		Reader in = new Reader(data);
		in.at = MAGIC.length;
		String fetched = new String(in.bytes(in.bounded(32)), StandardCharsets.UTF_8);

		int items = in.bounded(MAX_ITEMS);
		String[] itemNames = new String[items];
		byte[] previous = new byte[0];
		for (int i = 0; i < items; i++)
		{
			previous = in.frontCoded(previous);
			itemNames[i] = new String(previous, StandardCharsets.UTF_8);
		}
		int[] itemIds = new int[items];
		for (int i = 0; i < items; i++)
		{
			itemIds[i] = in.bounded(Integer.MAX_VALUE) - 1;
		}

		int quantities = in.bounded(MAX_QUANTITIES);
		int[] low = new int[quantities];
		int[] high = new int[quantities];
		for (int i = 0; i < quantities; i++)
		{
			low[i] = in.bounded(Integer.MAX_VALUE);
			int span = in.bounded(Integer.MAX_VALUE - low[i]);
			high[i] = low[i] + span;
		}

		int rarities = in.bounded(MAX_RARITIES);
		int[] head = new int[rarities];
		long[] numerator = new long[rarities];
		long[] denominator = new long[rarities];
		for (int i = 0; i < rarities; i++)
		{
			head[i] = in.bounded(31);
			if ((head[i] & 7) == DropTables.FRACTION)
			{
				numerator[i] = in.bounded(Integer.MAX_VALUE);
				denominator[i] = in.bounded(Integer.MAX_VALUE);
				if (numerator[i] == 0 || denominator[i] == 0)
				{
					throw new Damaged();
				}
			}
		}

		int sources = in.bounded(MAX_SOURCES);
		String[] sourceNames = new String[sources];
		int[] combatLow = new int[sources];
		int[] combatHigh = new int[sources];
		int[] rowStart = new int[sources + 1];
		// Rows grow as they are read, so a lying count cannot make a large array before the bytes are there.
		IntList rowItem = new IntList();
		IntList rowQuantity = new IntList();
		IntList rowRarity = new IntList();
		IntList rowFlags = new IntList();
		IntList rowRolls = new IntList();
		previous = new byte[0];
		for (int s = 0; s < sources; s++)
		{
			previous = in.frontCoded(previous);
			sourceNames[s] = new String(previous, StandardCharsets.UTF_8);
			int combat = in.bounded(10_000);
			int span = in.bounded(10_000);
			combatLow[s] = combat - 1;
			combatHigh[s] = combat == 0 ? -1 : combat - 1 + span;
			int count = in.bounded(MAX_ROWS_PER_SOURCE);
			rowStart[s] = rowItem.size;
			if (rowItem.size + count > MAX_ROWS)
			{
				throw new Damaged();
			}
			int item = 0;
			for (int r = 0; r < count; r++)
			{
				item += in.bounded(items);
				if (item >= items)
				{
					throw new Damaged();
				}
				rowItem.add(item);
				rowQuantity.add(in.bounded(quantities - 1));
				rowRarity.add(in.bounded(rarities - 1));
				int flags = in.bounded(7);
				rowFlags.add(flags);
				rowRolls.add((flags & DropTables.ROLLS) != 0 ? in.bounded(100_000) : 100);
			}
		}
		rowStart[sources] = rowItem.size;
		if (in.at != data.length)
		{
			// Bytes after the end: not a file the tool wrote.
			throw new Damaged();
		}
		return new DropTables(fetched, itemNames, itemIds, low, high, head, numerator, denominator, sourceNames, combatLow, combatHigh,
			rowStart, rowItem.toArray(), rowQuantity.toArray(), rowRarity.toArray(), rowFlags.toArray(), rowRolls.toArray());
	}

	/** A growing list of ints. */
	private static final class IntList
	{
		int[] values = new int[1024];
		int size;

		void add(int value)
		{
			if (size == values.length)
			{
				values = java.util.Arrays.copyOf(values, size * 2);
			}
			values[size++] = value;
		}

		int[] toArray()
		{
			return java.util.Arrays.copyOf(values, size);
		}
	}
}
