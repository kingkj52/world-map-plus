package com.worldmapplus.search;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.*;
import javax.annotation.*;
import lombok.*;

/**
 * The state of the search field and its result list: what is typed, which result is selected, and
 * whether the field has the keyboard. Pure logic, so keys and results can be tested without a game.
 */
public final class SearchBox
{
	/** Longest query accepted. */
	public static final int MAX_LENGTH = 60;

	/** Results kept for a search, of which the bar shows as many as fit and scrolls through the rest. */
	public static final int MAX_RESULTS = 200;

	private final StringBuilder text = new StringBuilder();
	/** True while all the text is selected. */
	@Getter
	private boolean allSelected;
	@Getter
	private boolean focused;
	@Getter
	private List<SearchEntry> results = Collections.emptyList();
	@Getter
	private int selected;
	private SearchIndex index = new SearchIndex(Collections.emptyList());
	private IntBinaryOperator tileMap = (x, y) -> -1;
	private Supplier<SearchContext> context = () -> SearchContext.NONE;
	/** The first result shown, when there are more than fit. */
	@Getter
	private int scrollTop;
	private int visibleRows = 10;
	/**
	 * A line of help for what is typed: a way to finish a filter, a word that is not understood, or why there are
	 * no results. Empty when there is nothing to say.
	 */
	@Getter
	private String hint = "";
	private String completion = "";
	/** Gives the results other plugins have for a text, which follow this plugin's own. Null for none. */
	private BiFunction<String, SearchContext, List<SearchEntry>> extra;

	/** The text the results were last worked out for, so the list only goes back to the top for a new search. */
	private String lastQuery = "";

	public void setIndex(SearchIndex index)
	{
		this.index = index;
		refresh();
	}

	/**
	 * Lets typed coordinates be found. The operator says which map a tile is on, or -1 for none, and a
	 * typed tile such as 3222 3218 then comes first in the results.
	 */
	public void setTileMap(IntBinaryOperator tileMap)
	{
		this.tileMap = tileMap;
		refresh();
	}

	/** Says where the player is and what place names mean, asked for at each search. */
	public void setContext(Supplier<SearchContext> context)
	{
		this.context = context;
		refresh();
	}

	/** Adds what other plugins found for the text after this plugin's own results. Null for none. */
	public void setExtra(@Nullable BiFunction<String, SearchContext, List<SearchEntry>> extra)
	{
		this.extra = extra;
		refresh();
	}

	/** Works the list out again, for when an answer from another plugin has come in. */
	public void refreshExtras()
	{
		refresh();
	}

	/** How many results the bar has room for, so scrolling and the selection can keep to them. */
	public void setVisibleRows(int rows)
	{
		this.visibleRows = Math.max(1, rows);
		clampScroll();
	}

	/** Scrolls by a number of rows, clamped to the results. */
	public void scroll(int rows)
	{
		scrollTop += rows;
		clampScroll();
	}

	/** Scrolls so the thumb of a scroll bar clicked at {@code fraction} of the track (0 to 1) is centred there. */
	public void scrollToFraction(double fraction)
	{
		int hidden = Math.max(0, results.size() - visibleRows);
		scrollTop = (int) Math.round(Math.max(0, Math.min(1, fraction)) * hidden);
		clampScroll();
	}

	private void clampScroll()
	{
		scrollTop = Math.max(0, Math.min(scrollTop, Math.max(0, results.size() - visibleRows)));
	}

	public void focus()
	{
		focused = true;
	}

	/** Gives the keyboard back. The text stays, so the field can be resumed. */
	public void blur()
	{
		focused = false;
		allSelected = false;
	}

	/** Selects all the text, as Ctrl+A does: the next letter replaces it and Backspace clears it. */
	public void selectAll()
	{
		allSelected = focused && text.length() > 0;
	}

	public String getText()
	{
		return text.toString();
	}

	public void select(int row)
	{
		if (row >= 0 && row < results.size())
		{
			selected = row;
		}
	}

	/** Adds a typed character. Control characters and anything past the limit are ignored. */
	public void type(char c)
	{
		if (!focused || Character.isISOControl(c))
		{
			return;
		}
		if (allSelected)
		{
			text.setLength(0);
			allSelected = false;
		}
		if (text.length() >= MAX_LENGTH)
		{
			return;
		}
		text.append(c);
		refresh();
	}

	/**
	 * Replaces the whole text, for typing that happens somewhere else, such as the chat area, and is mirrored here. Control
	 * characters and anything past the limit are dropped, and nothing changes while the field does not have the keyboard.
	 */
	public void setText(String value)
	{
		if (!focused)
		{
			return;
		}
		StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
		for (int i = 0; i < value.length() && clean.length() < MAX_LENGTH; i++)
		{
			if (!Character.isISOControl(value.charAt(i)))
			{
				clean.append(value.charAt(i));
			}
		}
		if (clean.toString().contentEquals(text))
		{
			return;
		}
		allSelected = false;
		text.setLength(0);
		text.append(clean);
		refresh();
	}

	public void backspace()
	{
		if (focused && text.length() > 0)
		{
			text.setLength(allSelected ? 0 : text.length() - 1);
			allSelected = false;
			refresh();
		}
	}

	/** Empties the field. Returns true if there was anything to clear. */
	public boolean clear()
	{
		boolean had = text.length() > 0;
		allSelected = false;
		text.setLength(0);
		refresh();
		return had;
	}

	public void moveSelection(int delta)
	{
		if (results.isEmpty())
		{
			return;
		}
		selected = Math.floorMod(selected + delta, results.size());
		if (selected < scrollTop)
		{
			scrollTop = selected;
		}
		else if (selected >= scrollTop + visibleRows)
		{
			scrollTop = selected - visibleRows + 1;
		}
		clampScroll();
	}

	/** The selected result, or null when there are none. */
	@Nullable
	public SearchEntry selectedEntry()
	{
		return selected >= 0 && selected < results.size() ? results.get(selected) : null;
	}

	/** Finishes the filter being typed with the best name for it, if there is one. Returns true when it did. */
	public boolean complete()
	{
		if (completion.isEmpty() || completion.length() > MAX_LENGTH)
		{
			return false;
		}
		allSelected = false;
		text.setLength(0);
		text.append(completion);
		refresh();
		return true;
	}

	private static final Pattern AREA_FILTER = Pattern.compile(
		"^(-?)(region|in|area|at|where|place|map):\"?([^\"]*)\"?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern KIND_FILTER = Pattern.compile(
		"^(-?)(type|kind):([^,\"]*,)*([^,\"]*)$", Pattern.CASE_INSENSITIVE);

	private void explain(SearchQuery query, SearchContext where)
	{
		hint = "";
		completion = "";
		String typed = text.toString();
		if (typed.trim().isEmpty() || TileQuery.parse(typed) != null)
		{
			return;
		}
		Gazetteer places = where.getGazetteer();
		List<String> tokens = SearchQuery.tokens(typed);
		boolean finishing = !tokens.isEmpty() && !Character.isWhitespace(typed.charAt(typed.length() - 1));
		if (finishing)
		{
			String last = tokens.get(tokens.size() - 1);
			String before = typed.substring(0, typed.length() - last.length());
			Matcher area = AREA_FILTER.matcher(last);
			Matcher kind = KIND_FILTER.matcher(last);
			if (area.matches() && !area.group(3).trim().isEmpty())
			{
				List<String> names = places.suggest(SearchQuery.splitCamel(area.group(3)), 1);
				if (!names.isEmpty())
				{
					completion = before + area.group(1) + area.group(2).toLowerCase(Locale.ROOT) + ":\""
						+ names.get(0) + "\"";
				}
			}
			else if (area.matches())
			{
				hint = "Type part of a name, as in region:\"kebos lowlands\"";
				return;
			}
			else if (kind.matches() && !kind.group(4).isEmpty())
			{
				String part = SearchEntry.normalize(kind.group(4)).replace(' ', '_');
				String found = firstKindStarting(part);
				if (found != null)
				{
					String head = last.substring(0, last.length() - kind.group(4).length());
					completion = before + head + found;
				}
			}
		}
		if (completion.equalsIgnoreCase(typed))
		{
			completion = "";
		}
		if (!completion.isEmpty() && completion.length() <= MAX_LENGTH)
		{
			hint = "Tab to finish: " + completion.substring(lastTokenStart(completion));
			return;
		}
		completion = "";
		if (query.hasBadFilter())
		{
			hint = "A filter value was not understood. Try dist:40 or sort:name";
			return;
		}
		if (!results.isEmpty())
		{
			return;
		}
		for (String region : query.getRegions())
		{
			if (!places.knows(region))
			{
				hint = unknownArea(places, region);
				return;
			}
		}
		for (String map : query.getMaps())
		{
			if (!places.knows(map))
			{
				hint = unknownArea(places, map);
				return;
			}
		}
		hint = "No results. Try fewer words, or a filter such as region:\"varrock\"";
	}

	private static int lastTokenStart(String completionText)
	{
		List<String> tokens = SearchQuery.tokens(completionText);
		return completionText.length() - tokens.get(tokens.size() - 1).length();
	}

	private static String unknownArea(Gazetteer places, String region)
	{
		String first = region.split(" ")[0];
		// A slip in the last letters still points at the right name, so shorter and shorter starts are tried.
		List<String> names = new ArrayList<>();
		for (int length = first.length(); length >= 3 && names.isEmpty(); length--)
		{
			names = places.suggest(first.substring(0, length), 2);
		}
		StringBuilder sb = new StringBuilder("No area called \"" + region + "\".");
		if (!names.isEmpty())
		{
			sb.append(" Try region:\"").append(names.get(0)).append("\"");
		}
		return sb.toString();
	}

	private static String firstKindStarting(String part)
	{
		List<String> all = new ArrayList<>(Arrays.asList(SearchQuery.RESULT_KINDS));
		all.addAll(Arrays.asList(SearchQuery.ITEM_KINDS));
		for (String kind : all)
		{
			if (kind.startsWith(part) && !kind.equals(part))
			{
				return kind;
			}
		}
		return null;
	}

	/**
	 * Puts the results of other plugins among the results of this search by the same score (of the plain words, as the filtered search
	 * scores them), so that "every bank" is where a result of that name belongs, with a filter or without. They already passed the
	 * filters. Without plain words, or with sort: or a distance, the order is not by score and they go last.
	 */
	private static void merge(List<SearchEntry> own, List<SearchEntry> more, String typed)
	{
		SearchQuery query = SearchQuery.parse(typed);
		String normalized = query.hasFilters() ? String.join(" ", query.getWords()) : SearchEntry.normalize(typed);
		if (normalized.isEmpty() || query.getSort() != SearchQuery.Sort.RELEVANCE || query.getMaxDistance() >= 0)
		{
			own.addAll(more);
			return;
		}
		String[] words = normalized.split(" ");
		for (SearchEntry entry : more)
		{
			int score = SearchIndex.score(entry, normalized, words);
			int at = 0;
			while (at < own.size() && SearchIndex.score(own.get(at), normalized, words) >= score)
			{
				at++;
			}
			own.add(at, entry);
		}
	}

	private void refresh()
	{
		SearchContext where = context.get();
		results = index.search(text.toString(), MAX_RESULTS, where);
		boolean sameSearch = text.toString().equals(lastQuery);
		int[] tile = TileQuery.parse(text.toString());
		if (tile != null)
		{
			int map = tileMap.applyAsInt(tile[0], tile[1]);
			if (map >= 0)
			{
				List<SearchEntry> withTile = new ArrayList<>(results.size() + 1);
				withTile.add(new SearchEntry(SearchKind.TILE, "Tile " + tile[0] + ", " + tile[1],
					"Go to these coordinates",
					map, tile[0] + 0.5, tile[1] + 0.5));
				withTile.addAll(results);
				results = withTile;
			}
		}
		if (extra != null)
		{
			List<SearchEntry> more = extra.apply(text.toString(), where);
			if (!more.isEmpty())
			{
				List<SearchEntry> both = new ArrayList<>(results.subList(0, Math.min(results.size(), Math.max(0, MAX_RESULTS - more.size()))));
				merge(both, more, text.toString());
				results = both;
			}
		}
		// Something other than typing, such as the index being rebuilt, must not throw the list back to the top.
		if (sameSearch)
		{
			selected = Math.min(selected, Math.max(0, results.size() - 1));
			clampScroll();
		}
		else
		{
			selected = 0;
			scrollTop = 0;
		}
		lastQuery = text.toString();
		explain(SearchQuery.parse(text.toString()), where);
	}
}
