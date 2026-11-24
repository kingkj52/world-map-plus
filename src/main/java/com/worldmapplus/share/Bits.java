package com.worldmapplus.share;

import java.util.Arrays;

/**
 * Bits for share codes, most significant first, and the 10 bit checksum. A code is at most a few thousand bits, so bits
 * are kept one to a byte: simple to check, and there is nothing to gain from packing them.
 */
final class Bits
{
	private Bits()
	{
	}

	/** CRC-10/ATM: polynomial 0x233, start 0, bits taken most significant first, no reflection, no final xor. */
	static final int CRC_POLY = 0x233;

	/** The CRC-10/ATM of bits {@code from} (inclusive) to {@code to} (exclusive), starting from {@code init}. */
	static int crc10(byte[] bits, int from, int to, int init)
	{
		int crc = init & 0x3FF;
		for (int i = from; i < to; i++)
		{
			int top = (crc >> 9 & 1) ^ bits[i];
			crc = crc << 1 & 0x3FF;
			if (top != 0)
			{
				crc ^= CRC_POLY;
			}
		}
		return crc;
	}

	/** Collects bits. */
	static final class Writer
	{
		private byte[] bits = new byte[256];
		private int size;

		/** Writes the low {@code width} bits of a value, most significant first. */
		void write(int value, int width)
		{
			for (int i = width - 1; i >= 0; i--)
			{
				if (size == bits.length)
				{
					bits = Arrays.copyOf(bits, size * 2);
				}
				bits[size++] = (byte) (value >> i & 1);
			}
		}

		int size()
		{
			return size;
		}

		byte[] toArray()
		{
			return Arrays.copyOf(bits, size);
		}
	}

	/** Reads bits, refusing to read past the end. */
	static final class Reader
	{
		private final byte[] bits;
		private int position;

		Reader(byte[] bits)
		{
			this.bits = bits;
		}

		/** Reads an unsigned number of {@code width} bits (at most 30). */
		int read(int width) throws ShareException
		{
			if (width > bits.length - position)
			{
				throw new ShareException("The code is cut short.");
			}
			int value = 0;
			for (int i = 0; i < width; i++)
			{
				value = value << 1 | bits[position++];
			}
			return value;
		}

		/** Reads a two's complement number of {@code width} bits. */
		int signed(int width) throws ShareException
		{
			int value = read(width);
			return value >= 1 << width - 1 ? value - (1 << width) : value;
		}

		int position()
		{
			return position;
		}

		int remaining()
		{
			return bits.length - position;
		}
	}
}
