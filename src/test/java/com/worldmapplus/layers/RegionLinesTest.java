package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import org.junit.Test;

public class RegionLinesTest
{
	private static final Gson GSON = new Gson();

	private static RegionLines read(String json)
	{
		return RegionLines.read(new StringReader(json), GSON);
	}

	@Test
	public void aTileIsInTheRegionOfItsSquare()
	{
		RegionLines lines = read("{\"regions\":[{\"id\":\"A\",\"name\":\"Alpha\",\"color\":\"#ff0000\",\"cells\":[[50,49]]}]}");
		assertEquals("Alpha", lines.regionAt(3200, 3136).getName());
		assertEquals("Alpha", lines.regionAt(3263, 3199).getName());
		assertNull(lines.regionAt(3264, 3136));
		assertNull(lines.regionAt(3199, 3136));
		assertNull(lines.regionAt(-5, -5));
	}

	@Test
	public void aLoneSquareHasFourEdgesAndTwoSquaresShareOne()
	{
		RegionLines one = read("{\"regions\":[{\"id\":\"A\",\"name\":\"A\",\"color\":\"#ff0000\",\"cells\":[[1,1]]}]}");
		assertEquals(4, one.getRegions().get(0).getEdges().size());
		RegionLines two = read("{\"regions\":[{\"id\":\"A\",\"name\":\"A\",\"color\":\"#ff0000\",\"cells\":[[1,1],[2,1]]}]}");
		assertEquals(6, two.getRegions().get(0).getEdges().size());
	}

	@Test
	public void bordersBetweenTwoRegionsAreDrawnForBoth()
	{
		RegionLines lines = read("{\"regions\":["
			+ "{\"id\":\"A\",\"name\":\"A\",\"color\":\"#ff0000\",\"cells\":[[1,1]]},"
			+ "{\"id\":\"B\",\"name\":\"B\",\"color\":\"#0000ff\",\"cells\":[[2,1]]}]}");
		assertEquals(4, lines.getRegions().get(0).getEdges().size());
		assertEquals(4, lines.getRegions().get(1).getEdges().size());
	}

	@Test
	public void aSquareClaimedTwiceBelongsToTheFirstRegionOnly()
	{
		RegionLines lines = read("{\"regions\":["
			+ "{\"id\":\"A\",\"name\":\"A\",\"color\":\"#ff0000\",\"cells\":[[1,1]]},"
			+ "{\"id\":\"B\",\"name\":\"B\",\"color\":\"#0000ff\",\"cells\":[[1,1]]}]}");
		assertEquals(1, lines.getRegions().size());
		assertEquals("A", lines.regionAt(64, 64).getName());
	}

	@Test
	public void theLargestStretchIsNamedAndSmallSeparateOnesAreNot()
	{
		StringBuilder cells = new StringBuilder("[[0,0]");
		for (int i = 1; i < 10; i++)
		{
			cells.append(",[").append(i).append(",0]");
		}
		cells.append(",[30,30]]");
		RegionLines lines = read("{\"regions\":[{\"id\":\"A\",\"name\":\"A\",\"color\":\"#ff0000\",\"cells\":" + cells + "}]}");
		assertEquals(1, lines.getRegions().get(0).getLabels().size());
		double[] at = lines.getRegions().get(0).getLabels().get(0);
		assertEquals(5 * 64.0, at[0], 1e-9);
	}

	@Test
	public void aBigSecondStretchIsNamedToo()
	{
		StringBuilder cells = new StringBuilder();
		for (int i = 0; i < 8; i++)
		{
			cells.append(i > 0 ? "," : "").append("[").append(i).append(",0],[").append(i).append(",20]");
		}
		RegionLines lines = read("{\"regions\":[{\"id\":\"A\",\"name\":\"A\",\"color\":\"#ff0000\",\"cells\":[" + cells + "]}]}");
		assertEquals(2, lines.getRegions().get(0).getLabels().size());
	}

	@Test
	public void badRecordsAreSkipped()
	{
		RegionLines lines = read("{\"regions\":[null,{\"id\":\"A\"},{\"id\":\"B\",\"name\":\"B\",\"cells\":[]},"
			+ "{\"id\":\"C\",\"name\":\"C\",\"color\":\"nonsense\",\"cells\":[[1,1],[7]]}]}");
		assertEquals(1, lines.getRegions().size());
		assertEquals("C", lines.getRegions().get(0).getName());
		assertEquals(1, lines.getRegions().get(0).squareCount());
	}

	@Test
	public void paintingDrawsSomethingAndNeverFailsOnEmptyData()
	{
		RegionLines lines = read("{\"regions\":[{\"id\":\"A\",\"name\":\"Alpha\",\"color\":\"#ff0000\",\"cells\":[[1,1],[2,1]]}]}");
		BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		ViewTransform view = new ViewTransform(128, 96, 1.0, 0, 0, 300, 200);
		lines.paint(g, view);
		g.dispose();
		boolean drawn = false;
		for (int x = 0; x < 300 && !drawn; x++)
		{
			for (int y = 0; y < 200 && !drawn; y++)
			{
				drawn = image.getRGB(x, y) != 0;
			}
		}
		assertTrue("the squares are tinted", drawn);

		Graphics2D h = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics();
		RegionLines.empty().paint(h, view);
	}

	@Test
	public void theBundledTableNamesTheEleven()
	{
		RegionLines lines = RegionLines.load(GSON);
		assertEquals(11, lines.getRegions().size());
		assertEquals("Misthalin", lines.regionAt(3213, 3424).getName());
		assertEquals("Misthalin", lines.regionAt(3222, 3218).getName());
		assertEquals("Asgarnia", lines.regionAt(2964, 3378).getName());
		assertEquals("Kandarin", lines.regionAt(2662, 3305).getName());
		assertEquals("Forinthry", lines.regionAt(3100, 3800).getName());
		assertEquals("Morytania", lines.regionAt(3500, 3480).getName());
		assertEquals("Kharidian Desert", lines.regionAt(3300, 3000).getName());
		assertEquals("Great Kourend", lines.regionAt(1640, 3670).getName());
		assertEquals("Karamja", lines.regionAt(2850, 3000).getName());
		assertNotNull(lines.regionAt(2300, 3180));
		assertNotEquals(lines.regionAt(3213, 3424).getColor(), lines.regionAt(3100, 3800).getColor());
	}
}
