package com.worldmapplus.bridge;

import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.events.*;

/**
 * What other plugins have asked the map to show, received as {@link PluginMessage} events.
 * <p>
 * Plugins cannot call each other directly, since each has its own class loader, and drawing inside
 * another plugin's own overlay is not possible from here. RuneLite's answer is the plugin message:
 * a namespaced event carrying a plain map. Everything crossing it is a JDK type, {@link WorldPoint}
 * (which lives in the shared API), {@link Color} or {@link BufferedImage}, so both sides agree on it.
 * <p>
 * The contract, namespace {@code worldmapplus}, is Plugin API version {@link #API_VERSION}, written down in {@code docs/COMPAT.md}:
 * <pre>
 *   marker        { id: String, point: WorldPoint, name: String?, tooltip: String?,
 *                   color: Color?, image: BufferedImage?, source: String?,
 *                   clickable: Boolean?, hint: String?, menu: List&lt;String&gt;?, layer: String? }
 *   marker_clear  { id: String? , source: String? }       neither given clears every marker
 *   line          { id: String, points: List&lt;WorldPoint&gt;, color: Color?, label: String?, source: String?, layer: String? }
 *   line_clear    { id: String? , source: String? }       neither given clears every line
 *   focus         { point: WorldPoint, zoom: Number?, name: String?, id: String?, exact: Boolean? }
 *   covers        { id: String, points: List&lt;WorldPoint&gt; | point: WorldPoint }
 *   hello         { id: String }
 * </pre>
 * Answers, posted by the plugin on a later client tick and only ever in this namespace: {@code hello_reply}, {@code ready},
 * {@code covers_reply}, {@code focus_result}, {@code marker_clicked} and {@code marker_menu}.
 * And, from the Shortest Path plugin, namespace {@code shortestpath}, name {@code transports}:
 * {@code origin} and {@code destination} lists of WorldPoint with {@code displayInfo} strings,
 * which are drawn as hops. Shortest Path only sends these when its "post transports" option is on.
 * <p>
 * Every value is untrusted. Types are checked, counts and sizes are capped, and anything that does
 * not fit is ignored rather than raising an error.
 */
@Singleton
public class ExternalOverlays
{
	public static final String NAMESPACE = "worldmapplus";
	static final String SHORTEST_PATH = "shortestpath";

	static final int MAX_MARKERS = 500;
	static final int MAX_LINES = 60;
	static final int MAX_POINTS = 5000;
	static final int MAX_TEXT = 120;
	static final int MAX_ICON = 64;

	/** The version of the messages described in {@code docs/COMPAT.md}. It goes up only when something is added. */
	public static final int API_VERSION = 1;

	/** The most entries one marker may add to this map's right click menu, and the longest each may be. */
	static final int MAX_MENU = 4;
	static final int MAX_MENU_TEXT = 40;

	/** The longest hint a card shows for a click. (A marker's "layer" is still taken and its limit still said, 40, but not used.) */
	static final int MAX_HINT = 60;

	/** The most {@code hello} questions taken in any one second, whoever asks. */
	static final int MAX_HELLOS_PER_SECOND = 4;

	/** The messages this plugin reads in its namespace, as a {@code hello_reply} lists them. */
	static final List<String> MESSAGES = Collections.unmodifiableList(Arrays.asList(
		"marker", "marker_clear", "line", "line_clear", "focus", "covers", "hello"));

	/** The messages this plugin posts in its namespace. */
	static final List<String> REPLIES = Collections.unmodifiableList(Arrays.asList(
		"hello_reply", "ready", "covers_reply", "focus_result", "marker_clicked", "marker_menu"));

	private static final Color DEFAULT_MARKER = new Color(255, 0, 255);
	private static final Color DEFAULT_LINE = new Color(0, 200, 255);
	private static final Color HOP = new Color(255, 120, 40);
	private static final String HOP_SOURCE = "shortestpath";

	private final Map<String, ExternalMarker> markers = new LinkedHashMap<>();
	private final Map<String, ExternalLine> lines = new LinkedHashMap<>();
	private List<RouteHop> hops = Collections.emptyList();
	/** True from asking for a route that starts at a chosen tile until the jumps for it have arrived. */
	private boolean holdingFirst;
	/** True once those jumps have arrived: later ones, for a route Shortest Path started again from the player, are not used. */
	private boolean held;
	private FocusRequest focus;
	private int version;

	/** A marker or a line, which a plugin names by its source and id. */
	interface Sent
	{
		String getSource();

		String getId();
	}

	/** A request to bring the map to a place. */
	@Getter
	@RequiredArgsConstructor
	public static final class FocusRequest
	{
		private final WorldPoint point;
		/** Pixels per tile, or 0 for no preference. */
		private final double zoom;
		private final String name;
		/** What the asker called this request, echoed in the answer, or an empty text when no answer is wanted. */
		private final String id;
		/** True when the map may only move if some map has imagery of exactly this tile. */
		private final boolean exact;
	}

	private void changed()
	{
		version++;
	}

	/** Increases whenever anything changes, so a drawing cache knows to rebuild. */
	public synchronized int getVersion()
	{
		return version;
	}

	public synchronized List<ExternalMarker> markers()
	{
		return Collections.unmodifiableList(new ArrayList<>(markers.values()));
	}

	public synchronized List<ExternalLine> lines()
	{
		return Collections.unmodifiableList(new ArrayList<>(lines.values()));
	}

	/** Takes the pending focus request, if there is one. Each request is delivered once. */
	@Nullable
	public synchronized FocusRequest takeFocus()
	{
		FocusRequest request = focus;
		focus = null;
		return request;
	}

	/** Forgets everything. Used when the plugin shuts down. */
	public synchronized void clear()
	{
		markers.clear();
		lines.clear();
		hops = Collections.emptyList();
		focus = null;
		askedCovers.clear();
		replies.clear();
		version++;
	}

	public void onPluginMessage(PluginMessage message)
	{
		if (message == null || message.getNamespace() == null || message.getName() == null)
		{
			return;
		}
		Map<String, Object> data = message.getData();
		if (data == null)
		{
			data = Collections.emptyMap();
		}

		if (NAMESPACE.equals(message.getNamespace()))
		{
			switch (message.getName())
			{
				case "marker":
					addMarker(data);
					break;
				case "marker_clear":
					clear(markers, data);
					break;
				case "line":
					addLine(data);
					break;
				case "line_clear":
					clear(lines, data);
					break;
				case "focus":
					setFocus(data);
					break;
				case "covers":
					askCovers(data);
					break;
				case "hello":
					hello(data);
					break;
				default:
					break;
			}
		}
		else if (SHORTEST_PATH.equals(message.getNamespace()) && "transports".equals(message.getName()))
		{
			setHops(data);
		}
	}

	// ------------------------------------------------------------------
	// Markers
	// ------------------------------------------------------------------

	private synchronized void addMarker(Map<String, Object> data)
	{
		String id = text(data.get("id"));
		WorldPoint point = point(data.get("point"));
		if (id.isEmpty() || point == null)
		{
			return;
		}
		String source = text(data.get("source"));
		String key = source + "/" + id;
		if (full(markers, key, MAX_MARKERS))
		{
			return;
		}
		Color color = data.get("color") instanceof Color ? (Color) data.get("color") : DEFAULT_MARKER;
		// A click or a menu entry is reported back with the source and the id, so both must be plain ids for it to be offered.
		boolean answerable = !requestId(source).isEmpty() && !requestId(id).isEmpty();
		boolean clickable = answerable && Boolean.TRUE.equals(data.get("clickable"));
		List<String> menu = answerable ? menu(data.get("menu")) : Collections.emptyList();
		markers.put(key, new ExternalMarker(source, id, point.getX() + 0.5, point.getY() + 0.5, text(data.get("name")),
			text(data.get("tooltip")), color, icon(data.get("image")), null, false, null, false, clickable,
			clickable ? label(data.get("hint"), MAX_HINT) : "", menu));
		changed();
	}

	private static boolean full(Map<String, ?> map, String key, int most)
	{
		return !map.containsKey(key) && map.size() >= most;
	}

	/** Takes away what a {@code marker_clear} or {@code line_clear} names: by id, by source, both, or everything when neither is given. */
	private synchronized void clear(Map<String, ? extends Sent> map, Map<String, Object> data)
	{
		String id = text(data.get("id"));
		String source = text(data.get("source"));
		if (map.values().removeIf(m -> (id.isEmpty() || m.getId().equals(id)) && (source.isEmpty() || m.getSource().equals(source))))
		{
			changed();
		}
	}

	// ------------------------------------------------------------------
	// Lines
	// ------------------------------------------------------------------

	private synchronized void addLine(Map<String, Object> data)
	{
		String id = text(data.get("id"));
		List<double[]> points = points(data.get("points"));
		if (id.isEmpty() || points.size() < 2)
		{
			return;
		}
		String source = text(data.get("source"));
		String key = source + "/" + id;
		if (full(lines, key, MAX_LINES))
		{
			return;
		}
		Color color = data.get("color") instanceof Color ? (Color) data.get("color") : DEFAULT_LINE;
		lines.put(key, new ExternalLine(source, id, points, color, text(data.get("label")), false));
		changed();
	}

	/** Replaces the hops from the last Shortest Path message with the ones in this one. */
	private synchronized void setHops(Map<String, Object> data)
	{
		if (held)
		{
			return;
		}
		if (holdingFirst)
		{
			holdingFirst = false;
			held = true;
		}
		lines.values().removeIf(ExternalLine::isHop);

		List<double[]> origins = points(data.get("origin"));
		List<double[]> destinations = points(data.get("destination"));
		Object info = data.get("displayInfo");
		List<?> infos = info instanceof List ? (List<?>) info : Collections.emptyList();
		Object objectInfo = data.get("objectInfo");
		List<?> objects = objectInfo instanceof List ? (List<?>) objectInfo : Collections.emptyList();

		List<RouteHop> route = new ArrayList<>();
		int count = Math.min(origins.size(), destinations.size());
		for (int i = 0; i < count && i < MAX_LINES; i++)
		{
			List<double[]> pair = new ArrayList<>(2);
			pair.add(origins.get(i));
			pair.add(destinations.get(i));
			String label = hopLabel(i < infos.size() ? infos.get(i) : null, i < objects.size() ? objects.get(i) : null);
			lines.put(HOP_SOURCE + "/hop" + i, new ExternalLine(HOP_SOURCE, "hop" + i, pair, HOP, label, true));
			route.add(new RouteHop(origins.get(i)[0], origins.get(i)[1], (int) origins.get(i)[2], destinations.get(i)[0],
				destinations.get(i)[1], (int) destinations.get(i)[2], label));
		}
		hops = Collections.unmodifiableList(route);
		version++;
	}

	/**
	 * What to call a jump: the display text Shortest Path gave it, else the object or item it uses with
	 * the number that ends such text removed, else a plain word. Never empty, so a jump is always named.
	 */
	static String hopLabel(@Nullable Object display, @Nullable Object object)
	{
		String label = text(display);
		if (label.isEmpty())
		{
			label = text(object).replaceAll("\\s+\\d+$", "").trim();
		}
		return label.isEmpty() ? "Transport" : label;
	}

	/** The jumps of the route Shortest Path last sent, in the order they are taken. */
	public synchronized List<RouteHop> hops()
	{
		return hops;
	}

	/**
	 * Keeps the next jumps Shortest Path sends, and no later ones, until the route is cleared or another is asked for.
	 * For a route that starts at a tile chosen by hand: Shortest Path starts such a route again from the player when
	 * they walk away from it, which would replace the jumps with ones for a different route.
	 */
	public synchronized void holdFirstHops()
	{
		holdingFirst = true;
		held = false;
	}

	/** Follows every set of jumps Shortest Path sends again, for a route that starts where the player is. */
	public synchronized void releaseHops()
	{
		holdingFirst = false;
		held = false;
	}

	/** Removes the hops, for when the route they belonged to is cleared. */
	public synchronized void clearHops()
	{
		holdingFirst = false;
		held = false;
		hops = Collections.emptyList();
		if (lines.values().removeIf(ExternalLine::isHop))
		{
			changed();
		}
	}

	// ------------------------------------------------------------------
	// Focus
	// ------------------------------------------------------------------

	/** The source of the markers and the line that show a share someone sent. No plugin message makes these. */
	static final String SHARE_SOURCE = "World Map Plus share";

	private static final Color SHARE_COLOR = new Color(255, 170, 40);

	/**
	 * Shows where a shared pin, route or set of places is: a marker on each point and, for a route, a line through them in
	 * order. It stays until another share is shown or {@link #clearShare} is called, and nothing is saved.
	 */
	public synchronized void showShare(List<WorldPoint> points, boolean route, String title)
	{
		markers.values().removeIf(m -> SHARE_SOURCE.equals(m.getSource()));
		lines.values().removeIf(l -> SHARE_SOURCE.equals(l.getSource()));
		List<double[]> path = new ArrayList<>();
		for (int i = 0; i < points.size() && i < MAX_POINTS; i++)
		{
			WorldPoint p = points.get(i);
			String name = route ? "Stop " + (i + 1) + " of " + points.size() : points.size() == 1 ? "Shared pin" : "Shared place " + (i + 1);
			markers.put(SHARE_SOURCE + "/" + i, new ExternalMarker(SHARE_SOURCE, "share" + i, p.getX() + 0.5, p.getY() + 0.5, name,
				title + ". Not saved: Save it from the side panel.", SHARE_COLOR, null, null, false, null, false, false, "",
				Collections.emptyList()));
			path.add(new double[] {p.getX() + 0.5, p.getY() + 0.5, p.getPlane()});
		}
		if (route && path.size() >= 2)
		{
			lines.put(SHARE_SOURCE + "/order", new ExternalLine(SHARE_SOURCE, "order", path, SHARE_COLOR, title, false));
		}
		changed();
	}

	/** Takes the markers and the line of {@link #showShare} away. */
	public synchronized void clearShare()
	{
		boolean removed = markers.values().removeIf(m -> SHARE_SOURCE.equals(m.getSource()));
		removed |= lines.values().removeIf(l -> SHARE_SOURCE.equals(l.getSource()));
		if (removed)
		{
			changed();
		}
	}

	/** Asks the map to centre on a place the next time it is drawn, for the side panel's Show button. */
	public synchronized void focusOn(WorldPoint point, String name)
	{
		if (point != null)
		{
			focus = new FocusRequest(point, 0, name == null ? "" : name, "", false);
		}
	}

	private synchronized void setFocus(Map<String, Object> data)
	{
		WorldPoint point = point(data.get("point"));
		if (point == null)
		{
			return;
		}
		Object zoom = data.get("zoom");
		double ppt = zoom instanceof Number ? ((Number) zoom).doubleValue() : 0;
		if (Double.isNaN(ppt) || ppt < 0)
		{
			ppt = 0;
		}
		focus = new FocusRequest(point, ppt, text(data.get("name")), requestId(data.get("id")), Boolean.TRUE.equals(data.get("exact")));
	}

	// ------------------------------------------------------------------
	// Questions other plugins ask, and the answers
	// ------------------------------------------------------------------

	/** The most tiles one "covers" question may ask about. */
	static final int MAX_COVER_POINTS = 16;

	/** The most questions and answers kept waiting. */
	static final int MAX_WAITING = 32;

	/** How long an answer waits to be sent before it is dropped, in milliseconds. */
	static final long ANSWER_LIFETIME_MILLIS = 5_000;

	/** At most this many questions are taken in any one second, whoever asks. */
	static final int MAX_ASKS_PER_SECOND = 8;

	static final int MAX_ID_LENGTH = 64;

	/** How the maps cover one tile. */
	@Getter
	@RequiredArgsConstructor
	public static final class Cover
	{
		private final boolean covered;
		private final boolean exact;
		private final String mapName;
		private final String mapKind;
	}

	/** Looks up how the maps cover a tile. Set once the maps are loaded; until then questions wait. */
	public interface Coverage
	{
		Cover at(int x, int y);
	}

	/** A message to post once a tick, in the plugin's own namespace. */
	@Getter
	@RequiredArgsConstructor
	public static final class Reply
	{
		private final String name;
		private final Map<String, Object> data;
		private final long at;
	}

	@RequiredArgsConstructor
	private static final class Ask
	{
		private final String id;
		private final List<WorldPoint> points;
		private final long at;
	}

	private final ArrayDeque<Ask> askedCovers = new ArrayDeque<>();
	private final ArrayDeque<Reply> replies = new ArrayDeque<>();
	private final Rate asks = new Rate(MAX_ASKS_PER_SECOND);
	private final Rate hellos = new Rate(MAX_HELLOS_PER_SECOND);
	private Coverage coverage;

	/** At most so many of something in any one second, whoever asks. */
	private static final class Rate
	{
		private final long[] times;
		private int next;

		Rate(int most)
		{
			times = new long[most];
		}

		/** True when another may be taken now. Takes the slot when it may. */
		boolean allow(long now)
		{
			if (times[next] != 0 && now - times[next] < 1_000)
			{
				return false;
			}
			times[next] = now;
			next = (next + 1) % times.length;
			return true;
		}
	}

	public synchronized void setCoverage(@Nullable Coverage coverage)
	{
		this.coverage = coverage;
	}

	/**
	 * A kind of map as a word, so the plugin's own numbering never becomes part of what other plugins rely on: the surface, one of the
	 * game's own submaps, one of the wiki's extra maps, or an area drawn from the game's data (minigames, raids, bosses).
	 */
	public static String kindOf(int mapId)
	{
		if (mapId == 0)
		{
			return "surface";
		}
		if (mapId < 10_000)
		{
			return "submap";
		}
		return mapId < 11_000 ? "extra" : "area";
	}

	/** An id the asker made up, kept only when it is short and plain; otherwise an empty text, and no answer is made. */
	static String requestId(@Nullable Object value)
	{
		if (!(value instanceof String))
		{
			return "";
		}
		String id = (String) value;
		if (id.isEmpty() || id.length() > MAX_ID_LENGTH)
		{
			return "";
		}
		for (int i = 0; i < id.length(); i++)
		{
			char c = id.charAt(i);
			if (!(Character.isLetterOrDigit(c) && c < 128) && c != '_' && c != '-' && c != '.' && c != ':')
			{
				return "";
			}
		}
		return id;
	}


	/** Records a "covers" question. It is answered on a later tick, never from here. */
	private synchronized void askCovers(Map<String, Object> data)
	{
		String id = requestId(data.get("id"));
		if (id.isEmpty())
		{
			return;
		}
		List<WorldPoint> points = new ArrayList<>();
		Object many = data.get("points");
		if (many instanceof List)
		{
			for (Object item : items(many))
			{
				if (points.size() >= MAX_COVER_POINTS)
				{
					break;
				}
				WorldPoint point = point(item);
				if (point == null)
				{
					return;
				}
				points.add(point);
			}
		}
		else
		{
			WorldPoint point = point(data.get("point"));
			if (point != null)
			{
				points.add(point);
			}
		}
		long now = System.currentTimeMillis();
		if (points.isEmpty() || !asks.allow(now))
		{
			return;
		}
		if (askedCovers.size() >= MAX_WAITING)
		{
			askedCovers.removeFirst();
		}
		askedCovers.addLast(new Ask(id, points, now));
	}

	/** Queues the answer to a focus request that carried an id. Does nothing when it did not. */
	public synchronized void answerFocus(@Nullable FocusRequest request, boolean ok, String mapName, String mapKind, String reason)
	{
		if (request == null || request.getId().isEmpty())
		{
			return;
		}
		Map<String, Object> data = new HashMap<>();
		data.put("id", request.getId());
		data.put("ok", ok);
		data.put("mapName", mapName);
		data.put("mapKind", mapKind);
		data.put("reason", reason);
		queueReply("focus_result", data, System.currentTimeMillis());
	}

	private void queueReply(String name, Map<String, Object> data, long now)
	{
		if (replies.size() >= MAX_WAITING)
		{
			replies.removeFirst();
		}
		replies.addLast(new Reply(name, data, now));
	}

	/**
	 * The answers ready to post: one for every question asked since the last call that the maps can answer, and the results of focus
	 * requests, with any older than {@link #ANSWER_LIFETIME_MILLIS} dropped. Called once a game tick by the plugin, which posts them.
	 */
	public synchronized List<Reply> drainReplies(long now)
	{
		if (coverage != null)
		{
			while (!askedCovers.isEmpty())
			{
				Ask ask = askedCovers.removeFirst();
				if (now - ask.at > ANSWER_LIFETIME_MILLIS)
				{
					continue;
				}
				List<Boolean> covered = new ArrayList<>();
				List<Boolean> exact = new ArrayList<>();
				List<String> names = new ArrayList<>();
				List<String> kinds = new ArrayList<>();
				for (WorldPoint point : ask.points)
				{
					Cover cover = coverage.at(point.getX(), point.getY());
					covered.add(cover.isCovered());
					exact.add(cover.isExact());
					names.add(cover.getMapName());
					kinds.add(cover.getMapKind());
				}
				Map<String, Object> data = new HashMap<>();
				data.put("id", ask.id);
				data.put("covered", covered);
				data.put("exact", exact);
				data.put("mapNames", names);
				data.put("mapKinds", kinds);
				queueReply("covers_reply", data, now);
			}
		}
		else
		{
			askedCovers.removeIf(ask -> now - ask.at > ANSWER_LIFETIME_MILLIS);
		}
		List<Reply> ready = new ArrayList<>();
		for (Reply reply : replies)
		{
			if (now - reply.at <= ANSWER_LIFETIME_MILLIS)
			{
				ready.add(reply);
			}
		}
		replies.clear();
		return ready;
	}

	// ------------------------------------------------------------------
	// The handshake, and what is said back about a plugin's own markers
	// ------------------------------------------------------------------


	/** What this plugin supports, made afresh for every answer so no receiver can change what another one sees. */
	static Map<String, Object> capabilities()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("apiVersion", API_VERSION);
		data.put("plugin", "World Map Plus");
		data.put("messages", MESSAGES);
		data.put("replies", REPLIES);
		Object[] pairs = {"markers", MAX_MARKERS, "lines", MAX_LINES, "linePoints", MAX_POINTS, "text", MAX_TEXT, "image", MAX_ICON,
			"idLength", MAX_ID_LENGTH, "menuEntries", MAX_MENU, "menuText", MAX_MENU_TEXT, "layerText", 40, "hintText", MAX_HINT,
			"coverPoints", MAX_COVER_POINTS, "coversPerSecond", MAX_ASKS_PER_SECOND, "hellosPerSecond", MAX_HELLOS_PER_SECOND,
			"answerMillis", (int) ANSWER_LIFETIME_MILLIS};
		Map<String, Integer> limits = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			limits.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		data.put("limits", Collections.unmodifiableMap(limits));
		return data;
	}

	/** Records a {@code hello}. The answer, with the asker's id, goes out on a later tick. */
	private synchronized void hello(Map<String, Object> data)
	{
		String id = requestId(data.get("id"));
		if (id.isEmpty())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (!hellos.allow(now))
		{
			return;
		}
		Map<String, Object> answer = capabilities();
		answer.put("id", id);
		queueReply("hello_reply", answer, now);
	}

	/**
	 * Says once, when this plugin starts, that it is there and what it supports, so a plugin that started first, or that sent markers
	 * before a restart of this one (which forgets them), knows to send them again.
	 */
	public synchronized void announceReady()
	{
		queueReply("ready", capabilities(), System.currentTimeMillis());
	}

	/** True when the marker is still the one its plugin last sent under its source and id. */
	private boolean current(@Nullable ExternalMarker marker)
	{
		return marker != null && markers.get(marker.getSource() + "/" + marker.getId()) == marker;
	}

	/** Tells the plugin that sent a marker that the player clicked it. Nothing for a marker that did not ask, or is gone. */
	public synchronized boolean markerClicked(@Nullable ExternalMarker marker)
	{
		return current(marker) && marker.isClickable() && said("marker_clicked", marker, null);
	}

	/** Tells the plugin that sent a marker that the player chose one of the menu entries it added. */
	public synchronized boolean markerMenu(@Nullable ExternalMarker marker, String option)
	{
		return current(marker) && marker.getMenu().contains(option) && said("marker_menu", marker, option);
	}

	private boolean said(String name, ExternalMarker marker, @Nullable String option)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("source", marker.getSource());
		data.put("id", marker.getId());
		if (option != null)
		{
			data.put("option", option);
		}
		queueReply(name, data, System.currentTimeMillis());
		return true;
	}

	/** True when nothing waits to be posted, so a caller on every client tick can skip the rest without making anything. */
	public synchronized boolean idle()
	{
		return replies.isEmpty() && askedCovers.isEmpty();
	}

	// ------------------------------------------------------------------
	// Untrusted value readers
	// ------------------------------------------------------------------

	/**
	 * A short text for a menu entry, a layer or a hint: no control or format characters, runs of white space made one space, the bar
	 * that separates names in a setting taken out, and cut to {@code max}. Anything but a String gives an empty text.
	 */
	static String label(@Nullable Object value, int max)
	{
		if (!(value instanceof String))
		{
			return "";
		}
		String raw = (String) value;
		StringBuilder out = new StringBuilder(Math.min(raw.length(), max));
		boolean space = false;
		for (int i = 0; i < raw.length() && out.length() < max; i++)
		{
			char c = raw.charAt(i);
			if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '|' || Character.getType(c) == Character.FORMAT)
			{
				space = out.length() > 0;
				continue;
			}
			if (space)
			{
				if (out.length() >= max - 1)
				{
					break;
				}
				out.append(' ');
			}
			space = false;
			out.append(c);
		}
		return out.toString();
	}


	/** The entries a marker adds to the right click menu: up to {@link #MAX_MENU} distinct short texts, anything else left out. */
	static List<String> menu(@Nullable Object value)
	{
		List<String> result = new ArrayList<>();
		for (Object item : items(value))
		{
			if (result.size() >= MAX_MENU)
			{
				break;
			}
			String entry = label(item, MAX_MENU_TEXT);
			if (!entry.isEmpty() && !result.contains(entry))
			{
				result.add(entry);
			}
		}
		return result;
	}

	static String text(@Nullable Object value)
	{
		if (!(value instanceof String))
		{
			return "";
		}
		String text = ((String) value).trim();
		return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
	}

	/**
	 * The items of a list another plugin sent, copied first because the list is the sender's and may change while it is read. Nothing
	 * for anything but a list, or a list caught mid change.
	 */
	static Object[] items(@Nullable Object value)
	{
		try
		{
			return value instanceof List ? ((List<?>) value).toArray() : new Object[0];
		}
		catch (RuntimeException e)
		{
			return new Object[0];
		}
	}

	@Nullable
	static WorldPoint point(@Nullable Object value)
	{
		return value instanceof WorldPoint ? (WorldPoint) value : null;
	}

	static List<double[]> points(@Nullable Object value)
	{
		List<double[]> result = new ArrayList<>();
		for (Object item : items(value))
		{
			if (result.size() >= MAX_POINTS)
			{
				break;
			}
			if (item instanceof WorldPoint)
			{
				WorldPoint p = (WorldPoint) item;
				result.add(new double[] {p.getX() + 0.5, p.getY() + 0.5, p.getPlane()});
			}
		}
		return result;
	}

	@Nullable
	static BufferedImage icon(@Nullable Object value)
	{
		if (!(value instanceof BufferedImage))
		{
			return null;
		}
		BufferedImage image = (BufferedImage) value;
		if (image.getWidth() <= 0 || image.getHeight() <= 0 || image.getWidth() > MAX_ICON
			|| image.getHeight() > MAX_ICON)
		{
			return null;
		}
		// A copy, so the sender changing its own image later cannot change what is drawn here.
		BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = copy.createGraphics();
		try
		{
			g.drawImage(image, 0, 0, null);
		}
		finally
		{
			g.dispose();
		}
		return copy;
	}
}
