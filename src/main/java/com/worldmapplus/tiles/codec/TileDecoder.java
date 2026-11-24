package com.worldmapplus.tiles.codec;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.Arrays;

/**
 * Decodes one tile or picture from the bytes the encoder wrote. No ImageIO, no Graphics2D, no floating point.
 * <p>
 * Payload layout:
 * <pre>
 * byte 0      colours - 1 (the palette index space, 1 to 256, counting the clear colour)
 * byte 1      bit 0: index 0 is the clear colour (all channels 0). Bits 1 to 4: context table size, log2 minus 10.
 *             Other bits are reserved and must be 0.
 * bytes 2..   one arithmetic coded stream: the palette (colours in any order, as differences; the packer puts them on a short path through colour space), then the
 *             picture, unless there is only one colour, which then fills it. See {@link TileModel}.
 * </pre>
 * The input is treated as hostile. Dimensions are checked before anything is allocated, every index is clamped,
 * work is a fixed amount per pixel, a stream that runs out is detected, and any failure is a {@link CodecException}.
 * <p>
 * An instance owns working tables and is reused from one decode to the next, so a caller that decodes many tiles should
 * keep one. For the shipped data they come to about 3.3 MB. They grow with what a payload asks for: the context tables are
 * nine arrays of 2^k ints each, where k is 10 to 18 and comes from the payload (up to 9.4 MB at 18), and the image buffer is
 * about one byte per pixel (2 MB for the largest picture a file may declare). So up to about 12 MB of tables at the
 * largest sizes. The output array is four bytes per pixel on top: 8 MB for that picture, 256 KB for a tile. It is not thread safe, and holds no state shared with other instances.
 */
public final class TileDecoder
{
	/** The largest side of a picture the decoder itself will take, in pixels. A file allows much less, see below. */
	public static final int MAX_SIDE = 4096;

	/** The most pixels in one picture the decoder itself will take. */
	public static final int MAX_PIXELS = 1 << 22;

	/**
	 * The largest side of a tile a chunk file may declare. The shipped tiles are 256 by 256, so this is twice that. A header that
	 * claims more is refused before any payload is read.
	 */
	public static final int MAX_TILE_SIDE = 512;

	/**
	 * The largest side of a whole map picture an overview file may declare. The shipped ones are at most 1536 by 1088, so this
	 * leaves room to grow.
	 */
	public static final int MAX_PICTURE_SIDE = 2048;

	/**
	 * The most pixels in all a whole map picture may declare. The shipped ones are 1536 by 1088, 1.67 million, so this leaves a
	 * little room, and a forged header cannot ask for more than 8 MB of pixels and the time to decode them.
	 */
	public static final int MAX_PICTURE_PIXELS = 2_000_000;

	/** Rows coded between two looks at the clock while a picture is decoded in pieces: a few steps of the model, well under a millisecond. */
	private static final int PIECE_ROWS = 4;

	/** Reading this many bytes past the end of the input means the stream was cut short. A whole stream needs at most 3. */
	private static final int MAX_OVERREAD = 3;

	private static final DirectColorModel RGB_MODEL = new DirectColorModel(24, 0xFF0000, 0xFF00, 0xFF);
	private static final DirectColorModel ARGB_MODEL = new DirectColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), 32, 0xFF0000,
		0xFF00, 0xFF, 0xFF000000, false, DataBuffer.TYPE_INT);
	private static final int[] RGB_MASKS = {0xFF0000, 0xFF00, 0xFF};
	private static final int[] ARGB_MASKS = {0xFF0000, 0xFF00, 0xFF, 0xFF000000};

	private final Reader reader = new Reader();
	private final int[] palette = new int[TileModel.MAX_COLOURS];
	private final int[] lut = new int[TileModel.MAX_COLOURS];

	public TileDecoder()
	{
	}

	/** True when a picture of this size can be decoded. */
	public static boolean sizeAllowed(int width, int height)
	{
		return width >= 1 && height >= 1 && width <= MAX_SIDE && height <= MAX_SIDE && (long) width * height <= MAX_PIXELS;
	}

	/** True when a chunk file may declare tiles of this size: at most {@link #MAX_TILE_SIDE} each way. */
	public static boolean tileSizeAllowed(int width, int height)
	{
		return width >= 1 && height >= 1 && width <= MAX_TILE_SIDE && height <= MAX_TILE_SIDE;
	}

	/**
	 * True when a picture file may declare a picture of this size: at most {@link #MAX_PICTURE_SIDE} each way and at most
	 * {@link #MAX_PICTURE_PIXELS} pixels.
	 */
	public static boolean pictureSizeAllowed(int width, int height)
	{
		return width >= 1 && height >= 1 && width <= MAX_PICTURE_SIDE && height <= MAX_PICTURE_SIDE
			&& (long) width * height <= MAX_PICTURE_PIXELS;
	}

	/**
	 * Decodes a payload straight into the pixel array of a new image: TYPE_INT_ARGB when alpha is true, with clear
	 * pixels transparent and the others opaque, else TYPE_INT_RGB, where a clear pixel is black.
	 * <p>
	 * The image is built around the array the pixels were decoded into, without ever asking its raster for that array.
	 * Asking (DataBufferInt.getData) would mark the image as one Java2D cannot track, and an image like that is not kept
	 * in video memory, so drawing it to an accelerated window costs about three times as much as drawing a tracked one.
	 */
	public BufferedImage decodeImage(byte[] data, int off, int len, int width, int height, boolean alpha)
	{
		if (!sizeAllowed(width, height))
		{
			throw new CodecException("picture size " + width + " by " + height + " is not allowed");
		}
		int[] pixels = new int[width * height];
		decode(data, off, len, width, height, pixels);
		return wrap(pixels, width, height, alpha);
	}

	private static BufferedImage wrap(int[] pixels, int width, int height, boolean alpha)
	{
		WritableRaster raster = Raster.createPackedRaster(new DataBufferInt(pixels, pixels.length), width, height, width,
			alpha ? ARGB_MASKS : RGB_MASKS, null);
		return new BufferedImage(alpha ? ARGB_MODEL : RGB_MODEL, raster, false, null);
	}

	/**
	 * Decodes a payload into out, row by row. Each pixel is 0xFFRRGGBB, or 0 for the clear colour. That is the way both a
	 * TYPE_INT_ARGB and a TYPE_INT_RGB image hold their pixels after drawing an opaque picture into them.
	 */
	public void decode(byte[] data, int off, int len, int width, int height, int[] out)
	{
		try
		{
			if (prepare(data, off, len, width, height, out))
			{
				reader.run(null);
				reader.emit(lut, out);
			}
		}
		catch (CodecException e)
		{
			throw e;
		}
		catch (RuntimeException e)
		{
			throw new CodecException("damaged data", e);
		}
	}

	/**
	 * Checks the arguments and the header, reads the palette and fills the colour table, which is everything a decode does before
	 * the pixels. Returns true when the pixels still have to be coded, false when the picture is one colour and out is already
	 * filled with it.
	 */
	private boolean prepare(byte[] data, int off, int len, int width, int height, int[] out)
	{
		if (data == null || out == null)
		{
			throw new CodecException("no data");
		}
		if (off < 0 || len < 0 || off > data.length - len)
		{
			throw new CodecException("payload outside the data");
		}
		if (!sizeAllowed(width, height))
		{
			throw new CodecException("picture size " + width + " by " + height + " is not allowed");
		}
		if (out.length < width * height)
		{
			throw new CodecException("output array too small");
		}
		if (len < 3)
		{
			throw new CodecException("payload too short");
		}
		int colours = (data[off] & 255) + 1;
		int flags = data[off + 1] & 255;
		boolean clear = (flags & 1) != 0;
		int tbCode = (flags >> 1) & 15;
		if ((flags & 0xE0) != 0 || tbCode > TileModel.MAX_TB - TileModel.MIN_TB)
		{
			throw new CodecException("unknown payload flags");
		}
		int n = colours - (clear ? 1 : 0);
		reader.reset(width, height, colours, TileModel.MIN_TB + tbCode);
		reader.start(data, off + 2, off + len);
		reader.codePalette(palette, n);
		int o = clear ? 1 : 0;
		for (int i = 0; i < TileModel.MAX_COLOURS; i++)
		{
			lut[i] = i < colours && i >= o ? 0xFF000000 | palette[i - o] : 0;
		}
		if (colours == 1)
		{
			// One colour, so there is nothing more to decode.
			Arrays.fill(out, 0, width * height, lut[0]);
			return false;
		}
		return true;
	}

	/**
	 * Starts decoding a picture in pieces, so that a long decode can be shared out over many short tasks. The result is exactly
	 * what {@link #decodeImage} gives. The decoder belongs to the returned {@link Progress} until it is finished or dropped: using
	 * it for anything else in between spoils both. The same checks as for a whole decode are made here, before anything is
	 * allocated beyond the result.
	 */
	public Progress begin(byte[] data, int off, int len, int width, int height, boolean alpha)
	{
		if (!sizeAllowed(width, height))
		{
			throw new CodecException("picture size " + width + " by " + height + " is not allowed");
		}
		int[] pixels = new int[width * height];
		boolean more;
		try
		{
			more = prepare(data, off, len, width, height, pixels);
			if (more)
			{
				reader.startRun();
			}
		}
		catch (CodecException e)
		{
			throw e;
		}
		catch (RuntimeException e)
		{
			throw new CodecException("damaged data", e);
		}
		return new Progress(pixels, width, height, alpha, more);
	}

	/** A picture being decoded in pieces. Not thread safe, and one piece at a time. */
	public final class Progress
	{
		private final int[] pixels;
		private final int width;
		private final int height;
		private final boolean alpha;
		private boolean done;
		private int emitted;
		private BufferedImage image;

		private Progress(int[] pixels, int width, int height, boolean alpha, boolean more)
		{
			this.pixels = pixels;
			this.width = width;
			this.height = height;
			this.alpha = alpha;
			this.done = !more;
		}

		/** True when the whole picture is decoded and {@link #image()} can be asked for. */
		public boolean isDone()
		{
			return done;
		}

		/** Rows of the picture decoded so far. */
		public int rows()
		{
			return done ? height : emitted;
		}

		/**
		 * Decodes for about the given number of nanoseconds (always at least a few rows, so it always moves on) and returns true
		 * when the picture is finished. A failure is a {@link CodecException}, after which this object is spoiled.
		 */
		public boolean step(long nanos)
		{
			if (done)
			{
				return true;
			}
			try
			{
				long deadline = System.nanoTime() + nanos;
				do
				{
					reader.runRows(null, PIECE_ROWS);
				}
				while (!reader.runDone() && System.nanoTime() < deadline);
				int upTo = reader.rowsCoded();
				reader.emitRows(lut, pixels, emitted, upTo);
				emitted = upTo;
				done = reader.runDone();
				return done;
			}
			catch (CodecException e)
			{
				throw e;
			}
			catch (RuntimeException e)
			{
				throw new CodecException("damaged data", e);
			}
		}

		/** The finished picture. */
		public BufferedImage image()
		{
			if (!done)
			{
				throw new IllegalStateException("the picture is not finished");
			}
			if (image == null)
			{
				image = wrap(pixels, width, height, alpha);
			}
			return image;
		}
	}

	/** The arithmetic decoder behind the shared model. */
	private static final class Reader extends TileModel
	{
		private long x1;
		private long x2;
		private long x;
		private byte[] data;
		private int ip;
		private int end;
		private int over;

		Reader()
		{
			super(false);
		}

		void start(byte[] source, int from, int to)
		{
			data = source;
			ip = from;
			end = to;
			over = 0;
			x1 = 0;
			x2 = 0xFFFFFFFFL;
			x = 0;
			for (int i = 0; i < 4; i++)
			{
				x = (x << 8) | next();
			}
		}

		private int next()
		{
			if (ip < end)
			{
				return data[ip++] & 255;
			}
			if (++over > MAX_OVERREAD)
			{
				throw new CodecException("data ends too soon");
			}
			return 255;
		}

		@Override
		int code(int p, int ignored)
		{
			long xmid = x1 + ((x2 - x1) >>> 12) * p;
			int bit;
			if (x <= xmid)
			{
				bit = 1;
				x2 = xmid;
			}
			else
			{
				bit = 0;
				x1 = xmid + 1;
			}
			while (((x1 ^ x2) & 0xFF000000L) == 0)
			{
				x1 = (x1 << 8) & 0xFFFFFFFFL;
				x2 = ((x2 << 8) & 0xFFFFFFFFL) | 255;
				x = ((x << 8) & 0xFFFFFFFFL) | next();
			}
			return bit;
		}
	}
}
