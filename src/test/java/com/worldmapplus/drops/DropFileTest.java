package com.worldmapplus.drops;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * The drop tabs file {@code drops.json}: the small sample the tests use, the bundled file, and damaged files, which give the empty
 * tables and never throw. The sample is {@code src/test/resources/drops-sample.json}, written by {@code python tools/build_drops.py
 * --sample} (tools/test_build_drops.py checks it is current); what the words in it say is tested there.
 */
public class DropFileTest
{
	private static final Gson GSON = new Gson();

	static String sampleText()
	{
		try (InputStream in = DropFileTest.class.getResourceAsStream("/drops-sample.json"))
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** Goblin (bones always, a bronze dagger, noted coins rolled three times) and Man (pickpocketed coins, a rarity in words). */
	public static DropTables sample()
	{
		return DropTables.checked(GSON.fromJson(sampleText(), DropTables.class));
	}

	private static DropTables changed(String field, Object value)
	{
		JsonObject json = GSON.fromJson(sampleText(), JsonObject.class);
		json.add(field, GSON.toJsonTree(value));
		return DropTables.checked(GSON.fromJson(json, DropTables.class));
	}

	@Test
	public void theSampleReadsAsItWasWritten()
	{
		DropTables t = sample();
		assertEquals(2, t.tabCount());
		DropTables.Tab goblin = t.drops(" Goblin ");
		assertEquals("Combat 2-5. Drops 3 items", goblin.getSummary());
		assertTrue(goblin.isTotal());
		assertArrayEquals(new int[] {0, 1, 2}, goblin.getRows());
		assertEquals("Bones", t.label(0));
		assertEquals("Always", t.rarity(0));
		assertEquals("Always, 1", t.text(0));
		assertNull(t.base(0));
		assertEquals(526, t.itemId(0));
		assertEquals(1.0, t.order(0), 0);
		assertEquals("Rare, 5-15 (noted), 3 rolls", t.text(2));
		assertEquals(1 / 128.0 * 3 * 10, t.perKill(2), 1e-12);
		assertTrue(t.drops("man").isTotal());
		assertEquals("Drops 2 items", t.drops("Man").getSummary());
		assertEquals("~Rare", t.rarity(4));
		assertEquals(-1, t.itemId(4));
		assertEquals(-1, t.perKill(4), 0);
		assertEquals(1 / 256.0, t.order(4), 0);
		assertNull(t.drops("Goblin#Drop table 1 x"));
	}

	@Test
	public void droppedByRowsNameTheirMonster()
	{
		DropTables t = sample();
		int[] coins = t.droppedBy(" Coins ").getRows();
		assertEquals(2, coins.length);
		assertEquals("Goblin (Drop table 1)", t.label(coins[0]));
		assertEquals("Goblin", t.base(coins[0]));
		assertEquals("Combat 2-5, 5-15 (noted), 3 rolls", t.text(coins[0]));
		assertEquals("Man", t.label(coins[1]));
		assertEquals("5-15, pickpocket", t.text(coins[1]));
		assertNull(t.droppedBy("No such thing"));
	}

	@Test
	public void theBundledFileReads()
	{
		DropTables t = DropTables.load(GSON);
		assertFalse("drops.json must be bundled and readable", t.isEmpty());
		assertTrue(t.tabCount() > 1000);
		assertTrue(t.droppedBy("Coins").getRows().length > 300);
	}

	@Test
	public void aDamagedFileGivesTheEmptyTables()
	{
		assertSame(DropTables.EMPTY, DropTables.checked(null));
		assertTrue("a file with no rows has no rows", DropTables.checked(GSON.fromJson("{}", DropTables.class)).isEmpty());
		assertSame("a word past the list", DropTables.EMPTY, changed("text", new int[] {2, 5, 7, 9, 12, 14, 14, 16, 18, 99}));
		assertSame("a negative word", DropTables.EMPTY, changed("label", new int[] {0, 3, 6, 6, 10, 13, 13, 13, 17, -1}));
		assertSame("a list one short", DropTables.EMPTY, changed("id", new int[] {526}));
		assertSame("a list that is not there", DropTables.EMPTY, changed("order", null));
		assertSame("a tab with a row past the rows", DropTables.EMPTY,
			changed("drops", GSON.fromJson("{\"goblin\":{\"summary\":\"\",\"rows\":[10]}}", JsonObject.class)));
		assertSame("a Dropped by row without its monster", DropTables.EMPTY,
			changed("droppedBy", GSON.fromJson("{\"bones\":{\"rows\":[0]}}", JsonObject.class)));
		JsonArray words = GSON.fromJson(sampleText(), JsonObject.class).getAsJsonArray("words");
		words.set(3, com.google.gson.JsonNull.INSTANCE);
		assertSame("a word that is not there", DropTables.EMPTY, changed("words", words));
		words.set(3, new JsonPrimitive("Bronze dagger"));
		assertFalse("the sample itself is fine", changed("words", words).isEmpty());
	}
}
