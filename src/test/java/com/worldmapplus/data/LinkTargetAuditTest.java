package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Round 48: every link of an icon or a doorway leads to a map that has a picture of its far tile, named by the data and never
 * found by a fallback, and every dungeon icon's card says the name of its entrance. The Myreque Hideout under the bar of Burgh de
 * Rott led into the Giants' Foundry, which the Kharidian Desert Underground draws over the same squares as the Morytania
 * Underground draws the hideout: the far tile was given to the smallest map that covers it.
 */
public class LinkTargetAuditTest
{
	private static final Gson GSON = new Gson();
	private static MapAssets assets;
	private static MapCatalog catalog;

	@BeforeClass
	public static void load()
	{
		assets = new MapAssets(GSON, Executors.newSingleThreadScheduledExecutor());
		catalog = assets.getCatalog();
	}

	private static JsonObject resource(String name) throws IOException
	{
		try (InputStream in = com.worldmapplus.data.ToolInputs.stream(name);
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			return GSON.fromJson(reader, JsonObject.class);
		}
	}

	@Test
	public void theMyrequeHideoutAtTheBarLeadsIntoTheMorytaniaUnderground()
	{
		int doors = 0;
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			if (hotspot.getMapId() == 0 && Math.hypot(hotspot.getX() - 3488.5, hotspot.getY() - 3231.5) <= 1.5 && hotspot.getTarget() != null)
			{
				doors++;
				assertEquals("the card names the hideout", "Myreque Hideout", hotspot.getCard().getTitle());
				assertEquals("it leads into the Morytania Underground", 14, hotspot.getTarget().getMapId());
				assertTrue("next to where the game's link ends", Math.hypot(hotspot.getTarget().getX() - 3490.5,
					hotspot.getTarget().getY() - 9592.5) <= 3);
				assertEquals("the preview shows the same map", 14, hotspot.getCard().getPreviewMapId());
				assertTrue("the card says so", hotspot.getCard().getSubtitle().contains("Morytania Underground"));
			}
			if (hotspot.getMapId() == 42 && hotspot.getCard().getTitle().equals("Myreque Hideout"))
			{
				throw new AssertionError("a doorway of the hideout inside the Giants' Foundry at " + hotspot.getX() + ", " + hotspot.getY());
			}
		}
		assertTrue("the bar's trapdoor is a doorway", doors >= 1);
	}

	@Test
	public void aNamedMapIsNeverReplacedByTheMapThatCoversTheTile()
	{
		MapInfo foundry = catalog.mapFor(3490, 9592);
		assertNotNull(foundry);
		assertEquals("the smallest map over the tile is the Kharidian Desert Underground", 42, foundry.getId());
		// Which map a link names is decided by tools/build_cards.py (Maps.map_of, tested in tools/test_build_cards.py).
	}

	@Test
	public void everyLinkLeadsToAMapThatShowsItsFarTile()
	{
		List<String> wrong = new ArrayList<>();
		int targets = 0;
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			Hotspot.Target target = hotspot.getTarget();
			if (target == null)
			{
				continue;
			}
			targets++;
			MapInfo map = catalog.byId(target.getMapId());
			if (map == null || !map.contains((int) Math.floor(target.getX()), (int) Math.floor(target.getY())))
			{
				wrong.add(hotspot.getCard().getTitle() + " on " + hotspot.getMapId() + " to " + target.getMapId() + " " + target.getX() + ", "
					+ target.getY());
			}
			if (hotspot.getCard().hasPreview() && hotspot.getCard().getPreviewMapId() != target.getMapId())
			{
				wrong.add(hotspot.getCard().getTitle() + " previews " + hotspot.getCard().getPreviewMapId() + " and opens " + target.getMapId());
			}
		}
		assertTrue("cards that open a map " + targets, targets > 500);
		assertTrue("links off their map: " + wrong, wrong.isEmpty());
	}

	@Test
	public void everyEntranceEndIsOnTheMapItNames() throws IOException
	{
		List<String> wrong = new ArrayList<>();
		for (JsonElement element : resource("entrances.json").getAsJsonArray("links"))
		{
			JsonObject link = element.getAsJsonObject();
			for (String end : new String[] {"a", "b"})
			{
				if (!link.has(end) || link.get(end).isJsonNull())
				{
					continue;
				}
				JsonObject at = link.getAsJsonObject(end);
				MapInfo map = catalog.byId(at.get("map").getAsInt());
				if (map == null || !map.contains(at.get("x").getAsInt(), at.get("y").getAsInt()))
				{
					wrong.add(link.get("name").getAsString() + " " + end + " " + at);
				}
			}
		}
		assertTrue("entrance ends off their map: " + wrong, wrong.isEmpty());
	}

	@Test
	public void everyDungeonIconSaysTheNameOfItsEntrance() throws IOException
	{
		GameIconSet icons = assets.getGameIcons();
		List<String> wrong = new ArrayList<>();
		int checked = 0;
		for (JsonElement element : resource("icon-details.json").getAsJsonArray("details"))
		{
			JsonObject detail = element.getAsJsonObject();
			if (!"Dungeon entrance".equals(detail.has("subtitle") ? detail.get("subtitle").getAsString() : null))
			{
				continue;
			}
			GameIconSet.MapIcons on = icons.on(detail.get("map").getAsInt());
			if (on == null)
			{
				continue;
			}
			double x = detail.get("x").getAsDouble();
			double y = detail.get("y").getAsDouble();
			for (int i = 0; i < on.size(); i++)
			{
				if (on.x(i) == x && on.y(i) == y && on.card(i) != null)
				{
					checked++;
					String title = on.card(i).getCard().getTitle();
					if (!title.equals(detail.get("title").getAsString()))
					{
						wrong.add(x + ", " + y + " is " + detail.get("title").getAsString() + " but says " + title);
					}
				}
			}
		}
		assertTrue("dungeon icons named " + checked, checked > 100);
		assertTrue("dungeon icons whose card names another place: " + wrong, wrong.isEmpty());
	}
}
