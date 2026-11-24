package com.worldmapplus.tiles.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.TreeMap;
import org.junit.Test;

public class SmallPackTest
{
	private static byte[] filled(int length, int seed)
	{
		byte[] out = new byte[length];
		for (int i = 0; i < length; i++)
		{
			out[i] = (byte) (seed * 31 + i);
		}
		return out;
	}

	private static byte[] pack(TreeMap<Integer, byte[]> files)
	{
		return SmallPack.build(files);
	}

	private static TreeMap<Integer, byte[]> sample()
	{
		TreeMap<Integer, byte[]> files = new TreeMap<>();
		files.put(1, filled(100, 1));
		files.put(5, filled(1, 2));
		files.put(12, filled(3000, 3));
		files.put(10187, filled(77, 4));
		return files;
	}

	@Test
	public void everyFileComesBackByteForByte() throws IOException
	{
		TreeMap<Integer, byte[]> files = sample();
		byte[] bytes = pack(files);
		SmallPack.Index index = SmallPack.readIndex(new ByteArrayInputStream(bytes));
		assertEquals(4, index.size());
		for (java.util.Map.Entry<Integer, byte[]> e : files.entrySet())
		{
			int at = index.find(e.getKey());
			assertArrayEquals(e.getValue(), SmallPack.read(new ByteArrayInputStream(bytes), index.offset(at), index.length(at)));
		}
		assertEquals(-1, index.find(2));
		assertEquals(-1, index.find(0));
		assertEquals(-1, index.find(99999));
	}

	@Test
	public void theLastFileEndsExactlyAtTheEndOfThePack() throws IOException
	{
		byte[] bytes = pack(sample());
		SmallPack.Index index = SmallPack.readIndex(new ByteArrayInputStream(bytes));
		int last = index.size() - 1;
		assertEquals(bytes.length, index.offset(last) + index.length(last));
	}

	@Test
	public void aStreamThatEndsEarlyGivesNull() throws IOException
	{
		byte[] bytes = pack(sample());
		SmallPack.Index index = SmallPack.readIndex(new ByteArrayInputStream(bytes));
		byte[] cut = Arrays.copyOf(bytes, bytes.length - 5);
		int last = index.size() - 1;
		assertNull(SmallPack.read(new ByteArrayInputStream(cut), index.offset(last), index.length(last)));
		assertNull(SmallPack.read(new ByteArrayInputStream(cut), bytes.length + 10, 5));
	}

	@Test
	public void everyShortenedHeaderIsRefused()
	{
		byte[] bytes = pack(sample());
		SmallPack.Index index;
		try
		{
			index = SmallPack.readIndex(new ByteArrayInputStream(bytes));
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
		int header = index.offset(0);
		for (int length = 0; length < header; length++)
		{
			refuse(Arrays.copyOf(bytes, length));
		}
	}

	@Test
	public void wrongHeadersAreRefused()
	{
		byte[] bytes = pack(sample());
		byte[] magic = bytes.clone();
		magic[0] = 'X';
		refuse(magic);
		byte[] version = bytes.clone();
		version[4] = 2;
		refuse(version);
		// Zero files.
		refuse(new byte[] {'W', 'M', 'P', 'K', 1, 0});
		// A count over the limit: 5000.
		refuse(new byte[] {'W', 'M', 'P', 'K', 1, (byte) 0x88, 0x27});
		// Map ids that do not rise: 5 then +0.
		refuse(new byte[] {'W', 'M', 'P', 'K', 1, 2, 5, 1, 0, 1, 0, 0});
		// A length of zero.
		refuse(new byte[] {'W', 'M', 'P', 'K', 1, 1, 5, 0});
		// A length over the limit: 2^21.
		refuse(new byte[] {'W', 'M', 'P', 'K', 1, 1, 5, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01});
		// A number longer than five bytes.
		refuse(new byte[] {'W', 'M', 'P', 'K', 1, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1});
		// A map id over the largest.
		refuse(new byte[] {'W', 'M', 'P', 'K', 1, 1, (byte) 0xFF, (byte) 0xFF, 0x7F, 1});
	}

	@Test
	public void theBuilderRefusesWhatTheReaderWould()
	{
		try
		{
			SmallPack.build(new TreeMap<>());
			fail("empty pack built");
		}
		catch (IllegalArgumentException expected)
		{
			// as it should
		}
		TreeMap<Integer, byte[]> empty = new TreeMap<>();
		empty.put(3, new byte[0]);
		try
		{
			SmallPack.build(empty);
			fail("empty chunk file packed");
		}
		catch (IllegalArgumentException expected)
		{
			// as it should
		}
	}

	@Test
	public void namesOfPackableFilesAreRecognised()
	{
		assertEquals(12, ChunkNames.packedBase("12.wmc"));
		assertEquals(10187, ChunkNames.packedBase("10187.wmc"));
		assertEquals(-1, ChunkNames.packedBase("0/3_5.wmc"));
		assertEquals(-1, ChunkNames.packedBase("12.wmo"));
		assertEquals(-1, ChunkNames.packedBase(".wmc"));
		assertEquals(-1, ChunkNames.packedBase("a1.wmc"));
		assertEquals(-1, ChunkNames.packedBase("99999999.wmc"));
		assertEquals(-1, ChunkNames.packedBase("2000000.wmc"));
	}

	private static void refuse(byte[] bytes)
	{
		try
		{
			SmallPack.readIndex(new ByteArrayInputStream(bytes));
			fail("accepted a damaged header of " + bytes.length + " bytes");
		}
		catch (IOException expected)
		{
			assertEquals(true, expected.getMessage() != null && !expected.getMessage().isEmpty());
		}
	}
}
