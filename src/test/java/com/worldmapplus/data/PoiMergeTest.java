package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HotspotIndex;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.IconCards;
import com.worldmapplus.layers.IconExtra;
import com.worldmapplus.layers.PoiCategory;
import com.worldmapplus.layers.PoiIcons;
import com.worldmapplus.layers.PoiLayer;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The points of interest the plugin used to draw over the game's own icons are merged into those icons (tools/merge_pois.py):
 * everything a merged point said is on its icon's card while its layer is on, nothing is drawn where the game draws an icon, a
 * point with no icon still draws, a search for a merged point lands on the icon, and the counts are the ones of the audit
 * (tools/input/poi-merge-audit.json).
 */
public class PoiMergeTest
{
	private static final Gson GSON = new Gson();

	private static ScheduledExecutorService executor;
	private static MapAssets assets;
	private static PoiLoader.Result pois;
	private static List<Hotspot> merged = new ArrayList<>();
	private static List<Hotspot> alone = new ArrayList<>();
	private static Set<String> everyLayer = new HashSet<>();

	@BeforeClass
	public static void load()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(GSON, executor);
		pois = PoiLoader.load(GSON, assets.getCatalog());
		for (Hotspot hotspot : pois.getHotspots())
		{
			(hotspot.isMerged() ? merged : alone).add(hotspot);
		}
		for (PoiCategory category : pois.getCategories())
		{
			everyLayer.add(category.getId());
		}
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	/** Every text a card holds, rows that wait for Shift included. */
	private static String text(HoverCard card)
	{
		StringBuilder all = new StringBuilder(card.getTitle()).append('\n');
		if (card.getSubtitle() != null)
		{
			all.append(card.getSubtitle()).append('\n');
		}
		for (HoverCard.Row row : card.visibleRows(true))
		{
			all.append(row.getLabel()).append('\n').append(row.getValue()).append('\n');
		}
		return all.toString();
	}

	/** The index of the icon a merged point went into, in its map's icons. */
	private static int iconOf(Hotspot hotspot)
	{
		Hotspot.Merge merge = hotspot.getMerge();
		GameIconSet.MapIcons icons = assets.getGameIcons().on(hotspot.getMapId());
		assertNotNull("the map of " + hotspot.getCard().getTitle() + " has icons", icons);
		for (int i = 0; i < icons.size(); i++)
		{
			if (icons.x(i) == merge.getIconX() && icons.y(i) == merge.getIconY() && icons.floor(i) == merge.getIconFloor())
			{
				return i;
			}
		}
		return -1;
	}

	@Test
	public void thereAreMergedPointsAndPointsLeftAlone()
	{
		assertTrue("merged " + merged.size(), merged.size() > 900);
		assertTrue("alone " + alone.size(), alone.size() > 200);
	}

	@Test
	public void everyMergedPointsInformationIsOnTheCardOfItsIcon()
	{
		for (Hotspot hotspot : merged)
		{
			int i = iconOf(hotspot);
			assertTrue("the icon of " + hotspot.getCard().getTitle() + " at " + hotspot.getX() + ", " + hotspot.getY(), i >= 0);
			GameIconSet.MapIcons icons = assets.getGameIcons().on(hotspot.getMapId());
			Hotspot own = icons.card(i);
			assertNotNull("the icon has a card", own);
			HoverCard card = IconCards.compose(own.getCard(), icons.floor(i), icons.extras(i), everyLayer);
			String all = text(card);
			// A place's name may be spelled with other capitals on the card, where the card already says it is near the place.
			assertTrue("the card of the icon says " + hotspot.getCard().getTitle() + ": " + all,
				all.toLowerCase(java.util.Locale.ROOT).contains(hotspot.getCard().getTitle().toLowerCase(java.util.Locale.ROOT)));
			IconExtra mine = null;
			for (IconExtra extra : icons.extras(i))
			{
				if (extra.getName().equals(hotspot.getCard().getTitle()) && java.util.Objects.equals(extra.getDetail(), hotspot.getCard().getSubtitle()))
				{
					mine = extra;
				}
			}
			assertNotNull("the icon carries the point " + hotspot.getCard().getTitle(), mine);
			String detail = IconCards.detailOf(mine);
			assertTrue("the card of the icon says '" + detail + "': " + all, all.contains(detail));
		}
	}

	@Test
	public void aLayerThatIsOffLeavesTheIconWithItsBasicCard()
	{
		int checked = 0;
		for (Hotspot hotspot : merged)
		{
			if (hotspot.getMerge().isAlways())
			{
				continue;
			}
			int i = iconOf(hotspot);
			GameIconSet.MapIcons icons = assets.getGameIcons().on(hotspot.getMapId());
			HoverCard own = icons.card(i).getCard();
			// Every layer but this point's own is on: nothing of the point is on the card.
			Set<String> others = new HashSet<>(everyLayer);
			others.remove(hotspot.getCategory());
			HoverCard shown = IconCards.compose(own, 0, icons.extras(i), others);
			List<String> mine = new ArrayList<>();
			for (IconExtra extra : icons.extras(i))
			{
				if (hotspot.getCategory().equals(extra.getLayer()))
				{
					mine.add(extra.getName());
				}
			}
			for (String name : mine)
			{
				boolean alsoBasic = text(own).contains(name);
				assertTrue("'" + name + "' stays off the card while its layer is off", alsoBasic || !text(shown).contains(name));
			}
			if (++checked > 400)
			{
				break;
			}
		}
		assertTrue(checked > 100);
	}

	private static boolean drawsAnything(Hotspot hotspot)
	{
		MapInfo map = assets.getCatalog().byId(hotspot.getMapId());
		PoiLayer layer = new PoiLayer(new HotspotIndex(Collections.singletonList(hotspot)), new PoiIcons(), categories());
		BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		layer.paint(g, new ViewTransform(hotspot.getX(), hotspot.getY(), 4.0, 0, 0, 120, 120), map, everyLayer);
		g.dispose();
		for (int y = 0; y < 120; y++)
		{
			for (int x = 0; x < 120; x++)
			{
				if (image.getRGB(x, y) >>> 24 != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static Map<String, PoiCategory> categories()
	{
		Map<String, PoiCategory> byId = new LinkedHashMap<>();
		for (PoiCategory category : pois.getCategories())
		{
			byId.put(category.getId(), category);
		}
		return byId;
	}

	@Test
	public void noMarkerIsDrawnWhereTheGameDrawsAnIcon()
	{
		for (Hotspot hotspot : merged)
		{
			assertFalse(hotspot.getCard().getTitle() + " at " + hotspot.getX() + ", " + hotspot.getY() + " is drawn on its icon",
				drawsAnything(hotspot));
		}
	}

	@Test
	public void aPointWithNoIconStillDrawsWhenItsLayerIsOn()
	{
		int drawn = 0;
		for (Hotspot hotspot : alone)
		{
			PoiCategory category = categories().get(hotspot.getCategory());
			if (category == null || !category.isLayer())
			{
				// Kinds the game's map already shows (banks, altars, anvils) are only searched and never drawn.
				continue;
			}
			assertTrue(hotspot.getCard().getTitle() + " at " + hotspot.getX() + ", " + hotspot.getY() + " is still drawn", drawsAnything(hotspot));
			drawn++;
		}
		assertTrue("drawn " + drawn, drawn > 200);
	}

	@Test
	public void aMergedPointStandsOnItsIconAndIsFoundThere()
	{
		Map<String, List<SearchEntry>> byName = new HashMap<>();
		for (SearchEntry entry : assets.getSearchEntries())
		{
			if (entry.getKind() == SearchKind.POINT)
			{
				byName.computeIfAbsent(entry.getName(), k -> new ArrayList<>()).add(entry);
			}
		}
		for (Hotspot hotspot : merged)
		{
			Hotspot.Merge merge = hotspot.getMerge();
			assertEquals(merge.getIconX(), hotspot.getX(), 0);
			assertEquals(merge.getIconY(), hotspot.getY(), 0);
			boolean found = false;
			for (SearchEntry entry : byName.getOrDefault(hotspot.getCard().getTitle(), Collections.emptyList()))
			{
				if (entry.getMapId() != hotspot.getMapId())
				{
					continue;
				}
				found |= entry.getX() == hotspot.getX() && entry.getY() == hotspot.getY();
				for (double[] tile : entry.getAlsoAt())
				{
					found |= tile[0] == hotspot.getX() && tile[1] == hotspot.getY();
				}
			}
			assertTrue("a search for " + hotspot.getCard().getTitle() + " reaches the icon at " + hotspot.getX() + ", " + hotspot.getY(), found);
		}
	}

	@Test
	public void theCountsPerCategoryAreTheAuditsCounts() throws IOException
	{
		File file = new File("tools/input/poi-merge-audit.json");
		Assume.assumeTrue("the audit is not in this copy", file.isFile());
		JsonObject audit;
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			audit = GSON.fromJson(reader, JsonObject.class);
		}
		Map<String, int[]> counts = new TreeMap<>();
		for (Hotspot hotspot : pois.getHotspots())
		{
			counts.computeIfAbsent(hotspot.getCategory(), k -> new int[2])[hotspot.isMerged() ? 0 : 1]++;
		}
		JsonObject categories = audit.getAsJsonObject("categories");
		assertEquals("the audit lists every category", categories.size(), counts.size());
		int matched = 0;
		for (Map.Entry<String, com.google.gson.JsonElement> entry : categories.entrySet())
		{
			JsonObject category = entry.getValue().getAsJsonObject();
			int[] have = counts.get(entry.getKey());
			assertNotNull(entry.getKey(), have);
			assertEquals(entry.getKey() + " merged", category.get("matched").getAsInt(), have[0]);
			assertEquals(entry.getKey() + " left alone", category.get("unmatched").getAsInt(), have[1]);
			matched += have[0];
		}
		assertEquals(merged.size(), matched);
	}

	@Test
	public void aKindWithNoIconOfTheGamesIsNeverMerged()
	{
		for (Hotspot hotspot : merged)
		{
			assertFalse(hotspot.getCategory(), "rc_altar".equals(hotspot.getCategory()) || "teleport".equals(hotspot.getCategory()));
		}
	}
}
