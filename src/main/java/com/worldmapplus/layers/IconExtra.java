package com.worldmapplus.layers;

import java.util.Objects;
import javax.annotation.*;
import lombok.*;

/**
 * What one of the plugin's own points of interest adds to the card of the game's icon it was merged into: a rare tree's species, the
 * fish at a fishing spot, the name of a minigame. The point is not drawn on its own where the game draws an icon (see
 * {@link Hotspot#isMerged()}); this is what is left of it, and it is shown on the icon's card while the layer the point belongs to is
 * switched on.
 */
public final class IconExtra
{
	private final String layer;
	@Getter
	private final String name;
	private final String detail;
	@Getter
	private final int tileX;
	@Getter
	private final int tileY;

	/**
	 * @param layer the id of the layer that switches this on, or null for information that has no layer of its own and is always shown
	 * @param name what the point is called
	 * @param detail what the point says about itself, or null
	 * @param tileX where the point itself was, in tiles (a farming patch looks its live state up there)
	 * @param tileY see {@code tileX}
	 */
	public IconExtra(@Nullable String layer, String name, @Nullable String detail, int tileX, int tileY)
	{
		this.layer = layer;
		this.name = name == null ? "" : name;
		this.detail = detail;
		this.tileX = tileX;
		this.tileY = tileY;
	}

	/** The layer that shows this, or null when it is always shown. */
	@Nullable
	public String getLayer()
	{
		return layer;
	}

	@Nullable
	public String getDetail()
	{
		return detail;
	}

	/** True when two say the same thing for the same layer, as the several booths of one bank do. */
	boolean sameAs(IconExtra other)
	{
		return Objects.equals(layer, other.layer) && name.equals(other.name) && Objects.equals(detail, other.detail);
	}
}
