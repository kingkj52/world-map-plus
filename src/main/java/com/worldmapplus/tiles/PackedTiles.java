package com.worldmapplus.tiles;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.zip.*;
import javax.annotation.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import lombok.extern.slf4j.*;

/**
 * The map pictures. The four overview pictures are PNG files in the plugin jar, so the map always shows something. The detailed
 * tiles are PNG files inside packs (stored zip files of a few hundred kilobytes) that are downloaded the first time the view
 * needs them ({@link MapDownloader}), kept on disk ({@link PackCache}) and read from there ever after. {@link MapManifest} says
 * which pack holds which square and what each must hash to; a pack that does not match is dropped, never used.
 * <p>
 * {@link TileCache} asks for tiles in the order the view needs them. {@link #ready} says whether a tile can be read now, and
 * {@link #request} (called for the tiles the view waits for, with their distance from the middle) is what makes a download
 * happen, the packs nearest the middle first. {@link #load} only ever reads what is already here, so it is quick and never waits
 * for the network. A pack that cannot be had is tried again later, further apart each time, and given up for the session after
 * {@link #MAX_FAILURES} tries.
 */
@Slf4j
public final class PackedTiles implements TileSource
{
	public static final String DOWNLOADING = "Downloading map pictures...";
	public static final String FAILED = "Map pictures could not be downloaded, showing the overview";

	/** The most memory the packs read from disk may use, as downloaded bytes. The newest pack is kept even if it alone is larger. */
	static final long MEMORY_BUDGET = 12L << 20;

	/** A pack counts as wanted this long after the last request for one of its tiles. */
	static final long ASK_MILLIS = 2_000;

	static final int MAX_FAILURES = 6;
	static final long FIRST_RETRY_MILLIS = 30_000;
	static final long MAX_RETRY_MILLIS = 10 * 60_000;

	/** The manifest is fetched again after a pack did not match, but not more often than this. */
	static final long MANIFEST_AGAIN_MILLIS = 10 * 60_000;

	private static final int MAX_TILE_BYTES = 512 << 10;
	private static final int MAX_ENTRIES = 4096;
	private static final int MAX_OVERVIEW_BYTES = 8 << 20;
	private static final int MAX_OVERVIEW_PIXELS = 8192;

	/** The tiles of one pack read into memory, by square key. */
	private static final class Loaded
	{
		final Map<Long, byte[]> tiles = new HashMap<>();
		long bytes;
	}

	private final TileIndex index;
	private final Gson gson;
	private final Executor executor;
	@Nullable
	private final MapDownloader http;
	@Nullable
	private final PackCache cache;
	private final BooleanSupplier download;
	private final LongSupplier clock;

	private MapManifest manifest;
	private boolean started;
	private boolean manifestFetched;
	private boolean manifestStale;
	/** The manifest's own downloading state, kept like a pack's: under way, failures, when to try again, given up. */
	private final MapManifest.Pack fetch = new MapManifest.Pack();
	private long manifestAskedAt;
	private long manifestFetchedAt;

	/** Packs read into memory by their file name, least recently used first. */
	private final LinkedHashMap<String, Loaded> memory = new LinkedHashMap<>(16, 0.75f, true);
	private long memoryBytes;
	private boolean allowedNow;
	private long allowedAt = Long.MIN_VALUE;

	/** The player's setting, asked at most twice a second: the setting costs more to read than the checks that need it. Caller holds the lock. */
	private boolean allowed()
	{
		long now = clock.getAsLong();
		if (allowedAt == Long.MIN_VALUE || now - allowedAt >= 500)
		{
			allowedNow = download.getAsBoolean();
			allowedAt = now;
		}
		return allowedNow;
	}

	/** For tests: the memory budget in force. */
	long memoryBudget = MEMORY_BUDGET;

	/**
	 * @param http where packs come from, or null for a source that never downloads
	 * @param cache where packs are kept, or null to keep them in memory only
	 * @param download whether downloading is allowed right now (the player's setting); asked every time
	 */
	PackedTiles(TileIndex index, Gson gson, Executor executor, @Nullable MapDownloader http, @Nullable PackCache cache,
		BooleanSupplier download, LongSupplier millis)
	{
		this.index = index;
		this.gson = gson;
		this.executor = executor;
		this.http = http;
		this.cache = cache;
		this.download = download;
		this.clock = millis;
		if (cache == null)
		{
			started = true;
		}
		else
		{
			executor.execute(this::readCachedManifest);
		}
	}

	/**
	 * The source the plugin uses: downloads from {@link MapDownloader#BASE} through RuneLite's client and keeps packs in the cache folder
	 * (memory only when there is none).
	 */
	public static PackedTiles create(TileIndex index, Gson gson, Executor executor, okhttp3.OkHttpClient client,
		@Nullable net.runelite.client.util.Filepath pluginFolder, BooleanSupplier download)
	{
		return new PackedTiles(index, gson, executor, new MapDownloader(client, MapDownloader.BASE, System::currentTimeMillis),
			pluginFolder == null ? null : new PackCache(pluginFolder), download, System::currentTimeMillis);
	}

	/** A source that never downloads and keeps nothing: only the overview pictures in the jar show. */
	public static PackedTiles overviewsOnly(TileIndex index, Gson gson, Executor executor)
	{
		return new PackedTiles(index, gson, executor, null, null, () -> false, System::currentTimeMillis);
	}

	// ---- what the view asks ----

	/**
	 * True for every square of the index the release holds a tile for (every square of the index while the manifest is not known).
	 * A tile that cannot be had, because downloads are off or failed, still exists: the view shows the overview in its place.
	 */
	@Override
	public synchronized boolean exists(int mapId, int x, int y)
	{
		return index.exists(mapId, x, y) && (manifest == null || manifest.find(mapId, x, y) != null);
	}

	@Override
	public synchronized boolean ready(int mapId, int x, int y)
	{
		MapManifest.Pack pack = manifest == null ? null : manifest.find(mapId, x, y);
		return pack != null && here(pack);
	}

	private static boolean asked(MapManifest.Pack pack, long now)
	{
		return pack.askedAt != 0 && now - pack.askedAt <= ASK_MILLIS;
	}

	private boolean here(MapManifest.Pack pack)
	{
		return pack.onDisk || memory.containsKey(pack.file);
	}

	@Override
	public synchronized void request(int mapId, int x, int y, double distance)
	{
		if (http == null || !started || !allowed())
		{
			return;
		}
		long now = clock.getAsLong();
		if (manifest == null)
		{
			manifestAskedAt = now;
		}
		askManifest(now);
		MapManifest.Pack pack = manifest == null ? null : manifest.find(mapId, x, y);
		if (pack == null || pack.dead || here(pack))
		{
			return;
		}
		pack.distance = now - pack.askedAt > ASK_MILLIS ? distance : Math.min(pack.distance, distance);
		pack.askedAt = now;
		pump(now);
	}

	@Nullable
	@Override
	public synchronized String status()
	{
		if (http == null || !allowed())
		{
			return null;
		}
		long now = clock.getAsLong();
		boolean asking = manifest == null && now - manifestAskedAt <= ASK_MILLIS;
		boolean trouble = manifest == null && (fetch.dead || fetch.failures > 0);
		if (manifest != null)
		{
			for (MapManifest.Pack pack : manifest.packs)
			{
				if (asked(pack, now) && !here(pack))
				{
					asking = true;
					trouble |= pack.dead || pack.retryAt > now;
				}
			}
		}
		return !asking ? null : trouble || http.blocked() ? FAILED : DOWNLOADING;
	}

	// ---- the manifest ----

	private void readCachedManifest()
	{
		MapManifest found = null;
		Set<String> names = Collections.emptySet();
		try
		{
			byte[] bytes = cache.read(PackCache.MANIFEST, MapManifest.MAX_BYTES);
			if (bytes != null)
			{
				found = MapManifest.parse(gson, bytes, index);
				names = cache.names();
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("World Map Plus: the cached map manifest is not usable", e);
		}
		synchronized (this)
		{
			if (found != null && manifest == null)
			{
				install(found, names);
			}
			started = true;
		}
	}

	/** Starts fetching the manifest if it is time to: once a session, and again after a pack did not match. Caller holds the lock. */
	private void askManifest(long now)
	{
		boolean wanted = !manifestFetched || manifestStale && now - manifestFetchedAt >= MANIFEST_AGAIN_MILLIS;
		if (!wanted || fetch.flying || fetch.dead || now < fetch.retryAt)
		{
			return;
		}
		// Marked first: a download that fails at once may finish before get returns.
		fetch.flying = true;
		if (!http.get("manifest.json", MapManifest.MAX_BYTES, bytes -> later(() -> manifestArrived(bytes))))
		{
			fetch.flying = false;
		}
	}

	private void manifestArrived(@Nullable byte[] bytes)
	{
		MapManifest parsed = null;
		if (bytes != null)
		{
			try
			{
				parsed = MapManifest.parse(gson, bytes, index);
			}
			catch (RuntimeException e)
			{
				log.debug("World Map Plus: the map manifest was refused", e);
			}
		}
		Set<String> names = parsed == null || cache == null ? Collections.emptySet() : cache.names();
		synchronized (this)
		{
			long now = clock.getAsLong();
			fetch.flying = false;
			if (parsed == null)
			{
				fail(fetch, now);
				return;
			}
			fetch.failures = 0;
			manifestFetched = true;
			manifestStale = false;
			manifestFetchedAt = now;
			install(parsed, names);
			pump(now);
		}
		if (cache != null)
		{
			try
			{
				cache.write(PackCache.MANIFEST, bytes);
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("World Map Plus: could not keep the map manifest", e);
			}
			Set<String> keep = new HashSet<>();
			keep.add(PackCache.MANIFEST);
			for (MapManifest.Pack pack : parsed.packs)
			{
				keep.add(pack.file);
			}
			cache.retain(keep);
		}
	}

	/**
	 * Makes a manifest the one in use. A pack that is on disk under the same file name (the hash is in it) stays, and keeps what is
	 * known of its failures, so a pack given up for the session stays given up. Caller holds the lock.
	 */
	private void install(MapManifest next, Set<String> names)
	{
		Set<String> files = new HashSet<>();
		for (MapManifest.Pack pack : next.packs)
		{
			files.add(pack.file);
			pack.onDisk = names.contains(pack.file);
			for (MapManifest.Pack old : manifest == null ? new MapManifest.Pack[0] : manifest.packs)
			{
				if (old.file.equals(pack.file))
				{
					pack.onDisk |= old.onDisk;
					pack.failures = old.failures;
					pack.dead = old.dead;
					pack.retryAt = old.retryAt;
				}
			}
		}
		memory.keySet().removeIf(file -> !files.contains(file));
		memoryBytes = 0;
		for (Loaded loaded : memory.values())
		{
			memoryBytes += loaded.bytes;
		}
		manifest = next;
	}

	/** Counts a failure of a download: the next try comes later each time, and after {@link #MAX_FAILURES} there is none. */
	private static void fail(MapManifest.Pack download, long now)
	{
		download.failures++;
		download.retryAt = now + Math.min(MAX_RETRY_MILLIS, FIRST_RETRY_MILLIS << Math.min(download.failures - 1, 5));
		download.dead = download.failures >= MAX_FAILURES;
	}

	// ---- downloading packs ----

	/** Starts the wanted packs, nearest the middle first, while the downloader takes more. Caller holds the lock. */
	private void pump(long now)
	{
		if (manifest == null || http == null || !allowed() || !(manifestFetched || fetch.failures > 0))
		{
			return;
		}
		while (true)
		{
			MapManifest.Pack best = null;
			for (MapManifest.Pack pack : manifest.packs)
			{
				if (asked(pack, now) && !pack.flying && !pack.dead && now >= pack.retryAt && !here(pack)
					&& (best == null || pack.distance < best.distance))
				{
					best = pack;
				}
			}
			if (best == null)
			{
				return;
			}
			final MapManifest.Pack wanted = best;
			final MapManifest from = manifest;
			wanted.flying = true;
			if (!http.get(wanted.name, (int) wanted.size, bytes -> later(() -> packArrived(from, wanted, bytes))))
			{
				wanted.flying = false;
				return;
			}
		}
	}

	private void packArrived(MapManifest from, MapManifest.Pack pack, @Nullable byte[] bytes)
	{
		Loaded loaded = null;
		boolean wrong = false;
		if (bytes != null)
		{
			try
			{
				if (bytes.length == pack.size && hash(bytes).equals(pack.sha256))
				{
					loaded = parse(bytes);
				}
				else
				{
					wrong = true;
				}
			}
			catch (RuntimeException e)
			{
				wrong = true;
			}
		}
		boolean kept = false;
		if (loaded != null && cache != null)
		{
			try
			{
				cache.write(pack.file, bytes);
				kept = true;
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("World Map Plus: could not keep {}", pack.name, e);
			}
		}
		synchronized (this)
		{
			pack.flying = false;
			long now = clock.getAsLong();
			if (loaded == null)
			{
				fail(pack, now);
				manifestStale |= wrong;
			}
			else
			{
				pack.failures = 0;
				pack.onDisk = kept;
				if (from == manifest)
				{
					remember(pack.file, loaded);
				}
			}
			pump(now);
		}
	}

	private void later(Runnable task)
	{
		try
		{
			executor.execute(task);
		}
		catch (RejectedExecutionException e)
		{
			// Shutting down.
		}
	}

	// ---- reading tiles ----

	@Nullable
	@Override
	public BufferedImage load(int mapId, int x, int y)
	{
		MapManifest.Pack pack;
		Loaded loaded;
		synchronized (this)
		{
			if (http != null && started && !manifestFetched && allowed())
			{
				// The first read of the session also looks for a newer manifest, so a player whose cache holds everything hears of updates.
				askManifest(clock.getAsLong());
			}
			pack = manifest == null ? null : manifest.find(mapId, x, y);
			if (pack == null)
			{
				return null;
			}
			loaded = memory.get(pack.file);
		}
		if (loaded == null)
		{
			loaded = readFromDisk(pack);
		}
		byte[] png = loaded == null ? null : loaded.tiles.get(MapManifest.squareKey(mapId, x, y));
		if (png == null)
		{
			return null;
		}
		try
		{
			return decode(png, mapId >= FLOOR_STRIDE, TILE_PIXELS);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("World Map Plus: could not decode tile {}:{}_{}", mapId, x, y, e);
			return null;
		}
	}

	/** Reads a pack kept on disk. One that does not match its hash is deleted and must be downloaded again. */
	@Nullable
	private Loaded readFromDisk(MapManifest.Pack pack)
	{
		if (cache == null || !pack.onDisk)
		{
			return null;
		}
		try
		{
			byte[] bytes = cache.read(pack.file, (int) pack.size);
			if (bytes != null && bytes.length == pack.size && hash(bytes).equals(pack.sha256))
			{
				Loaded loaded = parse(bytes);
				synchronized (this)
				{
					remember(pack.file, loaded);
				}
				return loaded;
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("World Map Plus: could not read {} from the cache", pack.file, e);
		}
		cache.delete(pack.file);
		synchronized (this)
		{
			pack.onDisk = false;
		}
		return null;
	}

	/** Keeps a pack in memory, letting the least recently used ones go over the budget. Caller holds the lock. */
	private void remember(String file, Loaded loaded)
	{
		Loaded old = memory.put(file, loaded);
		memoryBytes += loaded.bytes - (old == null ? 0 : old.bytes);
		Iterator<Map.Entry<String, Loaded>> it = memory.entrySet().iterator();
		while (memoryBytes > memoryBudget && memory.size() > 1 && it.hasNext())
		{
			Map.Entry<String, Loaded> eldest = it.next();
			if (!eldest.getKey().equals(file))
			{
				memoryBytes -= eldest.getValue().bytes;
				it.remove();
			}
		}
	}

	/** Reads the entries of a pack, named {@code <mapId>/<x>_<y>.png}. Throws for anything else. */
	private static Loaded parse(byte[] zip)
	{
		Loaded loaded = new Loaded();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)))
		{
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry())
			{
				String[] part = entry.getName().replace(".png", "").split("[/_]");
				byte[] png = in.readNBytes(MAX_TILE_BYTES + 1);
				if (part.length != 3 || png.length > MAX_TILE_BYTES || loaded.tiles.size() >= MAX_ENTRIES)
				{
					throw new IllegalArgumentException("unexpected entry " + entry.getName());
				}
				loaded.tiles.put(MapManifest.squareKey(Integer.parseInt(part[0]), Integer.parseInt(part[1]), Integer.parseInt(part[2])), png);
				loaded.bytes += png.length + 64;
			}
		}
		catch (IOException e)
		{
			throw new IllegalArgumentException(e);
		}
		return loaded;
	}

	static String hash(byte[] bytes)
	{
		try
		{
			return String.format("%064x", new java.math.BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	// ---- pictures ----

	/**
	 * Decodes a PNG in memory (never through a temporary file) and draws it into plain RGB, or ARGB for floors, which are laid over
	 * the ground so what is clear in them stays clear. A side of 0 accepts any size up to the largest overview.
	 */
	static BufferedImage decode(byte[] png, boolean alpha, int side) throws IOException
	{
		BufferedImage decoded;
		ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(png));
		try
		{
			decoded = ImageIO.read(in);
		}
		finally
		{
			try
			{
				in.close();
			}
			catch (IOException closedByImageIo)
			{
				// ImageIO closes the stream itself once it has read the image.
			}
		}
		int w = decoded == null ? 0 : decoded.getWidth();
		int h = decoded == null ? 0 : decoded.getHeight();
		if (w < 1 || h < 1 || side > 0 && (w != side || h != side) || w > MAX_OVERVIEW_PIXELS || h > MAX_OVERVIEW_PIXELS)
		{
			throw new IOException("not a picture of the expected size");
		}
		int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
		if (decoded.getType() == type)
		{
			return decoded;
		}
		BufferedImage out = new BufferedImage(w, h, type);
		Graphics2D g = out.createGraphics();
		try
		{
			g.drawImage(decoded, 0, 0, null);
		}
		finally
		{
			g.dispose();
		}
		return out;
	}

	@Nullable
	@Override
	public BufferedImage overview(int mapId)
	{
		try (InputStream in = PackedTiles.class.getResourceAsStream("/com/worldmapplus/tiles/overview-" + mapId + ".png"))
		{
			byte[] png = in == null ? null : in.readNBytes(MAX_OVERVIEW_BYTES + 1);
			return png == null || png.length > MAX_OVERVIEW_BYTES ? null : decode(png, false, 0);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("World Map Plus: could not read the overview of map {}", mapId, e);
			return null;
		}
	}

	/** Cancels the downloads under way, so nothing is left running when the plugin shuts down. They are asked for again when the view needs them. */
	@Override
	public void cancel()
	{
		if (http == null)
		{
			return;
		}
		http.cancelAll();
		synchronized (this)
		{
			fetch.flying = false;
			for (MapManifest.Pack pack : manifest == null ? new MapManifest.Pack[0] : manifest.packs)
			{
				pack.flying = false;
			}
		}
	}

	/** Lets go of the packs held in memory. They are read from disk again when asked. */
	@Override
	public synchronized void release()
	{
		memory.clear();
		memoryBytes = 0;
	}
}
