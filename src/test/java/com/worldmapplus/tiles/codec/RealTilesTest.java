package com.worldmapplus.tiles.codec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldmapplus.tiles.PngTiles;
import com.worldmapplus.tiles.TileSource;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Assume;
import org.junit.Test;

/**
 * Real tiles, compared with what the plugin produced from the PNG files before the chunk files. Reads the PNG sources in
 * {@code tools/tile-src} the way the plugin did (ImageIO, then drawn into an RGB or ARGB image), encodes them, decodes
 * them and compares by SHA-256 of the pixels and by getRGB. Skipped when the PNG sources are not on this machine.
 */
public class RealTilesTest
{
	private static JsonObject index()
	{
		Assume.assumeTrue("the PNG tile sources are not on this machine", PngTiles.available());
		try
		{
			return new Gson().fromJson(new String(Files.readAllBytes(PngTiles.sourceRoot().resolve("index.json")), StandardCharsets.UTF_8),
				JsonObject.class);
		}
		catch (java.io.IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static BufferedImage png(int mapId, int x, int y)
	{
		try
		{
			return PngTiles.tile(PngTiles.sourceRoot(), mapId, x, y);
		}
		catch (java.io.IOException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static String group(int mapId)
	{
		return PackTiles.group(mapId);
	}

	/** Every k-th tile of each group, so the sample spans the whole map. */
	private static List<int[]> sample()
	{
		Map<String, List<int[]>> all = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : index().getAsJsonObject("maps").entrySet())
		{
			int map = Integer.parseInt(e.getKey());
			for (JsonElement v : e.getValue().getAsJsonArray())
			{
				int key = v.getAsInt();
				all.computeIfAbsent(group(map), k -> new ArrayList<>()).add(new int[] {map, key >>> 16, key & 0xFFFF});
			}
		}
		List<int[]> picked = new ArrayList<>();
		for (Map.Entry<String, List<int[]>> e : all.entrySet())
		{
			List<int[]> tiles = e.getValue();
			int want = e.getKey().equals("surface") ? 70 : 45;
			int step = Math.max(1, tiles.size() / want);
			for (int i = 0; i < tiles.size(); i += step)
			{
				picked.add(tiles.get(i));
			}
		}
		return picked;
	}

	@Test
	public void sampledRealTilesDecodeToTheSamePixelsAsTheCurrentPath()
	{
		List<int[]> tiles = sample();
		assertTrue("sample of " + tiles.size(), tiles.size() >= 200);
		TileEncoder encoder = new TileEncoder();
		TileDecoder decoder = new TileDecoder();
		Map<String, Integer> perGroup = new TreeMap<>();
		for (int[] t : tiles)
		{
			BufferedImage ref = png(t[0], t[1], t[2]);
			boolean alpha = t[0] >= TileSource.FLOOR_STRIDE;
			int[] px = TileEncoder.pixels(ref);
			byte[] payload = encoder.encode(px, 256, 256);
			BufferedImage got = decoder.decodeImage(payload, 0, payload.length, 256, 256, alpha);
			String what = t[0] + "/" + t[1] + "_" + t[2];
			assertTrue(what + " is the same image", Corpus.sameImage(got, ref));
			assertEquals(what + " sha256", PackTiles.sha256(px), PackTiles.sha256(TileEncoder.pixels(got)));
			perGroup.merge(group(t[0]), 1, Integer::sum);
		}
		for (String g : Arrays.asList("surface", "floors", "game", "extra"))
		{
			assertTrue(g + " is in the sample", perGroup.getOrDefault(g, 0) >= 20);
		}
	}

	@Test
	public void aChunkOfRealTilesWithAliasesDecodesToTheSamePixels()
	{
		JsonObject maps = index().getAsJsonObject("maps");
		// the region of the ground map with the most squares, with its floors
		Map<String, List<int[]>> regions = new TreeMap<>();
		for (int base : new int[] {0, 20000, 40000, 60000})
		{
			if (!maps.has(Integer.toString(base)))
			{
				continue;
			}
			for (JsonElement v : maps.getAsJsonArray(Integer.toString(base)))
			{
				int key = v.getAsInt();
				int x = key >>> 16;
				int y = key & 0xFFFF;
				regions.computeIfAbsent(ChunkNames.chunk(base, x, y), k -> new ArrayList<>()).add(new int[] {base, x, y});
			}
		}
		List<int[]> biggest = null;
		for (List<int[]> r : regions.values())
		{
			if (biggest == null || r.size() > biggest.size())
			{
				biggest = r;
			}
		}
		ChunkBuilder builder = new ChunkBuilder(256, 256);
		for (int[] t : biggest)
		{
			builder.add(t[0], t[1], t[2], TileEncoder.pixels(png(t[0], t[1], t[2])));
		}
		ChunkBuilder.Result built = builder.buildResult(new TileEncoder());
		ChunkFile chunk = ChunkFile.parse(built.bytes);
		TileDecoder decoder = new TileDecoder();
		for (int[] t : biggest)
		{
			BufferedImage got = chunk.decodeImage(t[0], t[1], t[2], t[0] >= TileSource.FLOOR_STRIDE, decoder);
			assertTrue(t[0] + "/" + t[1] + "_" + t[2], Corpus.sameImage(got, png(t[0], t[1], t[2])));
		}
		assertEquals(biggest.size(), chunk.size());
	}
}
