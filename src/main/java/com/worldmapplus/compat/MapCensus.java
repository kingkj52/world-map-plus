package com.worldmapplus.compat;

import com.worldmapplus.bridge.ExternalMarker;
import com.worldmapplus.bridge.NativePoints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Which other plugins put something on the game's own world map, found by looking and never by touching.
 * <p>
 * A plugin that wants something on the game's map has only a few ways to do it, and each leaves a public trace:
 * <ul>
 * <li>a {@code WorldMapPoint} given to RuneLite's manager, read by {@link com.worldmapplus.bridge.NativePoints}, whose
 * class says which plugin it came from;</li>
 * <li>an overlay hooked to the world map interface or its map layer, which RuneLite lists with its owner;</li>
 * <li>an overlay on the always-on-top layer that works out where the game's map is and draws on it itself, as Quest
 * Helper does, which leaves no trace except the plugin's name and the name of its overlay.</li>
 * </ul>
 * This class reads the list of loaded plugins and of overlays, both public and read only, and says what it found. It calls
 * nothing of any plugin's and changes nothing in them. Client thread only, and it does nothing at all unless asked.
 */
@Singleton
public class MapCensus
{
	/** How long a reading of the overlays is reused. */
	static final long REFRESH_MILLIS = 1_000;

	/** Most plugin names kept in the record of who has ever had points on the game's map. */
	static final int MAX_SOURCES = 64;

	/** The group of the game's world map interface. */
	static final int WORLD_MAP_GROUP = InterfaceID.WORLDMAP;

	/** Plugins known to draw on the game's map themselves, by the name RuneLite lists them under. */
	static final Map<String, String> KNOWN_DRAWERS;

	static
	{
		Map<String, String> known = new LinkedHashMap<>();
		known.put("Quest Helper", "its lines and arrows");
		known.put("Port Tasks", "its lines");
		known.put("Herbi AFK", "its lines");
		known.put("Shortest Path", "its path");
		KNOWN_DRAWERS = Collections.unmodifiableMap(known);
	}

	/** True when an overlay's class name says it is for the world map and not the minimap, such as a plugin's map overlay. */
	static boolean looksLikeWorldMapOverlay(String simpleName)
	{
		if (simpleName.contains("Minimap") || simpleName.contains("MiniMap"))
		{
			return false;
		}
		return simpleName.contains("WorldMap") || simpleName.contains("MapOverlay");
	}

	/** One loaded plugin. */
	public static final class PluginInfo
	{
		private final String name;
		private final String className;
		private final boolean enabled;

		public PluginInfo(String name, String className, boolean enabled)
		{
			this.name = name == null ? "" : name;
			this.className = className == null ? "" : className;
			this.enabled = enabled;
		}

		public String getName()
		{
			return name;
		}

		public String getClassName()
		{
			return className;
		}

		public boolean isEnabled()
		{
			return enabled;
		}

		/** The package the plugin's classes are in, which its own helpers share. */
		String packageName()
		{
			int dot = className.lastIndexOf('.');
			return dot < 0 ? "" : className.substring(0, dot);
		}
	}

	/** One overlay, as RuneLite lists it. */
	public static final class OverlayInfo
	{
		private final String pluginName;
		private final String className;
		private final List<Integer> hooks;
		private final String layer;

		public OverlayInfo(@Nullable String pluginName, String className, List<Integer> hooks, String layer)
		{
			this.pluginName = pluginName == null ? "" : pluginName;
			this.className = className == null ? "" : className;
			this.hooks = hooks == null ? Collections.emptyList() : hooks;
			this.layer = layer == null ? "" : layer;
		}

		public String getPluginName()
		{
			return pluginName;
		}

		public String getClassName()
		{
			return className;
		}

		public String getLayer()
		{
			return layer;
		}

		/** True when the overlay is drawn after the world map interface or something inside it. */
		boolean hooksWorldMap()
		{
			for (int hook : hooks)
			{
				if (hook >>> 16 == WORLD_MAP_GROUP)
				{
					return true;
				}
			}
			return false;
		}
	}

	/** Where the list of plugins comes from. A seam for tests. */
	public interface Plugins
	{
		List<PluginInfo> list();
	}

	/** Where the list of overlays comes from. A seam for tests. */
	public interface Overlays
	{
		void forEach(Consumer<OverlayInfo> sink);
	}

	/** What the census says about one plugin. */
	public static final class Entry
	{
		private final String name;
		private final int points;
		private final boolean hooksMap;
		private final String drawsOnMap;

		Entry(String name, int points, boolean hooksMap, @Nullable String drawsOnMap)
		{
			this.name = name;
			this.points = points;
			this.hooksMap = hooksMap;
			this.drawsOnMap = drawsOnMap == null ? "" : drawsOnMap;
		}

		/** The plugin's name, or "Other plugins" for points whose plugin could not be told. */
		public String getName()
		{
			return name;
		}

		/** How many points it has on the game's map now. */
		public int getPoints()
		{
			return points;
		}

		/** True when it has an overlay drawn after the game's map. */
		public boolean isHooksMap()
		{
			return hooksMap;
		}

		/** What it draws on the game's map for itself, in words, or an empty text. */
		public String getDrawsOnMap()
		{
			return drawsOnMap;
		}

		/** True when it draws on the game's map itself, so it is only right where this map and the game's agree. */
		public boolean drawsItself()
		{
			return !drawsOnMap.isEmpty();
		}
	}

	/** Everything found in one reading. */
	public static final class Snapshot
	{
		static final Snapshot EMPTY = new Snapshot(Collections.emptyList());

		private final List<Entry> entries;
		private final boolean hadPoints;

		Snapshot(List<Entry> entries)
		{
			this(entries, false);
		}

		Snapshot(List<Entry> entries, boolean hadPoints)
		{
			this.entries = Collections.unmodifiableList(entries);
			this.hadPoints = hadPoints;
		}

		public List<Entry> getEntries()
		{
			return entries;
		}

		/**
		 * True when some plugin has points on the game's map now, or has had some earlier in this session. Such a plugin may
		 * place its points only near where the game's map is looking, so at a new place it has none yet, and waiting for
		 * points to appear would wait for ever. The record of who has had points is kept until the plugin is switched off, so
		 * the game's map should be kept with this one. A plugin that only draws there, or only has an overlay, is not helped
		 * by it, and with no plugin ever having had a point this says no.
		 */
		public boolean needsGameMap()
		{
			return hadPoints || hasPointsNow();
		}

		/** True when some plugin has points on the game's map in this reading. */
		public boolean hasPointsNow()
		{
			for (Entry entry : entries)
			{
				if (entry.getPoints() > 0)
				{
					return true;
				}
			}
			return false;
		}

		/** The first plugin that draws on the game's map for itself, or null. */
		@Nullable
		public Entry firstDrawer()
		{
			for (Entry entry : entries)
			{
				if (entry.drawsItself())
				{
					return entry;
				}
			}
			return null;
		}
	}

	/** The name given to points whose plugin could not be told. */
	public static final String UNKNOWN = "Other plugins";

	private final Plugins plugins;
	private final Overlays overlays;
	private final String ownPackage;
	private Snapshot snapshot = Snapshot.EMPTY;
	private long snapshotAt = Long.MIN_VALUE;
	private List<PluginInfo> pluginList = Collections.emptyList();
	private List<OverlayInfo> overlayList = Collections.emptyList();
	private long listedAt = Long.MIN_VALUE;

	/** The names of the plugins that have had points on the game's map in this session and are still switched on. */
	private final Set<String> sources = new LinkedHashSet<>();

	@Inject
	public MapCensus(PluginManager manager, OverlayManager overlayManager)
	{
		this(() ->
		{
			List<PluginInfo> list = new ArrayList<>();
			for (Plugin plugin : manager.getPlugins())
			{
				list.add(new PluginInfo(plugin.getName(), plugin.getClass().getName(), manager.isPluginEnabled(plugin)));
			}
			return list;
		}, sink -> overlayManager.anyMatch(overlay ->
		{
			sink.accept(describe(overlay));
			return false;
		}), "com.worldmapplus");
	}

	/** For tests, with the two lists given and the package of the plugin this belongs to. */
	public MapCensus(Plugins plugins, Overlays overlays, String ownPackage)
	{
		this.plugins = plugins;
		this.overlays = overlays;
		this.ownPackage = ownPackage;
	}

	private static OverlayInfo describe(Overlay overlay)
	{
		Plugin plugin = overlay.getPlugin();
		return new OverlayInfo(plugin == null ? null : plugin.getName(), overlay.getClass().getName(),
			new ArrayList<>(overlay.getDrawHooks()), String.valueOf(overlay.getLayer()));
	}

	/** True when the class of a point belongs to the plugin of this name, by the package its classes share. */
	static boolean belongsTo(PluginInfo plugin, String className)
	{
		String pack = plugin.packageName();
		return !pack.isEmpty() && className.startsWith(pack + ".");
	}

	/** The name of the plugin a point's class belongs to, or {@link #UNKNOWN}. Uses the last reading of the plugins. */
	public synchronized String ownerOf(@Nullable String className)
	{
		if (className == null || className.isEmpty())
		{
			return UNKNOWN;
		}
		PluginInfo best = null;
		for (PluginInfo plugin : pluginList)
		{
			if (belongsTo(plugin, className) && (best == null || plugin.packageName().length() > best.packageName().length()))
			{
				best = plugin;
			}
		}
		return best == null ? UNKNOWN : best.getName();
	}

	private List<PluginInfo> listPlugins(long now)
	{
		if (listedAt == Long.MIN_VALUE || now < listedAt || now - listedAt >= REFRESH_MILLIS)
		{
			listedAt = now;
			try
			{
				List<PluginInfo> found = new ArrayList<>();
				for (PluginInfo info : plugins.list())
				{
					if (info.isEnabled() && !info.packageName().startsWith(ownPackage))
					{
						found.add(info);
					}
				}
				pluginList = found;
				pruneSources(found);
				List<OverlayInfo> overlaysFound = new ArrayList<>();
				overlays.forEach(overlaysFound::add);
				overlayList = overlaysFound;
			}
			catch (RuntimeException e)
			{
				// The lists are changed by other threads and can be caught mid change. The next reading is fine.
			}
		}
		return pluginList;
	}

	/** Forgets the plugins that have been switched off since they had points, so a plugin that is gone no longer counts. */
	private void pruneSources(List<PluginInfo> enabled)
	{
		Set<String> names = new LinkedHashSet<>();
		for (PluginInfo info : enabled)
		{
			names.add(info.getName());
		}
		java.util.Iterator<String> each = sources.iterator();
		while (each.hasNext())
		{
			String name = each.next();
			if (!UNKNOWN.equals(name) && !names.contains(name))
			{
				each.remove();
			}
		}
	}

	/** Writes down who owns each marker, so the record of who has had points on the game's map only grows. */
	private void note(List<ExternalMarker> markers)
	{
		for (ExternalMarker marker : markers)
		{
			if (sources.size() >= MAX_SOURCES)
			{
				return;
			}
			sources.add(ownerOf(marker.getOrigin()));
		}
	}

	/**
	 * Writes down the plugins that own these markers as having had points on the game's map, without making a reading. For
	 * markers the player has chosen not to see here (showing other plugins' points is off). They exist all the same, which says
	 * that the game's map has to be kept with this one. Does nothing for an empty list.
	 */
	public synchronized void remember(List<ExternalMarker> markers)
	{
		if (!markers.isEmpty())
		{
			note(markers);
		}
	}

	/** True when any other plugin has had points on the game's map since this session began and is still switched on. */
	public synchronized boolean hasHadPoints()
	{
		return !sources.isEmpty();
	}

	/**
	 * What the census finds, from the markers other plugins have on the game's map. Reads the two lists at most once a
	 * second. With {@code active} false it does nothing and says nothing was found, so a map that is not showing, or a
	 * plugin that has been switched off, costs nothing.
	 */
	public synchronized Snapshot take(long nowMillis, boolean active, List<ExternalMarker> nativeMarkers)
	{
		if (!active)
		{
			// The record of who has had points is kept: it is about the session, not about this map being on screen.
			snapshot = Snapshot.EMPTY;
			snapshotAt = Long.MIN_VALUE;
			return snapshot;
		}
		if (snapshotAt != Long.MIN_VALUE && nowMillis >= snapshotAt && nowMillis - snapshotAt < REFRESH_MILLIS / 4)
		{
			return snapshot;
		}
		List<PluginInfo> enabled = listPlugins(nowMillis);
		note(nativeMarkers);
		Map<String, int[]> counts = new LinkedHashMap<>();
		for (ExternalMarker marker : nativeMarkers)
		{
			counts.computeIfAbsent(ownerOf(marker.getOrigin()), k -> new int[1])[0]++;
		}
		Map<String, Boolean> hooks = new LinkedHashMap<>();
		Map<String, Boolean> mapOverlay = new LinkedHashMap<>();
		for (OverlayInfo overlay : overlayList)
		{
			String owner = overlay.getPluginName();
			if (owner.isEmpty() || isOwn(overlay) || isCore(overlay))
			{
				continue;
			}
			if (overlay.hooksWorldMap())
			{
				hooks.put(owner, true);
			}
			else if ("ALWAYS_ON_TOP".equals(overlay.getLayer()) && looksLikeWorldMapOverlay(simple(overlay.getClassName())))
			{
				mapOverlay.put(owner, true);
			}
		}
		List<Entry> entries = new ArrayList<>();
		Map<String, Boolean> seen = new LinkedHashMap<>();
		for (PluginInfo plugin : enabled)
		{
			String name = plugin.getName();
			int points = counts.containsKey(name) ? counts.get(name)[0] : 0;
			boolean hooked = hooks.containsKey(name);
			String draws = KNOWN_DRAWERS.get(name);
			if (draws == null && mapOverlay.containsKey(name))
			{
				draws = "what it draws on the map";
			}
			if (points > 0 || hooked || draws != null)
			{
				entries.add(new Entry(name, points, hooked, draws));
				seen.put(name, true);
			}
		}
		int lost = counts.containsKey(UNKNOWN) ? counts.get(UNKNOWN)[0] : 0;
		if (lost > 0)
		{
			entries.add(new Entry(UNKNOWN, lost, false, null));
		}
		snapshotAt = nowMillis;
		snapshot = new Snapshot(entries, !sources.isEmpty());
		return snapshot;
	}

	private boolean isOwn(OverlayInfo overlay)
	{
		return overlay.getClassName().startsWith(ownPackage + ".");
	}

	/** True for RuneLite's own overlays for the world map, which draw the points this map already draws itself. */
	private static boolean isCore(OverlayInfo overlay)
	{
		return overlay.getClassName().startsWith("net.runelite.client.ui.overlay.")
			|| overlay.getClassName().startsWith(NativePoints.CORE_PACKAGE);
	}

	private static String simple(String className)
	{
		int dot = className.lastIndexOf('.');
		return dot < 0 ? className : className.substring(dot + 1);
	}
}
