package com.worldmapplus.layers;

import com.worldmapplus.unlock.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * Every icon the game's own list puts on the maps, indexed by place so a view finds what is on screen without testing them all.
 * <p>
 * Built once from the bundled data. Each map has a grid of {@link #CELL} tile cells and its icons stored in plain arrays in cell
 * order, so a lookup is a few cell ranges and never allocates, and the whole set is a handful of arrays (a few hundred kilobytes
 * at most for the game's four thousand icons). The set is immutable.
 */
public final class GameIconSet
{
	/** Side of a grid cell, in tiles. */
	public static final int CELL = 32;

	/** An icon before it is indexed. */
	public static final class Spec
	{
		@Getter
		private final int mapId;
		@Getter
		private final double x;
		@Getter
		private final double y;
		@Getter
		private final int picture;
		@Getter
		private final int floor;
		@Getter
		private final int category;
		@Getter
		private final int priority;
		private final Hotspot card;
		private final List<IconExtra> extras;
		private final Need need;

		/**
		 * @param picture the number of the picture in the atlas, from 0
		 * @param floor the floor of the map the icon belongs to
		 * @param category the number of the icon's category in the data
		 * @param priority 0 for icons that stay when there is no room for all of them, 1 for the rest
		 * @param card the hotspot that answers for the icon, or null when the plugin has nothing to say about it
		 */
		public Spec(int mapId, double x, double y, int picture, int floor, int category, int priority, @Nullable Hotspot card)
		{
			this(mapId, x, y, picture, floor, category, priority, card, null);
		}

		/** As above, with what the plugin's points of interest merged into the icon add to its card. */
		public Spec(int mapId, double x, double y, int picture, int floor, int category, int priority, @Nullable Hotspot card,
			@Nullable List<IconExtra> extras)
		{
			this(mapId, x, y, picture, floor, category, priority, card, extras, null);
		}

		/** As above, with what the icon asks of the player, or null when it asks nothing the plugin can judge. */
		public Spec(int mapId, double x, double y, int picture, int floor, int category, int priority, @Nullable Hotspot card,
			@Nullable List<IconExtra> extras, @Nullable Need need)
		{
			this.need = need;
			this.extras = extras == null || extras.isEmpty() ? null : Collections.unmodifiableList(new ArrayList<>(extras));
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.picture = picture;
			this.floor = floor;
			this.category = category;
			this.priority = priority;
			this.card = card;
		}

		@Nullable
		public Hotspot getCard()
		{
			return card;
		}

		@Nullable
		public Need getNeed()
		{
			return need;
		}

		/** What was merged into the icon, or an empty list. */
		public List<IconExtra> getExtras()
		{
			return extras == null ? Collections.emptyList() : extras;
		}
	}

	/** The icons of one map, in cell order. */
	public static final class MapIcons
	{
		@Getter
		private final int mapId;
		private final int minCellX;
		private final int minCellY;
		private final int cellsX;
		private final int cellsY;
		private final int[] cellStart;
		private final float[] x;
		private final float[] y;
		private final short[] picture;
		private final byte[] floor;
		private final short[] category;
		private final byte[] priority;
		private final Hotspot[] card;
		private final List<IconExtra>[] extras;
		private final Need[] need;
		/** True for an icon of an upper floor that has the same kind of icon in the same place on a lower floor. */
		private final boolean[] shadowed;

		private MapIcons(int mapId, List<Spec> specs)
		{
			this.mapId = mapId;
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			for (Spec spec : specs)
			{
				minX = Math.min(minX, cell(spec.x));
				minY = Math.min(minY, cell(spec.y));
				maxX = Math.max(maxX, cell(spec.x));
				maxY = Math.max(maxY, cell(spec.y));
			}
			this.minCellX = minX;
			this.minCellY = minY;
			this.cellsX = maxX - minX + 1;
			this.cellsY = maxY - minY + 1;
			List<Spec> sorted = new ArrayList<>(specs);
			sorted.sort(Comparator.<Spec>comparingInt(s -> (cell(s.y) - minCellY) * cellsX + cell(s.x) - minCellX)
				.thenComparingInt(s -> s.priority).thenComparingDouble(s -> s.y).thenComparingDouble(s -> s.x));
			int n = sorted.size();
			this.cellStart = new int[cellsX * cellsY + 1];
			this.x = new float[n];
			this.y = new float[n];
			this.picture = new short[n];
			this.floor = new byte[n];
			this.category = new short[n];
			this.priority = new byte[n];
			this.card = new Hotspot[n];
			@SuppressWarnings("unchecked")
			List<IconExtra>[] merged = (List<IconExtra>[]) new List<?>[n];
			this.extras = merged;
			this.need = new Need[n];
			this.shadowed = new boolean[n];
			Map<Long, Integer> lowest = new HashMap<>();
			for (int i = 0; i < n; i++)
			{
				Spec s = sorted.get(i);
				x[i] = (float) s.x;
				y[i] = (float) s.y;
				picture[i] = (short) s.picture;
				floor[i] = (byte) s.floor;
				category[i] = (short) s.category;
				priority[i] = (byte) s.priority;
				card[i] = s.card;
				extras[i] = s.extras;
				need[i] = s.need;
				cellStart[(cell(s.y) - minCellY) * cellsX + cell(s.x) - minCellX + 1]++;
				lowest.merge(sameSpot(category[i], x[i], y[i]), (int) floor[i], Math::min);
			}
			for (int i = 0; i < n; i++)
			{
				shadowed[i] = floor[i] > lowest.get(sameSpot(category[i], x[i], y[i]));
			}
			for (int i = 1; i < cellStart.length; i++)
			{
				cellStart[i] += cellStart[i - 1];
			}
		}

		/** How many icons the map has. */
		public int size()
		{
			return x.length;
		}

		public double x(int i)
		{
			return x[i];
		}

		public double y(int i)
		{
			return y[i];
		}

		public int picture(int i)
		{
			return picture[i];
		}

		public int floor(int i)
		{
			return floor[i];
		}

		public int category(int i)
		{
			return category[i];
		}

		public int priority(int i)
		{
			return priority[i];
		}

		/**
		 * True when the icon is drawn on a view of a floor. The ground floor's view shows every icon of the map, those of the floors
		 * above at their place too (an icon that an upper floor repeats from a lower one, same kind in the same place, is shown
		 * once, as its lowest floor's). A view of an upper floor shows the icons of that floor.
		 */
		public boolean shownOn(int i, int onFloor)
		{
			return onFloor == 0 ? floor[i] == 0 || !shadowed[i] : floor[i] == onFloor;
		}

		@Nullable
		public Hotspot card(int i)
		{
			return card[i];
		}

		/** What the icon asks of the player, or null when it asks nothing the plugin can judge. */
		@Nullable
		public Need need(int i)
		{
			return need[i];
		}

		/** What the plugin's points of interest merged into an icon add to its card, or an empty list. */
		public List<IconExtra> extras(int i)
		{
			return extras[i] == null ? Collections.emptyList() : extras[i];
		}

		/**
		 * Writes the indexes of the icons in the cells a rectangle of tiles touches into {@code out}, as many as fit, and returns
		 * how many there are in all. When the answer is more than {@code out.length} the caller makes a longer array and asks again.
		 * Allocates nothing.
		 */
		public int query(double minX, double minY, double maxX, double maxY, int[] out)
		{
			int cx0 = Math.max(cell(minX) - minCellX, 0);
			int cx1 = Math.min(cell(maxX) - minCellX, cellsX - 1);
			int cy0 = Math.max(cell(minY) - minCellY, 0);
			int cy1 = Math.min(cell(maxY) - minCellY, cellsY - 1);
			if (cx0 > cx1 || cy0 > cy1)
			{
				return 0;
			}
			int total = 0;
			for (int cy = cy0; cy <= cy1; cy++)
			{
				// Cells of one row are next to each other in the arrays, so a row is one run.
				int from = cellStart[cy * cellsX + cx0];
				int to = cellStart[cy * cellsX + cx1 + 1];
				for (int i = from; i < to; i++)
				{
					if (total < out.length)
					{
						out[total] = i;
					}
					total++;
				}
			}
			return total;
		}
	}

	private static final MapIcons[] NONE = new MapIcons[0];

	private final MapIcons[] byMap;
	private final int count;
	private final Set<Hotspot> cards = Collections.newSetFromMap(new IdentityHashMap<>());

	public GameIconSet(List<Spec> specs)
	{
		int highest = -1;
		for (Spec spec : specs)
		{
			highest = Math.max(highest, spec.mapId);
		}
		List<List<Spec>> grouped = new ArrayList<>();
		for (int i = 0; i <= highest; i++)
		{
			grouped.add(null);
		}
		for (Spec spec : specs)
		{
			if (grouped.get(spec.mapId) == null)
			{
				grouped.set(spec.mapId, new ArrayList<>());
			}
			grouped.get(spec.mapId).add(spec);
		}
		this.byMap = highest < 0 ? NONE : new MapIcons[highest + 1];
		for (int i = 0; i <= highest; i++)
		{
			if (grouped.get(i) != null)
			{
				byMap[i] = new MapIcons(i, grouped.get(i));
			}
		}
		this.count = specs.size();
		for (Spec spec : specs)
		{
			if (spec.card != null)
			{
				cards.add(spec.card);
			}
		}
	}

	/** True when a hotspot is the card of one of these icons, so the layer that draws the icon answers for it. */
	public boolean isCardOfAnIcon(Hotspot hotspot)
	{
		return cards.contains(hotspot);
	}

	public static GameIconSet empty()
	{
		return new GameIconSet(new ArrayList<>());
	}

	/** How many icons there are in all. */
	public int size()
	{
		return count;
	}

	/** The icons of a map, or null when it has none. */
	@Nullable
	public MapIcons on(int mapId)
	{
		return mapId >= 0 && mapId < byMap.length ? byMap[mapId] : null;
	}

	/** The ids of the maps that have icons. */
	public int[] mapIds()
	{
		int[] ids = new int[byMap.length];
		int n = 0;
		for (int i = 0; i < byMap.length; i++)
		{
			if (byMap[i] != null)
			{
				ids[n++] = i;
			}
		}
		return Arrays.copyOf(ids, n);
	}

	static int cell(double world)
	{
		return (int) Math.floor(world / CELL);
	}

	/** Two icons of one category whose places are less than this many tiles apart are the same icon seen on two floors. */
	static final double SAME_SPOT = 0.5;

	/** A key that is the same for icons of one category in one place: the category and the place in steps of {@link #SAME_SPOT}. */
	private static long sameSpot(int category, double x, double y)
	{
		long gx = Math.round(x / SAME_SPOT) & 0xFFFFFFL;
		long gy = Math.round(y / SAME_SPOT) & 0xFFFFFFL;
		return (long) (category & 0xFFFF) << 48 | gx << 24 | gy;
	}
}
