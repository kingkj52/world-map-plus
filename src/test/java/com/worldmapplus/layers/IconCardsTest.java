package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The card of a game icon: the floor it is on, and what the points of interest merged into it add while their layers are on. */
public class IconCardsTest
{
	private static final Set<String> NONE = Collections.emptySet();

	private static Set<String> on(String... layers)
	{
		return new HashSet<>(Arrays.asList(layers));
	}

	private static HoverCard bare(String kind, String near)
	{
		HoverCard.Builder card = HoverCard.builder(kind);
		if (near != null)
		{
			card.subtitle(near);
		}
		return card.build();
	}

	private static String rowValue(HoverCard card, String label)
	{
		for (HoverCard.Row row : card.visibleRows(true))
		{
			if (row.getLabel().equals(label))
			{
				return row.getValue();
			}
		}
		return null;
	}

	@Test
	public void floorsAreNamedLikeTheGameDoesInTheUk()
	{
		assertEquals("Ground floor", FloorNames.name(0));
		assertEquals("1st floor", FloorNames.name(1));
		assertEquals("2nd floor", FloorNames.name(2));
		assertEquals("3rd floor", FloorNames.name(3));
		assertEquals("4th floor", FloorNames.name(4));
		assertEquals("11th floor", FloorNames.name(11));
		assertEquals("21st floor", FloorNames.name(21));
		assertEquals("", FloorNames.label(0));
		assertEquals("2nd floor", FloorNames.label(2));
	}

	@Test
	public void theGroundFloorHasNoLabelAndTheOthersSayWhichFloor()
	{
		HoverCard base = bare("Bank", "Near Lumbridge");
		assertSame("nothing to add to a ground floor icon", base, IconCards.compose(base, 0, Collections.emptyList(), NONE));
		for (int floor = 1; floor <= 3; floor++)
		{
			HoverCard card = IconCards.compose(base, floor, Collections.emptyList(), NONE);
			assertEquals("Bank", card.getTitle());
			assertEquals(FloorNames.name(floor), rowValue(card, IconCards.FLOOR_LABEL));
		}
		assertNull(rowValue(IconCards.compose(base, 0, Collections.emptyList(), NONE), IconCards.FLOOR_LABEL));
	}

	@Test
	public void aLayerThatIsOffAddsNothing()
	{
		HoverCard base = bare("Rare trees", "Near Lumbridge");
		List<IconExtra> extras = Collections.singletonList(new IconExtra("tree", "Yew tree", "Woodcutting level 60", 3100, 3200));
		assertSame(base, IconCards.compose(base, 0, extras, NONE));
		assertSame(base, IconCards.compose(base, 0, extras, on("fishing")));
		assertEquals("Yew tree", IconCards.compose(base, 0, extras, on("tree")).getTitle());
	}

	@Test
	public void aSinglePointNamesABareCardAndTheKindMovesUnderIt()
	{
		HoverCard base = bare("Rare trees", "Near Lumbridge");
		List<IconExtra> extras = Collections.singletonList(new IconExtra("tree", "Blisterwood tree", "Woodcutting level 62", 3631, 3362));
		HoverCard card = IconCards.compose(base, 0, extras, on("tree"));
		assertEquals("Blisterwood tree", card.getTitle());
		assertEquals("Woodcutting level 62", card.getSubtitle());
		assertEquals("the place stays on the card", "Lumbridge", rowValue(card, "Near"));
	}

	@Test
	public void aPointThatOnlyRepeatsTheKindLeavesTheKindUnderTheName()
	{
		HoverCard base = bare("Minigame", null);
		List<IconExtra> extras = Collections.singletonList(new IconExtra("minigame", "Pest Control Novice", "Minigame", 2660, 2637));
		HoverCard card = IconCards.compose(base, 0, extras, on("minigame"));
		assertEquals("Pest Control Novice", card.getTitle());
		assertEquals("Minigame", card.getSubtitle());
	}

	@Test
	public void aPointNamedForItsPlaceGivesARowForWhatIsThere()
	{
		HoverCard base = bare("Fishing spot", "Near Al Kharid");
		List<IconExtra> extras = Collections.singletonList(new IconExtra("fishing", "Al Kharid", "Fishing: Shrimp, Anchovies", 3274, 3140));
		HoverCard card = IconCards.compose(base, 0, extras, on("fishing"));
		assertEquals("Fishing spot", card.getTitle());
		assertEquals("Near Al Kharid", card.getSubtitle());
		assertEquals("Shrimp, Anchovies", rowValue(card, "Fish"));

		HoverCard hunter = IconCards.compose(bare("Hunter training", null), 0,
			Collections.singletonList(new IconExtra("hunter", "Aldarin West", "Hunting: Ruby Harvest", 1342, 2934)), on("hunter"));
		assertEquals("Ruby Harvest", rowValue(hunter, "Creatures"));
		HoverCard mining = IconCards.compose(bare("Mining site", null), 0,
			Collections.singletonList(new IconExtra("mining", "Uzer", "Mining: Clay x10", 3415, 3160)), on("mining"));
		assertEquals("Clay x10", rowValue(mining, "Ore"));
	}

	@Test
	public void severalPointsMakeARowEachAndRepeatsAreOneRow()
	{
		HoverCard base = bare("Fishing spot", null);
		List<IconExtra> extras = new ArrayList<>();
		extras.add(new IconExtra("fishing", "Seers Village", "Fishing: Salmon", 2725, 3524));
		extras.add(new IconExtra("fishing", "Seers Village", "Fishing: Salmon", 2714, 3531));
		extras.add(new IconExtra("fishing", "Seers Village North", "Fishing: Trout", 2714, 3540));
		HoverCard card = IconCards.compose(base, 0, extras, on("fishing"));
		assertEquals(2, card.getRows().size());
		assertEquals("Salmon", rowValue(card, "Seers Village"));
		assertEquals("Trout", rowValue(card, "Seers Village North"));
	}

	@Test
	public void aCardThatSaysMoreThanAKindKeepsEverythingItSays()
	{
		HoverCard shop = HoverCard.builder("General store").subtitle("Run by Shopkeeper").row("Pot", "1 coin").hint("Hint").build();
		List<IconExtra> extras = Collections.singletonList(new IconExtra("tree", "Oak tree", "Woodcutting level 15", 1, 2));
		HoverCard card = IconCards.compose(shop, 1, extras, on("tree"));
		assertEquals("General store", card.getTitle());
		assertEquals("Run by Shopkeeper", card.getSubtitle());
		assertEquals("1 coin", rowValue(card, "Pot"));
		assertEquals("Woodcutting level 15", rowValue(card, "Oak tree"));
		assertEquals("1st floor", rowValue(card, "Floor"));
		assertEquals("Hint", card.getHint());
	}

	@Test
	public void informationWithNoLayerIsAlwaysShown()
	{
		HoverCard base = bare("Bank", "Near Lletya");
		List<IconExtra> extras = Collections.singletonList(new IconExtra(null, "Bank: Lletya", "Bank, needs Mourning's End Part I", 2352, 3162));
		HoverCard card = IconCards.compose(base, 0, extras, NONE);
		assertEquals("Bank: Lletya", card.getTitle());
		assertEquals("Bank, needs Mourning's End Part I", card.getSubtitle());
		assertFalse(card.getRows().isEmpty());
	}

	@Test
	public void aLongListWaitsForShift()
	{
		HoverCard base = bare("Rare trees", null);
		List<IconExtra> extras = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			extras.add(new IconExtra("tree", "Tree " + i, "Woodcutting level " + i, i, i));
		}
		HoverCard card = IconCards.compose(base, 0, extras, on("tree"));
		assertEquals(IconCards.MAX_ROWS, card.getRows().size());
		assertEquals(8, card.getOverflow().size());
		assertTrue(rowValue(card, "Tree 19") != null);
	}
}
