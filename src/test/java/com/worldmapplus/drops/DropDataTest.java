package com.worldmapplus.drops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

/** A sample of real monsters and items in the bundled tabs, and how the tabs agree with the bundled spawn data. */
public class DropDataTest
{
	private static DropTables tables;
	private static Set<String> creatures;

	@BeforeClass
	public static void read() throws Exception
	{
		tables = DropTables.load(new com.google.gson.Gson());
		creatures = new HashSet<>();
		JsonObject root = com.worldmapplus.data.SpawnTestData.json();
		for (String list : new String[] {"monsters", "npcs"})
		{
			JsonArray array = root.getAsJsonArray(list);
			for (JsonElement e : array)
			{
				creatures.add(e.getAsJsonObject().get("name").getAsString().toLowerCase(Locale.ROOT));
			}
		}
	}

	/** The row of a monster's Drops tab for an item, or -1. */
	private static int row(String creature, String item)
	{
		for (int r : tables.drops(creature).getRows())
		{
			if (tables.label(r).equals(item))
			{
				return r;
			}
		}
		return -1;
	}

	@Test
	public void realMonstersHaveTheirKnownDrops()
	{
		int hide = row("Cow", "Cowhide");
		assertTrue("a cow always drops a cowhide", hide >= 0);
		assertEquals("Always", tables.rarity(hide));

		int whip = row("Abyssal demon", "Abyssal whip");
		assertTrue("an abyssal demon drops the whip", whip >= 0);
		assertEquals(1 / 512.0, tables.order(whip), 1 / 512.0 * 0.01);
		assertEquals(4151, tables.itemId(whip));

		assertTrue("a goblin has more than one table", tables.drops("Goblin").getSummary().contains(" tables"));
		assertTrue("an abyssal demon's level is known", tables.drops("Abyssal demon").getSummary().startsWith("Combat 1"));

		boolean pickpocket = false;
		for (int r : tables.drops("Man").getRows())
		{
			pickpocket |= tables.text(r).contains("pickpocket");
		}
		assertTrue("a man can be pickpocketed", pickpocket);
	}

	@Test
	public void realItemsKnowWhatDropsThem()
	{
		boolean demon = false;
		for (int r : tables.droppedBy("Abyssal whip").getRows())
		{
			demon |= tables.base(r).equals("Abyssal demon");
		}
		assertTrue(demon);
		int[] coins = tables.droppedBy("Coins").getRows();
		assertTrue("many things drop coins", coins.length > 300);
		assertEquals(995, tables.itemId(coins[0]));
	}

	@Test
	public void theTabsAgreeWithTheBundledSpawns()
	{
		int withTabs = 0;
		int withLevel = 0;
		for (String creature : creatures)
		{
			DropTables.Tab tab = tables.drops(creature);
			if (tab != null)
			{
				withTabs++;
				withLevel += tab.getSummary().startsWith("Combat ") ? 1 : 0;
				assertTrue(creature, tab.getRows().length > 0);
			}
		}
		assertTrue("most monsters on the map have a drop table: " + withTabs, withTabs >= 550);
		assertTrue("most know a combat level: " + withLevel, withLevel > withTabs / 2);
		int rows = 0;
		int withId = 0;
		for (String creature : creatures)
		{
			DropTables.Tab tab = tables.drops(creature);
			for (int r : tab == null ? new int[0] : tab.getRows())
			{
				rows++;
				withId += tables.itemId(r) >= 0 ? 1 : 0;
				assertNotNull(tables.label(r));
				assertTrue(tables.order(r) == -1 || (tables.order(r) > 0 && tables.order(r) <= 1));
				assertTrue(tables.perKill(r) == -1 || tables.perKill(r) > 0);
			}
		}
		assertTrue("nearly every row has an item id for its price: " + withId, withId >= rows * 97 / 100);
	}
}
