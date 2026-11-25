package com.worldmapplus.tiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.runelite.client.util.Filepath;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

/**
 * The download, cache and read path of {@link PackedTiles}, over a fake HTTP layer (an interceptor that answers from a map and
 * records every request: no network). The release here is made of two small packs of flat coloured tiles.
 */
public class PackedTilesTest
{
	private static final Gson GSON = new Gson();
	private static final String BASE = "https://maps.test/release/";

	private static final TileIndex INDEX = TileIndex.of(index());

	private static Map<String, int[]> index()
	{
		Map<String, int[]> maps = new HashMap<>();
		maps.put("0", new int[] {TileIndex.key(50, 50), TileIndex.key(51, 50), TileIndex.key(50, 51)});
		maps.put("20000", new int[] {TileIndex.key(50, 50)});
		return maps;
	}

	// ---- a small release ----

	private static byte[] png(int rgb, boolean alpha) throws IOException
	{
		BufferedImage image = new BufferedImage(256, 256, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 256; x++)
		{
			for (int y = 0; y < 256; y++)
			{
				image.setRGB(x, y, rgb);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static byte[] zip(Map<String, byte[]> entries) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out))
		{
			for (Map.Entry<String, byte[]> e : new java.util.TreeMap<>(entries).entrySet())
			{
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static byte[] ground(int colour) throws IOException
	{
		Map<String, byte[]> tiles = new HashMap<>();
		tiles.put("0/50_50.png", png(colour, false));
		tiles.put("0/51_50.png", png(colour, false));
		tiles.put("0/50_51.png", png(colour, false));
		return zip(tiles);
	}

	private static byte[] floor() throws IOException
	{
		return zip(Collections.singletonMap("20000/50_50.png", png(0x80FF0000, true)));
	}

	private static String entry(String name, byte[] pack, String areas)
	{
		return "{\"name\":\"" + name + "\",\"size\":" + pack.length + ",\"sha256\":\"" + PackedTiles.hash(pack) + "\",\"areas\":" + areas + "}";
	}

	private static byte[] manifest(int version, String... packs)
	{
		return ("{\"format\":1,\"version\":" + version + ",\"packs\":[" + String.join(",", packs) + "]}").getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] manifest(int version, byte[] ground, byte[] floor)
	{
		return manifest(version, entry("ground-01.zip", ground, "[[0,50,50,51,51]]"), entry("floor1-01.zip", floor, "[[20000,50,50,50,50]]"));
	}

	// ---- the fake server and the rig around the source ----

	private static final class Rig
	{
		final Map<String, byte[]> files = new ConcurrentHashMap<>();
		/** Answers to give for a file before it is served normally: an HTTP code, or -1 for a network failure. */
		final Map<String, Deque<Integer>> scripted = new ConcurrentHashMap<>();
		final Map<String, String> retryAfter = new ConcurrentHashMap<>();
		final List<String> requests = Collections.synchronizedList(new ArrayList<>());
		final AtomicLong clock = new AtomicLong(1_000_000);
		final java.nio.file.Path folder;
		volatile boolean allowed = true;
		volatile TileIndex index = INDEX;
		/** When set, a request for a file whose name starts with p waits for it before it is answered. */
		volatile java.util.concurrent.CountDownLatch gate;

		Rig() throws IOException
		{
			folder = Files.createTempDirectory("wmp-packed-test");
		}

		OkHttpClient client()
		{
			return new OkHttpClient.Builder().addInterceptor(chain ->
			{
				Request request = chain.request();
				String name = request.url().pathSegments().get(request.url().pathSegments().size() - 1);
				requests.add(name);
				java.util.concurrent.CountDownLatch waiting = gate;
				if (waiting != null && name.startsWith("p"))
				{
					try
					{
						waiting.await();
					}
					catch (InterruptedException e)
					{
						throw new IOException(e);
					}
				}
				Deque<Integer> script = scripted.get(name);
				Integer code = script == null ? null : script.pollFirst();
				if (code != null && code < 0)
				{
					throw new IOException("offline");
				}
				byte[] bytes = code == null ? files.get(name) : new byte[0];
				int status = code != null ? code : bytes == null ? 404 : 200;
				Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
					.body(ResponseBody.create(MediaType.get("application/octet-stream"), status == 200 ? bytes : new byte[0]));
				if (retryAfter.containsKey(name))
				{
					response.header("Retry-After", retryAfter.get(name));
				}
				return response.build();
			}).build();
		}

		PackedTiles open() throws IOException
		{
			return open(() -> allowed, client());
		}

		PackedTiles open(BooleanSupplier download, OkHttpClient client) throws IOException
		{
			return new PackedTiles(index, GSON, Runnable::run, new MapDownloader(client, BASE, clock::get),
				new PackCache(Filepath.Unchecked.getRooted(folder)), download, clock::get);
		}

		int count(String name)
		{
			synchronized (requests)
			{
				return Collections.frequency(requests, name);
			}
		}
	}

	private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException
	{
		for (int i = 0; i < 3000 && !condition.getAsBoolean(); i++)
		{
			Thread.sleep(2);
		}
		return condition.getAsBoolean();
	}

	/** Asks for a square until it can be read, as the tile cache does on every frame it still waits. */
	private static boolean fetched(PackedTiles tiles, int map, int x, int y) throws InterruptedException
	{
		return waitFor(() ->
		{
			tiles.request(map, x, y, 1);
			return tiles.ready(map, x, y);
		});
	}

	/** Asks for a square, as the view does on every frame it waits, until the condition holds. */
	private static boolean ask(PackedTiles tiles, int map, int x, int y, java.util.function.BooleanSupplier condition) throws InterruptedException
	{
		return waitFor(() ->
		{
			tiles.request(map, x, y, 1);
			return condition.getAsBoolean();
		});
	}

	private static void manifestKept(Rig rig) throws InterruptedException
	{
		assertTrue(waitFor(() -> Files.exists(rig.folder.resolve("maps").resolve("manifest.json"))));
	}

	private static Rig release(int version, int colour) throws IOException
	{
		Rig rig = new Rig();
		byte[] ground = ground(colour);
		byte[] floor = floor();
		rig.files.put("manifest.json", manifest(version, ground, floor));
		rig.files.put("ground-01.zip", ground);
		rig.files.put("floor1-01.zip", floor);
		return rig;
	}

	// ---- the manifest ----

	@Test
	public void aGoodManifestLocatesEverySquareOfTheIndex() throws IOException
	{
		byte[] ground = ground(0x336699);
		MapManifest m = MapManifest.parse(GSON, manifest(7, ground, floor()), INDEX);
		assertEquals(7, m.version);
		assertEquals("ground-01.zip", m.find(0, 51, 50).name);
		assertEquals("floor1-01.zip", m.find(20000, 50, 50).name);
		assertNull("a square the index does not have", m.find(0, 51, 51));
		assertNull(m.find(0, -1, 5));
		assertTrue(m.find(0, 50, 50).file.endsWith(".zip") && m.find(0, 50, 50).file.contains(m.find(0, 50, 50).sha256.substring(0, 12)));
	}

	private static void refused(byte[] bytes)
	{
		try
		{
			MapManifest.parse(GSON, bytes, INDEX);
			fail("should have been refused: " + new String(bytes, StandardCharsets.UTF_8));
		}
		catch (RuntimeException expected)
		{
			// Refused, as it should be.
		}
	}

	@Test
	public void aBadManifestIsRefused() throws IOException
	{
		byte[] pack = ground(1);
		String hash = PackedTiles.hash(pack);
		String good = entry("ground-01.zip", pack, "[[0,50,50,51,51]]");
		refused("not json".getBytes(StandardCharsets.UTF_8));
		refused("[]".getBytes(StandardCharsets.UTF_8));
		refused("{\"format\":2,\"packs\":[]}".getBytes(StandardCharsets.UTF_8));
		refused("{\"format\":1}".getBytes(StandardCharsets.UTF_8));
		refused(manifest(1, good.replace("ground-01.zip", "../ground-01.zip")));
		refused(manifest(1, good.replace("ground-01.zip", "Ground.zip")));
		refused(manifest(1, good.replace("ground-01.zip", "ground-01.exe")));
		refused(manifest(1, good.replace(hash, hash.substring(1))));
		refused(manifest(1, good.replace(hash, hash.toUpperCase())));
		refused(manifest(1, good.replace("\"size\":" + pack.length, "\"size\":0")));
		refused(manifest(1, good.replace("\"size\":" + pack.length, "\"size\":999999999")));
		refused(manifest(1, good.replace("[[0,50,50,51,51]]", "[[0,50,50,49,51]]")));
		refused(manifest(1, good.replace("[[0,50,50,51,51]]", "[[0,50,50,51]]")));
		refused(manifest(1, good.replace("[[0,50,50,51,51]]", "[[0,-1,50,51,51]]")));
		refused(manifest(1, good.replace("[[0,50,50,51,51]]", "[[0,0,0,1048575,1048575]]")));
		refused(manifest(1, good, good));
		refused(manifest(1, good, good.replace("ground-01.zip", "ground-02.zip")));
		refused(manifest(1, good, entry("ground-02.zip", ground(2), "[[0,51,50,51,50]]")));
		refused(new byte[MapManifest.MAX_BYTES + 1]);
		// Fields the plugin keeps for itself are not taken from the file.
		MapManifest m = MapManifest.parse(GSON, manifest(1, good.replace("\"areas\"", "\"onDisk\":true,\"dead\":true,\"file\":\"x\",\"areas\"")), INDEX);
		assertFalse(m.find(0, 50, 50).onDisk);
		assertFalse(m.find(0, 50, 50).dead);
		assertTrue(m.find(0, 50, 50).file.startsWith("ground-01-"));
	}

	// ---- downloading ----

	@Test
	public void aTileIsDownloadedWhenTheViewWaitsForItAndThenReadFromMemoryOrDisk() throws Exception
	{
		Rig rig = release(1, 0x336699);
		PackedTiles tiles = rig.open();
		assertTrue("squares of the index exist before the manifest is known", tiles.exists(0, 50, 50));
		assertFalse(tiles.ready(0, 50, 50));
		assertNull("nothing to read yet", tiles.load(0, 50, 50));

		assertTrue(fetched(tiles, 0, 50, 50));
		BufferedImage image = tiles.load(0, 50, 50);
		assertNotNull(image);
		assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
		assertEquals(0x336699, image.getRGB(10, 10) & 0xFFFFFF);
		assertTrue("the pack holds the neighbours too", tiles.ready(0, 51, 50));
		assertEquals(1, rig.count("manifest.json"));
		assertEquals(1, rig.count("ground-01.zip"));
		assertEquals("only what was asked for", 0, rig.count("floor1-01.zip"));
		assertNull("nothing to say when nothing is waited for", waitStatusNull(tiles));

		assertTrue(fetched(tiles, 20000, 50, 50));
		BufferedImage floor = tiles.load(20000, 50, 50);
		assertEquals(BufferedImage.TYPE_INT_ARGB, floor.getType());
		assertEquals(0x80FF0000, floor.getRGB(3, 3));
		assertFalse("a square no pack holds does not exist once the manifest is known", tiles.exists(0, 51, 51));
		manifestKept(rig);
		assertTrue(rig.folder.resolve("maps").toFile().list().length >= 3);

		// After the memory is let go, the pack comes back from disk, not from the server.
		tiles.release();
		assertNotNull(tiles.load(0, 50, 50));
		assertEquals(1, rig.count("ground-01.zip"));
	}

	private static String waitStatusNull(PackedTiles tiles) throws InterruptedException
	{
		return waitFor(() -> tiles.status() == null) ? null : tiles.status();
	}

	@Test
	public void theNearestPacksAreFetchedFirstWhenThereIsNoRoomForAll() throws Exception
	{
		// Five packs of one square each. Three downloads run at once (held at the gate); the two that wait start in order of their
		// distance from the middle of the view when room is made.
		Map<String, int[]> maps = new HashMap<>();
		int[] keys = new int[5];
		for (int i = 0; i < 5; i++)
		{
			keys[i] = TileIndex.key(50 + i, 60);
		}
		maps.put("0", keys);
		Rig rig = new Rig();
		rig.index = TileIndex.of(maps);
		String[] entries = new String[5];
		for (int i = 0; i < 5; i++)
		{
			byte[] pack = zip(Collections.singletonMap("0/" + (50 + i) + "_60.png", png(0x101010 * (i + 1), false)));
			rig.files.put("p" + i + ".zip", pack);
			entries[i] = entry("p" + i + ".zip", pack, "[[0," + (50 + i) + ",60," + (50 + i) + ",60]]");
		}
		rig.files.put("manifest.json", manifest(1, entries));
		rig.gate = new java.util.concurrent.CountDownLatch(1);
		PackedTiles tiles = rig.open();
		assertTrue(waitFor(() ->
		{
			tiles.request(0, 50, 60, 100);
			return rig.count("p0.zip") == 1;
		}));
		double[] distance = {100, 90, 80, 5, 50};
		for (int i = 1; i < 5; i++)
		{
			final int n = i;
			tiles.request(0, 50 + n, 60, distance[n]);
		}
		assertTrue(waitFor(() -> rig.requests.size() == 4));
		assertEquals("room for three", 3, rig.requests.stream().filter(n -> n.startsWith("p")).count());
		rig.gate.countDown();
		assertTrue(waitFor(() -> rig.requests.size() == 6));
		List<String> order = new ArrayList<>(rig.requests);
		assertEquals("the nearest waiting pack first", "p3.zip", order.get(4));
		assertEquals("then the next", "p4.zip", order.get(5));
		assertTrue(waitFor(() -> tiles.ready(0, 53, 60) && tiles.ready(0, 54, 60)));
	}

	@Test
	public void cancellingStopsDownloadsWithoutHoldingTheFailureAgainstTheHost() throws Exception
	{
		Rig rig = release(1, 0x336699);
		rig.gate = new java.util.concurrent.CountDownLatch(1);
		PackedTiles tiles = rig.open();
		assertTrue(ask(tiles, 0, 50, 50, () -> rig.count("ground-01.zip") == 1 || rig.requests.contains("manifest.json")));
		tiles.cancel();
		rig.gate.countDown();
		Thread.sleep(200);
		assertFalse("a cancelled download delivers nothing", tiles.ready(0, 50, 50));
		// The clock has not moved: a failure would have left the host blocked for 30 seconds.
		assertTrue("asked again after the cancel", fetched(tiles, 0, 50, 50));
	}

	@Test
	public void aPackThatDoesNotMatchItsHashIsDroppedAndRetriedLaterNotInALoop() throws Exception
	{
		Rig rig = release(1, 0x336699);
		rig.files.put("ground-01.zip", ground(0x999999));
		PackedTiles tiles = rig.open();
		for (int i = 0; i < 50; i++)
		{
			tiles.request(0, 50, 50, 1);
			Thread.sleep(2);
		}
		assertTrue(waitFor(() -> rig.count("ground-01.zip") == 1));
		assertFalse(tiles.ready(0, 50, 50));
		assertNull(tiles.load(0, 50, 50));
		assertEquals("the bad pack is kept nowhere", 0, rig.folder.resolve("maps").toFile().list(
			(dir, name) -> name.startsWith("ground")).length);
		assertEquals("not asked again at once", 1, rig.count("ground-01.zip"));
		assertEquals(PackedTiles.FAILED, tiles.status());

		// After the wait it is tried again, and when the server has the right file it is taken.
		rig.files.put("ground-01.zip", ground(0x336699));
		rig.clock.addAndGet(PackedTiles.FIRST_RETRY_MILLIS + 1);
		assertTrue(fetched(tiles, 0, 50, 50));
		assertEquals(0x336699, tiles.load(0, 50, 50).getRGB(0, 0) & 0xFFFFFF);
		assertEquals(2, rig.count("ground-01.zip"));
	}

	@Test
	public void aPackIsGivenUpForTheSessionAfterManyFailures() throws Exception
	{
		Rig rig = release(1, 0x336699);
		rig.files.put("ground-01.zip", new byte[10]);
		PackedTiles tiles = rig.open();
		long wait = PackedTiles.MAX_RETRY_MILLIS + 1;
		for (int i = 1; i <= PackedTiles.MAX_FAILURES; i++)
		{
			final int n = i;
			rig.clock.addAndGet(wait);
			assertTrue(ask(tiles, 0, 50, 50, () -> rig.count("ground-01.zip") >= n));
			Thread.sleep(20);
		}
		for (int i = 0; i < 4; i++)
		{
			rig.clock.addAndGet(wait);
			tiles.request(0, 50, 50, 1);
			Thread.sleep(20);
		}
		assertEquals(PackedTiles.MAX_FAILURES, rig.count("ground-01.zip"));
		assertTrue("the tile still exists, the overview shows in its place", tiles.exists(0, 50, 50));
		assertFalse(tiles.ready(0, 50, 50));
	}

	@Test
	public void aRateLimitLeavesTheWholeHostAloneUntilItIsOver() throws Exception
	{
		Rig rig = release(1, 0x336699);
		rig.scripted.put("ground-01.zip", new ArrayDeque<>(Collections.singletonList(429)));
		rig.retryAfter.put("ground-01.zip", "120");
		PackedTiles tiles = rig.open();
		assertTrue(ask(tiles, 0, 50, 50, () -> rig.count("ground-01.zip") == 1));
		assertEquals(PackedTiles.FAILED, waitForStatus(tiles));
		rig.requests.clear();
		// The other pack is wanted, but the host is being left alone, so nothing at all is sent.
		for (int i = 0; i < 20; i++)
		{
			tiles.request(20000, 50, 50, 1);
			tiles.request(0, 50, 50, 1);
			Thread.sleep(2);
		}
		assertEquals("no request while backing off: " + rig.requests, 0, rig.requests.size());
		rig.clock.addAndGet(119_000);
		tiles.request(20000, 50, 50, 1);
		assertEquals("Retry-After is honoured", 0, rig.requests.size());
		rig.clock.addAndGet(2_000);
		assertTrue(fetched(tiles, 20000, 50, 50));
		assertTrue(fetched(tiles, 0, 50, 50));
	}

	private static String waitForStatus(PackedTiles tiles) throws InterruptedException
	{
		waitFor(() -> PackedTiles.FAILED.equals(tiles.status()));
		return tiles.status();
	}

	@Test
	public void aServerErrorAndANetworkFailureBackOffAndGrow() throws Exception
	{
		Rig rig = release(1, 0x336699);
		rig.scripted.put("manifest.json", new ArrayDeque<>(java.util.Arrays.asList(503, -1)));
		PackedTiles tiles = rig.open();
		tiles.request(0, 50, 50, 1);
		assertTrue(waitFor(() -> rig.count("manifest.json") == 1));
		assertEquals("the first failure is waited out, the manifest unknown", PackedTiles.FAILED, waitForStatus(tiles));
		tiles.request(0, 50, 50, 1);
		assertEquals(1, rig.count("manifest.json"));
		rig.clock.addAndGet(MapDownloader.FIRST_BACKOFF_MILLIS + 1);
		tiles.request(0, 50, 50, 1);
		assertTrue(waitFor(() -> rig.count("manifest.json") == 2));
		// The second failure doubles the wait.
		rig.clock.addAndGet(MapDownloader.FIRST_BACKOFF_MILLIS + 1);
		tiles.request(0, 50, 50, 1);
		Thread.sleep(50);
		assertEquals(2, rig.count("manifest.json"));
		rig.clock.addAndGet(MapDownloader.FIRST_BACKOFF_MILLIS * 3);
		assertTrue(fetched(tiles, 0, 50, 50));
	}

	@Test
	public void aFileLargerThanTheManifestSaysIsRefused() throws Exception
	{
		Rig rig = release(1, 0x336699);
		byte[] big = new byte[ground(0x336699).length + 500];
		rig.files.put("ground-01.zip", big);
		PackedTiles tiles = rig.open();
		assertTrue(ask(tiles, 0, 50, 50, () -> rig.count("ground-01.zip") == 1));
		Thread.sleep(50);
		assertFalse(tiles.ready(0, 50, 50));
		assertEquals(0, rig.folder.resolve("maps").toFile().list((dir, name) -> name.startsWith("ground")).length);
	}

	@Test
	public void anAnswerFromAnotherHostIsRefused() throws Exception
	{
		Rig rig = release(1, 0x336699);
		OkHttpClient elsewhere = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			Request moved = chain.request().newBuilder().url("https://evil.test/ground-01.zip").build();
			return new Response.Builder().request(moved).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(ResponseBody.create(MediaType.get("application/octet-stream"), rig.files.get("manifest.json"))).build();
		}).build();
		PackedTiles tiles = rig.open(() -> true, elsewhere);
		tiles.request(0, 50, 50, 1);
		Thread.sleep(100);
		tiles.request(0, 50, 50, 1);
		assertFalse(tiles.ready(0, 50, 50));
		assertEquals(PackedTiles.FAILED, tiles.status());
	}

	// ---- offline, the cache, updates and the setting ----

	@Test
	public void withoutAnyDownloadOnlyTheOverviewsShowAndNothingIsAsked() throws Exception
	{
		PackedTiles tiles = PackedTiles.overviewsOnly(INDEX, GSON, Runnable::run);
		assertFalse(tiles.ready(0, 50, 50));
		tiles.request(0, 50, 50, 1);
		assertNull(tiles.status());
		assertNull(tiles.load(0, 50, 50));
		for (int id : new int[] {0, 20000, 40000, 60000})
		{
			BufferedImage overview = tiles.overview(id);
			assertNotNull("the jar carries the overview of map " + id, overview);
			assertEquals(BufferedImage.TYPE_INT_RGB, overview.getType());
			assertTrue(overview.getWidth() > 500);
		}
		assertNull(tiles.overview(5));
	}

	@Test
	public void offlineOnTheFirstRunTheOverviewStillShowsAndThePlayerIsToldOnceTheViewWaits() throws Exception
	{
		Rig rig = release(1, 0x336699);
		rig.scripted.put("manifest.json", new ArrayDeque<>(Collections.singletonList(-1)));
		PackedTiles tiles = rig.open();
		assertNotNull(tiles.overview(0));
		tiles.request(0, 50, 50, 1);
		assertEquals(PackedTiles.FAILED, waitForStatus(tiles));
		assertTrue("the square exists, so the view draws the overview in its place", tiles.exists(0, 50, 50));
		assertNotNull(tiles.overview(0));
	}

	@Test
	public void aCachedPackIsUsedWithoutAnyRequest() throws Exception
	{
		Rig rig = release(1, 0x336699);
		assertTrue(fetched(rig.open(), 0, 50, 50));
		manifestKept(rig);
		rig.requests.clear();

		PackedTiles second = rig.open();
		assertTrue("the cached manifest is read at once", second.ready(0, 50, 50));
		BufferedImage image = second.load(0, 50, 50);
		assertEquals(0x336699, image.getRGB(0, 0) & 0xFFFFFF);
		Thread.sleep(100);
		assertEquals("a cache hit asks for no pack", 0, rig.count("ground-01.zip") + rig.count("floor1-01.zip"));
		assertTrue("the manifest is asked for once, to learn of updates", rig.count("manifest.json") <= 1);

		// Even with the server gone, a request for a cached tile does not go out for the pack.
		rig.files.clear();
		rig.requests.clear();
		second.request(0, 51, 50, 1);
		assertTrue(second.ready(0, 51, 50));
		assertEquals(0, rig.count("ground-01.zip"));
	}

	@Test
	public void aDamagedCachedPackIsDroppedAndFetchedAgain() throws Exception
	{
		Rig rig = release(1, 0x336699);
		assertTrue(fetched(rig.open(), 0, 50, 50));
		manifestKept(rig);
		java.io.File[] files = rig.folder.resolve("maps").toFile().listFiles((dir, name) -> name.startsWith("ground"));
		assertEquals(1, files.length);
		Files.write(files[0].toPath(), new byte[] {1, 2, 3});

		PackedTiles second = rig.open();
		assertTrue(second.ready(0, 50, 50));
		assertNull("a file that does not match is not used", second.load(0, 50, 50));
		assertFalse(second.ready(0, 50, 50));
		// The pack comes again; the manifest may be asked for again at the same time, so wait until the square reads.
		assertTrue(ask(second, 0, 50, 50, () -> second.ready(0, 50, 50) && second.load(0, 50, 50) != null));
	}

	@Test
	public void aNewManifestFetchesOnlyThePacksThatChanged() throws Exception
	{
		Rig rig = release(1, 0x336699);
		PackedTiles first = rig.open();
		assertTrue(fetched(first, 0, 50, 50));
		assertTrue(fetched(first, 20000, 50, 50));
		manifestKept(rig);
		rig.requests.clear();

		byte[] changed = ground(0xAA5500);
		byte[] floor = rig.files.get("floor1-01.zip");
		rig.files.put("manifest.json", manifest(2, changed, floor));
		rig.files.put("ground-01.zip", changed);

		PackedTiles second = rig.open();
		assertTrue("the old manifest serves until the new one is here", second.ready(0, 50, 50));
		assertNotNull(second.load(0, 50, 50));
		assertTrue(waitFor(() -> rig.count("manifest.json") == 1 && !second.ready(0, 50, 50)));
		assertTrue("the unchanged pack is still there", second.ready(20000, 50, 50));
		assertTrue(fetched(second, 0, 50, 50));
		assertEquals(0xAA5500, second.load(0, 50, 50).getRGB(0, 0) & 0xFFFFFF);
		assertEquals("the new manifest, once", 1, rig.count("manifest.json"));
		assertEquals("the changed pack", 1, rig.count("ground-01.zip"));
		assertEquals("the unchanged pack stays where it is", 0, rig.count("floor1-01.zip"));
		assertEquals("the old pack file is gone from the cache", 1, rig.folder.resolve("maps").toFile()
			.list((dir, name) -> name.startsWith("ground")).length);
	}

	@Test
	public void withTheSettingOffNothingIsSentAndWhatIsCachedStillShows() throws Exception
	{
		Rig rig = release(1, 0x336699);
		assertTrue(fetched(rig.open(), 0, 50, 50));
		manifestKept(rig);
		rig.requests.clear();

		rig.allowed = false;
		PackedTiles off = rig.open();
		assertTrue(off.ready(0, 50, 50));
		assertNotNull(off.load(0, 50, 50));
		assertFalse(off.ready(20000, 50, 50));
		for (int i = 0; i < 20; i++)
		{
			off.request(20000, 50, 50, 1);
		}
		Thread.sleep(50);
		assertEquals("no request at all with the setting off: " + rig.requests, 0, rig.requests.size());
		assertNull(off.status());
		assertTrue(off.exists(20000, 50, 50));

		rig.allowed = true;
		rig.clock.addAndGet(1_000);
		assertTrue("turning it on starts the download", fetched(off, 20000, 50, 50));
	}

	@Test
	public void theCacheFolderStaysTidy() throws Exception
	{
		Rig rig = release(1, 0x336699);
		PackCache cache = new PackCache(Filepath.Unchecked.getRooted(rig.folder));
		assertTrue(cache.names().isEmpty());
		cache.write("a-111111111111.zip", new byte[] {1});
		cache.write("b-222222222222.zip", new byte[] {2, 3});
		assertEquals(2, cache.names().size());
		assertEquals(2, cache.read("b-222222222222.zip", 10).length);
		assertNull("larger than allowed", cache.read("b-222222222222.zip", 1));
		assertNull(cache.read("missing.zip", 10));
		cache.retain(Collections.singleton("a-111111111111.zip"));
		assertEquals(Collections.singleton("a-111111111111.zip"), cache.names());
		try
		{
			cache.write("../escape.zip", new byte[1]);
			fail("a path that leaves the folder must be refused");
		}
		catch (RuntimeException | IOException expected)
		{
			// Filepath refuses it.
		}
	}
}
