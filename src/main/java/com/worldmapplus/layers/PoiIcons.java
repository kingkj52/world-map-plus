package com.worldmapplus.layers;

import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import lombok.extern.slf4j.*;

/**
 * The small pictures drawn for points of interest, read from the plugin jar on first use and kept.
 * <p>
 * The pictures are RuneLite's own world map icons, copied into the plugin at build time and kept together in icons.pack. A name with
 * no picture returns null and the caller draws a lettered disc instead.
 */
@Slf4j
public final class PoiIcons
{
	/** Icons read by one task of {@link #preload}, so no task holds the shared thread for long. */
	private static final int PRELOAD_BATCH = 6;

	private final Map<String, BufferedImage> cache = new HashMap<>();

	/** The pictures, read from icons.pack the first time one is asked for. */
	private IconPack pack;

	/**
	 * Reads the named icons on the executor, a few at a time, each task queueing the next, so the first frame that draws points of
	 * interest finds them in memory and does no image reading on the render thread. The names are checked like any other.
	 */
	public void preload(Executor executor, Set<String> names)
	{
		List<String> todo = new ArrayList<>(names);
		submit(executor, todo, 0);
	}

	private void submit(Executor executor, List<String> todo, int from)
	{
		if (from >= todo.size())
		{
			return;
		}
		try
		{
			executor.execute(() ->
			{
				int to = Math.min(todo.size(), from + PRELOAD_BATCH);
				for (int i = from; i < to; i++)
				{
					get(todo.get(i));
				}
				submit(executor, todo, to);
			});
		}
		catch (RejectedExecutionException e)
		{
			// The plugin is stopping; the icons are read when they are drawn.
		}
	}

	@Nullable
	public synchronized BufferedImage get(@Nullable String name)
	{
		if (name == null || name.isEmpty() || name.contains("/") || name.contains(".."))
		{
			return null;
		}
		if (cache.containsKey(name))
		{
			return cache.get(name);
		}
		BufferedImage image = null;
		try (InputStream in = pack().open(name))
		{
			if (in != null)
			{
				image = readInMemory(in);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("World Map Plus: could not read icon {}", name, e);
		}
		cache.put(name, image);
		return image;
	}

	/** The bundled pictures. A missing or damaged pack is logged once and gives no pictures. */
	private IconPack pack()
	{
		if (pack == null)
		{
			try
			{
				pack = IconPack.bundled();
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("World Map Plus: could not read the point of interest pictures: {}", e.toString());
				pack = IconPack.empty();
			}
		}
		return pack;
	}

	/**
	 * Reads an image with a cache held in memory. ImageIO.read(InputStream) may otherwise use a temporary
	 * file, and this plugin touches no files.
	 */
	private static BufferedImage readInMemory(InputStream in) throws IOException
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
				// ImageIO closes the stream itself when it has read the image, and a second close reports an error.
				stream.close();
			}
			catch (IOException alreadyClosed)
			{
				// Nothing left to release.
			}
		}
	}
}
