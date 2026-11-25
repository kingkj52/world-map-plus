package com.worldmapplus.bridge;

import com.worldmapplus.data.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.pins.*;
import com.worldmapplus.search.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.unlock.*;
import com.worldmapplus.view.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.List;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.swing.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.coords.*;

/**
 * Plugin API v3 (docs/COMPAT.md): what other plugins add to this one and ask of it, received as plugin messages. Four things cross:
 * the handshake ({@code partner_hello}, {@code partner_goodbye}, {@code ping}), contributions ({@code register} and
 * {@code unregister}, kept in one registry of slots), questions ({@code request} with a topic, answered with a {@code reply}) and
 * pushed state ({@code state} with a topic and a sequence number, both ways). A slot has an owner plugin id, an id, an order and the
 * partner's callbacks; the same kind, owner and id sent again replaces it, and slots sort by order, then owner, then id. Every call into
 * partner code goes through {@link #call}: exceptions are caught and logged once, three in a row switch the slot off until its owner
 * registers it again, and a call over its time budget makes the slot skip a while.
 */
@Slf4j
public final class Partners
{
	/** The display name of the second plugin. The one place it is written; the text players see is made from it. */
	public static final String HELPER_NAME = "World Map Plus Helper";

	public static final String INSTALL = "This needs the " + HELPER_NAME + " plugin. Install it from the Plugin Hub for full functionality.";

	/** The settings group partners read their settings from, through RuneLite's ConfigManager. */
	public static final String SETTINGS_GROUP = "worldmapplus";

	/** The kinds of contribution. A painter registered with the layer "screen" is a {@link #PANEL}. */
	@RequiredArgsConstructor
	public enum Kind
	{
		/** A component in the side panel. call: JComponent. Keys: tab (places, routes, top or a name), title. */
		SECTION("section", ""),
		/** A handler for a panel button's action id (the slot id). call: Consumer of the payload map, on the interface thread. */
		ACTION("action", ""),
		/** Draws on the map. call: BiConsumer of Graphics2D and the frame map. Key: layer (under or over). */
		PAINTER("painter", ""),
		/** Says what is under the pointer. call: Function of the frame map to a map or null. click: Consumer. */
		HIT("hit", "click"),
		/** Adds lines to a hover card (call: Function of the thing to a List of String) or, for the kinds in owns, gives its whole body. */
		CARD("card", "owns"),
		/** Gives search results. call: Function of the query to a List of Map. select: Consumer. On the shared executor. */
		SEARCH("search", "select"),
		/** Right click menu entries. call: Consumer of the context. when: Function of the context to a List of texts, one entry each. */
		MENU("menu", "when"),
		/** A panel in screen space over the map. call: BiConsumer of Graphics2D and the panel frame. input: Function of an event to Boolean. */
		PANEL("painter", "input"),
		/** A button on hover cards. call: Function of the subject to a List of String (button text, menu text) or null. click: Function to a notice. */
		BUTTON("button", "click");

		/** The kind's name in a register message, and the key of its second callback or list. */
		final String wire;
		final String extra;
	}

	/** The topics of a {@code request}, of a {@code state} a partner pushes, and of a {@code state} this plugin pushes. */
	public static final List<String> REQUESTS = Collections.unmodifiableList(Arrays.asList(
		"saved", "save_pins", "save_route", "route_order", "go", "coverage", "focus", "show", "follow"));
	public static final List<String> STATES = Collections.unmodifiableList(Arrays.asList(
		"judged", "layers", "game_map", "status", "order_undo"));
	public static final List<String> PUSHES = Collections.unmodifiableList(Arrays.asList(
		"theme", "needs", "saved_changed", "chat_busy", "chosen", "route", "follow_ended", "off"));
	private static final List<String> PARTNER_MESSAGES = Arrays.asList("partner_hello", "register", "unregister", "request", "state");
	public static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList(
		"section", "action", "painter", "hit", "card", "button", "menu", "search"));

	/** The action ids of the side panel's Share, Import, Order and Undo order buttons, which the Helper plugin handles. */
	public static final String ACTION_SHARE_PLACES = "share_places";
	public static final String ACTION_SHARE_ROUTE = "share_route";
	public static final String ACTION_IMPORT = "import_code";
	public static final String ACTION_ORDER = "order_route";
	public static final String ACTION_UNDO_ORDER = "undo_order";

	/** The tab name that puts a section above the side panel's tabs, with no header of the panel's own. */
	public static final String TOP = "top";

	static final int MAX_SLOTS = 64;
	static final int MAX_PER_OWNER = 32;
	static final int MAX_OWNERS = 8;
	static final int MAX_FAILS = 3;
	static final int MAX_LINES = 4;
	static final int MAX_THING_LINES = 8;
	static final int MAX_THINGS = 4;
	static final int MAX_OWNS_KINDS = 8;
	static final int MAX_THING_ROWS = 160;
	static final int MAX_RESULTS = 20;
	static final int MAX_MENU = 8;
	static final int MAX_MENU_TEXT = 40;
	static final int MAX_HINT = 60;
	static final int MAX_MARKS = 400;
	static final int MAX_ALIASES = 6;
	static final int MAX_SHARED = 64;
	static final int MAX_COVER_POINTS = 16;
	/** The requests and states one owner may send in any one second, and the partner hellos everyone together may. */
	static final int PER_SECOND = 20;
	static final long PING_MILLIS = 30_000;
	static final long SILENT_MILLIS = 95_000;
	static final long HIT_KEEP_NANOS = 150_000_000L;
	static final long NOTICE_GRACE_MILLIS = 10_000;
	private static final int MAX_SKIP = 600;
	private static final int SLOW_RUN = 8;

	/**
	 * What one call may take before the slot counts a slow call. A painter or a panel is skipped only after {@link #SLOW_RUN} slow calls
	 * in a row, so one slow frame (the first draw, a pause of the garbage collector) never makes it blink; a panel has the wider budget.
	 */
	long paintBudget = 4_000_000L;
	long panelBudget = 40_000_000L;
	long hitBudget = 1_000_000L;
	long cardBudget = 5_000_000L;
	long searchBudget = 300_000_000L;

	/** One contribution. */
	@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class Slot
	{
		public final Kind kind;
		public final String owner;
		public final String id;
		public final int order;
		public final String title;
		public final String tab;
		private final Object call;
		private final Object more;
		/** A search provider's second callback, for texts with the filters of a place (null for none). */
		private final Object filtered;
		private final ThreadLocal<Frame> frame = ThreadLocal.withInitial(Frame::new);
		private volatile boolean off;
		private volatile int fails;
		private volatile int skip;
		private int slow;
		private int good;
		private int run;
		private boolean told;

		public boolean isOff()
		{
			return off;
		}

		/** The layer of a painter: "under" (below the icons), "route" (above the icons, below the route discs and pins) or "over". */
		public String layer()
		{
			return more instanceof String ? (String) more : "over";
		}

		/** The component of a section slot. */
		public JComponent component()
		{
			return (JComponent) call;
		}
	}

	/**
	 * What a painter, a hit test or a panel is given, made once per slot and thread and reused, so a frame allocates nothing. The arrays
	 * are a panel's and change in place.
	 */
	private static final class Frame
	{
		final Map<String, Object> data = new HashMap<>();
		final Map<String, Object> view = Collections.unmodifiableMap(data);
		final AffineTransform transform = new AffineTransform();
		final Rectangle bounds = new Rectangle();
		final int[] map = new int[4];
		final int[] limits = new int[2];
		final int[] mouse = new int[2];
		final double[] player = new double[3];
		final long[] now = new long[1];
		final boolean[] fades = new boolean[1];
		int mapId = -1;
		int floor = -1;
		int surface = -1;
		double zoom;

		Frame()
		{
			data.putAll(Wire.map("transform", transform, "bounds", bounds, "map", map, "limits", limits, "mouse", mouse, "player", player,
				"now", now, "fades", fades));
		}

		Map<String, Object> set(ViewTransform v, int map, int floorNow, BiPredicate<Double, Double> onMap)
		{
			data.put("onMap", onMap);
			double scale = v.getPixelsPerTile();
			transform.setTransform(scale, 0, 0, -scale, v.screenX(0), v.screenY(0));
			bounds.setBounds(v.getLeft(), v.getTop(), v.getWidth(), v.getHeight());
			if (map != mapId || floorNow != floor || scale != zoom)
			{
				mapId = map;
				floor = floorNow;
				zoom = scale;
				data.putAll(Wire.map("mapId", map, "floor", floorNow, "zoom", scale));
			}
			return view;
		}
	}

	/** Adds the rows a partner gave (a List of two-element Lists, label and value): the first at once, the rest when the card is expanded. */
	private static void rows(HoverCard.Builder card, Object given)
	{
		Object[] rows = Wire.list(given);
		for (int i = 0; i < rows.length && i < MAX_THING_ROWS; i++)
		{
			Object[] row = Wire.list(rows[i]);
			if (row.length == 2 && i < HotspotLoader.MAX_ROWS)
			{
				card.row(Wire.label(row[0], 60), Wire.label(row[1], 80));
			}
			else if (row.length == 2)
			{
				card.overflowRow(Wire.label(row[0], 60), Wire.label(row[1], 80));
			}
		}
	}

	/** A thing a hit test found under the pointer, checked and copied. */
	public static final class Thing
	{
		private final Slot slot;
		private final Map<String, Object> data;
		private final HoverCard card;
		public final String name;
		public final boolean clickable;
		@Nullable
		public final WorldPoint point;

		Thing(Slot slot, Map<?, ?> raw)
		{
			this.slot = slot;
			name = Wire.text(raw.get("name"));
			clickable = slot.more != null && Boolean.TRUE.equals(raw.get("clickable"));
			point = Wire.point(raw.get("point"));
			data = describe(Wire.label(raw.get("kind"), 20), name, point, Wire.label(raw.get("id"), 64));
			String detail = Wire.text(raw.get("detail"));
			String hint = Wire.label(raw.get("hint"), MAX_HINT);
			HoverCard.Builder made = HoverCard.builder(name.isEmpty() ? "Marker" : name).subtitle(detail.isEmpty() ? null : detail);
			Wire.strings(raw.get("lines"), MAX_THING_LINES).forEach(line -> made.row(line, ""));
			// A whole card of rows (round 62E, the Helper's shops): the first rows at once, the rest when the card is expanded.
			rows(made, raw.get("rows"));
			String wiki = Wire.label(raw.get("wiki"), 20);
			if (!wiki.isEmpty() && !name.isEmpty())
			{
				made.wiki(wiki, name);
			}
			card = (clickable && hint.isEmpty() ? made.hint("Click to choose") : hint.isEmpty() ? made : made.hint(hint)).build();
		}

		public HoverCard card()
		{
			return card;
		}

		/** What partners are told of this thing: kind, name, tile and id. */
		public Map<String, Object> data()
		{
			return data;
		}
	}

	/** The button a partner puts on a card: its text, the text of the matching menu entry, and the key to press it with. */
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class Button
	{
		public final String label;
		public final String menu;
		public final String key;
	}

	/** A request to show a place: the map to go to and the tiles to mark. */
	@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class Show
	{
		public final int surface;
		public final int mapId;
		public final double x;
		public final double y;
		public final String name;
		public final String detail;
		public final List<double[]> marks;
	}

	/** A partner that said something lately: its role, when, its rate gate and the last sequence number of each state topic. */
	private static final class Seen
	{
		String role = "other";
		boolean owes;
		long at;
		final Wire.Gate gate = new Wire.Gate(PER_SECOND);
		final Map<String, Integer> seqs = new HashMap<>();
	}

	/** A question waiting for the next client tick. */
	@RequiredArgsConstructor
	private static final class Ask
	{
		final String owner;
		final String topic;
		final String id;
		final Map<String, Object> data;
		final long at;
	}

	/** The hit test answers kept for a moment, for one pointer position and view. */
	@RequiredArgsConstructor
	private static final class Kept
	{
		final long key;
		final long at;
		final List<Thing> things;
	}

	/** Whether the map with an id holds a tile. */
	public interface MapCheck
	{
		boolean holds(int map, int x, int y);
	}

	private final ExternalOverlays outer;
	private final Map<String, Slot> slots = new LinkedHashMap<>();
	private final Map<String, Seen> owners = new LinkedHashMap<>();
	/** The owner whose push each state topic last took, so its goodbye clears it. */
	private final Map<String, String> stateOwners = new HashMap<>();
	private final ArrayDeque<Ask> asks = new ArrayDeque<>();
	private final Unlocks unlocks = new Unlocks();
	private final Wire.Gate helloGate = new Wire.Gate(8);
	private volatile Slot[][] table = new Slot[Kind.values().length][0];
	private volatile int version;
	LongSupplier clock = System::currentTimeMillis;
	private Consumer<Runnable> toEdt = Runnable::run;
	private Consumer<Runnable> toClient = Runnable::run;
	private Consumer<Runnable> background = Runnable::run;
	private PinStore pins;
	private RouteStore routes;
	private IntBinaryOperator tileMap = (x, y) -> -1;
	private MapCheck mapHolds = (map, x, y) -> false;
	private Consumer<String> statusLine = text -> { };
	private Runnable panelOpener = () -> { };
	private Function<WorldPoint, String> goer = at -> "missing";
	private BooleanSupplier chatBusy = () -> false;
	private IntFunction<String> mapNames = id -> null;
	volatile ExternalOverlays.Coverage coverage;
	private boolean busyShown;
	private int pushSeq;
	private long lastPing;
	private int seenPins = -1;
	private int seenNeeds;
	private int seenRoutes = -1;
	private long noticeGrace = NOTICE_GRACE_MILLIS;
	private long expectedAt;
	private boolean expected;
	private boolean noticed;
	private String found = "";
	private String wanted = "";
	private boolean searching;
	private List<SearchEntry> foundEntries = Collections.emptyList();
	private Map<SearchEntry, Object[]> foundData = new IdentityHashMap<>();
	private volatile int searchVersion;
	private SearchContext wantedContext = SearchContext.NONE;
	private volatile Kept kept;
	private volatile Show pendingShow;
	private volatile long undoRoute;
	private volatile List<Long> undoStops = Collections.emptyList();
	private volatile int orderVersion;

	Partners(ExternalOverlays outer)
	{
		this.outer = outer;
	}

	/** Connects what a running plugin has: the threads to hop to, the saved places, and which map holds a tile (-1 for none). */
	public void attach(Consumer<Runnable> edt, Consumer<Runnable> client, Consumer<Runnable> background, @Nullable PinStore pins,
		@Nullable RouteStore routes, IntBinaryOperator tileMap)
	{
		this.toEdt = edt == null ? Runnable::run : edt;
		this.toClient = client == null ? Runnable::run : client;
		this.background = background == null ? Runnable::run : background;
		this.pins = pins;
		this.routes = routes;
		this.tileMap = tileMap;
	}

	/**
	 * Connects what the requests and states that act on the plugin need: whether a map holds a tile, the side panel's status line and
	 * how to open the panel (both on the interface thread), the way to ask for a route (on the client thread; answers "ok", "missing"
	 * or "disabled"), and whether the game's chat area is taken by this plugin's own search.
	 */
	public void connect(MapCheck holds, Consumer<String> statusLine, Runnable panelOpener, Function<WorldPoint, String> goer,
		BooleanSupplier chatBusy)
	{
		this.mapHolds = holds == null ? (map, x, y) -> false : holds;
		this.statusLine = statusLine == null ? text -> { } : statusLine;
		this.panelOpener = panelOpener == null ? () -> { } : panelOpener;
		this.goer = goer == null ? at -> "missing" : goer;
		this.chatBusy = chatBusy == null ? () -> false : chatBusy;
	}

	/** Connects the name of a map (null when there is none), for the entry of a chosen result. */
	public void mapNames(IntFunction<String> names)
	{
		this.mapNames = names == null ? id -> null : names;
	}

	public int version()
	{
		return version;
	}

	/** What the Helper last pushed of the player's progress, for the marks on icons and cards. */
	public Unlocks unlocks()
	{
		return unlocks;
	}

	public int searchVersion()
	{
		return searchVersion;
	}

	public Slot[] slots(Kind kind)
	{
		return table[kind.ordinal()];
	}

	public boolean any(Kind kind)
	{
		return table[kind.ordinal()].length > 0;
	}

	// ------------------------------------------------------------------
	// Messages in
	// ------------------------------------------------------------------

	/** Reads one message of the namespace. Anything without a good owner, or of a name this class does not know, is ignored. */
	public synchronized void onMessage(String name, Map<String, Object> data)
	{
		String owner = Wire.id(data.get("owner"));
		if ("partner_goodbye".equals(name) && !owner.isEmpty())
		{
			drop(owner, "goodbye");
			return;
		}
		// Only these come from partners: what this plugin posts itself never carries an owner.
		Seen seen = owner.isEmpty() || !PARTNER_MESSAGES.contains(name) ? null : seen(owner);
		long now = clock.getAsLong();
		if (seen == null)
		{
			return;
		}
		if ("partner_hello".equals(name))
		{
			seen.role = "part two".equals(data.get("role")) ? "part two" : "other";
			// A hello over the rate limit is not lost: it is answered on a later tick, as soon as the limit allows.
			seen.owes = !helloGate.allow(now);
			if (!seen.owes)
			{
				welcome(owner);
			}
		}
		else if ("register".equals(name))
		{
			register(owner, data);
		}
		else if ("unregister".equals(name))
		{
			String kind = Wire.label(data.get("kind"), 12);
			String id = Wire.id(data.get("id"));
			if (slots.values().removeIf(s -> s.owner.equals(owner) && (kind.isEmpty() || s.kind.wire.equals(kind)) && (id.isEmpty() || s.id.equals(id))))
			{
				rebuild();
			}
		}
		else if (("request".equals(name) || "state".equals(name)) && seen.gate.allow(now))
		{
			Map<String, Object> copy = Wire.copy(data);
			String topic = Wire.label(data.get("topic"), 20);
			if (copy != null && "request".equals(name) && REQUESTS.contains(topic))
			{
				if (asks.size() >= ExternalOverlays.MAX_WAITING)
				{
					asks.removeFirst();
				}
				asks.addLast(new Ask(owner, topic, Wire.id(data.get("id")), copy, now));
			}
			else if (copy != null && "state".equals(name) && STATES.contains(topic) && copy.get("seq") instanceof Number
				&& ((Number) copy.get("seq")).intValue() > seen.seqs.getOrDefault(topic, Integer.MIN_VALUE))
			{
				seen.seqs.put(topic, ((Number) copy.get("seq")).intValue());
				stateOwners.put(topic, owner);
				state(owner, topic, copy);
			}
		}
	}

	/** Answers a partner's hello: the capabilities, and what a partner needs to know at the start. */
	private void welcome(String owner)
	{
		Map<String, Object> answer = ExternalOverlays.capabilities();
		answer.put("id", owner);
		outer.say("hello_reply", answer);
		push("theme", themeData());
		pushBusy(chatBusy.getAsBoolean());
		pushNeeds();
	}

	/** Answers the hellos that came over the rate limit, as the limit allows. */
	private void welcomeOwed(long now)
	{
		for (Map.Entry<String, Seen> entry : owners.entrySet())
		{
			if (entry.getValue().owes && helloGate.allow(now))
			{
				entry.getValue().owes = false;
				welcome(entry.getKey());
			}
		}
	}

	/** Notes that an owner is there, if there is room for it. Null when there is not. */
	@Nullable
	private Seen seen(String owner)
	{
		Seen seen = owners.get(owner);
		if (seen == null && owners.size() < MAX_OWNERS)
		{
			seen = new Seen();
			owners.put(owner, seen);
		}
		if (seen != null)
		{
			seen.at = clock.getAsLong();
		}
		return seen;
	}

	private void register(String owner, Map<String, Object> data)
	{
		String wire = Wire.label(data.get("kind"), 12);
		String layer = Wire.label(data.get("layer"), 8);
		Kind kind = "painter".equals(wire) && "screen".equals(layer) ? Kind.PANEL : null;
		for (Kind k : Kind.values())
		{
			kind = kind == null && k.wire.equals(wire) ? k : kind;
		}
		String id = Wire.id(data.get("id"));
		if (kind == null || id.isEmpty())
		{
			return;
		}
		Object call = data.get("call");
		Object more = kind.extra.isEmpty() ? null : data.get(kind.extra);
		Set<String> owns = new HashSet<>(Wire.strings(more, MAX_OWNS_KINDS));
		// The second callback (or a painter's layer, a card's kinds): needed for a panel's input and a button's click, else optional.
		more = kind == Kind.PAINTER ? ("under".equals(layer) || "route".equals(layer) ? layer : null) : kind == Kind.CARD ? (owns.isEmpty() ? null : Collections.unmodifiableSet(owns))
			: kind == Kind.HIT || kind == Kind.SEARCH ? (more instanceof Consumer ? more : null) : more instanceof Function ? more : null;
		boolean fits = (kind == Kind.SECTION ? call instanceof JComponent : kind == Kind.ACTION ? call instanceof Consumer
			: kind == Kind.MENU ? call instanceof Consumer
			: kind == Kind.PAINTER || kind == Kind.PANEL ? call instanceof BiConsumer : call instanceof Function)
			&& (more != null || kind != Kind.PANEL && kind != Kind.BUTTON && kind != Kind.MENU);
		String key = kind + "|" + owner + "|" + id;
		double order = Wire.number(data.get("order"));
		if (fits && (slots.containsKey(key) || slots.size() < MAX_SLOTS && slots.values().stream().filter(s -> s.owner.equals(owner)).count() < MAX_PER_OWNER))
		{
			String tab = Wire.label(data.get("tab"), 20);
			slots.put(key, new Slot(kind, owner, id, Double.isNaN(order) ? 100 : (int) Math.max(-1000, Math.min(1000, order)),
				Wire.label(data.get(kind == Kind.MENU ? "label" : "title"), MAX_MENU_TEXT), tab.isEmpty() ? "places" : tab, call, more,
					kind == Kind.SEARCH && data.get("filtered") instanceof Function ? data.get("filtered") : null));
			rebuild();
		}
	}

	/** Takes a state a partner pushed (its sequence number already checked). An empty map clears the topic. */
	private void state(String owner, String topic, Map<String, Object> data)
	{
		switch (topic)
		{
			case "judged":
				if (data.isEmpty())
				{
					unlocks.clear();
				}
				else
				{
					unlocks.take(data);
				}
				break;
			case "layers":
				outer.setLayers(data);
				break;
			case "game_map":
				outer.setGameMap(data);
				break;
			case "status":
				String text = Wire.text(data.get("text"));
				boolean open = Boolean.TRUE.equals(data.get("open"));
				toEdt.accept(() ->
				{
					if (open)
					{
						panelOpener.run();
					}
					if (!text.isEmpty())
					{
						statusLine.accept(text);
					}
				});
				break;
			default:
				// order_undo: which route has an order that can be put back, and its stops now. No stops clear it.
				List<Long> stops = Wire.longs(data.get("stops"), RouteStore.MAX_STOPS);
				if (stops != null && stops.isEmpty() || data.isEmpty())
				{
					forgetOrder();
				}
				else if (stops != null && data.get("route") instanceof Number)
				{
					undoRoute = ((Number) data.get("route")).longValue();
					undoStops = Collections.unmodifiableList(stops);
					orderVersion++;
				}
				break;
		}
	}

	/** Pushes a state topic of this plugin to every partner, with the next sequence number. */
	synchronized void push(String topic, Map<String, Object> data)
	{
		data.put("topic", topic);
		data.put("seq", ++pushSeq);
		outer.say("state", data);
	}

	/** Answers a request that carried an id, on the next client tick: the id, the topic, ok, the reason and the rest of the answer. */
	void reply(String id, String topic, String reason, Map<String, Object> answer)
	{
		if (!id.isEmpty())
		{
			answer.put("id", id);
			answer.put("topic", topic);
			answer.put("ok", "ok".equals(reason));
			answer.put("reason", reason);
			outer.say("reply", answer);
		}
	}

	/** Removes an owner and everything it gave and pushed. Anything but a goodbye is told to everyone. */
	public synchronized void drop(String owner, String why)
	{
		boolean had = owners.remove(owner) != null;
		for (String topic : new ArrayList<>(stateOwners.keySet()))
		{
			if (stateOwners.get(topic).equals(owner))
			{
				stateOwners.remove(topic);
				state(owner, topic, Collections.emptyMap());
			}
		}
		asks.removeIf(ask -> ask.owner.equals(owner));
		if (slots.values().removeIf(s -> s.owner.equals(owner)))
		{
			rebuild();
			had = true;
		}
		if (had && !"goodbye".equals(why))
		{
			off(owner, "", "", why);
		}
	}

	/** Tells everyone that a contribution (or, with an empty kind, a whole owner) is gone, so the owner can register again. */
	private void off(String owner, String kind, String id, String why)
	{
		push("off", Wire.map("partner", owner, "kind", kind, "id", id, "reason", why));
	}

	/** Forgets everything, for when the plugin stops. */
	public synchronized void clear()
	{
		forgetOrder();
		slots.clear();
		owners.clear();
		stateOwners.clear();
		asks.clear();
		unlocks.clear();
		rebuild();
		attach(null, null, null, null, null, (x, y) -> -1);
		connect(null, null, null, null, null);
		mapNames(null);
		coverage = null;
		pendingShow = null;
		busyShown = false;
		searching = false;
		found = "";
		wanted = "";
		foundEntries = Collections.emptyList();
		foundData = new IdentityHashMap<>();
	}

	private void rebuild()
	{
		Slot[][] made = new Slot[Kind.values().length][];
		for (Kind kind : Kind.values())
		{
			made[kind.ordinal()] = slots.values().stream().filter(s -> s.kind == kind)
				.sorted(Comparator.<Slot>comparingInt(s -> s.order).thenComparing(s -> s.owner).thenComparing(s -> s.id))
				.toArray(Slot[]::new);
		}
		table = made;
		version++;
	}

	// ------------------------------------------------------------------
	// Guarded calls
	// ------------------------------------------------------------------

	/** Counts a failure of a slot, says so once in the log, and switches the slot off at the third in a row. */
	public void fail(Slot slot, Throwable error)
	{
		if (!slot.told)
		{
			slot.told = true;
			log.warn("World Map Plus: a {} contribution of {} ({}) threw {}", slot.kind, slot.owner, slot.id, error.toString());
		}
		if (++slot.fails >= MAX_FAILS && !slot.off)
		{
			slot.off = true;
			version++;
			off(slot.owner, slot.kind.wire, slot.id, "failed");
		}
	}

	/** False when the slot is switched off or is being skipped for being slow. */
	private static boolean ready(Slot slot)
	{
		if (slot.off)
		{
			return false;
		}
		if (slot.skip > 0)
		{
			slot.skip--;
			return false;
		}
		return true;
	}

	/**
	 * Calls one of a slot's callbacks (a Function or a Consumer of {@code a}, or a BiConsumer of {@code a} and {@code b}), guarded. With a
	 * budget the slot is asked only when it is not being skipped, and a call over the budget makes it skip the next calls, longer each
	 * time it happens again at once; with budget 0 it is always asked (a click is never lost) and not timed. The answer, or null.
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	private Object call(Slot slot, Object callback, Object a, @Nullable Object b, long budget)
	{
		if (budget == 0 ? slot.off : !ready(slot))
		{
			return null;
		}
		long t0 = System.nanoTime();
		try
		{
			Object answer = null;
			if (callback instanceof Function)
			{
				answer = ((Function<Object, Object>) callback).apply(a);
			}
			else if (callback instanceof BiConsumer)
			{
				((BiConsumer<Object, Object>) callback).accept(a, b);
			}
			else
			{
				((Consumer<Object>) callback).accept(a);
			}
			slot.fails = 0;
			return answer;
		}
		catch (RuntimeException | LinkageError | StackOverflowError e)
		{
			fail(slot, e);
			return null;
		}
		finally
		{
			if (budget > 0 && System.nanoTime() - t0 > budget)
			{
				slot.good = 0;
				boolean visible = slot.kind == Kind.PAINTER || slot.kind == Kind.PANEL;
				if (!visible || ++slot.run >= SLOW_RUN)
				{
					slot.skip = Math.min(MAX_SKIP, 15 << Math.min(5, slot.slow++));
					slot.run = 0;
					if (slot.slow == 1)
					{
						log.warn("World Map Plus: a {} contribution of {} ({}) is slow and is being skipped for a while", slot.kind, slot.owner, slot.id);
					}
				}
			}
			else if (budget > 0)
			{
				slot.run = 0;
				if (slot.slow > 0 && ++slot.good >= 3)
				{
					slot.slow = 0;
				}
			}
		}
	}

	/** Gives a partner's callback a value on a thread of the plugin's choosing, guarded and never skipped. */
	private void tell(Consumer<Runnable> thread, Slot slot, Object callback, Object argument)
	{
		thread.accept(() -> call(slot, callback, argument, null, 0));
	}

	/** Draws with a painter or a panel on a copy of the graphics. */
	private void draw(Slot slot, Graphics2D g, Map<String, Object> frame)
	{
		Graphics2D copy = (Graphics2D) g.create();
		try
		{
			call(slot, slot.call, copy, frame, slot.kind == Kind.PANEL ? panelBudget : paintBudget);
		}
		finally
		{
			copy.dispose();
		}
	}

	// ------------------------------------------------------------------
	// Side panel actions
	// ------------------------------------------------------------------

	/** True when some plugin handles the action id. */
	public boolean handles(String action)
	{
		return Arrays.stream(slots(Kind.ACTION)).anyMatch(slot -> !slot.off && slot.id.equals(action));
	}

	/**
	 * Sends a panel button's action, with its payload, to the first plugin that handles it, on the interface thread. False when nobody
	 * does, and the caller shows {@link #INSTALL}.
	 */
	public boolean act(String action, Supplier<Map<String, Object>> payload)
	{
		for (Slot slot : slots(Kind.ACTION))
		{
			if (!slot.off && slot.id.equals(action))
			{
				tell(toEdt, slot, slot.call, payload.get());
				return true;
			}
		}
		return false;
	}

	/** Changes how long the notice waits after {@link #expectHelper} (10 seconds), for tests. */
	public void setNoticeGrace(long millis)
	{
		noticeGrace = millis;
	}

	/** Asks the plugins' features that need part two to say so, so the side panel can tell the player once if it is missing. */
	public synchronized void expectHelper()
	{
		if (!expected)
		{
			expected = true;
			expectedAt = clock.getAsLong();
		}
	}

	public synchronized boolean partTwoPresent()
	{
		return owners.values().stream().anyMatch(s -> "part two".equals(s.role));
	}

	/** True once, when a feature needs part two and it is not there. */
	public synchronized boolean takeNotice()
	{
		if (!expected || noticed || clock.getAsLong() - expectedAt < noticeGrace || partTwoPresent())
		{
			return false;
		}
		noticed = true;
		return true;
	}

	/** The pins and routes with these ids as plain maps, for the payload of an action. */
	public Map<String, Object> payload(Collection<Long> pinIds, Collection<Long> routeIds)
	{
		// The pins also in the order given (the order they were ticked), which the list does not keep.
		return Wire.map("pins", places(pinIds, true), "routes", places(routeIds, false), "order", new ArrayList<>(pinIds));
	}

	/** The pins or the routes (all of them for a null list of ids) as maps of plain values. */
	private List<Map<String, Object>> places(@Nullable Collection<Long> ids, boolean pinsNotRoutes)
	{
		List<Map<String, Object>> list = new ArrayList<>();
		for (Pin pin : pinsNotRoutes && pins != null ? pins.all() : Collections.<Pin>emptyList())
		{
			if (ids == null || ids.contains(pin.getId()))
			{
				int x = (int) Math.floor(pin.getX());
				int y = (int) Math.floor(pin.getY());
				// autoMap: the map this tile is shown on by itself (-1 for none), so a place saved on another map is told from it.
				list.add(Wire.map("id", pin.getId(), "name", pin.getName(), "kind", pin.getKind(), "point", new WorldPoint(x, y, pin.getPlane()),
					"mapId", pin.getMap(), "autoMap", tileMap.applyAsInt(x, y), "color", pin.getColor(), "hidden", pin.isHidden()));
			}
		}
		for (SavedRoute route : !pinsNotRoutes && routes != null ? routes.all() : Collections.<SavedRoute>emptyList())
		{
			if (ids == null || ids.contains(route.getId()))
			{
				list.add(Wire.map("id", route.getId(), "name", route.getName(), "stops", new ArrayList<>(route.getStops()), "loop", route.isLoop()));
			}
		}
		return list;
	}

	// ------------------------------------------------------------------
	// The map: painters, things under the pointer, cards, buttons, menu entries
	// ------------------------------------------------------------------

	/** Lets every painter draw, each on a copy of the graphics, with the transform from world tiles to pixels in its frame map. */
	public void paint(Graphics2D g, ViewTransform view, int mapId, int floor, BiPredicate<Double, Double> onMap)
	{
		paint(g, view, mapId, floor, onMap, "over");
	}

	/** Lets the painters of the layer "route" draw: over the icons and labels, under the route's numbered discs, the saved routes and the pins. */
	public void paintRoute(Graphics2D g, ViewTransform view, int mapId, int floor, BiPredicate<Double, Double> onMap)
	{
		paint(g, view, mapId, floor, onMap, "route");
	}

	/** Lets the painters of the layer "under" draw, right after the ground pictures. The same frame map as {@link #paint}. */
	public void paintUnder(Graphics2D g, ViewTransform view, int mapId, int floor, BiPredicate<Double, Double> onMap)
	{
		paint(g, view, mapId, floor, onMap, "under");
	}

	private void paint(Graphics2D g, ViewTransform view, int mapId, int floor, BiPredicate<Double, Double> onMap, String layer)
	{
		for (Slot slot : slots(Kind.PAINTER))
		{
			if (slot.layer().equals(layer))
			{
				draw(slot, g, slot.frame.get().set(view, mapId, floor, onMap));
			}
		}
	}

	/** What the hit tests say is under the pointer, kept for a moment while the pointer and view stay the same. */
	public List<Thing> hits(ViewTransform view, int mapId, int floor, int x, int y, BiPredicate<Double, Double> onMap)
	{
		Slot[] all = slots(Kind.HIT);
		long now = System.nanoTime();
		long key = (((long) x * 31 + y) * 31 + mapId) * 31 + floor + Double.doubleToLongBits(view.getPixelsPerTile() + view.getCenterX() + view.getCenterY());
		Kept last = kept;
		if (all.length == 0 || last != null && last.key == key && now - last.at < HIT_KEEP_NANOS)
		{
			return all.length == 0 ? Collections.emptyList() : last.things;
		}
		List<Thing> result = new ArrayList<>(2);
		for (Slot slot : all)
		{
			Frame frame = slot.frame.get();
			frame.set(view, mapId, floor, onMap);
			frame.data.put("x", x);
			frame.data.put("y", y);
			frame.data.put("worldX", view.worldX(x));
			frame.data.put("worldY", view.worldY(y));
			Object answer = result.size() < MAX_THINGS ? call(slot, slot.call, frame.view, null, hitBudget) : null;
			if (answer instanceof Map)
			{
				result.add(new Thing(slot, (Map<?, ?>) answer));
			}
		}
		kept = new Kept(key, now, result);
		return result;
	}

	/** Tells the plugin that made a thing that the player clicked it, on the client thread. */
	public void clicked(Thing thing)
	{
		if (thing.clickable)
		{
			tell(toClient, thing.slot, thing.slot.more, thing.data);
		}
	}

	/** The plain values a partner is told about a thing: kind, name, tile and id (the tile may be missing). */
	public static Map<String, Object> describe(String kind, String name, @Nullable WorldPoint tile, String id)
	{
		return Collections.unmodifiableMap(Wire.map("kind", kind, "name", name, "id", id, "tile", tile));
	}

	/** The lines the plugins add to the card of a thing (a map as {@link #describe} makes), a few each, at most 8 in all. */
	public List<String> lines(Map<String, Object> thing)
	{
		List<String> lines = new ArrayList<>();
		for (Slot slot : slots(Kind.CARD))
		{
			if (lines.size() < 2 * MAX_LINES)
			{
				lines.addAll(Wire.strings(call(slot, slot.call, thing, null, cardBudget), MAX_LINES));
			}
		}
		return lines;
	}

	/** True when some plugin gives the whole card for things of this kind (a card slot with it in its {@code owns} list). */
	public boolean owns(String kind)
	{
		return Arrays.stream(slots(Kind.CARD)).anyMatch(slot -> !slot.off && slot.more instanceof Set && ((Set<?>) slot.more).contains(kind));
	}

	/**
	 * The whole card for a thing (a map as {@link #describe} makes) from the plugin that owns its kind, drawn in this plugin's card
	 * style, or null when nobody does or the owner has nothing for it. The owner answers a map: {@code title}, {@code subtitle},
	 * {@code rows} (a List of two-element Lists, label and value, at most {@value #MAX_THING_ROWS}), {@code hint} and {@code wiki}.
	 */
	@Nullable
	public HoverCard body(Map<String, Object> thing)
	{
		for (Slot slot : slots(Kind.CARD))
		{
			if (slot.more instanceof Set && ((Set<?>) slot.more).contains(thing.get("kind")))
			{
				Map<String, Object> asked = new HashMap<>(thing);
				asked.put("body", Boolean.TRUE);
				Object answer = call(slot, slot.call, Collections.unmodifiableMap(asked), null, cardBudget);
				String title = answer instanceof Map ? Wire.label(((Map<?, ?>) answer).get("title"), 60) : "";
				if (!title.isEmpty())
				{
					Map<?, ?> body = (Map<?, ?>) answer;
					String subtitle = Wire.label(body.get("subtitle"), 80);
					String hint = Wire.label(body.get("hint"), 80);
					HoverCard.Builder card = HoverCard.builder(title).subtitle(subtitle.isEmpty() ? null : subtitle);
					rows(card, body.get("rows"));
					String wiki = Wire.label(body.get("wiki"), 20);
					if (!wiki.isEmpty())
					{
						card.wiki(wiki, title);
					}
					return (hint.isEmpty() ? card : card.hint(hint)).build();
				}
			}
		}
		return null;
	}

	/**
	 * The plain values a button's partner is told about a card: its title, what it is for ({@code what}, {@code name} and {@code detail},
	 * the three words of {@code HoverCard.getWiki}) and the name to search the wiki for, when it has them.
	 */
	public static Map<String, Object> subject(String title, @Nullable String[] wiki, @Nullable String wikiSearch)
	{
		return Collections.unmodifiableMap(Wire.map("title", title, "what", wiki == null ? null : wiki[0], "name", wiki == null ? null : wiki[1],
			"detail", wiki == null ? null : wiki[2], "wikiSearch", wikiSearch));
	}

	/** The first button some partner offers for a subject, or null. Asked when a card is made, never per frame. */
	@Nullable
	public Button button(Map<String, Object> subject)
	{
		for (Slot slot : slots(Kind.BUTTON))
		{
			List<String> texts = Wire.strings(call(slot, slot.call, subject, null, cardBudget), 2);
			if (!texts.isEmpty())
			{
				return new Button(Wire.label(texts.get(0), MAX_MENU_TEXT), texts.get(texts.size() - 1), slot.owner + "|" + slot.id);
			}
		}
		return null;
	}

	/** Presses the button with this key for a subject. The partner's notice (cleaned), or null. Not timed: a click is never lost. */
	@Nullable
	public String press(String key, Map<String, Object> subject)
	{
		for (Slot slot : slots(Kind.BUTTON))
		{
			if ((slot.owner + "|" + slot.id).equals(key))
			{
				String notice = Wire.text(call(slot, slot.more, subject, null, 0));
				return notice.isEmpty() ? null : notice;
			}
		}
		return null;
	}

	/** The menu entries that apply to a place (context: tile, mapId, floor and maybe thing and things), at most {@value #MAX_MENU}. */
	public List<Slot> menu(Map<String, Object> context)
	{
		List<Slot> list = new ArrayList<>();
		for (Slot slot : slots(Kind.MENU))
		{
			// The texts are the entries made for this place: one entry each.
			for (String entry : list.size() >= MAX_MENU ? Collections.<String>emptyList() : Wire.strings(call(slot, slot.more, context, null, Long.MAX_VALUE), MAX_MENU))
			{
				if (list.size() < MAX_MENU)
				{
					list.add(new Slot(slot.kind, slot.owner, slot.id, slot.order, Wire.label(entry, MAX_MENU_TEXT), slot.tab, slot.call, slot.more, slot.filtered));
				}
			}
		}
		return list;
	}

	/** Tells the plugin that the player chose its menu entry, with the entry's text as {@code option}, on the client thread. */
	public void choose(Slot slot, Map<String, Object> context)
	{
		Map<String, Object> told = new HashMap<>(context);
		told.put("option", slot.title);
		tell(toClient, slot, slot.call, Collections.unmodifiableMap(told));
	}

	// ------------------------------------------------------------------
	// Panels in screen space, the results of a search choice, and showing a place
	// ------------------------------------------------------------------

	/**
	 * Lets the panels of partners draw in screen space. The frame map has "map" (x, y, width, height of the map's controls area),
	 * "limits" (the highest the panel may start and the furthest right it may reach), "mouse" (x and y, or -1), "player" (map id or -1,
	 * x, y), "now" (milliseconds), "fades" (whether short fades are on), "box" (this surface's int array: the panel writes its x, y,
	 * width and height in it, width 0 when it shows nothing, and 1 in the fifth place when it wants another frame) and "surface".
	 */
	public void paintPanels(Graphics2D g, int surface, int[] box, Rectangle map, int top, int right, @Nullable Point mouse, long now,
		boolean fades, int playerMap, double px, double py)
	{
		for (Slot slot : slots(Kind.PANEL))
		{
			Frame frame = slot.frame.get();
			frame.map[0] = map.x;
			frame.map[1] = map.y;
			frame.map[2] = map.width;
			frame.map[3] = map.height;
			frame.limits[0] = top;
			frame.limits[1] = right;
			frame.mouse[0] = mouse == null ? -1 : mouse.x;
			frame.mouse[1] = mouse == null ? -1 : mouse.y;
			frame.player[0] = playerMap;
			frame.player[1] = px;
			frame.player[2] = py;
			frame.now[0] = now;
			frame.fades[0] = fades;
			if (frame.surface != surface)
			{
				frame.surface = surface;
				frame.data.put("surface", surface);
			}
			frame.data.put("box", box);
			draw(slot, g, frame.view);
		}
	}

	/** True when a point is on the panel a surface's box says is shown. */
	public static boolean onPanel(int[] box, int x, int y)
	{
		return box[2] > 0 && x >= box[0] && y >= box[1] && x < box[0] + box[2] && y < box[1] + box[3];
	}

	/**
	 * Gives a mouse event on a panel to the partners that have one: type "click", "press", "drag", "release", "wheel" or "close", the
	 * pixel, and for a wheel the rows. True when a partner took it (for "press", when it began dragging a scroll bar).
	 */
	public boolean panelInput(int surface, String type, int x, int y, int amount)
	{
		for (Slot slot : slots(Kind.PANEL))
		{
			if (Boolean.TRUE.equals(call(slot, slot.more, Wire.map("surface", surface, "type", type, "x", x, "y", y, "amount", amount), null, 0)))
			{
				return true;
			}
		}
		return false;
	}

	/** Tells partners with a panel that the player chose a search result, on the next client tick; a partner's own result carries its id. */
	public void chosen(int surface, SearchEntry entry)
	{
		if (!any(Kind.PANEL))
		{
			return;
		}
		Map<String, Object> shown = entryData(entry);
		synchronized (this)
		{
			Object[] known = foundData.get(entry);
			shown.put("id", known == null ? null : ((Map<?, ?>) known[1]).get("id"));
		}
		push("chosen", Wire.map("surface", surface, "entry", shown));
	}

	private String name(int mapId)
	{
		String name = mapNames.apply(mapId);
		return name == null ? "" : name;
	}

	private Map<String, Object> entryData(SearchEntry entry)
	{
		return Wire.map("kind", entry.getKind().name(), "name", entry.getName(), "detail", entry.getDetail(), "mapId", entry.getMapId(),
			"mapName", name(entry.getMapId()), "x", entry.getX(), "y", entry.getY(), "alsoAt", marks(entry.getAlsoAt()));
	}

	/** Copies of the tiles ({x, y} arrays) of a list, finite ones only, at most {@value #MAX_MARKS}. */
	private static List<double[]> marks(List<?> tiles)
	{
		List<double[]> list = new ArrayList<>();
		for (Object item : Wire.list(tiles))
		{
			double[] tile = item instanceof double[] && ((double[]) item).length == 2 ? ((double[]) item).clone() : null;
			if (tile != null && !Double.isNaN(Wire.number(tile[0])) && !Double.isNaN(Wire.number(tile[1])) && list.size() < MAX_MARKS)
			{
				list.add(tile);
			}
		}
		return list;
	}

	/** The pending request to show a place if it is for this surface, taken (once), else null. A field read when there is none. */
	@Nullable
	public Show takeShow(int surface)
	{
		Show now = pendingShow;
		if (now == null || now.surface != surface)
		{
			return null;
		}
		synchronized (this)
		{
			if (pendingShow != now)
			{
				return null;
			}
			pendingShow = null;
		}
		return now;
	}

	// ------------------------------------------------------------------
	// Search
	// ------------------------------------------------------------------

	/**
	 * What the plugins' search providers found for the text, ranked by this plugin's own search and cut to {@value #MAX_RESULTS}.
	 * The providers run on the shared executor, one search at a time with only the newest text kept, so nothing waits for them: this
	 * gives what is known for exactly this text, and {@link #searchVersion} goes up when an answer arrives.
	 */
	public List<SearchEntry> search(String query, SearchContext context)
	{
		if (!any(Kind.SEARCH) || query.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		synchronized (this)
		{
			if (query.equals(found))
			{
				return foundEntries;
			}
			wanted = query;
			wantedContext = context;
			if (searching)
			{
				return Collections.emptyList();
			}
			searching = true;
		}
		background.accept(this::runSearches);
		synchronized (this)
		{
			return query.equals(found) ? foundEntries : Collections.emptyList();
		}
	}

	private void runSearches()
	{
		boolean again = true;
		while (again)
		{
			String query;
			SearchContext context;
			synchronized (this)
			{
				query = wanted;
				context = wantedContext;
			}
			Map<SearchEntry, Object[]> data = new IdentityHashMap<>();
			List<SearchEntry> entries = collect(query, context.getGazetteer(), data);
			synchronized (this)
			{
				found = query;
				foundEntries = entries;
				foundData = data;
				searchVersion++;
				again = !query.equals(wanted);
				searching = again;
			}
		}
	}

	private List<SearchEntry> collect(String query, Gazetteer places, Map<SearchEntry, Object[]> data)
	{
		List<SearchEntry> entries = new ArrayList<>();
		SearchQuery parsed = SearchQuery.parse(query);
		boolean placed = parsed.hasPlaceFilter();
		// A partner that can judge a place is given a test of one, to apply before it ranks and cuts; every result is judged here too.
		Predicate<double[]> place = spot -> SearchIndex.inPlace((int) spot[0], spot[1], spot[2], parsed, places);
		for (Slot slot : parsed.hasBadFilter() ? new Slot[0] : slots(Kind.SEARCH))
		{
			Object answer = placed && slot.filtered != null ? call(slot, slot.filtered, Wire.map("text", query, "place", place), null, searchBudget)
				: call(slot, slot.call, query, null, searchBudget);
			for (Object item : Wire.list(answer))
			{
				Map<?, ?> map = item instanceof Map ? (Map<?, ?>) item : Collections.emptyMap();
				WorldPoint at = Wire.point(map.get("point"));
				String name = Wire.text(map.get("name"));
				int mapId = map.get("mapId") instanceof Number ? ((Number) map.get("mapId")).intValue()
					: at == null ? -1 : tileMap.applyAsInt(at.getX(), at.getY());
				if (at != null && !name.isEmpty() && mapId >= 0 && entries.size() < MAX_RESULTS
						&& (!placed || SearchIndex.inPlace(mapId, at.getX() + 0.5, at.getY() + 0.5, parsed, places)))
				{
					SearchEntry entry = new SearchEntry(resultKind(Wire.label(map.get("kind"), 12)), name, Wire.text(map.get("detail")), mapId,
						at.getX() + 0.5, at.getY() + 0.5, marks(Arrays.asList(Wire.list(map.get("marks")))));
					entry.setReveal(Wire.label(map.get("reveal"), 40).isEmpty() ? null : Wire.label(map.get("reveal"), 40));
					entry.setAliases(Wire.strings(map.get("aliases"), MAX_ALIASES));
					entries.add(entry);
					data.put(entry, new Object[] {slot, describe("search", name, at, Wire.label(map.get("id"), 64))});
				}
			}
		}
		List<SearchEntry> ranked = new ArrayList<>(new SearchIndex(entries).search(query, MAX_RESULTS));
		entries.stream().filter(e -> !ranked.contains(e)).forEach(ranked::add);
		return Collections.unmodifiableList(ranked);
	}

	/** The kinds of result a partner may name for what it found; any other word is a plain {@code PARTNER} result. */
	private static final SearchKind[] RESULT_KINDS = {SearchKind.KIND, SearchKind.MONSTER, SearchKind.NPC, SearchKind.ITEM, SearchKind.MUSIC,
		SearchKind.QUEST, SearchKind.SHOP};

	private static SearchKind resultKind(String name)
	{
		for (SearchKind kind : RESULT_KINDS)
		{
			if (kind.name().equals(name))
			{
				return kind;
			}
		}
		return SearchKind.PARTNER;
	}

	/** Tells the plugin that gave a search result that the player chose it, on the client thread. */
	public void selected(SearchEntry entry)
	{
		Object[] known;
		synchronized (this)
		{
			known = foundData.get(entry);
		}
		if (known != null && ((Slot) known[0]).more != null)
		{
			tell(toClient, (Slot) known[0], ((Slot) known[0]).more, known[1]);
		}
	}

	// ------------------------------------------------------------------
	// Once a client tick: pushed state, pings, silence, and the questions
	// ------------------------------------------------------------------

	/** The palette as a map of role name to colour. */
	public static Map<String, Object> themeData()
	{
		Palette palette = Palettes.current();
		Map<String, Color> colors = new LinkedHashMap<>();
		for (Role role : Role.values())
		{
			colors.put(role.name(), palette.get(role));
		}
		return Wire.map("name", palette.name(), "colors", Collections.unmodifiableMap(colors), "edgeWidth", palette.edgeWidth());
	}

	public synchronized void themeChanged()
	{
		if (!owners.isEmpty())
		{
			push("theme", themeData());
		}
	}

	/** Tells partners what the icons and cards ask of the player: the table of {@link Need}s, in the order of their numbers. */
	private void pushNeeds()
	{
		List<List<Object>> table = Need.table();
		seenNeeds = table.size();
		push("needs", Wire.map("needs", table));
	}

	private void pushBusy(boolean busy)
	{
		busyShown = busy;
		push("chat_busy", Wire.map("busy", busy));
	}

	/**
	 * Called once a client tick: answers the questions that wait, tells partners the saved places changed and whether this plugin's
	 * search has the chat area, asks whether they are still there every 30 seconds, and lets go of those silent for 95.
	 */
	public synchronized void tick(long now)
	{
		answerAsks(now);
		if (owners.isEmpty())
		{
			return;
		}
		welcomeOwed(now);
		tellPartners(now);
	}

	/** Handles the questions that wait, dropping those too old to answer. */
	synchronized void answerAsks(long now)
	{
		for (Iterator<Ask> it = asks.iterator(); it.hasNext(); )
		{
			Ask ask = it.next();
			if (now - ask.at > ExternalOverlays.ANSWER_LIFETIME_MILLIS || answer(ask))
			{
				it.remove();
			}
		}
	}

	private void tellPartners(long now)
	{
		if (pins != null && routes != null)
		{
			int pinsNow = pins.getVersion();
			int routesNow = routes.getVersion();
			if (seenPins >= 0 && (pinsNow != seenPins || routesNow != seenRoutes))
			{
				push("saved_changed", Wire.map("pins", pinsNow != seenPins, "routes", routesNow != seenRoutes));
			}
			seenPins = pinsNow;
			seenRoutes = routesNow;
		}
		if (chatBusy.getAsBoolean() != busyShown)
		{
			pushBusy(!busyShown);
		}
		if (Need.count() != seenNeeds)
		{
			pushNeeds();
		}
		if (now - lastPing >= PING_MILLIS)
		{
			lastPing = now;
			outer.say("ping", new HashMap<>());
		}
		for (String owner : new ArrayList<>(owners.keySet()))
		{
			if (clock.getAsLong() - owners.get(owner).at > SILENT_MILLIS)
			{
				drop(owner, "silent");
			}
		}
	}

	/** Handles one question on the client thread. False when it must wait (coverage before the maps are loaded). */
	private boolean answer(Ask ask)
	{
		Map<String, Object> data = ask.data;
		Map<String, Object> answer = new HashMap<>();
		String reason = "ok";
		switch (ask.topic)
		{
			case "coverage":
				ExternalOverlays.Coverage maps = coverage;
				List<WorldPoint> points = data.get("points") == null ? Wire.tiles(Collections.singletonList(data.get("point")), 1)
					: Wire.tiles(data.get("points"), MAX_COVER_POINTS);
				if (maps == null)
				{
					return false;
				}
				List<Map<String, Object>> tiles = new ArrayList<>();
				for (WorldPoint point : points)
				{
					ExternalOverlays.Cover cover = maps.at(point.getX(), point.getY());
					tiles.add(Wire.map("covered", cover.isCovered(), "exact", cover.isExact(), "mapName", cover.getMapName(), "mapKind", cover.getMapKind()));
				}
				answer.put("tiles", tiles);
				reason = points.isEmpty() ? "bad_request" : reason;
				break;
			case "focus":
				outer.setFocus(ask.id, data);
				return true;
			case "follow":
				outer.setFollow(data);
				return true;
			case "show":
				show(data);
				return true;
			case "go":
				WorldPoint at = Wire.point(data.get("point"));
				reason = at == null ? "bad_request" : goer.apply(at);
				break;
			default:
				try
				{
					reason = pins == null || routes == null ? "not_ready" : saved(ask.topic, data, answer);
				}
				catch (RuntimeException e)
				{
					reason = "failed";
				}
				break;
		}
		reply(ask.id, ask.topic, reason, answer);
		return true;
	}

	/** Keeps a request to take the map to a place, for the surface it names to apply on its next frame. */
	private void show(Map<String, Object> data)
	{
		double x = Wire.number(data.get("x"));
		double y = Wire.number(data.get("y"));
		if (data.get("surface") instanceof Number && data.get("mapId") instanceof Number && !Double.isNaN(x) && !Double.isNaN(y))
		{
			pendingShow = new Show(((Number) data.get("surface")).intValue(), ((Number) data.get("mapId")).intValue(), x, y,
				Wire.text(data.get("name")), Wire.text(data.get("detail")), marks(Arrays.asList(Wire.list(data.get("marks")))));
		}
	}

	// ------------------------------------------------------------------
	// Putting a route's stops in another order: walking lengths, the new order, and the undo the panel offers
	// ------------------------------------------------------------------

	/** Changes whenever the order that can be undone changes, so the panel builds its route cards again. */
	public int orderVersion()
	{
		return orderVersion;
	}

	/** True when a partner said that this route, with exactly these stops, has an order that can be put back. */
	public boolean undoable(long routeId, List<Long> stops)
	{
		return routeId == undoRoute && !undoStops.isEmpty() && undoStops.equals(stops);
	}

	private void forgetOrder()
	{
		undoRoute = 0;
		undoStops = Collections.emptyList();
		orderVersion++;
	}

	/**
	 * Tells partners the route being shown, as steps of {x, y, toX, toY, fromFloor, toFloor, walk (1 or 0), tiles}, for the Helper to draw
	 * the walks between the jumps. An empty list says there is no route. Sent again only when it changed (the caller checks), at most once a
	 * client tick.
	 */
	public void route(List<double[]> steps)
	{
		synchronized (this)
		{
			if (!owners.isEmpty())
			{
				push("route", Wire.map("steps", steps));
			}
		}
	}

	// ------------------------------------------------------------------
	// The saved places: the snapshot, saving places and routes, and a new order of a route's stops
	// ------------------------------------------------------------------

	/** Answers the questions about saved places: saved, save_pins, save_route and route_order. The reason. */
	private String saved(String topic, Map<String, Object> ask, Map<String, Object> answer)
	{
		if ("saved".equals(topic))
		{
			String what = Wire.label(ask.get("what"), 8);
			answer.put("pins", "routes".equals(what) ? null : places(null, true));
			answer.put("routes", "pins".equals(what) ? null : places(null, false));
			return "ok";
		}
		if ("route_order".equals(topic))
		{
			List<Long> before = Wire.longs(ask.get("expect"), RouteStore.MAX_STOPS);
			List<Long> after = Wire.longs(ask.get("stops"), RouteStore.MAX_STOPS);
			if (before == null || after == null || before.isEmpty() || !(ask.get("route") instanceof Number))
			{
				return "bad_request";
			}
			if (!new ArrayList<>(before).stream().sorted().collect(java.util.stream.Collectors.toList())
				.equals(after.stream().sorted().collect(java.util.stream.Collectors.toList())))
			{
				return "bad_stop";
			}
			long routeId = ((Number) ask.get("route")).longValue();
			synchronized (routes)
			{
				SavedRoute route = routes.get(routeId);
				if (route == null || !route.getStops().equals(before))
				{
					return "changed";
				}
				return routes.update(routeId, route.getName(), after, route.isLoop()) ? "ok" : "failed";
			}
		}
		// save_pins and save_route: a place for each point, one the player has at the same tile, floor and map used again, on the map
		// asked for when it holds the tile, else (none asked for) the map the tile is on. A route is saved whole or not at all.
		boolean route = "save_route".equals(topic);
		List<WorldPoint> tiles = Wire.tiles(ask.get("points"), MAX_SHARED);
		Object[] maps = Wire.list(ask.get("maps"));
		Object[] names = Wire.list(ask.get("names"));
		String kind = Wire.label(ask.get("kind"), 16);
		int[] onMap = new int[tiles.size()];
		Set<List<Integer>> fresh = new HashSet<>();
		for (int i = 0; i < tiles.size(); i++)
		{
			WorldPoint at = tiles.get(i);
			int asked = i < maps.length && maps[i] instanceof Number ? ((Number) maps[i]).intValue() : -1;
			onMap[i] = asked < 0 ? tileMap.applyAsInt(at.getX(), at.getY()) : mapHolds.holds(asked, at.getX(), at.getY()) ? asked : -1;
			if (onMap[i] >= 0 && existing(at, onMap[i]) == null)
			{
				fresh.add(Arrays.asList(at.getX(), at.getY(), at.getPlane(), onMap[i]));
			}
			else if (route && onMap[i] < 0)
			{
				answer.put("stop", i + 1);
				return "no_map";
			}
		}
		answer.putAll(route ? Wire.map("missing", fresh.size(), "max", RouteStore.MAX_STOPS) : Collections.emptyMap());
		String refused = tiles.isEmpty() ? "bad_request" : !route ? null : tiles.size() > RouteStore.MAX_STOPS ? "too_many_stops"
			: routes.all().size() >= RouteStore.MAX_ROUTES ? "routes_full" : pins.all().size() + fresh.size() > PinStore.MAX_PINS ? "places_full" : null;
		if (refused != null)
		{
			return refused;
		}
		List<Long> ids = new ArrayList<>();
		int added = 0;
		for (int i = 0; i < tiles.size(); i++)
		{
			WorldPoint at = tiles.get(i);
			Pin pin = onMap[i] < 0 ? null : existing(at, onMap[i]);
			String given = i < names.length ? Wire.text(names[i]) : "";
			if (pin == null && onMap[i] >= 0)
			{
				pin = pins.add(given.isEmpty() ? "Pin at " + at.getX() + ", " + at.getY() : given, kind.isEmpty() ? "pin" : kind, onMap[i],
					at.getX() + 0.5, at.getY() + 0.5, at.getPlane());
				added += pin == null ? 0 : 1;
				if (pin == null)
				{
					break;
				}
			}
			ids.add(pin == null ? null : pin.getId());
		}
		int skipped = Collections.frequency(ids, null);
		if (!route)
		{
			answer.putAll(Wire.map("pinIds", ids, "added", added, "skipped", skipped, "had", ids.size() - added - skipped,
				"full", ids.size() < tiles.size()));
			return "ok";
		}
		String name = Wire.text(ask.get("name"));
		SavedRoute made = ids.size() < tiles.size() ? null : routes.create(name.isEmpty() ? "Shared route" : name, ids, Boolean.TRUE.equals(ask.get("loop")));
		answer.putAll(Wire.map("stops", tiles.size(), "routeId", made == null ? null : made.getId()));
		return ids.size() < tiles.size() ? "full" : made == null ? "routes_full" : "ok";
	}

	@Nullable
	private Pin existing(WorldPoint at, int map)
	{
		for (Pin pin : pins.all())
		{
			if (pin.getMap() == map && pin.getPlane() == at.getPlane() && (int) Math.floor(pin.getX()) == at.getX()
				&& (int) Math.floor(pin.getY()) == at.getY())
			{
				return pin;
			}
		}
		return null;
	}
}
