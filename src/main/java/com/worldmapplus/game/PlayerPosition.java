package com.worldmapplus.game;

import lombok.*;
import net.runelite.api.coords.*;

/**
 * Where the local player is, to a fraction of a tile.
 * <p>
 * {@code x} and {@code y} are continuous world tile coordinates, so a player standing in the
 * middle of tile 3200,3200 is at 3200.5,3200.5 and one walking between two tiles is somewhere in
 * between. Inside an instance they are the coordinates of the area the instance was copied from,
 * which is where the map should draw them.
 */
@Getter
@RequiredArgsConstructor
public final class PlayerPosition
{
	private final double x;
	private final double y;
	/** The whole tile the player is on. */
	private final WorldPoint tile;
}
