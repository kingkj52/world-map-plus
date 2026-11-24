package com.worldmapplus.tiles;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.tiles.codec.ChunkBuilder;
import com.worldmapplus.tiles.codec.ChunkFile;
import com.worldmapplus.tiles.codec.ChunkNames;
import com.worldmapplus.tiles.codec.SmallPack;
import com.worldmapplus.tiles.codec.TileEncoder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BundledTilesTest
{
	/** Files for a source that is not the jar, counting how often each is opened. */
	private static final class MemoryFiles implements BundledTiles.Resources
	{
		final Map<String, byte[]> files = new ConcurrentHashMap<>();
		final Map<String, AtomicInteger> opens = new ConcurrentHashMap<>();

		@Override
		public InputStream open(String name)
		{
			opens.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
			byte[] bytes = files.get(name);
			return bytes == null ? null : new ByteArrayInputStream(bytes);
		}

		int opened(String name)
		{
			AtomicInteger n = opens.get(name);
			return n == null ? 0 : n.get();
		}
	}

	/** A tile of a few colours in blocks, so it differs from every other seed. */
	private static int[] pattern(int seed, boolean clearCorner)
	{
		int[] px = new int[256 * 256];
		for (int y = 0; y < 256; y++)
		{
			for (int x = 0; x < 256; x++)
			{
				int v = ((x / 16) * 7 + (y / 16) * 13 + seed * 29) % 9;
				px[y * 256 + x] = 0xFF000000 | (40 + v * 20) << 16 | (200 - v * 15 + seed % 7) << 8 | (seed * 11 + v * 5) % 256;
			}
		}
		if (clearCorner)
		{
			for (int y = 0; y < 64; y++)
			{
				for (int x = 0; x < 64; x++)
				{
					px[y * 256 + x] = 0;
				}
			}
		}
		return px;
	}

	private static int[] noise(int seed)
	{
		Random r = new Random(seed);
		int[] palette = new int[200];
		for (int i = 0; i < palette.length; i++)
		{
			palette[i] = 0xFF000000 | r.nextInt(1 << 24);
		}
		int[] px = new int[256 * 256];
		for (int i = 0; i < px.length; i++)
		{
			px[i] = palette[r.nextInt(palette.length)];
		}
		return px;
	}

	private static byte[] chunk(Object... tiles)
	{
		ChunkBuilder builder = new ChunkBuilder(256, 256);
		for (int i = 0; i < tiles.length; i += 4)
		{
			builder.add((Integer) tiles[i], (Integer) tiles[i + 1], (Integer) tiles[i + 2], (int[]) tiles[i + 3]);
		}
		return builder.build(new TileEncoder());
	}

	private static Map<String, int[]> index(int mapId, int... squares)
	{
		Map<String, int[]> maps = new HashMap<>();
		int[] keys = new int[squares.length / 2];
		for (int i = 0; i < keys.length; i++)
		{
			keys[i] = BundledTiles.key(squares[2 * i], squares[2 * i + 1]);
		}
		maps.put(Integer.toString(mapId), keys);
		return maps;
	}

	private static int[] raw(BufferedImage image)
	{
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	@Test
	public void keyPacksSquaresReversibly()
	{
		int key = BundledTiles.key(50, 196);
		assertEquals(50, key >>> 16);
		assertEquals(196, key & 0xFFFF);
	}

	@Test
	public void theIndexDecidesWhichSquaresExist()
	{
		Map<String, int[]> maps = new HashMap<>();
		maps.put("0", new int[] {BundledTiles.key(50, 50), BundledTiles.key(51, 50)});
		maps.put("12", new int[] {BundledTiles.key(47, 147)});
		maps.put("junk", new int[] {1});
		BundledTiles tiles = BundledTiles.fromIndex(maps);
		assertTrue(tiles.exists(0, 50, 50));
		assertTrue(tiles.exists(12, 47, 147));
		assertFalse(tiles.exists(0, 47, 147));
		assertFalse(tiles.exists(5, 50, 50));
		assertEquals(2, tiles.tileCount(0));
	}

	@Test
	public void squaresNotInTheIndexAreNeverRead()
	{
		MemoryFiles files = new MemoryFiles();
		BundledTiles tiles = BundledTiles.fromIndex(new HashMap<>(), files);
		assertNull(tiles.load(0, 1, 1));
		assertEquals(0, files.opens.size());
	}

	@Test
	public void aTileIsDecodedFromItsChunkToTheSamePixels()
	{
		MemoryFiles files = new MemoryFiles();
		int[] a = pattern(1, false);
		int[] b = pattern(2, false);
		files.files.put(ChunkNames.chunk(0, 50, 50), chunk(0, 50, 50, a, 0, 51, 50, b));
		BundledTiles tiles = BundledTiles.fromIndex(index(0, 50, 50, 51, 50), files);
		BufferedImage image = tiles.load(0, 50, 50);
		assertNotNull(image);
		assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
		assertEquals(256, image.getWidth());
		assertArrayEquals(a, raw(image));
		assertArrayEquals(b, raw(tiles.load(0, 51, 50)));
	}

	@Test
	public void floorsKeepTheirClearPixelsAndGroundTilesAreOpaque()
	{
		MemoryFiles files = new MemoryFiles();
		int[] floor = pattern(3, true);
		int id = TileSource.tileMapId(0, 1);
		files.files.put(ChunkNames.chunk(id, 50, 50), chunk(id, 50, 50, floor));
		Map<String, int[]> maps = index(id, 50, 50);
		BundledTiles tiles = BundledTiles.fromIndex(maps, files);
		BufferedImage image = tiles.load(id, 50, 50);
		assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
		assertEquals(0, image.getRGB(10, 10) >>> 24);
		assertEquals(255, image.getRGB(100, 100) >>> 24);
		assertArrayEquals(floor, raw(image));
	}

	@Test
	public void decodedGroundTilesAreOrdinaryOpaqueRgbImages()
	{
		MemoryFiles files = new MemoryFiles();
		files.files.put(ChunkNames.chunk(0, 50, 50), chunk(0, 50, 50, pattern(1, false)));
		BundledTiles tiles = BundledTiles.fromIndex(index(0, 50, 50), files);
		BufferedImage image = tiles.load(0, 50, 50);
		assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
		assertEquals(3, image.getRaster().getNumBands());
		assertEquals(java.awt.Transparency.OPAQUE, image.getTransparency());
		assertFalse(image.getColorModel().hasAlpha());
		// It can be drawn, copied and read pixel by pixel like any other image.
		BufferedImage copy = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = copy.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		assertArrayEquals(pattern(1, false), raw(copy));
	}

	@Test
	public void aMissingChunkGivesNoTile()
	{
		MemoryFiles files = new MemoryFiles();
		BundledTiles tiles = BundledTiles.fromIndex(index(0, 50, 50), files);
		assertNull(tiles.load(0, 50, 50));
		assertNull(tiles.overview(0));
	}

	@Test
	public void aDamagedOrTruncatedChunkGivesNoTileAndThrowsNothing()
	{
		byte[] good = chunk(0, 50, 50, pattern(1, false), 0, 51, 50, pattern(2, false));
		List<byte[]> bad = new ArrayList<>();
		bad.add(new byte[0]);
		bad.add(new byte[] {1, 2, 3});
		bad.add(Arrays.copyOf(good, good.length / 2));
		bad.add(Arrays.copyOf(good, good.length - 1));
		byte[] flipped = good.clone();
		flipped[flipped.length / 2] ^= 0x10;
		bad.add(flipped);
		byte[] header = good.clone();
		header[0] = 'X';
		bad.add(header);
		byte[] noise = new byte[5000];
		new Random(4).nextBytes(noise);
		bad.add(noise);
		for (byte[] bytes : bad)
		{
			MemoryFiles files = new MemoryFiles();
			files.files.put(ChunkNames.chunk(0, 50, 50), bytes);
			BundledTiles tiles = BundledTiles.fromIndex(index(0, 50, 50, 51, 50), files);
			assertNull(tiles.load(0, 50, 50));
			assertNull(tiles.load(0, 51, 50));
			assertEquals(0, tiles.heldChunks());
		}
	}

	@Test
	public void aChunkThatLacksTheSquareTheIndexPromisesGivesNoTile()
	{
		MemoryFiles files = new MemoryFiles();
		files.files.put(ChunkNames.chunk(0, 50, 50), chunk(0, 50, 50, pattern(1, false)));
		BundledTiles tiles = BundledTiles.fromIndex(index(0, 50, 50, 51, 50), files);
		assertNotNull(tiles.load(0, 50, 50));
		assertNull(tiles.load(0, 51, 50));
	}

	@Test
	public void neighbouringSquaresShareOneRead()
	{
		MemoryFiles files = new MemoryFiles();
		String name = ChunkNames.chunk(0, 48, 48);
		files.files.put(name, chunk(0, 48, 48, pattern(1, false), 0, 49, 48, pattern(2, false), 0, 55, 55, pattern(3, false)));
		BundledTiles tiles = BundledTiles.fromIndex(index(0, 48, 48, 49, 48, 55, 55), files);
		for (int i = 0; i < 3; i++)
		{
			assertNotNull(tiles.load(0, 48, 48));
			assertNotNull(tiles.load(0, 49, 48));
			assertNotNull(tiles.load(0, 55, 55));
		}
		assertEquals(1, files.opened(name));
		assertEquals(1, tiles.heldChunks());
		assertEquals(1, tiles.idleDecoderCount());
	}

	@Test
	public void concurrentReadersOfOneChunkReadItOnceAndGetTheRightPixels() throws Exception
	{
		MemoryFiles files = new MemoryFiles();
		String name = ChunkNames.chunk(0, 48, 48);
		int[][] expected = new int[8][];
		Object[] tiles = new Object[8 * 4];
		int[] squares = new int[16];
		for (int i = 0; i < 8; i++)
		{
			expected[i] = pattern(i + 1, false);
			tiles[i * 4] = 0;
			tiles[i * 4 + 1] = 48 + i;
			tiles[i * 4 + 2] = 48;
			tiles[i * 4 + 3] = expected[i];
			squares[2 * i] = 48 + i;
			squares[2 * i + 1] = 48;
		}
		files.files.put(name, chunk(tiles));
		BundledTiles source = BundledTiles.fromIndex(index(0, squares), files);
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try
		{
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int t = 0; t < 6; t++)
			{
				results.add(pool.submit(() ->
				{
					go.await();
					boolean ok = true;
					for (int round = 0; round < 6; round++)
					{
						for (int i = 0; i < 8; i++)
						{
							BufferedImage image = source.load(0, 48 + i, 48);
							int[] got = raw(image);
							ok &= Arrays.equals(expected[i], got);
						}
					}
					return ok;
				}));
			}
			go.countDown();
			for (Future<Boolean> f : results)
			{
				assertTrue(f.get());
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		assertEquals(1, files.opened(name));
		assertTrue("idle decoders " + source.idleDecoderCount(), source.idleDecoderCount() <= 2);
	}

	@Test
	public void theChunkCacheStaysWithinItsBudgetAndEvictsTheLeastRecentlyUsed()
	{
		MemoryFiles files = new MemoryFiles();
		int chunks = 20;
		long budget = 3_000;
		long sum = 0;
		Map<String, int[]> maps = new HashMap<>();
		for (int c = 0; c < chunks; c++)
		{
			int mapId = 100 + c;
			byte[] bytes = chunk(mapId, 0, 0, pattern(c, false), mapId, 1, 0, pattern(c + 50, false), mapId, 2, 0, pattern(c + 99, false));
			sum += bytes.length;
			files.files.put(ChunkNames.chunk(mapId, 0, 0), bytes);
			maps.put(Integer.toString(mapId), new int[] {BundledTiles.key(0, 0)});
		}
		assertTrue("the test chunks must outweigh the budget: " + sum, sum + chunks * 3 * 96 > 3 * budget);
		assertEquals(8L << 20, BundledTiles.CHUNK_BUDGET);
		BundledTiles source = BundledTiles.fromIndex(maps, files, budget);
		for (int c = 0; c < chunks; c++)
		{
			assertNotNull(source.load(100 + c, 0, 0));
			assertTrue("held " + source.heldChunkBytes(), source.heldChunkBytes() <= budget);
		}
		assertTrue(source.heldChunks() < chunks);
		assertTrue(source.heldChunks() >= 2);
		// The newest chunk is still held, the oldest was dropped and is read again.
		assertNotNull(source.load(100 + chunks - 1, 0, 0));
		assertEquals(1, files.opened(ChunkNames.chunk(100 + chunks - 1, 0, 0)));
		assertNotNull(source.load(100, 0, 0));
		assertEquals(2, files.opened(ChunkNames.chunk(100, 0, 0)));
	}

	@Test
	public void aChunkLargerThanTheWholeBudgetIsStillServed()
	{
		MemoryFiles files = new MemoryFiles();
		files.files.put(ChunkNames.chunk(0, 50, 50), chunk(0, 50, 50, pattern(1, false), 0, 51, 50, pattern(2, false)));
		BundledTiles source = BundledTiles.fromIndex(index(0, 50, 50, 51, 50), files, 10);
		assertNotNull(source.load(0, 50, 50));
		assertNotNull(source.load(0, 51, 50));
		assertEquals(1, files.opened(ChunkNames.chunk(0, 50, 50)));
		assertEquals(1, source.heldChunks());
	}

	@Test
	public void releasingDropsTheCachesAndTheSourceStaysUsable()
	{
		MemoryFiles files = new MemoryFiles();
		String name = ChunkNames.chunk(0, 50, 50);
		files.files.put(name, chunk(0, 50, 50, pattern(1, false)));
		BundledTiles tiles = BundledTiles.fromIndex(index(0, 50, 50), files);
		assertNotNull(tiles.load(0, 50, 50));
		assertEquals(1, tiles.heldChunks());
		assertEquals(1, tiles.idleDecoderCount());
		assertTrue(tiles.heldChunkBytes() > 0);
		tiles.release();
		assertEquals(0, tiles.heldChunks());
		assertEquals(0, tiles.idleDecoderCount());
		assertEquals(0, tiles.heldChunkBytes());
		assertNotNull(tiles.load(0, 50, 50));
		assertEquals(2, files.opened(name));
	}

	@Test
	public void anOversizeResourceIsRefusedWithoutReadingItAll()
	{
		long[] served = {0};
		InputStream endless = new InputStream()
		{
			@Override
			public int read()
			{
				served[0]++;
				return 0;
			}

			@Override
			public int read(byte[] b, int off, int len)
			{
				served[0] += len;
				Arrays.fill(b, off, off + len, (byte) 0);
				return len;
			}
		};
		try
		{
			assertNull(BundledTiles.readCapped(endless, 100_000));
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
		assertTrue("served " + served[0], served[0] <= 100_001 + 64);

		BundledTiles tiles = BundledTiles.fromIndex(index(0, 50, 50), name -> name.endsWith(".wmc") ? endlessStream() : null);
		assertNull(tiles.load(0, 50, 50));
		assertEquals(0, tiles.heldChunks());
	}

	private static InputStream endlessStream()
	{
		return new InputStream()
		{
			private long served;

			@Override
			public int read()
			{
				return served++ > 100L * BundledTiles.MAX_CHUNK_BYTES ? -1 : 0;
			}

			@Override
			public int read(byte[] b, int off, int len)
			{
				if (served > 100L * BundledTiles.MAX_CHUNK_BYTES)
				{
					return -1;
				}
				served += len;
				return len;
			}
		};
	}

	@Test
	public void readCappedReturnsAnythingUpToTheCap() throws IOException
	{
		for (int size : new int[] {0, 1, 100, 65_535, 65_536, 65_537, 200_000, 500_000})
		{
			byte[] data = new byte[size];
			new Random(size).nextBytes(data);
			assertArrayEquals(data, BundledTiles.readCapped(new ByteArrayInputStream(data), 500_000));
			if (size > 0)
			{
				assertNull(BundledTiles.readCapped(new ByteArrayInputStream(data), size - 1));
			}
		}
	}

	@Test
	public void anOverviewIsDecodedToAnRgbPicture()
	{
		MemoryFiles files = new MemoryFiles();
		int[] px = new int[300 * 200];
		for (int i = 0; i < px.length; i++)
		{
			px[i] = 0xFF000000 | ((i % 300) / 20 * 9) << 16 | ((i / 300) / 25 * 21) << 8 | 77;
		}
		files.files.put(ChunkNames.overview(0), ChunkBuilder.imageFile(px, 300, 200, new TileEncoder()));
		BundledTiles tiles = BundledTiles.fromIndex(new HashMap<>(), files);
		BufferedImage image = tiles.overview(0);
		assertNotNull(image);
		assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
		assertEquals(300, image.getWidth());
		assertArrayEquals(px, raw(image));
		assertNull(tiles.overview(20000));
		files.files.put(ChunkNames.overview(20000), Arrays.copyOf(files.files.get(ChunkNames.overview(0)), 500));
		assertNull(tiles.overview(20000));
	}

	@Test
	public void aMissingOrDamagedIndexGivesASourceWithNoTiles()
	{
		MemoryFiles files = new MemoryFiles();
		assertEquals(0, BundledTiles.load(new Gson(), files).tileCount(0));
		files.files.put("index.json", "{\"maps\": [[[".getBytes(StandardCharsets.UTF_8));
		assertEquals(0, BundledTiles.load(new Gson(), files).tileCount(0));
		files.files.put("index.json", "{\"maps\":{\"0\":[1,2,3],\"x\":[4]}}".getBytes(StandardCharsets.UTF_8));
		BundledTiles tiles = BundledTiles.load(new Gson(), files);
		assertEquals(3, tiles.tileCount(0));
	}

	/** Chunk files of other maps are read out of small.pack, and give the same pixels as when they are files of their own. */
	@Test
	public void aChunkInsideTheSmallPackGivesTheSamePixels()
	{
		int[] a = pattern(4, false);
		int[] b = pattern(5, false);
		int[] c = pattern(6, false);
		byte[] twelve = chunk(12, 47, 147, a, 12, 48, 147, b);
		byte[] thirteen = chunk(13, 3, 3, c);
		java.util.TreeMap<Integer, byte[]> packed = new java.util.TreeMap<>();
		packed.put(12, twelve);
		packed.put(13, thirteen);
		MemoryFiles files = new MemoryFiles();
		files.files.put(SmallPack.NAME, SmallPack.build(packed));
		Map<String, int[]> maps = new HashMap<>(index(12, 47, 147, 48, 147));
		maps.putAll(index(13, 3, 3));
		BundledTiles tiles = BundledTiles.fromIndex(maps, files);
		assertArrayEquals(a, raw(tiles.load(12, 47, 147)));
		assertArrayEquals(b, raw(tiles.load(12, 48, 147)));
		assertArrayEquals(c, raw(tiles.load(13, 3, 3)));
		assertEquals("no loose file was asked for", 0, files.opened("12.wmc"));
		// The pack is read whole once and then kept (softly), so three loads of two chunks open it once.
		assertEquals(1, files.opened(SmallPack.NAME));
	}

	@Test
	public void aLooseFileStillWorksAndWinsWhenThePackLacksTheMap()
	{
		java.util.TreeMap<Integer, byte[]> packed = new java.util.TreeMap<>();
		packed.put(13, chunk(13, 3, 3, pattern(6, false)));
		MemoryFiles files = new MemoryFiles();
		files.files.put(SmallPack.NAME, SmallPack.build(packed));
		int[] a = pattern(4, false);
		files.files.put("12.wmc", chunk(12, 47, 147, a));
		BundledTiles tiles = BundledTiles.fromIndex(index(12, 47, 147), files);
		assertArrayEquals(a, raw(tiles.load(12, 47, 147)));
	}

	@Test
	public void aDamagedOrMissingPackGivesNoTilesAndNoError()
	{
		MemoryFiles files = new MemoryFiles();
		files.files.put(SmallPack.NAME, new byte[] {'W', 'M', 'P', 'K', 1, 99});
		BundledTiles tiles = BundledTiles.fromIndex(index(12, 47, 147), files);
		assertNull(tiles.load(12, 47, 147));
		assertNull(BundledTiles.fromIndex(index(12, 47, 147), new MemoryFiles()).load(12, 47, 147));
	}

	@Test
	public void aPackedChunkWhoseBytesAreDamagedGivesNoTile()
	{
		byte[] twelve = chunk(12, 47, 147, pattern(4, false));
		twelve[twelve.length / 2] ^= 1;
		java.util.TreeMap<Integer, byte[]> packed = new java.util.TreeMap<>();
		packed.put(12, twelve);
		MemoryFiles files = new MemoryFiles();
		files.files.put(SmallPack.NAME, SmallPack.build(packed));
		assertNull(BundledTiles.fromIndex(index(12, 47, 147), files).load(12, 47, 147));
	}

	@Test
	public void theBundledResourcesAreConsistent() throws Exception
	{
		BundledTiles tiles = BundledTiles.load(new Gson());
		int checked = 0;
		for (int x = 40; x < 60 && checked < 3; x++)
		{
			for (int y = 40; y < 60 && checked < 3; y++)
			{
				if (tiles.exists(0, x, y))
				{
					BufferedImage image = tiles.load(0, x, y);
					assertEquals(256, image.getWidth());
					assertEquals(256, image.getHeight());
					checked++;
				}
			}
		}
		assertEquals(3, checked);
	}

	/**
	 * What the jar carries under tiles/: the index, one chunk file for every chunk the index needs, with exactly the
	 * squares the index lists in it, the overviews, and nothing else (no PNG, no leftover).
	 */
	@Test
	public void theTilesFolderHoldsExactlyWhatTheIndexNeeds() throws Exception
	{
		Map<String, Set<String>> wanted = new HashMap<>();
		try (InputStream in = BundledTilesTest.class.getResourceAsStream("/com/worldmapplus/tiles/index.json"))
		{
			assertNotNull("index.json is not bundled", in);
			com.google.gson.JsonObject json = new Gson().fromJson(new String(readAll(in), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
			for (Map.Entry<String, com.google.gson.JsonElement> e : json.getAsJsonObject("maps").entrySet())
			{
				int mapId = Integer.parseInt(e.getKey());
				for (com.google.gson.JsonElement v : e.getValue().getAsJsonArray())
				{
					int x = v.getAsInt() >>> 16;
					int y = v.getAsInt() & 0xFFFF;
					wanted.computeIfAbsent(ChunkNames.chunk(mapId, x, y), k -> new TreeSet<>()).add(mapId + ":" + x + "_" + y);
				}
			}
		}
		assertTrue(wanted.size() > 200);
		Set<String> expectedFiles = new TreeSet<>(wanted.keySet());
		// The small chunk files of the other maps are inside small.pack, which must hold exactly those and nothing else.
		Map<Integer, byte[]> packed = new HashMap<>();
		try (InputStream in = BundledTilesTest.class.getResourceAsStream("/com/worldmapplus/tiles/" + SmallPack.NAME))
		{
			assertNotNull("small.pack is not bundled", in);
			byte[] all = readAll(in);
			SmallPack.Index pack = SmallPack.readIndex(new java.io.ByteArrayInputStream(all));
			for (int i = 0; i < pack.size(); i++)
			{
				packed.put(pack.map(i), java.util.Arrays.copyOfRange(all, pack.offset(i), pack.offset(i) + pack.length(i)));
			}
		}
		assertTrue(packed.size() > 150);
		for (Integer map : packed.keySet())
		{
			assertTrue("small.pack holds map " + map + " which the index does not need", expectedFiles.remove(map + ".wmc"));
			assertNull("map " + map + " is also a file of its own",
				BundledTilesTest.class.getResourceAsStream("/com/worldmapplus/tiles/" + map + ".wmc"));
		}
		expectedFiles.add(SmallPack.NAME);
		expectedFiles.add("index.json");
		for (int overview : new int[] {0, 20000, 40000, 60000})
		{
			expectedFiles.add(ChunkNames.overview(overview));
		}
		for (Map.Entry<String, Set<String>> e : wanted.entrySet())
		{
			int packedMap = ChunkNames.packedBase(e.getKey());
			try (InputStream in = BundledTilesTest.class.getResourceAsStream("/com/worldmapplus/tiles/" + e.getKey()))
			{
				byte[] bytes = in != null ? readAll(in) : packed.get(packedMap);
				assertNotNull(e.getKey() + " is missing", bytes);
				ChunkFile chunk = ChunkFile.parse(bytes);
				Set<String> held = new TreeSet<>();
				for (ChunkFile.Entry entry : chunk.entries())
				{
					held.add(entry.mapId + ":" + entry.x + "_" + entry.y);
				}
				assertEquals(e.getKey(), e.getValue(), held);
			}
		}
		URI root = BundledTilesTest.class.getResource("/com/worldmapplus/tiles/index.json").toURI();
		if ("file".equals(root.getScheme()))
		{
			Path folder = Paths.get(root).getParent();
			Set<String> found = new TreeSet<>();
			try (java.util.stream.Stream<Path> walk = Files.walk(folder))
			{
				walk.filter(Files::isRegularFile).forEach(f -> found.add(folder.relativize(f).toString().replace('\\', '/')));
			}
			assertEquals(expectedFiles, found);
		}
	}

	private static byte[] readAll(InputStream in) throws IOException
	{
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[65536];
		int n;
		while ((n = in.read(buffer)) >= 0)
		{
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}
}
