package com.worldmapplus.tiles;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** A tile source for tests: flat colour tiles for the squares it was told about. */
public class FakeTiles implements TileSource
{
	private final Set<Long> squares = new HashSet<>();
	private final AtomicInteger loads = new AtomicInteger();
	private final java.util.List<int[]> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
	private final AtomicInteger releases = new AtomicInteger();
	private BufferedImage overview;
	private int pieces = 1;
	private final AtomicInteger pieceCount = new AtomicInteger();

	private static long key(int mapId, int x, int y)
	{
		return ((long) mapId << 40) | ((long) x << 20) | y;
	}

	public FakeTiles add(int mapId, int x, int y)
	{
		squares.add(key(mapId, x, y));
		return this;
	}

	public FakeTiles overview(BufferedImage image)
	{
		this.overview = image;
		return this;
	}

	/** Makes the overview in this many pieces, one for each call of the picture's step. */
	public FakeTiles overviewPieces(int count)
	{
		this.pieces = count;
		return this;
	}

	/** How many pieces of an overview have been made. */
	public int overviewPiecesMade()
	{
		return pieceCount.get();
	}

	public int loads()
	{
		return loads.get();
	}

	/** The squares loaded so far, in the order they were loaded, as {mapId, x, y}. */
	public java.util.List<int[]> order()
	{
		return new java.util.ArrayList<>(order);
	}

	/** How many times {@link #release()} has been called. */
	public int releases()
	{
		return releases.get();
	}

	@Override
	public void release()
	{
		releases.incrementAndGet();
	}

	/** The colour a tile is filled with, so tests can tell tiles apart. */
	public static Color colourOf(int mapId, int x, int y)
	{
		int h = mapId * 31 + x * 17 + y * 13;
		return new Color(40 + (h * 53) % 200, 40 + (h * 29) % 200, 40 + (h * 71) % 200);
	}

	@Override
	public boolean exists(int mapId, int x, int y)
	{
		return squares.contains(key(mapId, x, y));
	}

	@Override
	public BufferedImage load(int mapId, int x, int y)
	{
		loads.incrementAndGet();
		order.add(new int[] {mapId, x, y});
		if (!exists(mapId, x, y))
		{
			return null;
		}
		BufferedImage image = new BufferedImage(TILE_PIXELS, TILE_PIXELS, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(colourOf(mapId, x, y));
		g.fillRect(0, 0, TILE_PIXELS, TILE_PIXELS);
		g.dispose();
		return image;
	}

	@Override
	public BufferedImage overview(int mapId)
	{
		return overview;
	}

	@Override
	public Picture startOverview(int mapId)
	{
		if (overview == null)
		{
			return null;
		}
		return new Picture()
		{
			private int left = pieces;

			@Override
			public boolean step(long nanos)
			{
				pieceCount.incrementAndGet();
				return --left <= 0;
			}

			@Override
			public BufferedImage image()
			{
				return overview;
			}
		};
	}
}
