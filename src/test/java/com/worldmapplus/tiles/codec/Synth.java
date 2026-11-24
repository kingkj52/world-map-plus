package com.worldmapplus.tiles.codec;

import java.util.Random;

/** Synthetic pictures for the codec tests. Every picture is 0xAARRGGBB with alpha 255, or 0 for a clear pixel. */
final class Synth
{
	static final int OPAQUE = 0xFF000000;

	private Synth()
	{
	}

	static int[] flat(int w, int h, int rgb)
	{
		int[] p = new int[w * h];
		java.util.Arrays.fill(p, OPAQUE | rgb);
		return p;
	}

	static int[] clear(int w, int h)
	{
		return new int[w * h];
	}

	/** A palette of n distinct colours, spread over the cube so brightness order differs from creation order. */
	static int colour(int i)
	{
		return OPAQUE | (((i * 37) & 255) << 16) | (((i * 91 + 13) & 255) << 8) | ((i * 151 + 77) & 255);
	}

	/** Random pixels drawn from n colours. With n up to 256, all the colours appear when the picture is big enough. */
	static int[] noise(int w, int h, int n, long seed)
	{
		Random r = new Random(seed);
		int[] p = new int[w * h];
		for (int i = 0; i < p.length; i++)
		{
			p[i] = colour(r.nextInt(n));
		}
		// make sure every colour is present, so the palette really has n entries
		for (int i = 0; i < n && i < p.length; i++)
		{
			p[i] = colour(i);
		}
		return p;
	}

	/** Noise in which some pixels are clear, so the clear colour is one of the n. */
	static int[] noiseWithClear(int w, int h, int n, long seed)
	{
		Random r = new Random(seed);
		int[] p = new int[w * h];
		for (int i = 0; i < p.length; i++)
		{
			int k = r.nextInt(n);
			p[i] = k == 0 ? 0 : colour(k);
		}
		for (int i = 0; i < n && i < p.length; i++)
		{
			p[i] = i == 0 ? 0 : colour(i);
		}
		return p;
	}

	/** A smooth ramp: brightness grows left to right and top to bottom. */
	static int[] gradient(int w, int h)
	{
		int[] p = new int[w * h];
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int v = Math.min(255, (x * 255) / Math.max(1, w - 1) / 2 + (y * 255) / Math.max(1, h - 1) / 2);
				p[y * w + x] = OPAQUE | (v << 16) | ((255 - v) << 8) | (v / 2);
			}
		}
		return p;
	}

	/** Blocks of 4 by 4 pixels in a few colours, like terrain, with some detail drawn over it. */
	static int[] terrain(int w, int h, long seed)
	{
		Random r = new Random(seed);
		int[] p = new int[w * h];
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int cell = ((x / 4) * 7 + (y / 4) * 13 + ((x / 4) * (y / 4)) % 5) % 6;
				p[y * w + x] = colour(cell);
			}
		}
		for (int i = 0; i < w * h / 40; i++)
		{
			p[r.nextInt(p.length)] = colour(6 + r.nextInt(5));
		}
		return p;
	}

	static int[] copy(int[] p)
	{
		return p.clone();
	}
}
