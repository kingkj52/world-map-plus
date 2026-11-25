package com.worldmapplus.verify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.render.MapRenderer;
import com.worldmapplus.tiles.SourceTiles;
import com.worldmapplus.tiles.TileCache;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders fixed 1280 x 720 frames of the map through the real MapRenderer and TileCache over the PNG sources, and records a
 * hash and a coarse thumbnail of each frame, how long a frame takes, how long a first view takes until every tile it
 * needs is decoded, and the heap afterwards. The hashes are compared with tools/verify/frames.json by
 * RenderRegressionTest. Test scope only: it reads and writes files, and the plugin never sees it.
 * <p>
 * Run by hand: {@code gradlew -q test --tests ...} does not print this, use the main method, for example
 * {@code java -cp <test runtime classpath> com.worldmapplus.verify.RenderBench --write} to refresh frames.json, or
 * {@code --write-cold} to record how long a first view takes with the chunk files into cold-view.json (the median of three
 * runs). frames.json keeps the first view times of the PNG tiles it was recorded with, and the regression test allows
 * whichever of the two is larger. See docs/VERIFY.md.
 */
public final class RenderBench
{
	static final int W = 1280;
	static final int H = 720;
	/** The thumbnail is a grid of 20 pixel blocks, 64 by 36. */
	static final int BLOCK = 20;
	static final int TW = W / BLOCK;
	static final int TH = H / BLOCK;

	private RenderBench()
	{
	}

	/** One fixed view. */
	static final class Scenario
	{
		final String name;
		final int mapId;
		final double x;
		final double y;
		final double scale;
		final int floor;

		Scenario(String name, int mapId, double x, double y, double scale, int floor)
		{
			this.name = name;
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.scale = scale;
			this.floor = floor;
		}
	}

	/** What one scenario produced. */
	static final class Frame
	{
		Scenario scenario;
		String hash;
		String thumb;
		double coldMs;
		int coldFrames;
		double p50;
		double p95;
		double max;
	}

	static final class Report
	{
		final List<Frame> frames = new ArrayList<>();
		double heapMb;

		/** What the JVM held, after a collection, before the run began: other tests may have left data behind. */
		double baselineMb;
	}

	/** Lumbridge, the point every scale is rendered around. */
	private static final double LX = 3222.5;
	private static final double LY = 3218.5;

	static List<Scenario> scenarios()
	{
		List<Scenario> list = new ArrayList<>();
		for (double scale : new double[] {0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8, 16, 32})
		{
			list.add(new Scenario("scale-" + scale, 0, LX, LY, scale, 0));
		}
		// Floors one to three where those floors have tiles: squares 43_62, 43_62 and 45_61 (x * 64 + 32).
		list.add(new Scenario("floor-1", 0, 43 * 64 + 32, 62 * 64 + 32, 4, 1));
		list.add(new Scenario("floor-2", 0, 43 * 64 + 32, 62 * 64 + 32, 4, 2));
		list.add(new Scenario("floor-3", 0, 45 * 64 + 32, 61 * 64 + 32, 4, 3));
		list.add(new Scenario("underground-7", 7, 44 * 64 + 32, 83 * 64 + 32, 2, 0));
		list.add(new Scenario("extra-10128", 10128, 40 * 64 + 32, 67 * 64 + 32, 2, 0));
		return list;
	}

	static String hashFrame(BufferedImage image)
	{
		int[] px = image.getRGB(0, 0, W, H, null, 0, W);
		byte[] bytes = new byte[px.length * 3];
		for (int i = 0; i < px.length; i++)
		{
			bytes[i * 3] = (byte) (px[i] >> 16);
			bytes[i * 3 + 1] = (byte) (px[i] >> 8);
			bytes[i * 3 + 2] = (byte) px[i];
		}
		return Reference.b64(Reference.sha256(bytes));
	}

	/** Mean colour of each 20 pixel block, rounded, three bytes per block. Tolerates a JDK that rounds a pixel differently. */
	static String thumbFrame(BufferedImage image)
	{
		int[] px = image.getRGB(0, 0, W, H, null, 0, W);
		byte[] out = new byte[TW * TH * 3];
		for (int by = 0; by < TH; by++)
		{
			for (int bx = 0; bx < TW; bx++)
			{
				long r = 0;
				long g = 0;
				long b = 0;
				for (int y = 0; y < BLOCK; y++)
				{
					for (int x = 0; x < BLOCK; x++)
					{
						int p = px[(by * BLOCK + y) * W + bx * BLOCK + x];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
					}
				}
				int n = BLOCK * BLOCK;
				int o = (by * TW + bx) * 3;
				out[o] = (byte) ((r + n / 2) / n);
				out[o + 1] = (byte) ((g + n / 2) / n);
				out[o + 2] = (byte) ((b + n / 2) / n);
			}
		}
		return Base64.getEncoder().encodeToString(out);
	}

	/** Largest per-channel difference between two thumbnails. */
	static int thumbDistance(String a, String b)
	{
		byte[] x = Base64.getDecoder().decode(a);
		byte[] y = Base64.getDecoder().decode(b);
		int worst = 0;
		for (int i = 0; i < x.length; i++)
		{
			worst = Math.max(worst, Math.abs((x[i] & 0xFF) - (y[i] & 0xFF)));
		}
		return worst;
	}

	static Report run() throws InterruptedException
	{
		Gson gson = new Gson();
		MapCatalog catalog = MapCatalog.load(gson);
		SourceTiles source = new SourceTiles(gson);
		// RuneLite's shared executor is in effect one thread for this plugin, so the loads run on one thread here.
		ExecutorService executor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "render-bench-loader");
			t.setDaemon(true);
			return t;
		});
		try
		{
			TileCache cache = new TileCache(source, executor, 256L << 20);
			MapRenderer renderer = new MapRenderer(cache);
			BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = canvas.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Report report = new Report();
			System.gc();
			Runtime start = Runtime.getRuntime();
			report.baselineMb = (start.totalMemory() - start.freeMemory()) / 1e6;
			for (Scenario s : scenarios())
			{
				MapInfo map = catalog.byId(s.mapId);
				if (map == null)
				{
					throw new IllegalStateException("map " + s.mapId + " is not in the catalogue");
				}
				report.frames.add(measure(s, map, cache, renderer, canvas, g));
			}
			g.dispose();
			System.gc();
			Runtime rt = Runtime.getRuntime();
			report.heapMb = (rt.totalMemory() - rt.freeMemory()) / 1e6;
			return report;
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	private static void paint(MapRenderer renderer, Graphics2D g, ViewTransform view, MapInfo map, int floor)
	{
		g.setColor(MapRenderer.BACKGROUND);
		g.fillRect(0, 0, W, H);
		renderer.paint(g, view, map, floor);
	}

	private static Frame measure(Scenario s, MapInfo map, TileCache cache, MapRenderer renderer, BufferedImage canvas,
		Graphics2D g) throws InterruptedException
	{
		Frame f = new Frame();
		f.scenario = s;
		cache.clear();
		ViewTransform view = new ViewTransform(s.x, s.y, s.scale, 0, 0, W, H);

		// Cold: a fresh cache, as when the map is opened. Time until a frame needs nothing that is still loading.
		long t0 = System.nanoTime();
		int frames = 0;
		for (; frames < 5000; frames++)
		{
			paint(renderer, g, view, map, s.floor);
			if (frames > 2 && cache.pendingCount() == 0)
			{
				break;
			}
			Thread.sleep(1);
		}
		paint(renderer, g, view, map, s.floor);
		f.coldMs = (System.nanoTime() - t0) / 1e6;
		f.coldFrames = frames;
		paint(renderer, g, view, map, s.floor);
		f.hash = hashFrame(canvas);
		f.thumb = thumbFrame(canvas);

		// Steady: the same view again, after a warm-up.
		for (int i = 0; i < 60; i++)
		{
			paint(renderer, g, view, map, s.floor);
		}
		double[] ms = new double[150];
		for (int i = 0; i < ms.length; i++)
		{
			long a = System.nanoTime();
			paint(renderer, g, view, map, s.floor);
			ms[i] = (System.nanoTime() - a) / 1e6;
		}
		Arrays.sort(ms);
		f.p50 = ms[ms.length / 2];
		f.p95 = ms[(int) (ms.length * 0.95)];
		f.max = ms[ms.length - 1];
		return f;
	}

	static JsonObject toJson(Report report, boolean withFrames, boolean withTimings)
	{
		JsonObject doc = new JsonObject();
		if (withFrames)
		{
			JsonObject frames = new JsonObject();
			for (Frame f : report.frames)
			{
				JsonObject o = new JsonObject();
				o.addProperty("map", f.scenario.mapId);
				o.addProperty("x", f.scenario.x);
				o.addProperty("y", f.scenario.y);
				o.addProperty("scale", f.scenario.scale);
				o.addProperty("floor", f.scenario.floor);
				o.addProperty("hash", f.hash);
				o.addProperty("thumb", f.thumb);
				frames.add(f.scenario.name, o);
			}
			doc.add("frames", frames);
		}
		if (withTimings)
		{
			JsonObject timings = new JsonObject();
			for (Frame f : report.frames)
			{
				JsonObject o = new JsonObject();
				o.addProperty("cold_ms", Math.round(f.coldMs));
				o.addProperty("cold_frames", f.coldFrames);
				o.addProperty("p50_ms", Math.round(f.p50 * 100) / 100.0);
				o.addProperty("p95_ms", Math.round(f.p95 * 100) / 100.0);
				o.addProperty("max_ms", Math.round(f.max * 100) / 100.0);
				timings.add(f.scenario.name, o);
			}
			doc.add("timings", timings);
			doc.addProperty("heap_mb", Math.round(report.heapMb * 10) / 10.0);
		}
		return doc;
	}

	/** Writes frames.json in the layout RenderRegressionTest reads: one frame per line. */
	static void writeFrames(Report report, Path path) throws IOException
	{
		Gson compact = new Gson();
		JsonObject doc = toJson(report, true, true);
		JsonObject header = new JsonObject();
		header.addProperty("note", "Expected frames of RenderBench (1280x720, fixed views). hash is exact on the JDK that "
			+ "recorded it. thumb is the mean colour of each 20 pixel block, compared with a tolerance of 1 per channel when the hash "
			+ "differs, which absorbs the one-step rounding other JDKs show in bilinear scaling. timings are from the recorded machine; "
			+ "the CI check allows three times as much.");
		header.addProperty("recordedOnJdk", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
		header.addProperty("recordedOn", java.time.LocalDate.now().toString());
		header.addProperty("cores", Runtime.getRuntime().availableProcessors());
		try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
		{
			w.write("{\n");
			w.write("\"note\":" + compact.toJson(header.get("note")) + ",\n");
			w.write("\"recordedOnJdk\":" + compact.toJson(header.get("recordedOnJdk")) + ",\n");
			w.write("\"recordedOn\":" + compact.toJson(header.get("recordedOn")) + ",\n");
			w.write("\"cores\":" + header.get("cores") + ",\n");
			w.write("\"size\":[" + W + "," + H + "],\n");
			w.write("\"frames\":{\n");
			JsonObject frames = doc.getAsJsonObject("frames");
			List<String> lines = new ArrayList<>();
			for (String name : frames.keySet())
			{
				lines.add(compact.toJson(name) + ":" + compact.toJson(frames.get(name)));
			}
			w.write(String.join(",\n", lines));
			w.write("\n},\n\"timings\":" + compact.toJson(doc.getAsJsonObject("timings")) + ",\n");
			w.write("\"heap_mb\":" + doc.get("heap_mb") + "\n}\n");
		}
	}

	/** Writes cold-view.json: the first view time of each scenario, the median of {@code runs} complete runs. */
	static void writeCold(int runs, Path path) throws Exception
	{
		List<Report> reports = new ArrayList<>();
		for (int i = 0; i < runs; i++)
		{
			reports.add(run());
		}
		JsonObject cold = new JsonObject();
		for (int i = 0; i < reports.get(0).frames.size(); i++)
		{
			double[] ms = new double[runs];
			for (int r = 0; r < runs; r++)
			{
				ms[r] = reports.get(r).frames.get(i).coldMs;
			}
			Arrays.sort(ms);
			cold.addProperty(reports.get(0).frames.get(i).scenario.name, Math.round(ms[runs / 2]));
		}
		JsonObject doc = new JsonObject();
		doc.addProperty("note", "Time until every tile of a first view is decoded, with the compact chunk files, in milliseconds (the median of "
			+ runs + " runs of RenderBench on the recording machine, one loader thread like RuneLite's shared executor). RenderRegressionTest "
			+ "allows the larger of this and the cold_ms recorded in frames.json (PNG tiles), times three, with a floor of 1.5 s.");
		doc.addProperty("recordedOnJdk", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
		doc.addProperty("recordedOn", java.time.LocalDate.now().toString());
		doc.addProperty("cores", Runtime.getRuntime().availableProcessors());
		doc.add("cold_ms", cold);
		try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
		{
			w.write(new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(doc));
			w.write("\n");
		}
	}

	/** {@code --write} refreshes tools/verify/frames.json, {@code --write-cold} cold-view.json, {@code --print} (default) prints the table. */
	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		if (Arrays.asList(args).contains("--write-cold"))
		{
			writeCold(3, Reference.verifyFile("cold-view.json"));
			System.out.println("wrote " + Reference.verifyFile("cold-view.json"));
			return;
		}
		Report report = run();
		System.out.println("scenario\tcold_ms\tcold_frames\tp50_ms\tp95_ms\tmax_ms\thash");
		for (Frame f : report.frames)
		{
			System.out.printf("%s\t%.0f\t%d\t%.2f\t%.2f\t%.2f\t%s%n", f.scenario.name, f.coldMs, f.coldFrames, f.p50, f.p95, f.max,
				f.hash.substring(0, 12));
		}
		System.out.printf("heap used after: %.1f MB%n", report.heapMb);
		if (Arrays.asList(args).contains("--write"))
		{
			writeFrames(report, Reference.verifyFile("frames.json"));
			System.out.println("wrote " + Reference.verifyFile("frames.json"));
		}
	}
}
