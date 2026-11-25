package com.worldmapplus.ui;

import com.worldmapplus.bridge.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * A route as a short list of steps: walk, take a jump, walk, and so on.
 * <p>
 * Shortest Path tells this plugin only about the jumps of a route, the boats, teleports and shortcuts,
 * not the walking between them. The walking is the gaps: from where the player is to the first jump,
 * from each jump's arrival to the next jump, and from the last arrival to the target. Each gap is
 * shown as a straight line with its length in tiles, which says where to head even though it is not
 * the exact path walked. Shortest Path draws the exact path in the game view.
 */
public final class RoutePlan
{
	/** A walk shorter than this many tiles is not worth a step of its own. */
	static final int MIN_WALK_TILES = 3;

	/** One step of the route. */
	public static final class Step
	{
		@Getter
		private final boolean walk;
		/** What to do, such as {@code Walk about 40 tiles} or {@code Ardougne cloak: Kandarin Monastery}. */
		@Getter
		private final String text;
		@Getter
		private final double fromX;
		@Getter
		private final double fromY;
		@Getter
		private final double toX;
		@Getter
		private final double toY;
		/** For a walk, how many tiles it is, going diagonally where that helps. */
		@Getter
		private final int tiles;
		/** The floor the step starts on. */
		@Getter
		private final int fromPlane;
		/** The floor the step ends on. */
		@Getter
		private final int toPlane;

		Step(boolean walk, String text, double fromX, double fromY, int fromPlane, double toX, double toY, int toPlane,
			int tiles)
		{
			this.fromPlane = fromPlane;
			this.toPlane = toPlane;
			this.walk = walk;
			this.text = text;
			this.fromX = fromX;
			this.fromY = fromY;
			this.toX = toX;
			this.toY = toY;
			this.tiles = tiles;
		}
	}

	@Getter
	private final List<Step> steps;

	private RoutePlan(List<Step> steps)
	{
		this.steps = Collections.unmodifiableList(steps);
	}

	public static RoutePlan empty()
	{
		return new RoutePlan(new ArrayList<>());
	}

	public boolean isEmpty()
	{
		return steps.isEmpty();
	}

	/**
	 * Builds the plan.
	 *
	 * @param start where the player is, as {x, y}, or null when not known
	 * @param hops the jumps, in order
	 * @param target where the route ends, as {x, y}, or null when this plugin did not ask for the route
	 */
	public static RoutePlan build(@Nullable double[] start, List<RouteHop> hops, @Nullable double[] target)
	{
		return build(start, 0, hops, target, 0);
	}

	/**
	 * As {@link #build(double[], List, double[])}, knowing which floor the player and the target are on, so a
	 * walk can be worked out on the right floor.
	 */
	public static RoutePlan build(@Nullable double[] start, int startPlane, List<RouteHop> hops,
		@Nullable double[] target, int targetPlane)
	{
		List<Step> steps = new ArrayList<>();
		double[] at = start;
		int atPlane = startPlane;
		for (RouteHop hop : hops)
		{
			if (at != null)
			{
				addWalk(steps, at, atPlane, new double[] {hop.getFromX(), hop.getFromY()}, hop.getFromPlane(),
					"Walk about %d tiles");
			}
			steps.add(new Step(false, hop.getLabel(), hop.getFromX(), hop.getFromY(), hop.getFromPlane(), hop.getToX(),
				hop.getToY(), hop.getToPlane(), 0));
			at = new double[] {hop.getToX(), hop.getToY()};
			atPlane = hop.getToPlane();
		}
		if (at != null && target != null)
		{
			addWalk(steps, at, atPlane, target, targetPlane, hops.isEmpty() ? "Walk about %d tiles to the target"
				: "Then walk about %d tiles to the target");
		}
		return new RoutePlan(steps);
	}

	private static void addWalk(List<Step> steps, double[] from, int fromPlane, double[] to, int toPlane, String format)
	{
		int tiles = (int) Math.round(Math.max(Math.abs(to[0] - from[0]), Math.abs(to[1] - from[1])));
		if (tiles >= MIN_WALK_TILES)
		{
			steps.add(new Step(true, String.format(format, tiles), from[0], from[1], fromPlane, to[0], to[1], toPlane, tiles));
		}
	}
}
