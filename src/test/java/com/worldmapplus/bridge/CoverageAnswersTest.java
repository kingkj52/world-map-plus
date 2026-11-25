package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/** The coverage and focus questions of Plugin API v3, as the Helper asks them for version 1 plugins. */
public class CoverageAnswersTest
{
	private final ExternalOverlays overlays = new ExternalOverlays();
	private final PartnerDouble asker = new PartnerDouble(overlays, "asker");

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

	/** What would be posted on a client tick at this time. */
	private List<ExternalOverlays.Reply> tick(long now)
	{
		overlays.partners().answerAsks(now);
		return overlays.drainReplies(now);
	}

	private List<?> tiles(Map<String, Object> reply, String key)
	{
		List<Object> values = new ArrayList<>();
		for (Object tile : (List<?>) reply.get("tiles"))
		{
			values.add(((Map<?, ?>) tile).get(key));
		}
		return values;
	}

	@Test
	public void aQuestionIsOnlyRecordedAndIsAnsweredOnALaterTick()
	{
		overlays.setCoverage(coverage());
		asker.request("coverage", "id", "q1", "point", new WorldPoint(3615, 9469, 0));
		assertTrue("nothing is posted from inside the asker's own post", overlays.drainReplies(System.currentTimeMillis()).isEmpty());
		List<ExternalOverlays.Reply> replies = tick(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("reply", replies.get(0).getName());
		Map<String, Object> data = replies.get(0).getData();
		assertEquals("q1", data.get("id"));
		assertEquals("coverage", data.get("topic"));
		assertEquals(Arrays.asList(true), tiles(data, "covered"));
		assertEquals(Arrays.asList(true), tiles(data, "exact"));
		assertEquals(Arrays.asList("Guardians of the Rift"), tiles(data, "mapName"));
		assertEquals(Arrays.asList("area"), tiles(data, "mapKind"));
		assertTrue("answered once", tick(System.currentTimeMillis()).isEmpty());
	}

	@Test
	public void aBatchIsAnsweredInTheOrderAskedAndAnUncoveredTileSaysSoWithTheIdEchoed()
	{
		overlays.setCoverage(coverage());
		asker.request("coverage", "id", "batch-7",
			"points", Arrays.asList(new WorldPoint(3615, 9469, 0), new WorldPoint(1, 1, 0), new WorldPoint(3000, 3000, 0)));
		Map<String, Object> data = tick(System.currentTimeMillis()).get(0).getData();
		assertEquals("batch-7", data.get("id"));
		assertEquals(Arrays.asList(true, false, true), tiles(data, "covered"));
		assertEquals(Arrays.asList(true, false, false), tiles(data, "exact"));
		assertEquals(Arrays.asList("area", "", "surface"), tiles(data, "mapKind"));
	}

	@Test
	public void aQuestionWithoutAGoodIdGetsNoAnswerAndBadPointsAreRefused()
	{
		overlays.setCoverage(coverage());
		asker.request("coverage", "point", new WorldPoint(3615, 9469, 0));
		asker.request("coverage", "id", "", "point", new WorldPoint(3615, 9469, 0));
		asker.request("coverage", "id", "has space", "point", new WorldPoint(3615, 9469, 0));
		asker.request("coverage", "id", repeat('a', 65), "point", new WorldPoint(3615, 9469, 0));
		assertTrue(tick(System.currentTimeMillis()).isEmpty());
		asker.request("coverage", "id", "ok", "point", "not a point");
		asker.request("coverage", "id", "ok2", "points", Arrays.asList(new WorldPoint(3615, 9469, 0), "bad"));
		asker.request("coverage", "id", "ok3", "points", new ArrayList<>());
		List<ExternalOverlays.Reply> replies = tick(System.currentTimeMillis());
		assertEquals(3, replies.size());
		for (ExternalOverlays.Reply reply : replies)
		{
			assertEquals("a list that is not whole is refused whole", "bad_request", reply.getData().get("reason"));
		}
	}

	@Test
	public void aQuestionAsksAboutNoMoreThanTheCapAndTheRateIsLimited()
	{
		overlays.setCoverage(coverage());
		List<WorldPoint> many = new ArrayList<>();
		for (int i = 0; i < 16; i++)
		{
			many.add(new WorldPoint(3615, 9469, 0));
		}
		asker.request("coverage", "id", "full", "points", many);
		assertEquals(Partners.MAX_COVER_POINTS, ((List<?>) tick(System.currentTimeMillis()).get(0).getData().get("tiles")).size());
		many.add(new WorldPoint(3615, 9469, 0));
		asker.request("coverage", "id", "big", "points", many);
		assertEquals("bad_request", tick(System.currentTimeMillis()).get(0).getData().get("reason"));
		for (int i = 0; i < 50; i++)
		{
			asker.request("coverage", "id", "q" + i, "point", new WorldPoint(3615, 9469, 0));
		}
		int answered = tick(System.currentTimeMillis()).size();
		assertTrue("at most the rate, got " + answered, answered <= Partners.PER_SECOND);
	}

	@Test
	public void questionsWaitUntilTheMapsAreLoadedAndAreDroppedWhenTooOld()
	{
		asker.request("coverage", "id", "early", "point", new WorldPoint(3615, 9469, 0));
		long now = System.currentTimeMillis();
		assertTrue("no coverage yet: nothing to say", tick(now).isEmpty());
		overlays.setCoverage(coverage());
		assertEquals("it waited and is answered", 1, tick(now + 100).size());
		overlays.setCoverage(null);
		asker.request("coverage", "id", "late", "point", new WorldPoint(3615, 9469, 0));
		overlays.setCoverage(coverage());
		assertTrue("too old is dropped", tick(now + ExternalOverlays.ANSWER_LIFETIME_MILLIS + 5_000).isEmpty());
	}

	@Test
	public void aFocusWithAnIdIsAnsweredOnceItIsApplied()
	{
		asker.request("focus", "point", new WorldPoint(3615, 9469, 0), "id", "f1", "exact", true);
		assertTrue("nothing is answered before the map has acted", tick(System.currentTimeMillis()).isEmpty());
		ExternalOverlays.FocusRequest request = overlays.takeFocus();
		assertNotNull(request);
		assertEquals("f1", request.getId());
		assertTrue(request.isExact());
		overlays.answerFocus(request, false, "", "", "no_map");
		List<ExternalOverlays.Reply> replies = overlays.drainReplies(System.currentTimeMillis());
		assertEquals(1, replies.size());
		assertEquals("reply", replies.get(0).getName());
		assertEquals("focus", replies.get(0).getData().get("topic"));
		assertEquals("f1", replies.get(0).getData().get("id"));
		assertEquals(false, replies.get(0).getData().get("ok"));
		assertEquals("no_map", replies.get(0).getData().get("reason"));
	}

	@Test
	public void aFocusWithoutAnIdIsNeverAnswered()
	{
		asker.request("focus", "point", new WorldPoint(3615, 9469, 0));
		tick(System.currentTimeMillis());
		ExternalOverlays.FocusRequest request = overlays.takeFocus();
		assertTrue(request.getId().isEmpty());
		assertFalse(request.isExact());
		overlays.answerFocus(request, true, "x", "area", "ok");
		assertTrue(overlays.drainReplies(System.currentTimeMillis()).isEmpty());
	}

	@Test
	public void shuttingDownForgetsEverythingWaiting()
	{
		overlays.setCoverage(coverage());
		asker.request("coverage", "id", "q", "point", new WorldPoint(3615, 9469, 0));
		overlays.clear();
		assertTrue(tick(System.currentTimeMillis()).isEmpty());
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
