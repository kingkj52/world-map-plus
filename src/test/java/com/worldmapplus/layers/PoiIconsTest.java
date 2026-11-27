package com.worldmapplus.layers;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * The point of interest pictures, plain PNG files under {@code poi-icons/} since round 70 (until then one pack file with its own
 * reader): every picture the bundled points name is there and decodes, and a name that is no picture, or tries to leave the folder,
 * gives none.
 */
public class PoiIconsTest
{
	@Test
	public void everyPictureTheBundledPointsNameIsThereAndDecodes() throws Exception
	{
		Set<String> wanted = new HashSet<>();
		try (InputStream in = PoiIconsTest.class.getResourceAsStream("/com/worldmapplus/pois.json"))
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
		PoiIcons icons = new PoiIcons();
		for (String name : wanted)
		{
			BufferedImage image = icons.get(name);
			assertNotNull(name + " is not in poi-icons or does not decode", image);
			assertTrue(name, image.getWidth() > 0 && image.getWidth() <= 64 && image.getHeight() > 0 && image.getHeight() <= 64);
			assertSame("read once and kept", image, icons.get(name));
		}
	}

	@Test
	public void aNameThatIsNoPictureGivesNone()
	{
		PoiIcons icons = new PoiIcons();
		assertNull(icons.get("no_such_icon.png"));
		assertNull(icons.get(null));
		assertNull(icons.get(""));
		assertNull("no way out of the folder", icons.get("../pois.json"));
		assertNull(icons.get("tiles/index.json"));
	}
}
