package com.worldmapplus.data;

import com.google.gson.*;
import com.worldmapplus.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.unlock.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * Every card of the map (entrances, transport stops, teleport destinations, shops, agility shortcuts), the icons of the game's own
 * map with the card that answers for each, the game's links between maps and its text labels.
 * <p>
 * All of it is made at build time by {@code tools/build_cards.py} into {@code things.json}, which says how. The plugin reads the file
 * with Gson, what a card asks of the player as finished checks. Nothing is read from the game.
 */
public final class MapElements
{
	/** A text label of the game's map. Filled by Gson. */
	@Getter
	public static final class Label
	{
		private String text;
		/** The text one line at a time, as the game breaks it. */
		private String[] lines;
		private int mapId;
		private double x;
		private double y;
		/** The floor the label is on. Only 0 is drawn on maps without floors. */
		private int plane;
		/** The game's text size: 0 small, 1 medium, 2 large. */
		private int scale;
		/** The game's text colour as 0xRRGGBB. */
		private int color;
	}

	/** One icon of the game's map: where it is and which category of the map key it belongs to (the game's number). Filled by Gson. */
	@Getter
	public static final class Spot
	{
		private double x;
		private double y;
		private int category;
	}

	/** One card of the file: a hotspot, with what it asks and, for an icon, what the wiki says it asks. Filled by Gson. */
	static final class Thing
	{
		Hotspot.Kind kind;
		int m;
		double x;
		double y;
		int r;
		HoverCard card;
		Hotspot.Target go;
		List<Hotspot.Link> links;
		String layer;
		int floor = Hotspot.NOT_A_MAP_ICON;
		boolean drawn;
		/** What it asks; each teleport row by its label; the wiki's requirement of an icon and its row text, given only when it applies. */
		Need need;
		Map<String, Need> ways;
		Need req;
		String reqRow;
	}

	/** One icon the plugin draws: its picture in the atlas, floor, category, place in the crowd order, card and merged points. */
	static final class Icon
	{
		int m;
		double x;
		double y;
		int pic;
		int floor;
		int cat;
		int prio;
		int card;
		List<IconExtra> extras;
		/** What the merged points ask. */
		Need n;
	}

	private List<Thing> things;
	/** Where the points of interest go among the cards, and where the icons' own cards start. */
	private int pois;
	private int own;
	private List<Icon> icons;
	private Map<Integer, List<Label>> labels;
	private Map<Integer, List<Spot>> spots;
	private List<String> categories;
	private int[] categoryIds;
	private int pictures;

	/** The cards as made, and as shown: with the wiki's requirement where it applies. */
	private transient List<Hotspot> made;
	private transient List<Hotspot> shown;
	private transient Map<Hotspot, Hotspot> covers;
	private transient Map<Integer, String> categoryNames;
	/**
	 * The icons of the game's own list, with the picture each is drawn with and the floor it is on. They are the icons of the maps the
	 * game has data for. An icon of one of the wiki's own extra maps has no picture here: the tile shows it.
	 */
	@Getter
	private transient List<GameIconSet.Spec> gameIcons;

	/** Reads {@code things.json}; nothing when it is missing or damaged. */
	public static MapElements load(Gson gson)
	{
		MapElements read = MapAssets.json(gson, "things.json", MapElements.class);
		return (read == null ? new MapElements() : read).ready();
	}

	/** An empty set, for tests and for a build without the data. */
	public static MapElements none()
	{
		return new MapElements().ready();
	}

	MapElements ready()
	{
		made = new ArrayList<>();
		shown = new ArrayList<>();
		covers = new IdentityHashMap<>();
		for (Thing t : list(things))
		{
			Hotspot hotspot = new Hotspot(t.m, t.x, t.y, t.r, t.card, t.go, list(t.links), t.kind, t.layer, null, t.floor, t.drawn)
				.withNeed(t.need).withWayNeeds(t.ways == null ? Collections.emptyMap() : t.ways);
			made.add(hotspot);
			if (t.req != null)
			{
				Hotspot with = (t.reqRow == null ? hotspot : hotspot.withCard(t.card.withRow("Requires", t.reqRow))).withNeed(t.req);
				covers.put(hotspot, with);
				hotspot = with;
			}
			shown.add(hotspot);
		}
		pois = Math.max(0, Math.min(pois, made.size()));
		own = Math.max(pois, Math.min(own, made.size()));
		gameIcons = new ArrayList<>();
		for (Icon icon : list(icons))
		{
			Hotspot card = icon.card >= 0 && icon.card < shown.size() ? shown.get(icon.card) : null;
			Need need = card != null && card.getNeed() != null ? card.getNeed() : icon.n;
			gameIcons.add(new GameIconSet.Spec(icon.m, icon.x, icon.y, icon.pic, icon.floor, icon.cat, icon.prio, card, icon.extras, need));
		}
		categories = list(categories);
		categoryIds = categoryIds == null ? new int[0] : categoryIds;
		categoryNames = new HashMap<>();
		for (int i = 0; i < Math.min(categories.size(), categoryIds.length); i++)
		{
			categoryNames.put(categoryIds[i], categories.get(i));
		}
		labels = labels == null ? Collections.emptyMap() : labels;
		spots = spots == null ? Collections.emptyMap() : spots;
		return this;
	}

	private static <T> List<T> list(@Nullable List<T> items)
	{
		return items == null ? Collections.emptyList() : items;
	}

	/**
	 * The cards of the entrances, stops, teleports, shops and shortcuts as they were made (what the search reads), with the points of
	 * interest put in their place in the order.
	 */
	public List<Hotspot> loaded(List<Hotspot> points)
	{
		List<Hotspot> result = new ArrayList<>(made.subList(0, pois));
		result.addAll(points);
		result.addAll(made.subList(pois, own));
		return result;
	}

	/**
	 * What a search entry asks of the player: the need of its card as made (of one row, for a way of teleporting), else that of the
	 * first of the game's icons given that asks something.
	 */
	@Nullable
	public Need needOf(int thing, @Nullable String way, @Nullable int[] icons)
	{
		Hotspot card = thing >= 0 && thing < made.size() ? made.get(thing) : null;
		Need need = card == null ? null : way == null ? card.getNeed() : card.wayNeed(way);
		for (int i = 0; icons != null && need == null && i < icons.length; i++)
		{
			Need of = icons[i] >= 0 && icons[i] < gameIcons.size() ? gameIcons.get(icons[i]).getNeed() : null;
			need = of != null && of.hasAlternatives() ? of : null;
		}
		return need;
	}

	/** The icons' own cards and the doorways of the game's links. */
	public List<Hotspot> getIcons()
	{
		return shown.subList(own, shown.size());
	}

	/** How many pictures the icons use, which is how many the atlas holds. */
	public int getPictureCount()
	{
		return pictures;
	}

	/** The card to use in place of one given by {@link #loaded}: the same card, or its copy with the wiki's requirement. */
	public Hotspot replacementOf(Hotspot card)
	{
		return covers.getOrDefault(card, card);
	}

	/**
	 * The names of the categories of icons the plugin draws, sorted, each once: the kinds a player can switch off. A category of the
	 * wiki's extra maps, whose icons are baked into the tiles and not drawn, is not one of them.
	 */
	public List<String> drawnCategoryNames()
	{
		Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (GameIconSet.Spec spec : gameIcons)
		{
			names.add(categoryKind(spec.getCategory()));
		}
		names.remove("");
		return new ArrayList<>(names);
	}

	/** The numbers (as {@link GameIconSet.Spec#getCategory()} gives them) of every category that has this name. */
	public List<Integer> categoriesNamed(String name)
	{
		List<Integer> found = new ArrayList<>();
		for (int i = 0; i < categories.size(); i++)
		{
			if (categories.get(i).equals(name))
			{
				found.add(i);
			}
		}
		return found;
	}

	/** The name of the kind of icon a category number stands for, such as Water source, or an empty string. */
	public String categoryKind(int category)
	{
		return category >= 0 && category < categories.size() ? categories.get(category) : "";
	}

	/** How many categories of icons the data has, which is one more than the highest number an icon's category can be. */
	public int categoryCount()
	{
		return categories.size();
	}

	/** Every icon of the game's map on a map, for marking one kind of them. */
	public List<Spot> spotsOn(int mapId)
	{
		return spots.getOrDefault(mapId, Collections.emptyList());
	}

	/** True when the game's number is one of the map's icon categories. */
	public boolean isCategory(int category)
	{
		return categoryNames.containsKey(category);
	}

	/** The category the map key gives a name, ignoring case, or null. */
	@Nullable
	public Integer categoryNamed(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		for (Map.Entry<Integer, String> entry : categoryNames.entrySet())
		{
			if (entry.getValue().equalsIgnoreCase(name.trim()))
			{
				return entry.getKey();
			}
		}
		// The key may word a category a little differently, as in a plural or a changed case.
		String wanted = squashed(name);
		if (wanted.length() < 3)
		{
			return null;
		}
		for (Map.Entry<Integer, String> entry : categoryNames.entrySet())
		{
			if (squashed(entry.getValue()).equals(wanted))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	private static String squashed(String text)
	{
		String letters = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		return letters.endsWith("s") ? letters.substring(0, letters.length() - 1) : letters;
	}

	/** The name the game's map key gives a category, or null. */
	@Nullable
	public String categoryName(int category)
	{
		return categoryNames.get(category);
	}

	/** The labels on a map, or an empty list. */
	public List<Label> labelsOn(int mapId)
	{
		return labels.getOrDefault(mapId, Collections.emptyList());
	}

	/** How many labels there are in all. */
	public int labelCount()
	{
		int count = 0;
		for (List<Label> list : labels.values())
		{
			count += list.size();
		}
		return count;
	}
}
