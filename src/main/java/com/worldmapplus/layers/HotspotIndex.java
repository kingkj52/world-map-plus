package com.worldmapplus.layers;

import com.worldmapplus.view.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;

/**
 * Finds the hotspot under the mouse without testing all of them.
 * <p>
 * Hotspots are bucketed by map and by a coarse grid of world tiles. A lookup converts the pixel
 * radius to tiles at the current zoom, so the grid cells to search are the few that radius reaches.
 * When several hotspots are in range the nearest to the mouse wins.
 */
public final class HotspotIndex
{
	private static final int CELL = 32;

	private final Map<Long, List<Hotspot>> cells = new HashMap<>();
	private int count;

	public HotspotIndex(List<Hotspot> hotspots)
	{
		for (Hotspot hotspot : hotspots)
		{
			long key = cellKey(hotspot.getMapId(), cell(hotspot.getX()), cell(hotspot.getY()));
			cells.computeIfAbsent(key, k -> new ArrayList<>()).add(hotspot);
			count++;
		}
	}

	public static HotspotIndex empty()
	{
		return new HotspotIndex(Collections.emptyList());
	}

	public int size()
	{
		return count;
	}

	/** Every hotspot, for checks of what the map covers. */
	public List<Hotspot> all()
	{
		List<Hotspot> result = new ArrayList<>(count);
		for (List<Hotspot> bucket : cells.values())
		{
			result.addAll(bucket);
		}
		return result;
	}

	private static int cell(double world)
	{
		return (int) Math.floor(world / CELL);
	}

	private static long cellKey(int mapId, int cx, int cy)
	{
		return ((long) (mapId & 0xFFFF) << 40) | ((long) (cx & 0xFFFFF) << 20) | (long) (cy & 0xFFFFF);
	}

	/** The nearest hotspot to a pixel that is within its own radius, or null. */
	@Nullable
	public Hotspot find(int mapId, ViewTransform view, double pixelX, double pixelY)
	{
		return find(mapId, view, pixelX, pixelY, h -> true);
	}

	/** Every hotspot of a map whose cell touches the view. A few cells beyond the edge are included. */
	public List<Hotspot> visible(int mapId, ViewTransform view)
	{
		List<Hotspot> result = new ArrayList<>();
		visible(mapId, view, result);
		return result;
	}

	/** As {@link #visible(int, ViewTransform)}, added to a list the caller keeps, so a frame makes no list of its own. */
	public void visible(int mapId, ViewTransform view, List<Hotspot> result)
	{
		int minX = cell(view.minWorldX()) - 1;
		int maxX = cell(view.maxWorldX()) + 1;
		int minY = cell(view.minWorldY()) - 1;
		int maxY = cell(view.maxWorldY()) + 1;
		for (int cx = minX; cx <= maxX; cx++)
		{
			for (int cy = minY; cy <= maxY; cy++)
			{
				List<Hotspot> bucket = cells.get(cellKey(mapId, cx, cy));
				if (bucket != null)
				{
					result.addAll(bucket);
				}
			}
		}
	}

	/** Like {@link #find(int, ViewTransform, double, double)}, considering only hotspots the filter accepts. */
	@Nullable
	public Hotspot find(int mapId, ViewTransform view, double pixelX, double pixelY, Predicate<Hotspot> filter)
	{
		double scale = view.getPixelsPerTile();
		double worldX = view.worldX(pixelX);
		double worldY = view.worldY(pixelY);

		// The widest radius any hotspot can have is bounded, so search that far in tiles.
		double reachTiles = MAX_RADIUS_PIXELS / scale;
		int minX = cell(worldX - reachTiles);
		int maxX = cell(worldX + reachTiles);
		int minY = cell(worldY - reachTiles);
		int maxY = cell(worldY + reachTiles);

		Hotspot best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int cx = minX; cx <= maxX; cx++)
		{
			for (int cy = minY; cy <= maxY; cy++)
			{
				List<Hotspot> bucket = cells.get(cellKey(mapId, cx, cy));
				if (bucket == null)
				{
					continue;
				}
				for (Hotspot hotspot : bucket)
				{
					if (!filter.test(hotspot))
					{
						continue;
					}
					double dx = view.screenX(hotspot.getX()) - pixelX;
					double dy = view.screenY(hotspot.getY()) - pixelY;
					double distance = Math.hypot(dx, dy);
					if (distance <= Math.min(radiusAt(hotspot, scale), MAX_RADIUS_PIXELS) && distance < bestDistance)
					{
						bestDistance = distance;
						best = hotspot;
					}
				}
			}
		}
		return best;
	}

	/**
	 * Pixels from the middle of an icon's disc to its rim at 4 pixels to the tile, where the picture draws the disc 15 pixels across.
	 * Only the wiki's own extra maps (ids from 10000) still have discs in their pictures.
	 */
	private static final double DISC_RADIUS_AT_4 = 7.5;

	/**
	 * How near the mouse must be to a hotspot, in pixels, at a zoom. A card on an icon that the tile picture draws (the wiki's extra
	 * maps, whose discs are in the pictures) answers where the disc is, and the disc is as big on screen as the picture makes it: 15
	 * pixels across at 4 pixels to the tile and in proportion at other zooms. So its radius is the disc's and a couple of pixels more,
	 * not a fixed number that is a small part of the disc when zoomed in and bigger than it when zoomed out. Everything else, the icons
	 * the plugin draws itself included, keeps the radius it was made with.
	 */
	static double radiusAt(Hotspot hotspot, double pixelsPerTile)
	{
		if (!hotspot.isMapIcon() || hotspot.isDrawn())
		{
			return hotspot.getRadiusPixels();
		}
		return Math.max(6, DISC_RADIUS_AT_4 * pixelsPerTile / 4.0 + 2);
	}

	/** No hotspot may ask for more than this, which keeps lookups to a few cells. */
	static final int MAX_RADIUS_PIXELS = 40;
}
