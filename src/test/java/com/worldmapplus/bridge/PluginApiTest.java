package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

/** The Plugin API v1 additions: the handshake, clicks and menu entries said back, layers, and what is done with bad input. */
public class PluginApiTest
{
	private static final WorldPoint AT = new WorldPoint(3222, 3218, 0);

	private static PluginMessage message(String namespace, String name, Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2)
		{
			data.put((String) keyValues[i], keyValues[i + 1]);
		}
		return new PluginMessage(namespace, name, data);
	}

	private static PluginMessage ours(String name, Object... keyValues)
	{
		return message(ExternalOverlays.NAMESPACE, name, keyValues);
	}

	private static List<ExternalOverlays.Reply> drain(ExternalOverlays overlays)
	{
		return overlays.drainReplies(System.currentTimeMillis());
	}

	@Test
	public void helloIsAnsweredOnALaterDrainWithTheVersionMessagesAndLimits()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		assertTrue(overlays.idle());
		overlays.onPluginMessage(ours("hello", "id", "my-plugin:1"));
		assertFalse("waiting to be posted", overlays.idle());
		List<ExternalOverlays.Reply> replies = drain(overlays);
		assertEquals(1, replies.size());
		assertEquals("hello_reply", replies.get(0).getName());
		Map<String, Object> data = replies.get(0).getData();
		assertEquals("my-plugin:1", data.get("id"));
		assertEquals(1, data.get("apiVersion"));
		assertEquals(ExternalOverlays.API_VERSION, data.get("apiVersion"));
		assertTrue(((List<?>) data.get("messages")).containsAll(Arrays.asList("marker", "marker_clear", "line", "line_clear",
			"focus", "covers", "hello")));
		assertTrue(((List<?>) data.get("replies")).containsAll(Arrays.asList("hello_reply", "ready", "covers_reply", "focus_result",
			"marker_clicked", "marker_menu")));
		Map<?, ?> limits = (Map<?, ?>) data.get("limits");
		assertEquals(500, limits.get("markers"));
		assertEquals(4, limits.get("menuEntries"));
		assertEquals(64, limits.get("idLength"));
		assertTrue(overlays.idle());
	}

	@Test
	public void eachAnswerHasItsOwnMapSoOneReceiverCannotChangeWhatAnotherSees()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(ours("hello", "id", "a"));
		overlays.onPluginMessage(ours("hello", "id", "b"));
		List<ExternalOverlays.Reply> replies = drain(overlays);
		assertEquals(2, replies.size());
		replies.get(0).getData().put("apiVersion", 99);
		assertEquals(1, replies.get(1).getData().get("apiVersion"));
		try
		{
			((List<Object>) replies.get(1).getData().get("messages")).add("x");
		}
		catch (UnsupportedOperationException expected)
		{
			// The lists are read only.
		}
		assertEquals(7, ExternalOverlays.MESSAGES.size());
	}

	@Test
	public void helloWithoutAGoodIdOrInAnotherNamespaceIsIgnored()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(ours("hello"));
		overlays.onPluginMessage(ours("hello", "id", 42));
		overlays.onPluginMessage(ours("hello", "id", "has space"));
		overlays.onPluginMessage(ours("hello", "id", new String(new char[65]).replace('\0', 'a')));
		overlays.onPluginMessage(message("otherplugin", "hello", "id", "x"));
		overlays.onPluginMessage(ours("HELLO", "id", "x"));
		assertTrue(drain(overlays).isEmpty());
	}

	@Test
	public void helloIsRateLimited()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		for (int i = 0; i < 20; i++)
		{
			overlays.onPluginMessage(ours("hello", "id", "h" + i));
		}
		assertEquals(ExternalOverlays.MAX_HELLOS_PER_SECOND, drain(overlays).size());
	}

	@Test
	public void readyIsAnnouncedWithTheSameCapabilitiesAndNoId()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.announceReady();
		List<ExternalOverlays.Reply> replies = drain(overlays);
		assertEquals("ready", replies.get(0).getName());
		assertEquals(1, replies.get(0).getData().get("apiVersion"));
		assertFalse(replies.get(0).getData().containsKey("id"));
	}

	@Test
	public void aClickableMarkerIsReportedWithItsSourceAndIdOnlyWhileItIsCurrent()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(ours("marker", "id", "m1", "source", "myplugin", "point", AT, "clickable", true,
			"hint", "Click to route here"));
		ExternalMarker marker = overlays.markers().get(0);
		assertTrue(marker.isClickable());
		assertEquals("Click to route here", marker.getHint());
		assertTrue(overlays.markerClicked(marker));
		List<ExternalOverlays.Reply> replies = drain(overlays);
		assertEquals("marker_clicked", replies.get(0).getName());
		assertEquals("myplugin", replies.get(0).getData().get("source"));
		assertEquals("m1", replies.get(0).getData().get("id"));
		assertEquals(2, replies.get(0).getData().size());

		// Sent again: the old object is no longer the marker, so a late click on it says nothing.
		overlays.onPluginMessage(ours("marker", "id", "m1", "source", "myplugin", "point", AT, "clickable", true));
		assertFalse(overlays.markerClicked(marker));
		overlays.onPluginMessage(ours("marker_clear", "source", "myplugin"));
		assertFalse(overlays.markerClicked(overlays.markers().isEmpty() ? marker : null));
		assertTrue(drain(overlays).isEmpty());
	}

	@Test
	public void aMarkerThatDidNotAskOrHasNoPlainIdsIsNeverReported()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(ours("marker", "id", "plain", "source", "p", "point", AT));
		overlays.onPluginMessage(ours("marker", "id", "Has Space", "source", "p", "point", AT, "clickable", true,
			"menu", Arrays.asList("Route")));
		overlays.onPluginMessage(ours("marker", "id", "x", "point", AT, "clickable", true));
		overlays.onPluginMessage(ours("marker", "id", "y", "source", "p", "point", AT, "clickable", "yes"));
		for (ExternalMarker marker : overlays.markers())
		{
			assertFalse(marker.getId(), marker.isClickable());
			assertTrue(marker.getId(), marker.getMenu().isEmpty());
			assertFalse(overlays.markerClicked(marker));
			assertFalse(overlays.markerMenu(marker, "Route"));
		}
		assertEquals("the markers themselves are still shown, as before", 4, overlays.markers().size());
		assertFalse(overlays.markerClicked(null));
		assertFalse(overlays.markerClicked(ExternalMarker.fromGameMap("some.Point", 1, 1, "x")));
		assertTrue(drain(overlays).isEmpty());
	}

	@Test
	public void menuEntriesAreCleanedCappedAndOnlyThoseRegisteredAreSaidBack()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		List<Object> menu = new ArrayList<>(Arrays.asList("Route\there", 7, "", "  ", "Route here", "Line\nbreak|bar",
			"Fourth", "Fifth", "Sixth", null));
		overlays.onPluginMessage(ours("marker", "id", "m", "source", "p", "point", AT, "menu", menu));
		ExternalMarker marker = overlays.markers().get(0);
		assertEquals(Arrays.asList("Route here", "Line break bar", "Fourth", "Fifth"), marker.getMenu());
		assertFalse("not clickable unless asked", marker.isClickable());
		assertFalse(overlays.markerMenu(marker, "Sixth"));
		assertTrue(overlays.markerMenu(marker, "Fourth"));
		Map<String, Object> data = drain(overlays).get(0).getData();
		assertEquals("Fourth", data.get("option"));
		assertEquals("p", data.get("source"));
		assertEquals("m", data.get("id"));
		String longEntry = new String(new char[100]).replace('\0', 'w');
		overlays.onPluginMessage(ours("marker", "id", "n", "source", "p", "point", AT, "menu", Collections.singletonList(longEntry)));
		assertEquals(ExternalOverlays.MAX_MENU_TEXT, overlays.markers().get(1).getMenu().get(0).length());
		overlays.onPluginMessage(ours("marker", "id", "o", "source", "p", "point", AT, "menu", "not a list"));
		assertTrue(overlays.markers().get(2).getMenu().isEmpty());
	}

	@Test
	public void layersAreCountedAcrossMarkersAndLinesAndCleaned()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(ours("marker", "id", "a", "source", "p", "point", AT, "layer", "Group"));
		overlays.onPluginMessage(ours("marker", "id", "b", "source", "p", "point", AT, "layer", "Group"));
		overlays.onPluginMessage(ours("marker", "id", "c", "source", "p", "point", AT, "layer", "Bad|name\u0000"));
		overlays.onPluginMessage(ours("marker", "id", "d", "source", "p", "point", AT, "layer", 5));
		overlays.onPluginMessage(ours("line", "id", "l", "source", "p", "points", Arrays.asList(AT, new WorldPoint(3230, 3218, 0)),
			"layer", "Group", "color", Color.RED));
		Map<String, Integer> layers = overlays.layers();
		assertEquals(Integer.valueOf(3), layers.get("Group"));
		assertEquals(Integer.valueOf(1), layers.get("Bad name"));
		assertEquals(2, layers.size());
		assertTrue("the same object while nothing changed", layers == overlays.layers());
		overlays.onPluginMessage(ours("marker_clear", "id", "a", "source", "p"));
		assertEquals(Integer.valueOf(2), overlays.layers().get("Group"));
		overlays.clear();
		assertTrue(overlays.layers().isEmpty());
	}

	@Test
	public void labelsDropControlAndFormatCharactersAndCutToLength()
	{
		assertEquals("a b", ExternalOverlays.label("  a ​\u0007 b  ", 40));
		assertEquals("", ExternalOverlays.label(null, 40));
		assertEquals("", ExternalOverlays.label(new StringBuilder("x"), 40));
		assertEquals("abc", ExternalOverlays.label("abc def", 4));
		assertEquals(40, ExternalOverlays.label(new String(new char[90]).replace('\0', 'z'), 40).length());
	}

	@Test
	public void theOldMessagesKeepTheirShape()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(ours("marker", "id", "Zezima", "source", "groupironmencompanion", "point", AT,
			"name", "Zezima", "tooltip", "World 302", "color", Color.GREEN));
		ExternalMarker marker = overlays.markers().get(0);
		assertEquals("Zezima", marker.getName());
		assertEquals("World 302", marker.getTooltip());
		assertFalse(marker.isClickable());
		assertTrue(marker.getLayer().isEmpty());
		overlays.onPluginMessage(ours("marker_clear", "source", "groupironmencompanion"));
		assertTrue(overlays.markers().isEmpty());
		overlays.onPluginMessage(ours("focus", "point", AT, "zoom", 8.0, "name", "Zezima"));
		assertEquals(AT, overlays.takeFocus().getPoint());
	}
}
