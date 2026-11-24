package com.worldmapplus.game;

import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.inject.Singleton;

/**
 * Remembers where the player has been this session, as a short trail.
 * <p>
 * It records only the player's own tiles, kept in memory, never saved and never sent anywhere. A
 * point is added when the player has moved a couple of tiles from the last one, and a change of
 * map or a long jump starts a new line, so a teleport does not draw a stripe across the world.
 */
@Singleton
public class TrailTracker
{
	/** Most points kept. */
	static final int MAX_POINTS = 600;

	/** A new point is recorded after the player moves this many tiles. */
	static final double MIN_STEP = 2.0;

	/** A jump longer than this starts a new line instead of joining the last point. */
	static final double MAX_STEP = 40.0;

	private final Deque<double[]> points = new ArrayDeque<>();
	private int lastMap = Integer.MIN_VALUE;

	/** Records a position. Client thread. */
	public synchronized void add(MapCatalog catalog, PlayerPosition position)
	{
		if (position == null)
		{
			return;
		}
		MapInfo map = catalog.mapFor(position.getTile().getX(), position.getTile().getY());
		int mapId = map == null ? -1 : map.getId();
		double x = position.getX();
		double y = position.getY();

		double[] last = points.peekLast();
		boolean newLine = last == null || mapId != lastMap
			|| Math.hypot(x - last[0], y - last[1]) > MAX_STEP;
		if (!newLine && Math.hypot(x - last[0], y - last[1]) < MIN_STEP)
		{
			return;
		}
		// The third value marks the start of a line: 1 for a break before this point.
		points.addLast(new double[] {x, y, newLine ? 1 : 0, mapId});
		lastMap = mapId;
		while (points.size() > MAX_POINTS)
		{
			points.pollFirst();
		}
	}

	/** A copy of the trail, oldest first. Each entry is {x, y, breakBefore, mapId}. */
	public synchronized List<double[]> snapshot()
	{
		return new ArrayList<>(points);
	}

	public synchronized void clear()
	{
		points.clear();
		lastMap = Integer.MIN_VALUE;
	}
}
