package com.worldmapplus.farming;

/** What a patch's variable says: a crop, what it is doing, and how far along it is. */
public final class PatchState
{
	private final Produce produce;
	private final CropState crop;
	private final int stage;

	public PatchState(Produce produce, CropState crop, int stage)
	{
		this.produce = produce;
		this.crop = crop;
		this.stage = stage;
	}

	public Produce getProduce()
	{
		return produce;
	}

	public CropState getCrop()
	{
		return crop;
	}

	/** Zero is the first stage. */
	public int getStage()
	{
		return stage;
	}

	/** How many stages the crop has in this state. */
	public int getStages()
	{
		return crop == CropState.HARVESTABLE || crop == CropState.FILLING ? produce.getLives() : produce.getStages();
	}

	/** Minutes between stages in this state, or 0 when the state does not advance by itself. */
	public int getTickMinutes()
	{
		switch (crop)
		{
			case HARVESTABLE:
				return produce.getRegrowMinutes();
			case GROWING:
				return produce.getTickMinutes();
			default:
				return 0;
		}
	}

	/** Stage changes still to come before the crop is finished growing, or regrowing. */
	public int stagesLeft()
	{
		return Math.max(0, getStages() - 1 - stage);
	}
}
