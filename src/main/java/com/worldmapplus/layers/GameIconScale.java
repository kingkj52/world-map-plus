package com.worldmapplus.layers;

/**
 * How big an icon of the game's map is drawn at a zoom, how much room it keeps to itself, and how big its hit area is.
 * <p>
 * Icons are drawn as big as the plugin's other round icons, such as the sailing ones: {@link #MAX_SIDE} pixels from
 * {@link #FULL_ZOOM} up. Farther out they shrink gently, steady in the logarithm of the zoom, to {@link #MIN_SIDE} at
 * {@link #SMALL_ZOOM} and never below, so at every zoom the map goes to an icon is a picture a player can read. A crowd is thinned
 * by {@link GameIconLayer}, not by making the pictures smaller. The size is a whole number of pixels, so one size means one
 * picture and the cache of scaled pictures stays small.
 * <p>
 * Pure functions of the zoom, so the drawing and the hit test cannot disagree.
 */
public final class GameIconScale
{
	/** The side, in pixels, of an icon from {@link #FULL_ZOOM} up. */
	public static final int MAX_SIDE = 20;

	/** The side, in pixels, of an icon at {@link #SMALL_ZOOM} and below. */
	public static final int MIN_SIDE = 18;

	/** From this many pixels to the tile up an icon is drawn at {@link #MAX_SIDE}. */
	public static final double FULL_ZOOM = 1.0;

	/** From this zoom down an icon is drawn at {@link #MIN_SIDE}. */
	public static final double SMALL_ZOOM = 0.2;

	/** The zoom below which no icon is drawn. The map does not go below 0.05, so every view shows icons. */
	public static final double HIDE_ZOOM = 0.04;

	/** From this zoom up an icon may overlap its neighbours as the game's map lets it, so nothing is thinned out. */
	public static final double CROWD_ZOOM = 4.0;

	/** From this zoom down an icon keeps most of its picture to itself. */
	public static final double ROOMY_ZOOM = 1.0;

	/** The share of its side an icon keeps to itself against its neighbours at {@link #ROOMY_ZOOM} and below. */
	public static final double ROOMY_SHARE = 0.75;

	/** The share it keeps just below {@link #CROWD_ZOOM}. */
	public static final double CROWDED_SHARE = 0.35;

	/** An icon of a category with a priority of this much or more is a minor one: it fades out as the map zooms out. */
	public static final int MINOR_PRIORITY = 100;

	/** Minor icons are all gone at this zoom and below. */
	public static final double MINOR_NONE_ZOOM = 0.05;

	/** Minor icons are all drawn from this zoom up. */
	public static final double MINOR_ALL_ZOOM = 0.5;

	/** How far past the drawn picture the hit area reaches on every side, in pixels. */
	public static final int HIT_MARGIN = 2;

	/** The smallest side of a hit area, in pixels, so an icon can still be pointed at. */
	public static final int MIN_HIT_SIZE = 12;

	private GameIconScale()
	{
	}

	/** True when icons are drawn at this zoom. */
	public static boolean shown(double pixelsPerTile)
	{
		return pixelsPerTile >= HIDE_ZOOM;
	}

	/** The side an icon is drawn at, in pixels, as a real number: {@link #MAX_SIDE} from {@link #FULL_ZOOM} up, {@link #MIN_SIDE} at and below {@link #SMALL_ZOOM}. */
	public static double side(double pixelsPerTile)
	{
		if (!(pixelsPerTile < FULL_ZOOM))
		{
			return MAX_SIDE;
		}
		if (!(pixelsPerTile > SMALL_ZOOM))
		{
			return MIN_SIDE;
		}
		double t = Math.log(FULL_ZOOM / pixelsPerTile) / Math.log(FULL_ZOOM / SMALL_ZOOM);
		return MAX_SIDE - (MAX_SIDE - MIN_SIDE) * t;
	}

	/** The share of a picture's native size an icon is drawn at. Never decreases as the zoom grows. */
	public static double scale(double pixelsPerTile)
	{
		return side(pixelsPerTile) / GameIconSprites.SIZE;
	}

	/** The side, in pixels, a picture of a native size is drawn at. 0 when no picture is drawn at this zoom. */
	public static int pictureSize(double pixelsPerTile, int nativeSize)
	{
		if (!shown(pixelsPerTile))
		{
			return 0;
		}
		return Math.max(1, (int) Math.round(nativeSize * scale(pixelsPerTile)));
	}

	/** The side of what is drawn for an icon, or 0 for nothing. */
	public static int drawnSize(double pixelsPerTile, int nativeSize)
	{
		return pictureSize(pixelsPerTile, nativeSize);
	}

	/**
	 * The side of the square an icon keeps to itself against its neighbours when a crowd is thinned, in pixels: {@link #ROOMY_SHARE}
	 * of the drawn side from {@link #ROOMY_ZOOM} down, falling steady in the logarithm of the zoom to {@link #CROWDED_SHARE} just
	 * under {@link #CROWD_ZOOM}, and 0 (no thinning) from there up.
	 */
	public static int footprint(double pixelsPerTile, int drawnSize)
	{
		if (!(pixelsPerTile < CROWD_ZOOM))
		{
			return 0;
		}
		double t = pixelsPerTile <= ROOMY_ZOOM ? 0.0 : Math.log(pixelsPerTile / ROOMY_ZOOM) / Math.log(CROWD_ZOOM / ROOMY_ZOOM);
		double share = ROOMY_SHARE - (ROOMY_SHARE - CROWDED_SHARE) * t;
		int side = (int) Math.round(drawnSize * share);
		return side < MIN_FOOTPRINT ? 0 : side;
	}

	/**
	 * The side of a cell of the world, in tiles, for thinning a crowd at a zoom: the smallest whole number of doublings of a tile that is
	 * at least a footprint wide on the screen. 0 when nothing is thinned at this zoom.
	 */
	public static int cellTiles(double pixelsPerTile, int drawnSize)
	{
		int footprint = footprint(pixelsPerTile, drawnSize);
		if (footprint <= 0)
		{
			return 0;
		}
		double need = footprint / pixelsPerTile;
		return need <= 1.0 ? 1 : 1 << (int) Math.ceil(Math.log(need) / Math.log(2) - 1e-9);
	}

	/** A footprint smaller than this is not worth thinning for: the pictures overlap almost completely anyway. */
	static final int MIN_FOOTPRINT = 4;

	/**
	 * The share of the minor icons that are drawn at a zoom: all from {@link #MINOR_ALL_ZOOM} up, none at and below
	 * {@link #MINOR_NONE_ZOOM}, and in between a share that grows steady in the logarithm of the zoom. Each minor icon has a fixed
	 * place in a fixed order, so the same ones stay as the zoom falls and nothing comes and goes by chance.
	 */
	public static double minorShare(double pixelsPerTile)
	{
		if (!(pixelsPerTile > MINOR_NONE_ZOOM))
		{
			return 0.0;
		}
		if (!(pixelsPerTile < MINOR_ALL_ZOOM))
		{
			return 1.0;
		}
		return Math.log(pixelsPerTile / MINOR_NONE_ZOOM) / Math.log(MINOR_ALL_ZOOM / MINOR_NONE_ZOOM);
	}

	/** The side of the hit area of an icon drawn at this side: the drawn side and the margin both ways, and at least the least. */
	public static int hitSize(int drawnSize)
	{
		return Math.max(MIN_HIT_SIZE, drawnSize + 2 * HIT_MARGIN);
	}
}
