package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

public class ExternalOverlaysTest
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

	@Test
	public void aMarkerIsStoredWithItsDetails()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("marker", "id", "bob", "point", new WorldPoint(3222, 3218, 0),
			"name", "Bob", "tooltip", "Online", "color", Color.RED, "source", "gim"));
		List<ExternalMarker> markers = overlays.markers();
		assertEquals(1, markers.size());
		ExternalMarker marker = markers.get(0);
		assertEquals("Bob", marker.getName());
		assertEquals("Online", marker.getTooltip());
		assertEquals(Color.RED, marker.getColor());
		assertEquals(3222.5, marker.getX(), 1e-9);
		assertEquals("gim", marker.getSource());
	}

	@Test
	public void sendingTheSameMarkerAgainMovesIt()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("marker", "id", "bob", "point", new WorldPoint(1, 1, 0), "source", "gim"));
		overlays.onPluginMessage(message("marker", "id", "bob", "point", new WorldPoint(9, 9, 0), "source", "gim"));
		assertEquals(1, overlays.markers().size());
		assertEquals(9.5, overlays.markers().get(0).getX(), 1e-9);
	}

	@Test
	public void sourcesDoNotOverwriteEachOther()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("marker", "id", "a", "point", new WorldPoint(1, 1, 0), "source", "one"));
		overlays.onPluginMessage(message("marker", "id", "a", "point", new WorldPoint(2, 2, 0), "source", "two"));
		assertEquals(2, overlays.markers().size());
	}

	@Test
	public void clearingByIdBySourceAndEverything()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("marker", "id", "a", "point", new WorldPoint(1, 1, 0), "source", "one"));
		overlays.onPluginMessage(message("marker", "id", "b", "point", new WorldPoint(2, 2, 0), "source", "one"));
		overlays.onPluginMessage(message("marker", "id", "c", "point", new WorldPoint(3, 3, 0), "source", "two"));
		overlays.onPluginMessage(message("marker_clear", "id", "a", "source", "one"));
		assertEquals(2, overlays.markers().size());
		overlays.onPluginMessage(message("marker_clear", "source", "one"));
		assertEquals(1, overlays.markers().size());
		overlays.onPluginMessage(message("marker_clear"));
		assertTrue(overlays.markers().isEmpty());
	}

	@Test
	public void badMarkersAreIgnored()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("marker", "id", "a"));
		overlays.onPluginMessage(message("marker", "point", new WorldPoint(1, 1, 0)));
		overlays.onPluginMessage(message("marker", "id", 5, "point", new WorldPoint(1, 1, 0)));
		overlays.onPluginMessage(message("marker", "id", "a", "point", "not a point"));
		overlays.onPluginMessage(message("marker", "id", "a", "point", new WorldPoint(1, 1, 0), "color", "red"));
		assertEquals(1, overlays.markers().size());
		assertNotNull(overlays.markers().get(0).getColor());
	}

	@Test
	public void markerCountIsCapped()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		for (int i = 0; i < ExternalOverlays.MAX_MARKERS + 50; i++)
		{
			overlays.onPluginMessage(message("marker", "id", "m" + i, "point", new WorldPoint(i, i, 0)));
		}
		assertEquals(ExternalOverlays.MAX_MARKERS, overlays.markers().size());
	}

	@Test
	public void longTextIsTrimmed()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 1000; i++)
		{
			big.append('x');
		}
		overlays.onPluginMessage(message("marker", "id", "a", "point", new WorldPoint(1, 1, 0), "name", big.toString()));
		assertEquals(ExternalOverlays.MAX_TEXT, overlays.markers().get(0).getName().length());
	}

	@Test
	public void onlySmallImagesAreAccepted()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		BufferedImage small = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		BufferedImage huge = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
		overlays.onPluginMessage(message("marker", "id", "a", "point", new WorldPoint(1, 1, 0), "image", small));
		overlays.onPluginMessage(message("marker", "id", "b", "point", new WorldPoint(2, 2, 0), "image", huge));
		assertNotNull(overlays.markers().get(0).getIcon());
		assertNull(overlays.markers().get(1).getIcon());
	}

	@Test
	public void aLineNeedsTwoPoints()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		List<WorldPoint> one = new ArrayList<>();
		one.add(new WorldPoint(1, 1, 0));
		overlays.onPluginMessage(message("line", "id", "r", "points", one));
		assertTrue(overlays.lines().isEmpty());

		List<WorldPoint> two = new ArrayList<>(one);
		two.add(new WorldPoint(5, 6, 0));
		overlays.onPluginMessage(message("line", "id", "r", "points", two, "label", "Route", "color", Color.GREEN));
		assertEquals(1, overlays.lines().size());
		ExternalLine line = overlays.lines().get(0);
		assertEquals(2, line.getPoints().size());
		assertEquals("Route", line.getLabel());
		assertFalse(line.isHop());
		overlays.onPluginMessage(message("line_clear"));
		assertTrue(overlays.lines().isEmpty());
	}

	@Test
	public void pointListsIgnoreNonPointsAndAreCapped()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		List<Object> mixed = new ArrayList<>();
		mixed.add(new WorldPoint(1, 1, 0));
		mixed.add("junk");
		mixed.add(null);
		for (int i = 0; i < ExternalOverlays.MAX_POINTS + 100; i++)
		{
			mixed.add(new WorldPoint(i, 5, 0));
		}
		overlays.onPluginMessage(message("line", "id", "r", "points", mixed));
		assertEquals(ExternalOverlays.MAX_POINTS, overlays.lines().get(0).getPoints().size());
	}

	@Test
	public void shortestPathTransportsBecomeHops()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		List<WorldPoint> from = new ArrayList<>();
		from.add(new WorldPoint(2760, 3238, 0));
		from.add(new WorldPoint(3000, 3000, 0));
		List<WorldPoint> to = new ArrayList<>();
		to.add(new WorldPoint(2792, 3414, 0));
		to.add(new WorldPoint(3100, 3100, 0));
		List<String> info = new ArrayList<>();
		info.add("Charter ship");
		info.add("Teleport");
		Map<String, Object> data = new HashMap<>();
		data.put("origin", from);
		data.put("destination", to);
		data.put("displayInfo", info);
		overlays.onPluginMessage(new PluginMessage("shortestpath", "transports", data));

		assertEquals(2, overlays.lines().size());
		assertTrue(overlays.lines().get(0).isHop());
		assertEquals("Charter ship", overlays.lines().get(0).getLabel());

		// A newer message replaces the old hops.
		overlays.onPluginMessage(new PluginMessage("shortestpath", "transports", new HashMap<>()));
		assertTrue(overlays.lines().isEmpty());
	}

	@Test
	public void clearingHopsLeavesOtherLines()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		List<WorldPoint> pts = new ArrayList<>();
		pts.add(new WorldPoint(1, 1, 0));
		pts.add(new WorldPoint(2, 2, 0));
		overlays.onPluginMessage(message("line", "id", "keep", "points", pts));
		Map<String, Object> data = new HashMap<>();
		data.put("origin", pts);
		data.put("destination", pts);
		overlays.onPluginMessage(new PluginMessage("shortestpath", "transports", data));
		assertEquals(3, overlays.lines().size());
		overlays.clearHops();
		assertEquals(1, overlays.lines().size());
		assertEquals("keep", overlays.lines().get(0).getId());
	}

	@Test
	public void aFocusRequestIsDeliveredOnce()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("focus", "point", new WorldPoint(3222, 3218, 0), "zoom", 12.0, "name", "Spot"));
		ExternalOverlays.FocusRequest request = overlays.takeFocus();
		assertNotNull(request);
		assertEquals(3222, request.getPoint().getX());
		assertEquals(12.0, request.getZoom(), 1e-9);
		assertEquals("Spot", request.getName());
		assertNull(overlays.takeFocus());
	}

	@Test
	public void aBadFocusIsIgnored()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(message("focus", "point", "somewhere"));
		overlays.onPluginMessage(message("focus", "point", new WorldPoint(1, 1, 0), "zoom", Double.NaN));
		ExternalOverlays.FocusRequest request = overlays.takeFocus();
		assertNotNull(request);
		assertEquals(0.0, request.getZoom(), 1e-9);
	}

	@Test
	public void strangeMessagesAreHarmless()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		overlays.onPluginMessage(null);
		overlays.onPluginMessage(new PluginMessage("other", "marker"));
		overlays.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "unknown"));
		overlays.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "marker"));
		assertTrue(overlays.markers().isEmpty());
		assertTrue(overlays.lines().isEmpty());
	}

	@Test
	public void theVersionChangesWhenContentDoes()
	{
		ExternalOverlays overlays = new ExternalOverlays();
		int before = overlays.getVersion();
		overlays.onPluginMessage(message("marker", "id", "a", "point", new WorldPoint(1, 1, 0)));
		assertTrue(overlays.getVersion() > before);
		int mid = overlays.getVersion();
		overlays.onPluginMessage(message("marker_clear", "id", "does not exist"));
		assertEquals(mid, overlays.getVersion());
		overlays.clear();
		assertTrue(overlays.markers().isEmpty());
	}

	@Test
	public void anIconIsCopiedAndAPointListIsReadFromASnapshot()
	{
		java.awt.image.BufferedImage original = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		original.setRGB(1, 1, 0xFFFF0000);
		java.awt.image.BufferedImage copy = ExternalOverlays.icon(original);
		assertNotNull(copy);
		assertTrue("not the sender's own image", copy != original);
		original.setRGB(1, 1, 0xFF00FF00);
		assertEquals(0xFFFF0000, copy.getRGB(1, 1));
		assertNull(ExternalOverlays.icon(new java.awt.image.BufferedImage(200, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB)));

		java.util.List<Object> list = new java.util.ArrayList<>();
		list.add(new net.runelite.api.coords.WorldPoint(10, 10, 0));
		assertEquals(1, ExternalOverlays.points(list).size());
	}
}
