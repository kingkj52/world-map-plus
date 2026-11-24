package com.worldmapplus.farming;

import com.worldmapplus.layers.HoverCard;
import java.util.List;
import javax.annotation.Nullable;

/** Builds the card for a farming point on the map, from what the tracker last saw. */
public final class FarmingCards
{
	private FarmingCards()
	{
	}

	/**
	 * The card for a farming point, or null when the tables know no patches there.
	 *
	 * @param name what the point is called, such as {@code Allotment/Herb/Flower patch}
	 * @param x the point's tile
	 */
	@Nullable
	public static HoverCard build(FarmingTracker tracker, String name, int x, int y)
	{
		FarmingData data = tracker.getData();
		List<FarmingData.Located> patches = data.patchesFor(name, x, y);
		if (patches.isEmpty())
		{
			return null;
		}
		long now = tracker.now();
		String place = patches.get(0).getRegion().getName();
		boolean one = true;
		for (FarmingData.Located located : patches)
		{
			one &= located.getRegion().getName().equals(place);
		}
		HoverCard.Builder card = HoverCard.builder(one ? place : "Farming patches")
			.subtitle("What was there when you were last near");

		boolean any = false;
		for (FarmingData.Located located : patches)
		{
			FarmingData.Patch patch = located.getPatch();
			Observation seen = tracker.observation(located.getRegion(), patch);
			String label = patch.label();
			if (seen == null)
			{
				card.row(label, "not seen yet");
				continue;
			}
			any = true;
			Forecast.Result result = Forecast.describe(data.decode(patch.getKind(), seen.getValue()), seen, now);
			card.row(label, result.getHeadline());
			String detail = result.getTiming().isEmpty()
				? Forecast.seenText(seen.getSeenMillis(), now)
				: result.getTiming() + ", " + Forecast.seenText(seen.getSeenMillis(), now);
			card.row("   ", detail);
		}
		if (!any)
		{
			card.hint("Walk past it and it will be filled in");
		}
		return card.build();
	}
}
