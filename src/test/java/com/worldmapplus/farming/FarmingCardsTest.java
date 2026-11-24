package com.worldmapplus.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.HoverCard;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class FarmingCardsTest
{
	private final Map<String, String> saved = new HashMap<>();
	private long now = 10_000_000L;
	private FarmingTracker tracker;

	@Before
	public void setUp()
	{
		FarmingTracker.Store store = new FarmingTracker.Store()
		{
			@Override
			public String get(String key)
			{
				return saved.get(key);
			}

			@Override
			public void put(String key, String value)
			{
				saved.put(key, value);
			}
		};
		tracker = new FarmingTracker(null, FarmingData.load(new Gson()), store, () -> now);
	}

	@Test
	public void aPatchNeverSeenSaysSo()
	{
		HoverCard card = FarmingCards.build(tracker, "Allotment/Herb/Flower patch", 3052, 3309);
		assertNotNull(card);
		assertEquals("Falador", card.getTitle());
		assertEquals(4, card.getRows().size());
		for (HoverCard.Row row : card.getRows())
		{
			assertEquals("not seen yet", row.getValue());
		}
		assertTrue(card.getHint().contains("Walk past"));
	}

	@Test
	public void aSeenPatchShowsWhatIsGrowingAndWhenItIsReady()
	{
		// Falador, herb patch: a Ranarr seed, planted and seen ten minutes ago.
		saved.put("farming.12083.4774", new Observation(32, (now - 10 * 60_000L), false).write());
		HoverCard card = FarmingCards.build(tracker, "Allotment/Herb/Flower patch", 3052, 3309);
		String text = "";
		for (HoverCard.Row row : card.getRows())
		{
			text += row.getLabel() + "=" + row.getValue() + "\n";
		}
		assertTrue(text, text.contains("Herb=Ranarr, growing (stage 1 of 5)"));
		assertTrue(text, text.contains("Ready in 50 min to 1h 10m"));
		assertTrue(text, text.contains("seen 10 min ago"));
		assertNull(card.getHint());
	}

	@Test
	public void aPointWithNoPatchesGivesNoCard()
	{
		assertNull(FarmingCards.build(tracker, "Banana patch", 3052, 3309));
		assertNull(FarmingCards.build(tracker, "Herb patch", 3222, 3218));
	}
}
