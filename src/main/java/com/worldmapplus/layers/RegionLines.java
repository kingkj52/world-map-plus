package com.worldmapplus.layers;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.worldmapplus.view.ViewTransform;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The regions of the world as the game's lore knows them, as a tint, a border and a name each.
 * <p>
 * The data is a bundled table of which 64 tile square belongs to which region, so the borders follow
 * those squares. Everything is drawn from coordinates and nothing is read from the game.
 */
@Slf4j
public final class RegionLines
{
	private static final String RESOURCE = "/com/worldmapplus/regions.json";

	/** Size of a square of the table, in tiles. */
	public static final int SQUARE = 64;

	/** Names are drawn only while the map is zoomed out this far or more. */
	static final double LABEL_MAX_PIXELS_PER_TILE = 2.0;

	/** A separate stretch of a region needs at least this many squares to be named as well. */
	static final int LABEL_MIN_SQUARES = 6;

	private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font LABEL_FONT_FAR = new Font(Font.SANS_SERIF, Font.BOLD, 16);

	/** One region: its squares, the edges where it meets something else, and where its name goes. */
	public static final class Region
	{
		private final String id;
		private final String name;
		private final Color color;
		private final Set<Long> squares;
		private final List<double[]> edges = new ArrayList<>();
		private final List<double[]> labels = new ArrayList<>();

		Region(String id, String name, Color color, Set<Long> squares)
		{
			this.id = id;
			this.name = name;
			this.color = color;
			this.squares = squares;
		}

		public String getId()
		{
			return id;
		}

		public String getName()
		{
			return name;
		}

		public Color getColor()
		{
			return color;
		}

		public int squareCount()
		{
			return squares.size();
		}

		/** Centres, in world tiles, where the region's name is drawn. */
		public List<double[]> getLabels()
		{
			return labels;
		}

		/** The boundary as {x1, y1, x2, y2, inwardX, inwardY} in world tiles. */
		List<double[]> getEdges()
		{
			return edges;
		}
	}

	private final List<Region> regions;
	private final Map<Long, Region> byCell = new HashMap<>();

	public RegionLines(List<Region> regions)
	{
		this.regions = Collections.unmodifiableList(regions);
		for (Region region : regions)
		{
			for (long key : region.squares)
			{
				byCell.put(key, region);
			}
		}
		for (Region region : regions)
		{
			buildEdges(region);
			buildLabels(region);
		}
	}

	public static RegionLines empty()
	{
		return new RegionLines(new ArrayList<>());
	}

	public List<Region> getRegions()
	{
		return regions;
	}

	/** The region a tile is in, or null for sea and anywhere the table does not cover. */
	public Region regionAt(int x, int y)
	{
		return byCell.get(key(Math.floorDiv(x, SQUARE), Math.floorDiv(y, SQUARE)));
	}

	private static long key(int rx, int ry)
	{
		return ((long) rx << 32) | (ry & 0xFFFFFFFFL);
	}

	private static int keyX(long key)
	{
		return (int) (key >> 32);
	}

	private static int keyY(long key)
	{
		return (int) key;
	}

	private void buildEdges(Region region)
	{
		for (long cell : region.squares)
		{
			int rx = keyX(cell);
			int ry = keyY(cell);
			double x0 = rx * (double) SQUARE;
			double y0 = ry * (double) SQUARE;
			double x1 = x0 + SQUARE;
			double y1 = y0 + SQUARE;
			if (byCell.get(key(rx, ry - 1)) != region)
			{
				region.edges.add(new double[] {x0, y0, x1, y0, 0, 1});
			}
			if (byCell.get(key(rx, ry + 1)) != region)
			{
				region.edges.add(new double[] {x0, y1, x1, y1, 0, -1});
			}
			if (byCell.get(key(rx - 1, ry)) != region)
			{
				region.edges.add(new double[] {x0, y0, x0, y1, 1, 0});
			}
			if (byCell.get(key(rx + 1, ry)) != region)
			{
				region.edges.add(new double[] {x1, y0, x1, y1, -1, 0});
			}
		}
	}

	/** A name for the largest stretch of the region and for every other stretch that is big enough. */
	private void buildLabels(Region region)
	{
		Set<Long> unvisited = new HashSet<>(region.squares);
		List<List<Long>> stretches = new ArrayList<>();
		while (!unvisited.isEmpty())
		{
			long start = unvisited.iterator().next();
			List<Long> stretch = new ArrayList<>();
			Deque<Long> queue = new ArrayDeque<>();
			queue.add(start);
			unvisited.remove(start);
			while (!queue.isEmpty())
			{
				long cell = queue.poll();
				stretch.add(cell);
				int rx = keyX(cell);
				int ry = keyY(cell);
				long[] next = {key(rx + 1, ry), key(rx - 1, ry), key(rx, ry + 1), key(rx, ry - 1)};
				for (long n : next)
				{
					if (unvisited.remove(n))
					{
						queue.add(n);
					}
				}
			}
			stretches.add(stretch);
		}
		stretches.sort((a, b) -> Integer.compare(b.size(), a.size()));
		for (int i = 0; i < stretches.size(); i++)
		{
			List<Long> stretch = stretches.get(i);
			if (i > 0 && stretch.size() < LABEL_MIN_SQUARES)
			{
				break;
			}
			double sx = 0;
			double sy = 0;
			for (long cell : stretch)
			{
				sx += (keyX(cell) + 0.5) * SQUARE;
				sy += (keyY(cell) + 0.5) * SQUARE;
			}
			region.labels.add(new double[] {sx / stretch.size(), sy / stretch.size()});
		}
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	/** Tints the squares, draws the borders, and names the regions while the map is zoomed out. */
	public void paint(Graphics2D g, ViewTransform view)
	{
		double ppt = view.getPixelsPerTile();
		double size = SQUARE * ppt;
		double minX = view.minWorldX();
		double maxX = view.maxWorldX();
		double minY = view.minWorldY();
		double maxY = view.maxWorldY();

		for (Region region : regions)
		{
			Color tint = new Color(region.color.getRed(), region.color.getGreen(), region.color.getBlue(), 30);
			g.setColor(tint);
			for (long cell : region.squares)
			{
				double x0 = keyX(cell) * (double) SQUARE;
				double y0 = keyY(cell) * (double) SQUARE;
				if (x0 > maxX || x0 + SQUARE < minX || y0 > maxY || y0 + SQUARE < minY)
				{
					continue;
				}
				g.fillRect((int) Math.floor(view.screenX(x0)), (int) Math.floor(view.screenY(y0 + SQUARE)),
					(int) Math.ceil(size) + 1, (int) Math.ceil(size) + 1);
			}
		}

		float width = (float) Math.max(1.6, Math.min(3.2, ppt * 6));
		g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		double inset = width / 2.0;
		for (Region region : regions)
		{
			g.setColor(new Color(region.color.getRed(), region.color.getGreen(), region.color.getBlue(), 230));
			for (double[] e : region.edges)
			{
				if (Math.max(e[0], e[2]) < minX || Math.min(e[0], e[2]) > maxX
					|| Math.max(e[1], e[3]) < minY || Math.min(e[1], e[3]) > maxY)
				{
					continue;
				}
				double dx = e[4] * inset;
				double dy = -e[5] * inset;
				g.draw(new Line2D.Double(view.screenX(e[0]) + dx, view.screenY(e[1]) + dy,
					view.screenX(e[2]) + dx, view.screenY(e[3]) + dy));
			}
		}

		if (ppt <= LABEL_MAX_PIXELS_PER_TILE)
		{
			g.setFont(ppt < 0.5 ? LABEL_FONT_FAR : LABEL_FONT);
			FontMetrics metrics = g.getFontMetrics();
			for (Region region : regions)
			{
				for (double[] at : region.labels)
				{
					if (at[0] < minX || at[0] > maxX || at[1] < minY || at[1] > maxY)
					{
						continue;
					}
					String text = region.name.toUpperCase();
					int x = (int) Math.round(view.screenX(at[0]) - metrics.stringWidth(text) / 2.0);
					int y = (int) Math.round(view.screenY(at[1]) + metrics.getAscent() / 2.0 - 2);
					g.setColor(new Color(0, 0, 0, 170));
					g.drawString(text, x + 1, y + 1);
					g.setColor(brighter(region.color));
					g.drawString(text, x, y);
				}
			}
		}
	}

	/** A lighter shade of a region colour, so its name reads over the dark tint. */
	static Color brighter(Color color)
	{
		return new Color(Math.min(255, color.getRed() + 70), Math.min(255, color.getGreen() + 70),
			Math.min(255, color.getBlue() + 70));
	}

	// ------------------------------------------------------------------
	// Loading
	// ------------------------------------------------------------------

	public static RegionLines load(Gson gson)
	{
		try (InputStream in = RegionLines.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("World Map Plus: {} is missing from the plugin jar", RESOURCE);
				return empty();
			}
			return read(new InputStreamReader(in, StandardCharsets.UTF_8), gson);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("World Map Plus: could not read {}", RESOURCE, e);
			return empty();
		}
	}

	public static RegionLines read(Reader reader, Gson gson)
	{
		RegionFile file = gson.fromJson(reader, RegionFile.class);
		List<Region> regions = new ArrayList<>();
		if (file == null || file.regions == null)
		{
			return new RegionLines(regions);
		}
		Set<Long> taken = new HashSet<>();
		for (RegionRecord record : file.regions)
		{
			if (record == null || record.id == null || record.name == null || record.cells == null)
			{
				continue;
			}
			Set<Long> squares = new HashSet<>();
			for (int[] cell : record.cells)
			{
				if (cell != null && cell.length >= 2 && taken.add(key(cell[0], cell[1])))
				{
					squares.add(key(cell[0], cell[1]));
				}
			}
			if (!squares.isEmpty())
			{
				regions.add(new Region(record.id, record.name, PoiCategory.parseColor(record.color), squares));
			}
		}
		return new RegionLines(regions);
	}

	static final class RegionFile
	{
		List<RegionRecord> regions;
	}

	static final class RegionRecord
	{
		String id;
		String name;
		String color;
		List<int[]> cells;
	}
}
