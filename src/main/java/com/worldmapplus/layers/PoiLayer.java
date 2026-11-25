package com.worldmapplus.layers;

import com.worldmapplus.map.*;
import com.worldmapplus.view.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;

/**
 * Draws the points of interest of the layers that are switched on.
 * <p>
 * Everything drawn here is fixed reference data from the plugin, placed by coordinates. Nothing is
 * read from the game. Points have an icon of their own when RuneLite has one, and a lettered disc
 * otherwise. A point that sits on an icon the game draws is not drawn here (it is merged into that
 * icon, whose card says what the point says), so only the points the game draws nothing for show.
 */
public final class PoiLayer
{
	/** Below this zoom no point is drawn: the map does not go there. */
	public static final double MIN_PIXELS_PER_TILE = GameIconScale.HIDE_ZOOM;

	/** The categories whose points are drawn first, where a crowd is thinned: the ways to travel. */
	private static final String FIRST_CATEGORY = "teleport";

	private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

	private final HotspotIndex index;
	private final PoiIcons icons;
	private final Map<String, PoiCategory> categories;

	public PoiLayer(HotspotIndex index, PoiIcons icons, Map<String, PoiCategory> categories)
	{
		this.index = index;
		this.icons = icons;
		this.categories = categories;
	}

	/** True when a category can be drawn. A saved setting that names a search-only kind is ignored. */
	public boolean isLayer(String category)
	{
		PoiCategory found = categories.get(category);
		return found != null && found.isLayer();
	}

	/** True when this zoom shows points at all. */
	public static boolean visibleAt(double pixelsPerTile)
	{
		return pixelsPerTile >= MIN_PIXELS_PER_TILE;
	}

	/** What one frame works with, kept from frame to frame so a frame makes no lists or tables. Guarded by the layer's own lock. */
	private final List<Hotspot> seen = new ArrayList<>();
	private final List<Hotspot> shown = new ArrayList<>();
	private long[] cellKeys = new long[256];
	private int[] cellWinner = new int[256];
	private final Ellipse2D.Double disc = new Ellipse2D.Double();
	private static final BasicStroke RIM = new BasicStroke(1.5f);

	/**
	 * Draws the layers in {@code enabled}. Synchronized, because the layer is shared by every map and keeps its working space between
	 * frames; a map in a window paints on another thread than the main map.
	 */
	public synchronized void paint(Graphics2D g, ViewTransform view, MapInfo map, Set<String> enabled)
	{
		if (map == null || enabled.isEmpty() || !visibleAt(view.getPixelsPerTile()))
		{
			return;
		}
		double zoom = view.getPixelsPerTile();
		int side = GameIconScale.drawnSize(zoom, GameIconSprites.SIZE);
		int cellTiles = GameIconScale.cellTiles(zoom, side);
		// The points a cell of the world holds are all looked at when the cell touches the view, so which one a cell keeps does not
		// depend on where the view is, and nothing flickers as the map is moved.
		ViewTransform wide = view;
		if (cellTiles > 0)
		{
			int margin = (int) Math.ceil(cellTiles * zoom) + side;
			wide = new ViewTransform(view.getCenterX(), view.getCenterY(), zoom, view.getLeft() - margin, view.getTop() - margin,
				view.getWidth() + 2 * margin, view.getHeight() + 2 * margin);
		}
		seen.clear();
		shown.clear();
		index.visible(map.getId(), wide, seen);
		for (int i = 0; i < seen.size(); i++)
		{
			Hotspot hotspot = seen.get(i);
			if (hotspot.getKind() == Hotspot.Kind.POI && !hotspot.isMerged() && enabled.contains(hotspot.getCategory())
				&& isLayer(hotspot.getCategory()))
			{
				shown.add(hotspot);
			}
		}
		int mask = cellTiles > 0 ? pickWinners(cellTiles) : 0;
		g.setFont(FONT);
		FontMetrics metrics = g.getFontMetrics();
		Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		try
		{
			for (int i = 0; i < shown.size(); i++)
			{
				Hotspot hotspot = shown.get(i);
				if (cellTiles > 0 && cellWinner[slotOf(cellOf(hotspot, cellTiles), mask)] != i)
				{
					continue;
				}
				double sx = view.screenX(hotspot.getX());
				double sy = view.screenY(hotspot.getY());
				if (sx < view.getLeft() - side || sy < view.getTop() - side || sx > view.getLeft() + view.getWidth() + side
					|| sy > view.getTop() + view.getHeight() + side)
				{
					continue;
				}
				BufferedImage icon = icons.get(hotspot.getIcon());
				if (icon != null)
				{
					int w = side;
					int h = Math.max(1, (int) Math.round(side * icon.getHeight() / (double) Math.max(1, icon.getWidth())));
					g.drawImage(icon, (int) Math.round(sx - w / 2.0), (int) Math.round(sy - h / 2.0), w, h, null);
					continue;
				}
				PoiCategory category = categories.get(hotspot.getCategory());
				Color color = category == null ? Color.GRAY : category.getColor();
				disc.setFrame(sx - side / 2.0, sy - side / 2.0, side, side);
				g.setColor(color);
				g.fill(disc);
				g.setColor(Color.WHITE);
				g.setStroke(RIM);
				g.draw(disc);
				if (category != null)
				{
					String glyph = category.getGlyph();
					g.drawString(glyph, (float) (sx - metrics.stringWidth(glyph) / 2.0),
						(float) (sy + (metrics.getAscent() - metrics.getDescent()) / 2.0));
				}
			}
		}
		finally
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				oldInterpolation == null ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : oldInterpolation);
		}
	}

	/**
	 * Fills the table that says which point each cell of the world keeps, in the order the points were found, as
	 * {@link #better} chooses between two in one cell. Returns the mask for {@link #slotOf}. Needs the lock.
	 */
	private int pickWinners(int cellTiles)
	{
		int capacity = 16;
		while (capacity < shown.size() * 2)
		{
			capacity <<= 1;
		}
		if (cellKeys.length < capacity)
		{
			cellKeys = new long[capacity];
			cellWinner = new int[capacity];
		}
		Arrays.fill(cellWinner, 0, capacity, -1);
		int mask = capacity - 1;
		for (int i = 0; i < shown.size(); i++)
		{
			Hotspot hotspot = shown.get(i);
			long key = cellOf(hotspot, cellTiles);
			int slot = slotOf(key, mask);
			int kept = cellWinner[slot];
			if (kept < 0)
			{
				cellKeys[slot] = key;
				cellWinner[slot] = i;
			}
			else if (better(shown.get(kept), hotspot) == hotspot)
			{
				cellWinner[slot] = i;
			}
		}
		return mask;
	}

	/** The place in the table of a cell: where it is, or the empty place it would go to. */
	private int slotOf(long key, int mask)
	{
		int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
		while (cellWinner[slot] >= 0 && cellKeys[slot] != key)
		{
			slot = (slot + 1) & mask;
		}
		return slot;
	}

	private static long cellOf(Hotspot hotspot, int cellTiles)
	{
		long cx = (long) Math.floor(hotspot.getX() / cellTiles);
		long cy = (long) Math.floor(hotspot.getY() / cellTiles);
		return (cx << 32) ^ (cy & 0xFFFFFFFFL);
	}

	/** Of two points in one cell, the one kept: a way to travel first, then the one most to the north and west, so the choice never varies. */
	private static Hotspot better(Hotspot a, Hotspot b)
	{
		boolean aFirst = FIRST_CATEGORY.equals(a.getCategory());
		boolean bFirst = FIRST_CATEGORY.equals(b.getCategory());
		if (aFirst != bFirst)
		{
			return aFirst ? a : b;
		}
		int byY = Double.compare(a.getY(), b.getY());
		if (byY != 0)
		{
			return byY < 0 ? a : b;
		}
		return Double.compare(a.getX(), b.getX()) <= 0 ? a : b;
	}
}
