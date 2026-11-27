package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 70: every picture the map draws, pixel for pixel, as it was before the pictures became plain PNG files and the dimmed,
 * padlocked and dotted icons were drawn at build time ({@code golden/icons.txt}): each icon of the game's map at every side the zoom
 * draws it at, plain, locked and with each quest dot, and each point of interest picture. A picture is written as its size and a
 * digest of its ARGB values row by row. What is read now is written to {@code build/icons-actual.txt}.
 */
public class IconsGoldenTest
{
	static String digest(BufferedImage image) throws NoSuchAlgorithmException
	{
		if (image == null)
		{
			return "none";
		}
		MessageDigest sha = MessageDigest.getInstance("SHA-1");
		byte[] row = new byte[image.getWidth() * 4];
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				int argb = image.getRGB(x, y);
				row[x * 4] = (byte) (argb >>> 24);
				row[x * 4 + 1] = (byte) (argb >>> 16);
				row[x * 4 + 2] = (byte) (argb >>> 8);
				row[x * 4 + 3] = (byte) argb;
			}
			sha.update(row);
		}
		StringBuilder hex = new StringBuilder(image.getWidth() + "x" + image.getHeight() + " ");
		for (byte b : sha.digest())
		{
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	@Test
	public void everyPictureIsAsBeforeTheMove() throws IOException, NoSuchAlgorithmException
	{
		StringBuilder out = new StringBuilder();
		GameIconSprites sprites = GameIconSprites.load(136);
		out.append("game icons ").append(sprites.size()).append('\n');
		for (int side = GameIconScale.MIN_SIDE; side <= GameIconScale.MAX_SIDE; side++)
		{
			for (int i = 0; i < sprites.size(); i++)
			{
				out.append(side).append(' ').append(i).append(" plain ").append(digest(sprites.get(i, side, GameIconLayer.NORMAL)))
					.append('\n');
				out.append(side).append(' ').append(i).append(" locked ").append(digest(sprites.get(i, side, GameIconLayer.LOCKED)))
					.append('\n');
				for (int state = 0; state < 3; state++)
				{
					out.append(side).append(' ').append(i).append(" dot ").append(state).append(' ')
						.append(digest(sprites.get(i, side, GameIconLayer.QUEST_NOT_STARTED + state))).append('\n');
				}
			}
		}
		TreeSet<String> names = new TreeSet<>();
		try (InputStream in = IconsGoldenTest.class.getResourceAsStream("/com/worldmapplus/pois.json"))
		{
			Matcher m = Pattern.compile("\"([^\"/]+\\.png)\"").matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			while (m.find())
			{
				names.add(m.group(1));
			}
		}
		PoiIcons icons = new PoiIcons();
		out.append("point of interest pictures ").append(names.size()).append('\n');
		for (String name : names)
		{
			out.append(name).append(' ').append(digest(icons.get(name))).append('\n');
		}
		String text = out.toString();
		Path actual = Paths.get("build", "icons-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "icons.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		if (!expected.equals(text))
		{
			String[] a = expected.split("\n", -1);
			String[] b = text.split("\n", -1);
			for (int i = 0; i < Math.min(a.length, b.length); i++)
			{
				assertEquals("line " + (i + 1), a[i], b[i]);
			}
			assertEquals("lines", a.length, b.length);
		}
	}
}
