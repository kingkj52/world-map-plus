package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HotspotIndex;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.PoiCategory;
import com.worldmapplus.layers.PoiIcons;
import com.worldmapplus.layers.PoiLayer;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The data layers that are cards and no markers (shops, transports and shortcuts, doorways, teleports): none of them draws anything of
 * its own, so none can duplicate an icon of the game, and the cards that sit on an icon were merged into it when the icons were loaded
 * (the icon's card is theirs). The counts are printed so docs/PLAN.md section 41 can quote them.
 */
public class CardLayersAuditTest
{
	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	@BeforeClass
	public static void load()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor);
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	private static boolean drawsAnything(Hotspot hotspot, Map<String, PoiCategory> categories, Set<String> layers)
	{
		MapInfo map = assets.getCatalog().byId(hotspot.getMapId());
		PoiLayer layer = new PoiLayer(new HotspotIndex(Collections.singletonList(hotspot)), new PoiIcons(), categories);
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		layer.paint(g, new ViewTransform(hotspot.getX(), hotspot.getY(), 4.0, 0, 0, 100, 100), map, layers);
		g.dispose();
		for (int y = 0; y < 100; y++)
		{
			for (int x = 0; x < 100; x++)
			{
				if (image.getRGB(x, y) >>> 24 != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void theCardLayersDrawNothingAndTheirCardsRideOnIconsWhereThereAreIcons()
	{
		Map<String, PoiCategory> categories = new LinkedHashMap<>();
		Set<String> layers = new HashSet<>();
		for (PoiCategory category : assets.getPoiCategories())
		{
			categories.put(category.getId(), category);
			layers.add(category.getId());
		}
		// The cards of the game's icons, by the card object: a card that an icon uses is on that icon.
		Map<HoverCard, Boolean> onAnIcon = new IdentityHashMap<>();
		GameIconSet icons = assets.getGameIcons();
		for (int mapId : icons.mapIds())
		{
			GameIconSet.MapIcons on = icons.on(mapId);
			for (int i = 0; i < on.size(); i++)
			{
				if (on.card(i) != null)
				{
					onAnIcon.put(on.card(i).getCard(), Boolean.TRUE);
				}
			}
		}
		Map<Hotspot.Kind, int[]> counts = new EnumMap<>(Hotspot.Kind.class);
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			if (hotspot.getCategory() != null || hotspot.isMapIcon())
			{
				continue;
			}
			assertFalse(hotspot.getKind() + " " + hotspot.getCard().getTitle() + " draws a marker", drawsAnything(hotspot, categories, layers));
			int[] count = counts.computeIfAbsent(hotspot.getKind(), k -> new int[2]);
			count[onAnIcon.containsKey(hotspot.getCard()) ? 0 : 1]++;
		}
		for (Map.Entry<Hotspot.Kind, int[]> entry : counts.entrySet())
		{
			System.out.println("CARD LAYER " + entry.getKey() + ": on an icon " + entry.getValue()[0] + ", on no icon " + entry.getValue()[1]);
		}
		assertTrue(counts.containsKey(Hotspot.Kind.TRANSPORT));
		// The teleport destinations: cards of the teleport layer, each at a place a teleport lands on, and the layer's own markers.
		int cards = 0;
		int cardsOnIcons = 0;
		int markers = 0;
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			if (!"teleport".equals(hotspot.getCategory()))
			{
				continue;
			}
			if (hotspot.getKind() == Hotspot.Kind.TELEPORT)
			{
				cards++;
				GameIconSet.MapIcons on = icons.on(hotspot.getMapId());
				boolean near = false;
				for (int i = 0; on != null && i < on.size() && !near; i++)
				{
					near = Math.abs(on.x(i) - hotspot.getX()) <= 2 && Math.abs(on.y(i) - hotspot.getY()) <= 2;
				}
				cardsOnIcons += near ? 1 : 0;
			}
			else
			{
				markers++;
			}
		}
		System.out.println("CARD LAYER teleport cards " + cards + ", within 2 tiles of an icon " + cardsOnIcons + "; teleport markers "
			+ markers);
	}
}
