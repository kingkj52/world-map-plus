package com.worldmapplus.verify;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldmapplus.tiles.BundledTiles;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the verification tests compare against, and how pixels are hashed. The hash is the contract shared with
 * tools/verify/common.py: sha256 over width * height * 4 bytes, R, G, B, A row by row, with a fully clear pixel
 * as zeros. Opaque images have A=255. Hashes are written as unpadded URL-safe base64.
 */
final class Reference
{
	static final Gson GSON = new Gson();

	private Reference()
	{
	}

	/** One tile or overview of the manifest. */
	static final class Entry
	{
		final String key;
		final int mapId;
		/** The square, or -1 for an overview. */
		final int x;
		final int y;
		final int width;
		final int height;
		final String rgba;
		final String file;

		Entry(String key, int width, int height, String rgba, String file)
		{
			this.key = key;
			this.width = width;
			this.height = height;
			this.rgba = rgba;
			this.file = file;
			int slash = key.indexOf('/');
			this.mapId = Integer.parseInt(key.substring(0, slash));
			String rest = key.substring(slash + 1);
			if (rest.equals("overview"))
			{
				x = -1;
				y = -1;
			}
			else
			{
				int us = rest.indexOf('_');
				x = Integer.parseInt(rest.substring(0, us));
				y = Integer.parseInt(rest.substring(us + 1));
			}
		}

		boolean isOverview()
		{
			return x < 0;
		}
	}

	/** The parsed tools/verify/manifest.json. */
	static final class Manifest
	{
		final Map<String, Entry> entries = new LinkedHashMap<>();
		final List<String> orphans = new ArrayList<>();
		String tileVersion = "";
		String generated = "";
	}

	/** Where a file of the harness lives. The tests run from the project directory. */
	static Path verifyFile(String name)
	{
		String base = System.getProperty("wmp.verify.dir", "tools/verify");
		return Paths.get(base).resolve(name);
	}

	static Manifest manifest() throws IOException
	{
		Manifest m = new Manifest();
		try (Reader in = Files.newBufferedReader(verifyFile("manifest.json"), StandardCharsets.UTF_8))
		{
			JsonObject doc = GSON.fromJson(in, JsonObject.class);
			JsonObject dims = doc.getAsJsonObject("dims");
			for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("tiles").entrySet())
			{
				String[] parts = e.getValue().getAsString().split(":");
				int w = 256;
				int h = 256;
				if (dims.has(e.getKey()))
				{
					w = dims.getAsJsonArray(e.getKey()).get(0).getAsInt();
					h = dims.getAsJsonArray(e.getKey()).get(1).getAsInt();
				}
				m.entries.put(e.getKey(), new Entry(e.getKey(), w, h, parts[0], parts[1]));
			}
			for (JsonElement o : doc.getAsJsonArray("orphans"))
			{
				m.orphans.add(o.getAsString());
			}
			m.tileVersion = doc.get("tileVersion").getAsString();
			m.generated = doc.get("generated").getAsString();
		}
		return m;
	}

	/** Digest strings per tile key, from a file of the form {"<section>": {"key": "digest"}}. */
	static Map<String, String> section(String file, String section) throws IOException
	{
		Map<String, String> out = new LinkedHashMap<>();
		try (Reader in = Files.newBufferedReader(verifyFile(file), StandardCharsets.UTF_8))
		{
			JsonObject doc = GSON.fromJson(in, JsonObject.class);
			for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject(section).entrySet())
			{
				out.put(e.getKey(), e.getValue().getAsString());
			}
		}
		return out;
	}

	static String hash(BufferedImage image)
	{
		int w = image.getWidth();
		int h = image.getHeight();
		return hash(image.getRGB(0, 0, w, h, null, 0, w), w, h);
	}

	static String hash(int[] argb, int w, int h)
	{
		byte[] bytes = new byte[w * h * 4];
		for (int i = 0; i < w * h; i++)
		{
			int v = argb[i];
			if ((v >>> 24) == 0)
			{
				continue;
			}
			bytes[i * 4] = (byte) (v >> 16);
			bytes[i * 4 + 1] = (byte) (v >> 8);
			bytes[i * 4 + 2] = (byte) v;
			bytes[i * 4 + 3] = (byte) (v >>> 24);
		}
		return b64(sha256(bytes));
	}

	static byte[] sha256(byte[] bytes)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	static String b64(byte[] digest)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	/** Loads one entry through the real source, the way the plugin does. Null when the source has nothing there. */
	static BufferedImage load(BundledTiles source, Entry e)
	{
		return e.isOverview() ? source.overview(e.mapId) : source.load(e.mapId, e.x, e.y);
	}

	static String describe(List<String> problems)
	{
		List<String> shown = problems.size() > 20 ? problems.subList(0, 20) : problems;
		return problems.size() + " problems: " + shown + (problems.size() > 20 ? " ..." : "");
	}

	static List<String> syncList()
	{
		return Collections.synchronizedList(new ArrayList<>());
	}

	/** True when the strict mode is on: environment variable WMP_STRICT or system property wmp.strict. */
	static boolean strict()
	{
		return System.getenv("WMP_STRICT") != null || Boolean.getBoolean("wmp.strict");
	}
}
