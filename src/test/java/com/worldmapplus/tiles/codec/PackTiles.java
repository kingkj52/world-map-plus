package com.worldmapplus.tiles.codec;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Packs the PNG tile sources into chunk files and verifies the result. Run it with the tile source (the folder that has
 * {@code index.json}, {@code <mapId>/<x>_<y>.png} and {@code overview-<mapId>.png}, which is {@code tools/tile-src}) and
 * an output folder (the plugin's {@code src/main/resources/com/worldmapplus/tiles}):
 * <pre>
 * PackTiles <tile source> <output folder> [--threads N] [--table-bits N] [--reports DIR] [--prune] [--loose]
 * </pre>
 * Chunk files of maps other than the ground map that are under {@link SmallPack#PACK_BELOW} bytes go into one {@code small.pack}
 * ({@link SmallPack}), which saves a jar entry header for each; {@code --loose} keeps every chunk a file.
 * The tiles packed are the ones {@code index.json} lists, and no others. The output folder gets the chunk files under the
 * names {@link ChunkNames} gives, one {@code overview-<mapId>.wmo} per overview, and the runtime {@code index.json}, which
 * lists the same squares in a fixed order. Every chunk is parsed back from its own bytes and every tile in it is decoded
 * and compared with the PNG, pixel for pixel, as a raw int array and as getRGB values. Any difference stops the run with an
 * error. The output is the same bytes every time the sources are the same.
 * <p>
 * With {@code --reports DIR} it also writes a {@code manifest.txt} that lists every file and a {@code golden.txt} with
 * the SHA-256 of the pixels of every tile as the plugin used to read them. Chunk and overview files already in the output
 * folder that this run did not write stop it, because they would be shipped, unless {@code --prune} deletes them.
 */
public final class PackTiles
{
	/** The result of a run. */
	public static final class Report
	{
		public int chunks;
		public int tiles;
		public int aliases;
		public long chunkBytes;
		public int overviews;
		public long overviewBytes;
		public final Map<String, long[]> byGroup = new TreeMap<>();
		public final List<String> manifest = new ArrayList<>();
		public final List<String> golden = new ArrayList<>();
	}

	/**
	 * Context table size of the overview pictures, log2. The encoder's own choice for a picture this large is the biggest
	 * table (2^18), which is 4 percent smaller on disk but takes about 1.0 seconds to decode against 0.6 seconds for 2^16
	 * and 0.5 for 2^15 (which is 4 percent bigger than 2^16 on disk). An overview is decoded on the shared executor thread
	 * while the map shows nothing, so the smaller table wins. See docs/PLAN.md.
	 */
	static final int OVERVIEW_TABLE_BITS = 15;

	private PackTiles()
	{
	}

	public static void main(String[] args) throws Exception
	{
		List<String> positional = new ArrayList<>();
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
		int tableBits = 0;
		Path reports = null;
		boolean prune = false;
		int packBelow = SmallPack.PACK_BELOW;
		for (int i = 0; i < args.length; i++)
		{
			switch (args[i])
			{
				case "--loose":
					packBelow = 0;
					break;
				case "--threads":
					threads = Integer.parseInt(args[++i]);
					break;
				case "--table-bits":
					tableBits = Integer.parseInt(args[++i]);
					break;
				case "--reports":
					reports = Paths.get(args[++i]);
					break;
				case "--prune":
					prune = true;
					break;
				default:
					positional.add(args[i]);
			}
		}
		if (positional.size() != 2)
		{
			System.err.println("usage: PackTiles <tile source> <output folder> [--threads N] [--table-bits N] [--reports DIR] [--prune] [--loose]");
			System.exit(2);
		}
		long start = System.nanoTime();
		Report r = pack(Paths.get(positional.get(0)), Paths.get(positional.get(1)), threads, tableBits, reports, prune, packBelow);
		System.out.printf("%d tiles in %d chunks (%d aliases), chunk files %d bytes; %d overviews, %d bytes; all verified; %.1f s%n",
			r.tiles, r.chunks, r.aliases, r.chunkBytes, r.overviews, r.overviewBytes, (System.nanoTime() - start) / 1e9);
		for (Map.Entry<String, long[]> e : r.byGroup.entrySet())
		{
			System.out.printf("  %-8s tiles %5d  aliases %4d  bytes %9d%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
		}
	}

	static String group(int mapId)
	{
		int base = ChunkNames.baseMap(mapId);
		if (base == ChunkNames.REGIONAL_MAP)
		{
			return mapId == base ? "surface" : "floors";
		}
		return base < 10000 ? "game" : "extra";
	}

	static String sha256(int[] pixels)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] b = new byte[pixels.length * 4];
			for (int i = 0; i < pixels.length; i++)
			{
				int v = pixels[i];
				b[i * 4] = (byte) (v >> 16);
				b[i * 4 + 1] = (byte) (v >> 8);
				b[i * 4 + 2] = (byte) v;
				b[i * 4 + 3] = (byte) (v >>> 24);
			}
			byte[] d = md.digest(b);
			StringBuilder sb = new StringBuilder();
			for (byte x : d)
			{
				sb.append(String.format("%02x", x));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** One chunk's worth of work: build, parse back, compare every tile with its PNG. */
	private static final class ChunkJob
	{
		final String name;
		final List<Corpus.Ref> refs = new ArrayList<>();

		ChunkJob(String name)
		{
			this.name = name;
		}
	}

	private static final class ChunkDone
	{
		String name;
		byte[] bytes;
		int entries;
		int aliases;
		final List<String> golden = new ArrayList<>();
		final Map<String, long[]> byGroup = new TreeMap<>();
	}

	/** As {@link #pack(Path, Path, int, int, Path, boolean)} with no reports and no pruning. */
	public static Report pack(Path in, Path out, int threads, int tableBitsOverride) throws Exception
	{
		return pack(in, out, threads, tableBitsOverride, null, false);
	}

	public static Report pack(Path in, Path out, int threads, int tableBitsOverride, Path reports, boolean prune) throws Exception
	{
		return pack(in, out, threads, tableBitsOverride, reports, prune, 0);
	}

	/**
	 * As above; chunk files of maps other than the ground map that are smaller than packBelow bytes go into one
	 * {@link SmallPack} file instead of files of their own (0 keeps every chunk a file).
	 */
	public static Report pack(Path in, Path out, int threads, int tableBitsOverride, Path reports, boolean prune, int packBelow)
		throws Exception
	{
		List<Corpus.Ref> refs = Corpus.scan(in);
		Map<Integer, byte[]> small = new TreeMap<>();
		Set<Path> written = new HashSet<>();
		Map<String, ChunkJob> jobs = new TreeMap<>();
		for (Corpus.Ref r : refs)
		{
			jobs.computeIfAbsent(ChunkNames.chunk(r.mapId, r.x, r.y), ChunkJob::new).refs.add(r);
		}
		Files.createDirectories(out);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		Report report = new Report();
		try
		{
			List<Future<ChunkDone>> futures = new ArrayList<>();
			for (ChunkJob job : jobs.values())
			{
				futures.add(pool.submit(() -> doChunk(job, tableBitsOverride)));
			}
			Map<String, Future<byte[]>> overviewFutures = new TreeMap<>();
			Map<String, int[]> overviewSizes = new ConcurrentHashMap<>();
			for (int id : Corpus.overviews(in))
			{
				Path png = in.resolve("overview-" + id + ".png");
				overviewFutures.put(ChunkNames.overview(id), pool.submit(() -> doOverview(png, overviewSizes, ChunkNames.overview(id), tableBitsOverride)));
			}
			Map<String, String> goldenByRel = new TreeMap<>();
			for (Future<ChunkDone> f : futures)
			{
				ChunkDone d = f.get();
				int packedMap = ChunkNames.packedBase(d.name);
				if (packBelow > 0 && packedMap >= 0 && d.bytes.length < packBelow)
				{
					small.put(packedMap, d.bytes);
				}
				else
				{
					Path target = out.resolve(d.name);
					Files.createDirectories(target.getParent());
					Files.write(target, d.bytes);
					written.add(target.toAbsolutePath().normalize());
					if (!Arrays.equals(Files.readAllBytes(target), d.bytes))
					{
						throw new IllegalStateException("written file differs: " + target);
					}
				}
				report.chunks++;
				report.tiles += d.entries;
				report.aliases += d.aliases;
				report.chunkBytes += d.bytes.length;
				report.manifest.add("chunk " + d.name + " " + d.bytes.length + " " + d.entries + " " + d.aliases);
				for (String g : d.golden)
				{
					String[] p = g.split(" ");
					goldenByRel.put(p[0], p[1]);
				}
				for (Map.Entry<String, long[]> e : d.byGroup.entrySet())
				{
					long[] t = report.byGroup.computeIfAbsent(e.getKey(), k -> new long[3]);
					for (int i = 0; i < 3; i++)
					{
						t[i] += e.getValue()[i];
					}
				}
			}
			for (Map.Entry<String, Future<byte[]>> e : overviewFutures.entrySet())
			{
				byte[] bytes = e.getValue().get();
				Files.write(out.resolve(e.getKey()), bytes);
				written.add(out.resolve(e.getKey()).toAbsolutePath().normalize());
				report.overviews++;
				report.overviewBytes += bytes.length;
				int[] size = overviewSizes.get(e.getKey());
				report.manifest.add("overview " + e.getKey() + " " + bytes.length + " " + size[0] + "x" + size[1]);
			}
			for (Map.Entry<String, String> e : goldenByRel.entrySet())
			{
				report.golden.add(e.getKey() + " " + e.getValue());
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		Path index = out.resolve("index.json");
		Files.write(index, runtimeIndex(refs).getBytes(StandardCharsets.UTF_8));
		Path pack = out.resolve(SmallPack.NAME);
		if (!small.isEmpty())
		{
			byte[] packBytes = SmallPack.build(small);
			Files.write(pack, packBytes);
			verifyPack(packBytes, small);
			written.add(pack.toAbsolutePath().normalize());
			report.manifest.add("pack " + SmallPack.NAME + " " + packBytes.length + " " + small.size() + " files");
		}
		checkStale(out, written, prune);
		if (reports != null)
		{
			Files.createDirectories(reports);
			List<String> manifest = new ArrayList<>(report.manifest);
			manifest.add("total chunks " + report.chunks + " tiles " + report.tiles + " aliases " + report.aliases + " bytes " + report.chunkBytes);
			manifest.add("total overviews " + report.overviews + " bytes " + report.overviewBytes);
			Files.write(reports.resolve("manifest.txt"), manifest, StandardCharsets.UTF_8);
			Files.write(reports.resolve("golden.txt"), report.golden, StandardCharsets.UTF_8);
		}
		return report;
	}

	/**
	 * The index the plugin reads, {@code {"maps":{"<mapId>":[<x << 16 | y>, ...]}}}, with the maps in numeric order, the
	 * squares in ascending order, no spaces and a closing line feed. Built from the tiles that were packed, so it cannot list
	 * a tile that has no chunk entry.
	 */
	static String runtimeIndex(List<Corpus.Ref> refs)
	{
		Map<Integer, List<Integer>> byMap = new TreeMap<>();
		for (Corpus.Ref r : refs)
		{
			byMap.computeIfAbsent(r.mapId, k -> new ArrayList<>()).add((r.x << 16) | r.y);
		}
		StringBuilder sb = new StringBuilder("{\"maps\":{");
		boolean firstMap = true;
		for (Map.Entry<Integer, List<Integer>> e : byMap.entrySet())
		{
			if (!firstMap)
			{
				sb.append(',');
			}
			firstMap = false;
			sb.append('"').append(e.getKey()).append("\":[");
			List<Integer> keys = e.getValue();
			keys.sort(null);
			for (int i = 0; i < keys.size(); i++)
			{
				if (i > 0)
				{
					sb.append(',');
				}
				sb.append(keys.get(i));
			}
			sb.append(']');
		}
		return sb.append("}}\n").toString();
	}

	/** Reads every chunk file back out of the pack bytes the way the plugin does and compares it with what went in. */
	private static void verifyPack(byte[] packBytes, Map<Integer, byte[]> small) throws IOException
	{
		SmallPack.Index index = SmallPack.readIndex(new java.io.ByteArrayInputStream(packBytes));
		if (index.size() != small.size())
		{
			throw new IllegalStateException("the pack lists " + index.size() + " files, expected " + small.size());
		}
		for (Map.Entry<Integer, byte[]> e : small.entrySet())
		{
			int at = index.find(e.getKey());
			byte[] back = at < 0 ? null : SmallPack.read(new java.io.ByteArrayInputStream(packBytes), index.offset(at), index.length(at));
			if (back == null || !Arrays.equals(back, e.getValue()))
			{
				throw new IllegalStateException("the pack gives back other bytes for map " + e.getKey());
			}
		}
	}

	/** Chunk and overview files in the output folder that this run did not write would be shipped, so they stop the run. */
	private static void checkStale(Path out, Set<Path> written, boolean prune) throws IOException
	{
		List<Path> stale = new ArrayList<>();
		try (java.util.stream.Stream<Path> walk = Files.walk(out))
		{
			walk.filter(Files::isRegularFile).forEach(f ->
			{
				String name = f.getFileName().toString();
				if ((name.endsWith(ChunkNames.EXTENSION) || name.endsWith(ChunkNames.OVERVIEW_EXTENSION) || name.equals(SmallPack.NAME))
					&& !written.contains(f.toAbsolutePath().normalize()))
				{
					stale.add(f);
				}
			});
		}
		if (stale.isEmpty())
		{
			return;
		}
		if (!prune)
		{
			throw new IllegalStateException(stale.size() + " chunk or overview files in " + out + " are not produced from these sources: "
				+ stale + ". Run with --prune to delete them.");
		}
		for (Path f : stale)
		{
			Files.delete(f);
			System.out.println("pruned " + f);
		}
	}

	private static ChunkDone doChunk(ChunkJob job, int tableBits) throws IOException
	{
		TileEncoder encoder = new TileEncoder();
		if (tableBits > 0)
		{
			encoder.tableBits(tableBits);
		}
		TileDecoder decoder = new TileDecoder();
		ChunkBuilder builder = new ChunkBuilder(256, 256);
		BufferedImage[] refImages = new BufferedImage[job.refs.size()];
		for (int i = 0; i < refImages.length; i++)
		{
			Corpus.Ref r = job.refs.get(i);
			refImages[i] = Corpus.readLikeThePlugin(r.file, r.alpha());
			if (refImages[i].getWidth() != 256 || refImages[i].getHeight() != 256)
			{
				throw new IllegalStateException(r.rel() + " is not 256 by 256");
			}
			builder.add(r.mapId, r.x, r.y, TileEncoder.pixels(refImages[i]));
		}
		ChunkBuilder.Result built = builder.buildResult(encoder);
		ChunkFile chunk = ChunkFile.parse(built.bytes);
		if (chunk.size() != job.refs.size())
		{
			throw new IllegalStateException(job.name + " holds " + chunk.size() + " entries, expected " + job.refs.size());
		}
		ChunkDone done = new ChunkDone();
		done.name = job.name;
		done.bytes = built.bytes;
		done.entries = built.entries;
		done.aliases = built.aliases;
		int[] buffer = new int[256 * 256];
		for (int i = 0; i < refImages.length; i++)
		{
			Corpus.Ref r = job.refs.get(i);
			BufferedImage ref = refImages[i];
			int entry = chunk.indexOf(r.mapId, r.x, r.y);
			if (entry < 0)
			{
				throw new IllegalStateException(r.rel() + " is missing from " + job.name);
			}
			BufferedImage got = chunk.decodeImage(r.mapId, r.x, r.y, r.alpha(), decoder);
			if (!Corpus.sameImage(got, ref))
			{
				throw new IllegalStateException("PIXEL MISMATCH in " + r.rel());
			}
			chunk.decode(entry, decoder, buffer);
			if (!Arrays.equals(buffer, Corpus.argb(ref)))
			{
				throw new IllegalStateException("PIXEL MISMATCH (array decode) in " + r.rel());
			}
			done.golden.add(r.rel() + " " + sha256(TileEncoder.pixels(ref)));
			long[] t = done.byGroup.computeIfAbsent(group(r.mapId), k -> new long[3]);
			t[0]++;
			if (chunk.isAlias(entry))
			{
				t[1]++;
			}
		}
		for (int i = 0; i < built.mapIds.length; i++)
		{
			done.byGroup.computeIfAbsent(group(built.mapIds[i]), k -> new long[3])[2] += built.sizes[i];
		}
		return done;
	}

	private static byte[] doOverview(Path png, Map<String, int[]> sizes, String name, int tableBits) throws IOException
	{
		BufferedImage ref = Corpus.readLikeThePlugin(png, false);
		TileEncoder encoder = new TileEncoder().tableBits(tableBits > 0 ? tableBits : OVERVIEW_TABLE_BITS);
		byte[] bytes = ChunkBuilder.imageFile(TileEncoder.pixels(ref), ref.getWidth(), ref.getHeight(), encoder);
		ImageFile file = ImageFile.parse(bytes);
		BufferedImage got = file.decodeImage(false);
		if (!Corpus.sameImage(got, ref))
		{
			throw new IllegalStateException("PIXEL MISMATCH in " + png.getFileName());
		}
		sizes.put(name, new int[] {ref.getWidth(), ref.getHeight()});
		return bytes;
	}
}
