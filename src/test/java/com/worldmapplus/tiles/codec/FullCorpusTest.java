package com.worldmapplus.tiles.codec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.tiles.PngTiles;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Test;

/**
 * The one-off check of the whole corpus: every tile and every overview packed, parsed back and compared pixel for pixel
 * with the PNG. Skipped unless run with {@code -Dwmp.fullcorpus=true} (or the environment variable
 * {@code WMP_FULLCORPUS=true}). The tile sources are {@code tools/tile-src} unless {@code -Dwmp.tiles=<folder>} says
 * otherwise. The output goes to a temporary folder, so the plugin's resources are not touched. Runs two packer threads,
 * which a 512 MB test JVM holds (a chunk of the surface keeps about 120 MB of pixels while it is built), and takes about
 * 40 seconds.
 */
public class FullCorpusTest
{
	@Test
	public void everyBundledTileAndOverviewSurvivesBitForBit() throws Exception
	{
		boolean on = Boolean.getBoolean("wmp.fullcorpus") || "true".equals(System.getenv("WMP_FULLCORPUS"));
		Assume.assumeTrue("set -Dwmp.fullcorpus=true to run", on);
		Path root = PngTiles.sourceRoot();
		Assume.assumeTrue("no tile folder at " + root, Files.isDirectory(root));
		Path out = Files.createTempDirectory("wmp-pack");
		PackTiles.Report r = PackTiles.pack(root, out, 2, 0);
		System.out.printf("full corpus: %d tiles, %d chunks, %d aliases, %d chunk bytes, %d overviews, %d overview bytes%n", r.tiles, r.chunks,
			r.aliases, r.chunkBytes, r.overviews, r.overviewBytes);
		assertEquals(4, r.overviews);
		assertTrue(r.tiles >= 4000);
		assertEquals(r.tiles, r.golden.size());
	}
}
