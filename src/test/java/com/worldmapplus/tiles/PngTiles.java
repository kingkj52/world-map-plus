package com.worldmapplus.tiles;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * The PNG tile sources in {@code tools/tile-src} and the way the plugin used to read them: ImageIO in memory, then drawn
 * into a plain RGB image (ground tiles and overviews) or an ARGB one (floors). The packer and the tests that prove the
 * chunk files hold the same pixels as the PNGs go through here. Test scope only, because the plugin itself no longer
 * ships PNG tiles.
 */
public final class PngTiles
{
	/** Where the tile sources are, unless the system property {@code wmp.tiles} names another folder. */
	public static final String DEFAULT_SOURCE = "tools/tile-src";

	private PngTiles()
	{
	}

	/** The folder with {@code index.json}, {@code <mapId>/<x>_<y>.png} and {@code overview-<mapId>.png}. */
	public static Path sourceRoot()
	{
		return Paths.get(System.getProperty("wmp.tiles", DEFAULT_SOURCE));
	}

	/** True when the PNG sources are on this machine. A copy of the repository trimmed for the Plugin Hub has none. */
	public static boolean available()
	{
		return Files.isRegularFile(sourceRoot().resolve("index.json"));
	}

	/** Reads an image with a cache held in memory, as the plugin did. */
	public static BufferedImage readInMemory(InputStream in) throws IOException
	{
		ImageInputStream stream = new MemoryCacheImageInputStream(in);
		try
		{
			return ImageIO.read(stream);
		}
		finally
		{
			try
			{
				stream.close();
			}
			catch (IOException alreadyClosed)
			{
				// ImageIO closes the stream itself when it has read the image.
			}
		}
	}

	/** A PNG file read the way the plugin read it: in memory, then as RGB, or ARGB when keepAlpha is set. */
	public static BufferedImage read(Path file, boolean keepAlpha) throws IOException
	{
		BufferedImage decoded = readInMemory(new ByteArrayInputStream(Files.readAllBytes(file)));
		return keepAlpha ? toArgb(decoded) : toRgb(decoded);
	}

	/** The tile of a square in the sources, as the plugin used to serve it. */
	public static BufferedImage tile(Path root, int mapId, int x, int y) throws IOException
	{
		return read(root.resolve(mapId + "/" + x + "_" + y + ".png"), mapId >= TileSource.FLOOR_STRIDE);
	}

	/** Floor tiles are drawn over the ground, so what is clear in them stays clear. */
	public static BufferedImage toArgb(BufferedImage source)
	{
		return draw(source, BufferedImage.TYPE_INT_ARGB);
	}

	/** Everything is held as plain RGB so drawing it is a straight copy. */
	public static BufferedImage toRgb(BufferedImage source)
	{
		return draw(source, BufferedImage.TYPE_INT_RGB);
	}

	private static BufferedImage draw(BufferedImage source, int type)
	{
		if (source.getType() == type)
		{
			return source;
		}
		BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), type);
		Graphics2D g = out.createGraphics();
		try
		{
			g.drawImage(source, 0, 0, null);
		}
		finally
		{
			g.dispose();
		}
		return out;
	}
}
