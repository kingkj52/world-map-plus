package com.worldmapplus.bridge;

import java.awt.*;
import java.util.List;
import lombok.*;

/** A line, a route or an area outline, that another plugin asked the map to draw. */
@Getter
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public final class ExternalLine implements ExternalOverlays.Sent
{
	private final String source;
	private final String id;
	/** The vertices as {x, y, floor}, in world tile coordinates. */
	private final List<double[]> points;
	private final Color color;
	private final String label;
	/** True for a single jump such as a boat or a teleport, drawn as an arc rather than a path. */
	private final boolean hop;
}
