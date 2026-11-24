package com.worldmapplus;

import com.google.gson.Gson;
import com.worldmapplus.data.HotspotLoader;
import com.worldmapplus.data.MapElements;
import com.worldmapplus.data.PlaceLoader;
import com.worldmapplus.data.ShortcutLoader;
import com.worldmapplus.layers.LabelLayer;
import com.worldmapplus.walk.WalkPlanner;
import com.worldmapplus.data.PoiLoader;
import com.worldmapplus.data.SpawnLoader;
import com.worldmapplus.layers.DoorwayGraph;
import com.worldmapplus.layers.GameIconLayer;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.GameIconSprites;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HotspotIndex;
import com.worldmapplus.layers.PoiCategory;
import com.worldmapplus.layers.PoiIcons;
import com.worldmapplus.layers.PoiLayer;
import com.worldmapplus.layers.RegionLines;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.render.MapRenderer;
import com.worldmapplus.search.Gazetteer;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchIndex;
import com.worldmapplus.search.SearchSources;
import com.worldmapplus.tiles.BundledTiles;
import com.worldmapplus.tiles.TileCache;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the viewer draws from, built from what is bundled in the plugin jar.
 * <p>
 * Nothing is read until it is first needed, so a plugin that is installed but switched off, or not started yet, costs
 * nothing beyond this small object. The first getter call (or {@link #preload()}) reads and indexes all the bundled data
 * once, under a lock, so two threads that arrive together share one load. After that every call is a field read.
 * {@link #preload()} does that work on RuneLite's shared executor, which is how the plugin starts it, so the map is
 * normally ready by the time it is first drawn.
 * <p>
 * Tile decoding runs on RuneLite's shared executor, never on a thread of our own.
 */
@Singleton
public class MapAssets
{
	private static final Logger log = LoggerFactory.getLogger(MapAssets.class);

	/** Most memory the decoded tiles may use. Decoded tiles are plain RGB, 256 KB each at full size. */
	private static final long TILE_BUDGET_MAX_BYTES = 96L << 20;

	/** The least memory the decoded tiles are given, however small the heap. */
	private static final long TILE_BUDGET_MIN_BYTES = 24L << 20;

	/** The decoded tiles are let go of after the map has been closed this long. */
	public static final long IDLE_RELEASE_MILLIS = 60_000;

	private final Gson gson;
	private final ScheduledExecutorService executor;

	/** The loaded data, or null until the first use. Written once, under the lock, after everything in it is built. */
	private volatile Data data;

	/** The drop tables, read on first use (never at start) on the shared executor. */
	private final com.worldmapplus.drops.DropLoader drops;

	/** Item prices from RuneLite's item manager. Set by the plugin while it runs; none in tests and previews. */
	private volatile com.worldmapplus.drops.PriceSource prices = com.worldmapplus.drops.PriceSource.NONE;

	/** What the player has done, for the icons to follow. Set by the plugin while it runs; null in tests and previews. */
	private volatile com.worldmapplus.unlock.UnlockView unlocks;

	@Inject
	public MapAssets(Gson gson, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.executor = executor;
		this.drops = new com.worldmapplus.drops.DropLoader(executor);
	}

	/** The drop tables' loader. Asking it for the tables starts the one read; nothing is read before that. */
	public com.worldmapplus.drops.DropLoader getDrops()
	{
		return drops;
	}

	/** Gives the drop tabs item prices to show, or takes them away with null. */
	public void setPrices(com.worldmapplus.drops.PriceSource source)
	{
		this.prices = source == null ? com.worldmapplus.drops.PriceSource.NONE : source;
	}

	/** Item prices, never null: with none set every price is unknown. */
	public com.worldmapplus.drops.PriceSource getPrices()
	{
		return prices;
	}

	/** Gives the icons the player's progress to follow, or takes it away with null. */
	public void setUnlocks(com.worldmapplus.unlock.UnlockView view)
	{
		this.unlocks = view;
	}

	/** The player's progress, or null when there is none to follow. */
	public com.worldmapplus.unlock.UnlockView getUnlocks()
	{
		return unlocks;
	}

	/** The most heap the JVM will use, or 0 when it does not say. */
	private static long heapLimit()
	{
		try
		{
			return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	/** The memory the decoded tiles may use: an eighth of the heap, between 24 and 96 MB. */
	static long tileBudget(long maxMemory)
	{
		if (maxMemory <= 0)
		{
			return TILE_BUDGET_MAX_BYTES;
		}
		return Math.max(TILE_BUDGET_MIN_BYTES, Math.min(TILE_BUDGET_MAX_BYTES, maxMemory / 8));
	}

	/** Everything that is read from the bundled files. Built by its constructor and never changed afterwards. */
	private static final class Data
	{
		private final MapCatalog catalog;
		private final TileCache tiles;
		private final MapRenderer renderer;
		private final HotspotIndex hotspots;
		private final SearchIndex searchIndex;
		private final DoorwayGraph doorways;
		private final List<PoiCategory> poiCategories;
		private final PoiLayer poiLayer;
		private final PoiIcons poiIcons = new PoiIcons();
		private final java.util.Set<String> poiIconNames = new java.util.LinkedHashSet<>();
		private final RegionLines regions;
		private final int floors;
		private final Gazetteer gazetteer;
		private final MapElements elements;
		private final LabelLayer labels;
		private final WalkPlanner walkPlanner;
		private final List<Hotspot> teleports = new java.util.ArrayList<>();
		private final List<SearchEntry> searchEntries;
		private final GameIconSet gameIcons;
		private final com.worldmapplus.search.KindIndex kinds;
		/** Made when the map first draws the game's icons itself. */
		private volatile GameIconLayer gameIconLayer;

		Data(Gson gson, ScheduledExecutorService executor)
		{
			this.catalog = MapCatalog.load(gson);
			BundledTiles bundled = BundledTiles.load(gson);
			this.floors = bundled.highestFloor(MapInfo.SURFACE);
			this.tiles = new TileCache(bundled, executor, tileBudget(heapLimit()));
			this.renderer = new MapRenderer(tiles);
			List<Hotspot> loaded = HotspotLoader.load(gson, catalog);
			PoiLoader.Result pois = PoiLoader.load(gson, catalog);
			loaded.addAll(pois.getHotspots());
			loaded.addAll(ShortcutLoader.load(gson, catalog));
			this.poiCategories = pois.getCategories();
			List<SearchEntry> entries = SearchSources.build(catalog, loaded);
			entries.addAll(SpawnLoader.load(catalog));
			this.searchEntries = entries;
			// The game's own icon names, for the icons the tiles show where no richer card exists.
			this.elements = MapElements.load(gson, catalog, loaded, entries);
			// A result for something an icon stands for says what is missing for it, as the icon's card does.
			SearchSources.attachIconNeeds(entries, elements.getGameIcons());
			// A card that covers an icon and was given the wiki's requirement for it is used in place of the one it was made from.
			loaded.replaceAll(elements::replacementOf);
			entries.addAll(PlaceLoader.build(gson, catalog, elements));
			// Every place of each kind, and each kind per town (kinds.json, from tools/build_kinds.py).
			this.kinds = com.worldmapplus.search.KindIndex.load(gson, catalog);
			entries.addAll(kinds.entries(catalog));
			this.labels = new LabelLayer(elements);
			this.gameIcons = new GameIconSet(elements.getGameIcons());
			this.walkPlanner = new WalkPlanner(executor);
			List<Hotspot> indexed = new java.util.ArrayList<>(loaded);
			indexed.addAll(elements.getIcons());
			this.hotspots = new HotspotIndex(indexed);
			this.doorways = new DoorwayGraph(loaded);
			Map<String, PoiCategory> byId = new LinkedHashMap<>();
			for (PoiCategory category : poiCategories)
			{
				byId.put(category.getId(), category);
			}
			for (Hotspot hotspot : indexed)
			{
				if (hotspot.getKind() == Hotspot.Kind.POI && hotspot.getIcon() != null)
				{
					poiIconNames.add(hotspot.getIcon());
				}
			}
			this.poiLayer = new PoiLayer(hotspots, poiIcons, byId);
			this.searchIndex = new SearchIndex(searchEntries);
			this.regions = RegionLines.load(gson);
			this.gazetteer = new Gazetteer(catalog, regions, HotspotLoader.shopPlaces(gson, catalog));
			gazetteer.addLabels(elements);
			for (Hotspot hotspot : loaded)
			{
				if (hotspot.getKind() == Hotspot.Kind.TELEPORT)
				{
					teleports.add(hotspot);
				}
			}
		}
	}

	/** Reads the bundled data if that has not been done yet, and returns it. */
	private Data data()
	{
		Data loaded = data;
		if (loaded == null)
		{
			synchronized (this)
			{
				loaded = data;
				if (loaded == null)
				{
					long start = System.nanoTime();
					loaded = new Data(gson, executor);
					data = loaded;
					log.debug("World Map Plus read its bundled data in {} ms", (System.nanoTime() - start) / 1_000_000L);
				}
			}
		}
		return loaded;
	}

	/** True once the bundled data has been read. */
	public boolean isLoaded()
	{
		return data != null;
	}

	/**
	 * Starts reading the bundled data on RuneLite's shared executor, so that it is ready by the time the map is first
	 * drawn. Does nothing when it is already read. A caller that needs the data before the read is done waits for it.
	 */
	public void preload()
	{
		if (data != null)
		{
			return;
		}
		executor.execute(() ->
		{
			try
			{
				data();
			}
			catch (RuntimeException e)
			{
				log.warn("World Map Plus could not read its bundled data", e);
			}
		});
	}

	/** Finds the walking between the jumps of a route, for drawing the exact way. */
	public WalkPlanner getWalkPlanner()
	{
		return data().walkPlanner;
	}

	/** The game's own text labels, such as area names. */
	public LabelLayer getLabels()
	{
		return data().labels;
	}

	/** Every icon of the game's own list, indexed by place. */
	public GameIconSet getGameIcons()
	{
		return data().gameIcons;
	}

	/**
	 * The layer that draws the game's icons, made (and its atlas read) the first time it is asked for. The map asks when it first
	 * paints a view.
	 */
	public GameIconLayer getGameIconLayer()
	{
		Data loaded = data();
		GameIconLayer layer = loaded.gameIconLayer;
		if (layer == null)
		{
			synchronized (loaded)
			{
				layer = loaded.gameIconLayer;
				if (layer == null)
				{
					layer = new GameIconLayer(loaded.gameIcons, GameIconSprites.load(loaded.elements.getPictureCount()));
					loaded.gameIconLayer = layer;
				}
			}
		}
		return layer;
	}


	/** Every place of each kind, merged per town. */
	public com.worldmapplus.search.KindIndex getKinds()
	{
		return data().kinds;
	}

	/** The game's own labels and icon names. */
	public MapElements getElements()
	{
		return data().elements;
	}

	/** What the place names used in searches mean. */
	public Gazetteer getGazetteer()
	{
		return data().gazetteer;
	}

	/** Every place one can teleport to, each with the card listing the ways. */
	public List<Hotspot> getTeleports()
	{
		return data().teleports;
	}

	/** The highest floor above the ground the surface has imagery for, or 0 when there is none. */
	public int getFloors()
	{
		return data().floors;
	}

	/** The regions of the world, for the region borders layer. */
	public RegionLines getRegions()
	{
		return data().regions;
	}

	public MapCatalog getCatalog()
	{
		return data().catalog;
	}

	public TileCache getTiles()
	{
		return data().tiles;
	}

	public MapRenderer getRenderer()
	{
		return data().renderer;
	}

	public HotspotIndex getHotspots()
	{
		return data().hotspots;
	}

	public SearchIndex getSearchIndex()
	{
		return data().searchIndex;
	}

	/** The kinds of point of interest, in the order the layers panel lists them. */
	public List<PoiCategory> getPoiCategories()
	{
		return data().poiCategories;
	}

	public PoiLayer getPoiLayer()
	{
		return data().poiLayer;
	}

	/** Which maps lead to which, for pointing at the doorway toward something on another map. */
	public DoorwayGraph getDoorways()
	{
		return data().doorways;
	}

	/** The entries the search index was built from, so extra ones can be added to a copy. */
	public List<SearchEntry> getSearchEntries()
	{
		return data().searchEntries;
	}

	/** Tells the tile loader that no map is being looked at any more. Does nothing when the data was never read. */
	public void endView()
	{
		Data loaded = data;
		if (loaded != null)
		{
			loaded.tiles.endView();
		}
	}

	/**
	 * Frees the decoded tiles once the map has not asked for any for {@link #IDLE_RELEASE_MILLIS}. Meant to be called from a
	 * callback that already runs, such as the game tick, so no timer is needed. Does nothing when the data was never read.
	 */
	public void releaseIfIdle()
	{
		Data loaded = data;
		if (loaded != null && loaded.tiles.releaseIfIdle(IDLE_RELEASE_MILLIS))
		{
			loaded.walkPlanner.release();
		}
	}

	/** Frees the decoded tiles. Does nothing when the data was never read. */
	public void release()
	{
		Data loaded = data;
		if (loaded != null)
		{
			loaded.tiles.clear();
			loaded.walkPlanner.release();
		}
	}
}
