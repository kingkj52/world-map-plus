package com.worldmapplus.tiles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * The only place the plugin talks to a server. It fetches one kind of thing: a file by name from the one release of map pictures
 * at {@link #BASE}, through RuneLite's own OkHttpClient (handed in), asynchronously. What it sends is a plain GET for that file;
 * nothing about the player or the account is in it. GitHub sees the player's IP address, as for any download.
 * <p>
 * Limits: a few downloads at once, every body capped at the size the caller expects, only HTTPS answers from GitHub's own hosts
 * accepted, and after a rate limit (429), a server error (5xx) or a network failure the whole host is left alone for a while that
 * doubles with every failure in a row (honouring Retry-After when it is longer). It never retries by itself: the caller asks again.
 */
final class MapDownloader
{
	/** The release the pictures are downloaded from. The files are named in the manifest, which is the first file asked for. */
	static final String BASE = "https://github.com/KingKj52/world-map-plus-maps/releases/latest/download/";

	static final int MAX_IN_FLIGHT = 3;
	static final long FIRST_BACKOFF_MILLIS = 30_000;
	static final long MAX_BACKOFF_MILLIS = 15 * 60_000;

	/** Called once per request, on a thread of OkHttp: the bytes, or null when the file could not be had. */
	interface Done
	{
		void done(@Nullable byte[] bytes);
	}

	private final OkHttpClient client;
	private final HttpUrl base;
	private final LongSupplier clock;
	/** The downloads under way, so they can be cancelled. Its size is the number of downloads under way. */
	private final Set<Call> calls = new HashSet<>();
	private int failures;
	private long blockedUntil;

	MapDownloader(OkHttpClient client, String base, LongSupplier millis)
	{
		this.client = client;
		this.base = HttpUrl.get(base);
		this.clock = millis;
	}

	/** True while the host is being left alone after a failure. */
	synchronized boolean blocked()
	{
		return clock.getAsLong() < blockedUntil;
	}

	/** Starts fetching a file of at most maxBytes. Returns false, and does nothing, when the host is being left alone or enough are under way. */
	boolean get(String name, int maxBytes, Done done)
	{
		Call started = client.newCall(new Request.Builder().url(HttpUrl.get(base + name)).build());
		synchronized (this)
		{
			if (calls.size() >= MAX_IN_FLIGHT || clock.getAsLong() < blockedUntil)
			{
				return false;
			}
			calls.add(started);
		}
		try
		{
			started.enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					finish(call, null, 0, 0, done);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					byte[] bytes = null;
					try (Response r = response)
					{
						ResponseBody body = r.body();
						if (r.code() == 200 && body != null && trusted(r.request().url()) && body.contentLength() <= maxBytes)
						{
							BufferedSource source = body.source();
							bytes = source.request(maxBytes + 1L) ? null : source.readByteArray();
						}
						finish(call, bytes, r.code(), retryAfterSeconds(r), done);
					}
					catch (IOException | RuntimeException e)
					{
						finish(call, null, 0, 0, done);
					}
				}
			});
		}
		catch (RuntimeException e)
		{
			finish(started, null, 0, 0, done);
		}
		return true;
	}

	/** Cancels every download under way. Their callers are not told and the host is not held against: the plugin is shutting down. */
	void cancelAll()
	{
		List<Call> open;
		synchronized (this)
		{
			open = new ArrayList<>(calls);
			calls.clear();
		}
		for (Call call : open)
		{
			call.cancel();
		}
	}

	/** The answer came from the host asked, or from the hosts GitHub serves release files from, over the same scheme. */
	private boolean trusted(HttpUrl url)
	{
		String host = url.host();
		return url.scheme().equals(base.scheme()) && (host.equals(base.host()) || host.endsWith(".githubusercontent.com"));
	}

	private static long retryAfterSeconds(Response response)
	{
		try
		{
			return Math.max(0, Math.min(3600, Long.parseLong(String.valueOf(response.header("Retry-After")).trim())));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private void finish(Call call, @Nullable byte[] bytes, int code, long retryAfter, Done done)
	{
		synchronized (this)
		{
			if (!calls.remove(call))
			{
				return;
			}
			if (bytes != null)
			{
				failures = 0;
			}
			else if (code == 0 || code == 429 || code >= 500)
			{
				long wait = Math.min(MAX_BACKOFF_MILLIS, FIRST_BACKOFF_MILLIS << Math.min(failures, 5));
				failures++;
				blockedUntil = clock.getAsLong() + Math.max(wait, retryAfter * 1000);
			}
		}
		try
		{
			done.done(bytes);
		}
		catch (RuntimeException e)
		{
			// The caller's own problem; the download is over either way.
		}
	}
}
