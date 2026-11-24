package com.worldmapplus.tiles.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;

/**
 * A picture decoded in pieces ({@link TileDecoder#begin}) is the same picture as one decoded in one go, whatever the size of
 * the pieces, and no piece of a shipped overview takes long.
 */
public class SlicedDecodeTest
{
	private final TileEncoder encoder = new TileEncoder();

	private static int[] inPieces(TileDecoder.Progress progress, long nanos)
	{
		int last = 0;
		int steps = 0;
		while (!progress.step(nanos))
		{
			assertTrue("rows only grow", progress.rows() >= last);
			last = progress.rows();
			steps++;
			assertTrue("a decode in pieces moves on", steps < 100_000);
		}
		assertTrue(progress.isDone());
		assertEquals(progress.rows(), progress.image().getHeight());
		return TileEncoder.pixels(progress.image());
	}

	/** The pixels as the image of a decoder gives them, clear ones as 0xFF000000 in an RGB image. */
	private static int[] rgb(int[] argb)
	{
		int[] out = new int[argb.length];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = 0xFF000000 | argb[i];
		}
		return out;
	}

	@Test
	public void syntheticPicturesDecodeTheSameInPiecesOfAnySize()
	{
		int[][] sizes = {{64, 64}, {256, 256}, {37, 23}, {100, 3}, {1536, 100}, {8, 8}, {4, 4}, {1, 1}, {3, 400}};
		for (int[] size : sizes)
		{
			int w = size[0];
			int h = size[1];
			int[][] pictures = {Synth.terrain(w, h, 5), Synth.noiseWithClear(w, h, 30, 6), Synth.gradient(w, h), Synth.flat(w, h, 0x112233),
				Synth.clear(w, h)};
			for (int[] px : pictures)
			{
				byte[] bytes = encoder.encode(px, w, h);
				int[] whole = new int[w * h];
				new TileDecoder().decode(bytes, 0, bytes.length, w, h, whole);
				assertArrayEquals(px, whole);
				for (long nanos : new long[] {0, 20_000, 1_000_000, Long.MAX_VALUE / 4})
				{
					TileDecoder.Progress progress = new TileDecoder().begin(bytes, 0, bytes.length, w, h, false);
					assertArrayEquals("size " + w + "x" + h + " pieces of " + nanos + " ns", rgb(whole), inPieces(progress, nanos));
				}
				TileDecoder.Progress alpha = new TileDecoder().begin(bytes, 0, bytes.length, w, h, true);
				assertArrayEquals("with alpha", whole, inPieces(alpha, 0));
			}
		}
	}

	@Test
	public void aDecoderIsReusableForAWholeDecodeAfterAPicture()
	{
		TileDecoder decoder = new TileDecoder();
		int[] px = Synth.terrain(128, 64, 3);
		byte[] bytes = encoder.encode(px, 128, 64);
		TileDecoder.Progress progress = decoder.begin(bytes, 0, bytes.length, 128, 64, true);
		assertArrayEquals(px, inPieces(progress, 0));
		int[] out = new int[128 * 64];
		decoder.decode(bytes, 0, bytes.length, 128, 64, out);
		assertArrayEquals(px, out);
	}

	@Test
	public void theImageIsNotAvailableBeforeItIsFinished()
	{
		int[] px = Synth.terrain(64, 64, 1);
		byte[] bytes = encoder.encode(px, 64, 64);
		TileDecoder.Progress progress = new TileDecoder().begin(bytes, 0, bytes.length, 64, 64, false);
		try
		{
			progress.image();
			fail("the picture is not finished");
		}
		catch (IllegalStateException expected)
		{
			// refused
		}
		assertTrue("steps after the end are harmless", inPieces(progress, 0).length == 64 * 64 && progress.step(0));
	}

	@Test
	public void aPictureOfOneColourIsFinishedAtOnce()
	{
		byte[] bytes = encoder.encode(Synth.flat(300, 200, 0x445566), 300, 200);
		TileDecoder.Progress progress = new TileDecoder().begin(bytes, 0, bytes.length, 300, 200, false);
		assertTrue(progress.isDone());
		assertEquals(0x445566, progress.image().getRGB(299, 199) & 0xFFFFFF);
	}

	private static byte[] resource(String name) throws IOException
	{
		try (InputStream in = SlicedDecodeTest.class.getResourceAsStream("/com/worldmapplus/tiles/" + name))
		{
			assertTrue(name + " is in the plugin", in != null);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[1 << 16];
			int n;
			while ((n = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	/** Every shipped overview, in pieces, is pixel for pixel the overview decoded whole. The longest piece is printed. */
	@Test
	public void theShippedOverviewsDecodeTheSameInPieces() throws IOException
	{
		TileDecoder decoder = new TileDecoder();
		long budget = 8_000_000L;
		long longest = 0;
		for (int map : new int[] {0, 20000, 40000, 60000})
		{
			ImageFile file = ImageFile.parse(resource(ChunkNames.overview(map)));
			int[] whole = TileEncoder.pixels(file.decodeImage(false, decoder));
			for (long nanos : new long[] {budget, 0})
			{
				TileDecoder.Progress progress = file.begin(false, decoder);
				int pieces = 0;
				boolean done = false;
				while (!done)
				{
					long t0 = System.nanoTime();
					done = progress.step(nanos);
					if (nanos == budget)
					{
						longest = Math.max(longest, System.nanoTime() - t0);
					}
					pieces++;
				}
				assertTrue("overview " + map + " takes many pieces, took " + pieces, pieces > 20);
				assertArrayEquals("overview " + map + " in pieces of " + nanos + " ns", whole, TileEncoder.pixels(progress.image()));
			}
		}
		System.out.printf("SlicedDecodeTest: longest piece of an overview at an 8 ms budget: %.1f ms%n", longest / 1e6);
		assertTrue("a piece of an overview took " + longest / 1_000_000 + " ms", longest < 400_000_000L);
	}

	/** A piece ends at the first look at the clock past the budget, so it is short even when the budget is tiny. */
	@Test
	public void aSmallBudgetGivesShortPieces() throws IOException
	{
		ImageFile file = ImageFile.parse(resource(ChunkNames.overview(0)));
		TileDecoder.Progress progress = file.begin(false, new TileDecoder());
		int pieces = 0;
		while (!progress.step(1))
		{
			pieces++;
		}
		// 1088 rows, four rows a piece, but it is allowed to take a few steps if the clock is slow to move.
		assertTrue("pieces " + pieces, pieces > 200);
	}

	@Test
	public void damagedPicturesInPiecesFailLikeTheWholeDecode()
	{
		Random r = new Random(42);
		int w = 96;
		int h = 64;
		byte[] good = encoder.encode(Synth.terrain(w, h, 9), w, h);
		Fuzz fuzz = new Fuzz();
		TileDecoder decoder = new TileDecoder();
		for (int i = 0; i < 300; i++)
		{
			byte[] bad = good.clone();
			int flips = 1 + r.nextInt(3);
			for (int f = 0; f < flips; f++)
			{
				bad[2 + r.nextInt(bad.length - 2)] ^= 1 << r.nextInt(8);
			}
			byte[] cut = i % 3 == 0 ? java.util.Arrays.copyOf(bad, 3 + r.nextInt(bad.length - 3)) : bad;
			fuzz.exercisePayload(cut, w, h, decoder);
		}
		assertTrue(fuzz.accepted + fuzz.rejected == 300);
		assertTrue("some damaged payloads are refused", fuzz.rejected > 0);
	}
}
