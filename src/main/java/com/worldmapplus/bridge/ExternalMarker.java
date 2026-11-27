package com.worldmapplus.bridge;

import java.awt.*;
import java.awt.image.*;
import java.util.List;
import javax.annotation.*;
import lombok.*;
import net.runelite.api.coords.*;

/** A point another plugin asked the map to show, or that it put on the game's own map. Every text is already capped and cleaned. */
@Getter
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public final class ExternalMarker implements ExternalOverlays.Sent
{
	private final String source;
	private final String id;
	private final double x;
	private final double y;
	private final String name;
	private final String tooltip;
	private final Color color;
	/** A small picture to draw instead of a plain marker, or null. */
	@Nullable
	private final BufferedImage icon;
	/** Where in the picture the point is, as pixels from its top left, or null for the middle. */
	@Nullable
	private final int[] anchor;
	/** True when the marker stays at the edge of the view while its place is off screen. */
	private final boolean snapToEdge;
	/** Where a click on the marker takes the map, or null for nowhere. */
	@Nullable
	private final WorldPoint jumpTarget;
	/** True for a party member's point made by RuneLite's Party plugin, with the member's name, which this map can follow. */
	private final boolean party;
	/** True when a left click on the marker is reported to the plugin that sent it, as a {@code marker_clicked} message. */
	private final boolean clickable;
	/** What the card says a click does, or an empty text for the usual words. */
	private final String hint;
	/** The entries the sending plugin added to this map's right click menu on the marker, in order. Empty for none. */
	private final List<String> menu;
}
