package com.worldmapplus.tiles.codec;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.worldmapplus.tiles.PngTiles;
import com.worldmapplus.tiles.TileSource;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PNG tile sources as files on disk (tools/tile-src), laid out as the plugin's resources used to be:
 * {@code index.json}, {@code <mapId>/<x>_<y>.png} and {@code overview-<mapId>.png}. Reads them the way the plugin did
 * before the chunk files, which is the reference every decoded pixel is compared with.
 * <p>
 * The tiles are exactly the ones {@code index.json} lists. PNG files in the folders that the index leaves out (seven
 * nearly blank files the plugin could never reach) are neither read nor packed.
 */
final class Corpus
{
	/** One tile file. */
	static final class Ref
	{
		final int mapId;
		final int x;
		final int y;
		final Path file;

		Ref(int mapId, int x, int y, Path file)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.file = file;
		}

		boolean alpha()
		{
			return mapId >= TileSource.FLOOR_STRIDE;
		}

		String rel()
		{
			return mapId + "/" + x + "_" + y + ".png";
		}
	}

	private Corpus()
	{
	}

	/**
	 * Every tile the index of the root lists, sorted by map and square. A listed tile with no file is an error. Files
	 * the index does not list are reported on stderr and left out.
	 */
	static List<Ref> scan(Path root) throws IOException
	{
		Path index = root.resolve("index.json");
		if (!Files.isRegularFile(index))
		{
			throw new IllegalStateException("no index.json in " + root + ": the packer packs the tiles the index lists");
		}
		List<Ref> refs = new ArrayList<>();
		JsonObject json = new Gson().fromJson(new String(Files.readAllBytes(index), StandardCharsets.UTF_8), JsonObject.class);
		for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("maps").entrySet())
		{
			int mapId = Integer.parseInt(e.getKey());
			for (JsonElement v : e.getValue().getAsJsonArray())
			{
				int key = v.getAsInt();
				int x = key >>> 16;
				int y = key & 0xFFFF;
				Path file = root.resolve(mapId + "/" + x + "_" + y + ".png");
				if (!Files.isRegularFile(file))
				{
					throw new IllegalStateException("index.json lists " + mapId + "/" + x + "_" + y + ", which is not in " + root);
				}
				refs.add(new Ref(mapId, x, y, file));
			}
		}
		refs.sort(Comparator.<Ref>comparingInt(r -> r.mapId).thenComparingInt(r -> r.x).thenComparingInt(r -> r.y));
		reportUnlisted(root, refs);
		return refs;
	}

	private static void reportUnlisted(Path root, List<Ref> refs) throws IOException
	{
		Set<String> listed = new HashSet<>();
		for (Ref r : refs)
		{
			listed.add(r.mapId + "/" + r.x + "_" + r.y);
		}
		Set<String> extra = new java.util.TreeSet<>();
		try (DirectoryStream<Path> maps = Files.newDirectoryStream(root))
		{
			for (Path dir : maps)
			{
				String name = dir.getFileName().toString();
				if (!Files.isDirectory(dir) || !name.matches("\\d+"))
				{
					continue;
				}
				try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.png"))
				{
					for (Path f : files)
					{
						String n = f.getFileName().toString();
						String key = name + "/" + n.substring(0, n.length() - 4);
						if (!listed.contains(key))
						{
							extra.add(key);
						}
					}
				}
			}
		}
		if (!extra.isEmpty())
		{
			System.err.println("note: " + extra.size() + " tiles are in the folder but not in index.json, so they are not packed: " + extra);
		}
	}

	/** The overview files in the root, as map ids. */
	static List<Integer> overviews(Path root) throws IOException
	{
		List<Integer> ids = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "overview-*.png"))
		{
			for (Path f : files)
			{
				String n = f.getFileName().toString();
				ids.add(Integer.parseInt(n.substring("overview-".length(), n.length() - 4)));
			}
		}
		ids.sort(null);
		return ids;
	}

	/** A PNG read the way the plugin read it: ImageIO in memory, then drawn into an RGB or ARGB image. */
	static BufferedImage readLikeThePlugin(Path file, boolean keepAlpha) throws IOException
	{
		return PngTiles.read(file, keepAlpha);
	}

	/**
	 * True when two images are the same to everything that draws or reads them: the same type and size, the same
	 * getRGB values, and the same raw ints (for TYPE_INT_RGB without the top byte, which that type ignores and which
	 * ImageIO and Graphics2D fill differently for the same picture).
	 */
	static boolean sameImage(BufferedImage a, BufferedImage b)
	{
		if (a.getType() != b.getType() || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
		{
			return false;
		}
		if (!java.util.Arrays.equals(argb(a), argb(b)))
		{
			return false;
		}
		int[] ra = raster(a);
		int[] rb = raster(b);
		int mask = a.getType() == BufferedImage.TYPE_INT_RGB ? 0xFFFFFF : 0xFFFFFFFF;
		for (int i = 0; i < ra.length; i++)
		{
			if ((ra[i] & mask) != (rb[i] & mask))
			{
				return false;
			}
		}
		return true;
	}

	/** The pixels of an image as 0xAARRGGBB, which is how the plugin reads them. */
	static int[] argb(BufferedImage image)
	{
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	/** The raw pixel array of an image that holds its pixels as ints. */
	static int[] raster(BufferedImage image)
	{
		return ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
	}
}
