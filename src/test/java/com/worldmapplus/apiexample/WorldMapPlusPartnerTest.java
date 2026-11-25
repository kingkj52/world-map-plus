package com.worldmapplus.apiexample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.Partners;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

/** The partner helper in docs/api compiles (this package holds the same file) and works against the real registry through a real bus. */
public class WorldMapPlusPartnerTest
{
	/** Stands in for World Map Plus: hands messages to the registry and posts its answers, as the plugin's client tick does. */
	public static final class Main
	{
		final ExternalOverlays overlays = new ExternalOverlays();
		final EventBus bus;

		Main(EventBus bus)
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
			overlays.partners().tick(System.currentTimeMillis());
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
			if (ExternalOverlays.NAMESPACE.equals(message.getNamespace()))
			{
				heard.add(message);
			}
		}

		PluginMessage answer(String id)
		{
			PluginMessage found = null;
			for (PluginMessage m : heard)
			{
				found = WorldMapPlusPartner.isAnswer(m, id) ? m : found;
			}
			return found;
		}

		PluginMessage state(String topic)
		{
			PluginMessage found = null;
			for (PluginMessage m : heard)
			{
				found = WorldMapPlusPartner.isState(m, topic) ? m : found;
			}
			return found;
		}

		PluginMessage last(String name)
		{
			PluginMessage found = null;
			for (PluginMessage m : heard)
			{
				found = WorldMapPlusPartner.isReply(m, name) ? m : found;
			}
			return found;
		}
	}

	@Test
	public void theCopyInTheDocsIsTheSameFile() throws Exception
	{
		File doc = new File("docs/api/WorldMapPlusPartner.java");
		assumeTrue("the docs are left out of the published copy", doc.exists());
		String docText = new String(Files.readAllBytes(doc.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String ours = new String(Files.readAllBytes(new File("src/test/java/com/worldmapplus/apiexample/WorldMapPlusPartner.java").toPath()),
			StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertEquals(ours.replaceFirst("package com\\.worldmapplus\\.apiexample;", "package com.example.yourplugin;"), docText);
	}

	@Test
	public void aPartnerWrittenWithTheHelperRegistersIsPingedAndGetsAnswers()
	{
		EventBus bus = new EventBus();
		Main main = new Main(bus);
		Listener partner = new Listener();
		bus.register(main);
		bus.register(partner);
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		main.overlays.partners().attach(null, null, null, pins, routes, (x, y) -> 0);

		WorldMapPlusPartner.hello(bus, "demo", "part two");
		boolean[] painted = {false};
		WorldMapPlusPartner.painter(bus, "demo", "dot", 1, "over", (Graphics2D g, Map<String, Object> frame) -> painted[0] = true);
		WorldMapPlusPartner.action(bus, "demo", "share_ticked", payload -> { });
		WorldMapPlusPartner.request(bus, "demo", "save_pins", "make:1", "points", Arrays.asList(new WorldPoint(3200, 3200, 0)),
			"names", Arrays.asList("Made"));
		main.tick();

		assertTrue(WorldMapPlusPartner.mustRegister(partner.last("hello_reply"), "demo"));
		assertEquals(3, partner.last("hello_reply").getData().get("apiVersion"));
		assertFalse("the palette comes with the handshake", WorldMapPlusPartner.theme(partner.state("theme")).isEmpty());
		assertTrue(WorldMapPlusPartner.theme(partner.state("theme")).get("ACCENT") instanceof Color);
		assertTrue(WorldMapPlusPartner.ok(partner.answer("make:1")));
		assertEquals(1, pins.all().size());
		assertEquals("Made", pins.all().get(0).getName());
		assertTrue(main.overlays.partners().partTwoPresent());
		assertTrue(main.overlays.partners().handles("share_ticked"));

		main.overlays.partners().paint(new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics(),
			new ViewTransform(3200, 3200, 4, 0, 0, 10, 10), 0, 0, (x, y) -> true);
		assertTrue(painted[0]);

		WorldMapPlusPartner.request(bus, "demo", "saved", "snap:1", "what", "pins");
		main.tick();
		assertTrue(WorldMapPlusPartner.ok(partner.answer("snap:1")));
		assertEquals(1, ((List<?>) partner.answer("snap:1").getData().get("pins")).size());

		main.overlays.partners().mapNames(id -> "Gielinor");
		WorldMapPlusPartner.panel(bus, "demo", "results", 5, (Graphics2D g, Map<String, Object> frame) -> { }, event -> true);
		WorldMapPlusPartner.request(bus, "demo", "show", null, "surface", 4, "mapId", 0, "x", 3222.0, "y", 3218.0, "name", "Hans",
			"marks", Arrays.asList(new double[] {3222, 3218}));
		main.tick();
		assertEquals(1, main.overlays.partners().slots(Partners.Kind.PANEL).length);
		assertEquals(1, main.overlays.partners().takeShow(4).marks.size());

		assertTrue(WorldMapPlusPartner.isPing(partner.last("ping")));
		WorldMapPlusPartner.goodbye(bus, "demo");
		assertEquals(0, main.overlays.partners().slots(Partners.Kind.PAINTER).length);
	}
}
