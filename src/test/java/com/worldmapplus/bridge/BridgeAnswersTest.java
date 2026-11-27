package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

public class BridgeAnswersTest
{
	private static PluginMessage message(String name, Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2)
		{
			data.put((String) keyValues[i], keyValues[i + 1]);
		}
		return new PluginMessage(ExternalOverlays.NAMESPACE, name, data);
	}

	/** Pretends the tile (3615, 9469) is on a map drawn from the game's data, and (3000, 3000) is on the surface by the nearest map. */
	private static ExternalOverlays.Coverage coverage()
	{
		return (x, y) ->
		{
			if (x == 3615)
			{
				return new ExternalOverlays.Cover(true, true, "Guardians of the Rift", "area");
			}
			if (x == 3000)
			{
				return new ExternalOverlays.Cover(true, false, "Gielinor Surface", "surface");
			}
			return new ExternalOverlays.Cover(false, false, "", "");
		};
	}

	@Test
	public void aQuestionIsOnlyRecordedAndIsAnsweredOnALaterDrain()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.setCoverage(coverage());
		overlays.onPluginMessage(message("covers", "id", "q1", "point", new WorldPoint(3615, 9469, 0)));
		// Nothing is posted by the subscriber itself: the answer exists only once the plugin drains on a tick.
		List<ExternalOverlays.Reply> replies = overlays.drainReplies(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("covers_reply", replies.get(0).getName());
		Map<String, Object> data = replies.get(0).getData();
		assertEquals("q1", data.get("id"));
		assertEquals(Arrays.asList(true), data.get("covered"));
		assertEquals(Arrays.asList(true), data.get("exact"));
		assertEquals(Arrays.asList("Guardians of the Rift"), data.get("mapNames"));
		assertEquals(Arrays.asList("area"), data.get("mapKinds"));
		assertTrue("drained once", overlays.drainReplies(System.currentTimeMillis()).isEmpty());
	}

	@Test
	public void aBatchIsAnsweredInTheOrderAskedAndAnUncoveredTileSaysSoWithTheIdEchoed()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.setCoverage(coverage());
		overlays.onPluginMessage(message("covers", "id", "batch-7",
			"points", Arrays.asList(new WorldPoint(3615, 9469, 0), new WorldPoint(1, 1, 0), new WorldPoint(3000, 3000, 0))));
		Map<String, Object> data = overlays.drainReplies(System.currentTimeMillis()).get(0).getData();
		assertEquals("batch-7", data.get("id"));
		assertEquals(Arrays.asList(true, false, true), data.get("covered"));
		assertEquals(Arrays.asList(true, false, false), data.get("exact"));
		assertEquals(Arrays.asList("area", "", "surface"), data.get("mapKinds"));
	}

	@Test
	public void aQuestionWithoutAGoodIdOrWithBadPointsGetsNoAnswer()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.setCoverage(coverage());
		overlays.onPluginMessage(message("covers", "point", new WorldPoint(3615, 9469, 0)));
		overlays.onPluginMessage(message("covers", "id", "", "point", new WorldPoint(3615, 9469, 0)));
		overlays.onPluginMessage(message("covers", "id", "has space", "point", new WorldPoint(3615, 9469, 0)));
		overlays.onPluginMessage(message("covers", "id", repeat('a', 65), "point", new WorldPoint(3615, 9469, 0)));
		overlays.onPluginMessage(message("covers", "id", "ok", "point", "not a point"));
		overlays.onPluginMessage(message("covers", "id", "ok2", "points", Arrays.asList(new WorldPoint(3615, 9469, 0), "bad")));
		overlays.onPluginMessage(message("covers", "id", "ok3", "points", new ArrayList<>()));
		assertTrue(overlays.drainReplies(System.currentTimeMillis()).isEmpty());
	}

	@Test
	public void aQuestionAsksAboutNoMoreThanTheCapAndTheRateIsLimited()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.setCoverage(coverage());
		List<WorldPoint> many = new ArrayList<>();
		for (int i = 0; i < 100; i++)
		{
			many.add(new WorldPoint(3615, 9469, 0));
		}
		overlays.onPluginMessage(message("covers", "id", "big", "points", many));
		List<?> covered = (List<?>) overlays.drainReplies(System.currentTimeMillis()).get(0).getData().get("covered");
		assertEquals(ExternalOverlays.MAX_COVER_POINTS, covered.size());
		for (int i = 0; i < 50; i++)
		{
			overlays.onPluginMessage(message("covers", "id", "q" + i, "point", new WorldPoint(3615, 9469, 0)));
		}
		int answered = overlays.drainReplies(System.currentTimeMillis()).size();
		assertTrue("at most the rate, got " + answered, answered <= ExternalOverlays.MAX_ASKS_PER_SECOND);
	}

	@Test
	public void questionsWaitUntilTheMapsAreLoadedAndAreDroppedWhenTooOld()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("covers", "id", "early", "point", new WorldPoint(3615, 9469, 0)));
		long now = System.currentTimeMillis();
		assertTrue("no coverage yet: nothing to say", overlays.drainReplies(now).isEmpty());
		overlays.setCoverage(coverage());
		assertEquals("it waited and is answered", 1, overlays.drainReplies(now + 100).size());
		overlays.setCoverage(null);
		overlays.onPluginMessage(message("covers", "id", "late", "point", new WorldPoint(3615, 9469, 0)));
		overlays.setCoverage(coverage());
		assertTrue("too old is dropped", overlays.drainReplies(now + ExternalOverlays.ANSWER_LIFETIME_MILLIS + 5_000).isEmpty());
	}

	@Test
	public void aFocusWithAnIdIsAnsweredOnceItIsApplied()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("focus", "point", new WorldPoint(3615, 9469, 0), "id", "f1", "exact", true));
		ExternalOverlays.FocusRequest request = overlays.takeFocus();
		assertNotNull(request);
		assertEquals("f1", request.getId());
		assertTrue(request.isExact());
		assertTrue("nothing is answered before the map has acted", overlays.drainReplies(System.currentTimeMillis()).isEmpty());
		overlays.answerFocus(request, false, "", "", "no_map");
		List<ExternalOverlays.Reply> replies = overlays.drainReplies(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("focus_result", replies.get(0).getName());
		assertEquals("f1", replies.get(0).getData().get("id"));
		assertEquals(false, replies.get(0).getData().get("ok"));
		assertEquals("no_map", replies.get(0).getData().get("reason"));
	}

	@Test
	public void aFocusWithoutAnIdIsNeverAnswered()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("focus", "point", new WorldPoint(3615, 9469, 0)));
		ExternalOverlays.FocusRequest request = overlays.takeFocus();
		assertNull(request.getId().isEmpty() ? null : request.getId());
		assertFalse(request.isExact());
		overlays.answerFocus(request, true, "x", "area", "ok");
		assertTrue(overlays.drainReplies(System.currentTimeMillis()).isEmpty());
	}

	@Test
	public void shuttingDownForgetsEverythingWaiting()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.setCoverage(coverage());
		overlays.onPluginMessage(message("covers", "id", "q", "point", new WorldPoint(3615, 9469, 0)));
		overlays.clear();
		assertTrue(overlays.drainReplies(System.currentTimeMillis()).isEmpty());
	}

	@Test
	public void kindsAreWordsAndNeverTheInternalNumbers()
	{
		assertEquals("surface", ExternalOverlays.kindOf(0));
		assertEquals("submap", ExternalOverlays.kindOf(7));
		assertEquals("extra", ExternalOverlays.kindOf(10_050));
		assertEquals("area", ExternalOverlays.kindOf(11_000));
	}

	private static String repeat(char c, int n)
	{
		char[] chars = new char[n];
		Arrays.fill(chars, c);
		return new String(chars);
	}
}
