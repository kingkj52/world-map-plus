package com.worldmapplus.ui;

import com.worldmapplus.search.KindIndex;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Where a result for the places of a kind takes the map: the map to show and the places on it to mark. Pure, so it is tested
 * without a game.
 */
final class KindResults
{
	private KindResults()
	{
	}

	/**
	 * The result to go to for an entry of the kind index: on the map the player is on (or nearest to) when the kind has places there,
	 * else on the map being looked at, else on the map of its first group, which is the nearest map fallback when the player is
	 * somewhere the kind has no place. The result stands at the place nearest the player on that map (the first place when the player
	 * is elsewhere) and carries every place of the entry on that map, so all are marked and further clicks go round them nearest
	 * first. Null when the kind is not known.
	 *
	 * @param playerMap the map the player is on or nearest to, or -1
	 * @param viewedMap the map being looked at, or -1
	 */
	@Nullable
	static SearchEntry target(KindIndex kinds, SearchEntry entry, int playerMap, int viewedMap)
	{
		KindIndex.Kind kind = kinds.byId(entry.getKindId());
		if (kind == null)
		{
			return null;
		}
		List<KindIndex.Group> groups = new ArrayList<>();
		if (entry.getGroup() >= 0 && entry.getGroup() < kind.getGroups().size())
		{
			groups.add(kind.getGroups().get(entry.getGroup()));
		}
		else
		{
			groups.addAll(kind.getGroups());
		}
		int map = hasPlacesOn(groups, playerMap) ? playerMap : hasPlacesOn(groups, viewedMap) ? viewedMap : groups.get(0).getMapId();
		List<double[]> tiles = new ArrayList<>();
		for (KindIndex.Group group : groups)
		{
			if (group.getMapId() == map)
			{
				tiles.addAll(group.getTiles());
			}
		}
		double[] first = tiles.get(0);
		return new SearchEntry(SearchKind.KIND, entry.getName(), entry.getDetail(), map, first[0], first[1], tiles);
	}

	private static boolean hasPlacesOn(List<KindIndex.Group> groups, int map)
	{
		if (map < 0)
		{
			return false;
		}
		for (KindIndex.Group group : groups)
		{
			if (group.getMapId() == map)
			{
				return true;
			}
		}
		return false;
	}
}
