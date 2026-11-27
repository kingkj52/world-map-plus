package com.worldmapplus.unlock;

import net.runelite.api.gameval.*;

/**
 * The achievement diary tiers: twelve regions with four tiers each, numbered {@code region * 4 + tier} (the regions in the wiki's
 * order: Ardougne, Desert, Falador, Fremennik, Kandarin, Karamja, Kourend & Kebos, Lumbridge & Draynor, Morytania, Varrock, Western
 * Provinces, Wilderness; the tiers Easy, Medium, Hard, Elite), and the game variable that says whether each tier is done. The build
 * tools read the data's words for a tier into that number ({@code tools/requirement_checks.py}).
 */
public final class Diaries
{
	/** How many tiers there are in all. */
	public static final int COUNT = 48;

	/**
	 * The game variable that says a tier is done, by tier number: RuneLite's own {@code VarbitID} names. The varbit is 0 until the
	 * tier is done. For the first three Karamja tiers it is the task list's own "done" varbit, which other tools compare with 2; any
	 * value above 0 is taken as done here, so a value that only means "under way" can never mark something missing.
	 */
	private static final int[] VARBITS = {
		VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
		VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE,
		VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
		VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE,
		VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
		VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE,
		VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
		VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE,
		VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
		VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE,
		VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE,
		VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
		VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE,
		VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
		VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE,
		VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
		VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE,
		VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
		VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE,
		VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
		VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE,
		VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
		VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE,
	};

	private Diaries()
	{
	}

	/** The varbit that says the tier is done. */
	public static int varbit(int tier)
	{
		return VARBITS[tier];
	}
}
