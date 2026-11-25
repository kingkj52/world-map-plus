package com.worldmapplus.bridge;

import java.util.*;
import java.util.List;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.events.*;

/**
 * The plugin's end of the plugin messages: the router of the {@code worldmapplus} namespace (Plugin API v3, whose registry, questions
 * and pushed state are in {@link Partners}), the queue of what this plugin posts, what the view takes from partners (a focus, a point to
 * follow, the layers list, the game map state), and the jumps of the Shortest Path plugin (namespace {@code shortestpath}, name
 * {@code transports}: {@code origin} and {@code destination} lists of WorldPoint with {@code displayInfo} strings, sent only while its
 * "post transports" option is on). See docs/COMPAT.md.
 * <p>
 * Plugin API version 1 (markers, lines, hello, covers, focus) is answered by the World Map Plus Helper plugin and by nobody else.
 */
@Singleton
public class ExternalOverlays
{
	public static final String NAMESPACE = "worldmapplus";
	static final String SHORTEST_PATH = "shortestpath";

	static final int MAX_LINES = 60;
	static final int MAX_POINTS = 5000;
	static final int MAX_TEXT = Wire.MAX_TEXT;

	/** The version of the messages described in {@code docs/COMPAT.md}. */
	public static final int API_VERSION = 3;

	/** The longest name of a layer tick box, and the most tick boxes of each of the two lists. */
	static final int MAX_LAYER_TEXT = 40;
	static final int MAX_LAYERS = 40;

	/** The most messages kept waiting to be posted, and how long one waits before it is dropped, in milliseconds. */
	static final int MAX_WAITING = 32;
	static final long ANSWER_LIFETIME_MILLIS = 5_000;

	private final Partners partners = new Partners(this);

	public Partners partners()
	{
		return partners;
	}

	private final ArrayDeque<Reply> replies = new ArrayDeque<>();
	private List<RouteHop> hops = Collections.emptyList();
	/** True from asking for a route that starts at a chosen tile until the jumps for it have arrived. */
	private boolean holdingFirst;
	/** True once those jumps have arrived: later ones, for a route Shortest Path started again from the player, are not used. */
	private boolean held;
	private FocusRequest focus;
	private FollowRequest follow;
	private volatile Map<String, Integer> pluginLayers = Collections.emptyMap();
	private volatile Map<String, Integer> sentLayers = Collections.emptyMap();
	private volatile boolean needsGameMap;
	private volatile String lineUpTip = "";

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

	/** A request to start, move or stop following a point that moves, such as a party member. */
	@Getter
	@RequiredArgsConstructor
	public static final class FollowRequest
	{
		/** "start", "update", "gone" (the point is not there any more) or "stop". */
		private final String op;
		private final String name;
		@Nullable
		private final WorldPoint point;
	}

	/** A message to post on a client tick, in the plugin's own namespace. */
	@Getter
	@RequiredArgsConstructor
	public static final class Reply
	{
		private final String name;
		private final Map<String, Object> data;
		private final long at;
	}

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

	/** Looks up how the maps cover a tile. Set once the maps are loaded; until then coverage questions wait. */
	public interface Coverage
	{
		Cover at(int x, int y);
	}

	public void setCoverage(@Nullable Coverage coverage)
	{
		partners.coverage = coverage;
	}

	/**
	 * A kind of map as a word, so the plugin's own numbering never becomes part of what other plugins rely on: the surface, one of the
	 * game's own submaps, one of the wiki's extra maps, or an area drawn from the game's data (minigames, raids, bosses).
	 */
	public static String kindOf(int mapId)
	{
		return mapId == 0 ? "surface" : mapId < 10_000 ? "submap" : mapId < 11_000 ? "extra" : "area";
	}

	// ------------------------------------------------------------------
	// Messages in and out
	// ------------------------------------------------------------------

	public void onPluginMessage(PluginMessage message)
	{
		if (message == null || message.getName() == null)
		{
			return;
		}
		Map<String, Object> data = message.getData() == null ? Collections.emptyMap() : message.getData();
		if (NAMESPACE.equals(message.getNamespace()))
		{
			partners.onMessage(message.getName(), data);
		}
		else if (SHORTEST_PATH.equals(message.getNamespace()) && "transports".equals(message.getName()))
		{
			setHops(data);
		}
	}

	/** Queues a message in this namespace for the next client tick. */
	synchronized void say(String name, Map<String, Object> data)
	{
		if (replies.size() >= MAX_WAITING)
		{
			replies.removeFirst();
		}
		replies.addLast(new Reply(name, data, System.currentTimeMillis()));
	}

	/** The messages ready to post, any older than {@link #ANSWER_LIFETIME_MILLIS} dropped. Called once a client tick by the plugin. */
	public synchronized List<Reply> drainReplies(long now)
	{
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

	/** True when nothing waits to be posted, so a caller on every client tick can skip the rest without making anything. */
	public synchronized boolean idle()
	{
		return replies.isEmpty();
	}

	/** What this plugin supports, made afresh for every answer so no receiver can change what another one sees. */
	static Map<String, Object> capabilities()
	{
		return Wire.map("apiVersion", API_VERSION, "plugin", "World Map Plus", "role", "main", "requests", Partners.REQUESTS,
			"states", Partners.STATES, "pushes", Partners.PUSHES, "kinds", Partners.KINDS, "settingsGroup", Partners.SETTINGS_GROUP,
			"helper", Partners.HELPER_NAME, "limits", Collections.unmodifiableMap(Wire.map("text", MAX_TEXT, "idLength", Wire.MAX_ID,
				"menuText", Partners.MAX_MENU_TEXT, "coverPoints", Partners.MAX_COVER_POINTS, "perSecond", Partners.PER_SECOND,
				"answerMillis", (int) ANSWER_LIFETIME_MILLIS)));
	}

	/** Says once, when this plugin starts, that it is there, so a partner that started first registers again. */
	public void announceReady()
	{
		say("ready", capabilities());
	}

	/** Forgets everything. Used when the plugin shuts down. */
	public void clear()
	{
		partners.clear();
		synchronized (this)
		{
			hops = Collections.emptyList();
			focus = null;
			follow = null;
			replies.clear();
		}
		setLayers(Collections.emptyMap());
		setGameMap(Collections.emptyMap());
	}

	// ------------------------------------------------------------------
	// What the view takes from partners
	// ------------------------------------------------------------------

	/** Takes the pending focus request, if there is one. Each request is delivered once. */
	@Nullable
	public synchronized FocusRequest takeFocus()
	{
		FocusRequest request = focus;
		focus = null;
		return request;
	}

	/** Asks the map to centre on a place the next time it is drawn, for the side panel's Show button. */
	public synchronized void focusOn(WorldPoint point, String name)
	{
		if (point != null)
		{
			focus = new FocusRequest(point, 0, name == null ? "" : name, "", false);
		}
	}

	/** A {@code focus} request: point, and optionally zoom, name and exact. */
	synchronized void setFocus(String id, Map<String, Object> data)
	{
		WorldPoint point = Wire.point(data.get("point"));
		double zoom = Wire.number(data.get("zoom"));
		if (point != null)
		{
			focus = new FocusRequest(point, zoom > 0 ? zoom : 0, Wire.text(data.get("name")), id, Boolean.TRUE.equals(data.get("exact")));
		}
	}

	/** Answers a focus request that carried an id, once the map has acted. */
	public void answerFocus(@Nullable FocusRequest request, boolean ok, String mapName, String mapKind, String reason)
	{
		if (request != null)
		{
			partners.reply(request.getId(), "focus", ok ? "ok" : reason, Wire.map("mapName", mapName, "mapKind", mapKind));
		}
	}

	/** Takes the pending follow request, if there is one. A later request replaces one not yet taken, except that a start is kept. */
	@Nullable
	public synchronized FollowRequest takeFollow()
	{
		FollowRequest request = follow;
		follow = null;
		return request;
	}

	/** A {@code follow} request: op (start, update, gone or stop), name, and point for start and update. */
	synchronized void setFollow(Map<String, Object> data)
	{
		String name = Wire.text(data.get("name"));
		String op = Wire.label(data.get("op"), 8);
		WorldPoint point = Wire.point(data.get("point"));
		boolean moves = "start".equals(op) || "update".equals(op);
		if (name.isEmpty() || !(moves ? point != null : "gone".equals(op) || "stop".equals(op)))
		{
			return;
		}
		// A start not taken yet keeps its place and gets the newer point.
		follow = new FollowRequest(follow != null && "start".equals(follow.getOp()) && "update".equals(op) ? "start" : op, name, point);
	}

	/** Tells partners that following ended here (the player dragged the map, or something else took it). */
	public void followEnded(String name)
	{
		partners.push("follow_ended", Wire.map("name", name));
	}

	/** The plugins with points on the game's map and how many each, for the layers list. Never null. */
	public Map<String, Integer> pluginLayers()
	{
		return pluginLayers;
	}

	/** The layers other plugins named for what they sent, with how many each. Never null. */
	public Map<String, Integer> sentLayers()
	{
		return sentLayers;
	}

	/** True when the Helper found a plugin that needs the game's own map kept with this one. */
	public boolean needsGameMap()
	{
		return needsGameMap;
	}

	/** What the line up button says when a plugin draws on the game's map itself, or an empty text. */
	public String lineUpTip()
	{
		return lineUpTip;
	}

	/** The {@code layers} state: names with counts, cut to {@value #MAX_LAYERS} each. */
	void setLayers(Map<String, Object> data)
	{
		pluginLayers = layerMap(data.get("plugins"));
		sentLayers = layerMap(data.get("sent"));
	}

	/** The {@code game_map} state: needs and tip. */
	void setGameMap(Map<String, Object> data)
	{
		needsGameMap = Boolean.TRUE.equals(data.get("needs"));
		lineUpTip = Wire.text(data.get("tip"));
	}

	private static Map<String, Integer> layerMap(@Nullable Object value)
	{
		Map<String, Integer> result = new LinkedHashMap<>();
		for (Object item : value instanceof Map ? Wire.list(((Map<?, ?>) value).entrySet()) : new Object[0])
		{
			Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
			String name = Wire.label(entry.getKey(), MAX_LAYER_TEXT);
			if (!name.isEmpty() && entry.getValue() instanceof Number && result.size() < MAX_LAYERS)
			{
				result.put(name, Math.max(0, Math.min(100_000, ((Number) entry.getValue()).intValue())));
			}
		}
		return Collections.unmodifiableMap(result);
	}

	// ------------------------------------------------------------------
	// Shortest Path's jumps
	// ------------------------------------------------------------------

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
		List<double[]> origins = points(data.get("origin"));
		List<double[]> destinations = points(data.get("destination"));
		Object[] infos = Wire.list(data.get("displayInfo"));
		Object[] objects = Wire.list(data.get("objectInfo"));
		List<RouteHop> route = new ArrayList<>();
		for (int i = 0; i < Math.min(origins.size(), destinations.size()) && i < MAX_LINES; i++)
		{
			double[] from = origins.get(i);
			double[] to = destinations.get(i);
			route.add(new RouteHop(from[0], from[1], (int) from[2], to[0], to[1], (int) to[2],
				hopLabel(i < infos.length ? infos[i] : null, i < objects.length ? objects[i] : null)));
		}
		hops = Collections.unmodifiableList(route);
	}

	/**
	 * What to call a jump: the display text Shortest Path gave it, else the object or item it uses with the number that ends such text
	 * removed, else a plain word. Never empty, so a jump is always named.
	 */
	static String hopLabel(@Nullable Object display, @Nullable Object object)
	{
		String label = Wire.text(display);
		if (label.isEmpty())
		{
			label = Wire.text(object).replaceAll("\\s+\\d+$", "").trim();
		}
		return label.isEmpty() ? "Transport" : label;
	}

	/** The jumps of the route Shortest Path last sent, in the order they are taken. */
	public synchronized List<RouteHop> hops()
	{
		return hops;
	}

	/**
	 * Keeps the next jumps Shortest Path sends, and no later ones, until the route is cleared or another is asked for. For a route that
	 * starts at a tile chosen by hand: Shortest Path starts such a route again from the player when they walk away from it, which would
	 * replace the jumps with ones for a different route.
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
		releaseHops();
		hops = Collections.emptyList();
	}

	static List<double[]> points(@Nullable Object value)
	{
		List<double[]> result = new ArrayList<>();
		for (Object item : Wire.list(value))
		{
			if (item instanceof WorldPoint && result.size() < MAX_POINTS)
			{
				WorldPoint p = (WorldPoint) item;
				result.add(new double[] {p.getX() + 0.5, p.getY() + 0.5, p.getPlane()});
			}
		}
		return result;
	}
}
