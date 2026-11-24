package com.worldmapplus.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ItemKindsTest
{
	private static boolean is(String item, String kind)
	{
		return ItemKinds.classify(item).contains(kind);
	}

	@Test
	public void swordsAndTheirKindAreMeleeWeapons()
	{
		for (String item : new String[] {"Iron sword", "Bronze scimitar", "Rune dagger", "Steel mace", "Iron spear",
			"Dragon halberd", "Abyssal whip", "Iron battleaxe", "Steel warhammer", "Adamant longsword"})
		{
			assertTrue(item, is(item, ItemKinds.MELEE));
		}
	}

	@Test
	public void aSwordfishIsFoodNotAWeapon()
	{
		assertTrue(is("Raw swordfish", ItemKinds.FOOD));
		assertFalse(is("Raw swordfish", ItemKinds.MELEE));
	}

	@Test
	public void bowsAndAmmoAreTold()
	{
		assertTrue(is("Shortbow", ItemKinds.RANGED));
		assertTrue(is("Oak longbow", ItemKinds.RANGED));
		assertTrue(is("Crossbow", ItemKinds.RANGED));
		assertTrue(is("Iron arrow", ItemKinds.AMMO));
		assertTrue(is("Bronze bolts", ItemKinds.AMMO));
		assertFalse(is("Iron arrow", ItemKinds.MELEE));
		assertFalse("an arrow is not a bow", is("Iron arrow", ItemKinds.RANGED));
	}

	@Test
	public void staffsAndWandsAreMagic()
	{
		assertTrue(is("Staff of air", ItemKinds.MAGIC));
		assertTrue(is("Magic staff", ItemKinds.MAGIC));
		assertTrue(is("Wand", ItemKinds.MAGIC));
	}

	@Test
	public void anAxeIsAToolAndAWeapon()
	{
		List<String> axe = ItemKinds.classify("Bronze axe");
		assertTrue(axe.contains(ItemKinds.TOOL));
		assertTrue(axe.contains(ItemKinds.MELEE));
		assertTrue(is("Iron pickaxe", ItemKinds.TOOL));
		assertFalse(is("Iron pickaxe", ItemKinds.MELEE));
	}

	@Test
	public void runesAreNotRuneArmour()
	{
		assertTrue(is("Law rune", ItemKinds.RUNE));
		assertTrue(is("Rune scimitar", ItemKinds.MELEE));
		assertFalse(is("Rune scimitar", ItemKinds.RUNE));
	}

	@Test
	public void otherKinds()
	{
		assertTrue(is("Iron platebody", ItemKinds.ARMOUR));
		assertTrue(is("Bronze full helm", ItemKinds.ARMOUR));
		assertTrue(is("Bronze kiteshield", ItemKinds.ARMOUR));
		assertTrue(is("Guam seed", ItemKinds.SEED));
		assertFalse(is("Potato seed", ItemKinds.FOOD));
		assertTrue(is("Iron ore", ItemKinds.ORE));
		assertTrue(is("Bronze bar", ItemKinds.BAR));
		assertTrue(is("Oak logs", ItemKinds.LOG));
		assertTrue(is("Amulet of strength", ItemKinds.JEWELLERY));
		assertTrue(is("Gold ring", ItemKinds.JEWELLERY));
		assertTrue(is("Attack potion(3)", ItemKinds.POTION));
		assertTrue(is("Bread", ItemKinds.FOOD));
		assertTrue(is("Hammer", ItemKinds.TOOL));
	}

	@Test
	public void unknownNamesAndNullGiveNothing()
	{
		assertTrue(ItemKinds.classify("Mystery thing").isEmpty());
		assertTrue(ItemKinds.classify(null).isEmpty());
		assertEquals("", ItemKinds.join(ItemKinds.classify("Mystery thing")));
		assertEquals("melee_weapon", ItemKinds.join(ItemKinds.classify("Iron sword")));
	}
}
