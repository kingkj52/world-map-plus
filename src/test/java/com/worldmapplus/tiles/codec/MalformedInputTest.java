package com.worldmapplus.tiles.codec;

import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Damaged and hostile input. Whatever is fed in, the only thing that may come out is a {@link CodecException} that is
 * not a wrapped internal failure, quickly, and without exhausting memory.
 */
public class MalformedInputTest
{
	private static List<byte[]> samples;
	private final TileDecoder decoder = new TileDecoder();
	private final Fuzz fuzz = new Fuzz();

	@BeforeClass
	public static void build()
	{
		samples = Fuzz.sampleChunks();
	}

	@Test
	public void theSampleFilesAreValid()
	{
		TileDecoder d = new TileDecoder();
		Fuzz f = new Fuzz();
		for (byte[] s : samples)
		{
			f.exercise(s, true, d);
		}
		assertTrue("all samples decode", f.accepted == samples.size() && f.rejected == 0);
	}

	@Test
	public void truncationAtEveryLength()
	{
		fuzz.truncations(samples, decoder);
		assertTrue(fuzz.rejected > 1000);
	}

	@Test
	public void bitFlips()
	{
		fuzz.bitFlips(samples, 400, 11, decoder);
		assertTrue(fuzz.rejected > 1000);
	}

	@Test
	public void randomFilesAndPayloads()
	{
		fuzz.randomBytes(300, 5, decoder);
		fuzz.randomPayloads(300, 6, decoder);
		assertTrue(fuzz.rejected > 0);
	}

	@Test
	public void noiseBehindAValidHeaderStillDecodesToSomethingWithoutHarm()
	{
		// A payload of noise is a legal stream of decisions: it decodes to some picture, or is cut short. Both are fine.
		fuzz.randomPayloads(200, 77, decoder);
		assertTrue(fuzz.accepted > 0);
	}

	/**
	 * A few hundred bytes of header can claim a picture of millions of pixels, and a decode is work per pixel. The sizes that the
	 * decoder alone would take but a file may not declare are refused when the header is read, before a payload is looked at.
	 */
	@Test
	public void aForgedHeaderCannotAskForAHugePictureThroughEitherFile()
	{
		long start = System.nanoTime();
		int refused = 0;
		for (int[] size : new int[][] {{4096, 1024}, {1024, 4096}, {2048, 2048}, {513, 512}, {1024, 1024}, {4096, 1}})
		{
			byte[] chunk = new Fuzz.Crafted().header(size[0], size[1]).directory(1, 0).entry(0, 0, 0, 3).raw(0, 0, 0).done();
			try
			{
				ChunkFile.parse(chunk);
			}
			catch (CodecException expected)
			{
				refused++;
			}
		}
		assertTrue("every one of those is more than a tile may be", refused == 6);
		for (int[] size : new int[][] {{4096, 1024}, {2049, 2048}, {4096, 4096}, {65535, 65535}, {2048, 2048}, {2048, 977}})
		{
			byte[] body = {'W', 'M', 'P', 'I', ImageFile.VERSION, 0, (byte) (size[0] >> 8), (byte) size[0], (byte) (size[1] >> 8),
				(byte) size[1], 0, 0, 0};
			try
			{
				ImageFile.parse(ChunkBuilder.withChecksum(body));
				org.junit.Assert.fail("picture " + size[0] + " by " + size[1]);
			}
			catch (CodecException expected)
			{
				refused++;
			}
		}
		assertTrue(refused == 12);
		assertTrue("refusing is a matter of reading ten bytes", (System.nanoTime() - start) / 1_000_000 < 500);
	}

	@Test
	public void decodingStaysQuickWhateverTheInput()
	{
		fuzz.randomBytes(100, 9, decoder);
		fuzz.bitFlips(samples, 100, 10, decoder);
		assertTrue("worst " + fuzz.worstMs + " ms", fuzz.worstMs < Fuzz.LIMIT_MS);
	}

	/** The same checks in a JVM with a 48 MB heap, where an allocation sized by damaged input would run out of memory. */
	@Test
	public void noOutOfMemoryInASmallHeap() throws Exception
	{
		String java = System.getProperty("java.home") + "/bin/java";
		ProcessBuilder pb = new ProcessBuilder(java, "-Xmx48m", "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
			FuzzMain.class.getName());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		StringBuilder output = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				output.append(line).append('\n');
			}
		}
		assertTrue("child JVM did not finish", p.waitFor(120, TimeUnit.SECONDS));
		assertTrue("small heap run failed:\n" + output, p.exitValue() == 0 && output.toString().contains("ok accepted="));
	}
}
