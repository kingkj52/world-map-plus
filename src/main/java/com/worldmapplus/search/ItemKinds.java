package com.worldmapplus.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sorts an item into rough kinds from its name, so a search can ask for {@code type:melee_weapon}.
 * <p>
 * The wiki's shop data has no item types, so this is a guess from the name and is meant to be right for
 * the common cases: a sword is a melee weapon, a shortbow a ranged weapon. An item can be several kinds,
 * as a bronze axe is both a tool and a melee weapon. Nothing here is looked up in the game.
 */
public final class ItemKinds
{
	public static final String MELEE = "melee_weapon";
	public static final String RANGED = "ranged_weapon";
	public static final String MAGIC = "magic_weapon";
	public static final String AMMO = "ammo";
	public static final String ARMOUR = "armour";
	public static final String FOOD = "food";
	public static final String POTION = "potion";
	public static final String TOOL = "tool";
	public static final String RUNE = "rune";
	public static final String SEED = "seed";
	public static final String ORE = "ore";
	public static final String BAR = "bar";
	public static final String LOG = "log";
	public static final String JEWELLERY = "jewellery";

	/** Every kind, for help text and tests. */
	public static final String[] ALL = {MELEE, RANGED, MAGIC, AMMO, ARMOUR, FOOD, POTION, TOOL, RUNE, SEED, ORE, BAR,
		LOG, JEWELLERY};

	private static final String[] FISH_AND_FOOD = {"swordfish", "shrimp", "anchovies", "sardine", "herring", "trout",
		"salmon", "tuna", "lobster", "shark", "bass", "cod", "pike", "mackerel", "monkfish", "karambwan", "manta",
		"bread", "cake", "pie", "pizza", "stew", "cheese", "chocolate", "kebab", "banana", "potato", "onion",
		"cabbage", "tomato", "cooked", "meat", "chicken", "beef", "wine", "ale", "beer", "gin", "cocktail", "bun",
		"sandwich", "egg", "milk", "orange", "apple", "pineapple", "lemon", "lime", "grapes", "dessert", "doughnut"};

	private ItemKinds()
	{
	}

	/** The kinds an item name suggests, in a fixed order. Empty when nothing is suggested. */
	public static List<String> classify(String itemName)
	{
		List<String> kinds = new ArrayList<>();
		if (itemName == null)
		{
			return kinds;
		}
		String name = itemName.toLowerCase(Locale.ROOT).trim();
		String[] words = name.replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
		String last = words.length == 0 ? "" : words[words.length - 1];

		boolean food = containsAny(name, FISH_AND_FOOD) && !last.equals("rune") && !last.equals("seed")
			&& !last.equals("seeds") && !name.contains("arrow") && !name.contains("pickaxe");
		boolean fishWithSwordInIt = name.contains("swordfish");

		if (last.equals("arrow") || last.equals("arrows") || last.equals("bolts") || last.equals("bolt")
			|| name.contains("arrowtips") || name.contains("bolt tips") || name.contains("cannonball"))
		{
			kinds.add(AMMO);
		}
		if (containsWord(name, "bow") || name.contains("shortbow") || name.contains("longbow")
			|| name.contains("crossbow") || name.contains("blowpipe") || name.contains("javelin")
			|| last.equals("dart") || last.equals("darts") || name.contains("chinchompa")
			|| name.contains("throwing") || last.equals("sling"))
		{
			kinds.add(RANGED);
		}
		if (name.contains("staff") || last.equals("wand") || name.contains("trident") || name.contains("battlestaff"))
		{
			kinds.add(MAGIC);
		}
		if (!fishWithSwordInIt && (name.contains("sword") || name.contains("scimitar") || name.contains("dagger")
			|| name.contains("mace") || name.contains("spear") || name.contains("halberd") || name.contains("hasta")
			|| name.contains("whip") || name.contains("claws") || name.contains("maul") || name.contains("warhammer")
			|| name.contains("rapier") || name.contains("flail") || name.contains("scythe") || name.contains("lance")
			|| name.contains("godsword") || name.contains("katana") || last.equals("club")
			|| name.contains("battleaxe") || name.contains("greataxe") || name.contains("waraxe")
			|| last.equals("axe") && !name.contains("pickaxe") || last.equals("blade")))
		{
			kinds.add(MELEE);
		}
		if (name.contains("platebody") || name.contains("platelegs") || name.contains("plateskirt")
			|| name.contains("chainbody") || name.endsWith("helm") || name.contains("full helm")
			|| name.contains("med helm") || name.contains("shield") || name.contains("kiteshield")
			|| last.equals("boots") || last.equals("gloves") || name.contains("vambraces") || name.contains("coif")
			|| name.contains("chaps") || last.equals("robe") || name.contains("robe top") || name.contains("robe bottom")
			|| last.equals("hat") || last.equals("cowl") || last.equals("bandana") || name.contains("body")
			|| last.equals("legs") || last.equals("skirt"))
		{
			kinds.add(ARMOUR);
		}
		if (food || fishWithSwordInIt)
		{
			kinds.add(FOOD);
		}
		if (name.contains("potion") || name.contains("brew") || name.contains("antidote") || name.contains("antipoison")
			|| name.contains("restore") || name.contains("(4)") || name.contains("(3)") || name.contains("(2)")
			|| name.contains("(1)"))
		{
			kinds.add(POTION);
		}
		if (name.contains("pickaxe") || name.contains("hatchet") || last.equals("axe") || last.equals("hammer")
			|| last.equals("chisel") || name.contains("tinderbox") || last.equals("saw") || last.equals("knife")
			|| last.equals("needle") || last.equals("spade") || last.equals("rake") || last.equals("trowel")
			|| name.contains("secateurs") || name.contains("fishing rod") || name.contains("fly fishing")
			|| name.contains("harpoon") || name.contains("lobster pot") || last.equals("shears") || last.equals("tongs")
			|| name.contains("watering can") || name.contains("dibber") || name.contains("bucket") && !food)
		{
			kinds.add(TOOL);
		}
		if (last.equals("rune") || last.equals("runes"))
		{
			kinds.add(RUNE);
		}
		if (last.equals("seed") || last.equals("seeds") || name.contains(" seed"))
		{
			kinds.add(SEED);
		}
		if (last.equals("ore"))
		{
			kinds.add(ORE);
		}
		if (last.equals("bar") && !name.contains("chocolate"))
		{
			kinds.add(BAR);
		}
		if (last.equals("logs") || last.equals("log"))
		{
			kinds.add(LOG);
		}
		if (last.equals("ring") || name.startsWith("ring of") || last.equals("amulet") || name.startsWith("amulet of")
			|| last.equals("necklace") || last.equals("bracelet") || name.contains("bracelet of")
			|| name.contains("necklace of"))
		{
			kinds.add(JEWELLERY);
		}
		return kinds;
	}

	/** The kinds as one comma separated string, for storing on an entry. */
	public static String join(List<String> kinds)
	{
		return String.join(",", kinds);
	}

	private static boolean containsAny(String text, String[] needles)
	{
		for (String needle : needles)
		{
			if (text.contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean containsWord(String text, String word)
	{
		for (String part : text.split("[^a-z0-9]+"))
		{
			if (part.equals(word))
			{
				return true;
			}
		}
		return false;
	}
}
