package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 73: what Plugin API v1 does with a fixed set of messages (good, odd and hostile ones), what it answers, and the markers it makes
 * of the points other plugins put on the game's own map, written before the v1 code and the census were slimmed
 * ({@code golden/plugin-api.txt}). What is made now is written to {@code build/plugin-api-actual.txt}.
 */
public class PluginApiGoldenTest
{

	private final StringBuilder out = new StringBuilder();
	private final ExternalOverlays api = new ExternalOverlays();

	private static PluginMessage message(String namespace, String name, Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2)
		{
			data.put((String) keyValues[i], keyValues[i + 1]);
		}
		return new PluginMessage(namespace, name, data);
	}

	private void send(String name, Object... keyValues)
	{
		out.append("> ").append(name).append(' ').append(Arrays.deepToString(describeAll(keyValues))).append('\n');
		api.onPluginMessage(message(ExternalOverlays.NAMESPACE, name, keyValues));
		state();
	}

	private static Object[] describeAll(Object[] values)
	{
		Object[] shown = new Object[values.length];
		for (int i = 0; i < values.length; i++)
		{
			shown[i] = describe(values[i]);
		}
		return shown;
	}

	private static BufferedImage image(int width, int height, int seed)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				image.setRGB(x, y, 0xFF000000 | (x * 31 + y * 17 + seed * 101) & 0xFFFFFF);
			}
		}
		return image;
	}

	static String describe(Object value)
	{
		if (value instanceof BufferedImage)
		{
			BufferedImage image = (BufferedImage) value;
			long hash = 7;
			for (int y = 0; y < image.getHeight(); y++)
			{
				for (int x = 0; x < image.getWidth(); x++)
				{
					hash = hash * 31 + image.getRGB(x, y);
				}
			}
			return "image " + image.getWidth() + "x" + image.getHeight() + " #" + Long.toHexString(hash);
		}
		if (value instanceof Color)
		{
			return "color " + Integer.toHexString(((Color) value).getRGB());
		}
		if (value instanceof WorldPoint)
		{
			WorldPoint p = (WorldPoint) value;
			return "(" + p.getX() + "," + p.getY() + "," + p.getPlane() + ")";
		}
		if (value instanceof double[])
		{
			return Arrays.toString((double[]) value);
		}
		if (value instanceof int[])
		{
			return Arrays.toString((int[]) value);
		}
		if (value instanceof Map)
		{
			TreeMap<String, String> sorted = new TreeMap<>();
			for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet())
			{
				sorted.put(String.valueOf(e.getKey()), describe(e.getValue()));
			}
			return sorted.toString();
		}
		if (value instanceof List)
		{
			List<String> items = new ArrayList<>();
			for (Object item : (List<?>) value)
			{
				items.add(describe(item));
			}
			return items.toString();
		}
		if (value instanceof String)
		{
			return "'" + ((String) value).replace("\n", "\\n").replace("\t", "\\t") + "'";
		}
		return String.valueOf(value);
	}

	static String marker(ExternalMarker m)
	{
		return "marker " + m.getSource() + "/" + m.getId() + " at " + m.getX() + "," + m.getY() + " name " + describe(m.getName())
			+ " tooltip " + describe(m.getTooltip()) + " " + describe(m.getColor()) + " icon " + describe(m.getIcon()) + " anchor "
			+ describe(m.getAnchor()) + " snap " + m.isSnapToEdge() + " jump " + describe(m.getJumpTarget()) + " party "
			+ m.isParty() + " clickable " + m.isClickable() + " hint " + describe(m.getHint()) + " menu "
			+ describe(m.getMenu());
	}

	private void state()
	{
		for (ExternalMarker m : api.markers())
		{
			out.append("  ").append(marker(m)).append('\n');
		}
		for (ExternalLine l : api.lines())
		{
			out.append("  line ").append(l.getSource()).append('/').append(l.getId()).append(' ').append(describe(l.getPoints()))
				.append(' ').append(describe(l.getColor())).append(" label ").append(describe(l.getLabel())).append(" hop ")
				.append(l.isHop()).append('\n');
		}
		for (RouteHop h : api.hops())
		{
			out.append("  hop ").append(h.getFromX()).append(',').append(h.getFromY()).append(',').append(h.getFromPlane())
				.append(" > ").append(h.getToX()).append(',').append(h.getToY()).append(',').append(h.getToPlane()).append(' ')
				.append(describe(h.getLabel())).append('\n');
		}
		ExternalOverlays.FocusRequest focus = api.takeFocus();
		if (focus != null)
		{
			out.append("  focus ").append(describe(focus.getPoint())).append(" zoom ").append(focus.getZoom()).append(" name ")
				.append(describe(focus.getName())).append(" id ").append(describe(focus.getId())).append(" exact ")
				.append(focus.isExact()).append('\n');
			api.answerFocus(focus, true, "Gielinor Surface", "surface", "ok");
		}
		for (ExternalOverlays.Reply reply : api.drainReplies(System.currentTimeMillis()))
		{
			out.append("  reply ").append(reply.getName()).append(' ').append(describe(reply.getData())).append('\n');
		}
		out.append("  version ").append(api.getVersion()).append(" idle ").append(api.idle()).append('\n');
	}

	private static ExternalOverlays.Cover cover(int x, int y)
	{
		if (x == 3615)
		{
			return new ExternalOverlays.Cover(true, true, "Guardians of the Rift", "area");
		}
		if (x >= 3000 && x < 3400)
		{
			return new ExternalOverlays.Cover(true, x % 2 == 0, "Gielinor Surface", "surface");
		}
		return new ExternalOverlays.Cover(false, false, "", "");
	}

	private void messages()
	{
		out.append("# answers before any question\n");
		api.announceReady();
		state();
		send("hello", "id", "golden-1");
		send("hello", "id", "bad id!");
		send("hello");
		send("hello", "id", 42);

		out.append("# markers\n");
		send("marker", "id", "a", "point", new WorldPoint(3200, 3200, 0));
		send("marker", "id", "b", "source", "gim", "point", new WorldPoint(3210, 3201, 1), "name", "  Bob  ", "tooltip",
			"Bob\nHitpoints 50\n\tPrayer 30", "color", new Color(10, 20, 30, 40), "image", image(16, 12, 1), "layer", "Group");
		send("marker", "id", "c", "source", "gim", "point", new WorldPoint(3220, 3202, 0), "clickable", true, "hint",
			"  Open\tthe\n|bank|  ", "menu", Arrays.asList("Route here", "Route here", "", "  Hide  ", "x|y", 7,
				"A very long menu entry that goes past forty letters for sure", "Fifth", "Sixth"));
		send("marker", "id", "bad id", "source", "gim", "point", new WorldPoint(3230, 3203, 0), "clickable", true, "menu",
			Collections.singletonList("Nope"));
		send("marker", "id", "d", "source", "bad source!", "point", new WorldPoint(3231, 3203, 0), "clickable", true);
		send("marker", "id", "e", "point", new WorldPoint(3232, 3204, 0), "image", image(65, 10, 2), "color", "red", "name", 5);
		send("marker", "id", "f", "point", "3200,3200");
		send("marker", "point", new WorldPoint(3233, 3205, 0));
		StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 30; i++)
		{
			longName.append("Name").append(i);
		}
		send("marker", "id", "a", "point", new WorldPoint(3201, 3201, 0), "name", longName.toString(), "clickable", "yes");
		send("marker", "id", "g", "source", "gim", "point", new WorldPoint(3240, 3206, 0), "image", image(64, 64, 3),
			"clickable", true);
		send("marker_clear", "id", "g", "source", "other");
		send("marker_clear", "id", "g");
		send("marker_clear", "source", "gim");
		send("marker_clear", "id", "nothing");
		send("marker_clear");
		send("marker_clear");

		out.append("# lines\n");
		send("line", "id", "l1", "source", "gim", "points", Arrays.asList(new WorldPoint(3200, 3200, 0), new WorldPoint(3210, 3220, 0),
			"skip", new WorldPoint(3230, 3240, 1)), "color", Color.GREEN, "label", " Path ", "layer", "Routes");
		send("line", "id", "l2", "points", Collections.singletonList(new WorldPoint(3200, 3200, 0)));
		send("line", "id", "l3", "points", "none");
		send("line", "points", Arrays.asList(new WorldPoint(1, 1, 0), new WorldPoint(2, 2, 0)));
		send("line", "id", "l4", "points", Arrays.asList(new WorldPoint(1, 1, 0), new WorldPoint(2, 2, 0)), "color", 5);
		send("line_clear", "source", "gim");
		send("line_clear");

		out.append("# Shortest Path's jumps\n");
		out.append("> shortestpath transports\n");
		api.onPluginMessage(message(ExternalOverlays.SHORTEST_PATH, "transports", "origin",
			Arrays.asList(new WorldPoint(3200, 3200, 0), new WorldPoint(3100, 3100, 0), new WorldPoint(2900, 2900, 0)), "destination",
			Arrays.asList(new WorldPoint(3300, 3300, 0), new WorldPoint(3000, 3000, 1), new WorldPoint(2800, 2800, 0)), "displayInfo",
			Arrays.asList("Fairy ring BIP", "", null), "objectInfo", Arrays.asList(null, "Boat 12345", 7)));
		state();
		api.holdFirstHops();
		out.append("> hold first hops, then two answers\n");
		api.onPluginMessage(message(ExternalOverlays.SHORTEST_PATH, "transports", "origin",
			Collections.singletonList(new WorldPoint(1, 2, 0)), "destination", Collections.singletonList(new WorldPoint(3, 4, 0))));
		api.onPluginMessage(message(ExternalOverlays.SHORTEST_PATH, "transports", "origin",
			Collections.singletonList(new WorldPoint(5, 6, 0)), "destination", Collections.singletonList(new WorldPoint(7, 8, 0))));
		state();
		api.releaseHops();
		out.append("> released, then an answer\n");
		api.onPluginMessage(message(ExternalOverlays.SHORTEST_PATH, "transports", "origin",
			Collections.singletonList(new WorldPoint(9, 10, 0)), "destination", Collections.singletonList(new WorldPoint(11, 12, 0))));
		state();
		out.append("> cleared\n");
		api.clearHops();
		state();
		out.append("> another namespace and another name\n");
		api.onPluginMessage(message("other", "marker", "id", "x", "point", new WorldPoint(1, 1, 0)));
		api.onPluginMessage(message(ExternalOverlays.SHORTEST_PATH, "path", "origin", Collections.emptyList()));
		state();

		out.append("# focus\n");
		send("focus", "point", new WorldPoint(3222, 3218, 0));
		send("focus", "point", new WorldPoint(3222, 3218, 0), "zoom", 6, "name", "Lumbridge", "id", "f-1", "exact", true);
		send("focus", "point", new WorldPoint(1, 1, 0), "zoom", Double.NaN, "id", "bad id");
		send("focus", "point", new WorldPoint(1, 1, 0), "zoom", -3.5, "exact", "yes");
		send("focus", "zoom", 4);
		out.append("> the side panel's Show\n");
		api.focusOn(new WorldPoint(3000, 3000, 0), "Saved place");
		state();
		api.focusOn(null, "Nothing");
		state();

		out.append("# covers, before the maps are loaded and after\n");
		send("covers", "id", "c-early", "point", new WorldPoint(3615, 9469, 0));
		api.setCoverage(PluginApiGoldenTest::cover);
		state();
		List<WorldPoint> many = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			many.add(new WorldPoint(3000 + i, 3000, 0));
		}
		send("covers", "id", "c-many", "points", many);
		send("covers", "id", "c-bad", "points", Arrays.asList(new WorldPoint(3000, 3000, 0), "x"));
		send("covers", "id", "c-one", "point", new WorldPoint(1, 1, 0), "points", "not a list");
		send("covers", "point", new WorldPoint(1, 1, 0));
		send("covers", "id", "c-none");

		out.append("# clicks and menu entries said back\n");
		send("marker", "id", "k", "source", "gim", "point", new WorldPoint(3200, 3200, 0), "clickable", true, "menu",
			Arrays.asList("Route here", "Hide"));
		ExternalMarker k = api.markers().get(0);
		out.append("> click current: ").append(api.markerClicked(k)).append('\n');
		out.append("> menu Hide: ").append(api.markerMenu(k, "Hide")).append('\n');
		out.append("> menu Other: ").append(api.markerMenu(k, "Other")).append('\n');
		out.append("> click null: ").append(api.markerClicked(null)).append('\n');
		state();
		send("marker", "id", "k", "source", "gim", "point", new WorldPoint(3200, 3200, 0), "clickable", false);
		out.append("> click replaced: ").append(api.markerClicked(k)).append(" menu ").append(api.markerMenu(k, "Hide")).append('\n');
		out.append("> click not clickable: ").append(api.markerClicked(api.markers().get(0))).append('\n');
		state();

		out.append("# a share shown and cleared\n");
		api.showShare(Arrays.asList(new WorldPoint(3200, 3200, 0), new WorldPoint(3210, 3210, 1), new WorldPoint(3220, 3220, 0)),
			true, "Bob's route");
		state();
		api.showShare(Collections.singletonList(new WorldPoint(3000, 3000, 0)), false, "A pin");
		state();
		api.showShare(Arrays.asList(new WorldPoint(3000, 3000, 0), new WorldPoint(3001, 3001, 0)), false, "Places");
		state();
		api.clearShare();
		state();
		api.clearShare();
		state();
		out.append("# map kinds\n");
		for (int id : new int[] {0, 1, 9999, 10000, 10999, 11000, 40000})
		{
			out.append("  kind ").append(id).append(' ').append(ExternalOverlays.kindOf(id)).append('\n');
		}
		out.append("# everything forgotten\n");
		api.clear();
		state();
	}

	private void gameMapPoints()
	{
		out.append("# the game map's points\n");
		List<WorldMapPoint> points = new ArrayList<>();
		WorldMapPoint plain = new WorldMapPoint(new WorldPoint(3222, 3218, 0), image(8, 8, 4));
		plain.setName("Alice");
		plain.setTooltip("Alice | 99/99 hp");
		points.add(plain);
		WorldMapPoint anchored = new WorldMapPoint(new WorldPoint(2000, 3000, 1), image(20, 30, 5));
		anchored.setImagePoint(new Point(4, 8));
		anchored.setSnapToEdge(true);
		anchored.setJumpOnClick(true);
		anchored.setTarget(new WorldPoint(2100, 3100, 0));
		points.add(anchored);
		WorldMapPoint jumpHere = new WorldMapPoint(new WorldPoint(2500, 3500, 0), null);
		jumpHere.setJumpOnClick(true);
		points.add(jumpHere);
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 100; i++)
		{
			longText.append("Tooltip").append(i).append(' ');
		}
		WorldMapPoint wordy = new WorldMapPoint(new WorldPoint(1, 2, 3), image(70, 10, 6));
		wordy.setName(longText.toString());
		wordy.setTooltip(longText.toString());
		points.add(wordy);
		points.add(new WorldMapPoint(null, image(8, 8, 7)));
		points.add(new NativePointsTest.TestCorePoint(new WorldPoint(5, 5, 0), image(8, 8, 8)));
		points.add(null);
		NativePoints source = new NativePoints(sink -> points.forEach(sink), "com.worldmapplus.bridge.NativePointsTest$TestCore");
		for (ExternalMarker m : source.markers(1000))
		{
			out.append("  ").append(marker(m)).append(" from game map\n");
		}
	}

	@Test
	public void theV1MessagesAndTheGameMapPointsAnswerAsBeforeTheSlimming() throws IOException
	{
		messages();
		gameMapPoints();
		String text = out.toString();
		Path actual = Paths.get("build", "plugin-api-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "plugin-api.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String[] a = expected.split("\n", -1);
		String[] b = text.split("\n", -1);
		for (int i = 0; i < Math.min(a.length, b.length); i++)
		{
			assertEquals("line " + (i + 1), a[i], b[i]);
		}
		assertEquals("lines", a.length, b.length);
	}
}
