package com.worldmapplus.drops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A sample of real monsters and items in the bundled tables, and how the tables agree with the bundled spawn data. */
public class DropDataTest
{
	private static DropTables tables;
	private static Set<String> creatures;

	@BeforeClass
	public static void read() throws Exception
	{
		tables = DropFile.load();
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

	/** The row of a table that drops an item, or -1. */
	private static int row(String source, String item)
	{
		for (int s = 0; s < tables.sourceCount(); s++)
		{
			if (tables.sourceName(s).equals(source))
			{
				for (int r = tables.rowsStart(s); r < tables.rowsEnd(s); r++)
				{
					if (tables.itemName(tables.rowItem(r)).equals(item))
					{
						return r;
					}
				}
			}
		}
		return -1;
	}

	@Test
	public void realMonstersHaveTheirKnownDrops()
	{
		int bones = row("Cow", "Cowhide");
		assertTrue("a cow always drops a cowhide", bones >= 0);
		assertEquals("Always", tables.rarityText(bones));

		int whip = -1;
		for (int s : tables.sourcesFor("Abyssal demon"))
		{
			whip = Math.max(whip, row(tables.sourceName(s), "Abyssal whip"));
		}
		assertTrue("an abyssal demon drops the whip", whip >= 0);
		assertEquals(1 / 512.0, tables.chance(whip), 1 / 512.0 * 0.01);
		assertEquals(4151, tables.itemId(tables.rowItem(whip)));

		assertTrue("a goblin has more than one table", tables.sourcesFor("Goblin").length >= 2);
		assertTrue("an abyssal demon's level is known", tables.combatLow(tables.sourcesFor("Abyssal demon")[0]) >= 100);

		int coins = row("Man", "Coins");
		assertTrue(coins >= 0);
		boolean pickpocket = false;
		for (int s : tables.sourcesFor("Man"))
		{
			for (int r = tables.rowsStart(s); r < tables.rowsEnd(s); r++)
			{
				pickpocket |= (tables.rowFlags(r) & DropTables.PICKPOCKET) != 0;
			}
		}
		assertTrue("a man can be pickpocketed", pickpocket);
	}

	@Test
	public void realItemsKnowWhatDropsThem()
	{
		int[] whip = tables.rowsOfName("Abyssal whip");
		assertTrue(whip.length >= 1);
		boolean demon = false;
		for (int r : whip)
		{
			demon |= DropTables.base(tables.sourceName(tables.rowSource(r))).equals("Abyssal demon");
		}
		assertTrue(demon);
		assertTrue("many things drop coins", tables.rowsOfName("Coins").length > 300);
		assertEquals(995, tables.itemId(tables.item("Coins")));
	}

	@Test
	public void theTablesAgreeWithTheBundledSpawns()
	{
		int withTables = 0;
		for (String creature : creatures)
		{
			if (tables.sourcesFor(creature).length > 0)
			{
				withTables++;
			}
		}
		assertTrue("most monsters on the map have a drop table: " + withTables, withTables >= 550);
		int named = 0;
		for (int s = 0; s < tables.sourceCount(); s++)
		{
			String name = tables.sourceName(s);
			assertTrue(name, !name.trim().isEmpty() && !name.startsWith("#"));
			assertTrue(name, tables.rowsEnd(s) > tables.rowsStart(s));
			if (tables.combatLow(s) >= 0)
			{
				assertTrue(name, tables.combatHigh(s) >= tables.combatLow(s));
				named++;
			}
		}
		assertTrue("most tables know a combat level: " + named, named > tables.sourceCount() / 2);
		int withId = 0;
		for (int i = 0; i < tables.itemCount(); i++)
		{
			if (tables.itemId(i) >= 0)
			{
				withId++;
			}
		}
		assertTrue("nearly every item has an id for its price: " + withId, withId >= tables.itemCount() * 98 / 100);
		for (int r = 0; r < tables.rowCount(); r++)
		{
			double chance = tables.chance(r);
			assertTrue(chance == -1 || (chance > 0 && chance <= 1));
			assertTrue(tables.quantityHigh(r) >= tables.quantityLow(r));
		}
	}
}
