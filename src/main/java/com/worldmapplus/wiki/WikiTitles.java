package com.worldmapplus.wiki;

import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Decides which things on the map have a wiki page of their own, and what its title is.
 * <p>
 * The title is the name the plugin's own data carries, spelt as it is there, with only the full stop that ends the wiki's shop names
 * removed. The data these names come from was itself taken from wiki pages (shops, monsters, quests, items, music, and the game's own
 * area names), so for those the name is the page. For anything else the plugin cannot be sure a page by that name exists, and the
 * rule is to give no title: a missing button costs a player nothing, and a button that opens a page that is not there sends them to a
 * 404. Entrances, bare map names, places named in the wiki's free text, items of the teleport tables and every other card are left out
 * for that reason.
 */
public final class WikiTitles
{
	/** Kinds of transport whose name is the title of a wiki page. The other kinds the data has are not certain to be pages. */
	private static final Set<String> TRANSPORT_PAGES = new HashSet<>(Arrays.asList("Charter ship", "Magic carpet", "Gnome glider",
		"Hot air balloon", "Magic mushtree", "Spirit tree", "Wilderness obelisk", "Fairy ring"));

	/** Agility courses whose name is the title of a wiki page. */
	private static final Set<String> COURSES = new HashSet<>(Arrays.asList("Agility Pyramid", "Al Kharid Rooftop Course",
		"Ape Atoll Agility Course", "Ardougne Rooftop Course", "Barbarian Outpost Agility Course", "Brimhaven Agility Arena",
		"Canifis Rooftop Course", "Draynor Village Rooftop Course", "Falador Rooftop Course", "Gnome Stronghold Agility Course",
		"Pollnivneach Rooftop Course", "Prifddinas Agility Course", "Rellekka Rooftop Course", "Seers' Village Rooftop Course",
		"Varrock Rooftop Course", "Werewolf Agility Course", "Wilderness Agility Course"));

	/** Minigames whose name is the title of a wiki page. */
	private static final Set<String> MINIGAMES = new HashSet<>(Arrays.asList("Barbarian Assault", "Brimhaven Agility Arena",
		"Burgh de Rott Ramble", "Castle Wars", "Clan Wars", "Fishing Trawler", "Fortis Colosseum", "Gnome Ball", "Gnome Restaurant",
		"Hallowed Sepulchre", "Last Man Standing", "Mage Arena", "Mage Training Arena", "Mahogany Homes", "Mastering Mixology",
		"Nightmare Zone", "Pyramid Plunder", "Ranging Guild", "Rat Pits", "Rogues' Den", "Shades of Mort'ton", "Soul Wars",
		"Tai Bwo Wannai Cleanup", "Tears of Guthix", "Temple Trekking", "The Gauntlet", "Tithe Farm", "Trouble Brewing",
		"TzHaar Fight Cave", "TzHaar Fight Pit", "Volcanic Mine"));

	/** Rare trees whose name is the title of a wiki page. The data also has mixed names such as Maple/Yew trees. */
	private static final Set<String> TREES = new HashSet<>(Arrays.asList("Blisterwood tree", "Bloodwood tree", "Camphor tree",
		"Ironwood tree", "Magic tree", "Mahogany tree", "Maple tree", "Redwood tree", "Rosewood tree", "Sulliuscep", "Teak tree",
		"Willow tree", "Yew tree"));

	/** Names that are words for a kind of place and not the name of one, so no page is called that. */
	private static final Set<String> GENERIC = new HashSet<>(Arrays.asList("map", "surface", "underground", "entrance", "exit", "dungeon",
		"cave", "caves", "mine", "tunnels", "basement", "floor", "upstairs", "downstairs", "unknown", "somewhere", "teleport", "shop"));

	private WikiTitles()
	{
	}

	/** A name cleaned up as a title, or null when it would not make an address. */
	@Nullable
	public static String clean(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		String title = name.trim();
		while (title.endsWith("."))
		{
			// The wiki writes the full stop of a shop's name into the name; the page has none. A name that ends in three dots is the title.
			if (title.endsWith("..."))
			{
				break;
			}
			title = title.substring(0, title.length() - 1).trim();
		}
		return WikiLinks.url(title) == null ? null : title;
	}

	/**
	 * The name to look up on the wiki's search page for something with no page that is certain to exist, or null when it would not make
	 * a search address. The search page never answers with a missing page.
	 */
	@Nullable
	public static String searchName(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		String text = name.trim();
		return WikiLinks.searchUrl(text) == null ? null : text;
	}

	/** The title for the name of a shop, or null. */
	@Nullable
	public static String forShop(@Nullable String name)
	{
		return clean(name);
	}

	/** The title for a kind of transport, such as {@code Fairy ring} or {@code Charter ship}, or null for a kind that is not a page. */
	@Nullable
	public static String forTransport(@Nullable String kind)
	{
		return kind != null && TRANSPORT_PAGES.contains(kind.trim()) ? kind.trim() : null;
	}

	/**
	 * The title for a point of interest the plugin's data names, or null. Only names on the short lists above are given one: the data
	 * also holds rooms, grades and trial names (the Animation Room, Pest Control Novice, a Barracuda trial) that the wiki has no page for.
	 */
	@Nullable
	public static String forPointLayer(@Nullable String layer, @Nullable String name, @Nullable String detail)
	{
		if (layer == null || name == null)
		{
			return null;
		}
		String title = name.trim();
		if ("agility".equals(layer))
		{
			return COURSES.contains(title) ? clean(title) : null;
		}
		if ("minigame".equals(layer))
		{
			return "Minigame".equals(detail) && MINIGAMES.contains(title) ? clean(title) : null;
		}
		if ("tree".equals(layer))
		{
			return detail != null && detail.startsWith("Woodcutting level") && TREES.contains(title) ? clean(title) : null;
		}
		return null;
	}

	/**
	 * The title for a spell of the teleport tables: {@code Varrock Teleport}, or {@code Varrock Teleport: Grand Exchange}, whose page is
	 * the spell's. Anything else in those tables is an item or a place and gets none.
	 */
	@Nullable
	static String forTeleport(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		String spell = name;
		int colon = spell.indexOf(':');
		if (colon > 0)
		{
			spell = spell.substring(0, colon);
		}
		int bracket = spell.indexOf(" (");
		if (bracket > 0)
		{
			spell = spell.substring(0, bracket);
		}
		spell = spell.trim();
		boolean isSpell = spell.endsWith(" Teleport") && spell.length() > " Teleport".length() || spell.startsWith("Teleport to ");
		return isSpell ? clean(spell) : null;
	}

	/** The title for a search result, or null when the kind of result is not certain to have a page. */
	@Nullable
	public static String forEntry(@Nullable SearchEntry entry)
	{
		if (entry == null)
		{
			return null;
		}
		switch (entry.getKind())
		{
			case QUEST:
			case MONSTER:
			case NPC:
			case ITEM:
			case MUSIC:
				return clean(entry.getName());
			case SHOP:
				return forShop(entry.getName());
			case PLACE:
				// Only the names the game's own map prints are pages. The names of our maps ("Map") and places the wiki describes in
				// free words are not certain to be.
				return entry.isAreaName() && isNameOfAPlace(entry.getName()) ? clean(entry.getName()) : null;
			case TRANSPORT:
			{
				String name = entry.getName();
				int colon = name.indexOf(':');
				return forTransport(colon > 0 ? name.substring(0, colon) : name);
			}
			case TELEPORT:
				return forTeleport(entry.getName());
			case POINT:
			{
				String detail = entry.getDetail().replace(" (" + entry.getAlsoAt().size() + " places)", "").trim();
				if (detail.startsWith("Agility course"))
				{
					return forPointLayer("agility", entry.getName(), detail);
				}
				return detail.startsWith("Woodcutting level") ? forPointLayer("tree", entry.getName(), detail)
					: forPointLayer("minigame", entry.getName(), detail);
			}
			default:
				return null;
		}
	}

	/** True for a name that looks like the name of a place and not a word for a kind of place. */
	static boolean isNameOfAPlace(@Nullable String name)
	{
		if (name == null)
		{
			return false;
		}
		String text = name.trim();
		return text.length() >= 3 && Character.isUpperCase(text.charAt(0)) && !GENERIC.contains(text.toLowerCase(Locale.ROOT));
	}
}
