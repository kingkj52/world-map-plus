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
 * The player's pins, kept in memory and saved to the plugin's settings as one JSON value.
 * <p>
 * A pin is the player's own note on the map. Nothing is sent anywhere. The list is capped so a
 * damaged or hand-edited setting cannot make the plugin hold an unreasonable amount.
 */
@Singleton
public class PinStore
{
	/** A saved value longer than this is ignored, so a damaged or hand edited setting costs little to read. */
	static final int MAX_SAVED_CHARS = 1_000_000;

	private static final long MAX_NEXT_ID = 1L << 50;

	static final String KEY = "pins";
	public static final int MAX_PINS = 200;
	static final int MAX_NAME = 60;

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

	private final Storage storage;
	private final Gson gson;
	private List<Pin> pins = new ArrayList<>();
	private boolean loaded;
	private int version;

	/** Ids are never given out twice, even after a place is removed, so a stale route cannot attach to a new place. */
	private long nextId = 1;

	/** The saved text as last read or written, so a change made by someone else can be told from our own write. */
	private String lastJson = "";
	private final List<LongConsumer> removedListeners = new CopyOnWriteArrayList<>();

	@Inject
	public PinStore(ConfigManager configManager, Gson gson)
	{
		this(Storage.settings(configManager, KEY), gson);
	}

	public PinStore(Storage storage, Gson gson)
	{
		this.storage = storage;
		this.gson = gson;
	}

	/** Increases whenever the pins change, so a holder of derived data knows to rebuild it. */
	public synchronized int getVersion()
	{
		return version;
	}

	public synchronized List<Pin> all()
	{
		ensureLoaded();
		return Collections.unmodifiableList(new ArrayList<>(pins));
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
		ensureLoaded();
		if (pins.size() >= MAX_PINS)
		{
			return null;
		}
		long id = nextId;
		for (Pin existing : pins)
		{
			id = Math.max(id, existing.getId() + 1);
		}
		nextId = id + 1;
		Pin pin = new Pin(id, clean(name), map, x, y, cleanKind(kind), System.currentTimeMillis(), plane);
		pins.add(pin);
		changed();
		return pin;
	}

	/** Calls a listener with the id of every place that is removed, so a route can let go of it. */
	public void onRemoved(LongConsumer listener)
	{
		removedListeners.add(listener);
	}

	/** The pin with an id, or null. */
	@Nullable
	public synchronized Pin get(long id)
	{
		ensureLoaded();
		for (Pin pin : pins)
		{
			if (pin.getId() == id)
			{
				return pin;
			}
		}
		return null;
	}

	/** Gives a pin another name and saves. Returns true if there was such a pin. */
	public synchronized boolean rename(long id, String name)
	{
		ensureLoaded();
		for (int i = 0; i < pins.size(); i++)
		{
			if (pins.get(i).getId() == id)
			{
				pins.set(i, pins.get(i).renamed(clean(name)));
				changed();
				return true;
			}
		}
		return false;
	}

	/** Changes a pin's colour (0xRRGGBB, or 0 for the usual one), size in percent and whether it is hidden. */
	public synchronized boolean restyle(long id, int color, int size, boolean hidden)
	{
		ensureLoaded();
		for (int i = 0; i < pins.size(); i++)
		{
			if (pins.get(i).getId() == id)
			{
				pins.set(i, pins.get(i).styled(color & 0xFFFFFF, Math.max(Pin.MIN_SIZE, Math.min(Pin.MAX_SIZE, size)), hidden));
				changed();
				return true;
			}
		}
		return false;
	}

	/** Removes a pin by id. Returns true if there was one. */
	public synchronized boolean remove(long id)
	{
		ensureLoaded();
		boolean removed = pins.removeIf(pin -> pin.getId() == id);
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
		for (Pin pin : pins)
		{
			if (pin.getMap() != map)
			{
				continue;
			}
			double distance = Math.hypot(pin.getX() - x, pin.getY() - y);
			if (distance <= bestDistance)
			{
				bestDistance = distance;
				best = pin;
			}
		}
		return best;
	}

	static String clean(String name)
	{
		// Angle brackets are dropped so a name can never be read as markup by a label that renders it.
		String text = name == null ? "" : name.replaceAll("[<>]", "").trim().replaceAll("\\s+", " ");
		if (text.isEmpty())
		{
			text = "Pin";
		}
		return text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
	}

	static String cleanKind(String kind)
	{
		String text = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		return text.isEmpty() ? "pin" : text.length() > 16 ? text.substring(0, 16) : text;
	}

	private void changed()
	{
		version++;
		save();
	}

	private void save()
	{
		PinFile file = new PinFile();
		file.pins = pins;
		file.nextId = nextId;
		String json = gson.toJson(file);
		lastJson = json;
		storage.write(json);
	}

	private void ensureLoaded()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;
		read(storage.read());
	}

	private void read(@Nullable String json)
	{
		lastJson = json == null ? "" : json;
		PinFile file = parseFile(gson, json);
		pins = file.pins;
		long highest = 0;
		for (Pin pin : pins)
		{
			highest = Math.max(highest, pin.getId());
		}
		// A hand edited number near the top of the range is not trusted, so ids can never wrap round.
		nextId = Math.max(Math.min(file.nextId, MAX_NEXT_ID), Math.max(1, highest + 1));
	}

	/**
	 * Takes in the saved value again if something other than this store changed it, such as a switch of
	 * RuneLite profile or an import of settings. Returns true when the list was replaced. Our own writes
	 * are recognised and ignored.
	 */
	public synchronized boolean reloadIfChanged()
	{
		if (!loaded)
		{
			return false;
		}
		String now = storage.read();
		if ((now == null ? "" : now).equals(lastJson))
		{
			return false;
		}
		read(now);
		version++;
		return true;
	}

	/** Reads saved pins. Anything unreadable gives an empty list rather than an error. */
	static List<Pin> parse(Gson gson, @Nullable String json)
	{
		return parseFile(gson, json).pins;
	}

	/** Largest distance from the origin a saved place may have, far beyond any map. */
	private static final double MAX_COORDINATE = 100_000;

	private static PinFile parseFile(Gson gson, @Nullable String json)
	{
		PinFile result = new PinFile();
		result.pins = new ArrayList<>();
		if (json == null || json.isEmpty() || json.length() > MAX_SAVED_CHARS)
		{
			return result;
		}
		try
		{
			PinFile file = gson.fromJson(json, PinFile.class);
			if (file == null || file.pins == null)
			{
				return result;
			}
			result.nextId = file.nextId;
			Set<Long> seen = new HashSet<>();
			for (Pin pin : file.pins)
			{
				if (pin == null || result.pins.size() >= MAX_PINS || pin.getId() <= 0 || !seen.add(pin.getId())
					|| !sane(pin.getX()) || !sane(pin.getY()))
				{
					continue;
				}
				result.pins.add(new Pin(pin.getId(), clean(pin.getName()), pin.getMap(), pin.getX(), pin.getY(),
					cleanKind(pin.getKind()), Math.max(0, pin.getCreated()), pin.getPlane(), pin.getColor(), pin.getSize(),
					pin.isHidden()));
			}
		}
		catch (JsonParseException e)
		{
			PinFile empty = new PinFile();
			empty.pins = new ArrayList<>();
			return empty;
		}
		return result;
	}

	private static boolean sane(double value)
	{
		return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
	}

	/** The shape of the saved value. Filled by Gson. */
	static final class PinFile
	{
		List<Pin> pins;
		long nextId;
	}
}
