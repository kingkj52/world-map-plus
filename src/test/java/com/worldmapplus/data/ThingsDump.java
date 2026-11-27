package com.worldmapplus.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.IconExtra;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.unlock.Need;
import com.worldmapplus.unlock.Needs;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the plugin knows about the cards of the map, written out as text one record a line: every hotspot the mouse can find (in
 * the order the index lists them), every icon of the game's list with the card that answers for it, the labels, the spots, the
 * category names and the search entries. Round 68 wrote this from the Java code that worked it all out (data/HotspotLoader,
 * ShortcutLoader, IconRequirements and MapElements) into {@code golden/things-derived.json}; the plugin now reads {@code things.json},
 * made by {@code tools/build_cards.py}, and {@code ThingsFileTest} checks that what it reads writes out the same.
 */
final class ThingsDump
{
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private ThingsDump()
	{
	}

	static String dump(MapAssets assets)
	{
		StringBuilder out = new StringBuilder();
		List<Hotspot> all = assets.getHotspots().all();
		Map<Hotspot, Integer> index = new IdentityHashMap<>();
		section(out, "hotspots");
		for (int i = 0; i < all.size(); i++)
		{
			index.put(all.get(i), i);
			line(out, i, hotspot(all.get(i)));
		}
		section(out, "icons");
		MapElements elements = assets.getElements();
		int n = 0;
		for (GameIconSet.Spec spec : elements.getGameIcons())
		{
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("m", spec.getMapId());
			r.put("x", spec.getX());
			r.put("y", spec.getY());
			r.put("pic", spec.getPicture());
			r.put("floor", spec.getFloor());
			r.put("cat", spec.getCategory());
			r.put("prio", spec.getPriority());
			Hotspot card = spec.getCard();
			if (card != null)
			{
				Integer at = index.get(card);
				if (at != null)
				{
					r.put("card", at);
				}
				else
				{
					r.put("copy", hotspot(card));
				}
			}
			if (spec.getExtras() != null)
			{
				List<Object> extras = new ArrayList<>();
				for (IconExtra extra : spec.getExtras())
				{
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("layer", extra.getLayer());
					e.put("name", extra.getName());
					e.put("detail", extra.getDetail());
					e.put("tx", extra.getTileX());
					e.put("ty", extra.getTileY());
					extras.add(e);
				}
				r.put("extras", extras);
			}
			r.put("need", need(spec.getNeed()));
			line(out, n++, r);
		}
		section(out, "labels");
		n = 0;
		for (MapInfo map : assets.getCatalog().getMaps())
		{
			for (MapElements.Label label : elements.labelsOn(map.getId()))
			{
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("on", map.getId());
				r.put("t", label.getText());
				r.put("lines", label.getLines());
				r.put("m", label.getMapId());
				r.put("x", label.getX());
				r.put("y", label.getY());
				r.put("z", label.getPlane());
				r.put("s", label.getScale());
				r.put("c", label.getColor());
				line(out, n++, r);
			}
		}
		section(out, "spots");
		n = 0;
		for (MapInfo map : assets.getCatalog().getMaps())
		{
			for (MapElements.Spot spot : elements.spotsOn(map.getId()))
			{
				line(out, n++, new Object[] {map.getId(), spot.getX(), spot.getY(), spot.getCategory()});
			}
		}
		section(out, "categories");
		n = 0;
		for (int i = 0; i < elements.categoryCount(); i++)
		{
			line(out, n++, new Object[] {i, elements.categoryKind(i)});
		}
		for (int id = 0; id < 100_000; id++)
		{
			if (elements.isCategory(id))
			{
				line(out, n++, new Object[] {"id", id, elements.categoryName(id)});
			}
		}
		line(out, n++, new Object[] {"drawn", elements.drawnCategoryNames()});
		line(out, n++, new Object[] {"pictures", elements.getPictureCount(), "labels", elements.labelCount(), "icons",
			elements.getIcons().size()});
		section(out, "search");
		n = 0;
		for (SearchEntry e : assets.getSearchEntries())
		{
			// Only the entries made from the cards: the spawns, quests, music and places have tests of their own.
			String k = e.getKind().name();
			boolean fromCards = k.equals("DOORWAY") || k.equals("TRANSPORT") || k.equals("SHOP") || k.equals("TELEPORT")
				|| k.equals("POINT") || k.equals("ITEM") && e.getDetail().startsWith("Sold at ")
				|| k.equals("NPC") && e.getDetail().startsWith("Runs ");
			if (!fromCards)
			{
				continue;
			}
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("k", e.getKind().name());
			r.put("n", e.getName());
			r.put("d", e.getDetail());
			r.put("m", e.getMapId());
			r.put("x", e.getX());
			r.put("y", e.getY());
			if (e.getAlsoAt().size() > 0)
			{
				r.put("at", e.getAlsoAt().size());
			}
			r.put("need", need(e.getNeed()));
			line(out, n++, r);
		}
		return out.toString();
	}

	private static void section(StringBuilder out, String name)
	{
		out.append("## ").append(name).append('\n');
	}

	private static void line(StringBuilder out, int i, Object record)
	{
		out.append(i).append(' ').append(GSON.toJson(record)).append('\n');
	}

	static Map<String, Object> hotspot(Hotspot h)
	{
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("kind", h.getKind().name());
		r.put("m", h.getMapId());
		r.put("x", h.getX());
		r.put("y", h.getY());
		r.put("r", h.getRadiusPixels());
		r.put("card", card(h.getCard()));
		if (h.getTarget() != null)
		{
			r.put("go", new Object[] {h.getTarget().getMapId(), h.getTarget().getX(), h.getTarget().getY()});
		}
		if (!h.getLinks().isEmpty())
		{
			List<Object> links = new ArrayList<>();
			for (Hotspot.Link link : h.getLinks())
			{
				links.add(new Object[] {link.getMapId(), link.getX(), link.getY(), link.getLabel()});
			}
			r.put("links", links);
		}
		r.put("layer", h.getCategory());
		r.put("icon", h.getIcon());
		r.put("floor", h.getFloor());
		r.put("drawn", h.isDrawn());
		if (h.getMerge() != null)
		{
			Hotspot.Merge m = h.getMerge();
			r.put("merge", new Object[] {m.getIconX(), m.getIconY(), m.getIconFloor(), m.getTileX(), m.getTileY(), m.isAlways()});
		}
		r.put("need", need(h.getNeed()));
		Map<String, Object> ways = new LinkedHashMap<>();
		for (List<HoverCard.Row> rows : java.util.Arrays.asList(h.getCard().getRows(), h.getCard().getOverflow()))
		{
			for (HoverCard.Row row : rows)
			{
				Need way = h.wayNeed(row.getLabel());
				if (way != null)
				{
					ways.put(row.getLabel(), need(way));
				}
			}
		}
		if (!ways.isEmpty())
		{
			r.put("ways", ways);
		}
		return r;
	}

	static Map<String, Object> card(HoverCard c)
	{
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("t", c.getTitle());
		r.put("s", c.getSubtitle());
		r.put("rows", rows(c.getRows()));
		if (!c.getOverflow().isEmpty())
		{
			r.put("more", rows(c.getOverflow()));
		}
		if (c.hasPreview())
		{
			r.put("preview", new Object[] {c.getPreviewMapId(), c.getPreviewX(), c.getPreviewY(), c.hasPreviewFocus()});
		}
		r.put("hint", c.getHint());
		r.put("wiki", c.getWiki());
		r.put("search", c.getWikiSearch());
		return r;
	}

	private static List<Object> rows(List<HoverCard.Row> rows)
	{
		List<Object> out = new ArrayList<>();
		for (HoverCard.Row row : rows)
		{
			out.add(new String[] {row.getLabel(), row.getValue()});
		}
		return out;
	}

	static Object need(Need need)
	{
		if (need == null)
		{
			return null;
		}
		Map<String, Object> r = new LinkedHashMap<>();
		List<String> ways = new ArrayList<>();
		for (Need.Check[] way : need.alternatives())
		{
			ways.add(Needs.describe(way));
		}
		r.put("any", ways);
		r.put("starts", need.startsQuest());
		return r;
	}
}
