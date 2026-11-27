package com.worldmapplus.pins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Round 76: pins and routes as the code before round 76 saved them ({@code golden/saved-pins.json} and {@code saved-routes.json}, written
 * by that code: a removed pin and a removed route, a restyled and hidden pin, a pin on another map and floor, a cleaned name, an empty
 * route, a loop through a place twice) load with every field, and a store that saves them again writes the same text.
 */
public class SavedDataTest
{
	private static final Gson GSON = new Gson();

	private static PinStore.Storage holding(String name) throws IOException
	{
		PinStore.Storage storage = PinStore.Storage.inMemory();
		storage.write(new String(Files.readAllBytes(Paths.get("src", "test", "resources", "golden", name)), StandardCharsets.UTF_8).trim());
		return storage;
	}

	@Test
	public void pinsSavedBeforeLoadWithEveryField() throws IOException
	{
		PinStore pins = new PinStore(holding("saved-pins.json"), GSON);
		List<Pin> all = pins.all();
		assertEquals(4, all.size());
		check(all.get(0), 1, "Bank by the pond", 0, 3208.5, 3220.5, "shop", 1795734743776L, 0, 0, 100, false);
		check(all.get(1), 3, "Up top", 0, 3205.25, 3209.75, "pin", 1795734743790L, 2, 0x33AAFF, 140, true);
		check(all.get(2), 4, "Far away place", 12, 1234.5, 5678.5, "teleport", 1795734743790L, 1, 0, 100, false);
		check(all.get(3), 5, "Cellar", 0, 3210.5, 9615.5, "npc", 1795734743791L, 0, 0, 60, false);
		assertEquals("ids carry on after the highest saved", 6, pins.add("New", 0, 1, 1).getId());
	}

	private static void check(Pin pin, long id, String name, int map, double x, double y, String kind, long created, int plane, int color,
		int size, boolean hidden)
	{
		assertEquals(id, pin.getId());
		assertEquals(name, pin.getName());
		assertEquals(map, pin.getMap());
		assertEquals(x, pin.getX(), 0);
		assertEquals(y, pin.getY(), 0);
		assertEquals(kind, pin.getKind());
		assertEquals(created, pin.getCreated());
		assertEquals(plane, pin.getPlane());
		assertEquals(color, pin.getColor());
		assertEquals(size, pin.getSize());
		assertEquals(hidden, pin.isHidden());
	}

	@Test
	public void routesSavedBeforeLoadWithEveryField() throws IOException
	{
		PinStore pins = new PinStore(holding("saved-pins.json"), GSON);
		RouteStore routes = new RouteStore(holding("saved-routes.json"), GSON, pins);
		List<SavedRoute> all = routes.all();
		assertEquals(3, all.size());
		assertEquals(1, all.get(0).getId());
		assertEquals("Herb run", all.get(0).getName());
		assertEquals(Arrays.asList(1L, 3L, 4L, 1L), all.get(0).getStops());
		assertTrue(all.get(0).isLoop());
		assertEquals("Empty", all.get(1).getName());
		assertTrue(all.get(1).getStops().isEmpty());
		assertFalse(all.get(1).isLoop());
		assertEquals("Line x", all.get(2).getName());
		assertEquals(Arrays.asList(5L, 4L), all.get(2).getStops());
		SavedRoute next = routes.create("Next", Arrays.asList(1L), false);
		assertNotNull(next);
		assertEquals("the saved next id is kept, past a removed route", 5, next.getId());
	}

	@Test
	public void savingThemAgainWritesTheSameText() throws IOException
	{
		PinStore.Storage pinText = holding("saved-pins.json");
		String pinsBefore = pinText.read();
		PinStore pins = new PinStore(pinText, GSON);
		assertTrue(pins.rename(3, "Up top"));
		assertEquals(pinsBefore, pinText.read());
		assertFalse("its own write is not a change", pins.reloadIfChanged());

		PinStore.Storage routeText = holding("saved-routes.json");
		String routesBefore = routeText.read();
		RouteStore routes = new RouteStore(routeText, GSON, pins);
		SavedRoute line = routes.get(3);
		assertTrue(routes.update(3, line.getName(), line.getStops(), line.isLoop()));
		assertEquals(routesBefore, routeText.read());
		assertFalse(routes.reloadIfChanged());
	}
}
