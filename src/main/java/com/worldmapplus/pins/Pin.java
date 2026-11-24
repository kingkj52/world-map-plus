package com.worldmapplus.pins;

/**
 * A place the player saved on the map: a pin dropped by hand, or a shop, person or other place saved
 * from a card or a search. Held and saved on the player's own machine only.
 * <p>
 * Gson fills the fields directly, so they are plain non-final fields.
 */
public final class Pin
{
	private long id;
	private String name;
	private int map;
	private double x;
	private double y;
	private String kind;
	private long created;
	private int plane;
	private int color;
	private int size;
	private boolean hidden;

	public Pin()
	{
	}

	public Pin(long id, String name, int map, double x, double y)
	{
		this(id, name, map, x, y, "pin", 0, 0);
	}

	public Pin(long id, String name, int map, double x, double y, String kind, long created)
	{
		this(id, name, map, x, y, kind, created, 0);
	}

	public Pin(long id, String name, int map, double x, double y, String kind, long created, int plane)
	{
		this(id, name, map, x, y, kind, created, plane, 0, DEFAULT_SIZE, false);
	}

	public Pin(long id, String name, int map, double x, double y, String kind, long created, int plane, int color, int size,
		boolean hidden)
	{
		this.color = color & 0xFFFFFF;
		this.size = size;
		this.hidden = hidden;
		this.id = id;
		this.name = name;
		this.map = map;
		this.x = x;
		this.y = y;
		this.kind = kind;
		this.created = created;
		this.plane = plane;
	}

	/** What the place is: pin, shop, npc, monster, quest, transport, teleport, place or item. */
	public String getKind()
	{
		return kind == null || kind.isEmpty() ? "pin" : kind;
	}

	/** When it was saved, as milliseconds since 1970, or 0 for pins saved before this was kept. */
	public long getCreated()
	{
		return created;
	}

	/** The floor the place is on, 0 to 3. Pins saved before floors were kept are on floor 0. */
	public int getPlane()
	{
		return Math.max(0, Math.min(3, plane));
	}

	/** The least and greatest size of a pin, as a percentage of its usual size. */
	public static final int MIN_SIZE = 50;
	public static final int MAX_SIZE = 250;
	public static final int DEFAULT_SIZE = 100;

	/** The pin's colour as 0xRRGGBB, or 0 for the usual colour. */
	public int getColor()
	{
		return color & 0xFFFFFF;
	}

	/** How big it is drawn, as a percentage of the usual size. */
	public int getSize()
	{
		return size == 0 ? DEFAULT_SIZE : Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
	}

	/** True when the pin is not drawn on the map. A route can still go through it. */
	public boolean isHidden()
	{
		return hidden;
	}

	/** A copy with another colour, size and visibility. */
	public Pin styled(int newColor, int newSize, boolean newHidden)
	{
		return new Pin(id, name, map, x, y, getKind(), created, plane, newColor, newSize, newHidden);
	}

	/** A copy with another name. */
	public Pin renamed(String newName)
	{
		return new Pin(id, newName, map, x, y, getKind(), created, plane, color, size, hidden);
	}

	public long getId()
	{
		return id;
	}

	public String getName()
	{
		return name == null ? "" : name;
	}

	public int getMap()
	{
		return map;
	}

	public double getX()
	{
		return x;
	}

	public double getY()
	{
		return y;
	}
}
