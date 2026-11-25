package com.worldmapplus.tiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * An end to end run: the real release (tools/make_map_release.py, in build/map-release) served by a local web server on the loopback
 * address, fetched by a real OkHttp client through the real downloader, kept in a cache folder and read back. The pictures that come
 * out are compared with the PNG sources when those are here. No traffic leaves the machine.
 */
public class LocalServerTest
{
	private HttpServer server;
	private final List<String> served = Collections.synchronizedList(new ArrayList<>());

	@Before
	public void start() throws IOException
	{
		Assume.assumeTrue("no release: run python tools/make_map_release.py", ReleaseTiles.available());
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange ->
		{
			String name = exchange.getRequestURI().getPath().substring(1);
			Path file = ReleaseTiles.folder().resolve(name);
			served.add(name);
			if (name.contains("/") || !Files.isRegularFile(file))
			{
				exchange.sendResponseHeaders(404, -1);
			}
			else
			{
				byte[] bytes = Files.readAllBytes(file);
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
	}

	@After
	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
		}
	}

	private PackedTiles open(Path cache) throws IOException
	{
		String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
		return new PackedTiles(TileIndex.load(new Gson()), new Gson(), Runnable::run,
			new MapDownloader(new OkHttpClient(), base, System::currentTimeMillis), new PackCache(Filepath.Unchecked.getRooted(cache)),
			() -> true, System::currentTimeMillis);
	}

	private static BufferedImage fetch(PackedTiles tiles, int map, int x, int y) throws InterruptedException
	{
		for (int i = 0; i < 5000 && !tiles.ready(map, x, y); i++)
		{
			tiles.request(map, x, y, 0);
			Thread.sleep(2);
		}
		return tiles.load(map, x, y);
	}

	@Test
	public void tilesComeFromTheServerOnceAndFromTheCacheAfterwards() throws Exception
	{
		Path cache = Files.createTempDirectory("wmp-local-server");
		PackedTiles tiles = open(cache);
		int[][] squares = {{0, 50, 50}, {20000, 50, 54}, {40000, 50, 54}, {6, 49, 148}, {0, 51, 50}};
		for (int[] s : squares)
		{
			if (!tiles.exists(s[0], s[1], s[2]))
			{
				continue;
			}
			BufferedImage image = fetch(tiles, s[0], s[1], s[2]);
			assertNotNull("map " + s[0] + " square " + s[1] + "," + s[2], image);
			assertEquals(256, image.getWidth());
			if (PngTiles.available())
			{
				BufferedImage expected = PngTiles.tile(PngTiles.sourceRoot(), s[0], s[1], s[2]);
				assertEquals("same pixels as the PNG source", 0, differing(expected, image));
			}
		}
		assertEquals("one manifest", 1, Collections.frequency(served, "manifest.json"));
		int packs = (int) served.stream().filter(n -> n.endsWith(".zip")).count();
		assertTrue("some packs came: " + served, packs >= 2);
		assertEquals("no pack twice", packs, served.stream().filter(n -> n.endsWith(".zip")).distinct().count());

		// A second start in the same cache folder needs no pack.
		served.clear();
		PackedTiles again = open(cache);
		assertNotNull(fetch(again, 0, 50, 50));
		Thread.sleep(100);
		assertEquals("no pack asked for", 0, served.stream().filter(n -> n.endsWith(".zip")).count());
	}

	private static int differing(BufferedImage a, BufferedImage b)
	{
		int bad = 0;
		for (int x = 0; x < a.getWidth(); x++)
		{
			for (int y = 0; y < a.getHeight(); y++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					bad++;
				}
			}
		}
		return bad;
	}
}
