package com.worldmapplus.pins;

import java.util.Arrays;

/**
 * The shortest order to visit a few stops, solved exactly by dynamic programming over the sets of stops visited (Held and Karp).
 * Time grows as n squared times two to the n, so it is meant for up to {@link #MAX_STOPS} stops: 12 stops take about a million
 * steps and well under a tenth of a second. Pure arithmetic, no clock and no thread of its own.
 */
public final class FastestOrder
{
	/** The most stops ordered. */
	public static final int MAX_STOPS = 12;

	private FastestOrder()
	{
	}

	/**
	 * @param distance distance[i][j] is the length from stop i to stop j
	 * @param keepFirst whether stop 0 stays first
	 * @param roundTrip whether the way comes back to the first stop at the end; a round trip always starts at stop 0, which loses
	 * nothing because a loop has no start
	 * @return the stops in the best order, as indexes into the matrix
	 */
	public static int[] solve(double[][] distance, boolean keepFirst, boolean roundTrip)
	{
		int n = distance.length;
		if (n > MAX_STOPS)
		{
			throw new IllegalArgumentException("too many stops: " + n);
		}
		int[] identity = new int[n];
		for (int i = 0; i < n; i++)
		{
			identity[i] = i;
		}
		if (n <= 2)
		{
			return identity;
		}
		boolean fixed = keepFirst || roundTrip;
		int sets = 1 << n;
		double[][] best = new double[sets][n];
		int[][] before = new int[sets][n];
		for (double[] row : best)
		{
			Arrays.fill(row, Double.POSITIVE_INFINITY);
		}
		if (fixed)
		{
			best[1][0] = 0;
		}
		else
		{
			for (int i = 0; i < n; i++)
			{
				best[1 << i][i] = 0;
			}
		}
		for (int set = 1; set < sets; set++)
		{
			for (int last = 0; last < n; last++)
			{
				double sofar = best[set][last];
				if ((set & (1 << last)) == 0 || sofar == Double.POSITIVE_INFINITY)
				{
					continue;
				}
				for (int next = 0; next < n; next++)
				{
					if ((set & (1 << next)) != 0)
					{
						continue;
					}
					int grown = set | (1 << next);
					double length = sofar + distance[last][next];
					if (length < best[grown][next])
					{
						best[grown][next] = length;
						before[grown][next] = last;
					}
				}
			}
		}
		int all = sets - 1;
		int end = -1;
		double shortest = Double.POSITIVE_INFINITY;
		for (int last = 0; last < n; last++)
		{
			double length = best[all][last] + (roundTrip ? distance[last][0] : 0);
			if (length < shortest)
			{
				shortest = length;
				end = last;
			}
		}
		if (end < 0)
		{
			return identity;
		}
		int[] order = new int[n];
		int set = all;
		for (int i = n - 1; i >= 0; i--)
		{
			order[i] = end;
			int previous = before[set][end];
			set &= ~(1 << end);
			end = previous;
		}
		return order;
	}

	/** The length of visiting the stops in this order, with the way back to the first for a round trip. */
	public static double length(double[][] distance, int[] order, boolean roundTrip)
	{
		double total = 0;
		for (int i = 1; i < order.length; i++)
		{
			total += distance[order[i - 1]][order[i]];
		}
		if (roundTrip && order.length > 1)
		{
			total += distance[order[order.length - 1]][order[0]];
		}
		return total;
	}
}
