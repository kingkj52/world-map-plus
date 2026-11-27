package com.worldmapplus.share;

import java.util.*;
import lombok.*;

/**
 * What a share code holds: one pin, a route (waypoints in order, and whether it comes back to the first), or a set of
 * places. Only locations travel. Names, colours, sizes, kinds and paths do not. Immutable.
 */
public final class Share
{
	/** The kinds of code, by the letter that starts them. */
	public enum Kind
	{
		PIN('p'),
		ROUTE('r'),
		PLACES('s');

		@Getter
		private final char letter;

		Kind(char letter)
		{
			this.letter = letter;
		}
	}

	@Getter
	private final Kind kind;
	private final boolean loop;
	@Getter
	private final List<SharePoint> points;

	public Share(Kind kind, boolean loop, List<SharePoint> points)
	{
		this.kind = kind;
		this.loop = kind == Kind.ROUTE && loop;
		this.points = Collections.unmodifiableList(new ArrayList<>(points));
	}

	public static Share pin(SharePoint point)
	{
		return new Share(Kind.PIN, false, Collections.singletonList(point));
	}

	public static Share route(List<SharePoint> stops, boolean loop)
	{
		return new Share(Kind.ROUTE, loop, stops);
	}

	public static Share places(List<SharePoint> places)
	{
		return new Share(Kind.PLACES, false, places);
	}

	/** True for a route that comes back to its first stop. */
	public boolean loops()
	{
		return loop;
	}

	/** What this is in a few words: "pin at 1249, 3720", "route of 7 stops, loops", "4 places". */
	public String describe()
	{
		int count = points.size();
		switch (kind)
		{
			case PIN:
				return "pin at " + points.get(0).describe();
			case ROUTE:
				return "route of " + count + (count == 1 ? " stop" : " stops") + (loop ? ", loops" : "");
			default:
				return count + (count == 1 ? " place" : " places");
		}
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof Share))
		{
			return false;
		}
		Share s = (Share) other;
		return s.kind == kind && s.loop == loop && s.points.equals(points);
	}

	@Override
	public int hashCode()
	{
		return (kind.hashCode() * 31 + (loop ? 1 : 0)) * 31 + points.hashCode();
	}

	@Override
	public String toString()
	{
		return kind + (loop ? " loop " : " ") + points;
	}
}
