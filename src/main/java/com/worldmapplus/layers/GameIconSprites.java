package com.worldmapplus.layers;

import java.awt.image.*;
import java.io.*;
import javax.annotation.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import lombok.extern.slf4j.*;

/**
 * The pictures of the game's map icons at every side the zoom draws them at ({@link GameIconScale}), plain and in the looks that mark
 * progress (dimmed with a padlock, or with a dot for how far the player is with the quest that starts there), all drawn at build time
 * by {@code tools/build_game_icons.py}: one sheet a side, {@code gameicons/<side>.png}, a row a picture and in it the picture in each
 * look at {@code look * side}, by {@link GameIconLayer}'s numbers.
 * <p>
 * A sheet is read the first time a picture of a look and side is asked for, and cut into that look's pictures, which are kept, so
 * drawing an icon is one {@code drawImage} of an image that already exists. The pictures kept are bounded by the pictures times the
 * sides times the looks.
 * <p>
 * The pictures are the Jagex sprites of the map icons, bundled like the icons copied out of RuneLite (see NOTICE).
 */
@Slf4j
public final class GameIconSprites
{
	/** Side of every picture in the game, in pixels, which the zoom scales from. */
	public static final int SIZE = 15;

	/** Looks there are, by {@link GameIconLayer}'s numbers. */
	private static final int LOOKS = 6;

	private final int count;
	/** [look][side - smallest side]: the pictures, cut when first asked for. */
	private final BufferedImage[][][] cut = new BufferedImage[LOOKS][GameIconScale.MAX_SIDE - GameIconScale.MIN_SIDE + 1][];

	private GameIconSprites(int count)
	{
		this.count = count;
	}

	/**
	 * The bundled pictures, as many as the data says there are; a picture the sheets have no room for is answered with null. The plain
	 * pictures are read now, so the first frame that draws icons reads no file.
	 */
	public static GameIconSprites load(int count)
	{
		GameIconSprites sprites = new GameIconSprites(Math.max(0, count));
		for (int side = GameIconScale.MIN_SIDE; side <= GameIconScale.MAX_SIDE; side++)
		{
			sprites.get(0, side, 0);
		}
		return sprites;
	}

	/** How many pictures there are. */
	public int size()
	{
		return count;
	}

	/**
	 * The picture drawn at a side in pixels, in a look (0 plain, 1 locked, 3 to 5 a quest not started, started and finished), or null
	 * when there is none. Asking for the same picture, side and look again gives the same image.
	 */
	@Nullable
	public synchronized BufferedImage get(int index, int side, int look)
	{
		if (index < 0 || index >= count || side < GameIconScale.MIN_SIDE || side > GameIconScale.MAX_SIDE || look < 0 || look >= LOOKS)
		{
			return null;
		}
		BufferedImage[] found = cut[look][side - GameIconScale.MIN_SIDE];
		if (found == null)
		{
			found = cut(side, look);
			cut[look][side - GameIconScale.MIN_SIDE] = found;
		}
		return found[index];
	}

	private BufferedImage[] cut(int side, int look)
	{
		BufferedImage[] out = new BufferedImage[count];
		BufferedImage sheet = null;
		try (InputStream in = GameIconSprites.class.getResourceAsStream("/com/worldmapplus/gameicons/" + side + ".png"))
		{
			sheet = in == null ? null : readInMemory(in);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("World Map Plus: could not read the icon sheet of side {}", side, e);
		}
		for (int i = 0; sheet != null && i < count && (i + 1) * side <= sheet.getHeight() && (look + 1) * side <= sheet.getWidth(); i++)
		{
			out[i] = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
			out[i].setRGB(0, 0, side, side, sheet.getRGB(look * side, i * side, side, side, null, 0, side), 0, side);
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
