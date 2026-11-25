package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.IconExtra;
import com.worldmapplus.unlock.Judge;
import com.worldmapplus.unlock.Need;
import com.worldmapplus.unlock.Needs;
import java.util.Collections;
import org.junit.Test;

/** Round 47: the requirements of icons read from requirements.json, matched by place or by name, and what the card gets. */
public class IconRequirementsTest
{
	private static final Gson GSON = new Gson();

	private static IconRequirements read(String json)
	{
		return IconRequirements.of(GSON.fromJson(json, IconRequirements.File.class));
	}

	private static Hotspot card(String title)
	{
		return new Hotspot(0, 10.5, 20.5, 8, HoverCard.builder(title).build(), null);
	}

	@Test
	public void anEntryByNameGivesTheNeedAndTheWikisWords()
	{
		IconRequirements requirements = read("{\"icons\":[{\"kind\":\"Agility training\",\"name\":\"Ardougne Rooftop Course\","
			+ "\"page\":\"Ardougne Rooftop Course\",\"requires\":[\"90 Agility\"]}]}");
		assertEquals(1, requirements.size());
		Hotspot plain = card("Agility training");
		IconExtra point = new IconExtra("agility", "Ardougne Rooftop Course", "Agility course", 10, 20);
		Hotspot result = requirements.apply(plain, "Agility training", 0, 10.5, 20.5, 0, Collections.singletonList(point));
		assertNotNull(result.getNeed());
		assertEquals("90 Agility", Judge.ways(result.getNeed())[0].describe());
		assertEquals("Requires", result.getCard().getRows().get(0).getLabel());
		assertEquals("90 Agility", result.getCard().getRows().get(0).getValue());
		// Another kind of icon with the same name is left alone.
		assertSame(plain, requirements.apply(plain, "Bank", 0, 10.5, 20.5, 0, Collections.singletonList(point)));
	}

	@Test
	public void anEntryByPlaceAndSeveralWays()
	{
		IconRequirements requirements = read("{\"icons\":[{\"kind\":\"Dungeon\",\"name\":\"God Wars Dungeon\",\"at\":[0,10.5,20.5,0],"
			+ "\"ways\":[[\"60 Strength\"],[\"60 Agility\"]]}]}");
		Hotspot result = requirements.apply(card("Somewhere"), "Dungeon", 0, 10.5, 20.5, 0, null);
		assertEquals(2, Judge.ways(result.getNeed()).length);
		assertEquals("60 Strength or 60 Agility", result.getCard().getRows().get(0).getValue());
		// Another floor or place is not the icon.
		assertNull(requirements.apply(card("Somewhere"), "Dungeon", 0, 10.5, 20.5, 1, null).getNeed());
		assertNull(requirements.apply(card("Somewhere"), "Dungeon", 0, 11.5, 20.5, 0, null).getNeed());
	}

	@Test
	public void aCardThatAlreadyAsksSomethingIsKept()
	{
		IconRequirements requirements = read("{\"icons\":[{\"kind\":\"Dungeon\",\"name\":\"A\",\"requires\":[\"90 Agility\"]},"
			+ "{\"kind\":\"Dungeon\",\"name\":\"B\",\"requires\":[\"a rope\"]}]}");
		Need own = Needs.ofWay(Collections.singletonList("50 Agility"), null);
		Hotspot asks = card("A").withNeed(own);
		assertSame(asks, requirements.apply(asks, "Dungeon", 0, 10.5, 20.5, 0, null));
		// Words the Helper cannot read are kept too: it judges them as asking nothing, so they are never held against the player.
		assertTrue(requirements.apply(card("B"), "Dungeon", 0, 10.5, 20.5, 0, null).getNeed() != null);
	}

	@Test
	public void damagedOrEmptyInputGivesNoRequirementsAndNeverThrows()
	{
		assertEquals(0, IconRequirements.of(null).size());
		assertEquals(0, read("{}").size());
		assertEquals(0, read("{\"icons\":[null,{},{\"kind\":\"Dungeon\"},{\"kind\":\"Dungeon\",\"name\":\"X\",\"requires\":[]},"
			+ "{\"kind\":\"Dungeon\",\"at\":[1,2],\"requires\":[\"9 Agility\"]}]}").size());
		assertTrue(IconRequirements.load(GSON).size() >= 60);
	}
}
