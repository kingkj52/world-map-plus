package com.worldmapplus.tiles.codec;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Damaged input for the codec: builders of valid and hand made chunks, and the mutations applied to them. Every run
 * asserts the same three things: the only exception is {@link CodecException} and it is not a wrapped internal
 * failure, no single operation takes long, and nothing runs out of memory. Used by the JUnit tests and, in a child JVM
 * with a small heap, by {@link FuzzMain}.
 */
final class Fuzz
{
	/** The longest one parse and decode of damaged input may take, in milliseconds. */
	static final long LIMIT_MS = 600;

	long worstMs;
	int accepted;
	int rejected;

	/** Valid chunks of different shapes. */
	static List<byte[]> sampleChunks()
	{
		List<byte[]> list = new ArrayList<>();
		TileEncoder enc = new TileEncoder();
		ChunkBuilder a = new ChunkBuilder(64, 64);
		for (int i = 0; i < 12; i++)
		{
			int[] px = i % 4 == 0 ? Synth.terrain(64, 64, i) : i % 4 == 1 ? Synth.noiseWithClear(64, 64, 30, i) : i % 4 == 2
				? Synth.gradient(64, 64) : Synth.flat(64, 64, 0x123456);
			a.add(i < 6 ? 0 : 20000, 100 + i % 6, 200 + i / 6, px);
		}
		a.add(40000, 3, 4, Synth.terrain(64, 64, 0));
		list.add(a.build(enc));
		ChunkBuilder b = new ChunkBuilder(8, 8);
		for (int i = 0; i < 40; i++)
		{
			b.add(10000 + i % 3, i, 7, Synth.noise(8, 8, 1 + i % 9, i));
		}
		list.add(b.build(enc));
		ChunkBuilder c = new ChunkBuilder(37, 23);
		c.add(5, 1, 1, Synth.terrain(37, 23, 1));
		list.add(c.build(enc));
		list.add(ChunkBuilder.imageFile(Synth.terrain(100, 60, 1), 100, 60, enc));
		return list;
	}

	static boolean isImage(byte[] data)
	{
		return data.length > 4 && data[3] == 'I';
	}

	/** Recomputes the checksum at the end, so a damaged file gets past it and the checks behind it are reached. */
	static byte[] fixChecksum(byte[] data)
	{
		if (data.length < 4)
		{
			return data;
		}
		return ChunkBuilder.withChecksum(Arrays.copyOf(data, data.length - 4));
	}

	/** Parses and decodes everything in the file. Returns normally or throws CodecException, nothing else. */
	void exercise(byte[] data, boolean checkSum, TileDecoder decoder)
	{
		long start = System.nanoTime();
		try
		{
			if (isImage(data))
			{
				ImageFile f = ImageFile.parse(data, checkSum);
				int[] whole = new int[f.width() * f.height()];
				CodecException failure = null;
				try
				{
					f.decode(decoder, whole);
				}
				catch (CodecException e)
				{
					failure = e;
				}
				checkInPieces(() -> f.begin(false, decoder), whole, failure == null);
				if (failure != null)
				{
					throw failure;
				}
			}
			else
			{
				ChunkFile f = ChunkFile.parse(data, checkSum);
				int[] out = new int[f.tileWidth() * f.tileHeight()];
				for (int i = 0; i < f.size(); i++)
				{
					f.decode(i, decoder, out);
				}
			}
			accepted++;
		}
		catch (CodecException e)
		{
			if (e.getCause() != null)
			{
				throw new AssertionError("internal failure behind a CodecException", e.getCause());
			}
			rejected++;
		}
		finally
		{
			worstMs = Math.max(worstMs, (System.nanoTime() - start) / 1_000_000);
		}
		if (worstMs > LIMIT_MS)
		{
			throw new AssertionError("took " + worstMs + " ms");
		}
	}

	/** Decodes raw payload bytes of a made up picture size. */
	void exercisePayload(byte[] payload, int w, int h, TileDecoder decoder)
	{
		long start = System.nanoTime();
		try
		{
			int[] whole = new int[TileDecoder.sizeAllowed(w, h) ? w * h : 1];
			CodecException failure = null;
			try
			{
				decoder.decode(payload, 0, payload.length, w, h, whole);
			}
			catch (CodecException e)
			{
				failure = e;
			}
			checkInPieces(() -> decoder.begin(payload, 0, payload.length, w, h, false), whole, failure == null);
			if (failure != null)
			{
				throw failure;
			}
			accepted++;
		}
		catch (CodecException e)
		{
			if (e.getCause() != null)
			{
				throw new AssertionError("internal failure behind a CodecException", e.getCause());
			}
			rejected++;
		}
		worstMs = Math.max(worstMs, (System.nanoTime() - start) / 1_000_000);
		if (worstMs > LIMIT_MS)
		{
			throw new AssertionError("payload decode took " + worstMs + " ms");
		}
	}

	/** Starts a decode in pieces. */
	interface Starter
	{
		TileDecoder.Progress start();
	}

	/**
	 * Decodes the same input in pieces of a few rows and checks it agrees with the whole decode: it finishes with the same pixels
	 * when the whole decode did, and fails, with a plain {@link CodecException}, when the whole decode failed.
	 */
	void checkInPieces(Starter starter, int[] whole, boolean wholeOk)
	{
		try
		{
			TileDecoder.Progress progress = starter.start();
			int guard = 0;
			while (!progress.step(0))
			{
				if (++guard > whole.length + 10)
				{
					throw new AssertionError("a decode in pieces does not move on");
				}
			}
			if (!wholeOk)
			{
				throw new AssertionError("a decode in pieces accepted what the whole decode refused");
			}
			int[] got = TileEncoder.pixels(progress.image());
			for (int i = 0; i < got.length; i++)
			{
				if ((got[i] & 0xFFFFFF) != (whole[i] & 0xFFFFFF))
				{
					throw new AssertionError("a decode in pieces differs at pixel " + i);
				}
			}
		}
		catch (CodecException e)
		{
			if (e.getCause() != null)
			{
				throw new AssertionError("internal failure behind a CodecException in pieces", e.getCause());
			}
			if (wholeOk)
			{
				throw new AssertionError("a decode in pieces refused what the whole decode accepted: " + e.getMessage());
			}
		}
	}

	/** Every prefix of every sample, with and without the checksum check. */
	void truncations(List<byte[]> samples, TileDecoder decoder)
	{
		for (byte[] s : samples)
		{
			for (int len = 0; len < s.length; len++)
			{
				byte[] cut = Arrays.copyOf(s, len);
				try
				{
					if (isImage(s))
					{
						ImageFile.parse(cut);
					}
					else
					{
						ChunkFile.parse(cut);
					}
					throw new AssertionError("a truncated file was accepted at length " + len);
				}
				catch (CodecException expected)
				{
					rejected++;
				}
				exercise(cut, false, decoder);
				exercise(fixChecksum(cut), true, decoder);
			}
		}
	}

	/** Random bit flips, with the checksum kept (must be caught) and with it repaired (must be handled). */
	void bitFlips(List<byte[]> samples, int perSample, long seed, TileDecoder decoder)
	{
		Random r = new Random(seed);
		for (byte[] s : samples)
		{
			for (int k = 0; k < perSample; k++)
			{
				byte[] m = s.clone();
				int flips = 1 + r.nextInt(4);
				for (int f = 0; f < flips; f++)
				{
					m[r.nextInt(m.length - 4)] ^= 1 << r.nextInt(8);
				}
				if (Arrays.equals(m, s))
				{
					continue;
				}
				try
				{
					if (isImage(s))
					{
						ImageFile.parse(m);
					}
					else
					{
						ChunkFile.parse(m);
					}
					throw new AssertionError("a flipped bit got past the checksum");
				}
				catch (CodecException expected)
				{
					rejected++;
				}
				exercise(fixChecksum(m), true, decoder);
			}
		}
	}

	/** Noise of every kind: random files, random payloads, random sizes. */
	void randomBytes(int count, long seed, TileDecoder decoder)
	{
		Random r = new Random(seed);
		for (int i = 0; i < count; i++)
		{
			byte[] d = new byte[r.nextInt(3000)];
			r.nextBytes(d);
			exercise(d, false, decoder);
			if (d.length >= 16)
			{
				exercise(fixChecksum(d), true, decoder);
			}
			// a plausible header in front of noise
			byte[] hd = new byte[16 + r.nextInt(500)];
			r.nextBytes(hd);
			System.arraycopy(ChunkFile.MAGIC, 0, hd, 0, 4);
			hd[4] = ChunkFile.VERSION;
			hd[5] = 0;
			exercise(fixChecksum(hd), true, decoder);
		}
	}

	/** Random payloads of random pictures, some with a valid header, some not. */
	void randomPayloads(int count, long seed, TileDecoder decoder)
	{
		Random r = new Random(seed);
		int[][] sizes = {{64, 64}, {256, 256}, {37, 23}, {1, 1}, {100, 3}, {4096, 1}, {1, 4096}, {8, 8}};
		for (int i = 0; i < count; i++)
		{
			int[] size = sizes[r.nextInt(sizes.length)];
			byte[] d = new byte[3 + r.nextInt(size[0] * size[1] > 10000 ? 20000 : 600)];
			r.nextBytes(d);
			if (r.nextInt(4) != 0)
			{
				d[1] &= 0x1F;
			}
			exercisePayload(d, size[0], size[1], decoder);
			// huge or silly sizes must be refused before anything is allocated
			exercisePayload(d, r.nextInt(100000) - 10, r.nextInt(100000) - 10, decoder);
		}
	}

	// ---- hand made chunks ----

	/** A chunk written field by field, so any field can be made to lie. */
	static final class Crafted
	{
		final ByteArrayOutputStream o = new ByteArrayOutputStream();

		Crafted header(int width, int height)
		{
			o.write(ChunkFile.MAGIC, 0, 4);
			o.write(ChunkFile.VERSION);
			o.write(0);
			o.write(width >> 8);
			o.write(width);
			o.write(height >> 8);
			o.write(height);
			return this;
		}

		Crafted varint(long v)
		{
			while (v >= 128)
			{
				o.write((int) ((v & 127) | 128));
				v >>>= 7;
			}
			o.write((int) v);
			return this;
		}

		Crafted raw(int... bytes)
		{
			for (int b : bytes)
			{
				o.write(b);
			}
			return this;
		}

		Crafted bytes(byte[] b)
		{
			o.write(b, 0, b.length);
			return this;
		}

		/** count, one map id, origin 0,0. */
		Crafted directory(long count, long mapId)
		{
			return varint(count).varint(1).varint(mapId).varint(0).varint(0);
		}

		Crafted entry(int mapIndex, long dx, long dy, long length)
		{
			return raw(mapIndex).varint(dx).varint(dy).varint(length);
		}

		byte[] done()
		{
			return ChunkBuilder.withChecksum(o.toByteArray());
		}
	}
}
