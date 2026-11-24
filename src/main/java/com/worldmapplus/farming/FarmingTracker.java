package com.worldmapplus.farming;

import com.google.gson.Gson;
import com.worldmapplus.WorldMapPlusConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/**
 * Remembers what each farming patch looked like the last time the player was near it.
 * <p>
 * The game sends a patch's variable only while the player is close to it. While the player is in a
 * place with patches, once a game tick, the tracker reads those variables. That is the same read
 * RuneLite's own Time Tracking plugin does: it only reads, it never changes the variable, and nothing
 * is sent anywhere. What it saw is kept in the plugin's settings for the account in use, so the map
 * can still say what is growing where when the player is far away.
 * <p>
 * Client thread only.
 */
@Singleton
public class FarmingTracker
{
	/** Ticks to wait after entering a new place, because the game sends the variables a moment late. */
	static final int SETTLE_TICKS = 3;

	/** Where observations are kept. */
	public interface Store
	{
		@Nullable
		String get(String key);

		void put(String key, String value);
	}

	/** The plugin's settings for the account in use. */
	private static final class ConfigStore implements Store
	{
		private final ConfigManager configManager;

		ConfigStore(ConfigManager configManager)
		{
			this.configManager = configManager;
		}

		@Nullable
		@Override
		public String get(String key)
		{
			return configManager.getRSProfileConfiguration(WorldMapPlusConfig.GROUP, key);
		}

		@Override
		public void put(String key, String value)
		{
			configManager.setRSProfileConfiguration(WorldMapPlusConfig.GROUP, key, value);
		}
	}

	private final Client client;
	private final FarmingData data;
	private final Store store;
	private final LongSupplier clock;

	private final Map<String, Observation> seen = new HashMap<>();
	private final Set<String> watching = new HashSet<>();
	private int lastRegion = -1;
	private int settle;

	@Inject
	public FarmingTracker(Client client, ConfigManager configManager, Gson gson)
	{
		this(client, FarmingData.load(gson), new ConfigStore(configManager), System::currentTimeMillis);
	}

	/** For tests and previews, which supply their own data, storage and clock. */
	public FarmingTracker(Client client, FarmingData data, Store store, LongSupplier clock)
	{
		this.client = client;
		this.data = data;
		this.store = store;
		this.clock = clock;
	}

	public FarmingData getData()
	{
		return data;
	}

	public long now()
	{
		return clock.getAsLong();
	}

	/** Once a game tick. Reads the patches of the place the player is standing in. */
	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.isInInstancedRegion())
		{
			lastRegion = -1;
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		WorldPoint at = player.getWorldLocation();
		if (at == null)
		{
			return;
		}
		update(at.getX(), at.getY(), at.getPlane(), client::getVarbitValue);
	}

	/** Forget everything held in memory, for when the account changes. What is stored stays stored. */
	public void onGameState(GameState state)
	{
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			seen.clear();
			watching.clear();
			lastRegion = -1;
		}
	}

	/** Reads the patches that apply at a place, using {@code varbits} to read a game variable. */
	void update(int x, int y, int plane, IntUnaryOperator varbits)
	{
		int region = FarmingData.regionId(x, y);
		if (region != lastRegion)
		{
			lastRegion = region;
			settle = SETTLE_TICKS;
			watching.clear();
		}
		if (settle > 0)
		{
			settle--;
			return;
		}
		List<FarmingData.Region> regions = data.regionsAt(x, y, plane);
		long now = clock.getAsLong();
		for (FarmingData.Region place : regions)
		{
			for (FarmingData.Patch patch : place.getPatches())
			{
				record(place, patch, varbits.applyAsInt(patch.getVarbit()), now);
			}
		}
	}

	private void record(FarmingData.Region place, FarmingData.Patch patch, int value, long now)
	{
		PatchState after = data.decode(patch.getKind(), value);
		if (after == null)
		{
			return;
		}
		String key = key(place, patch);
		Observation previous = observation(place, patch);
		boolean wasWatched = !watching.add(key);
		if (previous != null && previous.getValue() == value)
		{
			return;
		}
		boolean exact = wasWatched && previous != null
			&& advancedOneStage(data.decode(patch.getKind(), previous.getValue()), after);
		Observation next = new Observation(value, now, exact);
		seen.put(key, next);
		store.put(storeKey(place, patch), next.write());
	}

	/** True when a patch has just moved on to its next growth stage, which happens on a growth tick. */
	static boolean advancedOneStage(@Nullable PatchState before, PatchState after)
	{
		return before != null
			&& before.getProduce() == after.getProduce()
			&& before.getCrop() == after.getCrop()
			&& (after.getCrop() == CropState.GROWING || after.getCrop() == CropState.HARVESTABLE)
			&& after.getStage() == before.getStage() + 1;
	}

	/** What a patch looked like when it was last seen, or null when it never has been. */
	@Nullable
	public Observation observation(FarmingData.Region place, FarmingData.Patch patch)
	{
		String key = key(place, patch);
		if (seen.containsKey(key))
		{
			return seen.get(key);
		}
		Observation stored = Observation.read(store.get(storeKey(place, patch)));
		seen.put(key, stored);
		return stored;
	}

	private static String key(FarmingData.Region place, FarmingData.Patch patch)
	{
		return place.getId() + "." + patch.getVarbit();
	}

	private static String storeKey(FarmingData.Region place, FarmingData.Patch patch)
	{
		return "farming." + key(place, patch);
	}
}
