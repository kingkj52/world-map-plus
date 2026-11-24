package com.worldmapplus.tiles;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.worldmapplus.tiles.codec.ChunkFile;
import com.worldmapplus.tiles.codec.ChunkNames;
import com.worldmapplus.tiles.codec.PackTiles;
import com.worldmapplus.tiles.codec.SmallPack;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The packer on a small made-up source folder: the same bytes every time, only the tiles the index lists, a runtime index
 * that lists the same squares, files nothing produced stop the run, and what comes out reads back through the real
 * {@link BundledTiles} as the pixels of the PNG files.
 */
public class PackTilesTest
{
	private Path work;
	private Path source;

	@Before
	public void makeSource() throws Exception
	{
		work = Files.createTempDirectory("wmp-packtest");
		source = work.resolve("src");
		// Ground map 0: two squares in one region, one in another; a floor; a second map. 0/60_60 is on disk and not listed.
		png(0, 50, 50, 1, false);
		png(0, 51, 50, 2, false);
		png(0, 56, 50, 3, false);
		png(0, 60, 60, 4, false);
		png(20000, 50, 50, 5, true);
		png(12, 3, 4, 6, false);
		png(12, 4, 4, 6, false);
		Files.write(source.resolve("index.json"), ("{\"maps\":{\"12\":[" + BundledTiles.key(4, 4) + "," + BundledTiles.key(3, 4) + "],\"0\":["
			+ BundledTiles.key(56, 50) + "," + BundledTiles.key(50, 50) + "," + BundledTiles.key(51, 50) + "],\"20000\":[" + BundledTiles.key(50, 50)
			+ "]}}").getBytes(StandardCharsets.UTF_8));
		BufferedImage overview = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 64; y++)
		{
			for (int x = 0; x < 96; x++)
			{
				overview.setRGB(x, y, ((x / 8) * 20) << 16 | ((y / 8) * 30) << 8 | 90);
			}
		}
		ImageIO.write(overview, "png", source.resolve("overview-0.png").toFile());
	}

	@After
	public void clean() throws IOException
	{
		try (Stream<Path> walk = Files.walk(work))
		{
			walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private void png(int mapId, int x, int y, int seed, boolean floor) throws IOException
	{
		BufferedImage image = new BufferedImage(256, 256, floor ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		for (int py = 0; py < 256; py++)
		{
			for (int px = 0; px < 256; px++)
			{
				int v = ((px / 16) * 7 + (py / 16) * 13 + seed * 29) % 11;
				int rgb = (30 + v * 18) << 16 | (210 - v * 12) << 8 | (seed * 17 + v * 6) % 256;
				boolean clear = floor && (px < 100 || py > 200);
				image.setRGB(px, py, clear ? 0 : 0xFF000000 | rgb);
			}
		}
		Path file = source.resolve(mapId + "/" + x + "_" + y + ".png");
		Files.createDirectories(file.getParent());
		ImageIO.write(image, "png", file.toFile());
	}

	private static Map<String, byte[]> tree(Path root) throws IOException
	{
		Map<String, byte[]> files = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(root))
		{
			for (Path p : walk.filter(Files::isRegularFile).collect(Collectors.toList()))
			{
				files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
			}
		}
		return files;
	}

	@Test
	public void packingTwiceGivesTheSameBytesWhateverTheThreadCount() throws Exception
	{
		PackTiles.pack(source, work.resolve("a"), 1, 0);
		PackTiles.pack(source, work.resolve("b"), 3, 0);
		Map<String, byte[]> a = tree(work.resolve("a"));
		Map<String, byte[]> b = tree(work.resolve("b"));
		assertEquals(a.keySet(), b.keySet());
		for (String name : a.keySet())
		{
			assertArrayEquals(name, a.get(name), b.get(name));
		}
		Set<String> expected = new TreeSet<>(Arrays.asList("0/6_6.wmc", "0/7_6.wmc", "12.wmc", "overview-0.wmo", "index.json"));
		assertEquals(expected, a.keySet());
	}

	@Test
	public void onlyTheTilesTheIndexListsArePacked() throws Exception
	{
		PackTiles.Report report = PackTiles.pack(source, work.resolve("out"), 2, 0);
		assertEquals(6, report.tiles);
		ChunkFile chunk = ChunkFile.parse(Files.readAllBytes(work.resolve("out/0/7_6.wmc")));
		assertEquals(1, chunk.size());
		assertTrue(chunk.contains(0, 56, 50));
		ChunkFile region = ChunkFile.parse(Files.readAllBytes(work.resolve("out/0/6_6.wmc")));
		assertEquals(3, region.size());
		assertTrue(region.contains(20000, 50, 50));
		assertFalse(region.contains(0, 60, 60));
		// Map 12's two identical tiles share one payload.
		ChunkFile other = ChunkFile.parse(Files.readAllBytes(work.resolve("out/12.wmc")));
		assertEquals(2, other.size());
		assertEquals(1, report.aliases);
	}

	@Test
	public void theRuntimeIndexListsTheSameSquaresInAFixedOrder() throws Exception
	{
		PackTiles.pack(source, work.resolve("out"), 2, 0);
		String index = new String(Files.readAllBytes(work.resolve("out/index.json")), StandardCharsets.UTF_8);
		assertEquals("{\"maps\":{\"0\":[" + BundledTiles.key(50, 50) + "," + BundledTiles.key(51, 50) + "," + BundledTiles.key(56, 50) + "],\"12\":["
			+ BundledTiles.key(3, 4) + "," + BundledTiles.key(4, 4) + "],\"20000\":[" + BundledTiles.key(50, 50) + "]}}\n", index);
	}

	@Test
	public void whatIsPackedReadsBackAsThePixelsOfThePngFiles() throws Exception
	{
		Path out = work.resolve("out");
		PackTiles.pack(source, out, 2, 0);
		BundledTiles tiles = BundledTiles.load(new Gson(), name ->
		{
			Path file = out.resolve(name);
			return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
		});
		int[][] squares = {{0, 50, 50}, {0, 51, 50}, {0, 56, 50}, {20000, 50, 50}, {12, 3, 4}, {12, 4, 4}};
		for (int[] s : squares)
		{
			BufferedImage got = tiles.load(s[0], s[1], s[2]);
			BufferedImage want = PngTiles.tile(source, s[0], s[1], s[2]);
			assertNotNull(Arrays.toString(s), got);
			assertEquals(want.getType(), got.getType());
			assertArrayEquals(Arrays.toString(s), want.getRGB(0, 0, 256, 256, null, 0, 256), got.getRGB(0, 0, 256, 256, null, 0, 256));
		}
		assertNull(tiles.load(0, 60, 60));
		BufferedImage overview = tiles.overview(0);
		BufferedImage wantOverview = PngTiles.read(source.resolve("overview-0.png"), false);
		assertArrayEquals(wantOverview.getRGB(0, 0, 96, 64, null, 0, 96), overview.getRGB(0, 0, 96, 64, null, 0, 96));
		assertNull(tiles.overview(20000));
	}

	/** With a limit, the small chunk files of the other maps move into small.pack and read back as the same pixels. */
	@Test
	public void smallChunksOfOtherMapsGoIntoThePackAndReadBackTheSame() throws Exception
	{
		Path loose = work.resolve("loose");
		Path packedOut = work.resolve("packed");
		PackTiles.pack(source, loose, 2, 0);
		PackTiles.pack(source, packedOut, 2, 0, null, false, 1 << 20);
		Map<String, byte[]> files = tree(packedOut);
		assertEquals(new TreeSet<>(Arrays.asList("0/6_6.wmc", "0/7_6.wmc", "small.pack", "overview-0.wmo", "index.json")), files.keySet());
		// The chunk inside the pack is the chunk file the loose run wrote, byte for byte.
		Map<Integer, byte[]> inside = new TreeMap<>();
		SmallPack.Index index = SmallPack.readIndex(new java.io.ByteArrayInputStream(files.get("small.pack")));
		for (int i = 0; i < index.size(); i++)
		{
			inside.put(index.map(i), SmallPack.read(new java.io.ByteArrayInputStream(files.get("small.pack")), index.offset(i), index.length(i)));
		}
		assertEquals(new TreeSet<>(Arrays.asList(12)), inside.keySet());
		assertArrayEquals(Files.readAllBytes(loose.resolve("12.wmc")), inside.get(12));
		BundledTiles tiles = BundledTiles.load(new Gson(), name ->
		{
			Path file = packedOut.resolve(name);
			return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
		});
		for (int[] s : new int[][] {{12, 3, 4}, {12, 4, 4}, {0, 50, 50}})
		{
			assertArrayEquals(Arrays.toString(s), PngTiles.tile(source, s[0], s[1], s[2]).getRGB(0, 0, 256, 256, null, 0, 256),
				tiles.load(s[0], s[1], s[2]).getRGB(0, 0, 256, 256, null, 0, 256));
		}
		// A limit smaller than the file keeps it a file; a run without a limit after a run with one stops on the old pack.
		PackTiles.pack(source, work.resolve("tight"), 2, 0, null, false, 10);
		assertFalse(Files.exists(work.resolve("tight/small.pack")));
		assertTrue(Files.exists(work.resolve("tight/12.wmc")));
		try
		{
			PackTiles.pack(source, packedOut, 2, 0, null, false, 0);
			fail("a pack nothing produced would have been shipped");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("small.pack"));
		}
	}

	@Test
	public void filesNothingProducedStopTheRunUnlessPruned() throws Exception
	{
		Path out = work.resolve("out");
		PackTiles.pack(source, out, 2, 0);
		Path stale = out.resolve("0/9_9.wmc");
		Files.write(stale, new byte[] {1, 2, 3});
		try
		{
			PackTiles.pack(source, out, 2, 0, null, false);
			fail("a chunk file nothing produced would have been shipped");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("9_9.wmc"));
		}
		assertTrue(Files.exists(stale));
		PackTiles.pack(source, out, 2, 0, null, true);
		assertFalse(Files.exists(stale));
	}

	@Test
	public void aListedTileWithNoPictureIsAnError() throws Exception
	{
		Files.delete(source.resolve("12/4_4.png"));
		try
		{
			PackTiles.pack(source, work.resolve("out"), 2, 0);
			fail("index.json lists a tile that is not there");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("12/4_4"));
		}
	}

	@Test
	public void aSourceFolderWithoutAnIndexIsRefused() throws Exception
	{
		Files.delete(source.resolve("index.json"));
		try
		{
			PackTiles.pack(source, work.resolve("out"), 2, 0);
			fail("the packer follows index.json");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("index.json"));
		}
	}

	@Test
	public void reportsAreWrittenOnlyWhenAskedFor() throws Exception
	{
		PackTiles.pack(source, work.resolve("quiet"), 2, 0);
		assertFalse(Files.exists(work.resolve("quiet/manifest.txt")));
		assertFalse(Files.exists(work.resolve("quiet/golden.txt")));
		PackTiles.pack(source, work.resolve("loud"), 2, 0, work.resolve("reports"), false);
		assertTrue(Files.exists(work.resolve("reports/manifest.txt")));
		assertTrue(Files.exists(work.resolve("reports/golden.txt")));
		assertFalse(Files.exists(work.resolve("loud/manifest.txt")));
		assertEquals(6, Files.readAllLines(work.resolve("reports/golden.txt")).size());
	}
}
