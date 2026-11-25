package com.worldmapplus;

import com.google.gson.*;
import com.worldmapplus.data.*;
import com.worldmapplus.layers.*;
import com.worldmapplus.map.*;
import com.worldmapplus.render.*;
import com.worldmapplus.search.*;
import com.worldmapplus.tiles.*;
import com.worldmapplus.unlock.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.Nullable;
import javax.inject.*;
import lombok.*;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import org.slf4j.*;

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
	@Nullable
	private final OkHttpClient http;
	@Nullable
	private final WorldMapPlusConfig config;
	/** Tiles to use in place of the downloaded ones: tests and previews only. */
	@Nullable
	private final TileSource fixedTiles;

	/** The folder map packs are kept in, set by the plugin before the data is read. Null keeps them in memory only. */
	@Nullable
	private volatile Filepath cacheFolder;

	/** The loaded data, or null until the first use. Written once, under the lock, after everything in it is built. */
	private volatile Data data;

	/** What the player has done, for the icons to follow. Set by the plugin while it runs; null in tests and previews. */
	@Getter
	private volatile UnlockView unlocks;

	@Inject
	public MapAssets(Gson gson, ScheduledExecutorService executor, OkHttpClient http, WorldMapPlusConfig config)
	{
		this(gson, executor, http, config, null);
	}

	/** Assets that never download: only the overview pictures show, as on a first run without a connection. */
	public MapAssets(Gson gson, ScheduledExecutorService executor)
	{
		this(gson, executor, null, null, null);
	}

	/** Assets that draw the given tiles, for tests. */
	public MapAssets(Gson gson, ScheduledExecutorService executor, TileSource tiles)
	{
		this(gson, executor, null, null, tiles);
	}

	private MapAssets(Gson gson, ScheduledExecutorService executor, OkHttpClient http, WorldMapPlusConfig config, TileSource tiles)
	{
		this.gson = gson;
		this.executor = executor;
		this.http = http;
		this.config = config;
		this.fixedTiles = tiles;
	}

	/** Tells where downloaded map pictures are kept. Must be called before the data is first read to have any effect. */
	public void setCacheFolder(Filepath folder)
	{
		this.cacheFolder = folder;
	}

	private TileSource tileSource(TileIndex index)
	{
		if (fixedTiles != null)
		{
			return fixedTiles;
		}
		if (http == null || config == null)
		{
			return PackedTiles.overviewsOnly(index, gson, executor);
		}
		return PackedTiles.create(index, gson, executor, http, cacheFolder, config::downloadMaps);
	}

	/** Gives the icons the player's progress to follow, or takes it away with null. */
	public void setUnlocks(UnlockView view)
	{
		this.unlocks = view;
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
		private final Set<String> poiIconNames = new LinkedHashSet<>();
		private final RegionLines regions;
		private final int floors;
		private final Gazetteer gazetteer;
		private final MapElements elements;
		private final LabelLayer labels;
		private final List<Hotspot> teleports = new ArrayList<>();
		private final List<SearchEntry> searchEntries;
		private final GameIconSet gameIcons;
		/** Made when the map first draws the game's icons itself. */
		private volatile GameIconLayer gameIconLayer;

		Data(Gson gson, ScheduledExecutorService executor, TileSource source, TileIndex index)
		{
			this.catalog = MapCatalog.load(gson);
			this.floors = index.highestFloor(MapInfo.SURFACE);
			this.tiles = new TileCache(source, executor, tileBudget(0));
			this.renderer = new MapRenderer(tiles);
			List<Hotspot> loaded = HotspotLoader.load(gson, catalog);
			PoiLoader.Result pois = PoiLoader.load(gson, catalog);
			loaded.addAll(pois.getHotspots());
			loaded.addAll(ShortcutLoader.load(gson, catalog));
			this.poiCategories = pois.getCategories();
			List<SearchEntry> entries = SearchSources.build(catalog, loaded);
			this.searchEntries = entries;
			// The game's own icon names, for the icons the tiles show where no richer card exists.
			this.elements = MapElements.load(gson, catalog, loaded, PlaceLoader.quests(gson, catalog));
			// A result for something an icon stands for says what is missing for it, as the icon's card does.
			SearchSources.attachIconNeeds(entries, elements.getGameIcons());
			// A card that covers an icon and was given the wiki's requirement for it is used in place of the one it was made from.
			loaded.replaceAll(elements::replacementOf);
			entries.addAll(PlaceLoader.build(gson, catalog, elements));
			this.labels = new LabelLayer(elements);
			this.gameIcons = new GameIconSet(elements.getGameIcons());
			List<Hotspot> indexed = new ArrayList<>(loaded);
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
			this.gazetteer = new Gazetteer(catalog, regions, PlaceLoader.shopPlaces(gson, catalog));
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
					TileIndex index = TileIndex.load(gson);
						loaded = new Data(gson, executor, tileSource(index), index);
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
		if (loaded != null)
		{
			loaded.tiles.releaseIfIdle(IDLE_RELEASE_MILLIS);
		}
	}

	/** Frees the decoded tiles. Does nothing when the data was never read. */
	public void release()
	{
		Data loaded = data;
		if (loaded != null)
		{
			loaded.tiles.close();
		}
	}
}
