package com.worldmapplus.tiles.codec;

import java.awt.image.BufferedImage;
import java.util.zip.CRC32;

/**
 * One whole picture in one file, for the overview of a map. Byte layout, big endian:
 * <pre>
 * 0      4 bytes  magic "WMPI"
 * 4      1 byte   format version, 3
 * 5      1 byte   reserved, 0
 * 6      u16      width in pixels
 * 8      u16      height in pixels (both at most {@link TileDecoder#MAX_PICTURE_SIDE}, and at most
 *                 {@link TileDecoder#MAX_PICTURE_PIXELS} pixels in all)
 * 10     the payload of the tile encoder (see {@link TileDecoder}), up to the last four bytes
 * end-4  u32      CRC-32 of every byte before it
 * </pre>
 * Like a chunk it is checked against its own length before use, and is immutable once parsed.
 */
public final class ImageFile
{
	/** The largest picture file that is accepted, in bytes. */
	public static final int MAX_BYTES = 64 << 20;

	static final byte[] MAGIC = {'W', 'M', 'P', 'I'};
	static final int VERSION = 3;
	static final int HEADER = 10;

	private final byte[] data;
	private final int width;
	private final int height;

	private ImageFile(byte[] data, int width, int height)
	{
		this.data = data;
		this.width = width;
		this.height = height;
	}

	/** Checks and reads a picture file, including its checksum. Throws {@link CodecException} for anything wrong with it. */
	public static ImageFile parse(byte[] data)
	{
		return parse(data, true);
	}

	/** As {@link #parse(byte[])}, optionally without the checksum, so tests can reach the checks behind it. */
	static ImageFile parse(byte[] data, boolean checkSum)
	{
		if (data == null || data.length < HEADER + 3 + 4 || data.length > MAX_BYTES)
		{
			throw new CodecException("not a picture file: bad length");
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
				throw new CodecException("picture checksum does not match");
			}
		}
		Cursor c = new Cursor(data, limit);
		for (byte m : MAGIC)
		{
			if (c.u8() != (m & 255))
			{
				throw new CodecException("not a picture file: bad magic");
			}
		}
		if (c.u8() != VERSION)
		{
			throw new CodecException("unsupported picture version");
		}
		if (c.u8() != 0)
		{
			throw new CodecException("unknown picture flags");
		}
		int width = c.u16();
		int height = c.u16();
		if (!TileDecoder.pictureSizeAllowed(width, height))
		{
			throw new CodecException("picture size " + width + " by " + height + " is not allowed");
		}
		return new ImageFile(data, width, height);
	}

	public int width()
	{
		return width;
	}

	public int height()
	{
		return height;
	}

	/** Decodes the picture into out, row by row, as 0xFFRRGGBB with 0 for clear pixels. */
	public void decode(TileDecoder decoder, int[] out)
	{
		decoder.decode(data, HEADER, data.length - 4 - HEADER, width, height, out);
	}

	/** Decodes the picture into a new image, TYPE_INT_ARGB when alpha is true, else TYPE_INT_RGB. */
	public BufferedImage decodeImage(boolean alpha, TileDecoder decoder)
	{
		return decoder.decodeImage(data, HEADER, data.length - 4 - HEADER, width, height, alpha);
	}

	/**
	 * Starts decoding the picture in pieces, see {@link TileDecoder#begin}. The result is the picture {@link #decodeImage(boolean,
	 * TileDecoder)} gives, pixel for pixel.
	 */
	public TileDecoder.Progress begin(boolean alpha, TileDecoder decoder)
	{
		return decoder.begin(data, HEADER, data.length - 4 - HEADER, width, height, alpha);
	}

	/** As {@link #decodeImage(boolean, TileDecoder)} with a decoder made for this one call. */
	public BufferedImage decodeImage(boolean alpha)
	{
		return decodeImage(alpha, new TileDecoder());
	}
}
