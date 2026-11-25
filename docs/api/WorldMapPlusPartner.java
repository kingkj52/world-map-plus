package com.example.yourplugin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JComponent;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Talks to the World Map Plus plugin through RuneLite plugin messages, Plugin API version 3 (see its docs/COMPAT.md, "Plugin API v3").
 * It sits next to {@code WorldMapPlusApi} (version 1: markers, lines, focus), which it does not replace.
 * <p>
 * Copy this one file into your plugin and change the package line. It needs nothing from World Map Plus: plugins on the Hub cannot
 * depend on each other, and these are plain messages. If World Map Plus is not installed nobody receives them.
 * <ul>
 * <li>Say {@link #hello} on start up and again on every {@code ready}; answer every {@code ping} with {@link #hello}; say
 * {@link #goodbye} on shut down. A plugin that stays silent for about 95 seconds is dropped, with everything it gave.</li>
 * <li>Give World Map Plus parts with {@link #register} or the helpers below. The same owner, kind and id replaces; a contribution that
 * throws three times in a row is switched off until it is registered again (a pushed state of topic {@code off} says so).</li>
 * <li>Ask with {@link #request}: the answer is a {@code reply} with your id ({@link #isAnswer}). Push what you know with {@link #state},
 * once and again only when it changed, with a number that goes up. World Map Plus pushes its own state the same way ({@link #isState}).</li>
 * <li>Callbacks must be quick. Painters, hit tests and cards run on the thread that draws the map (the client thread, or the interface
 * thread for the map windows) and are skipped for a while when they take more than a few milliseconds.</li>
 * </ul>
 */
public final class WorldMapPlusPartner
{
	public static final String NAMESPACE = "worldmapplus";

	public static final int API_VERSION = 3;

	/** The settings group of World Map Plus. A partner reads its settings with {@link #setting}; it has none of its own. */
	public static final String SETTINGS_GROUP = "worldmapplus";

	private WorldMapPlusPartner()
	{
	}

	/** Says who this plugin is. Role is "part two" for the second World Map Plus plugin, "other" for everyone else. */
	public static void hello(EventBus bus, String owner, String role)
	{
		send(bus, "partner_hello", data(owner, "role", role, "apiVersion", API_VERSION));
	}

	/** Takes back everything this owner registered and pushed. */
	public static void goodbye(EventBus bus, String owner)
	{
		send(bus, "partner_goodbye", data(owner));
	}

	/**
	 * Registers one contribution. Kinds: "section", "action", "painter", "hit", "card", "button", "menu", "search". The extra keys of the
	 * kind go in {@code more} as key, value, key, value: tab and title (section), layer and input (painter), click (hit, button), owns
	 * (card), label and when (menu), select and filtered (search).
	 */
	public static void register(EventBus bus, String owner, String kind, String id, int order, Object call, Object... more)
	{
		send(bus, "register", with(data(owner, "kind", kind, "id", id, "order", order, "call", call), more));
	}

	/** Removes one contribution, or every contribution of a kind when the id is null, or everything when both are null. */
	public static void unregister(EventBus bus, String owner, String kind, String id)
	{
		send(bus, "unregister", data(owner, "kind", kind, "id", id));
	}

	/**
	 * A component in the side panel's Places or Routes tab, or in a tab of its own when the tab has another name. The tab "top" puts it
	 * above the tabs, under the panel's title and status line, with no header of the panel's: the partner draws its own.
	 */
	public static void section(EventBus bus, String owner, String id, int order, String tab, String title, JComponent component)
	{
		register(bus, owner, "section", id, order, component, "tab", tab, "title", title);
	}

	/** Handles the side panel button bound to this action id. The payload has "pins", "routes" and "order". */
	public static void action(EventBus bus, String owner, String actionId, Consumer<Map<String, Object>> handler)
	{
		register(bus, owner, "action", actionId, 100, handler);
	}

	/**
	 * Draws on the map: layer "over" (after the other plugins' markers) or "under" (right after the ground pictures, before the icons,
	 * labels, routes and pins) or "route" (over the icons and labels, under the route's numbered discs, the saved routes and the pins). The frame map has "transform" (an AffineTransform from world tiles, x east and y north, to pixels),
	 * "bounds", "mapId", "floor", "zoom" (pixels per tile) and "onMap". It is only valid during the call, and the graphics are a copy.
	 */
	public static void painter(EventBus bus, String owner, String id, int order, String layer, BiConsumer<Graphics2D, Map<String, Object>> painter)
	{
		register(bus, owner, "painter", id, order, painter, "layer", layer);
	}

	/**
	 * A panel in screen space over the map (a painter of layer "screen"), drawn and answered by the partner. The frame map has "map"
	 * (int[4]: x, y, width, height of the area for controls), "limits" (int[2]: the highest the panel may start, the furthest right it
	 * may reach), "mouse" (int[2], -1 off the map), "player" (double[3]: map id or -1, x, y), "now" (long[1]), "fades" (boolean[1]),
	 * "surface" (each map window has its own) and "box" (int[5]: write the panel's x, y, width and height here, width 0 for nothing, and
	 * 1 in the fifth place for another frame soon). The arrays change in place. {@code input} gets "type" ("click", "press", "drag",
	 * "release", "wheel", "close"), "x", "y", "amount" and "surface", and answers true when it took the event.
	 */
	public static void panel(EventBus bus, String owner, String id, int order, BiConsumer<Graphics2D, Map<String, Object>> painter,
		Function<Map<String, Object>, Boolean> input)
	{
		register(bus, owner, "painter", id, order, painter, "layer", "screen", "input", input);
	}

	/**
	 * Says what is under the pointer. The frame map has "x", "y" (pixels), "worldX", "worldY" and "mapId", "floor", "zoom". Return null,
	 * or a map with "name", "kind", "id", "detail", "lines" (up to 8), "point" (a WorldPoint), "clickable" and "hint", and for a whole card "rows" (a List of two-element Lists, label and value) and "wiki" (what the card is for).
	 */
	public static void hit(EventBus bus, String owner, String id, int order, Function<Map<String, Object>, Map<String, Object>> test,
		Consumer<Map<String, Object>> click)
	{
		register(bus, owner, "hit", id, order, test, "click", click);
	}

	/** Adds lines to the hover card of anything: the function gets a map with "kind", "name", "id" and "tile". */
	public static void card(EventBus bus, String owner, String id, int order, Function<Map<String, Object>, List<String>> lines)
	{
		register(bus, owner, "card", id, order, lines);
	}

	/**
	 * Gives the whole card body for the things of the kinds this plugin owns (such as "farming"). The function gets the thing's map
	 * (see {@link #card}) with "body" true and returns null (not mine) or a map with "title", "subtitle", "rows" (a List of two-element
	 * Lists: label, value; up to 24) and "hint"; World Map Plus draws it in its own card style.
	 */
	public static void cardBody(EventBus bus, String owner, String id, int order, Function<Map<String, Object>, Map<String, Object>> body,
		List<String> owns)
	{
		register(bus, owner, "card", id, order, body, "owns", owns);
	}

	/**
	 * Puts a button on hover cards. {@code offer} is asked when a card is made, with the card's "title" and, when it has them, "what" (the
	 * sort of thing it is for), "name", "detail" and "wikiSearch"; it answers the button's text and the text of the matching right click menu entry, or null for no button. {@code press}
	 * gets the same map when the button or the entry is chosen and may answer a sentence for the map to show.
	 */
	public static void button(EventBus bus, String owner, String id, int order, Function<Map<String, Object>, List<String>> offer,
		Function<Map<String, Object>, String> press)
	{
		register(bus, owner, "button", id, order, offer, "click", press);
	}

	/**
	 * Adds entries to the map's right click menu. {@code entries} gets the context ("tile", "mapId", "floor" and, over something,
	 * "thing" and "things") and returns the texts of the entries for that place (up to 8 in all), or null. Choosing one sends the
	 * context, with the text as "option", to {@code handler}. The label is a name for the contribution only.
	 */
	public static void menu(EventBus bus, String owner, String id, int order, String label, Consumer<Map<String, Object>> handler,
		Function<Map<String, Object>, List<String>> entries)
	{
		register(bus, owner, "menu", id, order, handler, "label", label, "when", entries);
	}

	/** Gives search results: maps with "name", "point" (a WorldPoint) and optionally "detail", "mapId", "id", "kind" (KIND, MONSTER, NPC, ITEM, MUSIC, QUEST or SHOP), "marks" (List of double[] {x, y}), "aliases" (List of String) and "reveal" (a category of the game's icons). Runs on the shared executor.
	 * {@code filtered} (or null) is asked instead of {@code provider} for a text with the filters of a place (region:, map:, near: a name): it
	 * gets "text" and "place", a {@code Predicate} of a double[3] {map id, x, y} that is true when the spot is in the place the text names;
	 * apply it before ranking and cutting the results. */
	public static void search(EventBus bus, String owner, String id, int order, Function<String, List<Map<String, Object>>> provider,
		Consumer<Map<String, Object>> select, Function<Map<String, Object>, List<Map<String, Object>>> filtered)
	{
		register(bus, owner, "search", id, order, provider, "select", select, "filtered", filtered);
	}

	/**
	 * Asks World Map Plus something. Topics and their keys are in docs/COMPAT.md: "saved" (what), "save_pins" (points, maps, names, kind),
	 * "save_route" (points, maps, name, loop), "route_order" (route, expect, stops), "go" (point), "places" (kind, name, names,
	 * detailStart, mapId, x, y), "coverage" (points), "focus" (point, zoom, name, exact), "show" (surface, mapId,
	 * x, y, name, detail, marks) and "follow" (op, name, point). With an id (1 to 64 of A-Z a-z 0-9 _ . : -) the answer is a
	 * {@code reply} with that id, "topic", "ok" and "reason"; with a null id nothing is answered.
	 */
	public static void request(EventBus bus, String owner, String topic, String requestId, Object... keyValues)
	{
		send(bus, "request", with(data(owner, "topic", topic, "id", requestId), keyValues));
	}

	/**
	 * Pushes what this plugin knows of a topic ("progress", "layers", "game_map", "status", "order_undo"; see docs/COMPAT.md). The number
	 * must go up from one push of a topic to the next; an older one is ignored. Send it once and again only when it changed, at most once
	 * a game tick. Everything pushed is forgotten at the goodbye.
	 */
	public static void state(EventBus bus, String owner, String topic, int seq, Object... keyValues)
	{
		send(bus, "state", with(data(owner, "topic", topic, "seq", seq), keyValues));
	}

	/** Posts any message of the API with data made by the methods above or by hand. */
	public static void send(EventBus bus, String name, Map<String, Object> data)
	{
		bus.post(new PluginMessage(NAMESPACE, name, data));
	}

	/** A setting of World Map Plus, as text, or null. Parse it yourself. The keys partners may rely on are listed in docs/COMPAT.md. */
	public static String setting(ConfigManager config, String key)
	{
		return config.getConfiguration(SETTINGS_GROUP, key);
	}

	// Reading what World Map Plus says. Every value is checked, so a message of an unexpected shape reads as nothing.

	/** True when the event is a message in World Map Plus's namespace with this name. */
	public static boolean isReply(PluginMessage message, String name)
	{
		return message != null && NAMESPACE.equals(message.getNamespace()) && name.equals(message.getName()) && message.getData() != null;
	}

	/** True for the {@code reply} to the request with this id. */
	public static boolean isAnswer(PluginMessage message, String requestId)
	{
		return isReply(message, "reply") && requestId.equals(message.getData().get("id"));
	}

	/**
	 * True for World Map Plus's push of a topic: "theme" (name, colors, edgeWidth), "saved_changed" (pins, routes), "chat_busy" (busy),
	 * "chosen" (surface, entry, kind), "follow_ended" (name) and "off" (partner, kind, id, reason).
	 */
	public static boolean isState(PluginMessage message, String topic)
	{
		return isReply(message, "state") && message.getData().get("owner") == null && topic.equals(message.getData().get("topic"));
	}

	/** True for {@code ready} (World Map Plus started, register again), and for a {@code hello_reply} to your own id. */
	public static boolean mustRegister(PluginMessage message, String owner)
	{
		return isReply(message, "ready") && "main".equals(message.getData().get("role"))
			|| isReply(message, "hello_reply") && owner.equals(message.getData().get("id"));
	}

	/** True for a {@code ping}: answer with {@link #hello}. */
	public static boolean isPing(PluginMessage message)
	{
		return isReply(message, "ping");
	}

	/** True for {@code main_goodbye}: World Map Plus is stopping. Nothing is kept there; register again after the next {@code ready}. */
	public static boolean mainStopped(PluginMessage message)
	{
		return isReply(message, "main_goodbye");
	}

	/** The palette of a {@code theme} push, role name (ACCENT, TEXT, PAGE, CARD, ...) to colour, or an empty map. */
	public static Map<String, Color> theme(PluginMessage message)
	{
		Map<String, Color> colors = new HashMap<>();
		Object all = isState(message, "theme") ? message.getData().get("colors") : null;
		if (all instanceof Map)
		{
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) all).entrySet())
			{
				if (entry.getKey() instanceof String && entry.getValue() instanceof Color)
				{
					colors.put((String) entry.getKey(), (Color) entry.getValue());
				}
			}
		}
		return Collections.unmodifiableMap(colors);
	}

	/** The width of card edges in a {@code theme} push (1 in most themes), or 1 when it says none. */
	public static float edgeWidth(PluginMessage message)
	{
		Object width = isState(message, "theme") ? message.getData().get("edgeWidth") : null;
		return width instanceof Number ? ((Number) width).floatValue() : 1f;
	}

	/** The id of a {@code reply} or {@code hello_reply}, or an empty text. */
	public static String requestId(PluginMessage message)
	{
		Object id = message == null || message.getData() == null ? null : message.getData().get("id");
		return id instanceof String ? (String) id : "";
	}

	/** True when a {@code reply} says it worked. */
	public static boolean ok(PluginMessage message)
	{
		return message != null && message.getData() != null && Boolean.TRUE.equals(message.getData().get("ok"));
	}

	private static Map<String, Object> data(String owner, Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("owner", owner);
		return with(data, keyValues);
	}

	private static Map<String, Object> with(Map<String, Object> data, Object... keyValues)
	{
		for (int i = 0; i + 1 < keyValues.length; i += 2)
		{
			if (keyValues[i + 1] != null)
			{
				data.put((String) keyValues[i], keyValues[i + 1]);
			}
		}
		return data;
	}
}
