package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HotspotLoaderTest
{
	private static final Gson GSON = new Gson();

	private static MapCatalog catalog()
	{
		List<MapInfo> maps = new ArrayList<>();
		maps.add(new MapInfo(0, "Gielinor Surface", 896, 1984, 4096, 4288, 2496, 3328));
		maps.add(new MapInfo(6, "Dwarven Mines", 2880, 9600, 3328, 9920, 3040, 9824));
		return new MapCatalog("v", maps);
	}

	@Test
	public void eachDoorwayMakesAHotspotAtBothEnds()
	{
		String json = "{\"links\":[{\"name\":\"Dwarven Mine\",\"a\":{\"map\":0,\"x\":3019,\"y\":3450},"
			+ "\"b\":{\"map\":6,\"x\":3019,\"y\":9850}}]}";
		List<Hotspot> spots = HotspotLoader.entrances(new StringReader(json), GSON, catalog());
		assertEquals(2, spots.size());

		Hotspot surface = spots.get(0);
		assertEquals(0, surface.getMapId());
		assertEquals(3019.5, surface.getX(), 1e-9);
		assertEquals("Dwarven Mine", surface.getCard().getTitle());
		assertEquals(6, surface.getCard().getPreviewMapId());
		assertEquals(6, surface.getTarget().getMapId());
		assertEquals(9850.5, surface.getTarget().getY(), 1e-9);

		Hotspot below = spots.get(1);
		assertEquals(6, below.getMapId());
		assertEquals(0, below.getCard().getPreviewMapId());
		assertEquals(0, below.getTarget().getMapId());
	}

	@Test
	public void anEntranceWithNoImageryAtTheOtherEndIsShownButCannotBeFollowed()
	{
		String json = "{\"links\":[{\"name\":\"Chambers of Xeric\",\"a\":{\"map\":0,\"x\":3019,\"y\":3450},\"b\":null}]}";
		List<Hotspot> spots = HotspotLoader.entrances(new StringReader(json), GSON, catalog());
		assertEquals(1, spots.size());
		assertEquals("Chambers of Xeric", spots.get(0).getCard().getTitle());
		assertEquals("Leads underground", spots.get(0).getCard().getSubtitle());
		assertTrue(spots.get(0).getTarget() == null);
		assertTrue(!spots.get(0).getCard().hasPreview());
	}

	@Test
	public void aStopWithOneDestinationCanBeFollowedWithAClick()
	{
		String json = "{\"routes\":[{\"kind\":\"Minecart\",\"from\":[3141,3504,0],\"to\":[2909,9800,0],\"label\":\"Keldagrim\"}]}";
		Hotspot spot = HotspotLoader.transports(new StringReader(json), GSON, catalog()).get(0);
		assertNotNull(spot.getTarget());
		assertEquals(6, spot.getTarget().getMapId());
		assertEquals(2909.5, spot.getTarget().getX(), 1e-9);
		String two = "{\"routes\":[{\"kind\":\"Boat\",\"from\":[3141,3504,0],\"to\":[2909,9800,0],\"label\":\"A\"},"
			+ "{\"kind\":\"Boat\",\"from\":[3141,3504,0],\"to\":[3000,9800,0],\"label\":\"B\"}]}";
		assertTrue(HotspotLoader.transports(new StringReader(two), GSON, catalog()).get(0).getTarget() == null);
	}

	@Test
	public void aDoorwayToAnUnknownMapIsSkipped()
	{
		String json = "{\"links\":[{\"name\":\"X\",\"a\":{\"map\":0,\"x\":1,\"y\":2},\"b\":{\"map\":99,\"x\":3,\"y\":4}}]}";
		assertTrue(HotspotLoader.entrances(new StringReader(json), GSON, catalog()).isEmpty());
	}

	@Test
	public void aNamelessDoorwayTakesTheDestinationMapsName()
	{
		String json = "{\"links\":[{\"a\":{\"map\":0,\"x\":3019,\"y\":3450},\"b\":{\"map\":6,\"x\":3019,\"y\":9850}}]}";
		List<Hotspot> spots = HotspotLoader.entrances(new StringReader(json), GSON, catalog());
		assertEquals("Dwarven Mines", spots.get(0).getCard().getTitle());
	}

	@Test
	public void routesFromNearbyTilesShareOneStop()
	{
		String json = "{\"routes\":["
			+ "{\"kind\":\"Charter ship\",\"from\":[1455,2968,0],\"to\":[2760,3238,0],\"label\":\"Brimhaven\",\"cost\":\"2,500 coins\"},"
			+ "{\"kind\":\"Charter ship\",\"from\":[1456,2969,0],\"to\":[2792,3414,0],\"label\":\"Catherby\",\"cost\":\"2,500 coins\"},"
			+ "{\"kind\":\"Charter ship\",\"from\":[2760,3238,0],\"to\":[1455,2968,0],\"label\":\"Aldarin\",\"cost\":\"2,500 coins\"}"
			+ "]}";
		List<Hotspot> spots = HotspotLoader.transports(new StringReader(json), GSON, catalog());
		assertEquals(2, spots.size());
		Hotspot aldarin = spots.get(0);
		assertEquals(2, aldarin.getCard().getRows().size());
		assertEquals("Brimhaven", aldarin.getCard().getRows().get(0).getLabel());
		assertEquals("2,500 coins", aldarin.getCard().getRows().get(0).getValue());
		assertEquals(2, aldarin.getLinks().size());
		assertEquals(2760.5, aldarin.getLinks().get(0).getX(), 1e-9);
	}

	@Test
	public void freeRoutesSayFreeAndQuestsGetTheirOwnRow()
	{
		String json = "{\"routes\":[{\"kind\":\"Boat\",\"from\":[2720,3301,0],\"to\":[2782,3273,0],"
			+ "\"label\":\"Fishing Platform\",\"quests\":[\"Sea Slug\"]}]}";
		Hotspot spot = HotspotLoader.transports(new StringReader(json), GSON, catalog()).get(0);
		assertEquals("free", spot.getCard().getRows().get(0).getValue());
		assertEquals("Sea Slug", spot.getCard().getRows().get(1).getValue());
	}

	@Test
	public void identicalDestinationsAreListedOnce()
	{
		String json = "{\"routes\":["
			+ "{\"kind\":\"Boat\",\"from\":[2720,3301,0],\"to\":[2782,3273,0],\"label\":\"Platform\"},"
			+ "{\"kind\":\"Boat\",\"from\":[2721,3301,0],\"to\":[2782,3274,0],\"label\":\"Platform\"}]}";
		Hotspot spot = HotspotLoader.transports(new StringReader(json), GSON, catalog()).get(0);
		assertEquals(1, spot.getCard().getRows().size());
	}

	@Test
	public void longRouteListsKeepTheRestForShift()
	{
		StringBuilder json = new StringBuilder("{\"routes\":[");
		for (int i = 0; i < 20; i++)
		{
			if (i > 0)
			{
				json.append(',');
			}
			json.append("{\"kind\":\"Charter ship\",\"from\":[1455,2968,0],\"to\":[2760,3238,0],\"label\":\"Port ")
				.append(i).append("\",\"cost\":\"100 coins\"}");
		}
		json.append("]}");
		Hotspot spot = HotspotLoader.transports(new StringReader(json.toString()), GSON, catalog()).get(0);
		com.worldmapplus.layers.HoverCard card = spot.getCard();
		assertEquals(HotspotLoader.MAX_ROWS, card.getRows().size());
		assertEquals("the other six wait for Shift", 6, card.getOverflow().size());
		assertEquals(20, card.visibleRows(true).size());
		assertEquals("and 6 more (hold Shift)", card.visibleRows(false).get(HotspotLoader.MAX_ROWS).getLabel());
		assertEquals("every destination still gets its line", 20, spot.getLinks().size());
	}

	@Test
	public void aNetworkNodeListsTheOthers()
	{
		String json = "{\"networks\":[{\"kind\":\"Gnome glider\",\"nodes\":["
			+ "{\"name\":\"Grand Tree\",\"x\":2465,\"y\":3501,\"plane\":3},"
			+ "{\"name\":\"Karamja\",\"x\":2971,\"y\":2975,\"plane\":0}]}]}";
		List<Hotspot> spots = HotspotLoader.transports(new StringReader(json), GSON, catalog());
		assertEquals(2, spots.size());
		Hotspot grandTree = spots.get(0);
		assertEquals("Gnome glider: Grand Tree", grandTree.getCard().getTitle());
		assertEquals("Karamja", grandTree.getCard().getRows().get(0).getLabel());
		assertEquals(1, grandTree.getLinks().size());
	}

	@Test
	public void aBigNetworkListsTheFirstFewAndKeepsTheRestForShift()
	{
		StringBuilder json = new StringBuilder("{\"networks\":[{\"kind\":\"Fairy ring\",\"nodes\":[");
		for (int i = 0; i < 30; i++)
		{
			if (i > 0)
			{
				json.append(',');
			}
			json.append("{\"name\":\"R").append(i).append("\",\"x\":").append(3000 + i).append(",\"y\":3300,\"plane\":0}");
		}
		json.append("]}]}");
		Hotspot first = HotspotLoader.transports(new StringReader(json.toString()), GSON, catalog()).get(0);
		assertEquals(HotspotLoader.MAX_ROWS, first.getCard().getRows().size());
		assertEquals(29 - HotspotLoader.MAX_ROWS, first.getCard().getOverflow().size());
		assertTrue(first.getCard().getSubtitle().startsWith("Connects to 29 other"));
		assertTrue("too many for lines", first.getLinks().isEmpty());
	}

	@Test
	public void damagedFilesGiveNothing()
	{
		assertTrue(HotspotLoader.entrances(new StringReader("null"), GSON, catalog()).isEmpty());
		assertTrue(HotspotLoader.transports(new StringReader("{}"), GSON, catalog()).isEmpty());
		assertNotNull(HotspotLoader.transports(new StringReader("{\"routes\":[null]}"), GSON, catalog()));
	}

	private static String shopJson(int items)
	{
		StringBuilder json = new StringBuilder("{\"shops\":[{\"name\":\"Bob's Brilliant Axes.\",\"owner\":\"Bob\","
			+ "\"where\":\"Lumbridge\",\"x\":3230,\"y\":3203,\"items\":[");
		for (int i = 0; i < items; i++)
		{
			if (i > 0)
			{
				json.append(',');
			}
			json.append("{\"item\":\"Axe ").append(i).append("\",\"stock\":5,\"price\":").append(1000 + i)
				.append(",\"change\":20}");
		}
		json.append("]}]}");
		return json.toString();
	}

	@Test
	public void aShopCardNamesTheKeeperAndListsStock()
	{
		List<Hotspot> spots = HotspotLoader.shops(new StringReader(shopJson(3)), GSON, catalog());
		assertEquals(1, spots.size());
		Hotspot shop = spots.get(0);
		assertEquals(Hotspot.Kind.SHOP, shop.getKind());
		assertEquals(3230.5, shop.getX(), 1e-9);
		assertEquals("Run by Bob, Lumbridge", shop.getCard().getSubtitle());
		assertEquals(3, shop.getCard().getRows().size());
		assertEquals("1,000 coins, 5 in stock", shop.getCard().getRows().get(0).getValue());
		assertTrue(shop.getCard().getHint().contains("20%"));
	}

	@Test
	public void aLongStockListKeepsTheRestForTheExpandedCard()
	{
		Hotspot shop = HotspotLoader.shops(new StringReader(shopJson(40)), GSON, catalog()).get(0);
		assertEquals(HotspotLoader.MAX_SHOP_ROWS, shop.getCard().getRows().size());
		assertEquals(40 - HotspotLoader.MAX_SHOP_ROWS, shop.getCard().getOverflow().size());
		List<com.worldmapplus.layers.HoverCard.Row> collapsed = shop.getCard().visibleRows(false);
		assertEquals(HotspotLoader.MAX_SHOP_ROWS + 1, collapsed.size());
		assertTrue(collapsed.get(collapsed.size() - 1).getLabel().contains("26 more"));
		assertEquals(40, shop.getCard().visibleRows(true).size());
	}

	@Test
	public void otherCurrenciesAreNamed()
	{
		String json = "{\"shops\":[{\"name\":\"Odd shop\",\"x\":3230,\"y\":3203,\"items\":["
			+ "{\"item\":\"Thing\",\"price\":30,\"currency\":\"Tokkul\"}]}]}";
		Hotspot shop = HotspotLoader.shops(new StringReader(json), GSON, catalog()).get(0);
		assertEquals("30 tokkul", shop.getCard().getRows().get(0).getValue());
		assertEquals(null, shop.getCard().getSubtitle());
	}

	@Test
	public void shopsOffEveryMapAreSkipped()
	{
		String json = "{\"shops\":[{\"name\":\"Nowhere\",\"x\":10,\"y\":10,\"items\":[{\"item\":\"X\",\"price\":1}]}]}";
		assertTrue(HotspotLoader.shops(new StringReader(json), GSON, catalog()).isEmpty());
	}

	private static final String TELEPORTS = "{\"teleports\":["
		+ "{\"name\":\"Varrock Teleport\",\"kind\":\"Spell\",\"group\":\"Standard spellbook\",\"x\":3213,\"y\":3424,"
		+ "\"plane\":0,\"skills\":[\"25 Magic\"],\"items\":\"3 air rune, 1 law rune\",\"snap\":[3212,3424]},"
		+ "{\"name\":\"Varrock tablet\",\"kind\":\"Item\",\"x\":3213,\"y\":3425,\"plane\":0,\"charges\":\"uses charges\","
		+ "\"snap\":[3212,3424]},"
		+ "{\"name\":\"Ancient Teleport\",\"kind\":\"Spell\",\"group\":\"Ancient spellbook\",\"x\":3099,\"y\":3500,"
		+ "\"plane\":0,\"skills\":[\"54 Magic\"],\"quests\":[\"Desert Treasure I\"]},"
		+ "{\"name\":\"Nowhere\",\"kind\":\"Item\",\"x\":1,\"y\":1,\"plane\":0}]}";

	@Test
	public void teleportsToTheSamePointShareOneCard()
	{
		List<Hotspot> spots = HotspotLoader.teleports(new StringReader(TELEPORTS), GSON, catalog());
		Hotspot varrock = null;
		for (Hotspot spot : spots)
		{
			if (spot.getKind() == Hotspot.Kind.TELEPORT && spot.getX() == 3212.5)
			{
				varrock = spot;
			}
		}
		assertNotNull(varrock);
		assertEquals("Teleports here", varrock.getCard().getTitle());
		assertEquals("2 ways to get here", varrock.getCard().getSubtitle());
		assertEquals("Varrock Teleport", varrock.getCard().getRows().get(0).getLabel());
		assertEquals("25 Magic", varrock.getCard().getRows().get(0).getValue());
		assertEquals("   runes", varrock.getCard().getRows().get(1).getLabel());
		assertEquals("Varrock tablet", varrock.getCard().getRows().get(2).getLabel());
		assertEquals("uses charges", varrock.getCard().getRows().get(2).getValue());
		assertEquals("teleport", varrock.getCategory());
	}

	@Test
	public void aTeleportOnAnUpperFloorNamesTheFloorTheUkWay()
	{
		String json = "{\"teleports\":[{\"name\":\"Rooftop\",\"kind\":\"Item\",\"x\":3213,\"y\":3424,\"plane\":2,\"snap\":[3212,3424]},"
			+ "{\"name\":\"Cellar\",\"kind\":\"Item\",\"x\":3213,\"y\":3425,\"plane\":1,\"snap\":[3212,3424]}]}";
		Hotspot spot = HotspotLoader.teleports(new StringReader(json), GSON, catalog()).get(0);
		assertEquals("Rooftop (2nd floor)", spot.getCard().getRows().get(0).getLabel());
		assertEquals("Cellar (1st floor)", spot.getCard().getRows().get(1).getLabel());
	}

	@Test
	public void aTeleportOffTheWikiPointsAlsoGetsAMarkerForTheLayer()
	{
		List<Hotspot> spots = HotspotLoader.teleports(new StringReader(TELEPORTS), GSON, catalog());
		int markers = 0;
		int cards = 0;
		for (Hotspot spot : spots)
		{
			if (spot.getKind() == Hotspot.Kind.POI)
			{
				markers++;
				assertEquals("Ancient Teleport", spot.getCard().getTitle());
			}
			else
			{
				cards++;
			}
		}
		assertEquals("only the unsnapped destination needs a marker", 1, markers);
		assertEquals("a place on no map is skipped", 2, cards);
	}

	@Test
	public void aSpellOnAnotherSpellbookAndItsQuestAreSaid()
	{
		List<Hotspot> spots = HotspotLoader.teleports(new StringReader(TELEPORTS), GSON, catalog());
		for (Hotspot spot : spots)
		{
			if (spot.getKind() == Hotspot.Kind.TELEPORT && spot.getX() == 3099.5)
			{
				assertEquals("Ancient spellbook, 54 Magic", spot.getCard().getRows().get(0).getValue());
				assertEquals("Desert Treasure I", spot.getCard().getRows().get(1).getValue());
				return;
			}
		}
		throw new AssertionError("no card for the ancient teleport");
	}

	@Test
	public void manyWaysToArriveSpillIntoTheExpandedCard()
	{
		StringBuilder json = new StringBuilder("{\"teleports\":[");
		for (int i = 0; i < 20; i++)
		{
			json.append(i > 0 ? "," : "").append("{\"name\":\"Way ").append(i)
				.append("\",\"kind\":\"Item\",\"x\":3213,\"y\":3424,\"plane\":0}");
		}
		json.append("]}");
		Hotspot spot = HotspotLoader.teleports(new StringReader(json.toString()), GSON, catalog()).get(0);
		assertEquals(HotspotLoader.MAX_ROWS, spot.getCard().getRows().size());
		assertEquals(6, spot.getCard().getOverflow().size());
	}

	@Test
	public void theBundledDataLoadsAgainstTheBundledCatalogue()
	{
		MapCatalog bundled = MapCatalog.load(GSON);
		List<Hotspot> all = HotspotLoader.load(GSON, bundled);
		assertTrue("hotspots: " + all.size(), all.size() > 500);
		int shops = 0;
		int doorways = 0;
		int transport = 0;
		for (Hotspot h : all)
		{
			switch (h.getKind())
			{
				case SHOP:
					shops++;
					break;
				case DOORWAY:
					doorways++;
					break;
				default:
					transport++;
			}
		}
		assertTrue("shops " + shops, shops > 300);
		assertTrue("doorways " + doorways, doorways > 200);
		assertTrue("transport " + transport, transport > 100);
	}

	@Test
	public void aOneWayEntryBesideATwoWayOneDoesNotHideIt()
	{
		String json = "{\"links\":["
			+ "{\"name\":\"Hespori Patch\",\"a\":{\"map\":0,\"x\":3019,\"y\":3450},\"b\":null},"
			+ "{\"name\":\"Hespori Patch\",\"a\":{\"map\":0,\"x\":3019,\"y\":3450},"
			+ "\"b\":{\"map\":6,\"x\":3019,\"y\":9850}},"
			+ "{\"name\":\"Somewhere else\",\"a\":{\"map\":0,\"x\":3100,\"y\":3300},\"b\":null}]}";
		List<Hotspot> spots = HotspotLoader.entrances(new StringReader(json), GSON, catalog());
		int atSpot = 0;
		for (Hotspot spot : spots)
		{
			if (spot.getMapId() == 0 && Math.abs(spot.getX() - 3019.5) < 1)
			{
				atSpot++;
				assertNotNull("the one at the spot can be followed", spot.getTarget());
			}
		}
		assertEquals(1, atSpot);
		boolean elsewhereKept = false;
		for (Hotspot spot : spots)
		{
			elsewhereKept |= spot.getCard().getTitle().equals("Somewhere else");
		}
		assertTrue("a lone one way entry is still listed", elsewhereKept);
	}
}
