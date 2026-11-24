package com.worldmapplus.tiles.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Test;

/** Chunks and picture files: contents, lookups, aliases and the checks on their own structure. */
public class ChunkFileTest
{
	private final TileEncoder encoder = new TileEncoder();
	private final TileDecoder decoder = new TileDecoder();

	private static void assertRefused(byte[] data)
	{
		try
		{
			ChunkFile.parse(data);
			fail("expected the chunk to be refused");
		}
		catch (CodecException expected)
		{
			assertEquals(null, expected.getCause());
		}
	}

	private static void assertRefusedBehindChecksum(byte[] data)
	{
		try
		{
			ChunkFile.parse(data, false);
			fail("expected the chunk to be refused");
		}
		catch (CodecException expected)
		{
			assertEquals(null, expected.getCause());
		}
	}

	@Test
	public void aChunkListsItsEntriesAndDecodesEachOne()
	{
		ChunkBuilder b = new ChunkBuilder(64, 64);
		int[] a = Synth.terrain(64, 64, 1);
		int[] c = Synth.noiseWithClear(64, 64, 20, 2);
		int[] d = Synth.gradient(64, 64);
		b.add(20000, 11, 13, c);
		b.add(0, 12, 10, a);
		b.add(0, 11, 10, d);
		ChunkFile f = ChunkFile.parse(b.build(encoder));
		assertEquals(3, f.size());
		assertEquals(64, f.tileWidth());
		assertEquals(64, f.tileHeight());
		List<ChunkFile.Entry> entries = f.entries();
		assertEquals("0:11_10", entries.get(0).toString());
		assertEquals("0:12_10", entries.get(1).toString());
		assertEquals("20000:11_13", entries.get(2).toString());
		assertEquals(0, f.indexOf(0, 11, 10));
		assertEquals(2, f.indexOf(20000, 11, 13));
		assertEquals(-1, f.indexOf(0, 11, 13));
		assertEquals(-1, f.indexOf(7, 11, 10));
		assertEquals(-1, f.indexOf(-1, 5, 5));
		assertEquals(-1, f.indexOf(0, Integer.MAX_VALUE, 5));
		assertTrue(f.contains(0, 12, 10));
		assertFalse(f.contains(1, 12, 10));
		assertArrayEquals(a, TileEncoder.pixels(f.decodeImage(0, 12, 10, false, decoder)));
		assertArrayEquals(d, TileEncoder.pixels(f.decodeImage(0, 11, 10, false)));
		BufferedImage floor = f.decodeImage(20000, 11, 13, true, decoder);
		assertEquals(BufferedImage.TYPE_INT_ARGB, floor.getType());
		assertArrayEquals(c, TileEncoder.pixels(floor));
		int[] out = new int[64 * 64];
		f.decode(1, decoder, out);
		assertArrayEquals(a, out);
	}

	@Test
	public void identicalTilesAreStoredOnceAndDecodeTheSame()
	{
		int[] a = Synth.terrain(64, 64, 5);
		ChunkBuilder b = new ChunkBuilder(64, 64);
		b.add(0, 1, 1, a);
		b.add(0, 2, 1, Synth.gradient(64, 64));
		b.add(20000, 1, 1, Synth.copy(a));
		b.add(20000, 5, 5, Synth.copy(a));
		ChunkBuilder.Result r = b.buildResult(encoder);
		assertEquals(2, r.aliases);
		ChunkFile f = ChunkFile.parse(r.bytes);
		int alias = f.indexOf(20000, 5, 5);
		assertTrue(f.isAlias(alias));
		assertFalse(f.isAlias(f.indexOf(0, 1, 1)));
		assertArrayEquals(a, TileEncoder.pixels(f.decodeImage(20000, 5, 5, true, decoder)));
		assertArrayEquals(a, TileEncoder.pixels(f.decodeImage(20000, 1, 1, false, decoder)));
		// without the aliases the file is bigger
		ChunkBuilder plain = new ChunkBuilder(64, 64, false);
		plain.add(0, 1, 1, a);
		plain.add(0, 2, 1, Synth.gradient(64, 64));
		plain.add(20000, 1, 1, Synth.copy(a));
		plain.add(20000, 5, 5, Synth.copy(a));
		assertTrue(plain.build(encoder).length > r.bytes.length);
	}

	@Test
	public void theBytesDoNotDependOnTheOrderTilesWereAdded()
	{
		ChunkBuilder one = new ChunkBuilder(32, 32);
		ChunkBuilder two = new ChunkBuilder(32, 32);
		int[][] tiles = new int[5][];
		for (int i = 0; i < tiles.length; i++)
		{
			tiles[i] = Synth.terrain(32, 32, i);
		}
		for (int i = 0; i < tiles.length; i++)
		{
			one.add(0, 100 + i, 5, tiles[i]);
			two.add(0, 104 - i, 5, tiles[4 - i]);
		}
		assertArrayEquals(one.build(encoder), two.build(new TileEncoder()));
	}

	@Test
	public void aSquareTheChunkLacksIsAnError()
	{
		ChunkBuilder b = new ChunkBuilder(8, 8);
		b.add(0, 1, 1, Synth.flat(8, 8, 3));
		ChunkFile f = ChunkFile.parse(b.build(encoder));
		try
		{
			f.decodeImage(0, 2, 2, false);
			fail();
		}
		catch (CodecException expected)
		{
			// refused
		}
		try
		{
			f.decode(5, decoder, new int[64]);
			fail();
		}
		catch (CodecException expected)
		{
			// refused
		}
	}

	@Test
	public void aPictureFileRoundTrips()
	{
		int[] p = Synth.terrain(1536, 100, 3);
		ImageFile f = ImageFile.parse(ChunkBuilder.imageFile(p, 1536, 100, encoder));
		assertEquals(1536, f.width());
		assertEquals(100, f.height());
		assertArrayEquals(p, TileEncoder.pixels(f.decodeImage(false)));
		int[] out = new int[1536 * 100];
		f.decode(decoder, out);
		assertArrayEquals(p, out);
	}

	@Test
	public void chunkNamesGroupTheGroundMapByRegionAndEveryOtherMapAlone()
	{
		assertEquals("0/6_6.wmc", ChunkNames.chunk(0, 50, 50));
		assertEquals("0/6_6.wmc", ChunkNames.chunk(40000, 55, 48));
		assertEquals("0/7_6.wmc", ChunkNames.chunk(60000, 56, 48));
		assertEquals("10187.wmc", ChunkNames.chunk(10187, 3, 4));
		assertEquals("12.wmc", ChunkNames.chunk(20012, 3, 4));
		assertEquals("overview-20000.wmo", ChunkNames.overview(20000));
	}

	@Test
	public void damagedHeadersAreRefused()
	{
		byte[] good = new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, 3).raw(0, 0, 0).done();
		ChunkFile.parse(good, false);
		byte[] noData = new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, 3).done();
		assertRefused(noData);
		assertRefused(new byte[0]);
		assertRefused(new byte[15]);
		assertRefused(null);
		byte[] badMagic = good.clone();
		badMagic[0] = 'X';
		assertRefused(badMagic);
		assertRefusedBehindChecksum(Fuzz.fixChecksum(badMagic));
		byte[] badVersion = good.clone();
		badVersion[4] = 4;
		assertRefusedBehindChecksum(Fuzz.fixChecksum(badVersion));
		byte[] badFlags = good.clone();
		badFlags[5] = 1;
		assertRefusedBehindChecksum(Fuzz.fixChecksum(badFlags));
		for (int[] size : new int[][] {{0, 8}, {8, 0}, {4097, 8}, {8, 4097}, {65535, 65535}, {4096, 4096}, {2049, 2048}, {513, 256},
			{256, 513}, {1024, 1024}, {2048, 2048}, {4096, 1024}})
		{
			assertRefusedBehindChecksum(new Fuzz.Crafted().header(size[0], size[1]).directory(1, 0).entry(0, 0, 0, 3).raw(0, 0, 0).done());
		}
	}

	@Test
	public void tilesUpToFiveHundredAndTwelveASideAreAcceptedAndNoMore()
	{
		for (int[] size : new int[][] {{1, 1}, {256, 256}, {512, 512}, {512, 1}, {1, 512}})
		{
			byte[] chunk = new Fuzz.Crafted().header(size[0], size[1]).directory(1, 0).entry(0, 0, 0, 3).raw(0, 0, 0).done();
			ChunkFile parsed = ChunkFile.parse(chunk);
			assertEquals(size[0], parsed.tileWidth());
			assertEquals(size[1], parsed.tileHeight());
		}
		for (int[] size : new int[][] {{513, 1}, {1, 513}, {513, 513}, {4096, 1024}, {2048, 2048}})
		{
			assertRefused(new Fuzz.Crafted().header(size[0], size[1]).directory(1, 0).entry(0, 0, 0, 3).raw(0, 0, 0).done());
		}
	}

	/** A picture file of the given declared size with a payload of three bytes, which is enough to parse and not to decode. */
	private static byte[] pictureHeader(int width, int height)
	{
		byte[] body = {'W', 'M', 'P', 'I', ImageFile.VERSION, 0, (byte) (width >> 8), (byte) width, (byte) (height >> 8), (byte) height, 0, 0, 0};
		return ChunkBuilder.withChecksum(body);
	}

	@Test
	public void picturesUpToTwoThousandAndFortyEightASideAreAcceptedAndNoMore()
	{
		for (int[] size : new int[][] {{1, 1}, {1536, 1088}, {1400, 1428}, {2048, 976}, {976, 2048}, {2048, 1}, {1, 2048}})
		{
			ImageFile parsed = ImageFile.parse(pictureHeader(size[0], size[1]));
			assertEquals(size[0], parsed.width());
			assertEquals(size[1], parsed.height());
		}
		for (int[] size : new int[][] {{0, 8}, {8, 0}, {2049, 1}, {1, 2049}, {4096, 1024}, {1024, 4096}, {4096, 4096}, {65535, 65535}})
		{
			try
			{
				ImageFile.parse(pictureHeader(size[0], size[1]));
				fail("a picture of " + size[0] + " by " + size[1] + " should be refused");
			}
			catch (CodecException expected)
			{
				// refused before any payload is read
			}
		}
	}

	/** A forged header may not ask for more than two million pixels, whatever the sides are. */
	@Test
	public void picturesOfMoreThanTwoMillionPixelsAreRefusedWhateverTheSides()
	{
		for (int[] size : new int[][] {{2048, 977}, {977, 2048}, {2048, 2048}, {1500, 1334}, {1415, 1415}, {2000, 1001}})
		{
			assertTrue((long) size[0] * size[1] > TileDecoder.MAX_PICTURE_PIXELS);
			try
			{
				ImageFile.parse(pictureHeader(size[0], size[1]));
				fail("a picture of " + size[0] + " by " + size[1] + " should be refused");
			}
			catch (CodecException expected)
			{
				// refused before any payload is read
			}
		}
		for (int[] size : new int[][] {{2048, 976}, {1414, 1414}, {2000, 1000}, {1536, 1088}, {1536, 1302}})
		{
			assertTrue((long) size[0] * size[1] <= TileDecoder.MAX_PICTURE_PIXELS);
			assertEquals(size[0], ImageFile.parse(pictureHeader(size[0], size[1])).width());
		}
		assertTrue("the shipped overviews fit with room to spare", 1536 * 1088 < TileDecoder.MAX_PICTURE_PIXELS);
	}

	@Test
	public void aChecksumThatDoesNotMatchIsRefused()
	{
		byte[] good = Fuzz.sampleChunks().get(1);
		ChunkFile.parse(good);
		for (int i = 0; i < good.length; i += 7)
		{
			byte[] bad = good.clone();
			bad[i] ^= 0x10;
			assertRefused(bad);
		}
	}

	@Test
	public void countsThatClaimMoreThanTheDataHoldAreRefused()
	{
		for (long count : new long[] {0, 1, 2, 4097, 1_000_000, 2_000_000_000L, 0x7FFFFFFFL})
		{
			byte[] c = new Fuzz.Crafted().header(8, 8).directory(count, 0).entry(0, 0, 0, 3).raw(0, 0, 0).done();
			if (count == 1)
			{
				ChunkFile.parse(c);
			}
			else
			{
				assertRefusedBehindChecksum(c);
			}
		}
		// a count past 31 bits, or a number that never ends
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).varint(1L << 40).raw(1, 0, 0, 0).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).raw(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01).done());
		// map counts
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).varint(1).varint(0).varint(0).varint(0).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).varint(1).varint(256).done());
	}

	@Test
	public void lengthsAndOffsetsThatPointOutsideTheFileAreRefused()
	{
		byte[] payload = encoder.encode(Synth.flat(8, 8, 1), 8, 8);
		byte[] ok = new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, payload.length).bytes(payload).done();
		ChunkFile.parse(ok);
		// longer than the data
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, payload.length + 1).bytes(payload).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, 0x7FFFFFFFL).bytes(payload).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(2, 0).entry(0, 0, 0, 0x7FFFFFFFL).entry(0, 1, 0, 0x7FFFFFFFL)
			.bytes(payload).done());
		// shorter than the data: leftover bytes
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, payload.length - 1).bytes(payload).done());
		// squares and maps out of range or not listed
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(1, 0, 0, payload.length).bytes(payload).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0xFFFFFFL, 0, payload.length).bytes(payload).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(1, 0x1FFFFFL).entry(0, 0, 0, payload.length).bytes(payload).done());
	}

	@Test
	public void aliasesThatLoopOrPointNowhereAreRefused()
	{
		byte[] payload = encoder.encode(Synth.flat(8, 8, 1), 8, 8);
		// an alias of itself, of a later entry, of an alias, of nothing
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, 0).varint(0).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(2, 0).entry(0, 0, 0, 0).varint(1).entry(0, 1, 0, payload.length)
			.bytes(payload).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(3, 0).entry(0, 0, 0, payload.length).entry(0, 1, 0, 0).varint(0)
			.entry(0, 2, 0, 0).varint(1).bytes(payload).done());
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(2, 0).entry(0, 0, 0, payload.length).entry(0, 1, 0, 0)
			.varint(0x7FFFFFFFL).bytes(payload).done());
		// the legal form
		ChunkFile.parse(new Fuzz.Crafted().header(8, 8).directory(2, 0).entry(0, 0, 0, payload.length).entry(0, 1, 0, 0).varint(0)
			.bytes(payload).done());
		// the same square twice
		assertRefusedBehindChecksum(new Fuzz.Crafted().header(8, 8).directory(2, 0).entry(0, 0, 0, payload.length).entry(0, 0, 0, 0).varint(0)
			.bytes(payload).done());
	}

	@Test
	public void aPayloadThatIsTooShortOrHasUnknownFlagsFailsOnDecode()
	{
		ChunkFile f = ChunkFile.parse(new Fuzz.Crafted().header(8, 8).directory(1, 0).entry(0, 0, 0, 2).raw(0, 0).done(), false);
		try
		{
			f.decode(0, decoder, new int[64]);
			fail();
		}
		catch (CodecException expected)
		{
			assertEquals(null, expected.getCause());
		}
		byte[] flags = encoder.encode(Synth.terrain(8, 8, 1), 8, 8);
		flags[1] |= (byte) 0x80;
		try
		{
			decoder.decode(flags, 0, flags.length, 8, 8, new int[64]);
			fail();
		}
		catch (CodecException expected)
		{
			// refused
		}
	}

	@Test
	public void aStreamThatEndsEarlyIsCaught()
	{
		int[] p = Synth.terrain(64, 64, 9);
		byte[] bytes = encoder.encode(p, 64, 64);
		int caught = 0;
		for (int cut = 3; cut < bytes.length - 6; cut += 3)
		{
			try
			{
				decoder.decode(bytes, 0, cut, 64, 64, new int[64 * 64]);
			}
			catch (CodecException expected)
			{
				caught++;
			}
		}
		assertTrue("caught " + caught, caught > (bytes.length - 9) / 3 * 0.9);
	}
}
