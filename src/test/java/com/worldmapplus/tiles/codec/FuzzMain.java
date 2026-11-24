package com.worldmapplus.tiles.codec;

import java.util.List;

/**
 * Runs the damaged input checks in a JVM started with a small heap (see {@link SmallHeapTest}). Exits with 0 when
 * nothing ran out of memory and nothing but CodecException came out.
 */
public final class FuzzMain
{
	private FuzzMain()
	{
	}

	public static void main(String[] args)
	{
		try
		{
			Fuzz fuzz = new Fuzz();
			TileDecoder decoder = new TileDecoder();
			List<byte[]> samples = Fuzz.sampleChunks();
			fuzz.truncations(samples.subList(0, 3), decoder);
			fuzz.bitFlips(samples, 300, 1, decoder);
			fuzz.randomBytes(300, 2, decoder);
			fuzz.randomPayloads(200, 3, decoder);
			System.out.println("ok accepted=" + fuzz.accepted + " rejected=" + fuzz.rejected + " worst=" + fuzz.worstMs + "ms");
		}
		catch (OutOfMemoryError e)
		{
			System.out.println("OUT OF MEMORY");
			System.exit(3);
		}
		catch (Throwable t)
		{
			t.printStackTrace(System.out);
			System.exit(1);
		}
	}
}
