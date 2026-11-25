package com.worldmapplus.tiles;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import net.runelite.client.util.Filepath;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The real read path of the plugin, over the release made by {@code tools/make_map_release.py} (folder {@code build/map-release},
 * or the system property {@code wmp.release}): a {@link PackedTiles} whose downloads are answered from that folder by a fake HTTP
 * layer, so no network is used. {@link #load} waits for the download the way the tile cache would on later frames. Test scope only.
 */
public final class ReleaseTiles implements TileSource
{
	public static final String BASE = "https://maps.test/release/";

	private final PackedTiles inner;
	private final TileIndex index;

	private ReleaseTiles(PackedTiles inner, TileIndex index)
	{
		this.inner = inner;
		this.index = index;
	}

	public static Path folder()
	{
		return Paths.get(System.getProperty("wmp.release", "build/map-release"));
	}

	/** True when a release has been built (python tools/make_map_release.py). */
	public static boolean available()
	{
		return Files.isRegularFile(folder().resolve("manifest.json"));
	}

	/** An HTTP client that answers from a folder and never touches the network: any file there is a 200, anything else a 404. */
	public static OkHttpClient clientFor(Path folder)
	{
		return new OkHttpClient.Builder().addInterceptor(chain ->
		{
			Request request = chain.request();
			String name = request.url().pathSegments().get(request.url().pathSegments().size() - 1);
			Path file = folder.resolve(name);
			boolean found = Files.isRegularFile(file);
			byte[] bytes = found ? Files.readAllBytes(file) : new byte[0];
			return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(found ? 200 : 404).message(found ? "OK" : "Not Found")
				.body(ResponseBody.create(MediaType.get("application/octet-stream"), bytes)).build();
		}).build();
	}

	/** Opens the release with a cache folder of its own and room for every pack in memory. */
	public static ReleaseTiles open(Gson gson) throws IOException
	{
		TileIndex index = TileIndex.load(gson);
		Filepath cache = Filepath.Unchecked.getRooted(Files.createTempDirectory("wmp-release-test"));
		PackedTiles tiles = new PackedTiles(index, gson, Runnable::run, new MapDownloader(clientFor(folder()), BASE, System::currentTimeMillis),
			new PackCache(cache), () -> true, System::currentTimeMillis);
		tiles.memoryBudget = 64L << 20;
		ReleaseTiles opened = new ReleaseTiles(tiles, index);
		// The manifest comes with the first request; after it a square exists only when a pack holds it.
		opened.load(0, 50, 50);
		return opened;
	}

	public int tileCount(int mapId)
	{
		return index.tileCount(mapId);
	}

	public int highestFloor(int mapId)
	{
		return index.highestFloor(mapId);
	}

	@Override
	public boolean exists(int mapId, int x, int y)
	{
		return inner.exists(mapId, x, y);
	}

	@Nullable
	@Override
	public BufferedImage load(int mapId, int x, int y)
	{
		if (!exists(mapId, x, y))
		{
			return null;
		}
		try
		{
			for (int i = 0; i < 30_000 && !inner.ready(mapId, x, y); i++)
			{
				inner.request(mapId, x, y, 0);
				Thread.sleep(1);
				if (i > 5 && PackedTiles.FAILED.equals(inner.status()))
				{
					// A pack that does not come (or does not match) is waited out by the real source; a test has no use for the wait.
					return null;
				}
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return null;
		}
		return inner.load(mapId, x, y);
	}

	@Nullable
	@Override
	public BufferedImage overview(int mapId)
	{
		return inner.overview(mapId);
	}
}
