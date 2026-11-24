package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.PoiCategory;
import com.worldmapplus.layers.PoiSelection;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class PoiLoaderTest
{
	private static final Gson GSON = new Gson();

	private static MapCatalog catalog()
	{
		List<MapInfo> maps = new ArrayList<>();
		maps.add(new MapInfo(0, "Gielinor Surface", 896, 1984, 4096, 4288, 2496, 3328));
		return new MapCatalog("v", maps);
	}

	private static final String JSON = "{\"categories\":[{\"id\":\"altar\",\"label\":\"Altars\",\"glyph\":\"+\",\"color\":\"#d6c36c\","
		+ "\"items\":[{\"n\":\"Air Altar\",\"d\":\"Runecrafting altar, level 1\",\"p\":[2985,3293],\"i\":\"air_altar_icon.png\"},"
		+ "{\"n\":\"Spread\",\"d\":\"Two places\",\"p\":[3000,3000,3100,3100]},"
		+ "{\"n\":\"Off the map\",\"p\":[5,5]}]},"
		+ "{\"id\":\"bank\",\"label\":\"Banks\",\"glyph\":\"B\",\"color\":\"bad\",\"items\":[]}]}";

	@Test
	public void categoriesKeepTheirOrderAndLooks()
	{
		PoiLoader.Result result = PoiLoader.read(new StringReader(JSON), GSON, catalog());
		assertEquals(2, result.getCategories().size());
		PoiCategory altar = result.getCategories().get(0);
		assertEquals("altar", altar.getId());
		assertEquals("Altars", altar.getLabel());
		assertEquals("+", altar.getGlyph());
		assertEquals(0xd6c36c, altar.getColor().getRGB() & 0xFFFFFF);
		assertEquals(java.awt.Color.GRAY, result.getCategories().get(1).getColor());
	}

	@Test
	public void everyPointIsAHotspotWithTheSharedCard()
	{
		List<Hotspot> hotspots = PoiLoader.read(new StringReader(JSON), GSON, catalog()).getHotspots();
		assertEquals(3, hotspots.size());
		Hotspot air = hotspots.get(0);
		assertEquals(Hotspot.Kind.POI, air.getKind());
		assertEquals("altar", air.getCategory());
		assertEquals("air_altar_icon.png", air.getIcon());
		assertEquals("Air Altar", air.getCard().getTitle());
		assertEquals("Runecrafting altar, level 1", air.getCard().getSubtitle());
		assertEquals(2985.5, air.getX(), 1e-9);
		assertNull(hotspots.get(1).getIcon());
		assertEquals(hotspots.get(1).getCard(), hotspots.get(2).getCard());
	}

	@Test
	public void pointsOffEveryMapAreSkipped()
	{
		List<Hotspot> hotspots = PoiLoader.read(new StringReader(JSON), GSON, catalog()).getHotspots();
		for (Hotspot h : hotspots)
		{
			assertTrue(!h.getCard().getTitle().equals("Off the map"));
		}
	}

	@Test
	public void damagedDataGivesNothing()
	{
		assertTrue(PoiLoader.read(new StringReader("null"), GSON, catalog()).getHotspots().isEmpty());
		assertTrue(PoiLoader.read(new StringReader("{}"), GSON, catalog()).getCategories().isEmpty());
		assertTrue(PoiLoader.read(new StringReader("{\"categories\":[null,{\"id\":\"x\"}]}"), GSON, catalog())
			.getCategories().isEmpty());
	}

	@Test
	public void theBundledPointsLoadAgainstTheBundledMaps()
	{
		MapCatalog bundled = MapCatalog.load(GSON);
		PoiLoader.Result result = PoiLoader.load(GSON, bundled);
		Set<String> ids = new HashSet<>();
		for (PoiCategory category : result.getCategories())
		{
			ids.add(category.getId());
		}
		assertTrue(ids.containsAll(Arrays.asList("bank", "agility", "minigame", "fishing", "teleport", "rc_altar")));
		assertTrue("points: " + result.getHotspots().size(), result.getHotspots().size() > 1000);
		for (Hotspot h : result.getHotspots())
		{
			assertTrue(ids.contains(h.getCategory()));
		}
	}

	@Test
	public void everyIconNamedInTheDataIsInTheJar()
	{
		MapCatalog bundled = MapCatalog.load(GSON);
		com.worldmapplus.layers.PoiIcons icons = new com.worldmapplus.layers.PoiIcons();
		for (Hotspot h : PoiLoader.load(GSON, bundled).getHotspots())
		{
			if (h.getIcon() != null)
			{
				assertTrue("missing icon " + h.getIcon(), icons.get(h.getIcon()) != null);
			}
		}
	}

	@Test
	public void layerSelectionsParseAndToggleForgivingly()
	{
		assertEquals(new HashSet<>(Arrays.asList("a", "b")), PoiSelection.parse(" a, b ,, "));
		assertTrue(PoiSelection.parse(null).isEmpty());
		assertEquals("a,b,c", PoiSelection.toggled("c,a", "b", true));
		assertEquals("a", PoiSelection.toggled("a,b", "b", false));
		assertEquals("a", PoiSelection.toggled("a", "a", true));
		assertEquals("", PoiSelection.toggled("", "x", false));
	}
}
