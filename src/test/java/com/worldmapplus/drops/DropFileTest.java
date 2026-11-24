package com.worldmapplus.drops;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The drops format: a round trip, the bundled file, and reading damaged input, which must never throw and never grow without bound. */
public class DropFileTest
{
	/** A small file of two monsters with every kind of field. */
	public static DropFileWriter sample()
	{
		DropFileWriter w = new DropFileWriter();
		w.items.add("Bones");
		w.ids.add(526);
		w.items.add("Bronze dagger");
		w.ids.add(1205);
		w.items.add("Coins");
		w.ids.add(995);
		w.items.add("Mystery item");
		w.ids.add(-1);
		w.quantities.add(new int[] {1, 1});
		w.quantities.add(new int[] {5, 15});
		w.quantities.add(new int[] {0, 0});
		w.rarities.add(new DropFileWriter.Rarity(DropTables.ALWAYS, 0, 0));
		w.rarities.add(new DropFileWriter.Rarity(0, 1, 128));
		w.rarities.add(new DropFileWriter.Rarity(16, 100, 546133));
		w.rarities.add(new DropFileWriter.Rarity(DropTables.RARE | 8, 0, 0));
		DropFileWriter.Source goblin = new DropFileWriter.Source("Goblin#Drop table 1", 2, 5);
		goblin.rows.add(new DropFileWriter.Row(0, 0, 0, 0, 100));
		goblin.rows.add(new DropFileWriter.Row(1, 0, 1, 0, 100));
		goblin.rows.add(new DropFileWriter.Row(2, 1, 1, DropTables.NOTED | DropTables.ROLLS, 300));
		w.sources.add(goblin);
		DropFileWriter.Source man = new DropFileWriter.Source("Man", -1, -1);
		man.rows.add(new DropFileWriter.Row(2, 1, 2, DropTables.PICKPOCKET, 100));
		man.rows.add(new DropFileWriter.Row(3, 2, 3, 0, 100));
		w.sources.add(man);
		return w;
	}

	@Test
	public void aFileReadsBackAsItWasWritten()
	{
		DropTables t = DropFile.decode(sample().packed());
		assertEquals("2026-11-05", t.fetched());
		assertEquals(4, t.itemCount());
		assertEquals(2, t.sourceCount());
		assertEquals(5, t.rowCount());
		assertEquals("Bronze dagger", t.itemName(1));
		assertEquals(995, t.itemId(2));
		assertEquals(-1, t.itemId(3));
		assertEquals("Goblin#Drop table 1", t.sourceName(0));
		assertEquals(2, t.combatLow(0));
		assertEquals(5, t.combatHigh(0));
		assertEquals(-1, t.combatLow(1));

		assertEquals("Always", t.rarityText(0));
		assertEquals(1.0, t.chance(0), 0);
		assertEquals("1/128", t.rarityText(1));
		assertEquals(1 / 128.0, t.chance(1), 1e-12);
		assertEquals("5-15 (noted)", t.quantityText(2));
		assertEquals(300, t.rowRolls(2));
		assertEquals("1/5,461.33", t.rarityText(3));
		assertEquals(1 / 5461.33, t.chance(3), 1e-9);
		assertTrue((t.rowFlags(3) & DropTables.PICKPOCKET) != 0);
		assertEquals("~Rare", t.rarityText(4));
		assertEquals(-1, t.chance(4), 0);
		assertEquals("", t.quantityText(4));
	}

	@Test
	public void theInverseListIsWorkedOutFromTheSameRows()
	{
		DropTables t = DropFile.decode(sample().packed());
		int coins = t.item("coins");
		assertEquals(2, coins);
		assertArrayEquals(new int[] {2, 3}, t.rowsOf(coins));
		assertEquals(0, t.rowSource(2));
		assertEquals(1, t.rowSource(3));
		assertEquals(0, t.rowsOf(-1).length);
		assertEquals(0, t.rowsOfName("No such thing").length);
		assertArrayEquals(new int[] {0}, t.sourcesFor("goblin"));
		assertArrayEquals(new int[] {1}, t.sourcesFor("Man"));
		assertEquals(0, t.sourcesFor("Goblin#Drop table 1 x").length);
	}

	@Test
	public void theBundledFileReads()
	{
		DropTables t = DropFile.load();
		assertFalse("drops.bin must be bundled and readable", t.isEmpty());
		assertTrue(t.sourceCount() > 1000);
		assertTrue(t.rowCount() > 20_000);
		assertTrue(t.itemCount() > 1500);
		assertTrue(t.fetched().matches("\\d{4}-\\d\\d-\\d\\d"));
	}

	@Test
	public void emptyAndForeignInputGiveTheEmptyTables()
	{
		assertSame(DropTables.EMPTY, DropFile.decode(null));
		assertSame(DropTables.EMPTY, DropFile.decode(new byte[0]));
		assertSame(DropTables.EMPTY, DropFile.decode("not a drops file".getBytes()));
		assertSame(DropTables.EMPTY, DropFile.decode(DropFileWriter.deflate("WMD2rest".getBytes())));
		assertSame(DropTables.EMPTY, DropFile.decode(new byte[DropFile.MAX_PACKED + 1]));
		// A stream that inflates to far more than any real file is cut off.
		assertSame(DropTables.EMPTY, DropFile.decode(DropFileWriter.deflate(new byte[DropFile.MAX_INFLATED + 10])));
	}

	@Test
	public void countsThatLieAreRefusedBeforeAnythingLargeIsMade()
	{
		DropFileWriter w = sample();
		byte[] raw = w.raw();
		// Bytes after the end.
		byte[] longer = Arrays.copyOf(raw, raw.length + 1);
		assertSame(DropTables.EMPTY, DropFile.decode(DropFileWriter.deflate(longer)));
		// A huge item count right after the date.
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		out.write('W');
		out.write('M');
		out.write('D');
		out.write('1');
		out.write(0);
		DropFileWriter.varint(out, Integer.MAX_VALUE);
		assertSame(DropTables.EMPTY, DropFile.decode(DropFileWriter.deflate(out.toByteArray())));
		// A row whose item is past the dictionary.
		DropFileWriter bad = sample();
		bad.sources.get(1).rows.add(new DropFileWriter.Row(9, 0, 0, 0, 100));
		assertSame(DropTables.EMPTY, DropFile.decode(bad.packed()));
		// A rarity index past the dictionary.
		bad = sample();
		bad.sources.get(1).rows.set(0, new DropFileWriter.Row(2, 0, 7, 0, 100));
		assertSame(DropTables.EMPTY, DropFile.decode(bad.packed()));
		// A fraction with a zero denominator.
		bad = sample();
		bad.rarities.set(1, new DropFileWriter.Rarity(0, 1, 0));
		assertSame(DropTables.EMPTY, DropFile.decode(bad.packed()));
	}

	@Test
	public void everyTruncationAndBitFlipIsReadWithoutThrowing()
	{
		byte[] raw = sample().raw();
		for (int length = 0; length < raw.length; length++)
		{
			DropTables t = DropFile.decode(DropFileWriter.deflate(Arrays.copyOf(raw, length)));
			assertSame("a file cut at " + length + " is refused", DropTables.EMPTY, t);
		}
		for (int i = 0; i < raw.length; i++)
		{
			for (int bit = 0; bit < 8; bit++)
			{
				byte[] flipped = raw.clone();
				flipped[i] ^= (byte) (1 << bit);
				assertNotNull(DropFile.decode(DropFileWriter.deflate(flipped)));
			}
		}
		byte[] packed = sample().packed();
		for (int length = 0; length < packed.length; length++)
		{
			assertNotNull(DropFile.decode(Arrays.copyOf(packed, length)));
		}
	}

	@Test
	public void randomBytesAndDamagedRealDataNeverThrow()
	{
		Random random = new Random(46);
		for (int i = 0; i < 2000; i++)
		{
			byte[] junk = new byte[random.nextInt(400)];
			random.nextBytes(junk);
			assertNotNull(DropFile.decode(junk));
			assertNotNull(DropFile.decode(DropFileWriter.deflate(junk)));
		}
		byte[] real;
		try (java.io.InputStream in = DropFile.class.getResourceAsStream(DropFile.RESOURCE))
		{
			real = in.readAllBytes();
		}
		catch (java.io.IOException e)
		{
			throw new AssertionError(e);
		}
		byte[] inflated = DropFile.inflate(real);
		assertNotNull(inflated);
		for (int i = 0; i < 60; i++)
		{
			byte[] damaged = inflated.clone();
			for (int k = 0; k < 4; k++)
			{
				damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
			}
			assertNotNull(DropFile.decode(DropFileWriter.deflate(damaged)));
			assertNotNull(DropFile.decode(Arrays.copyOf(real, random.nextInt(real.length))));
		}
	}
}
