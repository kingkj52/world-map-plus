package com.worldmapplus.layers;

import java.util.ArrayList;
import java.util.Collections;
import com.worldmapplus.unlock.Need;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Something on the map the mouse can point at: a dungeon entrance, a transport, a shop.
 * <p>
 * Hotspots are static reference data. Each knows where it is, how close the mouse has to be, the
 * card to show, and optionally where a click takes the map. A click only ever moves the viewer's
 * own camera or switches the viewer's own map. It never reaches the game.
 */
public final class Hotspot
{
	/** What sort of thing a hotspot is, for search results and for choosing what to draw. */
	public enum Kind
	{
		DOORWAY("Entrance"),
		TRANSPORT("Transport"),
		SHOP("Shop"),
		POI("Point"),
		TELEPORT("Teleport");

		private final String label;

		Kind(String label)
		{
			this.label = label;
		}

		public String label()
		{
			return label;
		}
	}

	/** Where a click on the hotspot takes the viewer. */
	public static final class Target
	{
		private final int mapId;
		private final double x;
		private final double y;

		public Target(int mapId, double x, double y)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
		}

		public int getMapId()
		{
			return mapId;
		}

		public double getX()
		{
			return x;
		}

		public double getY()
		{
			return y;
		}
	}

	/** A line drawn from the hotspot to another place while it is hovered, such as a ship route. */
	public static final class Link
	{
		private final int mapId;
		private final double x;
		private final double y;
		private final String label;

		public Link(int mapId, double x, double y, String label)
		{
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.label = label == null ? "" : label;
		}

		public int getMapId()
		{
			return mapId;
		}

		public double getX()
		{
			return x;
		}

		public double getY()
		{
			return y;
		}

		public String getLabel()
		{
			return label;
		}
	}

	private final int mapId;
	private final double x;
	private final double y;
	private final int radiusPixels;
	private final HoverCard card;
	private final Target target;
	private final List<Link> links;
	private final Kind kind;
	private final String category;
	private final String icon;
	private final int floor;
	private final boolean drawn;
	private final Merge merge;
	private final Need need;
	/** The need of each way by its row's label; set only while the hotspot is made, before anything else sees it. */
	private java.util.Map<String, Need> wayNeeds = Collections.emptyMap();

	public Hotspot(int mapId, double x, double y, int radiusPixels, HoverCard card, @Nullable Target target)
	{
		this(mapId, x, y, radiusPixels, card, target, Collections.emptyList(), Kind.DOORWAY);
	}

	public Hotspot(int mapId, double x, double y, int radiusPixels, HoverCard card, @Nullable Target target,
		List<Link> links)
	{
		this(mapId, x, y, radiusPixels, card, target, links, Kind.TRANSPORT);
	}

	public Hotspot(int mapId, double x, double y, int radiusPixels, HoverCard card, @Nullable Target target,
		List<Link> links, Kind kind)
	{
		this(mapId, x, y, radiusPixels, card, target, links, kind, null, null);
	}

	/** A point of interest: it belongs to a category, which is a layer, and may have an icon of its own. */
	public Hotspot(int mapId, double x, double y, int radiusPixels, HoverCard card, @Nullable Target target,
		List<Link> links, Kind kind, @Nullable String category, @Nullable String icon)
	{
		this(mapId, x, y, radiusPixels, card, target, links, kind, category, icon, NOT_A_MAP_ICON, false);
	}

	/**
	 * An icon of the game's map. It lies on a floor, and either the map's own picture shows it (a disc baked into the tile, which
	 * grows and shrinks with the picture) or {@code drawn} is true and the plugin draws it itself, at a size that follows the zoom
	 * but is bounded.
	 */
	public Hotspot(int mapId, double x, double y, int radiusPixels, HoverCard card, @Nullable Target target,
		List<Link> links, Kind kind, @Nullable String category, @Nullable String icon, int floor, boolean drawn)
	{
		this(mapId, x, y, radiusPixels, card, target, links, kind, category, icon, floor, drawn, null);
	}

	/**
	 * A point of interest that sits on an icon the game draws, and is merged into it: the plugin does not draw it, it answers no
	 * hover of its own, and what it says is on the icon's card (see {@link IconExtra}). It is placed at the icon, so a search for it
	 * goes where the icon is. It is the same point in every other respect.
	 */
	public static Hotspot merged(int mapId, double x, double y, int radiusPixels, HoverCard card, String category, Merge merge)
	{
		return new Hotspot(mapId, x, y, radiusPixels, card, null, new ArrayList<>(), Kind.POI, category, null, NOT_A_MAP_ICON, false,
			merge);
	}

	private Hotspot(int mapId, double x, double y, int radiusPixels, HoverCard card, @Nullable Target target,
		List<Link> links, Kind kind, @Nullable String category, @Nullable String icon, int floor, boolean drawn, @Nullable Merge merge)
	{
		this(mapId, x, y, radiusPixels, card, target, links, kind, category, icon, floor, drawn, merge, null);
	}

	private Hotspot(int mapId, double x, double y, int radiusPixels, HoverCard card, @Nullable Target target,
		List<Link> links, Kind kind, @Nullable String category, @Nullable String icon, int floor, boolean drawn, @Nullable Merge merge,
		@Nullable Need need)
	{
		this.need = need;
		this.merge = merge;
		this.floor = floor;
		this.drawn = drawn;
		this.category = category;
		this.icon = icon;
		this.mapId = mapId;
		this.x = x;
		this.y = y;
		this.radiusPixels = radiusPixels;
		this.card = card;
		this.target = target;
		this.links = Collections.unmodifiableList(links);
		this.kind = kind;
	}

	/**
	 * A copy of this hotspot that asks something of the player before it is of any use, or that starts a quest. The need only changes how
	 * the icon is drawn and what its card adds; it never changes where the hotspot is or what a click does.
	 */
	public Hotspot withNeed(@Nullable Need need)
	{
		if (need == null && this.need == null)
		{
			return this;
		}
		Hotspot copy = new Hotspot(mapId, x, y, radiusPixels, card, target, links, kind, category, icon, floor, drawn, merge, need);
		copy.wayNeeds = wayNeeds;
		return copy;
	}

	/** A copy of this hotspot with another card, everything else the same. */
	public Hotspot withCard(HoverCard other)
	{
		Hotspot copy = new Hotspot(mapId, x, y, radiusPixels, other, target, links, kind, category, icon, floor, drawn, merge, need);
		copy.wayNeeds = wayNeeds;
		return copy;
	}

	/**
	 * A copy of this hotspot that knows the need of each of its ways by the label of the way's row on its card (the teleports that land
	 * at one place), so a search result for one way can say what is missing for that way alone.
	 */
	public Hotspot withWayNeeds(java.util.Map<String, Need> byLabel)
	{
		if (byLabel.isEmpty() && wayNeeds.isEmpty())
		{
			return this;
		}
		Hotspot copy = new Hotspot(mapId, x, y, radiusPixels, card, target, links, kind, category, icon, floor, drawn, merge, need);
		copy.wayNeeds = Collections.unmodifiableMap(new java.util.HashMap<>(byLabel));
		return copy;
	}

	/** The need of the way whose row has this label, or null when it asks nothing or there is no such way. */
	@Nullable
	public Need wayNeed(String label)
	{
		return wayNeeds.get(label);
	}

	/** What the hotspot asks of the player, or null when it asks nothing the plugin can judge. */
	@Nullable
	public Need getNeed()
	{
		return need;
	}

	/** Where a merged point went: the icon it was merged into, and where the point itself was. */
	public static final class Merge
	{
		private final double iconX;
		private final double iconY;
		private final int iconFloor;
		private final int tileX;
		private final int tileY;
		private final boolean always;

		/**
		 * @param always true for a kind of point that has no layer to switch it on, whose information on the icon is always shown
		 */
		public Merge(double iconX, double iconY, int iconFloor, int tileX, int tileY, boolean always)
		{
			this.iconX = iconX;
			this.iconY = iconY;
			this.iconFloor = iconFloor;
			this.tileX = tileX;
			this.tileY = tileY;
			this.always = always;
		}

		public double getIconX()
		{
			return iconX;
		}

		public double getIconY()
		{
			return iconY;
		}

		public int getIconFloor()
		{
			return iconFloor;
		}

		/** The tile the point itself was on before it was merged. */
		public int getTileX()
		{
			return tileX;
		}

		public int getTileY()
		{
			return tileY;
		}

		public boolean isAlways()
		{
			return always;
		}
	}

	/** True for a point merged into an icon of the game's map: it is not drawn and answers no hover, the icon's card says it. */
	public boolean isMerged()
	{
		return merge != null;
	}

	/** Where this point was merged to, or null. */
	@Nullable
	public Merge getMerge()
	{
		return merge;
	}

	/** The value of {@link #getFloor()} for everything that is not an icon of the game's map. */
	public static final int NOT_A_MAP_ICON = -1;

	public Kind getKind()
	{
		return kind;
	}

	/** True for an icon of the game's map: a disc in the tile picture, or one the plugin draws from the game's list. */
	public boolean isMapIcon()
	{
		return floor >= 0;
	}

	/** The floor an icon of the game's map belongs to, or {@link #NOT_A_MAP_ICON}. */
	public int getFloor()
	{
		return floor;
	}

	/** True when the plugin draws this icon itself, so the card answers where the drawn picture is and not where the tile's disc is. */
	public boolean isDrawn()
	{
		return drawn;
	}

	/** The layer a point of interest belongs to, or null for other hotspots. */
	@Nullable
	public String getCategory()
	{
		return category;
	}

	/** The name of the point's icon file, or null for a lettered disc. */
	@Nullable
	public String getIcon()
	{
		return icon;
	}

	/** Places lines are drawn to while this hotspot is hovered. Empty for most hotspots. */
	public List<Link> getLinks()
	{
		return links;
	}

	/** The map the hotspot is drawn on. */
	public int getMapId()
	{
		return mapId;
	}

	public double getX()
	{
		return x;
	}

	public double getY()
	{
		return y;
	}

	/** How near the mouse must be, in pixels, whatever the zoom. */
	public int getRadiusPixels()
	{
		return radiusPixels;
	}

	public HoverCard getCard()
	{
		return card;
	}

	/** Where a click goes, or null if clicking does nothing. */
	@Nullable
	public Target getTarget()
	{
		return target;
	}
}
