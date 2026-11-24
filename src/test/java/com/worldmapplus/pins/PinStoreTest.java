package com.worldmapplus.pins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;

public class PinStoreTest
{
	private static final Gson GSON = new Gson();

	/** Storage in memory, which also records how often it was written. */
	private static final class Memory implements PinStore.Storage
	{
		private String value;
		private int writes;

		@Override
		public String read()
		{
			return value;
		}

		@Override
		public void write(String json)
		{
			value = json;
			writes++;
		}
	}

	@Test
	public void startsEmpty()
	{
		assertTrue(new PinStore(new Memory(), GSON).all().isEmpty());
	}

	@Test
	public void addingSavesAndSurvivesAReload()
	{
		Memory memory = new Memory();
		PinStore store = new PinStore(memory, GSON);
		Pin pin = store.add("Fishing spot", 0, 3243.5, 3153.5);
		assertNotNull(pin);
		assertEquals(1, memory.writes);

		PinStore reloaded = new PinStore(memory, GSON);
		List<Pin> pins = reloaded.all();
		assertEquals(1, pins.size());
		assertEquals("Fishing spot", pins.get(0).getName());
		assertEquals(3243.5, pins.get(0).getX(), 1e-9);
	}

	@Test
	public void aSavedPlaceKeepsItsKindAndWhenItWasSaved()
	{
		Memory memory = new Memory();
		PinStore store = new PinStore(memory, GSON);
		Pin shop = store.add("Bob's Brilliant Axes", "Shop", 0, 3230.5, 3203.5);
		assertEquals("shop", shop.getKind());
		assertTrue(shop.getCreated() > 0);
		Pin plain = store.add("A pin", 0, 1, 2);
		assertEquals("pin", plain.getKind());
		Pin reloaded = new PinStore(memory, GSON).get(shop.getId());
		assertEquals("shop", reloaded.getKind());
		assertEquals(shop.getCreated(), reloaded.getCreated());
	}

	@Test
	public void pinsSavedBeforeKindsExistedAreJustPins()
	{
		Memory memory = new Memory();
		memory.value = "{\"pins\":[{\"id\":3,\"name\":\"Old\",\"map\":0,\"x\":1.5,\"y\":2.5}]}";
		Pin old = new PinStore(memory, GSON).all().get(0);
		assertEquals("pin", old.getKind());
		assertEquals(0, old.getCreated());
	}

	@Test
	public void renamingKeepsEverythingElseAndSaves()
	{
		Memory memory = new Memory();
		PinStore store = new PinStore(memory, GSON);
		Pin pin = store.add("Before", "npc", 6, 10.5, 20.5);
		int writes = memory.writes;
		assertTrue(store.rename(pin.getId(), "  After  the   rename "));
		Pin after = store.get(pin.getId());
		assertEquals("After the rename", after.getName());
		assertEquals("npc", after.getKind());
		assertEquals(6, after.getMap());
		assertEquals(10.5, after.getX(), 1e-9);
		assertEquals(writes + 1, memory.writes);
		assertFalse(store.rename(999, "nobody"));
	}

	@Test
	public void namesCannotCarryMarkup()
	{
		PinStore store = new PinStore(new Memory(), GSON);
		Pin pin = store.add("<html><b>Bank</b>", 0, 1, 2);
		assertEquals("htmlbBank/b", pin.getName());
		assertTrue(store.rename(pin.getId(), "<>"));
		assertEquals("Pin", store.get(pin.getId()).getName());
	}

	@Test
	public void anIdIsNeverGivenOutTwice()
	{
		Memory memory = new Memory();
		PinStore store = new PinStore(memory, GSON);
		Pin first = store.add("One", 0, 1, 1);
		Pin second = store.add("Two", 0, 2, 2);
		store.remove(second.getId());
		Pin third = store.add("Three", 0, 3, 3);
		assertTrue(third.getId() > second.getId());
		store.remove(third.getId());
		PinStore again = new PinStore(memory, GSON);
		assertTrue("not even after a restart", again.add("Four", 0, 4, 4).getId() > third.getId());
		assertEquals(first.getId(), again.all().get(0).getId());
	}

	@Test
	public void badSavedPlacesAreLeftOut()
	{
		Memory memory = new Memory();
		memory.value = "{\"pins\":[{\"id\":0,\"name\":\"zero\",\"x\":1,\"y\":1},"
			+ "{\"id\":5,\"name\":\"ok\",\"x\":1,\"y\":1},"
			+ "{\"id\":5,\"name\":\"again\",\"x\":2,\"y\":2},"
			+ "{\"id\":6,\"name\":\"far\",\"x\":1e12,\"y\":1},"
			+ "{\"id\":7,\"name\":\"inf\",\"x\":1,\"y\":1e999}]}";
		List<Pin> read = new PinStore(memory, GSON).all();
		assertEquals(1, read.size());
		assertEquals("ok", read.get(0).getName());
	}

	@Test
	public void aChangeMadeBySomeoneElseIsTakenInButOurOwnWritesAreNot()
	{
		Memory memory = new Memory();
		PinStore store = new PinStore(memory, GSON);
		store.add("Mine", 0, 1, 1);
		int version = store.getVersion();
		assertFalse("our own write is not a change", store.reloadIfChanged());
		assertEquals(version, store.getVersion());

		memory.value = "{\"pins\":[{\"id\":9,\"name\":\"Other profile\",\"x\":5,\"y\":5}]}";
		assertTrue(store.reloadIfChanged());
		assertEquals(1, store.all().size());
		assertEquals("Other profile", store.all().get(0).getName());
		assertTrue(store.getVersion() > version);
		assertTrue("ids carry on above the new data", store.add("Next", 0, 1, 1).getId() > 9);

		memory.value = null;
		assertTrue("a profile with nothing saved empties the list", store.reloadIfChanged());
		assertTrue(store.all().isEmpty());
	}

	@Test
	public void reloadingBeforeAnythingWasReadDoesNothing()
	{
		Memory memory = new Memory();
		memory.value = "{\"pins\":[]}";
		assertFalse(new PinStore(memory, GSON).reloadIfChanged());
	}

	@Test
	public void listenersHearOfRemovals()
	{
		PinStore store = new PinStore(new Memory(), GSON);
		Pin pin = store.add("X", 0, 1, 2);
		List<Long> heard = new java.util.ArrayList<>();
		store.onRemoved(heard::add);
		store.remove(pin.getId());
		store.remove(pin.getId());
		assertEquals(java.util.Arrays.asList(pin.getId()), heard);
	}

	@Test
	public void idsAreUnique()
	{
		PinStore store = new PinStore(new Memory(), GSON);
		Pin a = store.add("a", 0, 1, 1);
		Pin b = store.add("b", 0, 2, 2);
		store.remove(a.getId());
		Pin c = store.add("c", 0, 3, 3);
		assertTrue(b.getId() != c.getId());
		assertTrue(a.getId() != b.getId());
	}

	@Test
	public void removingWorksOnlyForKnownIds()
	{
		PinStore store = new PinStore(new Memory(), GSON);
		Pin a = store.add("a", 0, 1, 1);
		assertFalse(store.remove(999));
		assertTrue(store.remove(a.getId()));
		assertTrue(store.all().isEmpty());
	}

	@Test
	public void nearestOnlyConsidersTheSameMapAndRadius()
	{
		PinStore store = new PinStore(new Memory(), GSON);
		Pin surface = store.add("surface", 0, 100, 100);
		store.add("mine", 6, 100, 100);
		assertEquals(surface.getId(), store.nearest(0, 101, 100, 3).getId());
		assertNull(store.nearest(0, 110, 100, 3));
		assertEquals("mine", store.nearest(6, 100, 100, 3).getName());
		assertNull(store.nearest(99, 100, 100, 3));
	}

	@Test
	public void namesAreCleaned()
	{
		assertEquals("Pin", PinStore.clean("   "));
		assertEquals("Pin", PinStore.clean(null));
		assertEquals("a b c", PinStore.clean("  a   b \t c  "));
		StringBuilder long65 = new StringBuilder();
		for (int i = 0; i < 100; i++)
		{
			long65.append('z');
		}
		assertEquals(PinStore.MAX_NAME, PinStore.clean(long65.toString()).length());
	}

	@Test
	public void theListIsCapped()
	{
		PinStore store = new PinStore(new Memory(), GSON);
		for (int i = 0; i < PinStore.MAX_PINS; i++)
		{
			assertNotNull(store.add("p" + i, 0, i, i));
		}
		assertNull(store.add("one too many", 0, 1, 1));
		assertEquals(PinStore.MAX_PINS, store.all().size());
	}

	@Test
	public void damagedSavedValuesGiveAnEmptyList()
	{
		for (String junk : new String[] {"", "null", "{", "[]", "{\"pins\":5}", "{\"pins\":[null,{\"x\":\"NaN\"}]}"})
		{
			Memory memory = new Memory();
			memory.value = junk;
			assertTrue("for " + junk, new PinStore(memory, GSON).all().size() <= 1);
		}
	}

	@Test
	public void theVersionAdvancesOnChange()
	{
		PinStore store = new PinStore(new Memory(), GSON);
		int start = store.getVersion();
		Pin pin = store.add("a", 0, 1, 1);
		assertTrue(store.getVersion() > start);
		int mid = store.getVersion();
		store.remove(12345);
		assertEquals(mid, store.getVersion());
		store.remove(pin.getId());
		assertTrue(store.getVersion() > mid);
	}

	@Test
	public void anOversizedSavedValueIsIgnored()
	{
		Memory memory = new Memory();
		StringBuilder big = new StringBuilder("{\"pins\":[");
		for (int i = 1; i < 30000; i++)
		{
			big.append(i > 1 ? "," : "").append("{\"id\":").append(i).append(",\"name\":\"padding padding padding\",\"x\":1,\"y\":1}");
		}
		memory.value = big.append("]}").toString();
		assertTrue(memory.value.length() > PinStore.MAX_SAVED_CHARS);
		assertTrue(new PinStore(memory, GSON).all().isEmpty());
	}

	@Test
	public void aHandEditedNextIdCannotMakeIdsWrapRound()
	{
		Memory memory = new Memory();
		memory.value = "{\"nextId\":9223372036854775807,\"pins\":[{\"id\":4,\"name\":\"A\",\"x\":1,\"y\":1}]}";
		PinStore store = new PinStore(memory, GSON);
		Pin added = store.add("B", 0, 2, 2);
		assertTrue(added.getId() > 4);
		assertTrue(store.get(added.getId()) != null);
		assertEquals(2, new PinStore(memory, GSON).all().size());
	}

	@Test
	public void aPinCanBeRecolouredResizedAndHiddenAndKeepsItAfterAReload()
	{
		Memory memory = new Memory();
		PinStore store = new PinStore(memory, GSON);
		Pin pin = store.add("Bank", "shop", 0, 10.5, 20.5);
		assertEquals(0, pin.getColor());
		assertEquals(Pin.DEFAULT_SIZE, pin.getSize());
		assertFalse(pin.isHidden());

		assertTrue(store.restyle(pin.getId(), 0x33CC66, 180, true));
		Pin reloaded = new PinStore(memory, GSON).get(pin.getId());
		assertEquals(0x33CC66, reloaded.getColor());
		assertEquals(180, reloaded.getSize());
		assertTrue(reloaded.isHidden());
		assertEquals("Bank", reloaded.getName());

		assertTrue(store.rename(pin.getId(), "Grand Exchange"));
		assertEquals(0x33CC66, store.get(pin.getId()).getColor());
		assertEquals(180, store.get(pin.getId()).getSize());
		assertFalse(store.restyle(-1, 0, 100, false));
	}

	@Test
	public void sizesAndColoursFromAFileAreKeptInRange()
	{
		Memory memory = new Memory();
		memory.value = "{\"pins\":[{\"id\":3,\"name\":\"Big\",\"map\":0,\"x\":1.5,\"y\":2.5,\"size\":9000,\"color\":-1},"
			+ "{\"id\":4,\"name\":\"Tiny\",\"map\":0,\"x\":1.5,\"y\":2.5,\"size\":3}]}";
		PinStore store = new PinStore(memory, GSON);
		assertEquals(Pin.MAX_SIZE, store.get(3).getSize());
		assertEquals(0xFFFFFF, store.get(3).getColor());
		assertEquals(Pin.MIN_SIZE, store.get(4).getSize());
		assertEquals(Pin.DEFAULT_SIZE, new Pin().getSize());
	}
}
