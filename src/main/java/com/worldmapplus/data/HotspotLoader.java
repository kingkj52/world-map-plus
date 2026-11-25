package com.worldmapplus.data;

import com.google.gson.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.map.*;
import com.worldmapplus.search.*;
import com.worldmapplus.unlock.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * Turns the bundled reference data into hotspots: the places the mouse can point at to get a card.
 * <p>
 * Everything here is static data generated at build time by the tools in {@code tools/}. Nothing
 * is fetched while the plugin runs.
 */
@Slf4j
public final class HotspotLoader
{
	private static final String ENTRANCES = "/com/worldmapplus/entrances.json";
	private static final String TRANSPORTS = "/com/worldmapplus/transports.json";
	private static final String TELEPORTS = "/com/worldmapplus/teleports.json";

	/** Pixel radius of a doorway or a transport marker. */
	private static final int RADIUS = 11;

	/** Rows a card shows before the rest wait for Shift. */
	public static final int MAX_ROWS = 14;

	/** Lines a transport draws to its destinations while hovered, so a big network does not become a web. */
	static final int MAX_LINKS = 20;

	/** Origins of the same kind this close together are one stop, such as the tiles around a ship. */
	private static final double CLUSTER_TILES = 6.0;

	private HotspotLoader()
	{
	}

	/** Loads everything bundled. Anything missing or damaged is skipped with a log line. */
	public static List<Hotspot> load(Gson gson, MapCatalog catalog)
	{
		List<Hotspot> hotspots = new ArrayList<>();
		EntranceFile entrances = read(gson, ENTRANCES, EntranceFile.class);
		if (entrances != null)
		{
			hotspots.addAll(entrances(entrances, catalog));
		}
		TransportFile transports = read(gson, TRANSPORTS, TransportFile.class);
		if (transports != null)
		{
			hotspots.addAll(transports(transports, catalog));
		}
		TeleportFile teleports = read(gson, TELEPORTS, TeleportFile.class);
		if (teleports != null)
		{
			hotspots.addAll(teleports(teleports, catalog));
		}
		return hotspots;
	}

	@Nullable
	private static <T> T read(Gson gson, String resource, Class<T> type)
	{
		try (InputStream in = HotspotLoader.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				log.warn("World Map Plus: {} is missing from the plugin jar", resource);
				return null;
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("World Map Plus: could not read {}", resource, e);
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Doorways
	// ------------------------------------------------------------------

	public static List<Hotspot> entrances(Reader reader, Gson gson, MapCatalog catalog)
	{
		EntranceFile file = gson.fromJson(reader, EntranceFile.class);
		return file == null ? new ArrayList<>() : entrances(file, catalog);
	}

	static List<Hotspot> entrances(EntranceFile file, MapCatalog catalog)
	{
		List<Hotspot> result = new ArrayList<>();
		if (file.links == null)
		{
			return result;
		}
		for (EntranceLink link : file.links)
		{
			if (link == null || link.a == null)
			{
				continue;
			}
			if (link.b == null && isShadow(link, file.links))
			{
				// The same doorway is also listed with a far end. The plain entry would sit over it and hide
				// its preview and its click.
				continue;
			}
			MapInfo mapA = catalog.byId(link.a.map);
			if (mapA == null)
			{
				continue;
			}
			if (link.b == null)
			{
				// An entrance whose other end has no imagery: it can be found and named, not followed.
				String name = link.name == null || link.name.isEmpty() ? "Entrance" : link.name;
				HoverCard card = HoverCard.builder(name).subtitle("Leads underground").build();
				result.add(new Hotspot(mapA.getId(), link.a.x + 0.5, link.a.y + 0.5, RADIUS, card, null));
				continue;
			}
			MapInfo mapB = catalog.byId(link.b.map);
			if (mapB == null)
			{
				continue;
			}
			String name = link.name == null || link.name.isEmpty() ? mapB.getName() : link.name;
			result.add(doorway(name, mapA, link.a, mapB, link.b, "Leads to "));
			result.add(doorway(name, mapB, link.b, mapA, link.a, "Leads to "));
		}
		return result;
	}

	/** Distance, in tiles, within which two entries are the same doorway. */
	private static final int SAME_DOORWAY_TILES = 2;

	/** True when another entry that has a far end has either of its ends at this one way entry's spot. */
	private static boolean isShadow(EntranceLink one, List<EntranceLink> all)
	{
		for (EntranceLink other : all)
		{
			if (other == null || other == one || other.a == null || other.b == null)
			{
				continue;
			}
			if (near(other.a, one.a) || near(other.b, one.a))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean near(Point first, Point second)
	{
		return first.map == second.map && Math.abs(first.x - second.x) <= SAME_DOORWAY_TILES
			&& Math.abs(first.y - second.y) <= SAME_DOORWAY_TILES;
	}

	private static Hotspot doorway(String name, MapInfo from, Point here, MapInfo to, Point there, String prefix)
	{
		HoverCard card = HoverCard.builder(name)
			.subtitle(prefix + to.getName())
			.preview(to.getId(), there.x + 0.5, there.y + 0.5)
			.hint("Click to open this map")
			.build();
		return new Hotspot(from.getId(), here.x + 0.5, here.y + 0.5, RADIUS, card,
			new Hotspot.Target(to.getId(), there.x + 0.5, there.y + 0.5));
	}

	// ------------------------------------------------------------------
	// Transports
	// ------------------------------------------------------------------

	public static List<Hotspot> transports(Reader reader, Gson gson, MapCatalog catalog)
	{
		TransportFile file = gson.fromJson(reader, TransportFile.class);
		return file == null ? new ArrayList<>() : transports(file, catalog);
	}

	static List<Hotspot> transports(TransportFile file, MapCatalog catalog)
	{
		List<Hotspot> result = new ArrayList<>();
		if (file.routes != null)
		{
			result.addAll(routes(file.routes, catalog));
		}
		if (file.networks != null)
		{
			for (Network network : file.networks)
			{
				if (network != null && network.nodes != null)
				{
					result.addAll(network(network, catalog));
				}
			}
		}
		return result;
	}

	/** One hotspot per departure point per kind, listing everywhere it goes. */
	private static List<Hotspot> routes(List<Route> routes, MapCatalog catalog)
	{
		// Group by kind, then by cluster of nearby origins.
		Map<String, List<Stop>> stopsByKind = new LinkedHashMap<>();
		for (Route route : routes)
		{
			if (route == null || route.from == null || route.to == null || route.from.length < 2
				|| route.to.length < 2 || route.kind == null)
			{
				continue;
			}
			List<Stop> stops = stopsByKind.computeIfAbsent(route.kind, k -> new ArrayList<>());
			Stop stop = null;
			for (Stop candidate : stops)
			{
				if (Math.hypot(candidate.x - route.from[0], candidate.y - route.from[1]) <= CLUSTER_TILES)
				{
					stop = candidate;
					break;
				}
			}
			if (stop == null)
			{
				stop = new Stop(route.from[0], route.from[1]);
				stops.add(stop);
			}
			stop.routes.add(route);
		}

		List<Hotspot> result = new ArrayList<>();
		for (Map.Entry<String, List<Stop>> entry : stopsByKind.entrySet())
		{
			for (Stop stop : entry.getValue())
			{
				MapInfo map = catalog.mapFor(stop.x, stop.y);
				if (map == null)
				{
					continue;
				}
				result.add(routeHotspot(entry.getKey(), stop, map, catalog));
			}
		}
		return result;
	}

	private static Hotspot routeHotspot(String kind, Stop stop, MapInfo map, MapCatalog catalog)
	{
		String place = stop.routes.get(0).place;
		for (Route route : stop.routes)
		{
			if (!Objects.equals(place, route.place))
			{
				place = null;
				break;
			}
		}
		String title = place == null || place.isEmpty() ? kind : kind + ": " + place;
		HoverCard.Builder card = HoverCard.builder(title).subtitle("Goes to").wiki("transport", kind);
		List<Hotspot.Link> links = new ArrayList<>();
		// One row for each place it goes to. A place with several ways of getting there, such as for coins or free, has them
		// all on its row.
		Map<String, List<Route>> byLabel = new LinkedHashMap<>();
		for (Route route : stop.routes)
		{
			String label = route.label == null || route.label.isEmpty() ? "Somewhere" : route.label;
			byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(route);
		}
		int shown = 0;
		for (Map.Entry<String, List<Route>> destination : byLabel.entrySet())
		{
			String label = destination.getKey();
			List<String> costs = new ArrayList<>();
			List<String> quests = new ArrayList<>();
			for (Route route : destination.getValue())
			{
				String cost = route.cost == null || route.cost.isEmpty() ? "free" : route.cost;
				if (!costs.contains(cost))
				{
					costs.add(cost);
				}
				for (String quest : needWords(route.quests, route.requires))
				{
					if (!quests.contains(quest))
					{
						quests.add(quest);
					}
				}
			}
			// Destinations past the first few wait in the overflow, which holding Shift opens in full.
			boolean overflow = shown >= MAX_ROWS;
			shown++;
			addRow(card, overflow, label, String.join(" or ", costs));
			if (!quests.isEmpty())
			{
				addRow(card, overflow, "   needs", String.join(", ", quests));
			}
			Route first = destination.getValue().get(0);
			MapInfo destinationMap = catalog.mapFor(first.to[0], first.to[1]);
			if (destinationMap != null && links.size() < MAX_LINKS)
			{
				links.add(new Hotspot.Link(destinationMap.getId(), first.to[0] + 0.5, first.to[1] + 0.5, label));
			}
		}
		// A stop with one destination, such as a cart from Grand Exchange to Keldagrim, can be followed
		// with a click, which takes the map to where it goes.
		Hotspot.Target target = null;
		if (links.size() == 1)
		{
			Hotspot.Link only = links.get(0);
			target = new Hotspot.Target(only.getMapId(), only.getX(), only.getY());
			card.hint("Click to see where it arrives");
		}
		// The stop can be used when any one of its routes can, so it is only out of reach when every one of them is.
		Needs.Ways ways = new Needs.Ways();
		for (Route route : stop.routes)
		{
			ways.add(route.skills, route.quests, route.requires);
		}
		return new Hotspot(map.getId(), stop.x + 0.5, stop.y + 0.5, RADIUS, card.build(), target, links).withNeed(ways.build());
	}

	private static List<Hotspot> network(Network network, MapCatalog catalog)
	{
		List<Hotspot> result = new ArrayList<>();
		String kind = network.kind == null ? "Transport" : network.kind;
		for (Node node : network.nodes)
		{
			if (node == null)
			{
				continue;
			}
			MapInfo map = catalog.mapFor(node.x, node.y);
			if (map == null)
			{
				continue;
			}
			String title = node.name == null || node.name.isEmpty() ? kind : kind + ": " + node.name;
			HoverCard.Builder card = HoverCard.builder(title).wiki("transport", kind);

			List<String> others = new ArrayList<>();
			for (Node other : network.nodes)
			{
				if (other != null && other != node && other.name != null && !other.name.isEmpty())
				{
					others.add(other.name);
				}
			}
			List<String> needs = needWords(node.quests, node.requires);
			if (!needs.isEmpty())
			{
				card.subtitle("Needs " + String.join(", ", needs));
			}
			if (!others.isEmpty())
			{
				card.subtitle(!needs.isEmpty()
					? "Needs " + String.join(", ", needs)
					: "Connects to " + others.size() + " other " + kind.toLowerCase() + "s");
				int listed = 0;
				for (String other : others)
				{
					addRow(card, listed++ >= MAX_ROWS, other, "");
				}
			}

			List<Hotspot.Link> links = new ArrayList<>();
			if (others.size() <= MAX_ROWS)
			{
				for (Node other : network.nodes)
				{
					if (other != null && other != node && other.name != null && !other.name.isEmpty())
					{
						MapInfo otherMap = catalog.mapFor(other.x, other.y);
						if (otherMap != null)
						{
							links.add(new Hotspot.Link(otherMap.getId(), other.x + 0.5, other.y + 0.5, other.name));
						}
					}
				}
			}
			result.add(new Hotspot(map.getId(), node.x + 0.5, node.y + 0.5, RADIUS, card.build(), null, links)
				.withNeed(Needs.ofWay(null, node.quests, node.requires)));
		}
		return result;
	}

	// ------------------------------------------------------------------
	// Teleport destinations
	// ------------------------------------------------------------------

	/** The layer the teleport destinations are shown under, which also gates their cards. */
	static final String TELEPORT_LAYER = "teleport";

	public static List<Hotspot> teleports(Reader reader, Gson gson, MapCatalog catalog)
	{
		TeleportFile file = gson.fromJson(reader, TeleportFile.class);
		return file == null ? new ArrayList<>() : teleports(file, catalog);
	}

	/**
	 * One card per destination listing every way to arrive there. Teleports that land on a point the
	 * wiki already shows share that point, so the card sits on its icon. Destinations without one also
	 * get a plain marker, so the layer shows them.
	 */
	static List<Hotspot> teleports(TeleportFile file, MapCatalog catalog)
	{
		List<Hotspot> result = new ArrayList<>();
		if (file.teleports == null)
		{
			return result;
		}
		List<Destination> destinations = new ArrayList<>();
		for (Teleport teleport : file.teleports)
		{
			if (teleport == null || teleport.name == null || teleport.name.isEmpty())
			{
				continue;
			}
			boolean snapped = teleport.snap != null && teleport.snap.length >= 2;
			int x = snapped ? teleport.snap[0] : teleport.x;
			int y = snapped ? teleport.snap[1] : teleport.y;
			Destination found = null;
			for (Destination candidate : destinations)
			{
				boolean same = snapped
					? candidate.snapped && candidate.x == x && candidate.y == y
					: !candidate.snapped && Math.hypot(candidate.x - x, candidate.y - y) <= CLUSTER_TILES;
				if (same)
				{
					found = candidate;
					break;
				}
			}
			if (found == null)
			{
				found = new Destination(x, y, snapped);
				destinations.add(found);
			}
			found.methods.add(teleport);
		}

		for (Destination destination : destinations)
		{
			MapInfo map = catalog.mapFor(destination.x, destination.y);
			if (map == null)
			{
				continue;
			}
			double hx = destination.x + 0.5;
			double hy = destination.y + 0.5;
			Needs.Ways ways = new Needs.Ways();
			Map<String, Need> byWay = new HashMap<>();
			for (Teleport method : destination.methods)
			{
				ways.add(method.skills, method.quests, method.requires);
				Need one = Needs.ofWay(method.skills, method.quests, method.requires);
				if (one != null)
				{
					byWay.putIfAbsent(teleportLabel(method), one);
				}
			}
			result.add(new Hotspot(map.getId(), hx, hy, RADIUS, teleportCard(destination), null,
				new ArrayList<>(), Hotspot.Kind.TELEPORT, TELEPORT_LAYER, null).withNeed(ways.build()).withWayNeeds(byWay));
			if (!destination.snapped)
			{
				HoverCard marker = HoverCard.builder(destination.methods.get(0).name).subtitle("Teleport").build();
				result.add(new Hotspot(map.getId(), hx, hy, RADIUS, marker, null, new ArrayList<>(),
					Hotspot.Kind.POI, TELEPORT_LAYER, null));
			}
		}
		return result;
	}

	static HoverCard teleportCard(Destination destination)
	{
		int count = destination.methods.size();
		HoverCard.Builder card = HoverCard.builder("Teleports here")
			.subtitle(count == 1 ? "1 way to get here" : count + " ways to get here");
		int shown = 0;
		for (Teleport method : destination.methods)
		{
			String label = teleportLabel(method);
			boolean overflow = shown >= MAX_ROWS;
			shown++;
			addRow(card, overflow, label, teleportRequirement(method));
			if (method.items != null && !method.items.isEmpty())
			{
				addRow(card, overflow, "   runes", method.items);
			}
			List<String> needs = needWords(method.quests, method.requires);
			if (!needs.isEmpty())
			{
				addRow(card, overflow, "   needs", String.join(", ", needs));
			}
		}
		return card.build();
	}

	private static void addRow(HoverCard.Builder card, boolean overflow, String label, String value)
	{
		if (overflow)
		{
			card.overflowRow(label, value);
		}
		else
		{
			card.row(label, value);
		}
	}

	/** A teleport's row on a destination's card: its name, and its floor when it is not the ground. */
	static String teleportLabel(Teleport method)
	{
		return method.plane > 0 ? method.name + " (" + FloorNames.name(method.plane).toLowerCase(Locale.ROOT) + ")" : method.name;
	}

	/** The quests and the other needs in the wiki's words (diary tiers, quest points) of one way, for a card, each once. */
	static List<String> needWords(@Nullable List<String> quests, @Nullable List<String> requires)
	{
		List<String> words = new ArrayList<>();
		for (List<String> list : Arrays.asList(quests, requires))
		{
			if (list == null)
			{
				continue;
			}
			for (String word : list)
			{
				if (word != null && !word.isEmpty() && !words.contains(word))
				{
					words.add(word);
				}
			}
		}
		return words;
	}

	/** What a method asks of the player, in a few words: its spellbook, level and whether it uses charges. */
	static String teleportRequirement(Teleport method)
	{
		List<String> parts = new ArrayList<>();
		if ("Spell".equals(method.kind) && method.group != null && !method.group.startsWith("Standard"))
		{
			parts.add(method.group);
		}
		if (method.skills != null && !method.skills.isEmpty())
		{
			parts.add(String.join(", ", method.skills));
		}
		if (method.charges != null && !method.charges.isEmpty())
		{
			parts.add(method.charges);
		}
		if (parts.isEmpty() && method.kind != null && !"Spell".equals(method.kind) && !"Item".equals(method.kind))
		{
			parts.add(method.kind.toLowerCase());
		}
		return String.join(", ", parts);
	}

	// ------------------------------------------------------------------
	// Resource shapes, bound by Gson from concrete classes
	// ------------------------------------------------------------------

	@RequiredArgsConstructor
	private static final class Stop
	{
		private final int x;
		private final int y;
		private final List<Route> routes = new ArrayList<>();
	}

	static final class TeleportFile
	{
		List<Teleport> teleports;
	}

	static final class Teleport
	{
		String name;
		String kind;
		String group;
		int x;
		int y;
		int plane;
		List<String> skills;
		String items;
		List<String> quests;
		/** Other needs in the wiki's words: achievement diary tiers and quest points (round 47). */
		List<String> requires;
		String charges;
		int[] snap;
	}

	/** Where one or more teleports land. */
	@RequiredArgsConstructor
	static final class Destination
	{
		final int x;
		final int y;
		final boolean snapped;
		final List<Teleport> methods = new ArrayList<>();
	}

	static final class EntranceFile
	{
		List<EntranceLink> links;
	}

	static final class EntranceLink
	{
		String name;
		Point a;
		Point b;
	}

	static final class Point
	{
		int map;
		int x;
		int y;
	}

	static final class TransportFile
	{
		List<Route> routes;
		List<Network> networks;
	}

	static final class Route
	{
		String kind;
		String place;
		int[] from;
		int[] to;
		String label;
		String cost;
		List<String> quests;
		List<String> skills;
		/** Other needs in the wiki's words: achievement diary tiers and quest points (round 47). */
		List<String> requires;
		int ticks;
	}

	static final class Network
	{
		String kind;
		List<Node> nodes;
	}

	static final class Node
	{
		String name;
		int x;
		int y;
		int plane;
		List<String> quests;
		/** Other needs in the wiki's words, for every node of the network (round 47). */
		List<String> requires;
	}
}
