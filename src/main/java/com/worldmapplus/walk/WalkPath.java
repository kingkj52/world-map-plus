package com.worldmapplus.walk;

import java.util.Arrays;
import javax.annotation.*;

/**
 * Finds the way to walk between two tiles on one floor: a shortest way through the tiles the game lets a
 * player step between, going diagonally where that is possible.
 * <p>
 * It is a breadth first search with the same step rules and the same order of trying directions as Shortest
 * Path's own walking search. Shortest Path says where a route jumps and not how it walks, so this finds the
 * walking between two points it did name. Where two ways are equally short it may pick the other one.
 */
public final class WalkPath
{
	/** The most tiles the search window may hold. A longer walk than this is not drawn as a path. */
	static final int MAX_AREA = 600_000;

	/** The most tiles the search will take in before it gives up, so a goal nothing can reach is not explored for long. */
	static final int MAX_EXPANSIONS = 400_000;

	/** Tiles left around the two points for the way to go round things. */
	private static final int MIN_MARGIN = 24;

	// Directions in the order Shortest Path tries them: west, east, south, north, then the diagonals.
	private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
	private static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};

	private WalkPath()
	{
	}

	/**
	 * @return the tiles from the start to the goal, both included, as x then y of each tile's corner, or null
	 * when there is no data for the start, no way within the search window, or the window would be too large
	 */
	@Nullable
	public static int[] find(CollisionMap map, int startX, int startY, int goalX, int goalY, int plane)
	{
		if (!map.knows(startX, startY) || !map.knows(goalX, goalY))
		{
			return null;
		}
		if (startX == goalX && startY == goalY)
		{
			return new int[] {startX, startY};
		}
		int distance = Math.max(Math.abs(goalX - startX), Math.abs(goalY - startY));
		int margin = Math.max(MIN_MARGIN, distance / 2);
		int minX = Math.min(startX, goalX) - margin;
		int minY = Math.min(startY, goalY) - margin;
		int width = Math.abs(goalX - startX) + 2 * margin + 1;
		int height = Math.abs(goalY - startY) + 2 * margin + 1;
		if ((long) width * height > MAX_AREA)
		{
			return null;
		}

		int[] parent = new int[width * height];
		Arrays.fill(parent, -1);
		int[] queue = new int[width * height];
		int head = 0;
		int tail = 0;
		int start = (startY - minY) * width + (startX - minX);
		int goal = (goalY - minY) * width + (goalX - minX);
		parent[start] = start;
		queue[tail++] = start;

		while (head < tail && head < MAX_EXPANSIONS)
		{
			int current = queue[head++];
			int x = current % width + minX;
			int y = current / width + minY;
			boolean blocked = map.isBlocked(x, y, plane);
			for (int d = 0; d < 8; d++)
			{
				int nx = x + DX[d];
				int ny = y + DY[d];
				int lx = nx - minX;
				int ly = ny - minY;
				if (lx < 0 || ly < 0 || lx >= width || ly >= height)
				{
					continue;
				}
				int next = ly * width + lx;
				// Out of a tile with no way out, any neighbour that is itself open can be stepped to.
				if (parent[next] != -1 || !(blocked ? !map.isBlocked(nx, ny, plane) : map.step(x, y, plane, DX[d], DY[d])))
				{
					continue;
				}
				parent[next] = current;
				if (next == goal)
				{
					return trace(parent, goal, start, width, minX, minY);
				}
				queue[tail++] = next;
			}
		}
		return null;
	}


	private static int[] trace(int[] parent, int goal, int start, int width, int minX, int minY)
	{
		int count = 1;
		for (int at = goal; at != start; at = parent[at])
		{
			count++;
		}
		int[] path = new int[count * 2];
		int at = goal;
		for (int i = count - 1; i >= 0; i--)
		{
			path[i * 2] = at % width + minX;
			path[i * 2 + 1] = at / width + minY;
			at = parent[at];
		}
		return path;
	}
}
