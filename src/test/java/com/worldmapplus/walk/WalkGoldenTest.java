package com.worldmapplus.walk;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 73: the walking paths of the bundled data for a set of start and goal pairs (open ground, walls, buildings, doors, upper floors,
 * no way in, too far, unknown ground), and what the planner answers for a journey asked again and again as the player walks along it,
 * written before the planner was made tighter ({@code golden/walk-paths.txt}). What is found now is written to
 * {@code build/walk-paths-actual.txt}.
 */
public class WalkGoldenTest
{
	private static final int[][] PAIRS = {
		{3222, 3218, 3222, 3230, 0},
		{3222, 3218, 3200, 3220, 0},
		{3222, 3218, 3234, 3232, 0},
		{3222, 3218, 3222, 3218, 0},
		{3222, 3218, 3105, 3250, 0},
		{3222, 3218, 3253, 3420, 0},
		{3210, 3424, 3185, 3436, 0},
		{3164, 3487, 3212, 3428, 0},
		{2965, 3380, 2945, 3370, 0},
		{3093, 3244, 3105, 3250, 0},
		{3208, 3220, 3208, 3220, 1},
		{3205, 3209, 3212, 3226, 1},
		{3205, 3209, 3212, 3226, 2},
		{2757, 3477, 2724, 3492, 0},
		{3253, 3420, 3290, 3374, 0},
		{3080, 3425, 3093, 3492, 0},
		{2440, 3090, 2470, 3120, 0},
		{3000, 3000, 3010, 3010, 0},
		{1, 1, 10, 10, 0},
		{3222, 3218, 2000, 2000, 0},
		{3222, 3218, 3600, 3500, 0},
		{3238, 3295, 3260, 3240, 0},
		{3109, 3168, 3122, 3164, 0},
		{3109, 3168, 3109, 3168, 2},
		{2662, 3305, 2615, 3335, 0},
		{3400, 2900, 3420, 2930, 0},
		{3655, 3480, 3680, 3500, 0},
		{2524, 3576, 2540, 3550, 0},
		{3086, 3517, 3100, 3540, 0},
		{3232, 3226, 3236, 3226, 0},
	};

	private static String path(int[] path)
	{
		if (path == null)
		{
			return "none";
		}
		StringBuilder out = new StringBuilder();
		out.append(path.length / 2).append(" tiles:");
		for (int i = 0; i + 1 < path.length; i += 2)
		{
			out.append(' ').append(path[i]).append(',').append(path[i + 1]);
		}
		return out.toString();
	}

	@Test
	public void theWalksOfTheBundledDataAreAsBeforeTheTighterPlanner() throws IOException
	{
		CollisionMap map = CollisionMap.load();
		StringBuilder out = new StringBuilder("# start x y, goal x y, floor: the way\n");
		for (int[] p : PAIRS)
		{
			out.append(Arrays.toString(p)).append(' ').append(path(WalkPath.find(map, p[0], p[1], p[2], p[3], p[4]))).append('\n');
		}

		out.append("# the planner, answering at once: a journey asked as the player walks along it, off it, and to a new goal\n");
		List<Runnable> waiting = new ArrayList<>();
		WalkPlanner planner = new WalkPlanner(waiting::add, () -> map);
		int[][] asks = {
			{3222, 3218, 3200, 3220}, {3222, 3218, 3200, 3220}, {3222, 3219, 3200, 3220}, {3221, 3220, 3200, 3220},
			{3230, 3230, 3200, 3220}, {3230, 3230, 3200, 3220}, {3222, 3218, 3200, 3220}, {3222, 3218, 3210, 3210},
			{3222, 3218, 3234, 3232}, {3222, 3218, 3234, 3232}, {3223, 3218, 3234, 3232},
		};
		for (int[] a : asks)
		{
			int[] answer = planner.lookup("step0", a[0], a[1], a[2], a[3], 0);
			out.append(Arrays.toString(a)).append(" asked: ").append(answer == null ? "null" : path(answer)).append(" (")
				.append(waiting.size()).append(" searches started)\n");
			for (Runnable run : waiting)
			{
				run.run();
			}
			waiting.clear();
			int[] after = planner.lookup("step0", a[0], a[1], a[2], a[3], 0);
			out.append("  then: ").append(after == null ? "null" : path(after)).append(" (").append(waiting.size())
				.append(" searches started)\n");
			for (Runnable run : waiting)
			{
				run.run();
			}
			waiting.clear();
		}
		out.append("# two journeys side by side, and the same journey under another name\n");
		int[] one = planner.lookup("step1", 3222, 3218, 3222, 3230, 0);
		int[] two = planner.lookup("step2", 3222, 3218, 3222, 3230, 0);
		out.append("step1 ").append(one == null ? "null" : path(one)).append(", step2 ").append(two == null ? "null" : path(two))
			.append(" (").append(waiting.size()).append(" searches started)\n");
		planner.release();
		out.append("after release: ").append(planner.lookup("step0", 3222, 3218, 3200, 3220, 0) == null ? "null" : "an answer")
			.append(" (").append(waiting.size()).append(" searches started)\n");

		String text = out.toString();
		Path actual = Paths.get("build", "walk-paths-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "walk-paths.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String[] a = expected.split("\n", -1);
		String[] b = text.split("\n", -1);
		for (int i = 0; i < Math.min(a.length, b.length); i++)
		{
			assertEquals("line " + (i + 1), a[i], b[i]);
		}
		assertEquals("lines", a.length, b.length);
	}
}
