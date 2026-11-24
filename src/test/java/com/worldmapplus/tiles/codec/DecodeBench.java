package com.worldmapplus.tiles.codec;

import com.google.gson.Gson;
import com.worldmapplus.tiles.BundledTiles;
import com.worldmapplus.tiles.TileSource;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Times the tile decoder by hand, in two modes. Developer machine only, never part of the plugin or of the test suite.
 * <p>
 * {@code warm [folder]} decodes every tile of the chunk files in the folder (default: the plugin's resources) once to warm
 * the JIT and once more with a timer around each tile, and prints the mean and some percentiles per group (the surface,
 * the floors above it, every other map) and a digest of all the pixels, which must not change when only the speed of the
 * decoder does.
 * <p>
 * {@code cold N1 N2 ...} is for a fresh JVM each time: it asks {@link BundledTiles} for the tiles around Lumbridge at the
 * half pixel scale, nearest the middle first, one after the other like the loader thread does, and prints how long the
 * first N1, N2, ... took from the first request, with the class loading and the JIT warm-up included.
 */
public final class DecodeBench
{
	private static final String DEFAULT_FOLDER = "src/main/resources/com/worldmapplus/tiles";

	private DecodeBench()
	{
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		String mode = args.length > 0 ? args[0] : "warm";
		if (mode.equals("cold"))
		{
			int[] marks = new int[args.length - 1];
			for (int i = 0; i < marks.length; i++)
			{
				marks[i] = Integer.parseInt(args[i + 1]);
			}
			cold(marks);
		}
		else if (mode.equals("overview"))
		{
			overviews(Paths.get(args.length > 1 ? args[1] : DEFAULT_FOLDER));
		}
		else
		{
			warm(Paths.get(args.length > 1 ? args[1] : DEFAULT_FOLDER), args.length > 2 ? Integer.parseInt(args[2]) : 1);
		}
	}

	private static void warm(Path folder, int stride) throws IOException
	{
		List<ChunkFile> chunks = new ArrayList<>();
		// By the path the chunk file has, or would have if it were not inside small.pack, so the order (and the digest) is the same.
		java.util.TreeMap<String, byte[]> files = new java.util.TreeMap<>();
		try (DirectoryStream<Path> top = Files.newDirectoryStream(folder, "*.wmc"))
		{
			for (Path f : top)
			{
				files.put(f.toString(), Files.readAllBytes(f));
			}
		}
		try (DirectoryStream<Path> sub = Files.newDirectoryStream(folder.resolve("0"), "*.wmc"))
		{
			for (Path f : sub)
			{
				files.put(f.toString(), Files.readAllBytes(f));
			}
		}
		Path pack = folder.resolve(SmallPack.NAME);
		if (Files.exists(pack))
		{
			byte[] all = Files.readAllBytes(pack);
			SmallPack.Index index = SmallPack.readIndex(new java.io.ByteArrayInputStream(all));
			for (int i = 0; i < index.size(); i++)
			{
				files.put(folder.resolve(index.map(i) + ".wmc").toString(), Arrays.copyOfRange(all, index.offset(i), index.offset(i) + index.length(i)));
			}
		}
		for (byte[] bytes : files.values())
		{
			chunks.add(ChunkFile.parse(bytes));
		}
		TileDecoder decoder = new TileDecoder();
		int[] out = new int[256 * 256];
		int tiles = 0;
		int seq = 0;
		for (ChunkFile c : chunks)
		{
			for (int e = 0; e < c.size(); e++)
			{
				if (!c.isAlias(e) && seq++ % stride == 0)
				{
					c.decode(e, decoder, out);
					tiles++;
				}
			}
		}
		System.out.println("warm-up pass done, " + tiles + " tiles");
		String[] names = {"surface", "floors", "other"};
		double[][] times = new double[3][tiles];
		int[] counts = new int[3];
		long digest = 17;
		seq = 0;
		for (ChunkFile c : chunks)
		{
			for (int e = 0; e < c.size(); e++)
			{
				if (c.isAlias(e) || seq++ % stride != 0)
				{
					continue;
				}
				long t0 = System.nanoTime();
				c.decode(e, decoder, out);
				long dt = System.nanoTime() - t0;
				int mapId = c.mapId(e);
				int g = mapId == 0 ? 0 : mapId >= TileSource.FLOOR_STRIDE ? 1 : 2;
				times[g][counts[g]++] = dt / 1e6;
				for (int v : out)
				{
					digest = digest * 31 + v;
				}
			}
		}
		double total = 0;
		int all = 0;
		for (int g = 0; g < 3; g++)
		{
			double[] t = Arrays.copyOf(times[g], counts[g]);
			Arrays.sort(t);
			double sum = 0;
			for (double v : t)
			{
				sum += v;
			}
			total += sum;
			all += t.length;
			if (t.length > 0)
			{
				System.out.printf("%-8s n=%4d mean=%.3f p50=%.3f p90=%.3f p99=%.3f max=%.3f ms%n", names[g], t.length, sum / t.length,
					t[t.length / 2], t[(int) (t.length * 0.9)], t[(int) (t.length * 0.99)], t[t.length - 1]);
			}
		}
		System.out.printf("all      n=%4d mean=%.3f ms, total %.0f ms%n", all, total / all, total);
		System.out.println("digest " + Long.toHexString(digest));
	}

	private static void overviews(Path folder) throws IOException
	{
		TileDecoder decoder = new TileDecoder();
		for (int round = 0; round < 4; round++)
		{
			StringBuilder line = new StringBuilder("round " + round + ":");
			for (int map : new int[] {0, 20000, 40000, 60000})
			{
				ImageFile file = ImageFile.parse(Files.readAllBytes(folder.resolve(ChunkNames.overview(map))));
				int[] out = new int[file.width() * file.height()];
				long t0 = System.nanoTime();
				file.decode(decoder, out);
				line.append(String.format("  %d (%dx%d) %.0f ms", map, file.width(), file.height(), (System.nanoTime() - t0) / 1e6));
			}
			System.out.println(line);
		}
	}

	private static void cold(int[] marks) throws Exception
	{
		int passes = Integer.getInteger("wmp.passes", 1);
		for (int pass = 0; pass < passes; pass++)
		{
			coldPass(marks);
		}
	}

	private static void coldPass(int[] marks) throws Exception
	{
		BundledTiles source = BundledTiles.load(new Gson());
		double cx = 3222.5 / 64;
		double cy = 3218.5 / 64;
		List<int[]> squares = new ArrayList<>();
		for (int x = (int) cx - 21; x <= (int) cx + 21; x++)
		{
			for (int y = (int) cy - 12; y <= (int) cy + 12; y++)
			{
				if (source.exists(0, x, y))
				{
					squares.add(new int[] {x, y});
				}
			}
		}
		squares.sort(Comparator.comparingDouble(s -> Math.hypot(s[0] + 0.5 - cx, s[1] + 0.5 - cy)));
		int next = 0;
		long t0 = System.nanoTime();
		StringBuilder line = new StringBuilder();
		long digest = 17;
		for (int i = 0; i < squares.size(); i++)
		{
			int[] s = squares.get(i);
			long s0 = System.nanoTime();
			BufferedImage image = source.load(0, s[0], s[1]);
			if (i < 40 && Boolean.getBoolean("wmp.series"))
			{
				System.out.printf("%d:%.1f ", i, (System.nanoTime() - s0) / 1e6);
			}
			digest = digest * 31 + image.getRGB(0, 0) + image.getRGB(100, 100);
			if (next < marks.length && i + 1 == marks[next])
			{
				line.append(String.format("%d tiles %.0f ms  ", i + 1, (System.nanoTime() - t0) / 1e6));
				next++;
			}
		}
		line.append(String.format("all %d tiles %.0f ms", squares.size(), (System.nanoTime() - t0) / 1e6));
		System.out.println(line + " (" + Long.toHexString(digest) + ")");
	}
}
