package com.worldmapplus.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.pins.Pin;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.pins.SavedRoute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/** Saving a share through the existing pins and routes, and making one from them. */
public class ShareImportTest
{
	private static final MapCatalog CATALOG = MapCatalog.load(new Gson());

	private String savedPins;
	private String savedRoutes;
	private PinStore pins;
	private RouteStore routes;
	private ShareImport importer;

	@Before
	public void setUp()
	{
		pins = new PinStore(new PinStore.Storage()
		{
			@Override
			public String read()
			{
				return savedPins;
			}

			@Override
			public void write(String json)
			{
				savedPins = json;
			}
		}, new Gson());
		routes = new RouteStore(new PinStore.Storage()
		{
			@Override
			public String read()
			{
				return savedRoutes;
			}

			@Override
			public void write(String json)
			{
				savedRoutes = json;
			}
		}, new Gson(), pins);
		importer = new ShareImport(pins, routes, CATALOG);
	}

	@Test
	public void aPinIsSavedWithTheRecipientsDefaultsAndNeverTwice()
	{
		Share pin = Share.pin(new SharePoint(1249, 3720, 0));
		assertEquals("Saved 1 place.", importer.savePlaces(pin));
		Pin saved = pins.all().get(0);
		assertEquals("Pin at 1249, 3720", saved.getName());
		assertEquals("pin", saved.getKind());
		assertEquals(0, saved.getColor());
		assertEquals(Pin.DEFAULT_SIZE, saved.getSize());
		assertEquals(1249.5, saved.getX(), 0);
		assertEquals(3720.5, saved.getY(), 0);
		assertEquals(CATALOG.mapFor(1249, 3720).getId(), saved.getMap());
		assertTrue(importer.savePlaces(pin).contains("already in your places"));
		assertEquals(1, pins.all().size());
	}

	@Test
	public void aPlaceOnNoMapOfThisVersionIsNotPutOnAnother()
	{
		assertTrue(importer.savePlaces(Share.pin(new SharePoint(20000, 20000, 0))).contains("not on a map"));
		assertTrue(importer.savePlaces(Share.pin(new SharePoint(3222, 3218, 0, 10128))).contains("not on a map"));
		assertTrue(importer.saveRoute(Share.route(Arrays.asList(new SharePoint(3222, 3218, 0), new SharePoint(20000, 20000, 0)),
			false), "x").contains("Stop 2"));
		assertEquals(0, pins.all().size());
		assertEquals(0, routes.all().size());
	}

	@Test
	public void aRouteKeepsItsOrderAndReusesPlacesTheRecipientHas()
	{
		Pin mine = pins.add("My bank", "shop", 0, 3185.5, 3436.5, 0);
		Share route = Share.route(Arrays.asList(new SharePoint(3185, 3436, 0), new SharePoint(3170, 3410, 0),
			new SharePoint(3185, 3436, 0), new SharePoint(3250, 3420, 0)), true);
		String message = importer.saveRoute(route, "Bank loop");
		assertTrue(message, message.startsWith("Saved the route Bank loop with 4 stops and 2 new places"));
		SavedRoute saved = routes.all().get(0);
		assertTrue(saved.isLoop());
		List<Long> stops = saved.getStops();
		assertEquals(Arrays.asList(mine.getId(), stops.get(1), mine.getId(), stops.get(3)), stops);
		assertEquals(3, pins.all().size());
		assertEquals("My bank", pins.get(mine.getId()).getName());
	}

	@Test
	public void nothingIsSavedWhenTheRouteDoesNotFit()
	{
		for (int i = 0; i < PinStore.MAX_PINS - 1; i++)
		{
			pins.add("p" + i, 0, 3000 + i % 100, 3000 + i / 100);
		}
		Share route = Share.route(Arrays.asList(new SharePoint(3222, 3218, 0), new SharePoint(3223, 3218, 0)), false);
		assertTrue(importer.saveRoute(route, "x").contains("too full"));
		assertEquals(0, routes.all().size());
		assertEquals(PinStore.MAX_PINS - 1, pins.all().size());
	}

	@Test
	public void sharingTakesOnlyTheTileTheFloorAndAMapThatTheTileDoesNotGive()
	{
		Pin surface = pins.add("Shop with a name", "shop", 0, 3222.0, 3218.7, 1);
		pins.restyle(surface.getId(), 0xFF0000, 180, true);
		Pin extra = pins.add("In the Abandoned Mine", "pin", 10000, 3393.5, 9601.5, 0);
		Share share = ShareBuilder.ofIds(Arrays.asList(surface.getId(), extra.getId(), 999L), pins, importer::autoMap);
		assertEquals(Share.places(Arrays.asList(new SharePoint(3222, 3218, 1), new SharePoint(3393, 9601, 0, 10000))), share);
		assertEquals(Share.pin(new SharePoint(3222, 3218, 1)), ShareBuilder.ofIds(Arrays.asList(surface.getId()), pins,
			importer::autoMap));
		SavedRoute route = routes.create("r", Arrays.asList(extra.getId(), surface.getId(), extra.getId()), false);
		Share shared = ShareBuilder.ofRoute(route, pins, importer::autoMap);
		assertEquals(3, shared.getPoints().size());
		assertEquals(new SharePoint(3393, 9601, 0, 10000), shared.getPoints().get(0));
	}

	@Test
	public void savedDataWrittenBeforeThisRoundStillLoadsAndShares() throws Exception
	{
		// A pins and a routes value as round 48 wrote them, with colours, sizes and hidden marks.
		savedPins = "{\"nextId\":3,\"pins\":[{\"id\":1,\"name\":\"Bank\",\"map\":0,\"x\":3185.5,\"y\":3436.5,\"kind\":\"shop\","
			+ "\"created\":5,\"plane\":0,\"color\":3394662,\"size\":140,\"hidden\":false},{\"id\":2,\"name\":\"Inn\",\"map\":0,"
			+ "\"x\":3226.5,\"y\":3394.5,\"kind\":\"pin\",\"created\":6,\"plane\":0,\"color\":0,\"size\":100,\"hidden\":true}]}";
		savedRoutes = "{\"nextId\":2,\"routes\":[{\"id\":1,\"name\":\"Pub run\",\"stops\":[1,2],\"loop\":true}]}";
		setUp();
		List<Pin> all = pins.all();
		assertEquals(2, all.size());
		assertEquals(0x33CC66, pins.get(1).getColor());
		Share route = ShareBuilder.ofRoute(routes.all().get(0), pins, importer::autoMap);
		assertEquals(Share.route(Arrays.asList(new SharePoint(3185, 3436, 0), new SharePoint(3226, 3394, 0)), true), route);
		assertEquals(route, ShareCodec.decode(ShareCodec.encode(route)));
		assertNull(importer.existing(new SharePoint(3185, 3437, 0), 0));
		List<Pin> before = new ArrayList<>(pins.all());
		importer.savePlaces(Share.pin(new SharePoint(3185, 3436, 0)));
		assertEquals("nothing the player had is changed", before, pins.all());
	}
}
