package com.worldmapplus.drops;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Writes the drops format the way {@code tools/build_drops.py} does, for the round trip and damage tests. Test code only: the plugin
 * never writes the file.
 */
public final class DropFileWriter
{
	public static final class Rarity
	{
		final int head;
		final long numerator;
		final long denominator;

		public Rarity(int head, long numerator, long denominator)
		{
			this.head = head;
			this.numerator = numerator;
			this.denominator = denominator;
		}
	}

	public static final class Row
	{
		final int item;
		final int quantity;
		final int rarity;
		final int flags;
		final int rolls;

		public Row(int item, int quantity, int rarity, int flags, int rolls)
		{
			this.item = item;
			this.quantity = quantity;
			this.rarity = rarity;
			this.flags = flags;
			this.rolls = rolls;
		}
	}

	public static final class Source
	{
		final String name;
		final int combatLow;
		final int combatHigh;
		public final List<Row> rows = new ArrayList<>();

		public Source(String name, int combatLow, int combatHigh)
		{
			this.name = name;
			this.combatLow = combatLow;
			this.combatHigh = combatHigh;
		}
	}

	public final List<String> items = new ArrayList<>();
	public final List<Integer> ids = new ArrayList<>();
	public final List<int[]> quantities = new ArrayList<>();
	public final List<Rarity> rarities = new ArrayList<>();
	public final List<Source> sources = new ArrayList<>();
	String fetched = "2026-11-05";

	/** The inflated bytes. Items and sources must be added sorted. */
	byte[] raw()
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('W');
		out.write('M');
		out.write('D');
		out.write('1');
		string(out, fetched);
		varint(out, items.size());
		frontCoded(out, items);
		for (int id : ids)
		{
			varint(out, id < 0 ? 0 : id + 1);
		}
		varint(out, quantities.size());
		for (int[] q : quantities)
		{
			varint(out, q[0]);
			varint(out, q[1] - q[0]);
		}
		varint(out, rarities.size());
		for (Rarity r : rarities)
		{
			varint(out, r.head);
			if ((r.head & 7) == 0)
			{
				varint(out, r.numerator);
				varint(out, r.denominator);
			}
		}
		varint(out, sources.size());
		byte[] previous = new byte[0];
		for (Source s : sources)
		{
			previous = frontCodedOne(out, previous, s.name);
			varint(out, s.combatLow < 0 ? 0 : s.combatLow + 1);
			varint(out, s.combatLow < 0 ? 0 : s.combatHigh - s.combatLow);
			varint(out, s.rows.size());
			int last = 0;
			for (Row r : s.rows)
			{
				varint(out, r.item - last);
				last = r.item;
				varint(out, r.quantity);
				varint(out, r.rarity);
				varint(out, r.flags);
				if ((r.flags & DropTables.ROLLS) != 0)
				{
					varint(out, r.rolls);
				}
			}
		}
		return out.toByteArray();
	}

	public byte[] packed()
	{
		return deflate(raw());
	}

	static byte[] deflate(byte[] raw)
	{
		Deflater deflater = new Deflater(9, true);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (!deflater.finished())
		{
			int n = deflater.deflate(buffer);
			out.write(buffer, 0, n);
		}
		deflater.end();
		return out.toByteArray();
	}

	static void varint(ByteArrayOutputStream out, long value)
	{
		while (true)
		{
			int b = (int) (value & 0x7F);
			value >>>= 7;
			if (value != 0)
			{
				out.write(b | 0x80);
			}
			else
			{
				out.write(b);
				return;
			}
		}
	}

	private static void string(ByteArrayOutputStream out, String text)
	{
		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		varint(out, data.length);
		out.write(data, 0, data.length);
	}

	private static void frontCoded(ByteArrayOutputStream out, List<String> names)
	{
		byte[] previous = new byte[0];
		for (String name : names)
		{
			previous = frontCodedOne(out, previous, name);
		}
	}

	private static byte[] frontCodedOne(ByteArrayOutputStream out, byte[] previous, String name)
	{
		byte[] data = name.getBytes(StandardCharsets.UTF_8);
		int shared = 0;
		while (shared < Math.min(previous.length, data.length) && previous[shared] == data[shared])
		{
			shared++;
		}
		varint(out, shared);
		varint(out, data.length - shared);
		out.write(data, shared, data.length - shared);
		return data;
	}
}
