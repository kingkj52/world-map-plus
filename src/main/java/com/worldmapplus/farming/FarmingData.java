package com.worldmapplus.farming;

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
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * The farming tables: which patches each part of the world has, which variable each one is read
 * from, what each value of that variable means, and how long each crop takes.
 * <p>
 * The tables are RuneLite's Time Tracking tables (BSD 2-Clause), read out of its source by
 * {@code tools/build_farming.py} and bundled as {@code farming.json}. Nothing is fetched while the
 * plugin runs.
 */
@Slf4j
public final class FarmingData
{
	private static final String RESOURCE = "/com/worldmapplus/farming.json";

	/** The kinds of patch, by the words the map's farming points use for them. */
	private static final Map<String, String> KIND_WORDS = new HashMap<>();

	static
	{
		KIND_WORDS.put("allotment", "ALLOTMENT");
		KIND_WORDS.put("herb", "HERB");
		KIND_WORDS.put("flower", "FLOWER");
		KIND_WORDS.put("bush", "BUSH");
		KIND_WORDS.put("fruit tree", "FRUIT_TREE");
		KIND_WORDS.put("hops", "HOPS");
		KIND_WORDS.put("tree", "TREE");
		KIND_WORDS.put("hardwood", "HARDWOOD_TREE");
		KIND_WORDS.put("redwood", "REDWOOD");
		KIND_WORDS.put("spirit tree", "SPIRIT_TREE");
		KIND_WORDS.put("anima", "ANIMA");
		KIND_WORDS.put("belladonna", "BELLADONNA");
		KIND_WORDS.put("cactus", "CACTUS");
		KIND_WORDS.put("coral", "CORAL");
		KIND_WORDS.put("seaweed", "SEAWEED");
		KIND_WORDS.put("calquat", "CALQUAT");
		KIND_WORDS.put("celastrus", "CELASTRUS");
		KIND_WORDS.put("grapes", "GRAPES");
		KIND_WORDS.put("crystal tree", "CRYSTAL_TREE");
		KIND_WORDS.put("mushroom", "MUSHROOM");
		KIND_WORDS.put("hespori", "HESPORI");
	}

	/** One patch of a region. */
	public static final class Patch
	{
		private final String name;
		private final int varbit;
		private final String kind;

		Patch(String name, int varbit, String kind)
		{
			this.name = name;
			this.varbit = varbit;
			this.kind = kind;
		}

		/** The part of the region it is in, such as {@code North}, or empty when there is only one. */
		public String getName()
		{
			return name;
		}

		/** The game variable the patch is read from. */
		public int getVarbit()
		{
			return varbit;
		}

		/** The kind of patch, such as {@code HERB}. */
		public String getKind()
		{
			return kind;
		}

		/** A name for the patch: {@code North allotment}, {@code Herb}. */
		public String label()
		{
			String words = kindName(kind);
			return name.isEmpty() ? words : name + " " + words.toLowerCase(Locale.ROOT);
		}
	}

	/** A place with farming patches. */
	public static final class Region
	{
		private final String name;
		private final int id;
		private final List<Integer> also;
		private final String rule;
		private final List<Patch> patches;

		Region(String name, int id, List<Integer> also, String rule, List<Patch> patches)
		{
			this.name = name;
			this.id = id;
			this.also = also;
			this.rule = rule;
			this.patches = Collections.unmodifiableList(patches);
		}

		public String getName()
		{
			return name;
		}

		/** The game's region number of the main square, as the key patches are stored under. */
		public int getId()
		{
			return id;
		}

		public List<Patch> getPatches()
		{
			return patches;
		}

		/** True when the patches of this region are being sent for a place. */
		public boolean contains(int x, int y, int plane)
		{
			return FarmingBounds.contains(rule, x, y, plane);
		}
	}

	private static final class StateRange
	{
		final int lo;
		final int hi;
		final Produce produce;
		final CropState crop;
		final int base;
		final int sign;

		StateRange(int lo, int hi, Produce produce, CropState crop, int base, int sign)
		{
			this.lo = lo;
			this.hi = hi;
			this.produce = produce;
			this.crop = crop;
			this.base = base;
			this.sign = sign;
		}
	}

	private final Map<String, List<StateRange>> states = new HashMap<>();
	private final Map<Integer, List<Region>> byRegionId = new HashMap<>();
	private final List<Region> regions = new ArrayList<>();

	private FarmingData()
	{
	}

	public static FarmingData empty()
	{
		return new FarmingData();
	}

	public static FarmingData load(Gson gson)
	{
		try (InputStream in = FarmingData.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("World Map Plus: {} is missing from the plugin jar", RESOURCE);
				return empty();
			}
			return read(new InputStreamReader(in, StandardCharsets.UTF_8), gson);
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("World Map Plus: could not read {}", RESOURCE, e);
			return empty();
		}
	}

	public static FarmingData read(Reader reader, Gson gson)
	{
		FarmingData data = new FarmingData();
		Tables file = gson.fromJson(reader, Tables.class);
		if (file == null)
		{
			return data;
		}
		Map<String, Produce> crops = new HashMap<>();
		if (file.produce != null)
		{
			for (Map.Entry<String, ProduceRecord> entry : file.produce.entrySet())
			{
				ProduceRecord p = entry.getValue();
				if (p != null && p.name != null)
				{
					crops.put(entry.getKey(), new Produce(p.name, p.tick, p.stages, p.regrow, p.lives));
				}
			}
		}
		if (file.kinds != null)
		{
			for (Map.Entry<String, KindRecord> entry : file.kinds.entrySet())
			{
				List<StateRange> ranges = new ArrayList<>();
				if (entry.getValue() != null && entry.getValue().states != null)
				{
					for (StateRecord s : entry.getValue().states)
					{
						Produce produce = s == null ? null : crops.get(s.produce);
						CropState crop = s == null ? null : cropState(s.crop);
						if (produce != null && crop != null)
						{
							ranges.add(new StateRange(s.lo, s.hi, produce, crop, s.base, s.sign));
						}
					}
				}
				data.states.put(entry.getKey(), ranges);
			}
		}
		if (file.regions != null)
		{
			for (RegionRecord r : file.regions)
			{
				if (r == null || r.name == null || r.patches == null)
				{
					continue;
				}
				List<Patch> patches = new ArrayList<>();
				for (PatchRecord p : r.patches)
				{
					// Compost bins are not patches, and have no states here.
					if (p != null && p.kind != null && data.states.containsKey(p.kind))
					{
						patches.add(new Patch(p.name == null ? "" : p.name, p.varbit, p.kind));
					}
				}
				if (patches.isEmpty())
				{
					continue;
				}
				List<Integer> also = r.also == null ? new ArrayList<>() : r.also;
				Region region = new Region(r.name, r.id, also, r.rule, patches);
				data.regions.add(region);
				data.byRegionId.computeIfAbsent(r.id, k -> new ArrayList<>()).add(region);
				for (int id : also)
				{
					data.byRegionId.computeIfAbsent(id, k -> new ArrayList<>()).add(region);
				}
			}
		}
		return data;
	}

	@Nullable
	private static CropState cropState(String name)
	{
		try
		{
			return CropState.valueOf(name);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			return null;
		}
	}

	public List<Region> getRegions()
	{
		return Collections.unmodifiableList(regions);
	}

	/** The game's region number for a tile. */
	public static int regionId(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	/** What a value of a kind of patch's variable means, or null when the table has nothing for it. */
	@Nullable
	public PatchState decode(String kind, int value)
	{
		List<StateRange> ranges = states.get(kind);
		if (ranges == null)
		{
			return null;
		}
		for (StateRange range : ranges)
		{
			if (value >= range.lo && value <= range.hi)
			{
				return new PatchState(range.produce, range.crop, Math.max(0, range.base + range.sign * value));
			}
		}
		return null;
	}

	/** The regions whose patches the game is sending while a player stands at a place. */
	public List<Region> regionsAt(int x, int y, int plane)
	{
		List<Region> result = new ArrayList<>();
		List<Region> candidates = byRegionId.get(regionId(x, y));
		if (candidates != null)
		{
			for (Region region : candidates)
			{
				if (region.contains(x, y, plane) && !result.contains(region))
				{
					result.add(region);
				}
			}
		}
		return result;
	}

	/** A patch of a place and the region it belongs to. */
	public static final class Located
	{
		private final Region region;
		private final Patch patch;

		Located(Region region, Patch patch)
		{
			this.region = region;
			this.patch = patch;
		}

		public Region getRegion()
		{
			return region;
		}

		public Patch getPatch()
		{
			return patch;
		}
	}

	/**
	 * The patches that a map point stands for, found from where it is and what it is called. A point
	 * called {@code Allotment/Herb/Flower patch} stands for the allotment, herb and flower patches of
	 * the region it is in.
	 */
	public List<Located> patchesFor(String pointName, int x, int y)
	{
		List<String> kinds = kindsIn(pointName);
		List<Located> result = new ArrayList<>();
		if (kinds.isEmpty())
		{
			return result;
		}
		for (Region region : regionsAt(x, y, 0))
		{
			for (Patch patch : region.patches)
			{
				if (kinds.contains(patch.kind))
				{
					result.add(new Located(region, patch));
				}
			}
		}
		return result;
	}

	/** The kinds of patch a name such as {@code Celastrus/Fruit Tree patch} mentions. */
	static List<String> kindsIn(String pointName)
	{
		List<String> kinds = new ArrayList<>();
		for (String part : pointName.replace(" patch", "").split("/"))
		{
			String kind = KIND_WORDS.get(part.trim().toLowerCase(Locale.ROOT));
			if (kind != null && !kinds.contains(kind))
			{
				kinds.add(kind);
			}
		}
		return kinds;
	}

	/** {@code FRUIT_TREE} as {@code Fruit tree}. */
	static String kindName(String kind)
	{
		String words = kind.toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	// ------------------------------------------------------------------
	// Resource shapes, bound by Gson from concrete classes
	// ------------------------------------------------------------------

	private static final class Tables
	{
		Map<String, ProduceRecord> produce;
		Map<String, KindRecord> kinds;
		List<RegionRecord> regions;
	}

	private static final class ProduceRecord
	{
		String name;
		int tick;
		int stages;
		int regrow;
		int lives;
	}

	private static final class KindRecord
	{
		List<StateRecord> states;
	}

	private static final class StateRecord
	{
		int lo;
		int hi;
		String produce;
		String crop;
		int base;
		int sign;
	}

	private static final class RegionRecord
	{
		String name;
		int id;
		List<Integer> also;
		String rule;
		List<PatchRecord> patches;
	}

	private static final class PatchRecord
	{
		String name;
		int varbit;
		String kind;
	}
}
