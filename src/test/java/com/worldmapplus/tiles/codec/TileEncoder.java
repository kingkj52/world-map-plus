package com.worldmapplus.tiles.codec;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes a picture into the payload {@link TileDecoder} reads. It runs the same model as the decoder, so the two stay
 * in step by construction. Deterministic: the same pixels always give the same bytes. An instance holds the same
 * working tables as a decoder and is reused from one picture to the next.
 * <p>
 * The input is the picture's pixels as 0xAARRGGBB, which is what BufferedImage.getRGB gives. Alpha must be 0 or 255,
 * and a pixel with alpha 0 must be 0 in every channel, because that is the one clear colour the decoder produces. At
 * most 256 colours, counting the clear one. Anything else is refused rather than quietly changed.
 */
public final class TileEncoder extends TileModel
{
	private long x1;
	private long x2;
	private ByteArrayOutputStream out;
	private int tableBits;

	/** Cost in bits of the palette, ordinary pixels, flat pixels, cell colours and cell flags, when {@link #collect} is set. */
	public final double[] cost = new double[5];
	public boolean collect;
	private boolean inPalette;

	public TileEncoder()
	{
		super(true);
	}

	/**
	 * Sets log2 of the context table size, from {@link TileModel#MIN_TB} to {@link TileModel#MAX_TB}, or 0 to let the
	 * encoder choose by the size of the picture.
	 */
	public TileEncoder tableBits(int bits)
	{
		if (bits != 0 && (bits < TileModel.MIN_TB || bits > TileModel.MAX_TB))
		{
			throw new IllegalArgumentException("table bits " + bits);
		}
		this.tableBits = bits;
		return this;
	}

	/** The pixels of an image as 0xAARRGGBB. */
	public static int[] pixels(BufferedImage image)
	{
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	public byte[] encode(BufferedImage image)
	{
		return encode(pixels(image), image.getWidth(), image.getHeight());
	}

	/**
	 * Weights of the red, green and blue differences in the distance used to put the palette in order. The encoder tries each and
	 * keeps the smallest result. Any order of the palette is valid for the decoder, so this costs nothing in the format.
	 */
	private static final long[][] ORDER_WEIGHTS = {{1, 1, 1}, {3, 4, 2}, {2, 4, 1}};

	private int orders = ORDER_WEIGHTS.length;

	/** How many palette orders to try, 1 to three. One is quicker and a little bigger. */
	public TileEncoder orders(int n)
	{
		if (n < 1 || n > ORDER_WEIGHTS.length)
		{
			throw new IllegalArgumentException("orders " + n);
		}
		this.orders = n;
		return this;
	}

	private static double dist(int a, int b, long[] w)
	{
		double dr = ((a >> 16) & 255) - ((b >> 16) & 255);
		double dg = ((a >> 8) & 255) - ((b >> 8) & 255);
		double db = (a & 255) - (b & 255);
		return Math.sqrt(w[0] * dr * dr + w[1] * dg * dg + w[2] * db * db);
	}

	/**
	 * The colours as a short path through colour space: from the darkest, always the nearest colour not yet used, then the
	 * path is improved by reversing stretches of it while that makes it shorter. Colours that are close in value get indices
	 * that are close, which makes both the palette and the pixels cheaper to code.
	 */
	static List<Integer> order(List<Integer> darkFirst, long[] w)
	{
		List<Integer> rest = new ArrayList<>(darkFirst);
		List<Integer> path = new ArrayList<>();
		path.add(rest.remove(0));
		while (!rest.isEmpty())
		{
			int last = path.get(path.size() - 1);
			int bi = 0;
			double bd = Double.MAX_VALUE;
			for (int i = 0; i < rest.size(); i++)
			{
				double d = dist(rest.get(i), last, w);
				if (d < bd)
				{
					bd = d;
					bi = i;
				}
			}
			path.add(rest.remove(bi));
		}
		int n = path.size();
		boolean improved = true;
		for (int rounds = 0; improved && rounds < 20; rounds++)
		{
			improved = false;
			for (int i = 0; i < n - 1; i++)
			{
				for (int j = i + 1; j < n; j++)
				{
					// Reversing path[i..j] replaces the edges (i-1,i) and (j,j+1) by (i-1,j) and (i,j+1).
					double before = 0;
					double after = 0;
					if (i > 0)
					{
						before += dist(path.get(i - 1), path.get(i), w);
						after += dist(path.get(i - 1), path.get(j), w);
					}
					if (j < n - 1)
					{
						before += dist(path.get(j), path.get(j + 1), w);
						after += dist(path.get(i), path.get(j + 1), w);
					}
					if (after < before - 1e-9)
					{
						Collections.reverse(path.subList(i, j + 1));
						improved = true;
					}
				}
			}
		}
		return path;
	}

	private static int luminance(int rgb)
	{
		return 299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255);
	}

	public byte[] encode(int[] argb, int width, int height)
	{
		if (!TileDecoder.sizeAllowed(width, height) || argb.length < width * height)
		{
			throw new IllegalArgumentException("picture size " + width + " by " + height);
		}
		boolean clear = false;
		Map<Integer, Integer> seen = new HashMap<>();
		List<Integer> colours = new ArrayList<>();
		for (int i = 0; i < width * height; i++)
		{
			int v = argb[i];
			int a = v >>> 24;
			if (a == 0)
			{
				if (v != 0)
				{
					throw new IllegalArgumentException("a clear pixel has a colour: " + Integer.toHexString(v));
				}
				clear = true;
			}
			else if (a != 255)
			{
				throw new IllegalArgumentException("partial alpha: " + Integer.toHexString(v));
			}
			else if (seen.put(v & 0xFFFFFF, 0) == null)
			{
				colours.add(v & 0xFFFFFF);
			}
		}
		colours.sort((p, q) ->
		{
			int lp = luminance(p);
			int lq = luminance(q);
			return lp != lq ? Integer.compare(lp, lq) : Integer.compare(p, q);
		});
		if (colours.size() <= 2)
		{
			return encodeOrdered(argb, width, height, colours, clear, seen);
		}
		byte[] best = null;
		for (int k = 0; k < orders; k++)
		{
			byte[] r = encodeOrdered(argb, width, height, order(colours, ORDER_WEIGHTS[k]), clear, new HashMap<>());
			if (best == null || r.length < best.length)
			{
				best = r;
			}
		}
		return best;
	}

	private byte[] encodeOrdered(int[] argb, int width, int height, List<Integer> colours, boolean clear, Map<Integer, Integer> seen)
	{
		int offset = clear ? 1 : 0;
		int ncol = colours.size() + offset;
		if (ncol < 1 || ncol > TileModel.MAX_COLOURS)
		{
			throw new IllegalArgumentException("a picture needs 1 to 256 colours, this one has " + ncol);
		}
		int[] palette = new int[colours.size()];
		for (int i = 0; i < palette.length; i++)
		{
			palette[i] = colours.get(i);
			seen.put(palette[i], i + offset);
		}
		byte[] sym = new byte[width * height];
		for (int i = 0; i < sym.length; i++)
		{
			sym[i] = (byte) ((argb[i] >>> 24) == 0 ? 0 : seen.get(argb[i] & 0xFFFFFF));
		}
		if (tableBits != 0)
		{
			return attempt(sym, palette, width, height, ncol, clear, tableBits);
		}
		if (width * height > 65536)
		{
			return attempt(sym, palette, width, height, ncol, clear, 18);
		}
		// Ordinary tiles: 2^16 entries per context, a smaller table for small results, and for big ones whichever size gives the
		// best trade of size for decoding time (see bestTable). A smaller table decodes faster.
		byte[] best = attempt(sym, palette, width, height, ncol, clear, 16);
		if (ncol == 1)
		{
			return best;
		}
		if (best.length > 4096)
		{
			return bestTable(sym, palette, width, height, ncol, clear, best);
		}
		for (int bits : new int[] {14, 12})
		{
			if (best.length < (bits == 14 ? 2048 : 600))
			{
				byte[] smaller = attempt(sym, palette, width, height, ncol, clear, bits);
				if (smaller.length <= best.length * 1.01)
				{
					best = smaller;
				}
			}
		}
		return best;
	}

	/**
	 * For a result of some size, tries tables of 2^14 to 2^17 entries per context and takes the smallest table whose result
	 * is within a small margin of the smallest result. A table that fits the processor cache decodes faster, and the larger
	 * ones only pay off a little.
	 */
	private byte[] bestTable(byte[] sym, int[] palette, int width, int height, int ncol, boolean clear, byte[] at16)
	{
		byte[] at17 = attempt(sym, palette, width, height, ncol, clear, 17);
		byte[] at15 = attempt(sym, palette, width, height, ncol, clear, 15);
		byte[] at14 = attempt(sym, palette, width, height, ncol, clear, 14);
		int smallest = Math.min(Math.min(at17.length, at16.length), Math.min(at15.length, at14.length));
		if (at14.length <= smallest * MARGIN_14)
		{
			return at14;
		}
		if (at15.length <= smallest * MARGIN_15)
		{
			return at15;
		}
		return at16.length <= smallest * MARGIN_16 ? at16 : at17;
	}

	/** How much larger than the smallest result a table of that size may give and still be taken. */
	private static final double MARGIN_14 = 1.10;
	private static final double MARGIN_15 = 1.05;
	private static final double MARGIN_16 = 1.02;

	private byte[] attempt(byte[] sym, int[] palette, int width, int height, int ncol, boolean clear, int bits)
	{
		reset(width, height, ncol, bits);
		out = new ByteArrayOutputStream();
		out.write(ncol - 1);
		out.write((clear ? 1 : 0) | ((bits - TileModel.MIN_TB) << 1));
		x1 = 0;
		x2 = 0xFFFFFFFFL;
		inPalette = true;
		codePalette(palette, palette.length);
		inPalette = false;
		if (ncol > 1)
		{
			run(sym);
		}
		out.write((int) (x1 >>> 24));
		byte[] result = out.toByteArray();
		out = null;
		return result;
	}

	@Override
	int code(int p, int bit)
	{
		if (collect)
		{
			int cat = inPalette ? 0 : group == 0 ? 1 : group == 1 ? 2 : group == 2 ? 3 : 4;
			cost[cat] -= Math.log((bit == 1 ? p : 4096 - p) / 4096.0) / Math.log(2);
		}
		long xmid = x1 + ((x2 - x1) >>> 12) * p;
		if (bit == 1)
		{
			x2 = xmid;
		}
		else
		{
			x1 = xmid + 1;
		}
		while (((x1 ^ x2) & 0xFF000000L) == 0)
		{
			out.write((int) (x2 >>> 24));
			x1 = (x1 << 8) & 0xFFFFFFFFL;
			x2 = ((x2 << 8) & 0xFFFFFFFFL) | 255;
		}
		return bit;
	}
}
