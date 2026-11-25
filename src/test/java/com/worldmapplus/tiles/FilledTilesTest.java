package com.worldmapplus.tiles;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Checks the map imagery that ships in the jar, read from the PNG sources: every square a map
 * lists is in the tile index (TileManifestTest decodes every one of them), and the places the wiki's imagery left black
 * (tools/fill_tiles.py draws them from the game cache) have something in them.
 */
public class FilledTilesTest
{
	private static final String ROOT = "/com/worldmapplus/";

	private static final SourceTiles TILES = new SourceTiles(new Gson());

	@Before
	public void sourcesPresent()
	{
		Assume.assumeTrue(PngTiles.available());
	}

	private static BufferedImage tile(int mapId, int x, int y) throws IOException
	{
		return TILES.load(mapId, x, y);
	}

	/** The share of a rectangle of a picture with something brighter than near black in it. */
	private static double lit(BufferedImage image, int x0, int y0, int width, int height)
	{
		int lit = 0;
		for (int x = x0; x < x0 + width; x++)
		{
			for (int y = y0; y < y0 + height; y++)
			{
				int rgb = image.getRGB(x, y);
				int max = Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
				if (max > 24)
				{
					lit++;
				}
			}
		}
		return lit / (double) (width * height);
	}

	@Test
	public void everySquareAMapListsHasItsPicture() throws IOException
	{
		JsonObject maps;
		try (InputStream in = FilledTilesTest.class.getResourceAsStream(ROOT + "maps.json"))
		{
			maps = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
		}
		int checked = 0;
		for (JsonElement element : maps.getAsJsonArray("maps"))
		{
			JsonObject map = element.getAsJsonObject();
			JsonArray squares = map.getAsJsonArray("sq");
			for (JsonElement square : squares)
			{
				int packed = square.getAsInt();
				assertTrue("map " + map.get("id") + " lists square " + (packed >> 16) + "," + (packed & 0xFFFF)
					+ " with no picture", TILES.exists(map.get("id").getAsInt(), packed >> 16, packed & 0xFFFF));
				checked++;
			}
		}
		assertTrue(checked > 2000);
	}

	@Test
	public void theHuntersGuildBasementIsDrawn() throws IOException
	{
		// Varlamore Underground, the square at 24,147: the wiki draws it black with three icons on it.
		BufferedImage image = tile(43, 24, 147);
		assertNotNull(image);
		assertTrue("the basement has ground drawn", lit(image, 0, 0, 256, 256) > 0.2);
		// The middle of the hall, which was black.
		assertTrue(lit(image, 60, 20, 80, 80) > 0.3);
	}

	@Test
	public void theFloorsTheWikiLeftOutAreDrawn() throws IOException
	{
		// A square of each map the cache fill changed, and how much of it should now have something in it.
		int[][] squares = {{20, 44, 150}, {33, 18, 159}, {3, 47, 149}, {46, 40, 149}, {30, 60, 159}};
		for (int[] s : squares)
		{
			BufferedImage image = tile(s[0], s[1], s[2]);
			assertNotNull("map " + s[0] + " square " + s[1] + "," + s[2], image);
			assertTrue("map " + s[0] + " square " + s[1] + "," + s[2] + " is mostly black",
				lit(image, 0, 0, 256, 256) > 0.15);
		}
	}
}
