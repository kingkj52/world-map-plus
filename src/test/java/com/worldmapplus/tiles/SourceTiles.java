package com.worldmapplus.tiles;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * The PNG tile sources in {@code tools/tile-src}, served straight from the folder through the bundled index, for tests that need
 * real map pictures and are not about how they are hosted. Test scope only. Use {@link PngTiles#available()} to skip a test on a
 * machine without the sources.
 */
public final class SourceTiles implements TileSource
{
	private final TileIndex index;

	public SourceTiles(Gson gson)
	{
		this.index = TileIndex.load(gson);
	}

	public TileIndex index()
	{
		return index;
	}

	public int tileCount(int mapId)
	{
		return index.tileCount(mapId);
	}

	public int highestFloor(int mapId)
	{
		return index.highestFloor(mapId);
	}

	@Override
	public boolean exists(int mapId, int x, int y)
	{
		return index.exists(mapId, x, y);
	}

	@Nullable
	@Override
	public BufferedImage load(int mapId, int x, int y)
	{
		try
		{
			return exists(mapId, x, y) ? PngTiles.tile(PngTiles.sourceRoot(), mapId, x, y) : null;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	@Nullable
	@Override
	public BufferedImage overview(int mapId)
	{
		try
		{
			Path file = PngTiles.sourceRoot().resolve("overview-" + mapId + ".png");
			// The sources keep the overviews at full size (32 pixels a square); the jar carries them at half size, which is the
			// exact 2 by 2 box average of the source, the same as the next mip level. This gives what the plugin shows.
			return Files.isRegularFile(file) ? TileCache.shrink(PngTiles.read(file, false), 1) : null;
		}
		catch (IOException e)
		{
			return null;
		}
	}
}
