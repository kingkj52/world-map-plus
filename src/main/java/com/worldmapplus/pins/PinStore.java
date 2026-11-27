package com.worldmapplus.pins;

import com.google.gson.*;
import com.worldmapplus.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import javax.annotation.*;
import javax.inject.*;
import net.runelite.client.config.*;

/**
 * The player's pins, kept in memory and saved to the plugin's settings as one JSON value (see {@link Saved}).
 * <p>
 * A pin is the player's own note on the map. Nothing is sent anywhere. The list is capped so a
 * damaged or hand-edited setting cannot make the plugin hold an unreasonable amount.
 */
@Singleton
public class PinStore extends Saved<Pin>
{
	static final String KEY = "pins";
	public static final int MAX_PINS = 200;

	/** Largest distance from the origin a saved place may have, far beyond any map. */
	private static final double MAX_COORDINATE = 100_000;

	/** Where the saved value lives. The plugin uses its settings; tests use memory. */
	public interface Storage
	{
		@Nullable
		String read();

		void write(String json);

		/** A store that keeps its value in the plugin's settings under a key. */
		static Storage settings(ConfigManager configManager, String key)
		{
			return new Storage()
			{
				@Override
				public String read()
				{
					return configManager.getConfiguration(WorldMapPlusConfig.GROUP, key);
				}

				@Override
				public void write(String json)
				{
					configManager.setConfiguration(WorldMapPlusConfig.GROUP, key, json);
				}
			};
		}

		/** A store that keeps its value in memory only, for tests and previews. */
		static Storage inMemory()
		{
			return new Storage()
			{
				private String value;

				@Override
				public String read()
				{
					return value;
				}

				@Override
				public void write(String json)
				{
					value = json;
				}
			};
		}
	}

	private final List<LongConsumer> removedListeners = new CopyOnWriteArrayList<>();

	@Inject
	public PinStore(ConfigManager configManager, Gson gson)
	{
		this(Storage.settings(configManager, KEY), gson);
	}

	public PinStore(Storage storage, Gson gson)
	{
		super(storage, gson, KEY, Pin[].class, MAX_PINS);
	}

	@Override
	long idOf(Pin pin)
	{
		return pin.getId();
	}

	@Override
	Pin kept(Pin pin)
	{
		return !sane(pin.getX()) || !sane(pin.getY()) ? null : new Pin(pin.getId(), clean(pin.getName()), pin.getMap(), pin.getX(),
			pin.getY(), cleanKind(pin.getKind()), Math.max(0, pin.getCreated()), pin.getPlane(), pin.getColor(), pin.getSize(),
			pin.isHidden());
	}

	/** Adds a pin and saves. Returns it, or null when the list is full. */
	@Nullable
	public synchronized Pin add(String name, int map, double x, double y)
	{
		return add(name, "pin", map, x, y);
	}

	/** Adds a saved place of a kind, such as a shop, and saves. Returns it, or null when the list is full. */
	@Nullable
	public synchronized Pin add(String name, String kind, int map, double x, double y)
	{
		return add(name, kind, map, x, y, 0);
	}

	/** Adds a saved place on a floor and saves. Returns it, or null when the list is full. */
	@Nullable
	public synchronized Pin add(String name, String kind, int map, double x, double y, int plane)
	{
		long id = newId();
		if (id == 0)
		{
			return null;
		}
		Pin pin = new Pin(id, clean(name), map, x, y, cleanKind(kind), System.currentTimeMillis(), plane);
		items.add(pin);
		changed();
		return pin;
	}

	/** Calls a listener with the id of every place that is removed, so a route can let go of it. */
	public void onRemoved(LongConsumer listener)
	{
		removedListeners.add(listener);
	}

	/** Gives a pin another name and saves. Returns true if there was such a pin. */
	public synchronized boolean rename(long id, String name)
	{
		return replace(id, pin -> pin.renamed(clean(name)));
	}

	/** Changes a pin's colour (0xRRGGBB, or 0 for the usual one), size in percent and whether it is hidden. */
	public synchronized boolean restyle(long id, int color, int size, boolean hidden)
	{
		return replace(id, pin -> pin.styled(color & 0xFFFFFF, Math.max(Pin.MIN_SIZE, Math.min(Pin.MAX_SIZE, size)), hidden));
	}

	/** Removes a pin by id. Returns true if there was one. */
	public synchronized boolean remove(long id)
	{
		ensureLoaded();
		boolean removed = items.removeIf(pin -> pin.getId() == id);
		if (removed)
		{
			changed();
			for (LongConsumer listener : removedListeners)
			{
				listener.accept(id);
			}
		}
		return removed;
	}

	/** The pin on a map nearest a point, if one is within {@code radiusTiles}. */
	@Nullable
	public synchronized Pin nearest(int map, double x, double y, double radiusTiles)
	{
		ensureLoaded();
		Pin best = null;
		double bestDistance = radiusTiles;
		for (Pin pin : items)
		{
			double distance = Math.hypot(pin.getX() - x, pin.getY() - y);
			if (pin.getMap() == map && distance <= bestDistance)
			{
				bestDistance = distance;
				best = pin;
			}
		}
		return best;
	}

	static String clean(String name)
	{
		return clean(name, "Pin");
	}

	static String cleanKind(String kind)
	{
		String text = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		return text.isEmpty() ? "pin" : text.length() > 16 ? text.substring(0, 16) : text;
	}

	private static boolean sane(double value)
	{
		return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
	}
}
