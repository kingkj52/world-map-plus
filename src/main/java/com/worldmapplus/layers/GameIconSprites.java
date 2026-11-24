package com.worldmapplus.layers;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * The pictures of the game's map icons, read once from the one atlas in the plugin jar, and their copies at the smaller sizes the
 * zoom asks for.
 * <p>
 * The atlas (tools/build_game_icons.py) holds the pictures in the order of the list in map-elements.json, {@link #COLUMNS} to a row,
 * each {@link #SIZE} pixels square. A picture is cut from it on first use. A copy at another size is made with area averaging, once
 * per picture and size, and kept, so drawing an icon is one {@code drawImage} of an image that already exists. The number of copies
 * is bounded by the pictures in the atlas times the sizes between the smallest and the native one.
 * <p>
 * The pictures are the Jagex sprites of the map icons, bundled like the icons copied out of RuneLite (see NOTICE).
 */
@Slf4j
public final class GameIconSprites
{
	/** Side of every picture in the atlas, in pixels. */
	public static final int SIZE = 15;

	/** Pictures to a row of the atlas. */
	public static final int COLUMNS = 12;

	private static final String ATLAS = "/com/worldmapplus/gameicons/atlas.png";

	private final BufferedImage atlas;
	private final int count;
	/** [picture][size]: the picture drawn at that side, made when first asked for. Size 0 is unused. */
	private final BufferedImage[][] scaled;
	/** [picture][size]: the picture dimmed and marked with a padlock, made when first asked for. */
	private final BufferedImage[][] locked;
	/** [state][picture][size]: the picture with a dot for how far the player is with its quest, made when first asked for. */
	private final BufferedImage[][][] dotted;

	/** The dot's colours: not started, started, finished, as the game's quest list colours them. */
	static final int[] DOT_COLOURS = {0xFFE0352B, 0xFFF2D43A, 0xFF3FC24F};

	/** Sprites over an atlas image. A null atlas gives no pictures, so every call answers null. */
	public GameIconSprites(@Nullable BufferedImage atlas, int count)
	{
		this.atlas = atlas;
		this.count = atlas == null ? 0 : count;
		this.scaled = new BufferedImage[this.count][GameIconScale.MAX_SIDE + 1];
		this.locked = new BufferedImage[this.count][GameIconScale.MAX_SIDE + 1];
		this.dotted = new BufferedImage[DOT_COLOURS.length][this.count][GameIconScale.MAX_SIDE + 1];
	}

	/**
	 * The picture drawn at a side with a small round dot in its lower right corner, coloured for how far the player is with the quest
	 * that starts there: 0 not started (red), 1 started (yellow), 2 finished (green). Made once per state, picture and side and kept.
	 */
	@Nullable
	public synchronized BufferedImage questDot(int index, int side, int state)
	{
		BufferedImage plain = get(index, side);
		if (plain == null || state < 0 || state >= DOT_COLOURS.length)
		{
			return plain;
		}
		BufferedImage found = dotted[state][index][side];
		if (found == null)
		{
			found = withDot(plain, DOT_COLOURS[state]);
			dotted[state][index][side] = found;
		}
		return found;
	}

	/** A copy of a picture with a dot of a colour, with a dark edge, in its lower right corner. */
	static BufferedImage withDot(BufferedImage source, int colour)
	{
		int w = source.getWidth();
		int h = source.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				out.setRGB(x, y, source.getRGB(x, y));
			}
		}
		double radius = Math.max(2.0, Math.min(w, h) * 0.2);
		double cx = w - radius - 0.5;
		double cy = h - radius - 0.5;
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				double d = Math.hypot(x + 0.5 - (cx + 0.5), y + 0.5 - (cy + 0.5));
				if (d <= radius - 0.9)
				{
					out.setRGB(x, y, colour);
				}
				else if (d <= radius + 0.1)
				{
					out.setRGB(x, y, LOCK_OUTLINE);
				}
			}
		}
		return out;
	}

	/** Reads the bundled atlas. Pictures that the atlas has no room for, or no atlas at all, are answered with null. */
	public static GameIconSprites load(int count)
	{
		BufferedImage image = null;
		try (InputStream in = GameIconSprites.class.getResourceAsStream(ATLAS))
		{
			if (in != null)
			{
				image = readInMemory(in);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("World Map Plus: could not read the icon atlas", e);
		}
		if (image != null && image.getWidth() < COLUMNS * SIZE)
		{
			image = null;
		}
		return new GameIconSprites(image, image == null ? 0 : Math.min(count, COLUMNS * (image.getHeight() / SIZE)));
	}

	/** How many pictures there are. */
	public int size()
	{
		return count;
	}

	/** The picture at its native size, as ARGB, or null when there is none of that number. */
	@Nullable
	public BufferedImage get(int index)
	{
		return get(index, SIZE);
	}

	/**
	 * The picture drawn at a side in pixels, from 1 to the native size. The native size is the atlas picture itself. Asking for the
	 * same picture and side again gives the same image.
	 */
	@Nullable
	public synchronized BufferedImage get(int index, int side)
	{
		if (index < 0 || index >= count || side < 1 || side > GameIconScale.MAX_SIDE)
		{
			return null;
		}
		BufferedImage found = scaled[index][side];
		if (found == null)
		{
			found = side == SIZE ? cut(index) : shrink(get(index, SIZE), side);
			scaled[index][side] = found;
		}
		return found;
	}

	/** How much of its colour and its opacity a picture keeps when it is dimmed, out of 256. */
	private static final int DIM_COLOUR = 110;
	private static final int DIM_ALPHA = 120;

	private static final int LOCK_OUTLINE = 0xFF1E1912;
	private static final int LOCK_GOLD = 0xFFE6BE3C;

	/**
	 * The picture drawn at a side, dimmed and with a small padlock in its lower right corner: how an icon the player cannot use yet is
	 * shown. Made once per picture and side and kept, so drawing one is a single {@code drawImage}, like any other icon.
	 */
	@Nullable
	public synchronized BufferedImage locked(int index, int side)
	{
		BufferedImage plain = get(index, side);
		if (plain == null)
		{
			return null;
		}
		BufferedImage found = locked[index][side];
		if (found == null)
		{
			found = dim(plain);
			locked[index][side] = found;
		}
		return found;
	}

	/** A dimmed copy of a picture with a padlock on it. */
	static BufferedImage dim(BufferedImage source)
	{
		int side = source.getWidth();
		BufferedImage out = new BufferedImage(side, source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < out.getHeight(); y++)
		{
			for (int x = 0; x < side; x++)
			{
				int argb = source.getRGB(x, y);
				int alpha = (argb >>> 24) * DIM_ALPHA / 256;
				int r = argb >> 16 & 0xFF;
				int g = argb >> 8 & 0xFF;
				int b = argb & 0xFF;
				int grey = (r * 30 + g * 59 + b * 11) / 100;
				r = grey + (r - grey) * DIM_COLOUR / 256;
				g = grey + (g - grey) * DIM_COLOUR / 256;
				b = grey + (b - grey) * DIM_COLOUR / 256;
				out.setRGB(x, y, alpha << 24 | r << 16 | g << 8 | b);
			}
		}
		padlock(out);
		return out;
	}

	/** Draws the padlock in the lower right corner: a gold body with a dark edge and a shackle above it. */
	static void padlock(BufferedImage image)
	{
		int side = Math.min(image.getWidth(), image.getHeight());
		int w = Math.max(5, (int) Math.round(side * 0.4));
		int shackle = (w + 1) / 2;
		int body = Math.max(3, (w * 3 + 2) / 5);
		int x0 = image.getWidth() - w;
		int y0 = image.getHeight() - shackle - body;
		if (x0 < 0 || y0 < 0)
		{
			return;
		}
		fill(image, x0 + 1, y0, w - 2, shackle + 1, LOCK_OUTLINE);
		fill(image, x0 + 2, y0 + 1, w - 4, shackle - 1, 0);
		fill(image, x0, y0 + shackle, w, body, LOCK_OUTLINE);
		fill(image, x0 + 1, y0 + shackle + 1, w - 2, body - 2, LOCK_GOLD);
	}

	private static void fill(BufferedImage image, int x, int y, int w, int h, int argb)
	{
		for (int j = y; j < y + h; j++)
		{
			for (int i = x; i < x + w; i++)
			{
				image.setRGB(i, j, argb);
			}
		}
	}

	private BufferedImage cut(int index)
	{
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		int left = index % COLUMNS * SIZE;
		int top = index / COLUMNS * SIZE;
		for (int y = 0; y < SIZE; y++)
		{
			for (int x = 0; x < SIZE; x++)
			{
				out.setRGB(x, y, atlas.getRGB(left + x, top + y));
			}
		}
		return out;
	}

	/** The picture at a smaller side, each pixel the average of the part of the picture it covers, weighted by coverage and alpha. */
	static BufferedImage shrink(BufferedImage source, int side)
	{
		int w = source.getWidth();
		int h = source.getHeight();
		BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
		double stepX = (double) w / side;
		double stepY = (double) h / side;
		for (int ty = 0; ty < side; ty++)
		{
			for (int tx = 0; tx < side; tx++)
			{
				double x0 = tx * stepX;
				double x1 = x0 + stepX;
				double y0 = ty * stepY;
				double y1 = y0 + stepY;
				double sumA = 0;
				double sumR = 0;
				double sumG = 0;
				double sumB = 0;
				double area = 0;
				for (int sy = (int) Math.floor(y0); sy < Math.ceil(y1) && sy < h; sy++)
				{
					double wy = Math.min(y1, sy + 1) - Math.max(y0, sy);
					for (int sx = (int) Math.floor(x0); sx < Math.ceil(x1) && sx < w; sx++)
					{
						double wgt = wy * (Math.min(x1, sx + 1) - Math.max(x0, sx));
						int argb = source.getRGB(sx, sy);
						double a = (argb >>> 24) / 255.0;
						area += wgt;
						sumA += wgt * a;
						sumR += wgt * a * (argb >> 16 & 0xFF);
						sumG += wgt * a * (argb >> 8 & 0xFF);
						sumB += wgt * a * (argb & 0xFF);
					}
				}
				if (sumA <= 0 || area <= 0)
				{
					continue;
				}
				int alpha = (int) Math.round(255 * sumA / area);
				int r = (int) Math.round(sumR / sumA);
				int g = (int) Math.round(sumG / sumA);
				int b = (int) Math.round(sumB / sumA);
				out.setRGB(tx, ty, alpha << 24 | r << 16 | g << 8 | b);
			}
		}
		return out;
	}

	/** Reads an image with a cache held in memory, so no temporary file is made. */
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
				stream.close();
			}
			catch (IOException alreadyClosed)
			{
				// ImageIO closes the stream itself when it has read the image.
			}
		}
	}
}
