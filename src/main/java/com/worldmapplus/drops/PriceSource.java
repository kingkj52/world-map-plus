package com.worldmapplus.drops;

/**
 * Item prices as RuneLite's own item manager knows them. Reading never blocks and never asks anything of the game: {@link #price}
 * answers from what has already been read, and {@link #request} asks for the missing ones to be read later (on the client thread),
 * after which {@link #version} changes so a view can look again.
 */
public interface PriceSource
{
	/** No prices at all: every price unknown. */
	PriceSource NONE = new PriceSource()
	{
		@Override
		public long price(int itemId)
		{
			return -1;
		}

		@Override
		public void request(int[] itemIds)
		{
		}

		@Override
		public int version()
		{
			return 0;
		}
	};

	/** The price of one item in coins, or -1 when it is not known (yet, or at all: untradeable items have none). */
	long price(int itemId);

	/** Asks for the prices of these items to be read, if they are not known already. Returns at once. */
	void request(int[] itemIds);

	/** Changes whenever new prices have been read. */
	int version();
}
