package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * spawns.bin replaced spawns.json. The records were compared once, in full, when the file was made: tools/spawnsbin.py
 * {@code --check} read the old JSON and the new file and found them equal (every name, label, tile and its order, quest and music
 * point), and this test holds the Java reader to the same data. {@code python tools/spawnsbin.py --digest} prints a SHA-256 over every record in a
 * fixed text form; the Java reader's result must give the same one. A JSON copy of the old file (389 KB) is not kept: the digest
 * is the cheaper comparison, and any record that differed in any field would change it.
 */
public class SpawnFormatTest
{
	private static final String DIGEST = "d047c07b170ec08b7cce0d615a82e0f238d4e4b87d52159a2452555a741e9e8f";

	private static byte[] bundledBytes() throws IOException
	{
		try (InputStream in = SpawnFormatTest.class.getResourceAsStream(SpawnFormat.RESOURCE))
		{
			assertNotNull(in);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
			{
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	private static String digest(SpawnLoader.SpawnFile file) throws Exception
	{
		StringBuilder text = new StringBuilder();
		text.append("fetched ").append(file.fetched).append('\n');
		creatures(text, "monsters", file.monsters);
		creatures(text, "npcs", file.npcs);
		creatures(text, "items", file.items);
		points(text, "quests", file.quests);
		points(text, "music", file.music);
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder();
		for (byte b : hash)
		{
			hex.append(String.format("%02x", b & 0xFF));
		}
		return hex.toString();
	}

	private static void creatures(StringBuilder text, String key, List<SpawnLoader.Named> list)
	{
		text.append("list ").append(key).append(' ').append(list.size()).append('\n');
		for (SpawnLoader.Named named : list)
		{
			text.append("name ").append(named.name).append(' ').append(named.groups.size()).append('\n');
			for (SpawnLoader.Group group : named.groups)
			{
				text.append("group ").append(group.where).append(' ');
				for (int i = 0; i < group.pts.length; i++)
				{
					text.append(i == 0 ? "" : ",").append(group.pts[i]);
				}
				text.append('\n');
			}
		}
	}

	private static void points(StringBuilder text, String key, List<SpawnLoader.Point> list)
	{
		text.append("list ").append(key).append(' ').append(list.size()).append('\n');
		for (SpawnLoader.Point point : list)
		{
			text.append("point ").append(point.name).append(' ').append(point.x).append(' ').append(point.y).append('\n');
		}
	}

	@Test
	public void theBundledFileHoldsTheRecordsTheToolPrints() throws Exception
	{
		SpawnLoader.SpawnFile file = SpawnFormat.decode(bundledBytes());
		assertEquals(678, file.monsters.size());
		assertEquals(123, file.npcs.size());
		assertEquals(308, file.items.size());
		assertEquals(160, file.quests.size());
		assertEquals(80, file.music.size());
		assertEquals(DIGEST, digest(file));
	}

	@Test
	public void everyShortenedFileIsRefusedWithAClearError() throws Exception
	{
		byte[] data = bundledBytes();
		// Every length would take a while; every 97th plus the last few covers each kind of field.
		for (int length = 0; length < data.length; length += length < 600 ? 1 : 97)
		{
			refuse(Arrays.copyOf(data, length));
		}
		for (int length = data.length - 8; length < data.length; length++)
		{
			refuse(Arrays.copyOf(data, length));
		}
	}

	@Test
	public void extraBytesAndAWrongHeaderAreRefused() throws Exception
	{
		byte[] data = bundledBytes();
		refuse(Arrays.copyOf(data, data.length + 1));
		byte[] version = data.clone();
		version[3] = 2;
		refuse(version);
		byte[] magic = data.clone();
		magic[0] = 'X';
		refuse(magic);
		refuse(new byte[0]);
	}

	@Test
	public void countsThatCannotBeTrueAreRefusedBeforeAnythingIsMade() throws Exception
	{
		// Header, empty date, no labels, then a monster list claiming 2^30 creatures.
		refuse(bytes('W', 'M', 'S', 1, 0, 0, 0x80, 0x80, 0x80, 0x80, 0x04));
		// A number of more than five bytes.
		refuse(bytes('W', 'M', 'S', 1, 0, 0, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01));
		// One creature with a group that names a label that is not there.
		refuse(bytes('W', 'M', 'S', 1, 0, 0, 1, 1, 'a', 1, 5, 1, 0, 0, 0, 0, 0, 0));
		// One label "x", one creature "a" with one group claiming 3000 tiles in a file that ends there.
		refuse(bytes('W', 'M', 'S', 1, 0, 1, 0, 1, 'x', 1, 1, 'a', 1, 0, 0xB8, 0x17));
		// The same with one tile that steps below zero.
		refuse(bytes('W', 'M', 'S', 1, 0, 1, 0, 1, 'x', 1, 1, 'a', 1, 0, 1, 1, 0, 0, 0, 0, 0));
		// A date whose length is over the limit.
		refuse(bytes('W', 'M', 'S', 1, 0xFF, 0x7F));
	}

	@Test
	public void aSmallFileReadsBack() throws Exception
	{
		// date "d", one label "ab", one monster "c" with one group at label 0 of tiles (10,20) and (9,22), no npcs, items, quests, music.
		SpawnLoader.SpawnFile file = SpawnFormat.decode(bytes('W', 'M', 'S', 1, 1, 'd', 1, 0, 2, 'a', 'b', 1, 1, 'c', 1, 0, 2,
			20, 40, 1, 4, 0, 0, 0, 0));
		assertEquals("d", file.fetched);
		assertEquals("c", file.monsters.get(0).name);
		assertEquals("ab", file.monsters.get(0).groups.get(0).where);
		assertEquals(Arrays.toString(new int[] {10, 20, 9, 22}), Arrays.toString(file.monsters.get(0).groups.get(0).pts));
	}

	private static byte[] bytes(int... values)
	{
		byte[] out = new byte[values.length];
		for (int i = 0; i < values.length; i++)
		{
			out[i] = (byte) values[i];
		}
		return out;
	}

	private static void refuse(byte[] data)
	{
		try
		{
			SpawnFormat.decode(data);
			fail("accepted " + data.length + " bytes");
		}
		catch (SpawnFormat.Damaged expected)
		{
			assertNotNull(expected.getMessage());
		}
	}
}
