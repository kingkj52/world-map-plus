package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

/**
 * What this plugin reads in its namespace since Plugin API version 1 moved to the Helper: the hops of a route, what the Helper asks
 * (focus, follow) and pushes (layers, the game map) through version 3, and that version 1 is never answered here.
 */
public class ExternalOverlaysTest
{
	private static final WorldPoint AT = new WorldPoint(3222, 3218, 0);

	private static PluginMessage message(String name, Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2)
		{
			data.put((String) keyValues[i], keyValues[i + 1]);
		}
		return new PluginMessage(ExternalOverlays.NAMESPACE, name, data);
	}

	private static int seq;

	/** Sends a v3 request (focus, follow) or state (layers, game_map) as the Helper would, and lets a client tick handle it. */
	private static void send(ExternalOverlays overlays, String topic, Object... keyValues)
	{
		PluginMessage message = message("focus".equals(topic) || "follow".equals(topic) ? "request" : "state", keyValues);
		message.getData().put("owner", "worldmapplus-helper");
		message.getData().put("topic", topic);
		message.getData().put("seq", ++seq);
		overlays.onPluginMessage(message);
		overlays.partners().answerAsks(System.currentTimeMillis());
	}

	@Test
	public void version1IsNotAnsweredHereSoTheHelperIsTheOnlyOneToAnswer()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.setCoverage((x, y) -> new ExternalOverlays.Cover(true, true, "Gielinor", "surface"));
		for (String name : new String[] {"hello", "marker", "marker_clear", "line", "line_clear", "focus", "covers"})
		{
			overlays.onPluginMessage(message(name, "id", "v1:" + name, "point", AT, "points", Arrays.asList(AT, AT)));
		}
		assertTrue("nothing waits to be posted", overlays.idle());
		assertTrue(overlays.drainReplies(System.currentTimeMillis()).isEmpty());
		assertNull("no focus request", overlays.takeFocus());
		assertFalse(Partners.REQUESTS.contains("marker"));
		assertFalse(Partners.REQUESTS.contains("hello"));
		assertTrue(Partners.REQUESTS.containsAll(Arrays.asList("focus", "coverage", "follow")));
		assertTrue(Partners.STATES.containsAll(Arrays.asList("layers", "game_map")));
	}

	@Test
	public void shortestPathTransportsBecomeHops()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		List<WorldPoint> from = new ArrayList<>(Arrays.asList(new WorldPoint(2760, 3238, 0), new WorldPoint(3000, 3000, 0)));
		List<WorldPoint> to = new ArrayList<>(Arrays.asList(new WorldPoint(2792, 3414, 0), new WorldPoint(3100, 3100, 0)));
		Map<String, Object> data = new HashMap<>();
		data.put("origin", from);
		data.put("destination", to);
		data.put("displayInfo", new ArrayList<>(Arrays.asList("Charter ship", "Teleport")));
		overlays.onPluginMessage(new PluginMessage("shortestpath", "transports", data));
		assertEquals(2, overlays.hops().size());
		assertEquals("Charter ship", overlays.hops().get(0).getLabel());

		// A newer message replaces the old hops.
		overlays.onPluginMessage(new PluginMessage("shortestpath", "transports", new HashMap<>()));
		assertTrue(overlays.hops().isEmpty());

		overlays.onPluginMessage(new PluginMessage("shortestpath", "transports", data));
		overlays.clearHops();
		assertTrue(overlays.hops().isEmpty());
	}

	@Test
	public void aFocusRequestIsDeliveredOnceAndABadOneIsIgnored()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		send(overlays, "focus", "point", AT, "zoom", 12.0, "name", "Here", "exact", true, "id", "f1");
		ExternalOverlays.FocusRequest request = overlays.takeFocus();
		assertNotNull(request);
		assertEquals(AT, request.getPoint());
		assertEquals(12.0, request.getZoom(), 1e-9);
		assertTrue(request.isExact());
		assertNull(overlays.takeFocus());
		send(overlays, "focus", "point", "nope");
		send(overlays, "focus", "zoom", 5);
		assertNull(overlays.takeFocus());
		send(overlays, "focus", "point", AT, "zoom", -4.0);
		assertEquals(0, overlays.takeFocus().getZoom(), 0);
	}

	@Test
	public void followRequestsAreCheckedAndAStartKeepsItsPlace()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		send(overlays, "follow", "op", "start", "name", "Zezima");
		send(overlays, "follow", "op", "dance", "name", "Zezima", "point", AT);
		send(overlays, "follow", "op", "update", "point", AT);
		assertNull(overlays.takeFollow());
		send(overlays, "follow", "op", "start", "name", "Zezima", "point", AT);
		send(overlays, "follow", "op", "update", "name", "Zezima", "point", new WorldPoint(1, 2, 0));
		ExternalOverlays.FollowRequest request = overlays.takeFollow();
		assertEquals("start", request.getOp());
		assertEquals(new WorldPoint(1, 2, 0), request.getPoint());
		assertNull(overlays.takeFollow());
		send(overlays, "follow", "op", "gone", "name", "Zezima");
		assertEquals("gone", overlays.takeFollow().getOp());
	}

	@Test
	public void followEndingIsTold()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.followEnded("Zezima");
		List<ExternalOverlays.Reply> replies = overlays.drainReplies(System.currentTimeMillis());
		assertEquals("state", replies.get(0).getName());
		assertEquals("follow_ended", replies.get(0).getData().get("topic"));
		assertEquals("Zezima", replies.get(0).getData().get("name"));
	}

	@Test
	public void layersAndTheGameMapStateAreReadAndCleaned()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		assertTrue(overlays.pluginLayers().isEmpty());
		Map<String, Object> plugins = new LinkedHashMap<>();
		plugins.put("Quest Helper", 3);
		plugins.put("  ", 1);
		plugins.put("Bad|Name", "many");
		plugins.put("Sailing\n", 2);
		Map<String, Object> sent = new LinkedHashMap<>();
		for (int i = 0; i < 100; i++)
		{
			sent.put("layer" + i, i);
		}
		send(overlays, "layers", "plugins", plugins, "sent", sent);
		assertEquals(2, overlays.pluginLayers().size());
		assertEquals(Integer.valueOf(3), overlays.pluginLayers().get("Quest Helper"));
		assertEquals(Integer.valueOf(2), overlays.pluginLayers().get("Sailing"));
		assertEquals(ExternalOverlays.MAX_LAYERS, overlays.sentLayers().size());
		send(overlays, "layers", "plugins", "no map");
		assertTrue(overlays.pluginLayers().isEmpty());

		assertFalse(overlays.needsGameMap());
		send(overlays, "game_map", "needs", true, "tip", "Quest Helper draws its lines on the game map itself");
		assertTrue(overlays.needsGameMap());
		assertEquals("Quest Helper draws its lines on the game map itself", overlays.lineUpTip());
		overlays.onPluginMessage(message("partner_goodbye", "owner", "worldmapplus-helper"));
		assertFalse("the goodbye of the partner that pushed it clears it", overlays.needsGameMap());
		assertTrue(overlays.sentLayers().isEmpty());
		send(overlays, "game_map", "needs", true, "tip", "x");
		overlays.clear();
		assertFalse(overlays.needsGameMap());
		assertEquals("", overlays.lineUpTip());
	}

	@Test
	public void strangeMessagesAreHarmless()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(null);
		overlays.onPluginMessage(new PluginMessage("other", "view_focus", new HashMap<>()));
		overlays.onPluginMessage(message("unknown_thing"));
		assertNull(overlays.takeFocus());
		assertTrue(overlays.idle());
	}
}
