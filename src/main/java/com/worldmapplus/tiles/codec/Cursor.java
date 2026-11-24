package com.worldmapplus.tiles.codec;

/** A bounds checked reader over a byte array. Every failure is a {@link CodecException}. */
final class Cursor
{
	private final byte[] data;
	private final int limit;
	private int pos;

	/** Reads data[0 .. limit). */
	Cursor(byte[] data, int limit)
	{
		this.data = data;
		this.limit = limit;
	}

	int position()
	{
		return pos;
	}

	int remaining()
	{
		return limit - pos;
	}

	int u8()
	{
		if (pos >= limit)
		{
			throw new CodecException("data ends too soon");
		}
		return data[pos++] & 255;
	}

	int u16()
	{
		int hi = u8();
		return (hi << 8) | u8();
	}

	/** An unsigned LEB128 number that fits in 31 bits. */
	int varint()
	{
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7)
		{
			int b = u8();
			if (shift == 28 && (b & 0xF8) != 0)
			{
				throw new CodecException("number out of range");
			}
			value |= (b & 127) << shift;
			if (b < 128)
			{
				return value;
			}
		}
		throw new CodecException("number out of range");
	}

	void skip(int n)
	{
		if (n < 0 || n > remaining())
		{
			throw new CodecException("data ends too soon");
		}
		pos += n;
	}
}
