package com.worldmapplus.tiles.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.Test;

/** Round trips of synthetic pictures through the encoder and decoder, and the rules of the payload. */
public class TileCodecTest
{
	private final TileEncoder encoder = new TileEncoder();
	private final TileDecoder decoder = new TileDecoder();

	private byte[] roundTrip(int[] px, int w, int h)
	{
		byte[] bytes = encoder.encode(px, w, h);
		int[] out = new int[w * h];
		decoder.decode(bytes, 0, bytes.length, w, h, out);
		assertArrayEquals("pixels of " + w + " by " + h, px, out);
		return bytes;
	}

	@Test
	public void aPictureOfOneColour()
	{
		byte[] bytes = roundTrip(Synth.flat(256, 256, 0x336699), 256, 256);
		assertTrue("a flat tile is tiny, was " + bytes.length, bytes.length < 80);
	}

	@Test
	public void aPictureThatIsAllClear()
	{
		byte[] bytes = roundTrip(Synth.clear(256, 256), 256, 256);
		assertTrue(bytes.length < 80);
	}

	@Test
	public void twoColoursAsACheckerboard()
	{
		int[] p = new int[64 * 64];
		for (int i = 0; i < p.length; i++)
		{
			p[i] = (((i % 64) + (i / 64)) & 1) == 0 ? Synth.colour(1) : Synth.colour(2);
		}
		roundTrip(p, 64, 64);
	}

	@Test
	public void everyNumberOfColours()
	{
		for (int n : new int[] {1, 2, 3, 4, 5, 8, 9, 16, 17, 31, 32, 33, 100, 127, 128, 129, 200, 255, 256})
		{
			roundTrip(Synth.noise(128, 128, n, n), 128, 128);
		}
	}

	@Test
	public void theClearColourTakesOneOfTheColourSlots()
	{
		for (int n : new int[] {1, 2, 3, 4, 17, 128, 255, 256})
		{
			roundTrip(Synth.noiseWithClear(128, 128, n, n), 128, 128);
		}
	}

	@Test
	public void noiseOfAllColoursStillRoundTrips()
	{
		roundTrip(Synth.noise(256, 256, 256, 7), 256, 256);
	}

	@Test
	public void aGradient()
	{
		roundTrip(Synth.gradient(256, 256), 256, 256);
	}

	@Test
	public void terrainLikeTiles()
	{
		for (int i = 0; i < 6; i++)
		{
			roundTrip(Synth.terrain(256, 256, i), 256, 256);
		}
	}

	@Test
	public void aSingleDifferentPixelAnywhere()
	{
		int[] base = Synth.flat(64, 64, 0x102030);
		for (int at : new int[] {0, 1, 63, 64, 65, 2000, 64 * 63, 64 * 64 - 1})
		{
			int[] p = Synth.copy(base);
			p[at] = Synth.colour(5);
			roundTrip(p, 64, 64);
		}
		int[] clearOne = Synth.copy(base);
		clearOne[100] = 0;
		roundTrip(clearOne, 64, 64);
	}

	@Test
	public void sizesThatAreNotSquareOrNotMultiplesOfACell()
	{
		int[][] sizes = {{1, 1}, {2, 3}, {3, 1}, {4, 4}, {4, 8}, {8, 4}, {5, 7}, {37, 23}, {64, 32}, {32, 64}, {100, 1}, {1, 100},
			{255, 255}, {257, 3}, {1536, 4}, {20, 1088}};
		for (int[] s : sizes)
		{
			roundTrip(Synth.terrain(s[0], s[1], s[0] * 31 + s[1]), s[0], s[1]);
			roundTrip(Synth.noiseWithClear(s[0], s[1], 20, s[0]), s[0], s[1]);
		}
	}

	@Test
	public void theLargestSizesAllowed()
	{
		roundTrip(Synth.flat(4096, 1, 0x010203), 4096, 1);
		roundTrip(Synth.gradient(1, 4096), 1, 4096);
		roundTrip(Synth.terrain(2048, 2048, 3), 2048, 2048);
		roundTrip(Synth.flat(4096, 1024, 0x0A0B0C), 4096, 1024);
	}

	@Test
	public void theWholeRangeOfTableSizesWorks()
	{
		int[] p = Synth.terrain(128, 128, 9);
		byte[] reference = null;
		for (int bits = TileModel.MIN_TB; bits <= TileModel.MAX_TB; bits++)
		{
			byte[] bytes = new TileEncoder().tableBits(bits).encode(p, 128, 128);
			int[] out = new int[128 * 128];
			decoder.decode(bytes, 0, bytes.length, 128, 128, out);
			assertArrayEquals("table bits " + bits, p, out);
			if (reference == null)
			{
				reference = bytes;
			}
		}
	}

	@Test
	public void encodingIsDeterministicAndTheEncoderCanBeReused()
	{
		int[] a = Synth.terrain(256, 256, 1);
		int[] b = Synth.noise(256, 256, 40, 2);
		byte[] first = encoder.encode(a, 256, 256);
		encoder.encode(b, 256, 256);
		byte[] again = encoder.encode(a, 256, 256);
		byte[] fresh = new TileEncoder().encode(a, 256, 256);
		assertArrayEquals(first, again);
		assertArrayEquals(first, fresh);
	}

	@Test
	public void aDecoderGivesTheSameAnswerAfterOtherPictures()
	{
		int[] a = Synth.terrain(256, 256, 4);
		byte[] bytes = encoder.encode(a, 256, 256);
		TileDecoder shared = new TileDecoder();
		int[] out = new int[256 * 256];
		for (int i = 0; i < 3; i++)
		{
			byte[] other = encoder.encode(Synth.noise(64 + i, 32, 70, i), 64 + i, 32);
			shared.decode(other, 0, other.length, 64 + i, 32, new int[(64 + i) * 32]);
			shared.decode(bytes, 0, bytes.length, 256, 256, out);
			assertArrayEquals(a, out);
		}
	}

	@Test
	public void decodesIntoARgbImageAndAnArgbImage()
	{
		int[] opaque = Synth.terrain(256, 256, 8);
		byte[] bytes = encoder.encode(opaque, 256, 256);
		BufferedImage rgb = decoder.decodeImage(bytes, 0, bytes.length, 256, 256, false);
		assertEquals(BufferedImage.TYPE_INT_RGB, rgb.getType());
		assertArrayEquals(opaque, TileEncoder.pixels(rgb));
		BufferedImage argb = decoder.decodeImage(bytes, 0, bytes.length, 256, 256, true);
		assertEquals(BufferedImage.TYPE_INT_ARGB, argb.getType());
		assertArrayEquals(opaque, TileEncoder.pixels(argb));

		int[] withClear = Synth.noiseWithClear(256, 256, 12, 5);
		byte[] clearBytes = encoder.encode(withClear, 256, 256);
		BufferedImage floor = decoder.decodeImage(clearBytes, 0, clearBytes.length, 256, 256, true);
		assertArrayEquals(withClear, TileEncoder.pixels(floor));
		assertEquals(0, floor.getRGB(0, 0) >>> 24);
	}

	@Test
	public void decodesAPayloadInsideALargerArray()
	{
		int[] p = Synth.terrain(64, 64, 2);
		byte[] bytes = encoder.encode(p, 64, 64);
		byte[] padded = new byte[bytes.length + 20];
		System.arraycopy(bytes, 0, padded, 7, bytes.length);
		Arrays.fill(padded, 0, 7, (byte) 0x5A);
		Arrays.fill(padded, 7 + bytes.length, padded.length, (byte) 0xA5);
		int[] out = new int[64 * 64];
		decoder.decode(padded, 7, bytes.length, 64, 64, out);
		assertArrayEquals(p, out);
	}

	@Test
	public void theEncoderRefusesWhatItCannotRepresent()
	{
		int[] partial = Synth.flat(8, 8, 0x112233);
		partial[3] = 0x80112233;
		expectRefused(partial, 8, 8);
		int[] clearWithColour = Synth.flat(8, 8, 0x112233);
		clearWithColour[3] = 0x00112233;
		expectRefused(clearWithColour, 8, 8);
		int[] tooMany = new int[300];
		for (int i = 0; i < tooMany.length; i++)
		{
			tooMany[i] = Synth.OPAQUE | (i * 7919);
		}
		expectRefused(tooMany, 300, 1);
		int[] clearPlus256 = new int[257];
		for (int i = 0; i < 256; i++)
		{
			clearPlus256[i] = Synth.colour(i);
		}
		expectRefused(clearPlus256, 257, 1);
		expectRefused(new int[10], 0, 10);
		expectRefused(new int[10], 5000, 1);
	}

	private void expectRefused(int[] px, int w, int h)
	{
		try
		{
			encoder.encode(px, w, h);
			fail("expected a refusal");
		}
		catch (IllegalArgumentException expected)
		{
			// refused loudly
		}
	}

	@Test
	public void sizeLimitsAreEnforcedBeforeAnythingIsAllocated()
	{
		byte[] bytes = encoder.encode(Synth.flat(8, 8, 1), 8, 8);
		int[][] bad = {{0, 8}, {8, 0}, {-1, 8}, {4097, 1}, {1, 4097}, {4096, 4096}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {65536, 65536},
			{2049, 2048}};
		for (int[] s : bad)
		{
			try
			{
				decoder.decode(bytes, 0, bytes.length, s[0], s[1], new int[64]);
				fail("size " + s[0] + " by " + s[1] + " should be refused");
			}
			catch (CodecException expected)
			{
				// refused
			}
			assertFalse(TileDecoder.sizeAllowed(s[0], s[1]));
		}
		assertTrue(TileDecoder.sizeAllowed(2048, 2048));
		assertTrue(TileDecoder.sizeAllowed(4096, 1024));
		assertTrue(TileDecoder.sizeAllowed(1, 1));
		// What a file may declare is tighter than what the decoder will take.
		assertTrue(TileDecoder.tileSizeAllowed(256, 256) && TileDecoder.tileSizeAllowed(512, 512));
		assertFalse(TileDecoder.tileSizeAllowed(513, 512) || TileDecoder.tileSizeAllowed(512, 513) || TileDecoder.tileSizeAllowed(0, 1));
		assertTrue(TileDecoder.pictureSizeAllowed(1536, 1088) && TileDecoder.pictureSizeAllowed(2048, 976)
			&& TileDecoder.pictureSizeAllowed(2000, 1000));
		assertFalse(TileDecoder.pictureSizeAllowed(2049, 1) || TileDecoder.pictureSizeAllowed(4096, 1024)
			|| TileDecoder.pictureSizeAllowed(1, 0) || TileDecoder.pictureSizeAllowed(2048, 2048)
			|| TileDecoder.pictureSizeAllowed(2000, 1001));
	}

	@Test
	public void anOutputArrayThatIsTooSmallIsRefused()
	{
		byte[] bytes = encoder.encode(Synth.flat(8, 8, 1), 8, 8);
		try
		{
			decoder.decode(bytes, 0, bytes.length, 8, 8, new int[63]);
			fail();
		}
		catch (CodecException expected)
		{
			// refused
		}
	}
}
