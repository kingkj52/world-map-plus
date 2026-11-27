package com.worldmapplus.apiexample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.worldmapplus.bridge.ExternalMarker;
import com.worldmapplus.bridge.ExternalOverlays;
import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

/**
 * The copy-paste helper in docs/api compiles (this package holds the same file) and talks to the real bridge through a real event bus,
 * the way another plugin would.
 */
public class WorldMapPlusApiTest
{
	/** Stands in for World Map Plus: hands every message to the bridge and posts its answers, as the plugin's client tick does. */
	public static final class Map_
	{
		final ExternalOverlays overlays = new ExternalOverlays();
		final EventBus bus;

		Map_(EventBus bus)
		{
			this.bus = bus;
		}

		@Subscribe
		public void onPluginMessage(PluginMessage message)
		{
			overlays.onPluginMessage(message);
		}

		void tick()
		{
			for (ExternalOverlays.Reply reply : overlays.drainReplies(System.currentTimeMillis()))
			{
				bus.post(new PluginMessage(ExternalOverlays.NAMESPACE, reply.getName(), reply.getData()));
			}
		}
	}

	/** A plugin written with the helper. */
	public static final class Listener
	{
		final List<PluginMessage> heard = new ArrayList<>();

		@Subscribe
		public void onPluginMessage(PluginMessage message)
		{
			if (WorldMapPlusApi.NAMESPACE.equals(message.getNamespace()))
			{
				heard.add(message);
			}
		}
	}

	@Test
	public void theDocumentCopyIsTheSameFileWithItsOwnPackageLine() throws Exception
	{
		File doc = new File("docs/api/WorldMapPlusApi.java");
		assumeTrue("the docs are left out of the published copy", doc.exists());
		String docText = new String(Files.readAllBytes(doc.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String ours = new String(Files.readAllBytes(new File("src/test/java/com/worldmapplus/apiexample/WorldMapPlusApi.java").toPath()),
			StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertTrue(docText.startsWith("package com.example.yourplugin;\n"));
		assertEquals(ours.replaceFirst("package com\\.worldmapplus\\.apiexample;", "package com.example.yourplugin;"), docText);
	}

	@Test
	public void aPluginWrittenWithTheHelperGetsTheHandshakeAndItsClicks()
	{
		EventBus bus = new EventBus();
		Map_ map = new Map_(bus);
		Listener plugin = new Listener();
		bus.register(map);
		bus.register(plugin);

		WorldMapPlusApi.hello(bus, "demo-1");
		Map<String, Object> marker = WorldMapPlusApi.marker("demo", "spot", new WorldPoint(3200, 3200, 0));
		marker.put("name", "Spot");
		marker.put("clickable", true);
		marker.put("menu", WorldMapPlusApi.menu("Route here", "Forget"));
		marker.put("layer", "Demo spots");
		WorldMapPlusApi.send(bus, "marker", marker);
		WorldMapPlusApi.showMarker(bus, "demo", "other", new WorldPoint(3201, 3200, 0), "Other", "tip", Color.RED);
		WorldMapPlusApi.showLine(bus, "demo", "l", java.util.Arrays.asList(new WorldPoint(1, 1, 0), new WorldPoint(2, 2, 0)),
			Color.BLUE, "line");
		WorldMapPlusApi.focus(bus, new WorldPoint(3200, 3200, 0), 8, "Spot");
		assertEquals(2, map.overlays.markers().size());
		assertEquals(1, map.overlays.lines().size());
		assertEquals(new WorldPoint(3200, 3200, 0), map.overlays.takeFocus().getPoint());

		int before = plugin.heard.size();
		map.tick();
		PluginMessage reply = null;
		for (PluginMessage heard : plugin.heard.subList(before, plugin.heard.size()))
		{
			if (WorldMapPlusApi.isReply(heard, "hello_reply"))
			{
				reply = heard;
			}
		}
		assertEquals("demo-1", WorldMapPlusApi.text(reply, "id"));
		assertEquals(WorldMapPlusApi.KNOWN_VERSION, WorldMapPlusApi.apiVersion(reply));
		assertTrue(WorldMapPlusApi.supports(reply, "marker"));
		assertFalse(WorldMapPlusApi.supports(reply, "teleport"));

		ExternalMarker spot = null;
		for (ExternalMarker m : map.overlays.markers())
		{
			spot = "spot".equals(m.getId()) ? m : spot;
		}
		map.overlays.markerClicked(spot);
		map.overlays.markerMenu(spot, "Forget");
		before = plugin.heard.size();
		map.tick();
		List<PluginMessage> answers = plugin.heard.subList(before, plugin.heard.size());
		assertEquals(2, answers.size());
		assertTrue(WorldMapPlusApi.isAboutMine(answers.get(0), "demo"));
		assertFalse(WorldMapPlusApi.isAboutMine(answers.get(0), "someone-else"));
		assertEquals("spot", WorldMapPlusApi.text(answers.get(0), "id"));
		assertEquals("Forget", WorldMapPlusApi.menuOption(answers.get(1)));

		WorldMapPlusApi.clearMarkers(bus, "demo", null);
		WorldMapPlusApi.clearLines(bus, "demo", "l");
		assertTrue(map.overlays.markers().isEmpty());
		assertTrue(map.overlays.lines().isEmpty());
	}
}
