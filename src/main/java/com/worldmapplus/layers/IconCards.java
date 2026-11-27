package com.worldmapplus.layers;

import java.util.*;

/**
 * The card shown for one of the game's icons: the icon's own card, the floor it is on, and what the plugin's points of interest
 * that were merged into the icon add while their layers are on.
 * <p>
 * A bare icon card says only what kind of icon it is. When one point of interest is merged into it, that point's name is the better
 * title (a rare tree's species, a minigame's name) and the kind of icon moves to the line under it. Where the point's name is only the
 * place it is at (a fishing spot, a hunting area, a mining site), the card keeps its title and gets a row for what is there: the fish,
 * the creatures, the ore. Several points make several rows. A card that already says more than a kind (a shop, a way into another map)
 * keeps all it says and gets rows.
 * <p>
 * The floor is named in the game's UK words ({@link FloorNames}): nothing for the ground floor, and 1st floor, 2nd floor or 3rd
 * floor for an icon of a floor above.
 */
public final class IconCards
{
	/** The label of the row that names the floor. */
	public static final String FLOOR_LABEL = "Floor";

	/** Layers whose points are named for the place they are at, so the name makes a poor title: the row says what is there instead. */
	private static final Set<String> PLACE_NAMED = new HashSet<>(Arrays.asList("fishing", "hunter", "mining"));

	/** What the row of a layer whose points are named for their place is called when it is the only one. */
	private static String rowLabel(String layer)
	{
		if ("fishing".equals(layer))
		{
			return "Fish";
		}
		if ("hunter".equals(layer))
		{
			return "Creatures";
		}
		if ("mining".equals(layer))
		{
			return "Ore";
		}
		return null;
	}

	/** The start of a point's detail that only repeats what the layer is called. */
	private static final String[] PREFIXES = {"Fishing: ", "Hunting: ", "Mining: "};

	/** Details that say nothing the kind of icon does not. */
	private static final Set<String> GENERIC = new HashSet<>(Arrays.asList("Minigame", "Agility course", "Farming patch", "Bank",
		"Altar for praying", "Smithing anvils"));

	/** Rows an icon's card shows before the rest wait for Shift. */
	static final int MAX_ROWS = 12;

	private IconCards()
	{
	}

	/**
	 * The card to show for an icon.
	 *
	 * @param base the icon's own card (or, for a farming patch, its live card)
	 * @param floor the floor the icon is on
	 * @param extras what was merged into the icon, or null
	 * @param enabled the layers that are on
	 * @return {@code base} itself when there is nothing to add
	 */
	public static HoverCard compose(HoverCard base, int floor, List<IconExtra> extras, Set<String> enabled)
	{
		List<IconExtra> active = active(extras, enabled);
		String floorText = FloorNames.label(floor);
		if (active.isEmpty() && floorText.isEmpty())
		{
			return base;
		}
		HoverCard.Builder card = base.toBuilder();
		if (!active.isEmpty())
		{
			add(card, base, active);
		}
		if (!floorText.isEmpty())
		{
			card.row(FLOOR_LABEL, floorText);
		}
		return card.build();
	}

	/** The extras that are shown now, with the repeats left out. */
	static List<IconExtra> active(List<IconExtra> extras, Set<String> enabled)
	{
		List<IconExtra> active = new ArrayList<>();
		if (extras == null)
		{
			return active;
		}
		for (IconExtra extra : extras)
		{
			if (extra.getLayer() != null && !enabled.contains(extra.getLayer()))
			{
				continue;
			}
			boolean repeat = false;
			for (IconExtra seen : active)
			{
				repeat |= seen.sameAs(extra);
			}
			if (!repeat)
			{
				active.add(extra);
			}
		}
		return active;
	}

	private static void add(HoverCard.Builder card, HoverCard base, List<IconExtra> active)
	{
		boolean bare = base.getRows().isEmpty() && base.getOverflow().isEmpty() && !base.hasPreview();
		if (active.size() == 1 && bare && !PLACE_NAMED.contains(active.get(0).getLayer()))
		{
			IconExtra only = active.get(0);
			String detail = detailOf(only);
			card.title(only.getName());
			card.subtitle(detail.isEmpty() ? base.getTitle() : detail);
			// The card is now about the point, so the wiki page is the point's, when it has one.
			card.wiki(only.getWiki());
			String where = base.getSubtitle();
			if (where != null && !where.isEmpty())
			{
				int space = where.indexOf(' ');
				boolean short1 = space > 0 && space <= 4;
				card.row(short1 ? where.substring(0, space) : "Place", short1 ? where.substring(space + 1) : where);
			}
			return;
		}
		int shown = base.getRows().size();
		if (active.size() == 1 && rowLabel(active.get(0).getLayer()) != null)
		{
			// The name is the place, which the card already gives when it says it is near it.
			String place = active.get(0).getName();
			String subtitle = base.getSubtitle();
			if (!place.isEmpty() && (subtitle == null || !subtitle.toLowerCase(Locale.ROOT).contains(place.toLowerCase(Locale.ROOT))))
			{
				card.row("Where", place);
				shown++;
			}
		}
		for (IconExtra extra : active)
		{
			String label = active.size() == 1 && rowLabel(extra.getLayer()) != null ? rowLabel(extra.getLayer()) : extra.getName();
			if (shown++ < MAX_ROWS)
			{
				card.row(label, detailOf(extra));
			}
			else
			{
				card.overflowRow(label, detailOf(extra));
			}
		}
	}

	/** What a point says about itself, without the words that repeat its layer, or empty when that is only the kind of icon. */
	public static String detailOf(IconExtra extra)
	{
		String detail = extra.getDetail();
		if (detail == null)
		{
			return "";
		}
		for (String prefix : PREFIXES)
		{
			if (detail.startsWith(prefix))
			{
				detail = detail.substring(prefix.length());
			}
		}
		detail = detail.trim();
		return GENERIC.contains(detail) ? "" : detail;
	}
}
