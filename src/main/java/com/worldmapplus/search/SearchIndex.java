package com.worldmapplus.search;

import java.util.*;
import lombok.*;

/**
 * Ranks entries against what has been typed.
 * <p>
 * Every word typed has to match somewhere in an entry, in any order. A word scores best as a whole word of the name, then as the
 * start of a word, then inside a word (three letters or more), then one letter off a word or its start (five letters or more), and
 * the same in the detail for much less. An exact name, or the name's words in another order, beats a name that merely starts with
 * the query. Kinds break ties, so a place beats an item of the same name; then fewer words of its own come first.
 * <p>
 * Linear over the entries, which is fine for the ten thousand or so that are bundled: a lookup is
 * well under a millisecond per thousand entries.
 */
public final class SearchIndex
{
	/** Points for a typed word that fits the name, by how well it fits: none, one letter off, inside a word, word start, whole word. */
	private static final int[] NAME_POINTS = {0, 200, 300, 600, 700};
	/** The same for the detail, which counts less. */
	private static final int[] DETAIL_POINTS = {0, 30, 50, 100, 120};
	private static final int EXACT_NAME = 1000;
	private static final int NAME_STARTS_WITH_QUERY = 400;

	private final List<SearchEntry> entries;

	public SearchIndex(List<SearchEntry> entries)
	{
		List<SearchEntry> unique = new ArrayList<>(entries.size());
		Set<String> seen = new HashSet<>();
		for (SearchEntry entry : entries)
		{
			String key = entry.getKind() + "|" + entry.normalizedName() + "|"
				+ Math.round(entry.getX() / 8) + "|" + Math.round(entry.getY() / 8) + "|" + entry.getMapId() + "|" + entry.getOwnId();
			if (seen.add(key))
			{
				unique.add(entry);
			}
		}
		this.entries = Collections.unmodifiableList(unique);
	}

	public int size()
	{
		return entries.size();
	}

	public List<SearchEntry> search(String query, int limit)
	{
		List<SearchEntry> found = search(query, limit, false);
		// Named places are left out of an ordinary search, but when that finds nothing they are the answer.
		return found.isEmpty() ? search(query, limit, true) : found;
	}

	private List<SearchEntry> search(String query, int limit, boolean withLocations)
	{
		String normalized = SearchEntry.normalize(query);
		if (normalized.isEmpty() || limit <= 0)
		{
			return Collections.emptyList();
		}
		String[] words = normalized.split(" ");

		TopN top = new TopN(limit, PLAIN_ORDER, true);
		int seq = 0;
		for (SearchEntry entry : entries)
		{
			seq++;
			if (entry.isLocation() && !withLocations && !entry.isAreaName())
			{
				continue;
			}
			int score = score(entry, normalized, words);
			if (score > 0 && top.mayEnter(score))
			{
				top.offer(new Scored(entry, score, ownWords(entry.normalizedName(), words), seq));
			}
		}
		return top.result();
	}

	/** Ties keep a fixed order, so the list does not shuffle as more is typed: fewer words of its own, shorter, then by name. */
	private static final Comparator<Scored> PLAIN_ORDER = Comparator.<Scored>comparingInt(s -> -s.score)
		.thenComparingInt(s -> s.own)
		.thenComparingInt(s -> s.entry.getName().length())
		.thenComparing(s -> s.entry.getName())
		.thenComparingInt(s -> s.seq);

	/**
	 * Keeps the best {@code limit} of what is offered, in a heap whose top is the worst kept, so a search over every entry never sorts
	 * the matches it throws away. The order always ends with the entry's place in the index, which is what a stable sort of all
	 * matches does with ties, so the result is the first {@code limit} of the fully sorted list.
	 */
	private static final class TopN
	{
		private final int limit;
		private final Comparator<Scored> order;
		private final PriorityQueue<Scored> heap;
		/** True when the score is the first thing the order looks at, so a lower score than the worst kept cannot get in. */
		private final boolean scoreFirst;

		TopN(int limit, Comparator<Scored> order, boolean scoreFirst)
		{
			this.limit = limit;
			this.order = order;
			this.scoreFirst = scoreFirst;
			this.heap = new PriorityQueue<>(Math.min(limit, 256) + 1, order.reversed());
		}

		boolean mayEnter(int score)
		{
			return !scoreFirst || heap.size() < limit || score >= heap.peek().score;
		}

		void offer(Scored candidate)
		{
			if (heap.size() < limit)
			{
				heap.add(candidate);
			}
			else if (order.compare(candidate, heap.peek()) < 0)
			{
				heap.poll();
				heap.add(candidate);
			}
		}

		List<SearchEntry> result()
		{
			Scored[] best = heap.toArray(new Scored[0]);
			Arrays.sort(best, order);
			List<SearchEntry> result = new ArrayList<>(best.length);
			for (Scored scored : best)
			{
				result.add(scored.entry);
			}
			return result;
		}
	}

	/**
	 * Searches with filters. Plain words rank the results as they always have, the filters narrow them,
	 * and when there is nothing to rank by, or the results are asked to be near the player, the nearest
	 * come first.
	 */
	public List<SearchEntry> search(String text, int limit, SearchContext context)
	{
		SearchQuery query = SearchQuery.parse(text);
		if (!query.hasFilters())
		{
			return search(text, limit);
		}
		if (query.hasBadFilter() || query.isEmpty() || limit <= 0)
		{
			return Collections.emptyList();
		}
		String joined = String.join(" ", query.getWords());
		String[] words = query.getWords().toArray(new String[0]);
		boolean byDistance = query.getMaxDistance() >= 0 || words.length == 0;

		Comparator<Scored> order = orderFor(query, byDistance, context)
			.thenComparingInt(s -> s.entry.getName().length())
			.thenComparing(s -> s.entry.getName())
			.thenComparingInt(s -> s.seq);
		boolean ranked = query.getSort() == SearchQuery.Sort.RELEVANCE && !(byDistance && context.isPlayerKnown());
		TopN top = new TopN(limit, order, ranked);
		int seq = 0;
		for (SearchEntry entry : entries)
		{
			seq++;
			if (entry.isLocation() && !query.asksForPlaces() && !entry.isAreaName())
			{
				continue;
			}
			if (!passes(entry, query, context))
			{
				continue;
			}
			int score = words.length == 0 ? entry.getKind().weight() : score(entry, joined, words);
			if (score > 0 && top.mayEnter(score))
			{
				top.offer(new Scored(entry, score, ownWords(entry.normalizedName(), words), seq));
			}
		}
		return top.result();
	}

	/** How a filtered search puts its results in order. */
	private static Comparator<Scored> orderFor(SearchQuery query, boolean byDistance, SearchContext context)
	{
		switch (query.getSort())
		{
			case DISTANCE:
				return Comparator.<Scored>comparingDouble(s -> distanceOrFar(context, s.entry))
					.thenComparing(s -> -s.score);
			case NAME:
				return Comparator.<Scored, String>comparing(s -> s.entry.getName().toLowerCase(Locale.ROOT));
			default:
				return byDistance && context.isPlayerKnown()
					? Comparator.<Scored>comparingDouble(s -> distanceOrFar(context, s.entry)).thenComparing(s -> -s.score)
					: Comparator.<Scored>comparingInt(s -> -s.score).thenComparingInt(s -> s.own);
		}
	}

	private static double distanceOrFar(SearchContext context, SearchEntry entry)
	{
		double d = context.distanceTo(entry);
		return d < 0 ? Double.MAX_VALUE : d;
	}

	/**
	 * True when a spot is where the query's filters of a place allow: inside every region (also {@code near:} a name) and map it names,
	 * and in none that it leaves out. Partners' results are judged by it too.
	 */
	public static boolean inPlace(int mapId, double x, double y, SearchQuery query, Gazetteer places)
	{
		for (String region : query.getRegions())
		{
			if (!places.contains(region, mapId, x, y))
			{
				return false;
			}
		}
		for (String region : query.getExcludedRegions())
		{
			if (places.contains(region, mapId, x, y))
			{
				return false;
			}
		}
		for (String map : query.getExcludedMaps())
		{
			if (places.isMapNamed(map, mapId))
			{
				return false;
			}
		}
		for (String map : query.getMaps())
		{
			if (!places.isMapNamed(map, mapId))
			{
				return false;
			}
		}
		return true;
	}

	/** True when an entry satisfies every filter of the query. */
	static boolean passes(SearchEntry entry, SearchQuery query, SearchContext context)
	{
		for (String word : query.getNameWords())
		{
			if (!entry.normalizedName().contains(word))
			{
				return false;
			}
		}
		if (query.getMaxDistance() >= 0 && context.isPlayerKnown())
		{
			double d = context.distanceTo(entry);
			if (d < 0 || d > query.getMaxDistance())
			{
				return false;
			}
		}
		else if (query.getMaxDistance() >= 0)
		{
			return false;
		}
		if (!inPlace(entry.getMapId(), entry.getX(), entry.getY(), query, context.getGazetteer()))
		{
			return false;
		}
		for (String kind : query.getExcludedKinds())
		{
			if (hasKind(entry, kind))
			{
				return false;
			}
		}
		for (List<String> group : query.getKindGroups())
		{
			boolean any = false;
			for (String kind : group)
			{
				any |= hasKind(entry, kind);
			}
			if (!any)
			{
				return false;
			}
		}
		return true;
	}

	/** True when an entry is of a kind of result (the kinds of item are the Helper's, which gives the items). */
	static boolean hasKind(SearchEntry entry, String kind)
	{
		if (isPlaceWord(kind) && isPlaceLike(entry.getKind()))
		{
			return true;
		}
		String label = entry.getKind().label().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (label.equals(kind) || kindAlias(kind).equals(label))
		{
			return true;
		}
		return false;
	}

	private static boolean isPlaceWord(String kind)
	{
		return kind.equals("place") || kind.equals("places") || kind.equals("location") || kind.equals("locations");
	}

	/** The kinds that are somewhere to go: named places and entrances. */
	private static boolean isPlaceLike(SearchKind kind)
	{
		return kind == SearchKind.PLACE || kind == SearchKind.DOORWAY;
	}

	/** The result kind a plural or a near word stands for. */
	private static String kindAlias(String kind)
	{
		switch (kind)
		{
			case "transports":
				return "transport";
			case "teleports":
				return "teleport";
			case "doorways":
			case "entrance":
			case "entrances":
			case "dungeon":
				return "entrance";
			case "points":
			case "poi":
				return "point";
			case "places":
			case "map":
				return "place";
			case "pins":
				return "pin";
			case "routes":
				return "route";
			case "tiles":
				return "tile";
			default:
				return kind;
		}
	}

	static int score(SearchEntry entry, String query, String[] words)
	{
		String name = entry.normalizedName();
		String[] aliases = entry.normalizedAliases();
		String detail = entry.normalizedDetail();
		int total = 0;
		for (String word : words)
		{
			int level = match(name, word);
			for (String alias : aliases)
			{
				level = Math.max(level, match(alias, word));
			}
			int best = NAME_POINTS[level];
			if (best == 0)
			{
				best = DETAIL_POINTS[match(detail, word)];
			}
			if (best == 0)
			{
				return 0;
			}
			total += best;
		}
		int score = total / words.length;
		boolean exact = name.equals(query) || Arrays.asList(aliases).contains(query) || sameWords(name, words);
		if (exact)
		{
			score += EXACT_NAME;
		}
		else if (name.startsWith(query) || startsAny(aliases, query))
		{
			score += NAME_STARTS_WITH_QUERY;
		}
		return score + entry.getKind().weight(exact);
	}

	private static boolean startsAny(String[] texts, String query)
	{
		for (String text : texts)
		{
			if (text.startsWith(query))
			{
				return true;
			}
		}
		return false;
	}

	/** True when the words typed are the words of the name, each once, in any order. Allocates nothing. */
	static boolean sameWords(String name, String[] words)
	{
		if (words.length < 2)
		{
			return false;
		}
		int count = 0;
		int start = 0;
		int length = name.length();
		while (start < length)
		{
			int end = name.indexOf(' ', start);
			if (end < 0)
			{
				end = length;
			}
			boolean found = false;
			for (String word : words)
			{
				if (word.length() == end - start && name.regionMatches(start, word, 0, word.length()))
				{
					found = true;
					break;
				}
			}
			if (!found)
			{
				return false;
			}
			count++;
			start = end + 1;
		}
		return count == words.length;
	}

	/**
	 * How many words of the name no typed word starts, so "Catherby" comes before "Catherby Bay" for {@code catherby}. Allocates
	 * nothing.
	 */
	static int ownWords(String name, String[] words)
	{
		int own = 0;
		int start = 0;
		int length = name.length();
		while (start < length)
		{
			int end = name.indexOf(' ', start);
			if (end < 0)
			{
				end = length;
			}
			boolean fitted = false;
			for (String word : words)
			{
				if (word.length() <= end - start && name.regionMatches(start, word, 0, word.length()))
				{
					fitted = true;
					break;
				}
			}
			if (!fitted)
			{
				own++;
			}
			start = end + 1;
		}
		return own;
	}

	/** No match. */
	static final int NONE = 0;
	/** One letter off a word, or off the start of one, for a typed word of {@link #NEAR_MIN_LETTERS} letters or more. */
	static final int NEAR = 1;
	/** Inside a word, for a typed word of {@link #INSIDE_MIN_LETTERS} letters or more. */
	static final int INSIDE = 2;
	/** The start of a word. */
	static final int WORD_START = 3;
	/** A whole word. */
	static final int WHOLE = 4;

	/** A typed word this long or longer may be one letter off. */
	static final int NEAR_MIN_LETTERS = 5;

	/** A typed word this long or longer is looked for inside words too. */
	static final int INSIDE_MIN_LETTERS = 3;

	/**
	 * How well a typed word fits a normalised text (lower case words separated by single spaces): {@link #WHOLE}, {@link #WORD_START},
	 * {@link #INSIDE}, {@link #NEAR} or {@link #NONE}. Runs for every word of every entry at every key press, so it allocates nothing.
	 */
	static int match(String text, String word)
	{
		int n = text.length();
		int m = word.length();
		if (m == 0 || n == 0)
		{
			return NONE;
		}
		int best = NONE;
		int from = 0;
		while (from <= n - m)
		{
			int at = text.indexOf(word, from);
			if (at < 0)
			{
				break;
			}
			if (at == 0 || text.charAt(at - 1) == ' ')
			{
				if (at + m == n || text.charAt(at + m) == ' ')
				{
					return WHOLE;
				}
				best = WORD_START;
			}
			else if (best < INSIDE && m >= INSIDE_MIN_LETTERS)
			{
				best = INSIDE;
			}
			from = at + 1;
		}
		if (best != NONE || m < NEAR_MIN_LETTERS)
		{
			return best;
		}
		int start = 0;
		while (start < n)
		{
			int end = text.indexOf(' ', start);
			if (end < 0)
			{
				end = n;
			}
			if (nearWord(word, text, start, end))
			{
				return NEAR;
			}
			start = end + 1;
		}
		return NONE;
	}

	/** True when a typed word is one letter off a word of a text, or off the start of it. */
	private static boolean nearWord(String word, String text, int start, int end)
	{
		int m = word.length();
		int length = end - start;
		if (length < m - 1)
		{
			return false;
		}
		if (Math.abs(length - m) <= 1 && withinOneEdit(word, text, start, end))
		{
			return true;
		}
		for (int prefix = m - 1; prefix <= m + 1; prefix++)
		{
			if (prefix >= 1 && prefix < length && withinOneEdit(word, text, start, start + prefix))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True when a word and a stretch of a text differ by at most one letter put in, left out or changed, or two neighbours swapped.
	 * Allocates nothing.
	 */
	static boolean withinOneEdit(String a, String b, int bStart, int bEnd)
	{
		int la = a.length();
		int lb = bEnd - bStart;
		if (Math.abs(la - lb) > 1)
		{
			return false;
		}
		int i = 0;
		while (i < la && i < lb && a.charAt(i) == b.charAt(bStart + i))
		{
			i++;
		}
		if (i == la && i == lb)
		{
			return true;
		}
		int j = 0;
		while (j < la - i && j < lb - i && a.charAt(la - 1 - j) == b.charAt(bEnd - 1 - j))
		{
			j++;
		}
		if (la == lb)
		{
			int rest = la - i - j;
			return rest <= 1 || rest == 2 && a.charAt(i) == b.charAt(bStart + i + 1) && a.charAt(i + 1) == b.charAt(bStart + i);
		}
		return Math.min(la, lb) - i - j <= 0;
	}

	@RequiredArgsConstructor
	private static final class Scored
	{
		private final SearchEntry entry;
		private final int score;
		private final int own;
		/** Place in the index, the last tie break. */
		private final int seq;
	}
}
