package com.worldmapplus.tiles;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Decoded tiles, kept in memory, loaded off the render thread.
 * <p>
 * The renderer asks for the tiles it needs every frame. A tile that is not ready yet is noted and the answer is null, so
 * drawing never waits on a decode and never averages a tile down either. The work is done by a chain of short tasks on the
 * executor: each task does one small thing (decode one tile, decode a few rows of the whole-map picture, or average a few
 * cached tiles down) and then hands the executor the next one, so there is never more than {@link #MAX_TASKS} of this cache's
 * tasks waiting and another plugin's task on the same thread waits for one decode at most. The chain stops when the renderer
 * stops asking, which is judged by the clock because the frame counter stands still when the map is closed.
 * <p>
 * What each task does is chosen when it runs, in this order: a whole-map picture that the view needs, or that the view is waiting
 * for many tiles beside; averaging down tiles that are cached already; the missing tile nearest the middle of the view; the
 * whole-map picture when nothing else is waiting; and last a ring of tiles around the view, widening a little at a time, kept
 * apart from the rest so it can never push anything the view uses out of the cache. Requests no longer being asked for,
 * because the view moved on, are dropped before they are decoded.
 * <p>
 * A tile asked for at a smaller size than it is stored at is decoded, averaged down, and only the small copy is kept, unless the
 * full size is asked for too. The whole-map pictures are few and large and are kept in a set of their own that the tiles never
 * push out.
 */
@Slf4j
public final class TileCache
{
	/** Deepest mip level. Level k is the full tile shrunk by a factor of 2^k. */
	public static final int MAX_MIP = 5;

	/** A queued load is skipped if its tile has not been asked for in this many frames. */
	private static final long STALE_FRAMES = 12;

	/**
	 * The most tasks of this cache on the executor at once, running or waiting. Each task is one decode of about 5 milliseconds, and
	 * the one that finishes puts the next at the back of the queue, behind whatever other plugins have asked for in the meantime, so
	 * they wait for the rest of one decode and never for a queue of them.
	 */
	static final int MAX_TASKS = 1;

	/** Noted requests are looked through for stale ones every this many frames. */
	private static final long SWEEP_FRAMES = 30;

	/**
	 * The chain of tasks stops when nothing has asked for a tile for this long. The renderer asks every frame, so a map on screen
	 * is never close to this, and a map that is closed or hidden stops the work within a fraction of a second.
	 */
	private static final long ASK_WINDOW_NANOS = 400_000_000L;

	/** How long one task works on the whole-map picture. A piece is a few rows, so it can end a little after this. */
	private static final long SLICE_NANOS = 6_000_000L;

	/** The most tiles one task averages down. */
	private static final int SHRINK_BATCH = 8;

	/** With this many tiles waiting for the view the whole-map picture is made first, to have something to show in their place. */
	static final int OVERVIEW_FIRST_WANTS = 128;

	/**
	 * The tiles nearest the middle that are decoded before the whole-map picture even when it comes first, so something sharp
	 * shows in the first frames while the picture is made.
	 */
	static final int HEAD_START_TILES = 6;

	/** The most whole-map pictures kept apart from the tiles. */
	private static final int PINNED_MAX = 3;

	/** The view must have stayed the same this long before tiles beside it are fetched. */
	private static final long PREFETCH_DELAY_NANOS = 150_000_000L;

	/** How many squares wide the ring of tiles fetched around the view may get. */
	static final int PREFETCH_RINGS = 6;

	/** The share of the memory budget the tiles fetched ahead may use, in percent. */
	static final int WARM_PERCENT = 50;

	private static final int OVERVIEW_X = 0xFFFFF;

	/** The largest map id, square coordinate and so on that a key can tell apart: 20 bits each, like the chunk files. */
	static final int MAX_ID = 0xFFFFF;

	private final TileSource source;
	private final Executor executor;
	private final long maxBytes;

	/** Tiles the view uses, least recently used first. */
	private final LinkedHashMap<Long, BufferedImage> cache = new LinkedHashMap<>(512, 0.75f, true);
	/** Tiles fetched ahead of the view, oldest first. They are the first to go when room is needed. */
	private final LinkedHashMap<Long, BufferedImage> warm = new LinkedHashMap<>();
	/** The whole-map pictures, least recently used first. Tiles never push these out. */
	private final LinkedHashMap<Long, BufferedImage> pinned = new LinkedHashMap<>(8, 0.75f, true);
	/** Tiles asked for that are not in the cache yet, by base key. */
	private final Map<Long, Want> wanted = new LinkedHashMap<>();
	/** Whole-map pictures asked for that are not finished yet, by key. */
	private final Map<Long, Job> jobs = new LinkedHashMap<>();
	private final Set<Long> failed = new HashSet<>();
	private long bytes;
	private long warmBytes;
	private long pinnedBytes;
	private int tasks;
	/** Tiles decoded since the loader last had nothing waiting: the first few go before the whole-map picture. */
	private int headStart;
	private int generation;
	private volatile long frame;
	private volatile double focusX = Double.NaN;
	private volatile double focusY = Double.NaN;
	private volatile long lastAsked = System.nanoTime();
	private volatile boolean asking;
	private volatile long askWindowNanos = ASK_WINDOW_NANOS;
	private volatile long prefetchDelayNanos = PREFETCH_DELAY_NANOS;

	// The view the renderer last drew, and the ring of tiles being fetched around it.
	private boolean viewSet;
	private int viewSource;
	private int viewGround;
	private int viewMip;
	private int viewX0;
	private int viewX1;
	private int viewY0;
	private int viewY1;
	private long viewSince;
	private boolean prefetchDone;
	private int ringRadius;
	private int[] ringTiles = new int[0];
	private int ringCount;
	private int ringCursor;

	/** Tells a task that an executor which runs work in the caller's own thread has started the next one inside it. */
	private static final class Reentry
	{
		boolean inside;
		boolean again;
	}

	private final ThreadLocal<Reentry> reentry = ThreadLocal.withInitial(Reentry::new);

	/** One tile asked for and not yet loaded. */
	private static final class Want
	{
		final int mapId;
		final int x;
		final int y;
		long frame;
		/** The mip level the renderer asked for last. */
		int mip;
		boolean running;

		Want(int mapId, int x, int y)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
		}
	}

	/** One whole-map picture asked for and not finished yet. */
	private static final class Job
	{
		final long key;
		final int mapId;
		long frame;
		/** True when the view cannot be drawn right without it, false when it is only wanted to stand in for tiles. */
		boolean needed;
		boolean opened;
		boolean running;
		TileSource.Picture picture;

		Job(long key, int mapId)
		{
			this.key = key;
			this.mapId = mapId;
		}
	}

	/** One small piece of work for the executor. */
	private interface Work
	{
		/** Does the work, outside the lock. */
		void run();

		/** Called when run threw, so what it had marked as in progress is free again. */
		void abandon();
	}

	public TileCache(TileSource source, Executor executor, long maxBytes)
	{
		this.source = source;
		this.executor = executor;
		this.maxBytes = maxBytes;
	}

	public TileSource getSource()
	{
		return source;
	}

	/** For tests: how long the work goes on after the last request. */
	void setAskWindowMillis(long millis)
	{
		askWindowNanos = millis * 1_000_000L;
	}

	/** For tests: how long the view must stay the same before tiles beside it are fetched. */
	void setPrefetchDelayMillis(long millis)
	{
		prefetchDelayNanos = millis * 1_000_000L;
	}

	/** Call once at the start of every rendered frame. */
	public void beginFrame()
	{
		touch();
		long now = ++frame;
		if (now % SWEEP_FRAMES == 0)
		{
			synchronized (this)
			{
				dropStale();
			}
		}
	}

	/**
	 * As {@link #beginFrame()}, and says where the middle of the view is, in map squares, so the tiles nearest it are
	 * loaded first.
	 */
	public void beginFrame(double centreSquareX, double centreSquareY)
	{
		focusX = centreSquareX;
		focusY = centreSquareY;
		beginFrame();
	}

	/** Notes that something is asking for tiles now. */
	private void touch()
	{
		lastAsked = System.nanoTime();
		asking = true;
	}

	/** True while the renderer is asking. */
	private boolean active()
	{
		return asking && System.nanoTime() - lastAsked < askWindowNanos;
	}

	/**
	 * Tells the cache that nothing is looking at the map any more: it was closed or hidden. The work that is under way stops at
	 * its next step, and the tiles stay in memory for a while in case the map comes back. The next request starts it all again.
	 */
	public void endView()
	{
		synchronized (this)
		{
			asking = false;
			viewSet = false;
			ringCount = 0;
			ringCursor = 0;
		}
	}

	/**
	 * Packs a tile into one key: map id in the top 20 bits, the mip level in 4, then x and y in 20 each. The id of a floor of a
	 * map is floor times 20000 plus the map, which passes 16 bits, so a narrower id field would give two maps one key. Every
	 * field is range checked by the callers, so no two different (map, x, y, mip) with values in range share a key.
	 */
	static long key(int mapId, int x, int y, int mip)
	{
		return ((long) (mapId & MAX_ID) << 44) | ((long) (mip & 0xF) << 40)
			| ((long) (x & 0xFFFFF) << 20) | (long) (y & 0xFFFFF);
	}

	/** True when a map id and square fit in a key. Anything else cannot be told apart from another tile, so it is not served. */
	private static boolean fitsKey(int mapId, int x, int y)
	{
		return mapId >= 0 && mapId <= MAX_ID && x >= 0 && x <= MAX_ID && y >= 0 && y <= MAX_ID;
	}

	private static long overviewKey(int mapId)
	{
		return key(mapId, OVERVIEW_X, OVERVIEW_X, MAX_MIP + 1);
	}

	/**
	 * The tile at a mip level, or null if it is not loaded yet (in which case it has been queued),
	 * does not exist, or could not be read. Never does any work beyond looking in memory.
	 */
	@Nullable
	public BufferedImage get(int mapId, int x, int y, int mip)
	{
		int level = Math.max(0, Math.min(MAX_MIP, mip));
		if (!fitsKey(mapId, x, y) || !source.exists(mapId, x, y))
		{
			return null;
		}
		touch();
		long wantedKey = key(mapId, x, y, level);
		long base = key(mapId, x, y, 0);
		synchronized (this)
		{
			BufferedImage hit = cache.get(wantedKey);
			if (hit != null)
			{
				return hit;
			}
			BufferedImage ahead = warm.remove(wantedKey);
			if (ahead != null)
			{
				// The view has reached a tile that was fetched ahead of it.
				warmBytes -= sizeOf(ahead);
				bytes += sizeOf(ahead);
				cache.put(wantedKey, ahead);
				trim(wantedKey);
				return ahead;
			}
			if (failed.contains(base))
			{
				return null;
			}
			want(base, mapId, x, y, level);
			return null;
		}
	}

	/**
	 * Something to draw in place of a tile that is not loaded at the size asked for: a copy of it at another size that is in
	 * memory, the nearest size first and a smaller tile before a larger one. Null when there is none. The renderer stretches it to
	 * the square, and it goes as soon as the real one arrives.
	 */
	@Nullable
	public synchronized BufferedImage substitute(int mapId, int x, int y, int mip)
	{
		if (!fitsKey(mapId, x, y))
		{
			return null;
		}
		int level = Math.max(0, Math.min(MAX_MIP, mip));
		for (int d = 1; d <= MAX_MIP; d++)
		{
			int finer = level - d;
			int coarser = level + d;
			if (finer >= 0)
			{
				BufferedImage found = look(key(mapId, x, y, finer));
				if (found != null)
				{
					return found;
				}
			}
			if (coarser <= MAX_MIP)
			{
				BufferedImage found = look(key(mapId, x, y, coarser));
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	@Nullable
	private BufferedImage look(long key)
	{
		BufferedImage found = cache.get(key);
		return found != null ? found : warm.get(key);
	}

	/** True when the square has a tile that is still to come: it exists and has not failed to load. */
	public synchronized boolean isLoading(int mapId, int x, int y)
	{
		return fitsKey(mapId, x, y) && source.exists(mapId, x, y) && !failed.contains(key(mapId, x, y, 0));
	}

	/** The whole-map picture, or null if there is none or it is not loaded yet. Asking for it starts it, and the view needs it. */
	@Nullable
	public BufferedImage getOverview(int mapId)
	{
		return getOverview(mapId, true);
	}

	/**
	 * The whole-map picture, or null if there is none or it is not loaded yet. Asking starts it and keeps it wanted. With needed
	 * false the picture is only wanted to stand in for tiles that are still loading, so it does not count as pending work and it
	 * waits behind the tiles unless many of them are waiting.
	 */
	@Nullable
	public BufferedImage getOverview(int mapId, boolean needed)
	{
		if (!fitsKey(mapId, 0, 0))
		{
			return null;
		}
		touch();
		long wanted = overviewKey(mapId);
		synchronized (this)
		{
			BufferedImage hit = pinned.get(wanted);
			if (hit != null)
			{
				return hit;
			}
			if (failed.contains(wanted))
			{
				return null;
			}
			Job job = jobs.get(wanted);
			if (job == null)
			{
				job = new Job(wanted, mapId);
				jobs.put(wanted, job);
			}
			job.frame = frame;
			job.needed = needed;
			if (!kick() && !job.running)
			{
				jobs.remove(wanted);
			}
			return null;
		}
	}

	/** The whole-map picture if it is loaded, without asking for it. */
	@Nullable
	public synchronized BufferedImage peekOverview(int mapId)
	{
		return fitsKey(mapId, 0, 0) ? pinned.get(overviewKey(mapId)) : null;
	}

	/** True while the overview is queued or being read, so the caller can wait a frame for it. */
	public synchronized boolean isOverviewPending(int mapId)
	{
		return jobs.containsKey(overviewKey(mapId));
	}

	/**
	 * Says which squares the renderer is drawing, so that when everything in view is done the tiles around it can be fetched. The
	 * ids are the map the tiles are stored under and, for a floor, the ground map beneath it (the same number when there is none).
	 * Tiles are fetched at the same mip level, widening ring by ring while the view stays the same.
	 */
	public void noteView(int sourceId, int groundId, int mip, int firstX, int lastX, int firstY, int lastY)
	{
		touch();
		synchronized (this)
		{
			if (!viewSet || sourceId != viewSource || groundId != viewGround || mip != viewMip || firstX != viewX0 || lastX != viewX1
				|| firstY != viewY0 || lastY != viewY1)
			{
				viewSet = true;
				viewSource = sourceId;
				viewGround = groundId;
				viewMip = mip;
				viewX0 = firstX;
				viewX1 = lastX;
				viewY0 = firstY;
				viewY1 = lastY;
				viewSince = System.nanoTime();
				prefetchDone = false;
				ringRadius = 0;
				ringCount = 0;
				ringCursor = 0;
			}
			else if (!prefetchDone && tasks == 0 && wanted.isEmpty() && System.nanoTime() - viewSince >= prefetchDelayNanos)
			{
				kick();
			}
		}
	}

	/** Notes that a tile is wanted this frame and starts the chain of tasks if it is not running. Caller holds the lock. */
	private void want(long base, int mapId, int x, int y, int level)
	{
		Want want = wanted.get(base);
		if (want == null)
		{
			want = new Want(mapId, x, y);
			wanted.put(base, want);
		}
		want.frame = frame;
		want.mip = level;
		if (!kick() && !want.running)
		{
			wanted.remove(base);
		}
	}

	/**
	 * Hands the executor a task unless enough are out already. Returns false when the executor refused it. Caller holds the lock.
	 */
	private boolean kick()
	{
		if (tasks >= MAX_TASKS)
		{
			return true;
		}
		tasks++;
		try
		{
			executor.execute(this::step);
			return true;
		}
		catch (RejectedExecutionException e)
		{
			tasks--;
			return false;
		}
	}

	/** Forgets requests that have not been repeated for a while. Caller holds the lock. */
	private void dropStale()
	{
		long now = frame;
		Iterator<Want> it = wanted.values().iterator();
		while (it.hasNext())
		{
			Want want = it.next();
			if (!want.running && now - want.frame > STALE_FRAMES)
			{
				it.remove();
			}
		}
		Iterator<Job> jt = jobs.values().iterator();
		while (jt.hasNext())
		{
			Job job = jt.next();
			if (!job.running && now - job.frame > STALE_FRAMES)
			{
				jt.remove();
			}
		}
	}

	// ---- the chain of tasks ----

	/** One task of the chain: does one piece of work, then queues the next. Runs on the executor. */
	private void step()
	{
		Reentry mine = reentry.get();
		if (mine.inside)
		{
			// An executor that runs a task in the thread that submitted it has started the next one inside this one. The loop
			// below carries on instead, so a long fill does not pile up calls.
			mine.again = true;
			return;
		}
		mine.inside = true;
		try
		{
			boolean carryOn = true;
			while (carryOn)
			{
				carryOn = false;
				if (!runPiece())
				{
					return;
				}
				mine.again = false;
				try
				{
					executor.execute(this::step);
				}
				catch (RejectedExecutionException e)
				{
					synchronized (this)
					{
						tasks--;
					}
					return;
				}
				carryOn = mine.again;
				mine.again = false;
			}
		}
		finally
		{
			mine.inside = false;
		}
	}

	/** Does one piece of work. Returns true when the chain goes on, and false when it has ended and given up its place. */
	private boolean runPiece()
	{
		Work work;
		synchronized (this)
		{
			work = pick();
			if (work == null)
			{
				tasks--;
				return false;
			}
		}
		try
		{
			work.run();
		}
		catch (RuntimeException e)
		{
			log.debug("World Map Plus: a map piece failed", e);
			synchronized (this)
			{
				work.abandon();
			}
		}
		synchronized (this)
		{
			if (!active())
			{
				tasks--;
				return false;
			}
		}
		return true;
	}

	/** Chooses the next piece of work, or null when there is none or nobody is asking. Caller holds the lock. */
	@Nullable
	private Work pick()
	{
		if (!active())
		{
			return null;
		}
		long now = frame;
		double fx = focusX;
		double fy = focusY;
		Want nearest = null;
		long nearestKey = 0;
		double best = Double.MAX_VALUE;
		int fresh = 0;
		List<Want> cheap = null;
		Iterator<Map.Entry<Long, Want>> it = wanted.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Long, Want> entry = it.next();
			Want want = entry.getValue();
			if (want.running)
			{
				continue;
			}
			if (now - want.frame > STALE_FRAMES)
			{
				it.remove();
				continue;
			}
			long wantedKey = key(want.mapId, want.x, want.y, want.mip);
			if (cache.containsKey(wantedKey) || warm.containsKey(wantedKey))
			{
				// Already here, from another request or a fetch ahead.
				it.remove();
				continue;
			}
			if (now - want.frame <= 2)
			{
				fresh++;
			}
			if (want.mip > 0 && (cache.containsKey(entry.getKey()) || warm.containsKey(entry.getKey())))
			{
				// The full tile is in memory already, so only the small copy has to be made.
				if (cheap == null)
				{
					cheap = new ArrayList<>();
				}
				cheap.add(want);
				continue;
			}
			double d = Double.isNaN(fx) ? 0 : square(want.x + 0.5 - fx) + square(want.y + 0.5 - fy);
			if (d < best)
			{
				best = d;
				nearest = want;
				nearestKey = entry.getKey();
			}
		}

		Job job = null;
		for (Job j : jobs.values())
		{
			if (!j.running && now - j.frame <= STALE_FRAMES && (job == null || j.needed && !job.needed))
			{
				job = j;
			}
		}
		if (nearest == null && cheap == null)
		{
			headStart = 0;
		}
		if (job != null && headStart >= HEAD_START_TILES && (job.needed || fresh >= OVERVIEW_FIRST_WANTS))
		{
			return slice(job);
		}
		if (cheap != null)
		{
			final double cx = fx;
			final double cy = fy;
			Collections.sort(cheap, (a, b) -> Double.compare(distance(a, cx, cy), distance(b, cx, cy)));
			List<Want> batch = new ArrayList<>(cheap.subList(0, Math.min(SHRINK_BATCH, cheap.size())));
			for (Want want : batch)
			{
				want.running = true;
			}
			return new ShrinkWork(batch, generation);
		}
		if (nearest != null)
		{
			nearest.running = true;
			headStart++;
			return new DecodeWork(nearestKey, nearest, nearest.mip, generation);
		}
		if (job != null)
		{
			return slice(job);
		}
		return pickAhead(fx, fy);
	}

	private static double square(double v)
	{
		return v * v;
	}

	private static double distance(Want want, double fx, double fy)
	{
		return Double.isNaN(fx) ? 0 : square(want.x + 0.5 - fx) + square(want.y + 0.5 - fy);
	}

	private Work slice(Job job)
	{
		job.running = true;
		return new SliceWork(job, generation);
	}

	/** Chooses a tile beside the view to fetch ahead, or null. Caller holds the lock. */
	@Nullable
	private Work pickAhead(double fx, double fy)
	{
		if (!viewSet || prefetchDone || System.nanoTime() - viewSince < prefetchDelayNanos)
		{
			return null;
		}
		int level = viewMip;
		long size = pixelBytes(level);
		while (true)
		{
			if (ringCursor >= ringCount)
			{
				if (++ringRadius > PREFETCH_RINGS)
				{
					prefetchDone = true;
					return null;
				}
				buildRing(fx, fy);
				continue;
			}
			int id = ringTiles[3 * ringCursor];
			int x = ringTiles[3 * ringCursor + 1];
			int y = ringTiles[3 * ringCursor + 2];
			ringCursor++;
			long base = key(id, x, y, 0);
			if (failed.contains(base) || wanted.containsKey(base) || cache.containsKey(key(id, x, y, level)) || warm.containsKey(key(id, x, y, level)))
			{
				continue;
			}
			while (warmBytes + size > maxBytes * WARM_PERCENT / 100 || total() + size > maxBytes)
			{
				// Out of room for tiles ahead. Fetching ahead takes nothing from the tiles of the view, and it only makes room by
				// letting go of tiles fetched earlier for a place the view has left.
				if (!dropFarAhead())
				{
					prefetchDone = true;
					return null;
				}
			}
			return new AheadWork(id, x, y, level, generation);
		}
	}

	/** Drops the oldest tile fetched ahead that is too far from the view to be reached by the ring. Returns false if there is none. */
	private boolean dropFarAhead()
	{
		int reach = PREFETCH_RINGS + 1;
		Iterator<Map.Entry<Long, BufferedImage>> it = warm.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Long, BufferedImage> entry = it.next();
			long key = entry.getKey();
			int x = (int) ((key >> 20) & MAX_ID);
			int y = (int) (key & MAX_ID);
			if (x < viewX0 - reach || x > viewX1 + reach || y < viewY0 - reach || y > viewY1 + reach)
			{
				warmBytes -= sizeOf(entry.getValue());
				it.remove();
				return true;
			}
		}
		return false;
	}

	/** Lists the squares at the current ring distance from the view that have tiles, nearest the middle first. Caller holds the lock. */
	private void buildRing(double fx, double fy)
	{
		int r = ringRadius;
		double cx = Double.isNaN(fx) ? (viewX0 + viewX1 + 1) / 2.0 : fx;
		double cy = Double.isNaN(fy) ? (viewY0 + viewY1 + 1) / 2.0 : fy;
		List<int[]> found = new ArrayList<>();
		int[] ids = viewGround == viewSource ? new int[] {viewSource} : new int[] {viewSource, viewGround};
		for (int x = viewX0 - r; x <= viewX1 + r; x++)
		{
			for (int y = viewY0 - r; y <= viewY1 + r; y++)
			{
				if (x != viewX0 - r && x != viewX1 + r && y != viewY0 - r && y != viewY1 + r)
				{
					continue;
				}
				for (int id : ids)
				{
					if (fitsKey(id, x, y) && source.exists(id, x, y))
					{
						found.add(new int[] {id, x, y});
					}
				}
			}
		}
		Collections.sort(found, (a, b) -> Double.compare(square(a[1] + 0.5 - cx) + square(a[2] + 0.5 - cy),
			square(b[1] + 0.5 - cx) + square(b[2] + 0.5 - cy)));
		if (ringTiles.length < found.size() * 3)
		{
			ringTiles = new int[found.size() * 3];
		}
		for (int i = 0; i < found.size(); i++)
		{
			int[] t = found.get(i);
			ringTiles[3 * i] = t[0];
			ringTiles[3 * i + 1] = t[1];
			ringTiles[3 * i + 2] = t[2];
		}
		ringCount = found.size();
		ringCursor = 0;
	}

	/** Loads one tile, and averages it down when a mip level is wanted. Returns {full, small}, or {null, null}. */
	private BufferedImage[] decode(int mapId, int x, int y, int mip)
	{
		BufferedImage full = null;
		BufferedImage small = null;
		try
		{
			full = source.load(mapId, x, y);
			if (full != null && mip > 0)
			{
				small = shrink(full, mip);
			}
		}
		catch (RuntimeException e)
		{
			log.debug("World Map Plus: could not load tile {}:{}_{}", mapId, x, y, e);
			full = null;
			small = null;
		}
		return new BufferedImage[] {full, small};
	}

	/** Decodes the wanted tile nearest the middle of the view. */
	private final class DecodeWork implements Work
	{
		private final long baseKey;
		private final Want want;
		private final int mip;
		private final int gen;

		DecodeWork(long baseKey, Want want, int mip, int gen)
		{
			this.baseKey = baseKey;
			this.want = want;
			this.mip = mip;
			this.gen = gen;
		}

		@Override
		public void run()
		{
			BufferedImage[] image = decode(want.mapId, want.x, want.y, mip);
			synchronized (TileCache.this)
			{
				want.running = false;
				if (gen != generation)
				{
					return;
				}
				if (image[0] == null)
				{
					wanted.remove(baseKey);
					failed.add(baseKey);
					return;
				}
				if (mip > 0)
				{
					store(key(want.mapId, want.x, want.y, mip), image[1]);
				}
				// The full tile is kept when it is what was asked for, or when the asking changed while it was being decoded.
				if (mip == 0 || want.mip != mip)
				{
					store(baseKey, image[0]);
				}
				if (want.mip == mip && wanted.get(baseKey) == want)
				{
					wanted.remove(baseKey);
				}
			}
		}

		@Override
		public void abandon()
		{
			want.running = false;
		}
	}

	/** Averages down a few tiles whose full size is in memory already. Cheap, so many at once. */
	private final class ShrinkWork implements Work
	{
		private final List<Want> list;
		private final int gen;

		ShrinkWork(List<Want> list, int gen)
		{
			this.list = list;
			this.gen = gen;
		}

		@Override
		public void run()
		{
			for (Want want : list)
			{
				long baseKey = key(want.mapId, want.x, want.y, 0);
				int mip;
				BufferedImage base;
				synchronized (TileCache.this)
				{
					mip = want.mip;
					base = cache.get(baseKey);
					if (base == null)
					{
						base = warm.get(baseKey);
					}
					if (base == null || mip == 0)
					{
						// Gone from memory meanwhile, or the full size is wanted after all: the next piece decodes it.
						want.running = false;
						continue;
					}
				}
				BufferedImage small = shrink(base, mip);
				synchronized (TileCache.this)
				{
					want.running = false;
					if (gen != generation)
					{
						continue;
					}
					store(key(want.mapId, want.x, want.y, mip), small);
					if (want.mip == mip && wanted.get(baseKey) == want)
					{
						wanted.remove(baseKey);
					}
				}
			}
		}

		@Override
		public void abandon()
		{
			for (Want want : list)
			{
				want.running = false;
			}
		}
	}

	/** Decodes the next few rows of a whole-map picture. */
	private final class SliceWork implements Work
	{
		private final Job job;
		private final int gen;

		SliceWork(Job job, int gen)
		{
			this.job = job;
			this.gen = gen;
		}

		@Override
		public void run()
		{
			TileSource.Picture picture = job.picture;
			if (!job.opened)
			{
				picture = source.startOverview(job.mapId);
				job.picture = picture;
				job.opened = true;
			}
			if (picture == null || picture.step(SLICE_NANOS))
			{
				BufferedImage image = picture == null ? null : picture.image();
				synchronized (TileCache.this)
				{
					job.running = false;
					job.picture = null;
					if (gen != generation)
					{
						return;
					}
					jobs.remove(job.key);
					if (image == null)
					{
						failed.add(job.key);
					}
					else
					{
						storePinned(job.key, image);
					}
				}
				return;
			}
			synchronized (TileCache.this)
			{
				job.running = false;
			}
		}

		@Override
		public void abandon()
		{
			job.running = false;
			jobs.remove(job.key);
			failed.add(job.key);
		}
	}

	/** Decodes a tile beside the view and keeps it apart from the tiles the view uses. */
	private final class AheadWork implements Work
	{
		private final int mapId;
		private final int x;
		private final int y;
		private final int mip;
		private final int gen;

		AheadWork(int mapId, int x, int y, int mip, int gen)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.mip = mip;
			this.gen = gen;
		}

		@Override
		public void run()
		{
			BufferedImage[] image = decode(mapId, x, y, mip);
			synchronized (TileCache.this)
			{
				if (gen != generation)
				{
					return;
				}
				if (image[0] == null)
				{
					failed.add(key(mapId, x, y, 0));
					return;
				}
				storeAhead(key(mapId, x, y, mip), mip > 0 ? image[1] : image[0]);
			}
		}

		@Override
		public void abandon()
		{
		}
	}

	// ---- memory ----

	private long total()
	{
		return bytes + warmBytes + pinnedBytes;
	}

	/** Adds an image to the tiles of the view and evicts the least recently used until the budget holds. Caller holds the lock. */
	private void store(long key, BufferedImage image)
	{
		BufferedImage old = cache.put(key, image);
		if (old != null)
		{
			bytes -= sizeOf(old);
		}
		bytes += sizeOf(image);
		BufferedImage ahead = warm.remove(key);
		if (ahead != null)
		{
			warmBytes -= sizeOf(ahead);
		}
		trim(key);
	}

	/**
	 * Drops what is over the budget: tiles fetched ahead first, oldest first, then the tiles of the view, least recently used first.
	 * The one just stored is kept. Caller holds the lock.
	 */
	private void trim(long keep)
	{
		while (total() > maxBytes && !warm.isEmpty())
		{
			Iterator<Map.Entry<Long, BufferedImage>> oldest = warm.entrySet().iterator();
			warmBytes -= sizeOf(oldest.next().getValue());
			oldest.remove();
		}
		Iterator<Map.Entry<Long, BufferedImage>> it = cache.entrySet().iterator();
		while (total() > maxBytes && cache.size() > 1 && it.hasNext())
		{
			Map.Entry<Long, BufferedImage> eldest = it.next();
			if (eldest.getKey() == keep)
			{
				continue;
			}
			bytes -= sizeOf(eldest.getValue());
			it.remove();
		}
	}

	/**
	 * Keeps a tile fetched ahead of the view, if there is room for it without taking anything from the tiles of the view: at most
	 * {@link #WARM_PERCENT} percent of the budget, and only what the budget has to spare, the oldest ones fetched ahead making room.
	 * Caller holds the lock.
	 */
	private void storeAhead(long key, BufferedImage image)
	{
		long size = sizeOf(image);
		long cap = maxBytes * WARM_PERCENT / 100;
		if (cache.containsKey(key) || size > cap)
		{
			return;
		}
		BufferedImage old = warm.remove(key);
		if (old != null)
		{
			warmBytes -= sizeOf(old);
		}
		while (!warm.isEmpty() && (warmBytes + size > cap || total() + size > maxBytes))
		{
			Iterator<Map.Entry<Long, BufferedImage>> oldest = warm.entrySet().iterator();
			warmBytes -= sizeOf(oldest.next().getValue());
			oldest.remove();
		}
		if (warmBytes + size > cap || total() + size > maxBytes)
		{
			return;
		}
		warm.put(key, image);
		warmBytes += size;
	}

	/** Keeps a whole-map picture where tiles cannot push it out. Caller holds the lock. */
	private void storePinned(long key, BufferedImage image)
	{
		BufferedImage old = pinned.put(key, image);
		if (old != null)
		{
			pinnedBytes -= sizeOf(old);
		}
		pinnedBytes += sizeOf(image);
		Iterator<Map.Entry<Long, BufferedImage>> it = pinned.entrySet().iterator();
		while (pinned.size() > PINNED_MAX && it.hasNext())
		{
			Map.Entry<Long, BufferedImage> eldest = it.next();
			if (eldest.getKey() == key)
			{
				continue;
			}
			pinnedBytes -= sizeOf(eldest.getValue());
			it.remove();
		}
		trim(-1);
	}

	private static long sizeOf(BufferedImage image)
	{
		return (long) image.getWidth() * image.getHeight() * 4L;
	}

	/** What a tile costs at a mip level. */
	private static long pixelBytes(int mip)
	{
		long side = Math.max(1, TileSource.TILE_PIXELS >> mip);
		return side * side * 4L;
	}

	/** Averages the tile down by 2^level in each direction. */
	static BufferedImage shrink(BufferedImage full, int level)
	{
		int factor = 1 << level;
		int width = Math.max(1, full.getWidth() / factor);
		int height = Math.max(1, full.getHeight() / factor);
		int[] in = full.getRGB(0, 0, full.getWidth(), full.getHeight(), null, 0, full.getWidth());
		int[] out = new int[width * height];
		int area = factor * factor;
		if (full.getColorModel().hasAlpha())
		{
			return shrinkWithAlpha(full, in, width, height, factor);
		}

		for (int oy = 0; oy < height; oy++)
		{
			for (int ox = 0; ox < width; ox++)
			{
				int r = 0;
				int g = 0;
				int b = 0;
				for (int dy = 0; dy < factor; dy++)
				{
					int row = (oy * factor + dy) * full.getWidth() + ox * factor;
					for (int dx = 0; dx < factor; dx++)
					{
						int p = in[row + dx];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
					}
				}
				out[oy * width + ox] = ((r / area) << 16) | ((g / area) << 8) | (b / area);
			}
		}

		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		result.setRGB(0, 0, width, height, out, 0, width);
		return result;
	}

	/** Averages weighted by opacity, so a clear pixel does not darken its neighbours. */
	private static BufferedImage shrinkWithAlpha(BufferedImage full, int[] in, int width, int height, int factor)
	{
		int[] out = new int[width * height];
		int area = factor * factor;
		for (int oy = 0; oy < height; oy++)
		{
			for (int ox = 0; ox < width; ox++)
			{
				long r = 0;
				long g = 0;
				long b = 0;
				long a = 0;
				for (int dy = 0; dy < factor; dy++)
				{
					int row = (oy * factor + dy) * full.getWidth() + ox * factor;
					for (int dx = 0; dx < factor; dx++)
					{
						int p = in[row + dx];
						int alpha = (p >>> 24) & 0xFF;
						a += alpha;
						r += (long) ((p >> 16) & 0xFF) * alpha;
						g += (long) ((p >> 8) & 0xFF) * alpha;
						b += (long) (p & 0xFF) * alpha;
					}
				}
				if (a == 0)
				{
					out[oy * width + ox] = 0;
					continue;
				}
				int alpha = (int) (a / area);
				out[oy * width + ox] = (alpha << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
			}
		}
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		result.setRGB(0, 0, width, height, out, 0, width);
		return result;
	}

	/** Number of tile images held for the view, for tests. */
	synchronized int size()
	{
		return cache.size();
	}

	/** Number of tile images held that were fetched ahead of the view, for tests. */
	synchronized int aheadSize()
	{
		return warm.size();
	}

	/** Number of whole-map pictures held, for tests. */
	synchronized int pinnedSize()
	{
		return pinned.size();
	}

	/** Bytes of images held: the tiles of the view, the tiles fetched ahead and the whole-map pictures. */
	public synchronized long heldBytes()
	{
		return total();
	}

	/**
	 * Tiles asked for and not yet loaded, and the whole-map pictures the view needs and has not got, for tests and for the
	 * "loading" hint. Tiles fetched ahead of the view and pictures kept only to stand in for tiles are not counted.
	 */
	public synchronized int pendingCount()
	{
		int count = wanted.size();
		for (Job job : jobs.values())
		{
			if (job.needed)
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Lets go of everything if nothing has asked for a tile for the given time. For a caller that has a clock of its own to piggy
	 * back on, such as the game tick: the cache starts no timer. Returns true when it let go.
	 */
	public boolean releaseIfIdle(long idleMillis)
	{
		synchronized (this)
		{
			boolean holding = total() > 0 || !wanted.isEmpty() || !jobs.isEmpty();
			if (!holding || System.nanoTime() - lastAsked < idleMillis * 1_000_000L)
			{
				return false;
			}
		}
		clear();
		return true;
	}

	/**
	 * Drops everything, including what the tile source holds in memory. Used when the plugin shuts down and when the map has been
	 * closed for a while. A load that is running while this is called still finishes, and its tile is dropped.
	 */
	public void clear()
	{
		synchronized (this)
		{
			generation++;
			cache.clear();
			warm.clear();
			pinned.clear();
			failed.clear();
			wanted.clear();
			jobs.clear();
			bytes = 0;
			warmBytes = 0;
			pinnedBytes = 0;
			viewSet = false;
			ringCount = 0;
			ringCursor = 0;
			asking = false;
		}
		source.release();
	}
}
