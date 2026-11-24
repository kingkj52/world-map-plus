package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.Test;

/** icons.pack: the bundled pictures are all there and decode, and a damaged pack is refused with a message. */
public class IconPackTest
{
	private static byte[] bundledBytes() throws IOException
	{
		try (InputStream in = IconPackTest.class.getResourceAsStream(IconPack.RESOURCE))
		{
			assertNotNull(in);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
			{
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	@Test
	public void everyPictureTheBundledPointsNameIsInThePackAndDecodes() throws Exception
	{
		IconPack pack = IconPack.bundled();
		assertTrue("pictures " + pack.size(), pack.size() > 100);
		Set<String> wanted = new HashSet<>();
		try (InputStream in = IconPackTest.class.getResourceAsStream("/com/worldmapplus/pois.json"))
		{
			JsonObject root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement category : root.getAsJsonArray("categories"))
			{
				for (JsonElement item : category.getAsJsonObject().getAsJsonArray("items"))
				{
					JsonElement icon = item.getAsJsonObject().get("i");
					if (icon != null && !icon.isJsonNull())
					{
						wanted.add(icon.getAsString());
					}
				}
			}
		}
		assertTrue(wanted.size() > 50);
		for (String name : wanted)
		{
			try (InputStream in = pack.open(name))
			{
				assertNotNull(name + " is not in icons.pack", in);
				BufferedImage image = ImageIO.read(in);
				assertNotNull(name + " does not decode", image);
				assertTrue(name, image.getWidth() > 0 && image.getWidth() <= 64 && image.getHeight() > 0 && image.getHeight() <= 64);
			}
		}
		assertNull(pack.open("no_such_icon.png"));
	}

	@Test
	public void everyShortenedPackIsRefused() throws Exception
	{
		byte[] data = bundledBytes();
		for (int length = 0; length < data.length; length += length < 4000 ? 1 : 997)
		{
			refuse(Arrays.copyOf(data, length));
		}
		refuse(Arrays.copyOf(data, data.length - 1));
		refuse(Arrays.copyOf(data, data.length + 1));
	}

	@Test
	public void wrongHeadersAreRefused() throws Exception
	{
		byte[] data = bundledBytes();
		byte[] magic = data.clone();
		magic[0] = 'X';
		refuse(magic);
		byte[] version = data.clone();
		version[4] = 2;
		refuse(version);
		// No pictures, then a count over the limit (5000), then a number longer than five bytes.
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, 0});
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, (byte) 0x88, 0x27});
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1});
		// One picture whose name is 3 bytes "abc" and whose length is 2, with only one byte after it.
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, 1, 0, 3, 'a', 'b', 'c', 2, 9});
		// The same with the right amount of data is fine.
		assertEquals(1, IconPack.parse(new byte[] {'W', 'M', 'I', 'P', 1, 1, 0, 3, 'a', 'b', 'c', 2, 9, 9}).size());
		// Two pictures out of order: "b" then "a" (shared 0, rest 1).
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, 2, 0, 1, 'b', 1, 0, 1, 'a', 1, 7, 7});
		// A picture of length zero, and a name of length zero.
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, 1, 0, 1, 'a', 0});
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, 1, 0, 0, 1, 7});
		// A shared prefix longer than the name before it.
		refuse(new byte[] {'W', 'M', 'I', 'P', 1, 2, 0, 1, 'a', 1, 5, 0, 1, 7, 7});
	}

	private static void refuse(byte[] data)
	{
		try
		{
			IconPack.parse(data);
			fail("accepted " + data.length + " bytes");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
		}
	}
}
