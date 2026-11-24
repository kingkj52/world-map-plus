package com.worldmapplus.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.Test;

/** The bundled texts that name a floor use the game's UK words and carry no wiki markup (tools/fix_floor_names.py). */
public class FloorTextTest
{
	private static String read(String name) throws IOException
	{
		if (name.equals("spawns.json"))
		{
			return SpawnTestData.json().toString();
		}
		try (InputStream in = FloorTextTest.class.getResourceAsStream("/com/worldmapplus/" + name))
		{
			assertNotNull(name, in);
			return new String(readAll(in), StandardCharsets.UTF_8);
		}
	}

	private static byte[] readAll(InputStream in) throws IOException
	{
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
		{
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	@Test
	public void noTextCarriesTheWikisTwoCountryFloorMarkup() throws IOException
	{
		for (String name : new String[] {"shops.json", "spawns.json", "icon-details.json", "pois.json", "teleports.json", "transports.json"})
		{
			String text = read(name);
			assertFalse(name + " has [UK] markup", text.contains("[UK]") || text.contains("&#91;") || text.contains("[US]"));
			assertFalse(name + " has a character code", Pattern.compile("&(bull|ndash|amp|#\\d+);").matcher(text).find());
		}
	}

	@Test
	public void floorsAreNamedFirstSecondAndThirdTheUkWay() throws IOException
	{
		Pattern old = Pattern.compile("\\((?:Floor [123]|First [Ff]loor|Second [Ff]loor|Third [Ff]loor)\\)|\\b[123](?:st|nd|rd) Floor\\b");
		for (String name : new String[] {"shops.json", "spawns.json", "icon-details.json"})
		{
			assertFalse(name, old.matcher(read(name)).find());
		}
		assertTrue(read("shops.json").contains("1st floor of Legends' Guild"));
		assertTrue(read("icon-details.json").contains("Spikey Chain (1st floor)"));
	}
}
