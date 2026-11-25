package com.worldmapplus.bridge;

import java.awt.*;
import java.awt.datatransfer.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.coords.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.plugins.*;

/**
 * The things the map can do for the player outside itself.
 * <p>
 * Routing is a {@link PluginMessage} to the Shortest Path plugin, whose contract is namespace
 * {@code shortestpath}, name {@code path} with {@code start} and {@code target}, and name
 * {@code clear}. If that plugin is not installed nothing subscribes and the post does nothing.
 * The message only asks that plugin to draw its own path; it never reaches the game server.
 */
@Slf4j
@Singleton
public class MapActions
{
	private static final String SHORTEST_PATH = "shortestpath";

	private static final String SHORTEST_PATH_NAME = "Shortest Path";

	/** Whether the Shortest Path plugin is there to answer a route request. */
	public enum Status
	{
		/** It could not be checked, so the request is sent anyway. */
		UNKNOWN,
		MISSING,
		DISABLED,
		READY
	}

	private final EventBus eventBus;
	private final ExternalOverlays external;
	private final PluginManager plugins;
	private volatile WorldPoint routeTarget;
	/** Every target of the route, the first being {@link #routeTarget}. Shortest Path goes to whichever is nearest. */
	@Getter
	private volatile List<WorldPoint> routeTargets = Collections.emptyList();
	/** Where the route was set to start from by hand, or null for from where the player stands. */
	private volatile WorldPoint routeStart;
	/** How many targets a route may have. */
	public static final int MAX_TARGETS = 8;
	/** How close, in tiles, counts as having arrived at a route's target. */
	public static final int ARRIVED_TILES = 3;

	/** Runs a task a little later. The plugin sets it to the client thread's queue. Tests leave it immediate. */
	private volatile Consumer<Runnable> later = Runnable::run;

	/** False once the plugin has stopped, so a request put off earlier is not sent after that. */
	private volatile boolean active = true;

	/** Goes up on every Shortest Path message, so a task put off earlier can tell it has been overtaken. */
	private final AtomicInteger generation = new AtomicInteger();
	private final AtomicInteger othersRequests = new AtomicInteger();

	@Inject
	public MapActions(EventBus eventBus, ExternalOverlays external, PluginManager plugins)
	{
		this.eventBus = eventBus;
		this.external = external;
		this.plugins = plugins;
	}

	/** For tests and previews, where there is no plugin manager to ask. */
	public MapActions(EventBus eventBus, ExternalOverlays external)
	{
		this(eventBus, external, null);
	}

	/** Looks for the Shortest Path plugin among the loaded ones. */
	public Status shortestPathStatus()
	{
		if (plugins == null)
		{
			return Status.UNKNOWN;
		}
		for (Plugin plugin : plugins.getPlugins())
		{
			if (SHORTEST_PATH_NAME.equals(plugin.getName()))
			{
				return plugins.isPluginEnabled(plugin) ? Status.READY : Status.DISABLED;
			}
		}
		return Status.MISSING;
	}

	/**
	 * Asks Shortest Path for a route from where the player stands to a tile. Nothing is sent when that
	 * plugin is absent or off, and the status says which, so the map can tell the player.
	 */
	public Status routeTo(WorldPoint target)
	{
		if (target == null)
		{
			return Status.UNKNOWN;
		}
		return send(null, Collections.singletonList(target));
	}

	/**
	 * Sets the target of the route, as Shortest Path's own "Set Target" does on its map: a start that was set by
	 * hand is kept, and any other targets are replaced.
	 */
	public Status retarget(WorldPoint target)
	{
		if (target == null)
		{
			return Status.UNKNOWN;
		}
		return send(routeStart, Collections.singletonList(target));
	}

	/** Adds a target to the route, as "Set Target 2" does. Does nothing when there is no route yet. */
	public Status addTarget(WorldPoint target)
	{
		List<WorldPoint> now = routeTargets;
		if (target == null || now.isEmpty() || now.size() >= MAX_TARGETS || now.contains(target))
		{
			return Status.UNKNOWN;
		}
		List<WorldPoint> more = new ArrayList<>(now);
		more.add(target);
		return send(routeStart, more);
	}

	/**
	 * Makes the route start from a tile chosen by hand instead of from the player, as "Set Start" does. Does
	 * nothing when there is no route yet. The route stays as Shortest Path found it for that start, and does not
	 * start again from the player as they walk, until it is cleared or a new route is asked for.
	 */
	public Status setStart(WorldPoint start)
	{
		List<WorldPoint> now = routeTargets;
		if (start == null || now.isEmpty())
		{
			return Status.UNKNOWN;
		}
		return send(start, now);
	}

	private Status send(@Nullable WorldPoint start, List<WorldPoint> targets)
	{
		Status status = shortestPathStatus();
		if (status == Status.MISSING || status == Status.DISABLED)
		{
			return status;
		}
		Map<String, Object> data = new HashMap<>();
		data.put("start", start);
		data.put("target", targets.size() == 1 ? targets.get(0) : new LinkedHashSet<>(targets));
		data.put(OURS, Boolean.TRUE);
		// Shortest Path takes a temporary override of its own settings with a request, which it drops
		// again on "clear". It makes that plugin send the transports of the route, which is how the
		// jumps get onto this map. Its saved settings are not touched.
		Map<String, Object> override = new HashMap<>();
		override.put("postTransports", Boolean.TRUE);
		data.put("config", override);
		setRoute(start, targets);
		eventBus.post(new PluginMessage(SHORTEST_PATH, "path", data));
		return status;
	}

	/**
	 * Remembers a route. One that starts where the player is walks with them, so Shortest Path's changes to it are
	 * followed. One that starts at a chosen tile keeps the jumps Shortest Path first sent for it, since that plugin
	 * goes back to starting from the player when they walk away from the route.
	 */
	private void setRoute(@Nullable WorldPoint start, List<WorldPoint> targets)
	{
		routeStart = start;
		routeTargets = Collections.unmodifiableList(new ArrayList<>(targets));
		routeTarget = targets.isEmpty() ? null : targets.get(0);
		if (start == null)
		{
			external.releaseHops();
		}
		else
		{
			external.holdFirstHops();
		}
	}

	/** Marks a request this plugin sent itself, so it is not mistaken for another plugin's when it comes back. */
	static final String OURS = "worldmapplus";

	/**
	 * Watches the Shortest Path messages that go by, so a route another plugin asks for, such as the
	 * companion's group list or Quest Helper, shows on this map as well.
	 * <p>
	 * A request names a target, which is remembered so the map can mark it. If the request did not ask
	 * Shortest Path to send the transports of the route, it is sent once more with that one setting added
	 * and the sender's own settings kept, so the jumps can be drawn. That setting is a temporary override
	 * Shortest Path drops again on "clear". A request this plugin sent is recognised and left alone.
	 */
	public void onPluginMessage(PluginMessage message)
	{
		onPluginMessage(message, true);
	}

	/** Tells this that the plugin is running or has stopped. A request put off for later goes out only while running. */
	public void setActive(boolean active)
	{
		this.active = active;
	}

	/** Lets the plugin choose how the second request is delivered. See {@link #onPluginMessage}. */
	public void setLater(Consumer<Runnable> later)
	{
		this.later = later == null ? Runnable::run : later;
	}

	/**
	 * @param mirror whether to mark the target, draw the jumps and ask for the transports. A request from
	 *     another plugin is counted either way, so a saved route being followed gives way to it.
	 */
	public void onPluginMessage(PluginMessage message, boolean mirror)
	{
		if (message == null || !SHORTEST_PATH.equals(message.getNamespace()))
		{
			return;
		}
		String name = message.getName();
		Map<String, Object> data = message.getData();
		if (data == null && !"clear".equals(name))
		{
			return;
		}
		boolean ours = data != null && data.containsKey(OURS);
		generation.incrementAndGet();
		if ("clear".equals(name))
		{
			if (!ours)
			{
				othersRequests.incrementAndGet();
			}
			if (ours || mirror)
			{
				forgetRoute();
				external.clearHops();
			}
			return;
		}
		if (!"path".equals(name) || ours)
		{
			return;
		}
		othersRequests.incrementAndGet();
		if (!mirror)
		{
			return;
		}
		List<WorldPoint> targets = targetsOf(data.get("target"));
		WorldPoint start = startOf(data.get("start"));
		if (targets.isEmpty())
		{
			// A request with a start and no target moves the start of the route Shortest Path already has.
			targets = routeTargets;
			if (targets.isEmpty() || start == null)
			{
				return;
			}
		}
		setRoute(start, targets);
		Object config = data.get("config");
		if (config instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) config).get("postTransports")))
		{
			return;
		}
		Map<String, Object> again = new HashMap<>(data);
		Map<String, Object> merged = new HashMap<>();
		if (config instanceof Map)
		{
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) config).entrySet())
			{
				if (entry.getKey() instanceof String)
				{
					merged.put((String) entry.getKey(), entry.getValue());
				}
			}
		}
		merged.put("postTransports", Boolean.TRUE);
		again.put("config", merged);
		again.put(OURS, Boolean.TRUE);
		// The event bus calls subscribers in order of class name, ours before Shortest Path's, and Shortest Path
		// replaces its settings override with each request that carries one. Posting from inside this handler
		// would put our copy first and the original, with its own settings, after it. So the copy goes out after
		// the original has been delivered, and only if nothing else has been sent in between.
		int mine = generation.get();
		later.accept(() ->
		{
			if (active && generation.get() == mine)
			{
				eventBus.post(new PluginMessage(SHORTEST_PATH, "path", again));
			}
		});
	}

	/** The tile a request aims at: a WorldPoint, one of a set of them, or Shortest Path's packed number. */
	@Nullable
	static WorldPoint targetOf(@Nullable Object value)
	{
		if (value instanceof WorldPoint)
		{
			return (WorldPoint) value;
		}
		if (value instanceof Integer)
		{
			int packed = (Integer) value;
			if (packed == -1)
			{
				return null;
			}
			return new WorldPoint(packed & 0x7FFF, (packed >> 15) & 0x7FFF, (packed >> 30) & 0x3);
		}
		if (value instanceof Collection)
		{
			for (Object each : (Collection<?>) value)
			{
				WorldPoint found = targetOf(each);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** Every tile a request aims at, in order, without repeats. */
	static List<WorldPoint> targetsOf(@Nullable Object value)
	{
		List<WorldPoint> found = new ArrayList<>();
		collect(value, found);
		return found;
	}

	private static void collect(@Nullable Object value, List<WorldPoint> found)
	{
		if (found.size() >= MAX_TARGETS)
		{
			return;
		}
		if (value instanceof Collection)
		{
			for (Object each : (Collection<?>) value)
			{
				collect(each, found);
			}
			return;
		}
		WorldPoint point = targetOf(value);
		if (point != null && !found.contains(point))
		{
			found.add(point);
		}
	}

	/** Where a request says the route starts, or null when it starts from the player. */
	@Nullable
	static WorldPoint startOf(@Nullable Object value)
	{
		return value instanceof Collection ? null : targetOf(value);
	}

	private void forgetRoute()
	{
		routeTarget = null;
		routeTargets = Collections.emptyList();
		routeStart = null;
	}

	/** The tile the route was set to start from by hand, or null when it starts from the player. */
	@Nullable
	public WorldPoint getRouteStart()
	{
		return routeStart;
	}

	/** Where the last route request went, or null when there is none. */
	@Nullable
	public WorldPoint getRouteTarget()
	{
		return routeTarget;
	}

	/** Forgets the route when the player has reached any of its targets. Called once a game tick. */
	public void checkArrived(@Nullable WorldPoint player)
	{
		if (player == null)
		{
			return;
		}
		for (WorldPoint target : routeTargets)
		{
			if (player.getPlane() == target.getPlane() && player.distanceTo2D(target) <= ARRIVED_TILES)
			{
				routeArrived();
				return;
			}
		}
	}

	/** Forgets the target and its jumps without telling Shortest Path, for when the player has got there. */
	public void routeArrived()
	{
		forgetRoute();
		external.clearHops();
	}

	/** Clears any route Shortest Path is drawing, and the hops this map drew for it. */
	public void clearRoute()
	{
		forgetRoute();
		Map<String, Object> mine = new HashMap<>();
		mine.put(OURS, Boolean.TRUE);
		eventBus.post(new PluginMessage(SHORTEST_PATH, "clear", mine));
		external.clearHops();
	}

	/**
	 * A number that goes up each time a plugin other than this one asks Shortest Path for a route or
	 * clears one. A saved route being followed watches it, and gives way when it changes.
	 */
	public int othersRequests()
	{
		return othersRequests.get();
	}

	/** Puts text on the system clipboard. A refusal from the system is ignored. */
	public void copy(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (RuntimeException e)
		{
			log.debug("Could not use the clipboard", e);
		}
	}
}
