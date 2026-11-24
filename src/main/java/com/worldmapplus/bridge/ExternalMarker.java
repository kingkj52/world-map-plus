package com.worldmapplus.bridge;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/** A point another plugin asked the map to show, or that it put on the game's own map. */
public final class ExternalMarker
{
	private final String source;
	private final String id;
	private final double x;
	private final double y;
	private final String name;
	private final String tooltip;
	private final Color color;
	private final BufferedImage icon;
	private final int[] anchor;
	private final boolean snapToEdge;
	private final WorldPoint jumpTarget;
	private final String origin;
	private final boolean clickable;
	private final String hint;
	private final List<String> menu;
	private final String layer;

	ExternalMarker(String source, String id, double x, double y, String name, String tooltip, Color color,
		@Nullable BufferedImage icon)
	{
		this(source, id, x, y, name, tooltip, color, icon, null, false, null);
	}

	ExternalMarker(String source, String id, double x, double y, String name, String tooltip, Color color,
		@Nullable BufferedImage icon, @Nullable int[] anchor, boolean snapToEdge, @Nullable WorldPoint jumpTarget)
	{
		this(source, id, x, y, name, tooltip, color, icon, anchor, snapToEdge, jumpTarget, "");
	}

	ExternalMarker(String source, String id, double x, double y, String name, String tooltip, Color color,
		@Nullable BufferedImage icon, @Nullable int[] anchor, boolean snapToEdge, @Nullable WorldPoint jumpTarget,
		String origin)
	{
		this(source, id, x, y, name, tooltip, color, icon, anchor, snapToEdge, jumpTarget, origin, false, "",
			Collections.emptyList(), "");
	}

	/**
	 * A marker another plugin sent, with what it asked for beyond the picture: whether a click on it is reported back, the hint its
	 * card shows for that click, the entries it adds to this map's own right click menu, and the layer tick box it belongs to.
	 */
	ExternalMarker(String source, String id, double x, double y, String name, String tooltip, Color color,
		@Nullable BufferedImage icon, boolean clickable, String hint, List<String> menu, String layer)
	{
		this(source, id, x, y, name, tooltip, color, icon, null, false, null, "", clickable, hint, menu, layer);
	}

	private ExternalMarker(String source, String id, double x, double y, String name, String tooltip, Color color,
		@Nullable BufferedImage icon, @Nullable int[] anchor, boolean snapToEdge, @Nullable WorldPoint jumpTarget,
		String origin, boolean clickable, String hint, List<String> menu, String layer)
	{
		this.clickable = clickable;
		this.hint = hint == null ? "" : hint;
		this.menu = menu == null ? Collections.emptyList() : Collections.unmodifiableList(menu);
		this.layer = layer == null ? "" : layer;
		this.origin = origin == null ? "" : origin;
		this.source = source;
		this.id = id;
		this.x = x;
		this.y = y;
		this.name = name;
		this.tooltip = tooltip;
		this.color = color;
		this.icon = icon;
		this.anchor = anchor;
		this.snapToEdge = snapToEdge;
		this.jumpTarget = jumpTarget;
	}

	/** A point as read from the game's own map, with the name of the class it came from. */
	public static ExternalMarker fromGameMap(String origin, double x, double y, String name)
	{
		return new ExternalMarker("worldmap", "point", x, y, name, "", new Color(255, 0, 255), null, null, false, null,
			origin);
	}

	/** The name of the class this came from, for a point read from the game's own map, so its plugin can be told. */
	public String getOrigin()
	{
		return origin;
	}

	public String getSource()
	{
		return source;
	}

	public String getId()
	{
		return id;
	}

	public double getX()
	{
		return x;
	}

	public double getY()
	{
		return y;
	}

	public String getName()
	{
		return name;
	}

	public String getTooltip()
	{
		return tooltip;
	}

	public Color getColor()
	{
		return color;
	}

	/** A small picture to draw instead of a plain marker, or null. */
	@Nullable
	public BufferedImage getIcon()
	{
		return icon;
	}

	/** Where in the picture the point is, as pixels from its top left, or null for the middle. */
	@Nullable
	public int[] getAnchor()
	{
		return anchor;
	}

	/** True when the marker stays at the edge of the view while its place is off screen. */
	public boolean isSnapToEdge()
	{
		return snapToEdge;
	}

	/** Where a click on the marker takes the map, or null for nowhere. */
	@Nullable
	public WorldPoint getJumpTarget()
	{
		return jumpTarget;
	}

	/** True when a left click on the marker is reported to the plugin that sent it, as a {@code marker_clicked} message. */
	public boolean isClickable()
	{
		return clickable;
	}

	/** What the card says a click does, or an empty text for the usual words. */
	public String getHint()
	{
		return hint;
	}

	/** The entries the sending plugin added to this map's right click menu on the marker, in order. Empty for none. */
	public List<String> getMenu()
	{
		return menu;
	}

	/** The name of the tick box under the layers button that shows or hides this marker, or an empty text for none. */
	public String getLayer()
	{
		return layer;
	}

	/** True when this was read from a point another plugin put on the game's own map, and false for a marker sent to this map. */
	public boolean isFromGameMap()
	{
		return !origin.isEmpty();
	}
}
