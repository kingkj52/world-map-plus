package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.map.MapCatalog;
import java.util.List;
import org.junit.Test;

public class ShortcutLoaderTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void theBundledTableGivesCardsOnTheIconsItCovers()
	{
		List<Hotspot> cards = ShortcutLoader.load(GSON, MapCatalog.load(GSON));
		assertTrue("shortcut cards " + cards.size(), cards.size() > 80);
		for (Hotspot card : cards)
		{
			assertFalse(card.getCard().getTitle().isEmpty());
			assertFalse(card.getCard().getTitle().contains("<"));
			assertEquals(Hotspot.Kind.TRANSPORT, card.getKind());
		}
	}

	@Test
	public void aShortcutCardSaysWhatItNeedsAndLinksToWhereItComesOut()
	{
		String json = "{\"shortcuts\":[{\"pin\":[2936.5,3355.5],\"options\":[{\"name\":\"Crumbling wall\",\"action\":\"Climb-over\","
			+ "\"skills\":[\"5 Agility\"],\"items\":[\"Rope\"],\"quests\":[\"Watchtower\"],\"ticks\":8,\"oneWay\":false,"
			+ "\"a\":[2936,3355,0],\"b\":[2930,3355,0]}]}]}";
		ShortcutLoader.File file = GSON.fromJson(json, ShortcutLoader.File.class);
		List<Hotspot> cards = ShortcutLoader.hotspots(file, MapCatalog.load(GSON));
		assertEquals(1, cards.size());
		Hotspot card = cards.get(0);
		assertEquals(2936.5, card.getX(), 0);
		assertEquals("Crumbling wall", card.getCard().getTitle());
		assertEquals("Agility shortcut", card.getCard().getSubtitle());
		StringBuilder rows = new StringBuilder();
		for (HoverCard.Row row : card.getCard().getRows())
		{
			rows.append(row.getLabel()).append('=').append(row.getValue()).append(';');
		}
		assertTrue(rows.toString(), rows.toString().contains("Needs=Rope"));
		assertTrue(rows.toString().contains("Quest=Watchtower"));
		assertTrue(rows.toString().contains("Requires=5 Agility"));
		assertTrue(rows.toString().contains("both ways"));
		assertEquals("a line to the far side only, since one end is where the icon is", 1, card.getLinks().size());
		assertEquals(2930.5, card.getLinks().get(0).getX(), 0);
		assertNotNull("a click goes to the far side", card.getTarget());
	}

	@Test
	public void severalShortcutsOnOneIconAreListedAndMarkupIsKeptOut()
	{
		String json = "{\"shortcuts\":[{\"pin\":[2936.5,3355.5],\"options\":["
			+ "{\"name\":\"<b>Wall</b>\",\"action\":\"Climb\",\"skills\":[\"5 Agility\"],\"a\":[2936,3355,0],\"b\":[2930,3355,0]},"
			+ "{\"name\":\"Fence\",\"action\":\"Jump\",\"skills\":[\"11 Agility\",\"19 Ranged\"],\"a\":[2936,3355,0],\"b\":[2936,3362,0]}]}]}";
		List<Hotspot> cards = ShortcutLoader.hotspots(GSON.fromJson(json, ShortcutLoader.File.class), MapCatalog.load(GSON));
		assertEquals("Agility shortcuts", cards.get(0).getCard().getTitle());
		assertEquals("2 here", cards.get(0).getCard().getSubtitle());
		assertEquals("bWall/b", cards.get(0).getCard().getRows().get(0).getLabel());
		assertEquals("Fence", cards.get(0).getCard().getRows().get(1).getLabel());
		assertEquals("11 Agility, 19 Ranged", cards.get(0).getCard().getRows().get(1).getValue());
		assertEquals(2, cards.get(0).getLinks().size());
	}
}
