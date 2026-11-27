package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.IconCards;
import com.worldmapplus.layers.PoiCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 68: the text of the hover cards, as a player reads it, for a few hundred things of every kind (entrances, transports, shops,
 * points, teleports and the game's icons with every layer on), short and with Shift held. Written down before the cards moved into
 * {@code tools/build_cards.py} ({@code golden/hover-cards.txt}); the text now is written to {@code build/hover-cards-actual.txt}. After a
 * data update that is meant to change cards, look at the difference and copy that file over the golden one.
 */
public class HoverCardGoldenTest
{
	private static final int PER_KIND = 250;

	@Test
	public void cardsSayWhatTheySaidBeforeTheMove() throws IOException
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		List<String> lines = new ArrayList<>();
		try
		{
			MapAssets assets = new MapAssets(new Gson(), executor);
			Map<String, List<String>> byKind = new LinkedHashMap<>();
			for (Hotspot h : assets.getHotspots().all())
			{
				if (!h.isMapIcon())
				{
					byKind.computeIfAbsent(h.getKind().name(), k -> new ArrayList<>()).add(where(h.getMapId(), h.getX(), h.getY())
						+ text(h.getCard()));
				}
			}
			Set<String> every = new HashSet<>();
			for (PoiCategory category : assets.getPoiCategories())
			{
				every.add(category.getId());
			}
			for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
			{
				if (spec.getCard() != null)
				{
					HoverCard shown = IconCards.compose(spec.getCard().getCard(), spec.getFloor(), spec.getExtras(), every);
					byKind.computeIfAbsent("ICON", k -> new ArrayList<>()).add(where(spec.getMapId(), spec.getX(), spec.getY())
						+ "floor " + spec.getFloor() + " " + text(shown));
				}
			}
			for (Map.Entry<String, List<String>> kind : byKind.entrySet())
			{
				List<String> all = kind.getValue();
				all.sort(Comparator.naturalOrder());
				lines.add("## " + kind.getKey() + " (" + all.size() + ")");
				int step = Math.max(1, all.size() / PER_KIND);
				for (int i = 0; i < all.size(); i += step)
				{
					lines.add(all.get(i));
				}
			}
		}
		finally
		{
			executor.shutdownNow();
		}
		Path actual = Paths.get("build", "hover-cards-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, lines, StandardCharsets.UTF_8);
		Path golden = Paths.get("src", "test", "resources", "golden", "hover-cards.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		assertEquals(String.join("\n", Files.readAllLines(golden, StandardCharsets.UTF_8)), String.join("\n", lines));
	}

	private static String where(int map, double x, double y)
	{
		return String.format(java.util.Locale.ROOT, "%05d %09.1f %09.1f | ", map, x, y);
	}

	private static String text(HoverCard card)
	{
		StringBuilder out = new StringBuilder(card.getTitle());
		out.append(" | ").append(card.getSubtitle());
		for (HoverCard.Row row : card.visibleRows(false))
		{
			out.append(" | ").append(row.getLabel()).append(" = ").append(row.getValue());
		}
		if (!card.getOverflow().isEmpty())
		{
			out.append(" || Shift:");
			for (HoverCard.Row row : card.visibleRows(true))
			{
				out.append(" | ").append(row.getLabel()).append(" = ").append(row.getValue());
			}
		}
		if (card.hasPreview())
		{
			out.append(" | preview ").append(card.getPreviewMapId()).append(' ').append(card.getPreviewX()).append(',')
				.append(card.getPreviewY());
		}
		out.append(" | hint ").append(card.getHint()).append(" | wiki ").append(card.getWiki());
		return out.toString();
	}
}
