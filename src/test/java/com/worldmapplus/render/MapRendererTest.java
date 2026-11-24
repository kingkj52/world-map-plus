package com.worldmapplus.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.map.MapInfo;
import com.worldmapplus.tiles.FakeTiles;
import com.worldmapplus.tiles.TileCache;
import com.worldmapplus.tiles.TileSource;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.Test;

public class MapRendererTest
{
	private static final MapInfo MAP = new MapInfo(0, "Test", 3136, 3136, 3328, 3328, 3200, 3200);

	private static int rgb(Color c)
	{
		return c.getRGB() & 0xFFFFFF;
	}

	private static BufferedImage paint(FakeTiles source, ViewTransform view)
	{
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		BufferedImage canvas = new BufferedImage(
			view.getLeft() + view.getWidth(), view.getTop() + view.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		// Direct executor: the first frame requests, the second draws what loaded.
		renderer.paint(g, view, MAP);
		renderer.paint(g, view, MAP);
		g.dispose();
		return canvas;
	}

	@Test
	public void mipLevelFollowsTheScale()
	{
		assertEquals(0, MapRenderer.mipFor(32));
		assertEquals(0, MapRenderer.mipFor(4));
		assertEquals(0, MapRenderer.mipFor(3.5));
		assertEquals(1, MapRenderer.mipFor(2));
		assertEquals(2, MapRenderer.mipFor(1));
		assertEquals(3, MapRenderer.mipFor(0.5));
		assertEquals(5, MapRenderer.mipFor(0.01));
	}

	@Test
	public void aFloorIsDrawnFromTheTilesStoredForIt()
	{
		int firstFloor = TileSource.tileMapId(0, 1);
		FakeTiles source = new FakeTiles().add(0, 50, 50).add(firstFloor, 50, 50);
		ViewTransform view = new ViewTransform(3232, 3232, 4.0, 0, 0, 400, 400);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		BufferedImage canvas = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		renderer.paint(g, view, MAP, 1);
		renderer.paint(g, view, MAP, 1);
		assertEquals(rgb(FakeTiles.colourOf(firstFloor, 50, 50)), canvas.getRGB(200, 200) & 0xFFFFFF);
		renderer.paint(g, view, MAP, 0);
		renderer.paint(g, view, MAP, 0);
		assertEquals(rgb(FakeTiles.colourOf(0, 50, 50)), canvas.getRGB(200, 200) & 0xFFFFFF);
		g.dispose();
	}

	@Test
	public void floorTileIdsKeepClearOfMapIds()
	{
		assertEquals(0, TileSource.tileMapId(0, 0));
		assertEquals(33, TileSource.tileMapId(33, 0));
		assertEquals(20_000, TileSource.tileMapId(0, 1));
		assertEquals(40_033, TileSource.tileMapId(33, 2));
		assertEquals(60_000, TileSource.tileMapId(0, 3));
		assertEquals("a floor above the top is the top", 60_000, TileSource.tileMapId(0, 9));
		assertEquals("a negative floor is the ground", 5, TileSource.tileMapId(5, -2));
	}

	@Test
	public void northernSquaresAreDrawnAboveSouthernOnes()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50).add(0, 50, 51);
		// Centre on the corner shared by squares (50,50) and (50,51): world (3200 + 32, 3264).
		ViewTransform view = new ViewTransform(3232, 3264, 4.0, 0, 0, 400, 400);
		BufferedImage canvas = paint(source, view);
		int southPixel = canvas.getRGB(200, 300) & 0xFFFFFF;
		int northPixel = canvas.getRGB(200, 100) & 0xFFFFFF;
		assertEquals(rgb(FakeTiles.colourOf(0, 50, 50)), southPixel);
		assertEquals(rgb(FakeTiles.colourOf(0, 50, 51)), northPixel);
	}

	@Test
	public void easternSquaresAreDrawnToTheRight()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50).add(0, 51, 50);
		ViewTransform view = new ViewTransform(3264, 3232, 4.0, 0, 0, 400, 400);
		BufferedImage canvas = paint(source, view);
		assertEquals(rgb(FakeTiles.colourOf(0, 50, 50)), canvas.getRGB(100, 200) & 0xFFFFFF);
		assertEquals(rgb(FakeTiles.colourOf(0, 51, 50)), canvas.getRGB(300, 200) & 0xFFFFFF);
	}

	@Test
	public void squaresWithoutATileShowTheBackground()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50);
		ViewTransform view = new ViewTransform(3232, 3264, 4.0, 0, 0, 400, 400);
		BufferedImage canvas = paint(source, view);
		assertEquals(rgb(MapRenderer.BACKGROUND), canvas.getRGB(200, 100) & 0xFFFFFF);
	}

	@Test
	public void zoomedOutTilesStillCoverTheirSquare()
	{
		FakeTiles source = new FakeTiles().add(0, 50, 50);
		ViewTransform view = new ViewTransform(3232, 3232, 0.7, 0, 0, 200, 200);
		BufferedImage canvas = paint(source, view);
		assertEquals(rgb(FakeTiles.colourOf(0, 50, 50)), canvas.getRGB(100, 100) & 0xFFFFFF);
	}

	@Test
	public void theOverviewIsUsedWhenZoomedFarOut()
	{
		BufferedImage overview = new BufferedImage(6 * 32, 6 * 32, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = overview.createGraphics();
		g.setColor(new Color(0x12, 0x34, 0x56));
		g.fillRect(0, 0, overview.getWidth(), overview.getHeight());
		g.dispose();
		FakeTiles source = new FakeTiles().add(0, 50, 50).overview(overview);
		TileCache cache = new TileCache(source, Runnable::run, 64L << 20);
		cache.getOverview(0);
		MapRenderer renderer = new MapRenderer(cache);
		ViewTransform view = new ViewTransform(3232, 3232, 0.2, 0, 0, 200, 200);
		BufferedImage canvas = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
		Graphics2D canvasGraphics = canvas.createGraphics();
		renderer.paint(canvasGraphics, view, MAP);
		canvasGraphics.dispose();
		assertEquals(0x123456, canvas.getRGB(100, 100) & 0xFFFFFF);
		assertEquals(0, source.loads());
	}

	// ---- stand-ins for tiles that are still loading ----

	/** Three squares each way, 32 pixels a square, each square its own flat colour. */
	private static BufferedImage overview()
	{
		BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		for (int column = 0; column < 3; column++)
		{
			for (int row = 0; row < 3; row++)
			{
				g.setColor(new Color(0x30 + column * 0x40, 0x50 + row * 0x30, 0xA0 - column * 0x10 - row * 0x10));
				g.fillRect(column * 32, row * 32, 32, 32);
			}
		}
		g.dispose();
		return image;
	}

	/** The overview colour over square x, y of MAP: the top row of the picture is the northern square row. */
	private static int overviewColour(int x, int y)
	{
		int column = x - MAP.firstSquareX();
		int row = MAP.lastSquareY() - y;
		return new Color(0x30 + column * 0x40, 0x50 + row * 0x30, 0xA0 - column * 0x10 - row * 0x10).getRGB() & 0xFFFFFF;
	}

	private static FakeTiles squares(boolean withPicture)
	{
		FakeTiles source = new FakeTiles();
		if (withPicture)
		{
			source.overview(overview());
		}
		for (int x = MAP.firstSquareX(); x <= MAP.lastSquareX(); x++)
		{
			for (int y = MAP.firstSquareY(); y <= MAP.lastSquareY(); y++)
			{
				source.add(0, x, y);
			}
		}
		return source;
	}

	/** Paints and runs the queue until the cache has nothing pending, then paints once more. */
	private static void settle(Deque<Runnable> queue, TileCache cache, MapRenderer renderer, Graphics2D g, ViewTransform view)
	{
		for (int i = 0; i < 2000 && (cache.pendingCount() > 0 || i < 3); i++)
		{
			renderer.paint(g, view, MAP);
			while (!queue.isEmpty())
			{
				queue.poll().run();
			}
		}
		renderer.paint(g, view, MAP);
	}

	@Test
	public void aTileStillLoadingShowsThePartOfTheOverviewThatCoversIt()
	{
		FakeTiles source = new FakeTiles().overview(overview()).add(0, 50, 50);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		// The overview is made first and the tile is still to come.
		cache.getOverview(0);
		queue.poll().run();
		ViewTransform view = new ViewTransform(3232, 3232, 4.0, 0, 0, 400, 400);
		BufferedImage canvas = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		renderer.paint(g, view, MAP);
		assertEquals(0, source.loads());
		assertEquals("the middle of the square shows the overview", overviewColour(50, 50), canvas.getRGB(200, 200) & 0xFFFFFF);
		assertEquals(overviewColour(50, 50), canvas.getRGB(160, 240) & 0xFFFFFF);
		// A square with no tile at all is not stood in for.
		assertEquals(rgb(MapRenderer.BACKGROUND), canvas.getRGB(20, 20) & 0xFFFFFF);

		// The moment the tile is there, the stand in is gone, all of it.
		while (!queue.isEmpty())
		{
			queue.poll().run();
		}
		renderer.paint(g, view, MAP);
		int tile = rgb(FakeTiles.colourOf(0, 50, 50));
		for (int y = 72; y < 328; y += 5)
		{
			for (int x = 72; x < 328; x += 5)
			{
				assertEquals("pixel " + x + "," + y, tile, canvas.getRGB(x, y) & 0xFFFFFF);
			}
		}
		g.dispose();
	}

	@Test
	public void aStandInIsNeverDrawnOverATileThatIsReady()
	{
		FakeTiles source = new FakeTiles().overview(overview()).add(0, 50, 50).add(0, 51, 50);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		cache.getOverview(0);
		queue.poll().run();
		ViewTransform view = new ViewTransform(3264, 3232, 4.0, 0, 0, 400, 400);
		BufferedImage canvas = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		renderer.paint(g, view, MAP);
		// One task decodes one tile, so exactly one of the two is ready after it.
		queue.poll().run();
		renderer.paint(g, view, MAP);
		int left = canvas.getRGB(100, 200) & 0xFFFFFF;
		int right = canvas.getRGB(300, 200) & 0xFFFFFF;
		boolean leftReady = left == rgb(FakeTiles.colourOf(0, 50, 50));
		boolean rightReady = right == rgb(FakeTiles.colourOf(0, 51, 50));
		assertTrue("exactly one tile is ready", leftReady != rightReady);
		assertEquals("the other square shows the overview", leftReady ? overviewColour(51, 50) : overviewColour(50, 50),
			leftReady ? right : left);
		g.dispose();
	}

	@Test
	public void aFirstFrameHasNoHolesWhereTilesAreComingAtAnyScale()
	{
		for (double scale : new double[] {0.5, 0.7, 1.0, 2.0, 4.0, 8.0, 16.0})
		{
			FakeTiles source = squares(true);
			Deque<Runnable> queue = new ArrayDeque<>();
			TileCache cache = new TileCache(source, queue::add, 64L << 20);
			MapRenderer renderer = new MapRenderer(cache);
			cache.getOverview(0);
			queue.poll().run();
			ViewTransform view = new ViewTransform(3232, 3232, scale, 0, 0, 80, 80);
			BufferedImage canvas = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = canvas.createGraphics();
			renderer.paint(g, view, MAP);
			assertEquals("nothing decoded yet at " + scale, 0, source.loads());
			for (int y = 0; y < 80; y++)
			{
				for (int x = 0; x < 80; x++)
				{
					assertNotEquals("a hole at " + x + "," + y + " at scale " + scale, rgb(MapRenderer.BACKGROUND),
						canvas.getRGB(x, y) & 0xFFFFFF);
				}
			}
			g.dispose();
		}
	}

	@Test
	public void aStandInComesFromAnotherSizeOfTheSameTileWhenOneIsInMemory()
	{
		FakeTiles source = new FakeTiles().overview(overview()).add(0, 50, 50);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		BufferedImage canvas = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		ViewTransform full = new ViewTransform(3232, 3232, 4.0, 0, 0, 400, 400);
		renderer.paint(g, full, MAP);
		while (!queue.isEmpty())
		{
			queue.poll().run();
		}
		renderer.paint(g, full, MAP);
		assertEquals(rgb(FakeTiles.colourOf(0, 50, 50)), canvas.getRGB(200, 200) & 0xFFFFFF);
		// Zoomed out one step: the smaller copy is not made yet, and the full tile stands in for it, not the overview.
		ViewTransform out = new ViewTransform(3232, 3232, 2.0, 0, 0, 400, 400);
		renderer.paint(g, out, MAP);
		assertEquals("the tile itself", rgb(FakeTiles.colourOf(0, 50, 50)), canvas.getRGB(200, 200) & 0xFFFFFF);
		g.dispose();
	}

	@Test
	public void aSettledViewIsExactlyTheTilesWhateverStoodInForThemBefore()
	{
		for (double scale : new double[] {0.5, 0.7, 1.0, 2.0, 4.0, 8.0})
		{
			ViewTransform view = new ViewTransform(3232, 3232, scale, 0, 0, 120, 120);

			Deque<Runnable> queue = new ArrayDeque<>();
			TileCache cache = new TileCache(squares(true), queue::add, 64L << 20);
			BufferedImage settled = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = settled.createGraphics();
			settle(queue, cache, new MapRenderer(cache), g, view);
			g.dispose();

			Deque<Runnable> queue2 = new ArrayDeque<>();
			TileCache cache2 = new TileCache(squares(false), queue2::add, 64L << 20);
			BufferedImage reference = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
			Graphics2D g2 = reference.createGraphics();
			settle(queue2, cache2, new MapRenderer(cache2), g2, view);
			g2.dispose();

			for (int y = 0; y < 120; y++)
			{
				for (int x = 0; x < 120; x++)
				{
					assertEquals("scale " + scale + " pixel " + x + "," + y, reference.getRGB(x, y), settled.getRGB(x, y));
				}
			}
		}
	}

	@Test
	public void aFloorsGroundIsStoodInForByTheGroundOverviewUnderTheWash()
	{
		int floor = TileSource.tileMapId(0, 1);
		FakeTiles source = new FakeTiles().overview(overview()).add(0, 50, 50).add(floor, 50, 50);
		Deque<Runnable> queue = new ArrayDeque<>();
		TileCache cache = new TileCache(source, queue::add, 64L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		cache.getOverview(0);
		queue.poll().run();
		ViewTransform view = new ViewTransform(3232, 3232, 4.0, 0, 0, 400, 400);
		BufferedImage canvas = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		renderer.paint(g, view, MAP, 1);
		int ground = overviewColour(50, 50);
		int wash = MapRenderer.WASH.getRGB();
		int alpha = (wash >>> 24) & 0xFF;
		for (int shift = 16; shift >= 0; shift -= 8)
		{
			int expected = (((ground >> shift) & 0xFF) * (255 - alpha) + ((wash >> shift) & 0xFF) * alpha) / 255;
			int got = (canvas.getRGB(200, 200) >> shift) & 0xFF;
			assertTrue("channel " + shift + " expected " + expected + " got " + got, Math.abs(expected - got) <= 2);
		}
		g.dispose();
	}

	@Test
	public void aNullMapJustClearsTheBackground()
	{
		TileCache cache = new TileCache(new FakeTiles(), Runnable::run, 1L << 20);
		MapRenderer renderer = new MapRenderer(cache);
		BufferedImage canvas = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		renderer.paint(g, new ViewTransform(0, 0, 4, 0, 0, 50, 50), null);
		g.dispose();
		assertEquals(rgb(MapRenderer.BACKGROUND), canvas.getRGB(10, 10) & 0xFFFFFF);
	}
}
