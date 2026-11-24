package com.worldmapplus.farming;

/**
 * A crop, or the weeds that stand for an empty patch.
 * <p>
 * The figures are RuneLite's Time Tracking tables, read out of its source when the data is built.
 */
public final class Produce
{
	private final String name;
	private final int tickMinutes;
	private final int stages;
	private final int regrowMinutes;
	private final int lives;

	public Produce(String name, int tickMinutes, int stages, int regrowMinutes, int lives)
	{
		this.name = name;
		this.tickMinutes = tickMinutes;
		this.stages = stages;
		this.regrowMinutes = regrowMinutes;
		this.lives = lives;
	}

	/** The name shown to the player, such as {@code Ranarr}. */
	public String getName()
	{
		return name;
	}

	/** Minutes between one growth stage and the next. */
	public int getTickMinutes()
	{
		return tickMinutes;
	}

	/** How many states the crop has while growing. The last one is the last before it can be picked. */
	public int getStages()
	{
		return stages;
	}

	/** Minutes for a harvested crop to grow back, or 0 when it does not. */
	public int getRegrowMinutes()
	{
		return regrowMinutes;
	}

	/** How many times the crop can be picked before it is spent. */
	public int getLives()
	{
		return lives;
	}
}
