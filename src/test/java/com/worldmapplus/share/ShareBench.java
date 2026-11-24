package com.worldmapplus.share;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * The benchmark behind the choice of the share format (docs/SHARE-PROTOCOL.md, "Why this format"). Each candidate is a
 * cost model: how many bits it needs for the waypoints of a route. The models share the same header, checksum and text
 * form, so only the waypoint encodings are compared. Candidate E is the format that was built, and its figures are
 * checked against the real encoder in {@code ShareCodecTest.theBenchmarkModelOfTheChosenFormatMatchesTheEncoder}.
 * <p>
 * Run {@code main} to print the table. Nothing here is used by the plugin.
 */
public final class ShareBench
{
	private ShareBench()
	{
	}

	/** Header (count, loop, maps flag) plus the 10 bit checksum, the same for every candidate. */
	static final int COMMON_BITS = 6 + 1 + 1 + 10;

	static int abs(int[] p)
	{
		return p[0] < 4096 && p[1] < 16384 ? 1 + 12 + 14 + 2 : 1 + 15 + 15 + 2;
	}

	static boolean fits(int v, int bits)
	{
		return v >= -(1 << (bits - 1)) && v < (1 << (bits - 1));
	}

	static int region(int[] p)
	{
		return (p[0] >> 6) << 8 | p[1] >> 6;
	}

	/** Naive: every point as two unsigned 16 bit numbers and a plane. */
	static int naive(List<int[]> route)
	{
		return route.size() * 34;
	}

	/** A: the first point absolute, then the cheapest of small, medium and large deltas, or absolute. */
	static int candidateA(List<int[]> route)
	{
		int bits = abs(route.get(0));
		for (int i = 1; i < route.size(); i++)
		{
			int[] a = route.get(i - 1);
			int[] b = route.get(i);
			int dx = b[0] - a[0];
			int dy = b[1] - a[1];
			int best = 2 + abs(b);
			if (a[2] == b[2])
			{
				if (fits(dx, 4) && fits(dy, 4))
				{
					best = Math.min(best, 2 + 8);
				}
				else if (fits(dx, 8) && fits(dy, 8))
				{
					best = Math.min(best, 2 + 16);
				}
				else
				{
					best = Math.min(best, 2 + 32);
				}
			}
			bits += best;
		}
		return bits;
	}

	/** B: region id, local x and y and plane; a point in the region of the one before sends only its local position. */
	static int candidateB(List<int[]> route)
	{
		int bits = 30;
		for (int i = 1; i < route.size(); i++)
		{
			int[] a = route.get(i - 1);
			int[] b = route.get(i);
			bits += region(a) == region(b) && a[2] == b[2] ? 1 + 12 : 1 + 30;
		}
		return bits;
	}

	/** C: B with an 8 by 8 chunk level: a point in the chunk of the one before sends 6 bits. */
	static int candidateC(List<int[]> route)
	{
		int bits = 30;
		for (int i = 1; i < route.size(); i++)
		{
			int[] a = route.get(i - 1);
			int[] b = route.get(i);
			boolean sameRegion = region(a) == region(b) && a[2] == b[2];
			boolean sameChunk = sameRegion && (a[0] >> 3) == (b[0] >> 3) && (a[1] >> 3) == (b[1] >> 3);
			bits += sameChunk ? 2 + 6 : sameRegion ? 2 + 12 : 1 + 30;
		}
		return bits;
	}

	/** D: same region, small delta, neighbouring region, medium delta, an earlier point, absolute (4 bit opcode). */
	static int candidateD(List<int[]> route)
	{
		return candidateD(route, true, true, true, false);
	}

	static int candidateD(List<int[]> route, boolean region, boolean neighbour, boolean repeat, boolean chunk)
	{
		int bits = abs(route.get(0));
		for (int i = 1; i < route.size(); i++)
		{
			int[] a = route.get(i - 1);
			int[] b = route.get(i);
			int dx = b[0] - a[0];
			int dy = b[1] - a[1];
			int best = 4 + abs(b);
			if (a[2] == b[2])
			{
				if (fits(dx, 4) && fits(dy, 4))
				{
					best = Math.min(best, 2 + 8);
				}
				if (chunk && (a[0] >> 3) == (b[0] >> 3) && (a[1] >> 3) == (b[1] >> 3) && region(a) == region(b))
				{
					best = Math.min(best, 3 + 6);
				}
				if (region && region(a) == region(b))
				{
					best = Math.min(best, 2 + 12);
				}
				int rx = (b[0] >> 6) - (a[0] >> 6);
				int ry = (b[1] >> 6) - (a[1] >> 6);
				if (neighbour && Math.abs(rx) <= 1 && Math.abs(ry) <= 1 && (rx != 0 || ry != 0))
				{
					best = Math.min(best, 2 + 3 + 12);
				}
				if (fits(dx, 8) && fits(dy, 8))
				{
					best = Math.min(best, 3 + 16);
				}
			}
			if (repeat)
			{
				for (int j = 0; j < i - 1; j++)
				{
					if (Arrays.equals(route.get(j), b))
					{
						best = Math.min(best, 4 + 6);
						break;
					}
				}
			}
			bits += best;
		}
		return bits;
	}

	/**
	 * E: D with shorter opcodes for an absolute point (3 bits) and the neighbouring region (3 bits), and a record for a
	 * point on another floor within the region of the one before.
	 */
	static int candidateE(List<int[]> route)
	{
		int bits = abs(route.get(0));
		for (int i = 1; i < route.size(); i++)
		{
			int[] a = route.get(i - 1);
			int[] b = route.get(i);
			int dx = b[0] - a[0];
			int dy = b[1] - a[1];
			int best = 3 + abs(b);
			boolean sameRegion = region(a) == region(b);
			if (a[2] == b[2])
			{
				if (fits(dx, 4) && fits(dy, 4))
				{
					best = Math.min(best, 2 + 8);
				}
				if (sameRegion)
				{
					best = Math.min(best, 2 + 12);
				}
				int rx = (b[0] >> 6) - (a[0] >> 6);
				int ry = (b[1] >> 6) - (a[1] >> 6);
				if (Math.abs(rx) <= 1 && Math.abs(ry) <= 1 && (rx != 0 || ry != 0))
				{
					best = Math.min(best, 3 + 3 + 12);
				}
				if (fits(dx, 8) && fits(dy, 8))
				{
					best = Math.min(best, 3 + 16);
				}
			}
			else if (sameRegion)
			{
				best = Math.min(best, 4 + 2 + 12);
			}
			for (int j = 0; j < i - 1; j++)
			{
				if (Arrays.equals(route.get(j), b))
				{
					best = Math.min(best, 4 + 6);
					break;
				}
			}
			bits += best;
		}
		return bits;
	}

	/** Characters after {@code !wmp }: two for the type and version, then the bits in base 32. */
	static int chars(int waypointBits)
	{
		return 2 + (COMMON_BITS + waypointBits + 4) / 5;
	}

	/** Chat messages needed: one when the code fits 71 characters, else parts of 66 characters of the code each. */
	static int messages(int chars)
	{
		return chars <= 71 ? 1 : (chars + 65) / 66;
	}

	static List<int[]> route(int... xyp)
	{
		List<int[]> points = new ArrayList<>();
		for (int i = 0; i < xyp.length; i += 3)
		{
			points.add(new int[] {xyp[i], xyp[i + 1], xyp[i + 2]});
		}
		return points;
	}

	/** The scenarios of the benchmark, by name. Coordinates are real places where they are named. */
	static Map<String, List<int[]>> scenarios()
	{
		Map<String, List<int[]>> s = new LinkedHashMap<>();
		s.put("pin in region 4922 (Farming Guild)", route(1249, 3720, 0));
		s.put("5 stops in one region (Lumbridge castle)", route(3222, 3218, 0, 3208, 3220, 0, 3235, 3205, 0, 3214, 3240, 0, 3200, 3204, 0));
		s.put("10 stops in one region (Varrock square area)", route(3212, 3424, 0, 3220, 3434, 0, 3195, 3410, 0, 3230, 3400, 0,
			3209, 3441, 0, 3226, 3418, 0, 3201, 3430, 0, 3238, 3445, 0, 3215, 3405, 0, 3199, 3446, 0));
		s.put("5 stops in one 8x8 chunk", route(3222, 3218, 0, 3220, 3219, 0, 3217, 3221, 0, 3223, 3222, 0, 3219, 3216, 0));
		s.put("nearby stops across region edges", route(3198, 3200, 0, 3202, 3197, 0, 3196, 3203, 0, 3205, 3204, 0, 3199, 3195, 0));
		s.put("across several cities (Lumbridge, Draynor, Falador, Varrock)", route(3222, 3218, 0, 3093, 3244, 0, 2965, 3380, 0,
			3212, 3424, 0, 3253, 3420, 0));
		s.put("long jumps (herb run)", route(3058, 3311, 0, 2813, 3463, 0, 2670, 3374, 0, 3605, 3529, 0, 1238, 3726, 0, 1738, 3550,
			0, 2826, 3694, 0, 2847, 3931, 0));
		s.put("different planes (Lumbridge castle floors)", route(3208, 3220, 0, 3205, 3209, 1, 3208, 3220, 2, 3210, 3221, 2,
			3209, 3218, 1));
		s.put("underground and surface (Varrock sewers)", route(3237, 3458, 0, 3237, 9858, 0, 3245, 9866, 0, 3210, 9897, 0));
		s.put("repeated locations (bank between stops)", route(3185, 3436, 0, 3170, 3410, 0, 3185, 3436, 0, 3250, 3420, 0,
			3185, 3436, 0, 3110, 3420, 0, 3185, 3436, 0));
		Random random = new Random(49);
		List<int[]> scattered = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			scattered.add(new int[] {960 + random.nextInt(3072), 1216 + random.nextInt(11328), random.nextInt(4)});
		}
		s.put("12 random coordinates", scattered);
		List<int[]> walk = new ArrayList<>();
		int x = 2440;
		int y = 3090;
		for (int i = 0; i < 40; i++)
		{
			x += random.nextInt(81) - 40;
			y += random.nextInt(81) - 40;
			walk.add(new int[] {x, y, 0});
		}
		s.put("40 stops, 0 to 40 tiles apart", walk);
		List<int[]> far = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			far.add(new int[] {960 + random.nextInt(3072), 2048 + random.nextInt(2176), 0});
		}
		s.put("40 stops scattered over the surface", far);
		return s;
	}

	public static void main(String[] args)
	{
		Map<String, ToIntFunction<List<int[]>>> candidates = new LinkedHashMap<>();
		candidates.put("naive", ShareBench::naive);
		candidates.put("A", ShareBench::candidateA);
		candidates.put("B", ShareBench::candidateB);
		candidates.put("C", ShareBench::candidateC);
		candidates.put("D", ShareBench::candidateD);
		candidates.put("E", ShareBench::candidateE);
		candidates.put("D-region", r -> candidateD(r, false, true, true, false));
		candidates.put("D-neighbour", r -> candidateD(r, true, false, true, false));
		candidates.put("D-repeat", r -> candidateD(r, true, true, false, false));
		candidates.put("D+chunk", r -> candidateD(r, true, true, true, true));
		StringBuilder head = new StringBuilder("| Scenario | stops |");
		for (String name : candidates.keySet())
		{
			head.append(' ').append(name).append(" |");
		}
		System.out.println(head);
		Map<String, Integer> totals = new LinkedHashMap<>();
		for (Map.Entry<String, List<int[]>> scenario : scenarios().entrySet())
		{
			StringBuilder row = new StringBuilder("| " + scenario.getKey() + " | " + scenario.getValue().size() + " |");
			for (Map.Entry<String, ToIntFunction<List<int[]>>> candidate : candidates.entrySet())
			{
				int bits = candidate.getValue().applyAsInt(scenario.getValue());
				int chars = chars(bits);
				row.append(' ').append(bits).append(" b, ").append(chars).append(" c, ").append(5 + chars).append(" chat");
				int messages = messages(chars);
				row.append(messages > 1 ? ", " + messages + " msgs" : "").append(" |");
				totals.merge(candidate.getKey(), chars, Integer::sum);
			}
			System.out.println(row);
		}
		System.out.println("total characters: " + totals);
	}
}
