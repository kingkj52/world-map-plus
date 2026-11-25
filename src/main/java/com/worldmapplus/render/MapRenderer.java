package com.worldmapplus.render;

import com.worldmapplus.map.*;
import com.worldmapplus.tiles.*;
import com.worldmapplus.view.*;
import java.awt.*;
import java.awt.image.*;
import lombok.*;

/**
 * Draws the map imagery for one view.
 * <p>
 * Tiles are shown at the nearest of their mip levels that is not smaller than needed, so a tile is
 * only ever shrunk by less than a factor of two on screen and stays sharp. Past the point where
 * individual tiles are too small to bother with, the whole-map overview picture is drawn instead,
 * which turns the world-sized view from thousands of tiles into one image.
 * <p>
 * A tile that is not loaded yet is never left as a hole when something can stand in for it: first a copy of the same tile at
 * another size, if one is in memory, and otherwise the matching part of the whole-map picture, enlarged. Both are drawn
 * only until the real tile arrives and never over one that is ready, so a view that has settled is exactly the tiles. Below
 * half a pixel per tile the whole-map picture is the view, and from there to one pixel it is drawn under the tiles.
 */
public final class MapRenderer
{
	/** Colour behind the map and under squares that have no tile. */
	public static final Color BACKGROUND = new Color(0x15171c);

	/** Below this scale the overview picture is used instead of tiles. */
	static final double OVERVIEW_BELOW = 0.5;

	/**
	 * The wash the ground gets under a floor: the ground faintly, in grey, as the wiki draws it. The
	 * floor's own tiles are drawn over it.
	 */
	static final Color WASH = new Color(59, 61, 66, 209);

	/** Native scale of a tile image: 256 pixels for 64 tiles. */
	private static final double NATIVE = 4.0;

	private final TileCache tiles;
	/** True when previews are enlarged smoothly. Part of what decides a preview's pixels. */
	@Getter
	private boolean smoothMagnify;

	public MapRenderer(TileCache tiles)
	{
		this.tiles = tiles;
	}

	/** Smooth, rather than pixel-crisp, enlargement past the tiles' native size. */
	public void setSmoothMagnify(boolean smoothMagnify)
	{
		this.smoothMagnify = smoothMagnify;
	}

	/** The mip level that suits a scale: 0 at or above native, one more for every halving below. */
	static int mipFor(double pixelsPerTile)
	{
		if (pixelsPerTile >= NATIVE)
		{
			return 0;
		}
		double level = Math.log(NATIVE / pixelsPerTile) / Math.log(2.0);
		return Math.max(0, Math.min(TileCache.MAX_MIP, (int) Math.floor(level)));
	}

	public void paint(Graphics2D g, ViewTransform view, MapInfo map)
	{
		paint(g, view, map, 0);
	}

	/** Draws a map as it is on a floor. Floor 0 is the ground. A floor with no tiles is left empty. */
	public void paint(Graphics2D g, ViewTransform view, MapInfo map, int floor)
	{
		paint(g, view, map, floor, true);
	}

	/**
	 * Draws a small picture of a map that is not the main view, such as the one on a hover card. It asks for its tiles like any
	 * other view but does not count as the view the tiles around are fetched for. Returns true when every square is final (no tile
	 * still loading and no stand-in used), so the same picture can be kept and drawn again.
	 */
	public boolean paintPreview(Graphics2D g, ViewTransform view, MapInfo map)
	{
		int[] pending = STAND_INS.get();
		pending[0] = 0;
		paint(g, view, map, 0, false);
		return pending[0] == 0;
	}

	/**
	 * How many squares of the picture just drawn on this thread are not final: a tile still loading, or the whole-map picture still
	 * to come. Per thread, since windows draw on their own thread. Zero means drawing it again gives the same pixels.
	 */
	private static final ThreadLocal<int[]> STAND_INS = ThreadLocal.withInitial(() -> new int[1]);

	private void paint(Graphics2D g, ViewTransform view, MapInfo map, int floor, boolean mainView)
	{
		Shape oldClip = g.getClip();
		Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.clipRect(view.getLeft(), view.getTop(), view.getWidth(), view.getHeight());
		try
		{
			g.setColor(BACKGROUND);
			g.fillRect(view.getLeft(), view.getTop(), view.getWidth(), view.getHeight());
			if (map == null)
			{
				return;
			}

			// The middle of the view in map squares, so the tiles still to load are fetched from there outwards.
			tiles.beginFrame((view.minWorldX() + view.maxWorldX()) / (2.0 * TileSource.TILE_SIZE),
				(view.minWorldY() + view.maxWorldY()) / (2.0 * TileSource.TILE_SIZE));
			int source = TileSource.tileMapId(map.getId(), floor);
			double scale = view.getPixelsPerTile();

			// The whole-map picture is asked for at every scale, so it is there to stand in for tiles still loading. Below one
			// pixel per tile it is part of the picture and the view waits for it. On a floor it is the floor's own picture, and
			// above one pixel per tile only the ground's picture is used, if it is already there, for the ground under the floor.
			BufferedImage overview;
			if (scale < OVERVIEW_BELOW * 2)
			{
				overview = tiles.getOverview(source);
			}
			else
			{
				overview = source == map.getId() ? tiles.getOverview(source, false) : null;
			}
			BufferedImage groundPicture = source == map.getId() ? overview : tiles.peekOverview(map.getId());

			boolean underlay = overview != null && scale < OVERVIEW_BELOW * 2;
			if (underlay)
			{
				drawOverview(g, view, map, overview);
			}
			boolean waitingForOverview = overview == null && scale < OVERVIEW_BELOW
				&& tiles.isOverviewPending(source);
			if (waitingForOverview)
			{
				STAND_INS.get()[0]++;
			}
			if (!waitingForOverview && (scale >= OVERVIEW_BELOW || overview == null))
			{
				drawTiles(g, view, map, floor, source, scale, underlay ? null : groundPicture, mainView);
			}
			String status = mainView ? tiles.getSource().status() : null;
			if (status != null)
			{
				drawStatus(g, view, status);
			}
		}
		finally
		{
			g.setClip(oldClip);
			if (oldInterpolation != null)
			{
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
			}
		}
	}

	/** A small line in the lower left corner while map pictures are downloading or could not be: no popup, nothing to click. */
	private static void drawStatus(Graphics2D g, ViewTransform view, String text)
	{
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
		int x = view.getLeft() + 8;
		int y = view.getTop() + view.getHeight() - 8;
		g.setColor(new Color(0, 0, 0, 170));
		g.drawString(text, x + 1, y + 1);
		g.setColor(new Color(235, 235, 235, 220));
		g.drawString(text, x, y);
	}

	private void drawOverview(Graphics2D g, ViewTransform view, MapInfo map, BufferedImage image)
	{
		int left = map.firstSquareX() * TileSource.TILE_SIZE;
		int right = (map.lastSquareX() + 1) * TileSource.TILE_SIZE;
		int bottom = map.firstSquareY() * TileSource.TILE_SIZE;
		int top = (map.lastSquareY() + 1) * TileSource.TILE_SIZE;

		int dx = (int) Math.round(view.screenX(left));
		int dy = (int) Math.round(view.screenY(top));
		int dw = (int) Math.round(view.screenX(right)) - dx;
		int dh = (int) Math.round(view.screenY(bottom)) - dy;
		if (dw <= 0 || dh <= 0)
		{
			return;
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, dx, dy, dw, dh, null);
	}

	private void drawTiles(Graphics2D g, ViewTransform view, MapInfo map, int floor, int source, double scale, BufferedImage groundPicture,
		boolean mainView)
	{
		int mip = mipFor(scale);
		int firstX = Math.max(map.firstSquareX(), (int) Math.floor(view.minWorldX() / TileSource.TILE_SIZE));
		int lastX = Math.min(map.lastSquareX(), (int) Math.floor(view.maxWorldX() / TileSource.TILE_SIZE));
		int firstY = Math.max(map.firstSquareY(), (int) Math.floor(view.minWorldY() / TileSource.TILE_SIZE));
		int lastY = Math.min(map.lastSquareY(), (int) Math.floor(view.maxWorldY() / TileSource.TILE_SIZE));
		if (mainView)
		{
			tiles.noteView(source, map.getId(), mip, firstX, lastX, firstY, lastY);
		}

		int imageSide = TileSource.TILE_PIXELS >> mip;
		boolean magnifying = scale * TileSource.TILE_SIZE > imageSide;
		Object hint = magnifying && !smoothMagnify
			? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
			: RenderingHints.VALUE_INTERPOLATION_BILINEAR;
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);

		for (int x = firstX; x <= lastX; x++)
		{
			int dx = (int) Math.round(view.screenX(x * TileSource.TILE_SIZE));
			int dw = (int) Math.round(view.screenX((x + 1) * TileSource.TILE_SIZE)) - dx;
			for (int y = firstY; y <= lastY; y++)
			{
				int dy = (int) Math.round(view.screenY((y + 1) * TileSource.TILE_SIZE));
				int dh = (int) Math.round(view.screenY(y * TileSource.TILE_SIZE)) - dy;
				if (floor > 0 && drawTile(g, map, map.getId(), x, y, mip, dx, dy, dw, dh, groundPicture, hint))
				{
					g.setColor(WASH);
					g.fillRect(dx, dy, dw, dh);
				}
				drawTile(g, map, source, x, y, mip, dx, dy, dw, dh, floor > 0 ? null : groundPicture, hint);
			}
		}
	}

	/**
	 * Draws one square: the tile if it is ready, and if it is not, but is on its way, a stand-in for it. Returns true when
	 * something was drawn.
	 */
	private boolean drawTile(Graphics2D g, MapInfo map, int id, int x, int y, int mip, int dx, int dy, int dw, int dh,
		BufferedImage picture, Object hint)
	{
		BufferedImage image = tiles.get(id, x, y, mip);
		if (image != null)
		{
			g.drawImage(image, dx, dy, dw, dh, null);
			return true;
		}
		if (!tiles.isLoading(id, x, y))
		{
			return false;
		}
		STAND_INS.get()[0]++;
		BufferedImage near = tiles.substitute(id, x, y, mip);
		if (near == null && picture == null)
		{
			return false;
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		if (near != null)
		{
			g.drawImage(near, dx, dy, dw, dh, null);
		}
		else
		{
			drawPatch(g, map, picture, x, y, dx, dy, dw, dh);
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
		return true;
	}

	/** Draws the part of the whole-map picture that covers a square, stretched to the square's place on screen. */
	private static void drawPatch(Graphics2D g, MapInfo map, BufferedImage picture, int x, int y, int dx, int dy, int dw, int dh)
	{
		if (dw <= 0 || dh <= 0)
		{
			return;
		}
		int squaresX = map.lastSquareX() - map.firstSquareX() + 1;
		int squaresY = map.lastSquareY() - map.firstSquareY() + 1;
		int column = x - map.firstSquareX();
		// The picture's top row is the northern edge of the map.
		int row = map.lastSquareY() - y;
		int sx0 = column * picture.getWidth() / squaresX;
		int sx1 = (column + 1) * picture.getWidth() / squaresX;
		int sy0 = row * picture.getHeight() / squaresY;
		int sy1 = (row + 1) * picture.getHeight() / squaresY;
		if (sx1 <= sx0 || sy1 <= sy0)
		{
			return;
		}
		g.drawImage(picture, dx, dy, dx + dw, dy + dh, sx0, sy0, sx1, sy1, null);
	}
}
