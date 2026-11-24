package com.worldmapplus.verify;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldmapplus.tiles.BundledTiles;
import com.worldmapplus.tiles.PngTiles;
import com.worldmapplus.tiles.TileSource;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * About 40 tiles that stand for every kind of terrain (src/test/resources/golden), kept as pictures next to their
 * expected hashes. Each is decoded the way the plugin decodes a PNG, then compared pixel for pixel with what
 * BundledTiles serves for the same square, whatever the format behind it. The pictures were copied from the
 * shipped PNGs, so they stay valid after the tiles move to another format. To regenerate, see docs/VERIFY.md.
 */
public class GoldenTilesTest
{
	private static int[] pixels(BufferedImage image)
	{
		int[] px = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
		for (int i = 0; i < px.length; i++)
		{
			if ((px[i] >>> 24) == 0)
			{
				px[i] = 0;
			}
		}
		return px;
	}

	/** The plugin's own conversion (PngTiles.toRgb and toArgb, which the plugin used to apply to its PNG tiles), repeated here so a golden picture is read independently. */
	private static BufferedImage asThePluginSeesIt(BufferedImage decoded, boolean keepAlpha)
	{
		BufferedImage out = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
			keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
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

	@Test
	public void goldenTilesAreServedPixelForPixel() throws Exception
	{
		BundledTiles source = BundledTiles.load(Reference.GSON);
		JsonObject tiles;
		try (InputStream in = GoldenTilesTest.class.getResourceAsStream("/golden/golden.json"))
		{
			assertNotNull("src/test/resources/golden/golden.json is missing", in);
			tiles = Reference.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class).getAsJsonObject("tiles");
		}
		assertTrue("expected about 40 golden tiles, found " + tiles.size(), tiles.size() >= 35 && tiles.size() <= 60);

		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : tiles.entrySet())
		{
			String[] p = e.getKey().split("[/_]");
			int map = Integer.parseInt(p[0]);
			int x = Integer.parseInt(p[1]);
			int y = Integer.parseInt(p[2]);
			JsonObject rec = e.getValue().getAsJsonObject();

			BufferedImage picture;
			try (InputStream in = GoldenTilesTest.class.getResourceAsStream("/golden/" + rec.get("file").getAsString()))
			{
				assertNotNull("missing picture " + rec.get("file"), in);
				picture = asThePluginSeesIt(PngTiles.readInMemory(in), map >= TileSource.FLOOR_STRIDE);
			}
			if (!Reference.hash(picture).equals(rec.get("rgba").getAsString()))
			{
				problems.add("picture " + e.getKey() + " does not match its recorded hash");
				continue;
			}
			BufferedImage served = source.load(map, x, y);
			if (served == null)
			{
				problems.add("not served " + e.getKey());
				continue;
			}
			assertEquals(e.getKey() + " width", picture.getWidth(), served.getWidth());
			assertEquals(e.getKey() + " height", picture.getHeight(), served.getHeight());
			try
			{
				assertArrayEquals(e.getKey() + " pixels", pixels(picture), pixels(served));
			}
			catch (AssertionError ex)
			{
				problems.add("pixels differ " + e.getKey());
			}
		}
		assertTrue(Reference.describe(problems), problems.isEmpty());
	}
}
