package com.worldmapplus.farming;

/**
 * The few farming regions whose patches are only valid in part of the region.
 * <p>
 * The game sends a patch's variable under the same number everywhere, so the region alone does not say
 * which patches are being sent. These rules are translated from the {@code isInBounds} overrides in
 * RuneLite's Time Tracking plugin (BSD 2-Clause, Copyright (c) 2018 Abex and contributors).
 */
final class FarmingBounds
{
	private FarmingBounds()
	{
	}

	/** True when the patches of a region are being sent for a place. A null rule means the whole region. */
	static boolean contains(String rule, int x, int y, int plane)
	{
		if (rule == null)
		{
			return true;
		}
		switch (rule)
		{
			case "catherby":
				// Upstairs sends different variables.
				if (x >= 2816 && y < 3456)
				{
					return x < 2840 && y >= 3440 && plane == 0;
				}
				return true;
			case "catherby_fruit":
				// The fruit tree patch is always sent when upstairs.
				return x >= 2840 || y < 3440 || plane == 1;
			case "falador":
				// Not on the region boundary, because of the Port Sarim spirit tree patch.
				return y >= 3272;
			case "port_sarim":
				return y < 3272;
			case "fossil":
				// The hardwood variables are sent anywhere on the ground floor of the island, and one tick
				// early when climbing certain ladders and stairs.
				if (x == 3753 && y >= 3868 && y <= 3870)
				{
					return false;
				}
				if ((x == 3729 || x == 3728 || x == 3747 || x == 3746) && y <= 3832 && y >= 3830)
				{
					return false;
				}
				return plane == 0;
			default:
				return true;
		}
	}
}
