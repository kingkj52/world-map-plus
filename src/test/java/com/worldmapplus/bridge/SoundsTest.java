package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.SoundEffectID;
import org.junit.Test;

public class SoundsTest
{
	private boolean on;
	private long now = 1000;
	private final List<Integer> played = new ArrayList<>();
	private final Sounds sounds = new Sounds(() -> on, played::add, () -> now);

	@Test
	public void nothingPlaysWhileTheSettingIsOff()
	{
		sounds.cue(Sounds.Cue.OPEN);
		assertEquals(0, played.size());
	}

	@Test
	public void onlyTheGamesOwnSoundIdsAreUsedAndNeverMoreThanOneIn150Millis()
	{
		on = true;
		sounds.cue(Sounds.Cue.OPEN);
		now += 100;
		sounds.cue(Sounds.Cue.CHOOSE);
		assertEquals(1, played.size());
		now += 60;
		sounds.cue(Sounds.Cue.PIN);
		assertEquals(2, played.size());
		assertEquals(SoundEffectID.UI_BOOP, (int) played.get(0));
		assertEquals(SoundEffectID.UI_BOOP, (int) played.get(1));
		for (Sounds.Cue cue : Sounds.Cue.values())
		{
			boolean known = cue.id() == SoundEffectID.UI_BOOP;
			assertEquals(cue.name(), true, known);
		}
	}

	@Test
	public void aClockThatGoesBackDoesNotMuteTheCues()
	{
		on = true;
		sounds.cue(Sounds.Cue.PIN);
		now -= 5000;
		sounds.cue(Sounds.Cue.PIN);
		assertEquals(2, played.size());
	}
}
