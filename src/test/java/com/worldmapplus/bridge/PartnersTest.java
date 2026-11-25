package com.worldmapplus.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.RegionLines;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.search.Gazetteer;
import com.worldmapplus.search.SearchContext;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;

/** Plugin API v3: the registry of what partner plugins add, driven through a test double that only sends messages. */
public class PartnersTest
{
	/** What a surface answers when asked whether a place is on the map being drawn: yes. */
	private static final java.util.function.BiPredicate<Double, Double> ANYWHERE = (x, y) -> true;

	private static final ViewTransform VIEW = new ViewTransform(3200.0, 3200.0, 4.0, 0, 0, 200, 100);

	private ExternalOverlays main;
	private Partners partners;
	private PartnerDouble partner;
	private BufferedImage canvas;
	private Graphics2D g;

	@Before
	public void setUp()
	{
		main = new ExternalOverlays();
		partners = main.partners();
		partner = new PartnerDouble(main, "helper");
		canvas = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
		g = canvas.createGraphics();
	}

	private static BiConsumer<Graphics2D, Map<String, Object>> painter(AtomicInteger count)
	{
		return (g, frame) -> count.incrementAndGet();
	}

	private List<String> ids(Partners.Kind kind)
	{
		List<String> ids = new ArrayList<>();
		for (Partners.Slot slot : partners.slots(kind))
		{
			ids.add(slot.owner + "/" + slot.id);
		}
		return ids;
	}

	private List<String> heardNames()
	{
		List<String> names = new ArrayList<>();
		for (ExternalOverlays.Reply reply : partner.heard())
		{
			// A pushed state is named by its topic.
			names.add("state".equals(reply.getName()) ? (String) reply.getData().get("topic") : reply.getName());
		}
		return names;
	}

	// ------------------------------------------------------------------

	@Test
	public void registeringTheSameIdReplacesAndNeverDuplicates()
	{
		AtomicInteger first = new AtomicInteger();
		AtomicInteger second = new AtomicInteger();
		partner.register("painter", "layer", 10, painter(first));
		partner.register("painter", "layer", 10, painter(second));
		assertEquals(Arrays.asList("helper/layer"), ids(Partners.Kind.PAINTER));
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertEquals(0, first.get());
		assertEquals(1, second.get());
	}

	@Test
	public void aPainterThatAsksToDrawUnderTheIconsIsRunBySeparateCallOnly()
	{
		AtomicInteger over = new AtomicInteger();
		AtomicInteger under = new AtomicInteger();
		partner.register("painter", "over", 10, painter(over));
		partner.register("painter", "under", 10, painter(under), "layer", "under");
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertEquals(1, over.get());
		assertEquals(0, under.get());
		partners.paintUnder(g, VIEW, 0, 0, ANYWHERE);
		assertEquals(1, over.get());
		assertEquals(1, under.get());
		partner.register("painter", "under", 10, painter(under), "layer", "below");
		partners.paintUnder(g, VIEW, 0, 0, ANYWHERE);
		assertEquals("only the layer under counts", 1, under.get());
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertEquals(2, under.get());
	}

	@Test
	public void aPainterOfTheLayerRouteRunsAfterTheIconsAndBeforeThePins()
	{
		AtomicInteger over = new AtomicInteger();
		AtomicInteger route = new AtomicInteger();
		partner.register("painter", "route", 10, painter(route), "layer", "route");
		partner.register("painter", "over", 10, painter(over));
		partners.paintUnder(g, VIEW, 0, 0, ANYWHERE);
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertEquals("neither the under nor the over call runs it", 0, route.get());
		partners.paintRoute(g, VIEW, 0, 0, ANYWHERE);
		assertEquals(1, route.get());
		assertEquals(1, over.get());
	}

	@Test
	public void slotsSortByOrderThenOwnerThenId()
	{
		PartnerDouble other = new PartnerDouble(main, "another");
		AtomicInteger n = new AtomicInteger();
		partner.register("painter", "b", 5, painter(n));
		other.register("painter", "b", 5, painter(n));
		partner.register("painter", "a", 5, painter(n));
		other.register("painter", "z", -3, painter(n));
		assertEquals(Arrays.asList("another/z", "another/b", "helper/a", "helper/b"), ids(Partners.Kind.PAINTER));
	}

	@Test
	public void aPaintFailingThreeTimesInARowIsSwitchedOffUntilItsOwnerRegistersAgain()
	{
		AtomicInteger calls = new AtomicInteger();
		partner.register("painter", "bad", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (gr, frame) ->
		{
			calls.incrementAndGet();
			throw new IllegalStateException("partner bug");
		});
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertFalse(partners.slots(Partners.Kind.PAINTER)[0].isOff());
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertTrue(partners.slots(Partners.Kind.PAINTER)[0].isOff());
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertEquals("not called once off", 3, calls.get());
		assertTrue(heardNames().contains("off"));
		AtomicInteger fixed = new AtomicInteger();
		partner.register("painter", "bad", 1, painter(fixed));
		partners.paint(g, VIEW, 0, 0, ANYWHERE);
		assertEquals(1, fixed.get());
	}

	@Test
	public void aSuccessBetweenFailuresStartsTheCountAgain()
	{
		AtomicInteger calls = new AtomicInteger();
		partner.register("painter", "flaky", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (gr, frame) ->
		{
			if (calls.incrementAndGet() % 3 != 0)
			{
				throw new IllegalStateException("sometimes");
			}
		});
		for (int i = 0; i < 12; i++)
		{
			partners.paint(g, VIEW, 0, 0, ANYWHERE);
		}
		assertFalse(partners.slots(Partners.Kind.PAINTER)[0].isOff());
	}

	private static void pause(int millis)
	{
		try
		{
			Thread.sleep(millis);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	@Test
	public void aPainterThatIsAlwaysSlowIsSkippedAfterARunOfSlowFrames()
	{
		partners.paintBudget = 1_000_000L;
		AtomicInteger calls = new AtomicInteger();
		partner.register("painter", "slow", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (gr, frame) ->
		{
			calls.incrementAndGet();
			pause(8);
		});
		for (int i = 0; i < 40; i++)
		{
			partners.paint(g, VIEW, 0, 0, ANYWHERE);
		}
		assertTrue("a run of slow frames then skips it", calls.get() > 1 && calls.get() < 25);
		assertFalse("slow is not broken", partners.slots(Partners.Kind.PAINTER)[0].isOff());
	}

	@Test
	public void aSingleSlowFrameNeverMakesAPainterBlink()
	{
		partners.paintBudget = 1_000_000L;
		AtomicInteger first = new AtomicInteger();
		AtomicInteger second = new AtomicInteger();
		partner.register("painter", "a", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (gr, frame) ->
		{
			if (first.incrementAndGet() == 1)
			{
				pause(12);
			}
		});
		partner.register("painter", "b", 2, painter(second));
		for (int i = 0; i < 100; i++)
		{
			partners.paint(g, VIEW, 0, 0, ANYWHERE);
		}
		assertEquals("drawn in every frame, the slow first one included", 100, first.get());
		assertEquals("a slow painter does not take the frame from the next one", 100, second.get());
	}

	@Test
	public void aPanelIsDrawnInEveryFrameEvenAfterASlowFirstFrame()
	{
		partner.hello("part two");
		AtomicInteger calls = new AtomicInteger();
		partner.register("painter", "results", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (g2, frame) ->
		{
			if (calls.incrementAndGet() <= 2)
			{
				pause(15);
			}
		}, "layer", "screen", "input", (Function<Map<String, Object>, Boolean>) event -> false);
		int[] box = new int[5];
		for (int i = 0; i < 100; i++)
		{
			partners.paintPanels(g, 1, box, new java.awt.Rectangle(0, 0, 200, 100), 0, 200, null, 0L, false, -1, 0, 0);
		}
		assertEquals(100, calls.get());
	}

	@Test
	public void aPainterIsGivenTheMapFloorZoomAndATransformFromTilesToPixels()
	{
		AtomicReference<Point2D> corner = new AtomicReference<>();
		AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
		partner.register("painter", "probe", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (gr, frame) ->
		{
			AffineTransform t = (AffineTransform) frame.get("transform");
			corner.set(t.transform(new Point2D.Double(3201, 3199), null));
			seen.set(frame);
			gr.setColor(Color.RED);
			gr.fillRect(10, 10, 5, 5);
			gr.setClip(0, 0, 1, 1);
			gr.translate(500, 500);
		});
		partners.paint(g, VIEW, 7, 2, ANYWHERE);
		assertEquals(VIEW.screenX(3201), corner.get().getX(), 1e-9);
		assertEquals(VIEW.screenY(3199), corner.get().getY(), 1e-9);
		assertEquals(7, seen.get().get("mapId"));
		assertEquals(2, seen.get().get("floor"));
		assertEquals(4.0, (Double) seen.get().get("zoom"), 0);
		assertEquals(Color.RED.getRGB(), canvas.getRGB(12, 12));
		assertEquals("the map's own graphics are untouched by the partner's clip and translate", 200, g.getClipBounds() == null ? 200 : g.getClipBounds().width);
		try
		{
			seen.get().put("x", 1);
			throw new AssertionError("the frame map is read only");
		}
		catch (UnsupportedOperationException expected)
		{
			// as intended
		}
	}

	/** What the Helper pushes: for each need number the state of the quest it starts and what is missing. The need is missing 20 Agility. */
	private void progress(com.worldmapplus.unlock.Need need)
	{
		String[] missing = new String[need.number() + 1];
		missing[need.number()] = "20 Agility";
		partner.state("judged", "quests", new byte[missing.length], "missing", missing);
	}

	@Test
	public void theProgressOfAKnownPartnerIsCachedAndGoesWithItsGoodbye()
	{
		com.worldmapplus.unlock.Need need = com.worldmapplus.unlock.Needs.ofWay(Collections.singletonList("20 Agility"), null);
		assertFalse(partners.unlocks().lacks(need));
		partner.hello("part two");
		progress(need);
		assertTrue(partners.unlocks().lacks(need));
		assertEquals("20 Agility", partners.unlocks().shortfall(need));
		partner.goodbye();
		assertFalse("the goodbye takes the no-progress look", partners.unlocks().lacks(need));
	}

	@Test
	public void theProgressIsClearedWhenWorldMapPlusStopsForgettingItsPartners()
	{
		partner.hello("part two");
		com.worldmapplus.unlock.Need need = com.worldmapplus.unlock.Needs.ofWay(Collections.singletonList("20 Agility"), null);
		progress(need);
		assertTrue(partners.unlocks().version() > 0);
		partners.clear();
		assertEquals(0, partners.unlocks().shortfall(need) == null ? 0 : 1);
	}

	@Test
	public void theTableOfNeedsIsPushedAtEveryHelloAndAgainWhenItGrows()
	{
		com.worldmapplus.unlock.Need first = com.worldmapplus.unlock.Needs.ofWay(Collections.singletonList("21 Agility"), null);
		partner.hello("part two");
		Map<String, Object> pushed = PartnerDouble.pushed(partner.heard(), "needs");
		List<?> table = (List<?>) pushed.get("needs");
		assertTrue(table.size() > first.number());
		List<?> row = (List<?>) table.get(first.number());
		assertEquals("the ways, and the quest it starts (none)", 2, row.size());
		assertEquals("", row.get(1));
		partners.tick(1000);
		assertEquals("nothing new, nothing pushed", null, PartnerDouble.pushed(partner.heard(), "needs"));
		com.worldmapplus.unlock.Need second = com.worldmapplus.unlock.Needs.ofWay(Collections.singletonList("22 Agility"), null);
		partners.tick(2000);
		List<?> grown = (List<?>) PartnerDouble.pushed(partner.heard(), "needs").get("needs");
		assertTrue(grown.size() > second.number());
	}

	@Test
	public void anAnswerOfTheWrongShapeIsIgnoredWhole()
	{
		com.worldmapplus.unlock.Need need = com.worldmapplus.unlock.Needs.ofWay(Collections.singletonList("20 Agility"), null);
		partner.hello("part two");
		progress(need);
		int version = partners.unlocks().version();
		partner.state("judged", "quests", new byte[2], "missing", new String[3]);
		partner.state("judged", "quests", "none", "missing", new String[1]);
		partner.state("judged", "quests", new byte[1]);
		assertEquals(version, partners.unlocks().version());
		assertTrue(partners.unlocks().lacks(need));
	}

	@Test
	public void aCardSlotThatOwnsAKindGivesTheWholeBodyAndOthersAreNotAsked()
	{
		partner.hello("part two");
		List<Object> asked = new ArrayList<>();
		java.util.List<Object> many = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			many.add(Arrays.asList("Row " + i, "value"));
		}
		many.add("not a row");
		partner.register("card", "bodies", 100, (Function<Map<String, Object>, Object>) thing ->
		{
			asked.add(thing.get("body"));
			Map<String, Object> body = new java.util.HashMap<>();
			body.put("title", "  A   title ");
			body.put("rows", many);
			body.put("hint", "Do it");
			body.put("wiki", "shop");
			return body;
		}, "owns", Arrays.asList("farming"));
		assertTrue(partners.owns("farming"));
		assertFalse(partners.owns("bank"));
		com.worldmapplus.layers.HoverCard card = partners.body(Partners.describe("farming", "x", new WorldPoint(1, 2, 0), ""));
		assertEquals("A title", card.getTitle());
		assertEquals("the first 14 rows at once, a row is a pair", 14, card.getRows().size());
		assertEquals("the rest when expanded", 40, card.visibleRows(true).size());
		assertEquals("shop", card.getWiki()[0]);
		assertEquals("Do it", card.getHint());
		assertEquals(Collections.singletonList(Boolean.TRUE), asked);
		assertEquals(null, partners.body(Partners.describe("bank", "x", new WorldPoint(1, 2, 0), "")));
		assertEquals("no other kind is asked", 1, asked.size());
		partner.goodbye();
		assertFalse(partners.owns("farming"));
	}

	@Test
	public void goodbyeDropsEverythingTheOwnerGave()
	{
		AtomicInteger n = new AtomicInteger();
		partner.register("painter", "p", 1, painter(n));
		partner.register("card", "c", 1, (Function<Map<String, Object>, Object>) m -> Collections.singletonList("x"));
		partner.register("action", "share", 1, (Consumer<Map<String, Object>>) m -> n.incrementAndGet());
		PartnerDouble other = new PartnerDouble(main, "other");
		other.register("painter", "p", 1, painter(n));
		int before = partners.version();
		partner.goodbye();
		assertEquals(Arrays.asList("other/p"), ids(Partners.Kind.PAINTER));
		assertTrue(ids(Partners.Kind.CARD).isEmpty());
		assertFalse(partners.handles("share"));
		assertNotEquals(before, partners.version());
	}

	@Test
	public void aPartnerThatStopsAnsweringThePingIsDropped()
	{
		long[] time = {1_000_000L};
		partners.clock = () -> time[0];
		partner.hello("part two");
		partner.register("painter", "p", 1, painter(new AtomicInteger()));
		assertTrue(partners.partTwoPresent());
		heardNames();
		partners.tick(time[0]);
		assertTrue(heardNames().contains("ping"));
		time[0] += Partners.PING_MILLIS;
		partner.hello("part two");
		partners.tick(time[0]);
		assertEquals("answered, so kept", 1, partners.slots(Partners.Kind.PAINTER).length);
		time[0] += Partners.SILENT_MILLIS + 1;
		partners.tick(time[0]);
		assertEquals(0, partners.slots(Partners.Kind.PAINTER).length);
		assertFalse(partners.partTwoPresent());
		assertTrue(heardNames().contains("off"));
	}

	@Test
	public void aHelloOverTheRateLimitIsAnsweredLaterAndNotLost()
	{
		long[] now = {5_000_000L};
		partners.clock = () -> now[0];
		for (int i = 0; i < 12; i++)
		{
			new PartnerDouble(main, "owner" + i % 8).hello("other");
		}
		int answered = 0;
		for (ExternalOverlays.Reply reply : main.drainReplies(System.currentTimeMillis()))
		{
			answered += "hello_reply".equals(reply.getName()) ? 1 : 0;
		}
		assertEquals("eight in a second", 8, answered);
		now[0] += 1_200;
		partners.tick(now[0]);
		for (ExternalOverlays.Reply reply : main.drainReplies(System.currentTimeMillis()))
		{
			answered += "hello_reply".equals(reply.getName()) ? 1 : 0;
		}
		assertEquals("the other four, when the limit allows", 12, answered);
	}

	@Test
	public void helloGetsTheCapabilitiesAndThePalette()
	{
		partner.hello("part two");
		List<ExternalOverlays.Reply> replies = partner.heard();
		assertEquals(Arrays.asList("hello_reply", "state", "state"), Arrays.asList(replies.get(0).getName(), replies.get(1).getName(), replies.get(2).getName()));
		Map<String, Object> reply = replies.get(0).getData();
		assertEquals("helper", reply.get("id"));
		assertEquals("main", reply.get("role"));
		assertEquals(3, reply.get("apiVersion"));
		assertEquals(Partners.SETTINGS_GROUP, reply.get("settingsGroup"));
		assertEquals(Partners.HELPER_NAME, reply.get("helper"));
		assertEquals(Partners.KINDS, reply.get("kinds"));
		assertTrue(((List<?>) reply.get("requests")).containsAll(Arrays.asList("saved", "save_pins", "save_route", "focus", "coverage")));
		assertTrue(((List<?>) reply.get("states")).containsAll(Arrays.asList("judged", "layers", "status")));
		Map<String, Object> theme = replies.get(1).getData();
		assertEquals("theme", theme.get("topic"));
		assertEquals("chat_busy", replies.get(2).getData().get("topic"));
		assertTrue("pushes are numbered", (Integer) replies.get(2).getData().get("seq") > (Integer) theme.get("seq"));
		Map<?, ?> colors = (Map<?, ?>) theme.get("colors");
		assertTrue(colors.get("ACCENT") instanceof Color);
		assertTrue(colors.containsKey("PAGE"));
		partners.themeChanged();
		assertEquals(Arrays.asList("theme"), heardNames());
	}

	@Test
	public void aPartnerCannotClaimToBeTheMainPluginAndBadInputIsIgnored()
	{
		partner.send("partner_hello", "role", "main");
		assertFalse(partners.partTwoPresent());
		partner.send("register", "kind", "painter", "id", "x", "call", "not a callback");
		partner.send("register", "kind", "nonsense", "id", "x", "call", (Runnable) () -> { });
		partner.send("register", "kind", "painter", "id", "bad id!", "call", painter(new AtomicInteger()));
		new PartnerDouble(main, "bad owner!").register("painter", "x", 1, painter(new AtomicInteger()));
		assertEquals(0, partners.slots(Partners.Kind.PAINTER).length);
	}

	@Test
	public void limitsHoldForSlotsAndOwners()
	{
		AtomicInteger n = new AtomicInteger();
		for (int i = 0; i < Partners.MAX_PER_OWNER + 5; i++)
		{
			partner.register("painter", "p" + i, 1, painter(n));
		}
		assertEquals(Partners.MAX_PER_OWNER, partners.slots(Partners.Kind.PAINTER).length);
		for (int i = 0; i < Partners.MAX_OWNERS + 3; i++)
		{
			new PartnerDouble(main, "o" + i).register("card", "c", 1, (Function<Map<String, Object>, Object>) m -> null);
		}
		assertTrue(partners.slots(Partners.Kind.CARD).length <= Partners.MAX_OWNERS);
	}

	// ------------------------------------------------------------------ actions

	@Test
	public void anActionGoesToTheFirstHandlerWithThePayloadAndNobodyHandlingItIsKnown()
	{
		assertFalse(partners.handles("share_ticked"));
		assertFalse(partners.act("share_ticked", Collections::emptyMap));
		AtomicReference<Map<String, Object>> got = new AtomicReference<>();
		partner.register("action", "share_ticked", 1, (Consumer<Map<String, Object>>) got::set);
		assertTrue(partners.handles("share_ticked"));
		assertTrue(partners.act("share_ticked", () -> PartnerDouble.data("pins", Collections.emptyList())));
		assertNotNull(got.get().get("pins"));
	}

	@Test
	public void aFailingActionHandlerDoesNotReachTheCaller()
	{
		partner.register("action", "boom", 1, (Consumer<Map<String, Object>>) m ->
		{
			throw new IllegalArgumentException("x");
		});
		for (int i = 0; i < 3; i++)
		{
			assertTrue(partners.act("boom", Collections::emptyMap));
		}
		assertFalse("off after three, so the panel shows the install message", partners.handles("boom"));
	}

	@Test
	public void theNoticeComesOnceAndOnlyWhenAFeatureNeedsPartTwoAndItIsMissing()
	{
		assertFalse(partners.takeNotice());
		partners.setNoticeGrace(0);
		partners.expectHelper();
		assertTrue(partners.takeNotice());
		assertFalse("once", partners.takeNotice());
		assertTrue(Partners.INSTALL.contains(Partners.HELPER_NAME));
		assertEquals("This needs the World Map Plus Helper plugin. Install it from the Plugin Hub for full functionality.", Partners.INSTALL);
	}

	@Test
	public void noNoticeWhenPartTwoIsThere()
	{
		partners.expectHelper();
		partner.hello("part two");
		assertFalse(partners.takeNotice());
	}

	// ------------------------------------------------------------------ the map

	@Test
	public void aHitTestIsKeptWhileThePointerRestsAndAThrowingOneIsCaught()
	{
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<Object> clicked = new AtomicReference<>();
		partner.register("hit", "spot", 1, (Function<Map<String, Object>, Object>) frame ->
		{
			calls.incrementAndGet();
			double x = (Double) frame.get("worldX");
			return PartnerDouble.data("kind", "spot", "name", "Spot " + (int) x, "detail", "Detail", "lines", Arrays.asList("a", "b"),
				"clickable", true, "hint", "Click me", "point", new WorldPoint(3200, 3200, 0), "id", "s1");
		}, "click", (Consumer<Map<String, Object>>) clicked::set);
		List<Partners.Thing> first = partners.hits(VIEW, 0, 0, 100, 50, ANYWHERE);
		List<Partners.Thing> again = partners.hits(VIEW, 0, 0, 100, 50, ANYWHERE);
		assertEquals(1, first.size());
		assertEquals(1, calls.get());
		assertTrue(first.get(0) == again.get(0));
		assertEquals("Spot 3200", first.get(0).name);
		assertTrue(first.get(0).clickable);
		assertEquals("Click me", first.get(0).card().getHint());
		assertEquals(2, first.get(0).card().visibleRows(true).size());
		partners.clicked(first.get(0));
		assertEquals("spot", ((Map<?, ?>) clicked.get()).get("kind"));
		assertEquals("s1", ((Map<?, ?>) clicked.get()).get("id"));
		assertEquals(new WorldPoint(3200, 3200, 0), ((Map<?, ?>) clicked.get()).get("tile"));
		partner.register("hit", "bad", 2, (Function<Map<String, Object>, Object>) frame ->
		{
			throw new IllegalStateException("x");
		});
		for (int i = 0; i < 4; i++)
		{
			partners.hits(VIEW, 0, 0, 10 + i, 10, ANYWHERE);
		}
		assertTrue(partners.slots(Partners.Kind.HIT)[1].isOff());
	}

	@Test
	public void aHitTestMayGiveAWholeCardOfRowsWithTheRestForTheExpandedViewAndAWikiPage()
	{
		List<List<String>> rows = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			rows.add(Arrays.asList("Item " + i, i + " coins"));
		}
		rows.add(Arrays.asList("odd"));
		partner.register("hit", "shop", 1, (Function<Map<String, Object>, Object>) frame -> PartnerDouble.data(
			"kind", "shop", "name", "Bob's shop", "detail", "Run by Bob", "rows", rows, "wiki", "shop", "hint", "Prices move",
			"point", new WorldPoint(3200, 3200, 0)));
		com.worldmapplus.layers.HoverCard card = partners.hits(VIEW, 0, 0, 1, 1, ANYWHERE).get(0).card();
		assertEquals(com.worldmapplus.data.HotspotLoader.MAX_ROWS, card.getRows().size());
		assertEquals("the rest wait for the expanded card", 30 - com.worldmapplus.data.HotspotLoader.MAX_ROWS, card.getOverflow().size());
		assertEquals(30, card.visibleRows(true).size());
		assertEquals("Item 0", card.getRows().get(0).getLabel());
		assertEquals("29 coins", card.visibleRows(true).get(29).getValue());
		assertEquals("Prices move", card.getHint());
		assertEquals("shop", card.getWiki()[0]);
		assertEquals("Bob's shop", card.getWiki()[1]);
	}

	@Test
	public void hitTestResultsAreCheckedAndCapped()
	{
		partner.register("hit", "wild", 1, (Function<Map<String, Object>, Object>) frame -> PartnerDouble.data(
			"name", "n".repeat(500), "lines", Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", 11, null), "point", "not a point", "clickable", "yes"));
		Partners.Thing thing = partners.hits(VIEW, 0, 0, 1, 1, ANYWHERE).get(0);
		assertEquals(ExternalOverlays.MAX_TEXT, thing.name.length());
		assertEquals(Partners.MAX_THING_LINES, thing.card().visibleRows(true).size());
		assertFalse(thing.clickable);
		assertEquals(null, thing.point);
	}

	@Test
	public void cardContributorsAddLinesAndASlowOneIsSkipped() throws Exception
	{
		partner.register("card", "extra", 1, (Function<Map<String, Object>, Object>) thing ->
			Arrays.asList("Seen: " + thing.get("name"), "kind " + thing.get("kind")));
		partner.register("card", "bad", 2, (Function<Map<String, Object>, Object>) thing ->
		{
			throw new IllegalStateException("x");
		});
		Map<String, Object> thing = Partners.describe("shop", "Aemad", new WorldPoint(1, 2, 0), "");
		assertEquals(Arrays.asList("Seen: Aemad", "kind shop"), partners.lines(thing));
		partners.cardBudget = 1_000_000L;
		AtomicInteger calls = new AtomicInteger();
		partner.register("card", "slow", 3, (Function<Map<String, Object>, Object>) t ->
		{
			calls.incrementAndGet();
			try
			{
				Thread.sleep(8);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			return Collections.singletonList("slow");
		});
		partners.lines(thing);
		partners.lines(thing);
		assertEquals(1, calls.get());
	}

	@Test
	public void menuEntriesApplyByTheirOwnTestAndAreToldWhichPlaceWasChosen()
	{
		AtomicReference<Map<String, Object>> chosen = new AtomicReference<>();
		partner.register("menu", "mark", 1, (Consumer<Map<String, Object>>) chosen::set,
			"when", (Function<Map<String, Object>, Object>) m -> Arrays.asList("Mark here"));
		partner.register("menu", "shops", 2, (Consumer<Map<String, Object>>) m -> { }, "label", "Shops",
			"when", (Function<Map<String, Object>, Object>) m -> m.containsKey("thing") ? Arrays.asList("Only at shops", "") : null);
		partner.register("menu", "without when", 3, (Consumer<Map<String, Object>>) m -> { });
		Map<String, Object> context = PartnerDouble.data("tile", new WorldPoint(5, 6, 0), "mapId", 0, "floor", 0);
		List<Partners.Slot> shown = partners.menu(context);
		assertEquals(1, shown.size());
		assertEquals("Mark here", shown.get(0).title);
		partners.choose(shown.get(0), context);
		assertEquals(new WorldPoint(5, 6, 0), chosen.get().get("tile"));
		assertEquals("Mark here", chosen.get().get("option"));
		context.put("thing", Partners.describe("shop", "x", null, ""));
		assertEquals(2, partners.menu(context).size());
	}

	// ------------------------------------------------------------------ search

	@Test
	public void searchResultsAreCheckedRankedAndAnAnswerRaisesTheVersion()
	{
		partners.attach(null, null, null, null, null, (x, y) -> 0);
		AtomicReference<Map<String, Object>> picked = new AtomicReference<>();
		partner.register("search", "mine", 1, (Function<String, Object>) query -> Arrays.asList(
			PartnerDouble.data("name", "Bank of Zed", "detail", "far", "point", new WorldPoint(3200, 3200, 0), "id", "z"),
			PartnerDouble.data("name", "Zed " + query, "detail", "near", "point", new WorldPoint(3201, 3200, 0)),
			PartnerDouble.data("name", "no point"),
			"junk"), "select", (Consumer<Map<String, Object>>) picked::set);
		int before = partners.searchVersion();
		List<SearchEntry> found = partners.search("zed", SearchContext.NONE);
		assertEquals("inline executor answers at once", 2, found.size());
		assertNotEquals(before, partners.searchVersion());
		assertEquals(SearchKind.PARTNER, found.get(0).getKind());
		assertEquals("Zed zed", found.get(0).getName());
		assertEquals("exact start of the name ranks first", "Bank of Zed", found.get(1).getName());
		assertEquals(3200.5, found.get(1).getX(), 0);
		assertTrue("the same text is answered from what is known", found == partners.search("zed", SearchContext.NONE));
		partners.selected(found.get(1));
		assertEquals("z", picked.get().get("id"));
		assertEquals("search", picked.get().get("kind"));
	}

	private static SearchContext places()
	{
		Gson gson = new Gson();
		return new SearchContext(false, 0, 0, 0, new Gazetteer(MapCatalog.load(gson), RegionLines.load(gson), Collections.emptyList()));
	}

	private static Map<String, Object> result(String name, int mapId, int x, int y)
	{
		return PartnerDouble.data("name", name, "point", new WorldPoint(x, y, 0), "mapId", mapId);
	}

	@Test
	public void aFilterOfAPlaceGoesToAProviderAsATestAndEveryResultIsJudgedHereAsWell()
	{
		partners.attach(null, null, null, null, null, (x, y) -> 0);
		List<Map<String, Object>> asked = new ArrayList<>();
		List<String> plain = new ArrayList<>();
		// One provider takes the test and applies it; the other cannot judge a place and answers everything it has.
		partner.register("search", "judges", 1, (Function<String, Object>) q -> Collections.emptyList(), "filtered",
			(Function<Map<String, Object>, Object>) request ->
			{
				asked.add(request);
				@SuppressWarnings("unchecked")
				Predicate<double[]> place = (Predicate<double[]>) request.get("place");
				List<Object> answer = new ArrayList<>();
				for (Map<String, Object> r : Arrays.asList(result("Bank near", 0, 3212, 3430), result("Bank far", 0, 2660, 3300)))
				{
					WorldPoint at = (WorldPoint) r.get("point");
					if (place.test(new double[] {0, at.getX() + 0.5, at.getY() + 0.5}))
					{
						answer.add(r);
					}
				}
				return answer;
			});
		partner.register("search", "cannot", 2, (Function<String, Object>) q ->
		{
			plain.add(q);
			return Arrays.asList(result("Bank other near", 0, 3213, 3430), result("Bank other far", 0, 2660, 3300),
				result("Bank in a cave", 10, 2900, 10100));
		});
		SearchContext context = places();
		List<SearchEntry> found = partners.search("bank region:misthalin", context);
		assertEquals("only what is in the region, from both", "Bank near|Bank other near", names(found));
		assertEquals("the one that judges is given the text and a test, not asked the plain way", "bank region:misthalin", asked.get(0).get("text"));
		assertEquals(Arrays.asList("bank region:misthalin"), plain);
		found = partners.search("bank map:keldagrim", context);
		assertEquals("Bank in a cave", names(found));
		found = partners.search("bank -map:keldagrim", context);
		assertEquals("four of five, the cave is out", 4, found.size());
		assertFalse(names(found).contains("cave"));
		assertEquals("near: a name is a place as well", "Bank near|Bank other near", names(partners.search("bank near:misthalin", context)));
		assertTrue("a filter that cannot be read gives nothing", partners.search("bank dist:far", context).isEmpty());
		asked.clear();
		plain.clear();
		partners.search("bank", context);
		assertTrue("plain words are asked the plain way", asked.isEmpty() && plain.equals(Arrays.asList("bank")));
	}

	private static String names(List<SearchEntry> found)
	{
		StringBuilder out = new StringBuilder();
		for (SearchEntry entry : found)
		{
			out.append(out.length() > 0 ? "|" : "").append(entry.getName());
		}
		return out.toString();
	}

	@Test
	public void aPartnersResultMayNameItsKindMarkTilesRevealIconsAndGiveOtherNames()
	{
		partners.attach(null, null, null, null, null, (x, y) -> 0);
		partner.register("painter", "results", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (g2, frame) -> { },
			"layer", "screen", "input", (Function<Map<String, Object>, Boolean>) event -> false);
		partner.register("search", "mine", 1, (Function<String, Object>) query -> Arrays.asList(
			PartnerDouble.data("name", "Banks", "point", new WorldPoint(3200, 3200, 0), "id", "bank:-1", "kind", "KIND", "reveal", "Bank",
				"aliases", Arrays.asList("Bank", "every bank"), "marks", Arrays.asList(new double[] {3200.5, 3200.5}, new double[] {3210.5, 3200.5}, new double[] {1, 2, 3}, "x")),
			PartnerDouble.data("name", "Odd", "point", new WorldPoint(3201, 3200, 0), "kind", "TILE")));
		List<SearchEntry> found = partners.search("bank", SearchContext.NONE);
		SearchEntry banks = found.get(0);
		assertEquals(SearchKind.KIND, banks.getKind());
		assertEquals("Bank", banks.getReveal());
		assertEquals("the badly shaped mark is left out", 2, banks.getAlsoAt().size());
		assertEquals("a result is found by the names it gives", 1, new com.worldmapplus.search.SearchIndex(Collections.singletonList(banks)).search("every bank", 5).size());
		assertEquals("a kind that is not one of the allowed stays a plugin result", SearchKind.PARTNER, found.get(1).getKind());
		partners.chosen(3, banks);
		Map<?, ?> entry = (Map<?, ?>) PartnerDouble.pushed(partner.heard(), "chosen").get("entry");
		assertEquals("bank:-1", entry.get("id"));
		assertEquals("KIND", entry.get("kind"));
		assertEquals(2, ((List<?>) entry.get("alsoAt")).size());
	}

	@Test
	public void searchRunsOneAtATimeOnTheExecutorAndOnlyTheNewestTextIsKept()
	{
		List<Runnable> queued = new ArrayList<>();
		partners.attach(null, null, queued::add, null, null, (x, y) -> 0);
		List<String> asked = new ArrayList<>();
		partner.register("search", "mine", 1, (Function<String, Object>) query ->
		{
			asked.add(query);
			return Collections.singletonList(PartnerDouble.data("name", "Hit " + query, "point", new WorldPoint(3200, 3200, 0)));
		});
		assertTrue(partners.search("a", SearchContext.NONE).isEmpty());
		assertTrue(partners.search("ab", SearchContext.NONE).isEmpty());
		assertTrue(partners.search("abc", SearchContext.NONE).isEmpty());
		assertEquals("one job only, even after three keys", 1, queued.size());
		queued.get(0).run();
		assertEquals("the keys typed while it ran are skipped", Arrays.asList("abc"), asked);
		assertEquals(1, partners.search("abc", SearchContext.NONE).size());
		assertTrue("an older text gets nothing", partners.search("ab", SearchContext.NONE).isEmpty());
	}

	@Test
	public void aSearchProviderThatThrowsOrIsSlowDoesNotBreakSearch()
	{
		partners.attach(null, null, null, null, null, (x, y) -> 0);
		partner.register("search", "bad", 1, (Function<String, Object>) q ->
		{
			throw new IllegalStateException("x");
		});
		for (String q : new String[] {"a", "b", "c", "d"})
		{
			assertTrue(partners.search(q, SearchContext.NONE).isEmpty());
		}
		assertTrue(partners.slots(Partners.Kind.SEARCH)[0].isOff());
	}

	// ------------------------------------------------------------------ saved places

	private void connectStores(PinStore pins, RouteStore routes)
	{
		partners.attach(null, null, null, pins, routes, (x, y) -> x > 0 ? 0 : -1);
	}

	@Test
	public void aSnapshotOfPinsAndRoutesIsAnsweredOnATickAndAChangeIsAnnounced()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		long a = pins.add("Bank", "pin", 0, 3200.5, 3201.5, 1).getId();
		long b = pins.add("Shop", "shop", 0, 3210.5, 3211.5, 0).getId();
		routes.create("Loop", Arrays.asList(a, b), true);
		partner.hello("other");
		heardNames();
		partner.request("saved", "id", "snap:1");
		assertTrue("answered on the next tick, never at once", main.drainReplies(System.currentTimeMillis()).isEmpty());
		Map<String, Object> reply = PartnerDouble.reply(partner.heard(), "snap:1");
		assertNotNull(reply);
		assertEquals("saved", reply.get("topic"));
		List<?> pinList = (List<?>) reply.get("pins");
		assertEquals(2, pinList.size());
		Map<?, ?> bank = (Map<?, ?>) pinList.get(0);
		assertEquals("Bank", bank.get("name"));
		assertEquals(new WorldPoint(3200, 3201, 1), bank.get("point"));
		Map<?, ?> route = (Map<?, ?>) ((List<?>) reply.get("routes")).get(0);
		assertEquals(Arrays.asList(a, b), route.get("stops"));
		assertEquals(true, route.get("loop"));
		partners.tick(System.currentTimeMillis());
		pins.rename(a, "Bank 2");
		partners.tick(System.currentTimeMillis());
		assertTrue(heardNames().contains("saved_changed"));
		partners.tick(System.currentTimeMillis());
		assertFalse("only when something changed", heardNames().contains("saved_changed"));
	}

	@Test
	public void aPartnerCanMakePinsAndRoutesAndEverythingIsChecked()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		partner.hello("other");
		partner.request("save_pins", "id", "c1", "points", tiles(3200, 3200, -5, 3200), "names", Arrays.asList("Made"), "kind", "Shop!");
		partner.request("save_pins", "id", "c3", "points", Arrays.asList("not a point"));
		List<ExternalOverlays.Reply> heard = partner.heard();
		Map<String, Object> made = PartnerDouble.reply(heard, "c1");
		assertEquals(true, made.get("ok"));
		assertEquals(1, made.get("added"));
		assertEquals(1, made.get("skipped"));
		assertEquals(2, ((List<?>) made.get("pinIds")).size());
		assertEquals("bad_request", PartnerDouble.reply(heard, "c3").get("reason"));
		assertEquals(1, pins.all().size());
		assertEquals("Made", pins.all().get(0).getName());
		assertEquals("shop", pins.all().get(0).getKind());
		long id = pins.all().get(0).getId();
		partner.request("save_route", "id", "r", "name", "R", "points", tiles(3200, 3200, 3200, 3200), "loop", true);
		assertEquals("ok", PartnerDouble.reply(partner.heard(), "r").get("reason"));
		assertEquals(1, routes.all().size());
		assertEquals("a place the player has is used again", Arrays.asList(id, id), routes.all().get(0).getStops());
		assertTrue(routes.all().get(0).isLoop());
	}

	@Test
	public void questionsWithoutTheSavedPlacesAreNotReadyAndUnknownTopicsAreIgnored()
	{
		partner.hello("other");
		partner.request("saved", "id", "n1");
		partner.request("no_such_topic", "id", "n2");
		List<ExternalOverlays.Reply> heard = partner.heard();
		assertEquals("not_ready", PartnerDouble.reply(heard, "n1").get("reason"));
		assertEquals(null, PartnerDouble.reply(heard, "n2"));
	}

	// ------------------------------------------------------------------ round 56B: what the Helper plugin asks of this one

	private Map<String, Object> answerTo(String id)
	{
		Map<String, Object> found = PartnerDouble.reply(partner.heard(), id);
		assertNotNull("an answer to " + id, found);
		return found;
	}

	private static List<WorldPoint> tiles(int... xy)
	{
		List<WorldPoint> points = new ArrayList<>();
		for (int i = 0; i < xy.length; i += 2)
		{
			points.add(new WorldPoint(xy[i], xy[i + 1], 0));
		}
		return points;
	}

	@Test
	public void theTilesOfAShareAreSavedAsPinsOnlyWhereTheyAreNewAndHaveAMap()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		partners.connect((map, x, y) -> map == 7 && x == 3300, null, null, null, null);
		pins.add("Mine", "shop", 0, 3200.5, 3201.5, 0);
		partner.hello("part two");
		// One tile the player has, one new, one on no map, one asked for on a map that holds it, one on a map that does not.
		partner.request("save_pins", "id", "s1", "points", tiles(3200, 3201, 3210, 3211, -4, 3000, 3300, 3000, 3301, 3000),
			"maps", Arrays.asList(-1, -1, -1, 7, 7));
		Map<String, Object> answer = answerTo("s1");
		assertEquals(true, answer.get("ok"));
		assertEquals(2, answer.get("added"));
		assertEquals(1, answer.get("had"));
		assertEquals(2, answer.get("skipped"));
		assertEquals(false, answer.get("full"));
		assertEquals(3, pins.all().size());
		assertEquals("Pin at 3210, 3211", pins.all().get(1).getName());
		assertEquals("pin", pins.all().get(1).getKind());
		assertEquals("the map asked for", 7, pins.all().get(2).getMap());
		assertEquals("the place keeps its centre", 3210.5, pins.all().get(1).getX(), 0);
		// The same share again adds nothing.
		partner.request("save_pins", "id", "s2", "points", tiles(3200, 3201, 3210, 3211), "maps", Arrays.asList(-1, -1));
		answer = answerTo("s2");
		assertEquals(0, answer.get("added"));
		assertEquals(2, answer.get("had"));
		assertEquals(3, pins.all().size());
	}

	@Test
	public void aSharedRouteIsSavedWholeOrNotAtAll()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		long mine = pins.add("Mine", "shop", 0, 3226.5, 3220.5, 0).getId();
		partner.hello("part two");
		partner.request("save_route", "id", "r1", "name", "From a friend", "loop", true,
			"points", tiles(3226, 3220, 3230, 3223, 3226, 3220), "maps", Arrays.asList(-1, -1, -1));
		Map<String, Object> answer = answerTo("r1");
		assertEquals("ok", answer.get("reason"));
		assertEquals(1, answer.get("missing"));
		assertEquals(3, answer.get("stops"));
		assertEquals(2, pins.all().size());
		assertEquals(1, routes.all().size());
		List<Long> stops = routes.all().get(0).getStops();
		assertEquals("the place the player had is used again, the new one saved once", Arrays.asList(mine, pins.all().get(1).getId(), mine), stops);
		assertEquals("From a friend", routes.all().get(0).getName());
		assertTrue(routes.all().get(0).isLoop());
		assertEquals("the recipient's usual colour", 0, pins.all().get(1).getColor());

		// A stop on no map: nothing is saved, and the stop is named.
		partner.request("save_route", "id", "r2", "name", "Broken", "points", tiles(3240, 3240, -2, 3000),
			"maps", Arrays.asList(-1, -1));
		answer = answerTo("r2");
		assertEquals("no_map", answer.get("reason"));
		assertEquals(2, answer.get("stop"));
		assertEquals(2, pins.all().size());
		assertEquals(1, routes.all().size());

		// Too many stops.
		List<WorldPoint> many = new ArrayList<>();
		for (int i = 0; i < RouteStore.MAX_STOPS + 1; i++)
		{
			many.add(new WorldPoint(3000 + i, 3000, 0));
		}
		partner.request("save_route", "id", "r3", "name", "Long", "points", many);
		answer = answerTo("r3");
		assertEquals("too_many_stops", answer.get("reason"));
		assertEquals(RouteStore.MAX_STOPS, answer.get("max"));
		assertEquals(2, pins.all().size());
	}

	@Test
	public void aSharedRouteThatNeedsMorePlacesThanFitSavesNothing()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		for (int i = 0; i < PinStore.MAX_PINS - 1; i++)
		{
			pins.add("P" + i, "pin", 0, 100.5 + i, 100.5, 0);
		}
		partner.hello("part two");
		partner.request("save_route", "id", "r4", "name", "Big", "points", tiles(3000, 3000, 3001, 3000));
		Map<String, Object> answer = answerTo("r4");
		assertEquals("places_full", answer.get("reason"));
		assertEquals(2, answer.get("missing"));
		assertEquals(PinStore.MAX_PINS - 1, pins.all().size());
		assertTrue(routes.all().isEmpty());
	}

	@Test
	public void badShareRequestsAreRefused()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		partner.hello("part two");
		partner.request("save_pins", "id", "b1", "points", Collections.emptyList());
		assertEquals("bad_request", answerTo("b1").get("reason"));
		partner.request("save_pins", "id", "b2", "points", Arrays.asList("x", new WorldPoint(3000, 3000, 0)));
		assertEquals("bad_request", answerTo("b2").get("reason"));
		List<WorldPoint> tooMany = new ArrayList<>();
		for (int i = 0; i < Partners.MAX_SHARED + 1; i++)
		{
			tooMany.add(new WorldPoint(3000 + i, 3000, 0));
		}
		partner.request("save_pins", "id", "b3", "points", tooMany);
		assertEquals("bad_request", answerTo("b3").get("reason"));
		assertTrue(pins.all().isEmpty());
	}

	@Test
	public void aStatusLineAndTheOpeningOfThePanelAreHandedOnWithTheTextCleaned()
	{
		List<String> lines = new ArrayList<>();
		AtomicInteger opened = new AtomicInteger();
		partners.connect(null, lines::add, opened::incrementAndGet, null, null);
		partner.hello("part two");
		partner.state("status", "text", "Copied the whole code.");
		partner.state("status", "text", "", "open", true);
		partner.state("status", "open", true, "text", 5);
		partner.send("state", "topic", "status", "seq", 1, "text", "an old number is ignored");
		assertEquals(Arrays.asList("Copied the whole code."), lines);
		assertEquals(2, opened.get());
		StringBuilder long200 = new StringBuilder();
		for (int i = 0; i < 200; i++)
		{
			long200.append('x');
		}
		partner.state("status", "text", long200.toString());
		assertEquals("cut to the usual length", 120, lines.get(1).length());
		// At most so many a second from one partner.
		for (int i = 0; i < 30; i++)
		{
			partner.state("status", "text", "again " + i);
		}
		assertTrue(lines.size() <= Partners.PER_SECOND);
	}

	@Test
	public void aRouteRequestIsAnsweredWithWhatShortestPathCouldDo()
	{
		AtomicReference<WorldPoint> asked = new AtomicReference<>();
		String[] reason = {"ok"};
		partners.connect(null, null, null, at ->
		{
			asked.set(at);
			return reason[0];
		}, null);
		partner.hello("part two");
		partner.request("go", "id", "g1", "point", new WorldPoint(3200, 3201, 1));
		Map<String, Object> answer = answerTo("g1");
		assertEquals(new WorldPoint(3200, 3201, 1), asked.get());
		assertEquals(true, answer.get("ok"));
		assertEquals("ok", answer.get("reason"));
		reason[0] = "missing";
		partner.request("go", "id", "g2", "point", new WorldPoint(3200, 3201, 1));
		answer = answerTo("g2");
		assertEquals(false, answer.get("ok"));
		assertEquals("missing", answer.get("reason"));
		partner.request("go", "id", "g3", "point", "nowhere");
		assertEquals("bad_request", answerTo("g3").get("reason"));
		WorldPoint before = asked.get();
		partner.request("go", "point", new WorldPoint(3300, 3300, 0));
		partner.heard();
		assertEquals("asked without an id: done, with no answer", new WorldPoint(3300, 3300, 0), asked.get());
	}

	@Test
	public void aPlacePayloadSaysWhichMapItsTileIsOnAndKeepsTheOrderGiven()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		long a = pins.add("A", "pin", 0, 3200.5, 3201.5, 0).getId();
		long b = pins.add("B", "pin", 9, 3210.5, 3211.5, 2).getId();
		Map<String, Object> payload = partners.payload(Arrays.asList(b, a), Collections.emptyList());
		assertEquals(Arrays.asList(b, a), payload.get("order"));
		List<?> list = (List<?>) payload.get("pins");
		Map<?, ?> first = (Map<?, ?>) list.get(0);
		Map<?, ?> second = (Map<?, ?>) list.get(1);
		assertEquals("the tile is on map 0 by itself", 0, first.get("autoMap"));
		assertEquals(0, first.get("mapId"));
		assertEquals("B was saved on map 9", 9, second.get("mapId"));
		assertEquals(0, second.get("autoMap"));
		assertEquals(new WorldPoint(3210, 3211, 2), second.get("point"));
	}

	@Test
	public void thePartnerIsToldWhenThePluginsOwnSearchTakesTheChatAreaAndWhenItLetsGo()
	{
		boolean[] busy = {false};
		partners.connect(null, null, null, null, () -> busy[0]);
		partner.hello("part two");
		partners.tick(System.currentTimeMillis());
		assertEquals("told once with the handshake", 1, java.util.Collections.frequency(heardNames(), "chat_busy"));
		busy[0] = true;
		partners.tick(System.currentTimeMillis());
		assertEquals(true, PartnerDouble.pushed(partner.heard(), "chat_busy").get("busy"));
		partners.tick(System.currentTimeMillis());
		assertFalse("only when it changed", heardNames().contains("chat_busy"));
		busy[0] = false;
		partners.tick(System.currentTimeMillis());
		assertEquals(false, PartnerDouble.pushed(partner.heard(), "chat_busy").get("busy"));
	}

	@Test
	public void theThemeMessageCarriesTheEdgeWidth()
	{
		assertEquals(1f, ((Number) Partners.themeData().get("edgeWidth")).floatValue(), 0);
		assertTrue(((Map<?, ?>) Partners.themeData().get("colors")).containsKey("CARD"));
	}

	@Test
	public void theNoticeWaitsForAPartnerThatStartsAMomentLater()
	{
		long[] now = {1_000};
		partners.clock = () -> now[0];
		partners.expectHelper();
		assertFalse("not at once", partners.takeNotice());
		now[0] += Partners.NOTICE_GRACE_MILLIS - 1;
		assertFalse(partners.takeNotice());
		now[0] += 1;
		assertTrue(partners.takeNotice());
	}

	// ------------------------------------------------------------------ v1 is the Helper's

	@Test
	public void versionOneMessagesAreNotAnsweredHereAndLeaveThePartnersAlone()
	{
		partner.hello("part two");
		partner.register("painter", "p", 1, painter(new AtomicInteger()));
		heardNames();
		main.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "marker",
			PartnerDouble.data("id", "m1", "point", new WorldPoint(3222, 3218, 0), "name", "Old plugin")));
		main.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "hello", PartnerDouble.data("id", "v1:hello")));
		main.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, "marker_clear", PartnerDouble.data()));
		assertTrue("nobody is answered: the Helper answers version 1", partner.heard().isEmpty());
		assertEquals("partners are untouched by v1 messages", 1, partners.slots(Partners.Kind.PAINTER).length);
	}

	@Test
	public void stoppingTheMainPluginForgetsEveryPartner()
	{
		partner.hello("part two");
		partner.register("painter", "p", 1, painter(new AtomicInteger()));
		main.clear();
		assertEquals(0, partners.slots(Partners.Kind.PAINTER).length);
		assertFalse(partners.partTwoPresent());
	}

	// ------------------------------------------------------------------ panels, chosen, places, show

	@Test
	public void aPanelIsDrawnWithAFrameOfArraysAndTakesInputOnlyWhereItsBoxIs()
	{
		partner.hello("part two");
		int[] seen = new int[4];
		List<String> events = new ArrayList<>();
		BiConsumer<Graphics2D, Map<String, Object>> draw = (g2, frame) ->
		{
			int[] limits = (int[]) frame.get("limits");
			int[] box = (int[]) frame.get("box");
			seen[0] = limits[0];
			seen[1] = limits[1];
			seen[2] = ((int[]) frame.get("mouse"))[0];
			seen[3] = (int) ((double[]) frame.get("player"))[0];
			box[0] = 10;
			box[1] = 20;
			box[2] = 100;
			box[3] = 50;
		};
		Function<Map<String, Object>, Boolean> input = event ->
		{
			events.add(event.get("type") + ":" + event.get("x") + ":" + event.get("amount"));
			return "click".equals(event.get("type"));
		};
		partner.register("painter", "results", 1, draw, "layer", "screen", "input", input);
		assertEquals(1, partners.slots(Partners.Kind.PANEL).length);
		int[] box = new int[5];
		assertFalse(Partners.onPanel(box, 11, 21));
		partners.paintPanels(g, 3, box, new java.awt.Rectangle(0, 0, 200, 100), 40, 190, new java.awt.Point(5, 6), 1000L, true, 7, 1.0, 2.0);
		assertEquals(40, seen[0]);
		assertEquals(190, seen[1]);
		assertEquals(5, seen[2]);
		assertEquals(7, seen[3]);
		assertTrue(Partners.onPanel(box, 11, 21));
		assertFalse(Partners.onPanel(box, 110, 21));
		assertTrue(partners.panelInput(3, "click", 11, 21, 0));
		assertFalse(partners.panelInput(3, "wheel", 11, 21, 2));
		assertEquals(Arrays.asList("click:11:0", "wheel:11:2"), events);
	}

	@Test
	public void aPanelThatThrowsThreeTimesIsSwitchedOff()
	{
		partner.hello("part two");
		partner.register("painter", "bad", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (g2, frame) ->
		{
			throw new IllegalStateException("no");
		}, "layer", "screen", "input", (Function<Map<String, Object>, Boolean>) event -> false);
		partners.paintBudget = Long.MAX_VALUE / 4;
		int[] box = new int[5];
		for (int i = 0; i < 4; i++)
		{
			partners.paintPanels(g, 1, box, new java.awt.Rectangle(0, 0, 200, 100), 0, 200, null, 0L, false, -1, 0, 0);
		}
		assertTrue(partners.slots(Partners.Kind.PANEL)[0].isOff());
	}

	@Test
	public void aChoiceIsToldToPartnersWithAPanel()
	{
		SearchEntry shop = new SearchEntry(SearchKind.ITEM, "Bucket", "Sold at Shop for 5 coins, 10 in stock", 0, 3200, 3200);
		partners.mapNames(id -> id == 0 ? "Gielinor" : null);
		partner.hello("part two");
		heardNames();
		partners.chosen(1, shop);
		assertFalse("nobody has a panel", heardNames().contains("chosen"));
		partner.register("painter", "results", 1, (BiConsumer<Graphics2D, Map<String, Object>>) (g2, frame) -> { },
			"layer", "screen", "input", (Function<Map<String, Object>, Boolean>) event -> false);
		partners.chosen(1, shop);
		Map<String, Object> told = PartnerDouble.pushed(partner.heard(), "chosen");
		assertEquals(1, told.get("surface"));
		Map<?, ?> entry = (Map<?, ?>) told.get("entry");
		assertEquals("ITEM", entry.get("kind"));
		assertEquals("Bucket", entry.get("name"));
		assertEquals("Gielinor", entry.get("mapName"));
	}

	@Test
	public void aShowRequestIsKeptForItsSurfaceAndTakenOnce()
	{
		partner.hello("part two");
		partner.request("show", "surface", 2, "mapId", 0, "x", 3200.0, "y", 3201.0, "name", "Row", "detail", "3 here",
			"marks", Arrays.asList(new double[] {1, 2}, new double[] {Double.NaN, 1}, "junk"));
		partner.heard();
		assertEquals(null, partners.takeShow(1));
		Partners.Show show = partners.takeShow(2);
		assertNotNull(show);
		assertEquals(1, show.marks.size());
		assertEquals("Row", show.name);
		assertEquals(null, partners.takeShow(2));
		partner.request("show", "surface", 2, "mapId", 0, "x", Double.NaN, "y", 1.0);
		partner.heard();
		assertEquals(null, partners.takeShow(2));
	}

	@Test
	public void aCardButtonIsOfferedAndPressedThroughTheOwnersFunctions()
	{
		partner.hello("part two");
		java.util.List<String> pressed = new ArrayList<>();
		partner.register("button", "b", 1, (Function<Map<String, Object>, List<String>>) m ->
			m.get("name") == null ? null : Arrays.asList("Wiki", "Open " + m.get("name")),
			"click", (Function<Map<String, Object>, String>) m ->
			{
				pressed.add((String) m.get("name"));
				return "Done";
			});
		assertEquals(null, partners.button(Partners.subject("Bank", null, "Bank")));
		Partners.Button button = partners.button(Partners.subject("Zulrah", new String[] {"quest", "Zulrah", ""}, null));
		assertNotNull(button);
		assertEquals("Wiki", button.label);
		assertEquals("Open Zulrah", button.menu);
		assertEquals("Done", partners.press(button.key, Partners.subject("Zulrah", new String[] {"quest", "Zulrah", ""}, null)));
		assertEquals(Collections.singletonList("Zulrah"), pressed);
		assertEquals(null, partners.press("nobody|nothing", Partners.subject("Zulrah", new String[] {"quest", "Zulrah", ""}, null)));
		partner.goodbye();
		assertEquals(null, partners.button(Partners.subject("Zulrah", new String[] {"quest", "Zulrah", ""}, null)));
	}

	private Map<String, Object> lastReply(String id)
	{
		return PartnerDouble.reply(partner.heard(), id);
	}

	@Test
	public void theStepsOfTheRouteArePushedToPartnersAndNothingIsSentToNobody()
	{
		List<double[]> steps = Arrays.asList(new double[] {3010.5, 3090.5, 3030.5, 3090.5, 0, 0, 1, 20},
			new double[] {3030.5, 3090.5, 3150.5, 3090.5, 0, 0, 0, 0});
		partners.route(steps);
		assertFalse("nobody is there to be told", heardNames().contains("state"));
		partner.hello("part two");
		heardNames();
		partners.route(steps);
		Map<String, Object> told = PartnerDouble.pushed(partner.heard(), "route");
		assertEquals(2, ((List<?>) told.get("steps")).size());
		assertArrayEquals(new double[] {3010.5, 3090.5, 3030.5, 3090.5, 0, 0, 1, 20}, (double[]) ((List<?>) told.get("steps")).get(0), 0);
		partners.route(Collections.emptyList());
		assertTrue(((List<?>) PartnerDouble.pushed(partner.heard(), "route").get("steps")).isEmpty());
	}

	@Test
	public void aRouteIsReorderedOnlyWhileItHasTheStopsAskedAboutAndTheUndoIsKeptUntilTheOwnerGoes()
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		connectStores(pins, routes);
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			ids.add(pins.add("P" + i, "pin", 0, 3200.5 + i, 3200.5, 0).getId());
		}
		long route = routes.create("R", ids, false).getId();
		List<Long> wanted = Arrays.asList(ids.get(0), ids.get(2), ids.get(1), ids.get(3));
		partner.hello("part two");
		partner.request("route_order", "id", "o1", "route", route, "expect", ids, "stops", wanted);
		assertEquals("ok", lastReply("o1").get("reason"));
		assertEquals(wanted, routes.get(route).getStops());
		partner.request("route_order", "id", "o2", "route", route, "expect", ids, "stops", wanted);
		assertEquals("changed", lastReply("o2").get("reason"));
		partner.request("route_order", "id", "o3", "route", route, "expect", wanted, "stops", Arrays.asList(ids.get(0), ids.get(0), 5L, 6L));
		assertEquals("bad_stop", lastReply("o3").get("reason"));
		assertEquals(wanted, routes.get(route).getStops());
		partner.state("order_undo", "route", route, "stops", wanted);
		assertTrue(partners.undoable(route, wanted));
		assertFalse(partners.undoable(route, ids));
		int version = partners.orderVersion();
		partner.state("order_undo", "route", route, "stops", Arrays.asList(1L, "x"));
		assertTrue("a bad message keeps the last good state", partners.undoable(route, wanted));
		partner.state("order_undo", "route", route, "stops", Collections.emptyList());
		assertFalse(partners.undoable(route, wanted));
		assertNotEquals(version, partners.orderVersion());
		partner.state("order_undo", "route", route, "stops", wanted);
		partner.goodbye();
		assertFalse("cleared when its owner says goodbye", partners.undoable(route, wanted));
	}
}
