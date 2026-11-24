package com.worldmapplus.map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * The maps the viewer knows about, and which of them covers a given world position.
 * <p>
 * Where the bounds of two maps overlap, the smaller one wins. The surface is by far the largest,
 * so a dungeon whose rectangle happens to touch it is chosen first. The wiki's extra maps, which
 * share coordinates with the standard ones, are only chosen for positions no standard map covers.
 */
@Slf4j
public final class MapCatalog
{
	private static final String RESOURCE = "/com/worldmapplus/maps.json";

	private final List<MapInfo> maps;
	private final Map<Integer, MapInfo> byId = new HashMap<>();
	/** The same maps ordered smallest area first, which is the order position lookups test them. */
	private final List<MapInfo> bySpecificity;
	private final String version;

	public MapCatalog(String version, List<MapInfo> maps)
	{
		this.version = version == null ? "" : version;
		this.maps = Collections.unmodifiableList(new ArrayList<>(maps));
		for (MapInfo map : maps)
		{
			byId.put(map.getId(), map);
		}
		List<MapInfo> sorted = new ArrayList<>(maps);
		sorted.sort((a, b) ->
		{
			if (a.isStandard() != b.isStandard())
			{
				return a.isStandard() ? -1 : 1;
			}
			return Long.compare(a.area(), b.area());
		});
		this.bySpecificity = Collections.unmodifiableList(sorted);
	}

	/** Reads the bundled catalogue. A missing or damaged resource gives an empty catalogue. */
	public static MapCatalog load(Gson gson)
	{
		try (InputStream in = MapCatalog.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("World Map Plus: {} is missing from the plugin jar", RESOURCE);
				return new MapCatalog("", Collections.emptyList());
			}
			return read(gson, new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("World Map Plus: could not read {}", RESOURCE, e);
			return new MapCatalog("", Collections.emptyList());
		}
	}

	static MapCatalog read(Gson gson, Reader reader)
	{
		Resource resource = gson.fromJson(reader, Resource.class);
		if (resource == null || resource.maps == null)
		{
			return new MapCatalog("", Collections.emptyList());
		}
		List<MapInfo> list = new ArrayList<>();
		for (MapInfo map : resource.maps)
		{
			if (map != null && map.getName() != null)
			{
				list.add(map);
			}
		}
		return new MapCatalog(resource.version, list);
	}

	public List<MapInfo> getMaps()
	{
		return maps;
	}

	/** The wiki map version the bundled imagery was taken from. */
	public String getVersion()
	{
		return version;
	}

	@Nullable
	public MapInfo byId(int id)
	{
		return byId.get(id);
	}

	/** The most specific map whose bounds contain the position, or null. */
	@Nullable
	public MapInfo mapFor(int x, int y)
	{
		for (MapInfo map : bySpecificity)
		{
			if (map.contains(x, y))
			{
				return map;
			}
		}
		return null;
	}

	/**
	 * The map a tile belongs to, or when no map has imagery of that exact tile (an area the pictures leave out, the edge of a map) the
	 * map whose imagery is nearest, if that is within a margin of tiles. A map other than the surface wins a tie, because it is the more
	 * specific place. Null when nothing is that near.
	 */
	@Nullable
	public MapInfo mapNear(int x, int y, int margin)
	{
		MapInfo exact = mapFor(x, y);
		if (exact != null)
		{
			return exact;
		}
		MapInfo best = null;
		int bestDistance = margin + 1;
		for (MapInfo map : bySpecificity)
		{
			int distance = map.distanceTo(x, y);
			if (distance < bestDistance || (distance == bestDistance && best != null && best.getId() == MapInfo.SURFACE))
			{
				best = map;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** The surface map, or the first map if the surface is not bundled. */
	@Nullable
	public MapInfo surface()
	{
		MapInfo surface = byId.get(MapInfo.SURFACE);
		return surface != null ? surface : maps.isEmpty() ? null : maps.get(0);
	}

	/** The shape of maps.json. Filled by Gson. */
	private static final class Resource
	{
		private String version;
		private MapInfo[] maps;
	}
}
