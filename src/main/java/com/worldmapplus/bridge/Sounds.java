package com.worldmapplus.bridge;

import java.util.function.*;
import lombok.*;
import net.runelite.api.*;

/**
 * Short cues from the game's own sound effects, for the setting "Sounds" (off by default). Only the game's sound ids
 * ({@link SoundEffectID}) are used, through {@code Client.playSoundEffect(int)}, which plays at the player's own sound effect
 * volume. Never more than one cue in 150 ms. Must be called on the client thread, where the map draws and chat is read.
 */
@RequiredArgsConstructor
public final class Sounds
{
	/** What a cue is for. */
	public enum Cue
	{
		OPEN(SoundEffectID.UI_BOOP),
		CLOSE(SoundEffectID.UI_BOOP),
		CHOOSE(SoundEffectID.UI_BOOP),
		PIN(SoundEffectID.UI_BOOP);

		private final int id;

		Cue(int id)
		{
			this.id = id;
		}

		public int id()
		{
			return id;
		}
	}

	static final long MIN_GAP_MILLIS = 150;

	private final BooleanSupplier enabled;
	private final IntConsumer player;
	private final LongSupplier clock;
	private long last = Long.MIN_VALUE;

	/** Plays the cue if the setting is on and the last cue was long enough ago. */
	public void cue(Cue cue)
	{
		if (!enabled.getAsBoolean())
		{
			return;
		}
		long now = clock.getAsLong();
		if (last != Long.MIN_VALUE && now - last < MIN_GAP_MILLIS && now >= last)
		{
			return;
		}
		last = now;
		player.accept(cue.id());
	}
}
