package com.worldmapplus.data;

import com.google.gson.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.map.*;
import com.worldmapplus.search.*;
import com.worldmapplus.unlock.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * What the game's own world map draws that the wiki's tiles do not: the text labels (area names) and the
 * names of the icons the tiles do show (banks, anvils, altars and so on).
 * <p>
 * Static data made at build time by {@code tools/build_map_elements.py} from the game's cache. Nothing is
 * read from the game while the plugin runs.
 */
@Slf4j
public final class MapElements
{
	private static final String RESOURCE = "/com/worldmapplus/map-elements.json";

	/** Pixels around an icon within which the mouse gets its card. */
	private static final int ICON_RADIUS = 9;

	/** A native icon this close to a card the plugin already has is left out, since that card says more. */
	private static final double COVERED_TILES = 1.4;

	/** A text label of the game's map. */
	@Getter
	public static final class Label
	{
		private final String text;
		/** The text one line at a time, as the game breaks it. */
		private final String[] lines;
		private final int mapId;
		private final double x;
		private final double y;
		/** The floor the label is on. Only 0 is drawn on maps without floors. */
		private final int plane;
		/** The game's text size: 0 small, 1 medium, 2 large. */
		private final int scale;
		/** The game's text colour as 0xRRGGBB. */
		private final int color;

		Label(String text, int mapId, double x, double y, int plane, int scale, int color)
		{
			this.text = text;
			this.lines = text.split("\n");
			this.mapId = mapId;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.scale = scale;
			this.color = color;
		}
	}

	/** One icon of the game's map: where it is and which category of the map key it belongs to. */
	@Getter
	public static final class Spot
	{
		private final double x;
		private final double y;
		/** The game's own number for the category. */
		private final int category;

		Spot(double x, double y, int category)
		{
			this.x = x;
			this.y = y;
			this.category = category;
		}
	}

	private final Map<Integer, List<Label>> labels;
	/** Cards for the icons, each with the name the game gives it. */
	@Getter
	private final List<Hotspot> icons;
	private final Map<Integer, List<Spot>> spots;
	private final Map<Integer, String> categoryNames;
	/**
	 * The icons of the game's own list, with the picture each is drawn with (a number into the atlas) and the floor it is on. They are
	 * the icons of the maps the game has data for. An icon of one of the wiki's own extra maps has no picture here: the tile shows it.
	 */
	@Getter
	private final List<GameIconSet.Spec> gameIcons;
	/** How many pictures the icons use, which is how many the atlas holds. */
	@Getter
	private final int pictureCount;
	private final List<String> categoryList;

	private MapElements(Map<Integer, List<Label>> labels, List<Hotspot> icons)
	{
		this(labels, icons, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), 0, Collections.emptyList());
	}

	/**
	 * The cards of other data that cover an icon and were given the wiki's requirement for it (round 47), by the card they replace.
	 * The caller puts them in place of the old ones, so the card the mouse finds says the same as the icon.
	 */
	private final Map<Hotspot, Hotspot> covers = new IdentityHashMap<>();

	private MapElements(Map<Integer, List<Label>> labels, List<Hotspot> icons, Map<Integer, List<Spot>> spots,
		Map<Integer, String> categoryNames, List<GameIconSet.Spec> gameIcons, int pictureCount, List<String> categoryList)
	{
		this.labels = labels;
		this.icons = icons;
		this.spots = spots;
		this.categoryNames = categoryNames;
		this.gameIcons = gameIcons;
		this.pictureCount = pictureCount;
		this.categoryList = categoryList;
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
			if (spec.getCategory() >= 0 && spec.getCategory() < categoryList.size())
			{
				names.add(categoryList.get(spec.getCategory()));
			}
		}
		return new ArrayList<>(names);
	}

	/** The numbers (as {@link GameIconSet.Spec#getCategory()} gives them) of every category that has this name. */
	public List<Integer> categoriesNamed(String name)
	{
		List<Integer> found = new ArrayList<>();
		for (int i = 0; i < categoryList.size(); i++)
		{
			if (categoryList.get(i).equals(name))
			{
				found.add(i);
			}
		}
		return found;
	}

	/** The name of the kind of icon a category number stands for, such as Water source, or an empty string. */
	public String categoryKind(int category)
	{
		return category >= 0 && category < categoryList.size() ? categoryList.get(category) : "";
	}

	/** How many categories of icons the data has, which is one more than the highest number an icon's category can be. */
	public int categoryCount()
	{
		return categoryList.size();
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

	/** An empty set, for tests and for a build without the data. */
	public static MapElements none()
	{
		return new MapElements(Collections.emptyMap(), Collections.emptyList());
	}

	/** The card to use in place of one of the cards given to {@link #load}: the same card, or its copy with the wiki's requirement. */
	public Hotspot replacementOf(Hotspot card)
	{
		Hotspot replaced = covers.get(card);
		return replaced == null ? card : replaced;
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

	/**
	 * Reads the bundled data.
	 *
	 * @param existing the cards the plugin already has. An icon within a couple of tiles of one that is
	 * always shown is left out, because that card already says what is there.
	 */
	public static MapElements load(Gson gson, MapCatalog catalog, List<Hotspot> existing)
	{
		return load(gson, catalog, existing, Collections.emptyList());
	}

	/**
	 * @param known search entries, of which the quest starts name the quest icons that sit on them
	 */
	public static MapElements load(Gson gson, MapCatalog catalog, List<Hotspot> existing, List<SearchEntry> known)
	{
		try (InputStream in = MapElements.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.debug("World Map Plus: no {}", RESOURCE);
				return none();
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return parse(gson.fromJson(reader, File.class), catalog, existing, known, readDetails(gson), IconRequirements.load(gson));
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("World Map Plus: could not read {}", RESOURCE, e);
			return none();
		}
	}

	static MapElements parse(File file, MapCatalog catalog, List<Hotspot> existing)
	{
		return parse(file, catalog, existing, Collections.emptyList());
	}

	static MapElements parse(File file, MapCatalog catalog, List<Hotspot> existing, List<SearchEntry> known)
	{
		return parse(file, catalog, existing, known, Collections.emptyMap());
	}

	/** Notes for icons, made by tools/build_icon_details.py, by the icon's map and position. */
	static final class Detail
	{
		int map;
		double x;
		double y;
		String title;
		String subtitle;
		List<List<String>> rows;
	}

	static final class DetailFile
	{
		List<Detail> details;
	}

	private static final String DETAILS = "/com/worldmapplus/icon-details.json";

	private static String detailKey(int mapId, double x, double y)
	{
		return mapId + ":" + x + ":" + y;
	}

	private static Map<String, Detail> readDetails(Gson gson)
	{
		Map<String, Detail> result = new HashMap<>();
		try (InputStream in = MapElements.class.getResourceAsStream(DETAILS))
		{
			if (in == null)
			{
				return result;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				DetailFile file = gson.fromJson(reader, DetailFile.class);
				if (file != null && file.details != null)
				{
					for (Detail detail : file.details)
					{
						if (detail != null && detail.title != null && !detail.title.trim().isEmpty())
						{
							result.put(detailKey(detail.map, detail.x, detail.y), detail);
						}
					}
				}
			}
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("World Map Plus: could not read {}", DETAILS, e);
		}
		return result;
	}

	static MapElements parse(File file, MapCatalog catalog, List<Hotspot> existing, List<SearchEntry> known,
	Map<String, Detail> details)
	{
		return parse(file, catalog, existing, known, details, IconRequirements.none());
	}

	/** The same with what the wiki says the icons of kinds with no other data ask of the player (round 47). */
	static MapElements parse(File file, MapCatalog catalog, List<Hotspot> existing, List<SearchEntry> known,
	Map<String, Detail> details, IconRequirements requirements)
	{
		Map<Hotspot, Hotspot> replacedCovers = new IdentityHashMap<>();
		if (file == null)
		{
			return none();
		}
		Map<Integer, List<Label>> byMap = new HashMap<>();
		if (file.labels != null)
		{
			for (RawLabel raw : file.labels)
			{
				if (raw == null || raw.t == null || raw.t.trim().isEmpty())
				{
					continue;
				}
				MapInfo map = catalog.mapFor(raw.x, raw.y);
				if (map == null)
				{
					continue;
				}
				byMap.computeIfAbsent(map.getId(), id -> new ArrayList<>())
					.add(new Label(raw.t, map.getId(), raw.x + 0.5, raw.y + 0.5, raw.z, raw.s, raw.c));
			}
		}

		Map<Integer, List<SearchEntry>> quests = new HashMap<>();
		for (SearchEntry entry : known)
		{
			if (entry.getKind() == SearchKind.QUEST)
			{
				quests.computeIfAbsent(entry.getMapId(), id -> new ArrayList<>()).add(entry);
			}
		}

		List<Hotspot> cards = new ArrayList<>();
		List<GameIconSet.Spec> gameIcons = new ArrayList<>();
		Map<Integer, Integer> pictureIndex = new HashMap<>();
		if (file.sprites != null)
		{
			for (int i = 0; i < file.sprites.length; i++)
			{
				pictureIndex.put(file.sprites[i], i);
			}
		}
		if (file.icons != null && file.categories != null)
		{
			Map<Integer, List<Hotspot>> covered = coveredBy(existing);
			Map<String, List<IconExtra>> merged = mergedInto(existing);
			for (Map.Entry<String, double[][]> entry : file.icons.entrySet())
			{
				int mapId;
				try
				{
					mapId = Integer.parseInt(entry.getKey());
				}
				catch (NumberFormatException e)
				{
					continue;
				}
				for (double[] row : entry.getValue())
				{
					if (row == null || row.length < 3 || (int) row[2] < 0 || (int) row[2] >= file.categories.size())
					{
						continue;
					}
					// A row is x, y, category and then, in a file with pictures, the picture's number and the floor; the far end of a
					// link, when the icon has one, comes last.
					boolean modern = file.sprites != null && row.length >= 5;
					int floor = modern ? (int) row[4] : 0;
					Integer picture = modern ? pictureIndex.get((int) row[3]) : null;
					int linkAt = modern ? 5 : 3;
					// The far map follows the far tile when the file names it (-1 when it does not).
						double[] icon = row.length >= linkAt + 2 ? new double[] {row[0], row[1], row[2], row[linkAt], row[linkAt + 1],
							row.length >= linkAt + 3 ? row[linkAt + 2] : -1}
						: new double[] {row[0], row[1], row[2]};
					String name = file.categories.get((int) icon[2]);
					boolean drawn = picture != null;
					List<IconExtra> extras = merged.get(iconKey(mapId, icon[0], icon[1], floor));
					Hotspot card;
					Hotspot cover = coveringCard(covered.get(mapId), icon[0], icon[1], name);
					if (cover != null)
					{
						// The covering card stays as it is for everything else; only the icon's own copy gets the wiki's requirement.
						card = requirements.apply(cover, name, mapId, icon[0], icon[1], floor, extras);
						if (card != cover)
						{
							replacedCovers.putIfAbsent(cover, card);
						}
					}
					else
					{
						// A card of the right kind that sits a little off the icon is moved onto it, so the icon says what the card says.
						Hotspot rich = richNear(covered.get(mapId), icon[0], icon[1], name);
						Detail detail = details.get(detailKey(mapId, icon[0], icon[1]));
						boolean questNamed = "Quest".equals(name) && quests.get(mapId) != null
							&& nearestQuest(quests.get(mapId), icon[0], icon[1]) != null;
						if (rich != null)
						{
							card = new Hotspot(mapId, icon[0], icon[1], ICON_RADIUS, rich.getCard(), rich.getTarget(), rich.getLinks(),
								rich.getKind(), null, null, floor, drawn).withNeed(rich.getNeed());
						}
						else if (detail != null && !questNamed)
						{
							card = detailed(mapId, icon, detail, floor, drawn).withNeed(Needs.fromRows(detail.rows));
						}
						else
						{
							card = icon(catalog, mapId, icon, name, quests.get(mapId), byMap.get(mapId), floor, drawn);
						}
						card = requirements.apply(card, name, mapId, icon[0], icon[1], floor, extras);
						cards.add(card);
					}
					if (drawn)
					{
						Need need = card.getNeed();
						if (need == null)
						{
							need = needOfPoints(extras);
						}
						gameIcons.add(new GameIconSet.Spec(mapId, icon[0], icon[1], picture, floor, (int) icon[2], priorityOf(name), card,
							extras, need));
					}
				}
			}
		}
		addLinks(file, catalog, existing, byMap, cards);
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, List<Spot>> spots = new HashMap<>();
		if (file.categories != null && file.categoryIds != null)
		{
			for (int i = 0; i < Math.min(file.categories.size(), file.categoryIds.length); i++)
			{
				names.put(file.categoryIds[i], file.categories.get(i));
			}
			if (file.icons != null)
			{
				for (Map.Entry<String, double[][]> entry : file.icons.entrySet())
				{
					int mapId;
					try
					{
						mapId = Integer.parseInt(entry.getKey());
					}
					catch (NumberFormatException e)
					{
						continue;
					}
					List<Spot> list = new ArrayList<>();
					for (double[] icon : entry.getValue())
					{
						if (icon != null && icon.length >= 3 && (int) icon[2] >= 0 && (int) icon[2] < file.categoryIds.length)
						{
							list.add(new Spot(icon[0], icon[1], file.categoryIds[(int) icon[2]]));
						}
					}
					spots.put(mapId, list);
				}
			}
		}
		MapElements result = new MapElements(byMap, cards, spots, names, gameIcons, file.sprites == null ? 0 : file.sprites.length,
			file.categories == null ? Collections.emptyList() : file.categories);
		result.covers.putAll(replacedCovers);
		return result;
	}

	/** An entrance within this many tiles of one of the game's links already answers for it. */
	private static final double LINK_COVERED_TILES = 6.0;

	/** A label within this many tiles of a link names the doorway. */
	private static final double LINK_NAME_TILES = 14.0;

	/**
	 * The game's own links between maps that nothing else here answers for: no entrance in the plugin's own
	 * list and no icon carrying the link. Each becomes a doorway that previews and opens the other map, so
	 * every way the game's map offers is offered here. It is named for the nearest label, or else for the
	 * map it leads to.
	 */
	private static void addLinks(File file, MapCatalog catalog, List<Hotspot> existing,
		Map<Integer, List<Label>> labels, List<Hotspot> cards)
	{
		if (file.links == null)
		{
			return;
		}
		Map<Integer, List<Hotspot>> doors = new HashMap<>();
		for (Hotspot hotspot : existing)
		{
			if (hotspot.getKind() == Hotspot.Kind.DOORWAY && hotspot.getTarget() != null)
			{
				doors.computeIfAbsent(hotspot.getMapId(), id -> new ArrayList<>()).add(hotspot);
			}
		}
		for (Hotspot card : cards)
		{
			if (card.getTarget() != null)
			{
				doors.computeIfAbsent(card.getMapId(), id -> new ArrayList<>()).add(card);
			}
		}
		for (int[] link : file.links)
		{
			if (link == null || link.length < 4)
			{
				continue;
			}
			MapInfo from = mapOf(catalog, link.length >= 6 ? link[4] : -1, link[0], link[1]);
			MapInfo to = mapOf(catalog, link.length >= 6 ? link[5] : -1, link[2], link[3]);
			if (from == null || to == null || from.getId() == to.getId())
			{
				continue;
			}
			double x = link[0] + 0.5;
			double y = link[1] + 0.5;
			List<Hotspot> near = doors.get(from.getId());
			if (near != null && closeTo(near, x, y, LINK_COVERED_TILES, to.getId()))
			{
				continue;
			}
			String name = nameNear(labels.get(from.getId()), x, y, to.getName());
			HoverCard card = HoverCard.builder(name)
				.subtitle("Leads to " + to.getName())
				.preview(to.getId(), link[2] + 0.5, link[3] + 0.5)
				.hint("Click to open this map")
				.build();
			Hotspot door = new Hotspot(from.getId(), x, y, ICON_RADIUS, card,
				new Hotspot.Target(to.getId(), link[2] + 0.5, link[3] + 0.5));
			cards.add(door);
			doors.computeIfAbsent(from.getId(), id -> new ArrayList<>()).add(door);
		}
	}

	/**
	 * The map one end of a link is on. The file names it, because the game's maps draw different places over the same squares (the
	 * Kharidian Desert Underground draws the Giants' Foundry where the Morytania Underground draws the Myreque Hideout), so the most
	 * specific map that covers a tile is not always the one a link leads into. A named map that has no picture of the tile gives null,
	 * never another map. Only a file that names no map (an older one, or a test's) falls back to the map that covers the tile.
	 */
	@Nullable
	static MapInfo mapOf(MapCatalog catalog, int mapId, int x, int y)
	{
		if (mapId < 0)
		{
			return catalog.mapFor(x, y);
		}
		MapInfo named = catalog.byId(mapId);
		return named != null && named.contains(x, y) ? named : null;
	}

	private static boolean closeTo(List<Hotspot> spots, double x, double y, double tiles, int leadsTo)
	{
		for (Hotspot spot : spots)
		{
			if (spot.getTarget().getMapId() == leadsTo && Math.abs(spot.getX() - x) <= tiles
				&& Math.abs(spot.getY() - y) <= tiles)
			{
				return true;
			}
		}
		return false;
	}

	private static String nameNear(List<Label> labels, double x, double y, String fallback)
	{
		Label best = null;
		double bestDistance = LINK_NAME_TILES;
		if (labels != null)
		{
			for (Label label : labels)
			{
				double d = Math.hypot(label.getX() - x, label.getY() - y);
				if (d <= bestDistance && label.getPlane() == 0)
				{
					bestDistance = d;
					best = label;
				}
			}
		}
		return best == null ? fallback : best.getText().replace('\n', ' ');
	}

	/**
	 * A card for an icon. An icon that marks the way into or out of another map carries the game's own link:
	 * it shows a preview of the far end and a click opens that map, as the game's map does.
	 */
	private static Hotspot icon(MapCatalog catalog, int mapId, double[] icon, String name, List<SearchEntry> quests,
	List<Label> labels, int floor, boolean drawn)
	{
		HoverCard.Builder plain = HoverCard.builder(name);
		Need starts = null;
		String near = nearLabel(labels, icon[0], icon[1]);
		if (near != null)
		{
			plain.subtitle("Near " + near);
		}
		else
		{
			// The game writes no area names inside its dungeons, so an icon there says which map it is on.
			MapInfo here = catalog.byId(mapId);
			if (here != null && here.getId() != MapInfo.SURFACE)
			{
				plain.subtitle("In " + here.getName());
			}
		}
		if ("Quest".equals(name) && quests != null)
		{
			SearchEntry quest = nearestQuest(quests, icon[0], icon[1]);
			if (quest != null)
			{
				plain = HoverCard.builder(quest.getName()).subtitle("Quest start").wiki("quest", quest.getName());
				starts = Need.startOf(quest.getName());
			}
		}
		MapInfo far = icon.length >= 5 ? mapOf(catalog, icon.length >= 6 ? (int) icon[5] : -1, (int) icon[3], (int) icon[4]) : null;
		if (far == null || far.getId() == mapId)
		{
			return new Hotspot(mapId, icon[0], icon[1], ICON_RADIUS, plain.build(), null,
				Collections.emptyList(), Hotspot.Kind.POI, null, null, floor, drawn).withNeed(starts);
		}
		HoverCard card = HoverCard.builder(name)
			.subtitle("Leads to " + far.getName())
			.preview(far.getId(), icon[3] + 0.5, icon[4] + 0.5)
			.hint("Click to open this map")
			.build();
		return new Hotspot(mapId, icon[0], icon[1], ICON_RADIUS, card,
			new Hotspot.Target(far.getId(), icon[3] + 0.5, icon[4] + 0.5), Collections.emptyList(), Hotspot.Kind.POI, null, null, floor,
			drawn);
	}

	/** How far a card of the right kind may be from an icon and still be the icon's own. */
	private static final double RICH_TILES = 3.5;

	/** The kind of card that describes an icon of a category, or null when no kind of card is known to. */
	public static Hotspot.Kind kindFor(String category)
	{
		String lower = category.toLowerCase(Locale.ROOT);
		if (lower.equals("dungeon") || lower.equals("map link"))
		{
			return Hotspot.Kind.DOORWAY;
		}
		if (lower.equals("transportation") || lower.equals("agility shortcut"))
		{
			return Hotspot.Kind.TRANSPORT;
		}
		if (lower.contains("shop") || lower.contains("store") || lower.contains("trader") || lower.contains("pub")
		|| lower.contains("supplier") || lower.contains("herbalist") || lower.contains("apothecary") || lower.contains("exchange"))
		{
			return Hotspot.Kind.SHOP;
		}
		return null;
	}

	/** The nearest card of the kind that describes the category, within a few tiles, or null. */
	private static Hotspot richNear(List<Hotspot> near, double x, double y, String category)
	{
		Hotspot.Kind kind = kindFor(category);
		if (near == null || kind == null)
		{
			return null;
		}
		Hotspot best = null;
		double bestDistance = RICH_TILES;
		for (Hotspot hotspot : near)
		{
			if (hotspot.getKind() != kind)
			{
				continue;
			}
			double d = Math.hypot(hotspot.getX() - x, hotspot.getY() - y);
			if (d <= bestDistance)
			{
				bestDistance = d;
				best = hotspot;
			}
		}
		return best;
	}

	/** A card made from a note about the icon. */
	private static Hotspot detailed(int mapId, double[] icon, Detail detail, int floor, boolean drawn)
	{
		HoverCard.Builder card = HoverCard.builder(clean(detail.title));
		if (detail.subtitle != null && !detail.subtitle.trim().isEmpty())
		{
			card.subtitle(clean(detail.subtitle));
		}
		if (detail.rows != null)
		{
			for (List<String> row : detail.rows)
			{
				if (row != null && row.size() >= 2)
				{
					card.row(clean(row.get(0)), clean(row.get(1)));
				}
			}
		}
		return new Hotspot(mapId, icon[0], icon[1], ICON_RADIUS, card.build(), null, Collections.emptyList(), Hotspot.Kind.POI, null, null,
			floor, drawn);
	}

	/** Text from the data with nothing in it that could be read as markup. */
	private static String clean(String text)
	{
		return text == null ? "" : text.replaceAll("[<>\\p{Cntrl}]", "").trim();
	}

	/** How far, in tiles, a label may be from an icon and still say where it is. */
	private static final double NEAR_LABEL_TILES = 70.0;

	/** The nearest of the game's labels in a larger size, as a place to say an icon is near, or null. */
	private static String nearLabel(List<Label> labels, double x, double y)
	{
		if (labels == null)
		{
			return null;
		}
		String best = null;
		double bestDistance = NEAR_LABEL_TILES;
		for (Label label : labels)
		{
			if (label.getPlane() != 0 || label.getScale() < 1)
			{
				continue;
			}
			double d = Math.hypot(label.getX() - x, label.getY() - y);
			if (d < bestDistance)
			{
				bestDistance = d;
				best = label.getText().replace('\n', ' ').trim();
			}
		}
		return best;
	}

	/** The quest whose start is nearest an icon, within a few tiles, or null. */
	private static SearchEntry nearestQuest(List<SearchEntry> quests, double x, double y)
	{
		SearchEntry best = null;
		double bestDistance = QUEST_TILES;
		for (SearchEntry quest : quests)
		{
			double d = Math.hypot(quest.getX() - x, quest.getY() - y);
			if (d <= bestDistance)
			{
				bestDistance = d;
				best = quest;
			}
		}
		return best;
	}

	/**
	 * What the points merged into an icon say about the level it asks for. Every point must say one, and then the icon is out of reach only
	 * when the player lacks all of them. Null when any point says nothing about a level, or there are none.
	 */
	private static Need needOfPoints(@Nullable List<IconExtra> extras)
	{
		if (extras == null || extras.isEmpty())
		{
			return null;
		}
		List<List<List<String>>> each = new ArrayList<>(extras.size());
		for (IconExtra extra : extras)
		{
			each.add(Needs.fromPoint(extra.getLayer(), extra.getDetail()));
		}
		return Needs.fromPoints(each);
	}

	/** A quest start this close to a quest icon is the one the icon marks. */
	private static final double QUEST_TILES = 6.0;

	private static String iconKey(int mapId, double x, double y, int floor)
	{
		return mapId + ":" + x + ":" + y + ":" + floor;
	}

	/**
	 * What the points of interest merged into each icon add to its card, by the icon's map, place and floor. A point merged into an icon
	 * is not drawn and answers no hover of its own (see {@link Hotspot#isMerged()}), so its name and detail travel with the icon.
	 */
	private static Map<String, List<IconExtra>> mergedInto(List<Hotspot> existing)
	{
		Map<String, List<IconExtra>> result = new HashMap<>();
		for (Hotspot hotspot : existing)
		{
			Hotspot.Merge merge = hotspot.getMerge();
			if (merge == null)
			{
				continue;
			}
			HoverCard card = hotspot.getCard();
			result.computeIfAbsent(iconKey(hotspot.getMapId(), merge.getIconX(), merge.getIconY(), merge.getIconFloor()),
				k -> new ArrayList<>()).add(new IconExtra(merge.isAlways() ? null : hotspot.getCategory(), card.getTitle(),
				card.getSubtitle(), merge.getTileX(), merge.getTileY()));
		}
		return result;
	}

	/** The always shown cards by map, so an icon can be checked against the ones near it. */
	private static Map<Integer, List<Hotspot>> coveredBy(List<Hotspot> existing)
	{
		Map<Integer, List<Hotspot>> result = new HashMap<>();
		for (Hotspot hotspot : existing)
		{
			if (hotspot.getCategory() == null)
			{
				result.computeIfAbsent(hotspot.getMapId(), id -> new ArrayList<>()).add(hotspot);
			}
		}
		return result;
	}

	/**
	 * The card the plugin already has that answers for an icon, or null. An icon with a kind of card of its own, such as an entrance for a
	 * dungeon icon, is only covered by that kind, so a shop standing next to it does not hide it.
	 */
	private static Hotspot coveringCard(List<Hotspot> near, double x, double y, String category)
	{
		if (near == null)
		{
			return null;
		}
		Hotspot.Kind kind = kindFor(category);
		Hotspot best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Hotspot hotspot : near)
		{
			if (kind != null && hotspot.getKind() != kind)
			{
				continue;
			}
			if (Math.abs(hotspot.getX() - x) <= COVERED_TILES && Math.abs(hotspot.getY() - y) <= COVERED_TILES)
			{
				double d = Math.hypot(hotspot.getX() - x, hotspot.getY() - y);
				if (d < bestDistance)
				{
					bestDistance = d;
					best = hotspot;
				}
			}
		}
		return best;
	}

	/**
	 * The categories whose icons are always drawn, at every zoom, in the order they win a crowded spot: the things a player looks for
	 * first. Every other category is minor and fades out as the map zooms out.
	 */
	private static final List<String> MAJOR = Arrays.asList("Bank", "Transportation", "Quest", "Dungeon",
		"Map link", "Altar", "Minigame", "Agility shortcut", "Agility shortcut (One way)", "Docking point", "Slayer Master",
		"Furnace", "Anvil", "Farming patch", "Fishing spot", "Rare trees", "Hunter training", "Mining site", "Grand Exchange", "Raid",
		"Port task board", "Salvaging station", "Salvaging spot", "Ledger table", "Shipwright", "Crew registrar", "Trawling shoal",
		"Barracuda Trial", "Clan Hub", "Task Master", "Poll booth", "Agility training", "Combat training");

	/** The place of a major category in the order they win a crowded spot, or {@link GameIconScale#MINOR_PRIORITY} for a minor one. */
	static int priorityOf(String category)
	{
		int at = MAJOR.indexOf(category);
		return at < 0 ? GameIconScale.MINOR_PRIORITY : at;
	}

	/** The shape of the bundled file. Filled by Gson. */
	static final class File
	{
		List<RawLabel> labels;
		List<String> categories;
		Map<String, double[][]> icons;
		int[] categoryIds;
		int[][] links;
		int[] sprites;
	}

	static final class RawLabel
	{
		String t;
		int x;
		int y;
		int z;
		int s;
		int c;
	}
}
