package com.worldmapplus.tiles.codec;

import java.util.Arrays;

/**
 * The adaptive model behind the tile codec, shared by the decoder here and the encoder in the tests so the two can
 * never drift apart. Only the arithmetic coder step differs: {@link #code(int, int)} either writes a decision or reads
 * one.
 * <p>
 * An image is a grid of palette indices. It is coded as a sequence of binary decisions, each predicted by mixing
 * the opinions of several hashed contexts (the neighbours above and to the left, plus two match models) in a small
 * logistic mixer:
 * <ul>
 * <li>Every 4 by 4 cell (one game tile) is first flagged as one single colour or not. A flagged cell then costs one
 * colour, coded like a pixel.</li>
 * <li>Other pixels are coded as "same as the west, north, north-east or north-west neighbour" (the distinct ones
 * only), and if none of those, as the index through a binary tree.</li>
 * </ul>
 * A context keeps its statistics in a table of 2^tb slots. The slots of the decisions of one pixel sit together in
 * buckets of four, found by hashing the context once, so that the first decision of a pixel brings in the cache line the
 * later ones use: the candidate flags share one bucket and the tree uses one bucket per two levels. Contexts that
 * saved next to nothing in size were dropped, since each one costs time for every decision.
 * Everything is integer arithmetic with fixed tables, so a decode gives the same result on every JVM. The work per
 * pixel is bounded: at most four candidate decisions plus eight tree decisions, each over a fixed number of inputs.
 * The only static data are tables filled once when the class loads and never written again, so any number of
 * instances can run on any number of threads. One instance must be used by one thread at a time.
 */
abstract class TileModel
{
	/** Side of the cells that are flagged as one colour, in pixels. */
	static final int CELL = 4;

	/** log2 of {@link #CELL}. */
	private static final int CELL_SHIFT = 2;

	/** The most colours a tile can have, counting the clear one. */
	static final int MAX_COLOURS = 256;

	/** The smallest and largest log2 of the size of one context table. */
	static final int MIN_TB = 10;
	static final int MAX_TB = 18;

	/** Hashed contexts for an ordinary pixel, and the most any decision uses. */
	private static final int NCTX = 7;

	/** Context tables: one per ordinary pixel context, then the two that only a pixel with a flat neighbourhood uses. */
	private static final int NTAB = 9;

	/** Most neighbour candidates for one pixel. */
	private static final int NC = 4;

	/** Weights per mixer set: the most inputs there can be (contexts, two match models, one bias). */
	private static final int NIN = NCTX + 3;

	/** Contexts for a cell flag and for the colour of a flagged cell. */
	private static final int FLAG_CTX = 5;
	private static final int COLOUR_CTX = 6;

	/** The contexts a pixel with a flat neighbourhood uses: indices into the table set. */
	private static final int[] FLAT_TABLES = {0, 7, 4, 8};

	/** Mixer start weight and learning rate: quicker for tile sized pictures, steadier for big ones. */
	private static final int TILE_WEIGHT = 1 << 14;
	private static final int TILE_RATE = 128;
	private static final int BIG_WEIGHT = 1 << 14;
	private static final int BIG_RATE = 32;
	private static final int BIG_PIXELS = 1 << 16;
	private static final int TILE_LIMIT = 255;
	private static final int BIG_LIMIT = 12;
	private static final int MATCH_BITS = 16;

	// Mixer groups, which select the weight sets.
	private static final int GROUP_PIXEL = 0;
	private static final int GROUP_FLAT = 1;
	private static final int GROUP_COLOUR = 2;
	private static final int GROUP_FLAG = 3;

	/** Multiplier of the final step of a context hash and of the salt, applied once per pixel rather than once per decision. */
	private static final int CTX_MUL = 0x85EBCA6B;

	/** The salt of every decision id (a candidate flag or a tree node) already multiplied by {@link #CTX_MUL}. */
	private static final int[] SALT = new int[16 + MAX_COLOURS];

	private static final short[] STRETCH = new short[4096];

	/** {@link #squash(int)} for every d from -2048 to 2048, which is also the answer for any d beyond. */
	private static final char[] SQUASH = new char[4097];
	private static final int[] SQUASH_POINTS = {1, 2, 3, 6, 10, 16, 27, 45, 73, 120, 194, 310, 488, 747, 1101, 1546, 2047, 2549,
		2994, 3348, 3607, 3785, 3901, 3975, 4022, 4050, 4068, 4079, 4085, 4089, 4092, 4093, 4094};
	private static final char[] RECIPROCAL = new char[256];

	static
	{
		int next = 0;
		for (int x = -2047; x <= 2047; x++)
		{
			int v = squash(x);
			for (int i = next; i <= v; i++)
			{
				STRETCH[i] = (short) x;
			}
			next = v + 1;
		}
		for (int i = next; i < 4096; i++)
		{
			STRETCH[i] = 2047;
		}
		for (int d = -2048; d <= 2048; d++)
		{
			SQUASH[d + 2048] = (char) squash(d);
		}
		for (int id = 0; id < SALT.length; id++)
		{
			SALT[id] = id * 0x9E3779B1 * CTX_MUL;
		}
		for (int i = 0; i < RECIPROCAL.length; i++)
		{
			// 65536 / (i + 1.6), rounded down, in integers
			RECIPROCAL[i] = (char) (655360 / (10 * i + 16));
		}
	}

	/** The logistic function on the stretched scale, as a 12 bit probability, by interpolating a table. */
	static int squash(int d)
	{
		if (d > 2047)
		{
			return 4095;
		}
		if (d < -2047)
		{
			return 0;
		}
		int w = d & 127;
		int i = (d >> 7) + 16;
		return (SQUASH_POINTS[i] * (128 - w) + SQUASH_POINTS[i + 1] * w + 64) >> 7;
	}

	/** True when this instance encodes, false when it decodes. The shared code asks only to know what to predict. */
	final boolean encoding;

	// Image state.
	int w;
	int h;
	int stride;
	byte[] img = new byte[0];
	private int ncol;
	private int nb;
	/** The next row to code in a picture that is coded in pieces. */
	private int runY;
	private boolean[] uni = new boolean[0];
	private byte[] ucol = new byte[0];

	// Model tables.
	private int tb;
	private int rate;
	private int limit;
	private int[] tab = new int[0];
	private final int[] mixW = new int[NIN * 256];
	private final int[] mixW2 = new int[NIN * 128];
	private final int[] smA = new int[64 * 2];
	private final int[] smB = new int[64 * 2];
	private final int[] hashA = new int[1 << MATCH_BITS];
	private final int[] hashB = new int[1 << MATCH_BITS];
	private final int[] pm = new int[4096];
	private static final int PAL_SHIFT = 3;

	// Per decision scratch.
	private final int[] in = new int[NIN];
	private final int[] idx = new int[NCTX];
	private final int[] ctxm = new int[NCTX];
	private final int[] tbase = new int[NCTX];
	private final int[] bb = new int[NCTX];
	private final int[] cand = new int[NC];
	private int ncand;
	private int nact;
	int group;
	private int phase;
	private int wsel;
	private int wsel2;
	private int pr1;
	private int pr2;
	private int predA;
	private int predB;
	private int ebitA;
	private int ebitB;
	private int smIdxA;
	private int smIdxB;
	private int mpA;
	private int lenA;
	private int mpB;
	private int lenB;

	TileModel(boolean encoding)
	{
		this.encoding = encoding;
	}

	/** One step of the arithmetic coder with a 12 bit probability that the bit is 1. Returns the bit coded. */
	abstract int code(int p, int bit);

	/**
	 * Prepares for an image of w by h pixels with ncol colours. Sizes must already have been checked by the caller.
	 * Context tables are 2^tb entries each.
	 */
	final void reset(int w, int h, int ncol, int tb)
	{
		this.w = w;
		this.h = h;
		this.ncol = ncol;
		this.tb = tb;
		this.stride = w + 4;
		int size = stride * (h + 3);
		if (img.length < size)
		{
			img = new byte[size];
		}
		Arrays.fill(img, 0, size, (byte) 0);
		int cells = (w / CELL) * (h / CELL);
		if (uni.length < cells)
		{
			uni = new boolean[cells];
			ucol = new byte[cells];
		}
		Arrays.fill(uni, 0, cells, false);
		int b = 1;
		while ((1 << b) < ncol)
		{
			b++;
		}
		nb = b;
		if (tab.length < NTAB << tb)
		{
			tab = new int[NTAB << tb];
		}
		Arrays.fill(tab, 0, NTAB << tb, 1 << 31);
		boolean big = w * h > BIG_PIXELS;
		rate = big ? BIG_RATE : TILE_RATE;
		limit = big ? BIG_LIMIT : TILE_LIMIT;
		Arrays.fill(mixW, big ? BIG_WEIGHT : TILE_WEIGHT);
		Arrays.fill(mixW2, big ? BIG_WEIGHT : TILE_WEIGHT);
		Arrays.fill(smA, 1 << 31);
		Arrays.fill(smB, 1 << 31);
		Arrays.fill(hashA, -1);
		Arrays.fill(hashB, -1);
		mpA = -1;
		mpB = -1;
		lenA = 0;
		lenB = 0;
	}

	private int pos(int x, int y)
	{
		return (y + 2) * stride + x + 2;
	}

	/** A pixel of the picture, or 0 outside it. */
	private int px(int x, int y)
	{
		if (x < 0 || y < 0 || x >= w || y >= h)
		{
			return 0;
		}
		return img[pos(x, y)] & 255;
	}

	// The multipliers of the context hash.
	private static final int K1 = 0x2F0B4A27;
	private static final int K2 = 0x6F4F2A35;
	private static final int K3 = 0x1B873593;
	private static final int K4 = 0x5BD1E995;
	private static final int K5 = 0x27D4EB2F;

	/** A weighted sum of the five values. It is multiplied by {@link #CTX_MUL} on its way to a table index, which mixes it. */
	private static int hash(int a, int b, int c, int d, int e)
	{
		return a * K1 + b * K2 + c * K3 + d * K4 + e * K5;
	}

	/**
	 * Chooses, for every active context, the bucket of four neighbouring slots that the next decisions of the pixel live
	 * in, so that the decisions after the first one find their slots in a cache line that is already loaded. The id keeps
	 * the buckets of different kinds of decision apart.
	 */
	private void buckets(int id)
	{
		final int salt = SALT[id];
		final int shift = 32 - tb;
		for (int j = 0, n = nact; j < n; j++)
		{
			bb[j] = tbase[j] + (((ctxm[j] + salt) >>> shift) & ~3);
		}
	}

	// ---- the mixer ----

	/**
	 * The probability (12 bit, 1 to 4094) that the next decision is 1. The slot says which one of the four slots of the
	 * current buckets it is, sel picks the mixer set, and expA and expB are what the match models expect, or -1.
	 */
	private int predict(int at, int sel, int expA, int expB)
	{
		final int[] in = this.in;
		final int[] t = this.tab;
		final int n = nact;
		for (int j = 0; j < n; j++)
		{
			int i = bb[j] + at;
			idx[j] = i;
			in[j] = STRETCH[t[i] >>> 20];
		}
		int ms = 0;
		int slot = sel < NC ? 0 : 1;
		ebitA = expA;
		if (expA >= 0)
		{
			smIdxA = Math.min(lenA, 31) * 2 + slot;
			int st = STRETCH[smA[smIdxA] >>> 20];
			in[n] = expA == 1 ? st : -st;
			ms |= 1;
		}
		else
		{
			in[n] = 0;
		}
		ebitB = expB;
		if (expB >= 0)
		{
			smIdxB = Math.min(lenB, 31) * 2 + slot;
			int st = STRETCH[smB[smIdxB] >>> 20];
			in[n + 1] = expB == 1 ? st : -st;
			ms |= 2;
		}
		else
		{
			in[n + 1] = 0;
		}
		in[n + 2] = 256;
		wsel = ((group * 16 + sel) * 4 + ms) * NIN;
		wsel2 = ((group * 2 + slot) * 16 + phase) * NIN;
		final int[] w1 = mixW;
		final int[] w2 = mixW2;
		final int b1 = wsel;
		final int b2 = wsel2;
		// The sums fit an int: inputs are at most 2047 and the weights stay far below 2^20 on the bundled map, and the packer
		// decodes everything it writes, so a picture that overflowed would be caught there. On damaged input a sum may wrap,
		// which only changes a prediction.
		int dot = 0;
		int dot2 = 0;
		for (int i = 0, m = n + 3; i < m; i++)
		{
			int v = in[i];
			dot += v * w1[b1 + i];
			dot2 += v * w2[b2 + i];
		}
		int d1 = dot >> 16;
		int d2 = dot2 >> 16;
		pr1 = SQUASH[Math.max(-2048, Math.min(2048, d1)) + 2048];
		pr2 = SQUASH[Math.max(-2048, Math.min(2048, d2)) + 2048];
		int pr = SQUASH[Math.max(-2048, Math.min(2048, (d1 + d2) >> 1)) + 2048];
		return Math.max(1, Math.min(4094, pr));
	}

	private void update(int bit)
	{
		final int[] in = this.in;
		final int err1 = ((bit << 12) - pr1) * rate;
		final int err2 = ((bit << 12) - pr2) * rate;
		final int[] w1 = mixW;
		final int[] w2 = mixW2;
		final int b1 = wsel;
		final int b2 = wsel2;
		final int n = nact;
		for (int i = 0, m = n + 3; i < m; i++)
		{
			int v = in[i];
			w1[b1 + i] += (v * err1 + 0x8000) >> 16;
			w2[b2 + i] += (v * err2 + 0x8000) >> 16;
		}
		final int[] t = tab;
		final int target = bit << 22;
		for (int j = 0; j < n; j++)
		{
			int i = idx[j];
			int s = t[i];
			int count = s & 1023;
			int p = s >>> 10;
			p += (int) (((long) (target - p) * RECIPROCAL[count]) >> 16);
			count += (count - limit) >>> 31;
			t[i] = (p << 10) | count;
		}
		if (ebitA >= 0)
		{
			adapt(smA, smIdxA, bit == ebitA ? 1 : 0);
		}
		if (ebitB >= 0)
		{
			adapt(smB, smIdxB, bit == ebitB ? 1 : 0);
		}
	}

	private static void adapt(int[] t, int i, int bit)
	{
		int s = t[i];
		int count = s & 1023;
		int p = s >>> 10;
		p += (int) (((long) ((bit << 22) - p) * RECIPROCAL[count]) >> 16);
		count += (count - 60) >>> 31;
		t[i] = (p << 10) | count;
	}

	// ---- contexts ----

	/** Sets up the contexts for the pixel at x, y: the flat or the full set, and the candidate list. */
	private void beginPixel(int x, int y)
	{
		final byte[] img = this.img;
		final int p = pos(x, y);
		final int stride = this.stride;
		int W = img[p - 1] & 255;
		int N = img[p - stride] & 255;
		int NW = img[p - stride - 1] & 255;
		int NE = img[p - stride + 1] & 255;
		int WW = img[p - 2] & 255;
		int NN = img[p - 2 * stride] & 255;
		int ph = (x & 3) | ((y & 3) << 2);
		phase = ph;
		if (W == N && N == NW && N == NE)
		{
			// Flat neighbourhood: a few cheap contexts and no match models.
			group = GROUP_FLAT;
			nact = FLAT_TABLES.length;
			final int wn = W * K1 + N * K2;
			ctxm[0] = (wn + K5) * CTX_MUL;
			ctxm[1] = (W * K1 + WW * K2 + N * K3 + NN * K4 + 4 * K5) * CTX_MUL;
			ctxm[2] = (W * K1 + ph * K2 + 7 * K5) * CTX_MUL;
			ctxm[3] = (wn + NW * K3 + NE * K4 + (WW * 256 + NN + 5 * 65536) * K5) * CTX_MUL;
			for (int j = 0; j < nact; j++)
			{
				tbase[j] = FLAT_TABLES[j] << tb;
			}
			lenA = 0;
			lenB = 0;
			predA = -1;
			predB = -1;
		}
		else
		{
			int NNE = img[p - 2 * stride + 1] & 255;
			group = GROUP_PIXEL;
			nact = NCTX;
			final int wn = W * K1 + N * K2;
			final int wnne = wn + NW * K3 + NE * K4;
			ctxm[0] = (wn + K5) * CTX_MUL;
			ctxm[1] = (wn + NW * K3 + 2 * K5) * CTX_MUL;
			ctxm[2] = (wn + NE * K3 + 3 * K5) * CTX_MUL;
			ctxm[3] = (N * K1 + NN * K2 + NE * K3 + NNE * K4 + 6 * K5) * CTX_MUL;
			ctxm[4] = (W * K1 + ph * K2 + 7 * K5) * CTX_MUL;
			ctxm[5] = (N * K1 + ph * K2 + 9 * K5) * CTX_MUL;
			ctxm[6] = (wnne + (ph + 10 * 256) * K5) * CTX_MUL;
			for (int j = 0; j < NCTX; j++)
			{
				tbase[j] = j << tb;
			}
			int hA = hash(W, WW, img[p - 3] & 255, img[p - 4] & 255, 11) >>> (32 - MATCH_BITS);
			int hB = hash(W, N, NW, NE, WW * 256 + NN + 12 * 65536) >>> (32 - MATCH_BITS);
			if (lenA == 0)
			{
				int c = hashA[hA];
				if (c >= 0)
				{
					mpA = c;
					int l = matchLength(c, p);
					lenA = l;
					if (l == 0)
					{
						mpA = -1;
					}
				}
			}
			if (lenB == 0)
			{
				int c = hashB[hB];
				if (c >= 0)
				{
					mpB = c;
					int l = matchLength(c, p);
					lenB = l;
					if (l == 0)
					{
						mpB = -1;
					}
				}
			}
			hashA[hA] = p;
			hashB[hB] = p;
			predA = (lenA > 0 && mpA >= 0) ? img[mpA] & 255 : -1;
			predB = (lenB > 0 && mpB >= 0) ? img[mpB] & 255 : -1;
		}
		ncand = 0;
		cand[ncand++] = W;
		if (N != W)
		{
			cand[ncand++] = N;
		}
		if (NE != W && NE != N)
		{
			cand[ncand++] = NE;
		}
		if (NW != W && NW != N && NW != NE)
		{
			cand[ncand++] = NW;
		}
	}

	/** How many pixels before c equal the pixels before p, at most 32. */
	private int matchLength(int c, int p)
	{
		final byte[] img = this.img;
		int l = 0;
		while (l < 32 && c - 1 - l >= 0 && img[c - 1 - l] == img[p - 1 - l])
		{
			l++;
		}
		return l;
	}

	private void endPixel(int x, int y, int sym)
	{
		img[pos(x, y)] = (byte) sym;
		if (lenA > 0)
		{
			if (predA == sym)
			{
				lenA++;
				mpA++;
			}
			else
			{
				lenA = 0;
			}
		}
		if (lenB > 0)
		{
			if (predB == sym)
			{
				lenB++;
				mpB++;
			}
			else
			{
				lenB = 0;
			}
		}
	}

	private void setupCellFlag(int bx, int by, int cw)
	{
		int x0 = bx * CELL;
		int y0 = by * CELL;
		boolean uL = bx > 0 && uni[by * cw + bx - 1];
		boolean uU = by > 0 && uni[(by - 1) * cw + bx];
		boolean uUL = bx > 0 && by > 0 && uni[(by - 1) * cw + bx - 1];
		boolean uUR = bx + 1 < cw && by > 0 && uni[(by - 1) * cw + bx + 1];
		int cL = uL ? ucol[by * cw + bx - 1] & 255 : 256;
		int cU = uU ? ucol[(by - 1) * cw + bx] & 255 : 256;
		int N = px(x0, y0 - 1);
		boolean aboveFlat = by > 0 && px(x0 + 1, y0 - 1) == N && px(x0 + 2, y0 - 1) == N && px(x0 + 3, y0 - 1) == N;
		int NE = px(x0 + CELL, y0 - 1);
		int NW = px(x0 - 1, y0 - 1);
		int f = (uL ? 1 : 0) | (uU ? 2 : 0) | (uUL ? 4 : 0) | (uUR ? 8 : 0);
		ctxm[0] = hash(f, 0, 0, 0, 21) * CTX_MUL;
		ctxm[1] = hash(f, aboveFlat ? 1 : 0, cL == cU ? 1 : 0, 0, 22) * CTX_MUL;
		ctxm[2] = hash(aboveFlat ? 1 : 0, N, cL, 0, 23) * CTX_MUL;
		ctxm[3] = hash(N, NE, NW, f, 24) * CTX_MUL;
		ctxm[4] = hash(cL, cU, N, 0, 25) * CTX_MUL;
		nact = FLAG_CTX;
		for (int j = 0; j < nact; j++)
		{
			tbase[j] = j << tb;
		}
		predA = -1;
		predB = -1;
		group = GROUP_FLAG;
		phase = 0;
	}

	private void setupCellColour(int bx, int by, int cw)
	{
		int x0 = bx * CELL;
		int y0 = by * CELL;
		boolean uL = bx > 0 && uni[by * cw + bx - 1];
		boolean uU = by > 0 && uni[(by - 1) * cw + bx];
		int N = px(x0, y0 - 1);
		int NE = px(x0 + CELL, y0 - 1);
		int NW = px(x0 - 1, y0 - 1);
		int Wc = uL ? ucol[by * cw + bx - 1] & 255 : NW;
		int Wn = px(x0 - 1, y0);
		ctxm[0] = hash(Wc, N, 0, 0, 31) * CTX_MUL;
		ctxm[1] = hash(Wc, N, NE, NW, 32) * CTX_MUL;
		ctxm[2] = hash(N, NE, 0, 0, 33) * CTX_MUL;
		ctxm[3] = hash(Wc, uU ? 1 : 0, uL ? 1 : 0, Wn, 34) * CTX_MUL;
		ctxm[4] = hash(Wc >> 3, N >> 3, 0, 0, 35) * CTX_MUL;
		ctxm[5] = hash(Wc >> 5, N >> 5, NE >> 5, 0, 36) * CTX_MUL;
		nact = COLOUR_CTX;
		for (int j = 0; j < nact; j++)
		{
			tbase[j] = j << tb;
		}
		ncand = 0;
		cand[ncand++] = Wc;
		if (N != Wc)
		{
			cand[ncand++] = N;
		}
		if (NE != Wc && NE != N)
		{
			cand[ncand++] = NE;
		}
		if (NW != Wc && NW != N && NW != NE)
		{
			cand[ncand++] = NW;
		}
		predA = -1;
		predB = -1;
		group = GROUP_COLOUR;
		phase = 0;
	}

	// ---- coding ----

	/**
	 * Codes one palette index: first "is it candidate k" for each candidate, then the index through a binary tree.
	 * When encoding, s is the symbol. When decoding it is ignored and the symbol is returned.
	 */
	private int codePixel(int s)
	{
		buckets(1);
		for (int k = 0; k < ncand; k++)
		{
			int expA = predA >= 0 ? (predA == cand[k] ? 1 : 0) : -1;
			int expB = predB >= 0 ? (predB == cand[k] ? 1 : 0) : -1;
			int p = predict(k, k, expA, expB);
			int bit = code(p, encoding && s == cand[k] ? 1 : 0);
			update(bit);
			if (bit == 1)
			{
				return cand[k];
			}
		}
		int node = 1;
		for (int b = nb - 1; b >= 0; b--)
		{
			int expA = -1;
			int expB = -1;
			if (predA >= 0 && ((predA | (1 << nb)) >> (b + 1)) == node)
			{
				expA = (predA >> b) & 1;
			}
			if (predB >= 0 && ((predB | (1 << nb)) >> (b + 1)) == node)
			{
				expB = (predB >> b) & 1;
			}
			// A bucket serves two levels of the tree: its first slot is the node a level starts from, the other two its children.
			int at;
			if (((nb - 1 - b) & 1) == 0)
			{
				buckets(16 + node);
				at = 0;
			}
			else
			{
				at = 1 + (node & 1);
			}
			int p = predict(at, NC + nb - 1 - b, expA, expB);
			int bit = code(p, encoding ? (s >> b) & 1 : 0);
			update(bit);
			node = (node << 1) | bit;
		}
		int result = node - (1 << nb);
		return result >= ncol ? ncol - 1 : result;
	}

	private boolean cellUniform(byte[] sym, int x0, int y0)
	{
		int v = sym[y0 * w + x0];
		for (int y = y0; y < y0 + CELL; y++)
		{
			for (int x = x0; x < x0 + CELL; x++)
			{
				if (sym[y * w + x] != v)
				{
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Codes the whole picture. When encoding, sym holds the w by h indices, row by row. When decoding, sym is null
	 * and the result is left in {@link #img}, to be read with {@link #pos(int, int)} semantics by {@link #emit}.
	 */
	final void run(byte[] sym)
	{
		startRun();
		runRows(sym, h);
	}

	/** Starts a picture that is coded in pieces with {@link #runRows(byte[], int)}. Called after {@link #reset}. */
	final void startRun()
	{
		runY = 0;
	}

	/** The rows coded so far: rows before this one are final in {@link #img}. */
	final int rowsCoded()
	{
		return Math.min(runY, h);
	}

	/** True when every row has been coded. */
	final boolean runDone()
	{
		return runY >= h;
	}

	/**
	 * Codes the next rows of the picture, at least maxRows of them unless the picture ends first. Rows are coded in steps (four
	 * rows for a picture of whole cells, else one), and a step is never split, so a piece may be a few rows longer. Everything the
	 * coder remembers between steps is in fields, so coding in pieces gives exactly the result of coding in one go.
	 */
	final void runRows(byte[] sym, int maxRows)
	{
		final boolean cells = w % CELL == 0 && h % CELL == 0;
		final int cw = w / CELL;
		final int rowsPerStep = cells ? CELL : 1;
		final int stop = (int) Math.min((long) h, (long) runY + Math.max(1, maxRows));
		int y0 = runY;
		for (; y0 < stop; y0 += rowsPerStep)
		{
			int by = y0 / CELL;
			if (cells)
			{
				for (int bx = 0; bx < cw; bx++)
				{
					setupCellFlag(bx, by, cw);
					int isUniform = encoding && cellUniform(sym, bx * CELL, y0) ? 1 : 0;
					buckets(1);
					int p = predict(0, 0, -1, -1);
					int bit = code(p, isUniform);
					update(bit);
					if (bit == 1)
					{
						setupCellColour(bx, by, cw);
						int c = codePixel(encoding ? sym[y0 * w + bx * CELL] & 255 : 0);
						uni[by * cw + bx] = true;
						ucol[by * cw + bx] = (byte) c;
						for (int y = 0; y < CELL; y++)
						{
							int o = pos(bx * CELL, y0 + y);
							for (int x = 0; x < CELL; x++)
							{
								img[o + x] = (byte) c;
							}
						}
					}
				}
			}
			for (int y = y0; y < y0 + rowsPerStep; y++)
			{
				for (int x = 0; x < w; x++)
				{
					if (cells && uni[by * cw + (x >> CELL_SHIFT)])
					{
						lenA = 0;
						lenB = 0;
						x |= CELL - 1;
						continue;
					}
					beginPixel(x, y);
					int s = codePixel(encoding ? sym[y * w + x] & 255 : 0);
					endPixel(x, y, s);
				}
			}
		}
		runY = y0;
	}

	/** Writes the decoded picture, w by h, mapping each index through the table, into out. */
	final void emit(int[] lut, int[] out)
	{
		emitRows(lut, out, 0, h);
	}

	/** Writes rows from (inclusive) to to (exclusive) of the decoded picture into out, where row r starts at r * w. */
	final void emitRows(int[] lut, int[] out, int from, int to)
	{
		final byte[] img = this.img;
		int o = from * w;
		for (int y = from; y < to; y++)
		{
			int p = pos(0, y);
			for (int x = 0; x < w; x++)
			{
				out[o++] = lut[img[p++] & 255];
			}
		}
	}

	// ---- palette ----

	private int pbit(int ctx, int b)
	{
		int p = pm[ctx];
		int pp = Math.max(32, Math.min(4064, p));
		int r = code(pp, b);
		pm[ctx] += ((r << 12) - p) >> PAL_SHIFT;
		return r;
	}

	private static int bitLength(int v)
	{
		return 32 - Integer.numberOfLeadingZeros(v);
	}

	/**
	 * Codes n colours (24 bit RGB) as per channel differences from the previous colour. When decoding, rgb is filled in.
	 */
	final void codePalette(int[] rgb, int n)
	{
		Arrays.fill(pm, 2048);
		int p0 = 0;
		int p1 = 0;
		int p2 = 0;
		for (int i = 0; i < n; i++)
		{
			int cls = 0;
			int c0 = 0;
			int c1 = 0;
			int c2 = 0;
			for (int c = 0; c < 3; c++)
			{
				int prev = c == 0 ? p0 : c == 1 ? p1 : p2;
				int base = c * 1024;
				int d = encoding ? ((rgb[i] >> (16 - 8 * c)) & 255) - prev : 0;
				if (pbit(base + cls, d == 0 ? 1 : 0) == 1)
				{
					d = 0;
				}
				else
				{
					int neg = pbit(base + 16 + cls, d < 0 ? 1 : 0);
					int m = encoding ? Math.abs(d) : 0;
					int len = encoding ? bitLength(m) : 0;
					int k = 1;
					while (pbit(base + 32 + k, encoding && len > k ? 1 : 0) == 1 && k < 8)
					{
						k++;
					}
					int val = 1;
					for (int b = k - 2; b >= 0; b--)
					{
						val = (val << 1) | pbit(base + 64 + k * 4 + Math.min(k - 2 - b, 3), encoding ? (m >> b) & 1 : 0);
					}
					d = neg == 1 ? -val : val;
				}
				int v = (prev + d) & 255;
				if (c == 0)
				{
					c0 = v;
				}
				else if (c == 1)
				{
					c1 = v;
				}
				else
				{
					c2 = v;
				}
				cls = Math.min(bitLength(Math.abs(d)), 3) * 3 + (d < 0 ? 2 : d > 0 ? 1 : 0);
			}
			rgb[i] = (c0 << 16) | (c1 << 8) | c2;
			p0 = c0;
			p1 = c1;
			p2 = c2;
		}
	}
}
