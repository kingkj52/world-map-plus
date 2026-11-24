package com.worldmapplus.layers;

/**
 * What a floor is called in the plugin's text. The game's own text uses the UK way: the floor you walk in on is the ground floor
 * and the one above it is the 1st floor, so the floors of the map are the Ground floor, the 1st floor, the 2nd floor and the 3rd
 * floor. Every place the plugin names a floor goes through here.
 */
public final class FloorNames
{
	private FloorNames()
	{
	}

	/** The name of a floor: Ground floor, 1st floor, 2nd floor, 3rd floor. A floor below the ground is also the ground floor. */
	public static String name(int floor)
	{
		if (floor <= 0)
		{
			return "Ground floor";
		}
		return ordinal(floor) + " floor";
	}

	/** What a card says about the floor an icon is on: nothing for the ground floor, and otherwise the name, such as 1st floor. */
	public static String label(int floor)
	{
		return floor <= 0 ? "" : name(floor);
	}

	/** 1st, 2nd, 3rd, 4th and on. */
	static String ordinal(int number)
	{
		int lastTwo = number % 100;
		if (lastTwo >= 11 && lastTwo <= 13)
		{
			return number + "th";
		}
		switch (number % 10)
		{
			case 1:
				return number + "st";
			case 2:
				return number + "nd";
			case 3:
				return number + "rd";
			default:
				return number + "th";
		}
	}
}
