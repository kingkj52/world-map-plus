package com.worldmapplus.bridge;

import java.awt.Color;
import java.util.Collections;
import java.util.List;

/** A line, a route or an area outline, that another plugin asked the map to draw. */
public final class ExternalLine
{
	private final String source;
	private final String id;
	private final List<double[]> points;
	private final Color color;
	private final String label;
	private final boolean hop;
	private final String layer;

	ExternalLine(String source, String id, List<double[]> points, Color color, String label, boolean hop)
	{
		this(source, id, points, color, label, hop, "");
	}

	ExternalLine(String source, String id, List<double[]> points, Color color, String label, boolean hop, String layer)
	{
		this.layer = layer == null ? "" : layer;
		this.source = source;
		this.id = id;
		this.points = Collections.unmodifiableList(points);
		this.color = color;
		this.label = label;
		this.hop = hop;
	}

	public String getSource()
	{
		return source;
	}

	public String getId()
	{
		return id;
	}

	/** The vertices as {x, y} pairs, in world tile coordinates. */
	public List<double[]> getPoints()
	{
		return points;
	}

	public Color getColor()
	{
		return color;
	}

	public String getLabel()
	{
		return label;
	}

	/** The name of the tick box under the layers button that shows or hides this line, or an empty text for none. */
	public String getLayer()
	{
		return layer;
	}

	/** True for a single jump such as a boat or a teleport, drawn as an arc rather than a path. */
	public boolean isHop()
	{
		return hop;
	}
}
