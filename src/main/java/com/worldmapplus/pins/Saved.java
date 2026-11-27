package com.worldmapplus.pins;

import com.google.gson.*;
import java.util.*;
import javax.annotation.*;

/**
 * One list of the player's own things, pins or routes, kept in memory and saved to the plugin's settings as one JSON value,
 * {@code {"<name>":[...],"nextId":n}}. Ids are never given out twice, even after a thing is removed, so a stale route cannot attach to
 * a new place. The list is capped, and a saved value that is damaged, hand edited or too long gives an empty list rather than an
 * error. The saved text as last read or written is kept, so a change made by someone else (a switch of RuneLite profile, an import
 * of settings) can be told from the store's own write. Every method that touches the list is synchronized on the store.
 */
abstract class Saved<T>
{
	/** A saved value longer than this is ignored, so a damaged or hand edited setting costs little to read. */
	static final int MAX_SAVED_CHARS = 1_000_000;

	private static final long MAX_NEXT_ID = 1L << 50;

	/** The longest name kept. */
	static final int MAX_NAME = 60;

	private final PinStore.Storage storage;
	private final Gson gson;
	private final String name;
	private final Class<T[]> type;
	private final int max;
	List<T> items = new ArrayList<>();
	private boolean loaded;
	int version;
	private long nextId = 1;
	private String lastJson = "";

	Saved(PinStore.Storage storage, Gson gson, String name, Class<T[]> type, int max)
	{
		this.storage = storage;
		this.gson = gson;
		this.name = name;
		this.type = type;
		this.max = max;
	}

	abstract long idOf(T item);

	/** The thing as kept, from one read from the saved value whose id is new and above 0, or null to leave it out. */
	@Nullable
	abstract T kept(T item);

	/** Increases whenever the list changes, so a holder of derived data knows to rebuild it. */
	public synchronized int getVersion()
	{
		return version;
	}

	public synchronized List<T> all()
	{
		ensureLoaded();
		return Collections.unmodifiableList(new ArrayList<>(items));
	}

	/** The thing with an id, or null. */
	@Nullable
	public synchronized T get(long id)
	{
		ensureLoaded();
		for (T item : items)
		{
			if (idOf(item) == id)
			{
				return item;
			}
		}
		return null;
	}

	/** The id for a new thing, or 0 when the list is full. */
	long newId()
	{
		ensureLoaded();
		if (items.size() >= max)
		{
			return 0;
		}
		long id = nextId;
		for (T item : items)
		{
			id = Math.max(id, idOf(item) + 1);
		}
		nextId = id + 1;
		return id;
	}

	/** Replaces the thing with an id through {@code change}, and saves. Returns false when there is no such thing. */
	boolean replace(long id, java.util.function.UnaryOperator<T> change)
	{
		ensureLoaded();
		for (int i = 0; i < items.size(); i++)
		{
			if (idOf(items.get(i)) == id)
			{
				items.set(i, change.apply(items.get(i)));
				changed();
				return true;
			}
		}
		return false;
	}

	void changed()
	{
		version++;
		JsonObject file = new JsonObject();
		file.add(name, gson.toJsonTree(items));
		file.addProperty("nextId", nextId);
		lastJson = gson.toJson(file);
		storage.write(lastJson);
	}

	void ensureLoaded()
	{
		if (!loaded)
		{
			loaded = true;
			read(storage.read());
		}
	}

	private void read(@Nullable String json)
	{
		lastJson = json == null ? "" : json;
		List<T> list = new ArrayList<>();
		long saved = 0;
		if (json != null && !json.isEmpty() && json.length() <= MAX_SAVED_CHARS)
		{
			try
			{
				JsonObject file = gson.fromJson(json, JsonObject.class);
				T[] all = file == null ? null : gson.fromJson(file.get(name), type);
				if (all != null)
				{
					JsonElement next = file.get("nextId");
					saved = next != null && next.isJsonPrimitive() ? next.getAsLong() : 0;
					Set<Long> seen = new HashSet<>();
					for (T item : all)
					{
						T kept = item == null || list.size() >= max || idOf(item) <= 0 || !seen.add(idOf(item)) ? null : kept(item);
						if (kept != null)
						{
							list.add(kept);
						}
					}
				}
			}
			catch (RuntimeException e)
			{
				list.clear();
				saved = 0;
			}
		}
		items = list;
		long highest = 0;
		for (T item : list)
		{
			highest = Math.max(highest, idOf(item));
		}
		// A hand edited number near the top of the range is not trusted, so ids can never wrap round.
		nextId = Math.max(Math.min(saved, MAX_NEXT_ID), Math.max(1, highest + 1));
	}

	/** A name as kept: no angle brackets (so a label never reads it as markup), single spaces, not empty, not too long. */
	static String clean(String name, String otherwise)
	{
		String text = name == null ? "" : name.replaceAll("[<>]", "").trim().replaceAll("\\s+", " ");
		text = text.isEmpty() ? otherwise : text;
		return text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
	}

	/**
	 * Takes in the saved value again if something other than this store changed it, such as a switch of RuneLite profile or an
	 * import of settings. Returns true when the list was replaced. The store's own writes are recognised and ignored.
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
}
