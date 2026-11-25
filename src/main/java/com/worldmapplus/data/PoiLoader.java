package com.worldmapplus.data;

import com.google.gson.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.map.*;
import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * Reads the bundled points of interest: agility courses, banks, fishing spots and the other kinds
 * the World Map plugin shows, as hotspots with a card each.
 * <p>
 * A point that has an icon of the game at its place (tools/merge_pois.py says which, in {@code g}) is not drawn by the plugin: it is
 * loaded as a merged point, standing on the icon, and the icon's card says what it says.
 */
@Slf4j
public final class PoiLoader
{
	private static final String RESOURCE = "/com/worldmapplus/pois.json";
	private static final int RADIUS = 11;

	private PoiLoader()
	{
	}

	/** The categories, in the order the layers panel lists them, and every point as a hotspot. */
	@Getter
	public static final class Result
	{
		private final List<PoiCategory> categories;
		private final List<Hotspot> hotspots;

		Result(List<PoiCategory> categories, List<Hotspot> hotspots)
		{
			this.categories = Collections.unmodifiableList(categories);
			this.hotspots = Collections.unmodifiableList(hotspots);
		}
	}

	public static Result load(Gson gson, MapCatalog catalog)
	{
		try (InputStream in = PoiLoader.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("World Map Plus: {} is missing from the plugin jar", RESOURCE);
				return new Result(new ArrayList<>(), new ArrayList<>());
			}
			return read(new InputStreamReader(in, StandardCharsets.UTF_8), gson, catalog);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("World Map Plus: could not read {}", RESOURCE, e);
			return new Result(new ArrayList<>(), new ArrayList<>());
		}
	}

	public static Result read(Reader reader, Gson gson, MapCatalog catalog)
	{
		LocalDate now = LocalDate.now();
		return read(reader, gson, catalog, now.getYear() * 10000 + now.getMonthValue() * 100 + now.getDayOfMonth());
	}

	/**
	 * As {@link #read(Reader, Gson, MapCatalog)}, on a given day written as yyyymmdd. A point with a start {@code s} or an end
	 * {@code e} (tools/merge_seasonal.py, for events of the game) is left out before its start and after its end, so an event
	 * marker goes away on its own once the event is over, with no new release; a category whose points all ended goes with them.
	 */
	static Result read(Reader reader, Gson gson, MapCatalog catalog, int today)
	{
		PoiFile file = gson.fromJson(reader, PoiFile.class);
		List<PoiCategory> categories = new ArrayList<>();
		List<Hotspot> hotspots = new ArrayList<>();
		if (file == null || file.categories == null)
		{
			return new Result(categories, hotspots);
		}
		for (Category category : file.categories)
		{
			if (category == null || category.id == null || category.label == null)
			{
				continue;
			}
			categories.add(new PoiCategory(category.id, category.label,
				category.glyph == null ? "?" : category.glyph, PoiCategory.parseColor(category.color),
				category.layer == null || category.layer));
			if (category.items == null)
			{
				continue;
			}
			int ended = 0;
			for (Item item : category.items)
			{
				if (item == null || item.n == null || item.p == null)
				{
					continue;
				}
				if (item.s > today || item.e > 0 && item.e < today)
				{
					ended++;
					continue;
				}
				HoverCard.Builder builder = HoverCard.builder(item.n);
				if (item.d != null && !item.d.isEmpty())
				{
					builder.subtitle(item.d);
				}
				HoverCard card = builder.build();
				boolean always = category.layer != null && !category.layer;
				for (int i = 0; i + 1 < item.p.length; i += 2)
				{
					MapInfo map = catalog.mapFor(item.p[i], item.p[i + 1]);
					if (map == null)
					{
						continue;
					}
					int at = i / 2 * 3;
					if (item.g != null && item.g.length >= at + 3 && item.g[at] >= 0 && item.g[at + 1] >= 0)
					{
						// An icon of the game is at this place: the point is merged into it and stands where it does.
						hotspots.add(Hotspot.merged(map.getId(), item.g[at], item.g[at + 1], RADIUS, card, category.id,
							new Hotspot.Merge(item.g[at], item.g[at + 1], (int) item.g[at + 2], item.p[i], item.p[i + 1], always)));
						continue;
					}
					hotspots.add(new Hotspot(map.getId(), item.p[i] + 0.5, item.p[i + 1] + 0.5, RADIUS, card, null,
						new ArrayList<>(), Hotspot.Kind.POI, category.id, item.i));
				}
			}
			if (ended > 0 && ended == category.items.size())
			{
				categories.remove(categories.size() - 1);
			}
		}
		return new Result(categories, hotspots);
	}

	// ------------------------------------------------------------------
	// Resource shapes, bound by Gson from concrete classes
	// ------------------------------------------------------------------

	static final class PoiFile
	{
		List<Category> categories;
	}

	static final class Category
	{
		String id;
		String label;
		String glyph;
		String color;
		Boolean layer;
		List<Item> items;
	}

	static final class Item
	{
		String n;
		String d;
		int[] p;
		String i;
		/** Where the icon of the game each point was merged into is, three numbers a point: x, y and floor, or -1 for none. */
		double[] g;
		/** First and last day the point is shown, as yyyymmdd; 0 for no limit. Only the points of events have them. */
		int s;
		int e;
	}
}
