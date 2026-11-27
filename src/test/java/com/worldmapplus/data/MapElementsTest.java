package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.LabelLayer;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.unlock.Needs;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.Quest;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The cards, the game's own labels and icons, read from the bundled {@code things.json} ({@code tools/build_cards.py}), and the rules
 * the plugin applies as it reads them: what a card asks, and when what the wiki says an icon asks is added.
 */
public class MapElementsTest
{
	private static final Gson GSON = new Gson();
	private static MapCatalog catalog;
	private static List<Hotspot> existing;
	private static MapElements elements;

	@BeforeClass
	public static void load()
	{
		catalog = MapCatalog.load(GSON);
		elements = MapElements.load(GSON);
		existing = elements.loaded(Collections.emptyList());
	}

	@Test
	public void theGamesOwnLabelsAreThere()
	{
		assertTrue("labels " + elements.labelCount(), elements.labelCount() > 800);
		boolean lumbridge = false;
		boolean nemus = false;
		for (MapElements.Label label : elements.labelsOn(MapInfo.SURFACE))
		{
			lumbridge |= label.getText().equals("Lumbridge");
			nemus |= label.getText().replace("\n", " ").equals("Nemus Retreat");
		}
		assertTrue(lumbridge);
		assertTrue("the area the owner named", nemus);
	}

	@Test
	public void aLabelBreaksWhereTheGameBreaksIt()
	{
		for (MapElements.Label label : elements.labelsOn(MapInfo.SURFACE))
		{
			if (label.getText().startsWith("Kingdom of"))
			{
				assertEquals(2, label.getLines().length);
				assertEquals(2, label.getScale());
				return;
			}
		}
		throw new AssertionError("no Kingdom label");
	}

	@Test
	public void theIconsOfTheMapKnowTheirNames()
	{
		List<Hotspot> icons = elements.getIcons();
		assertTrue("icons " + icons.size(), icons.size() > 1500);
		boolean bank = false;
		boolean anvil = false;
		for (Hotspot icon : icons)
		{
			String title = icon.getCard().getTitle();
			bank |= title.equals("Bank");
			anvil |= title.equals("Anvil");
			assertFalse("no unnamed icon", title.isEmpty());
		}
		assertTrue(bank);
		assertTrue(anvil);
	}

	@Test
	public void theCardsOfEveryKindAreThere()
	{
		int shops = 0;
		int doorways = 0;
		int transport = 0;
		int teleports = 0;
		for (Hotspot h : existing)
		{
			assertNotNull("on a known map", catalog.byId(h.getMapId()));
			assertFalse(h.getCard().getTitle().contains("<"));
			shops += h.getKind() == Hotspot.Kind.SHOP ? 1 : 0;
			doorways += h.getKind() == Hotspot.Kind.DOORWAY ? 1 : 0;
			transport += h.getKind() == Hotspot.Kind.TRANSPORT ? 1 : 0;
			teleports += h.getKind() == Hotspot.Kind.TELEPORT ? 1 : 0;
		}
		assertTrue("shops " + shops, shops > 300);
		assertTrue("doorways " + doorways, doorways > 200);
		assertTrue("transport and shortcuts " + transport, transport > 180);
		assertTrue("teleport destinations " + teleports, teleports > 100);
	}

	@Test
	public void dungeonIconsCarryTheGamesOwnLinkSoAClickOpensTheOtherMap()
	{
		int linked = 0;
		for (Hotspot icon : elements.getIcons())
		{
			// An icon that took over the card of a transport or an entrance keeps that card's own text; the others carry the game's link.
			if (icon.getTarget() != null)
			{
				linked++;
				assertNotNull(catalog.byId(icon.getTarget().getMapId()));
				if (icon.getKind() == Hotspot.Kind.POI)
				{
					assertTrue("no preview on " + icon.getCard().getTitle(), icon.getCard().hasPreview());
					assertEquals("Click to open this map", icon.getCard().getHint());
				}
			}
		}
		assertTrue("linked icons " + linked, linked > 15);
	}

	@Test
	public void theHesporiEntranceCanBeFollowed()
	{
		// The Hespori entrance on the surface had a one way twin that hid it.
		Hotspot found = null;
		for (Hotspot spot : existing)
		{
			if (spot.getKind() == Hotspot.Kind.DOORWAY && spot.getMapId() == MapInfo.SURFACE && Math.abs(spot.getX() - 1232.5) < 2
				&& Math.abs(spot.getY() - 3729.5) < 2)
			{
				assertNotNull("followable: " + spot.getCard().getTitle(), spot.getTarget());
				found = spot;
			}
		}
		assertNotNull(found);
	}

	@Test
	public void labelsAreDrawnWhereTheyBelongAndSizesShowAtTheirOwnZooms()
	{
		assertTrue(LabelLayer.showsAt(0, 4.0));
		assertFalse(LabelLayer.showsAt(0, 1.0));
		assertTrue(LabelLayer.showsAt(1, 1.0));
		assertTrue(LabelLayer.showsAt(2, 0.3));
		assertFalse(LabelLayer.showsAt(2, 8.0));

		LabelLayer layer = new LabelLayer(elements);
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, 800, 600);
		ViewTransform view = new ViewTransform(3239.5, 3234.5, 4.0, 0, 0, 800, 600);
		layer.paint(g, view, catalog.byId(MapInfo.SURFACE), 0);
		g.dispose();
		int lit = 0;
		for (int x = 300; x < 500; x++)
		{
			for (int y = 270; y < 330; y++)
			{
				if ((image.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF)
				{
					lit++;
				}
			}
		}
		assertTrue("white text pixels near Lumbridge: " + lit, lit > 20);
	}

	@Test
	public void aBuildWithoutTheDataStillWorks()
	{
		MapElements none = MapElements.none();
		assertEquals(0, none.labelCount());
		assertTrue(none.getIcons().isEmpty());
		assertTrue(none.getGameIcons().isEmpty());
		assertTrue(none.loaded(Collections.emptyList()).isEmpty());
		assertTrue(new ArrayList<>(none.labelsOn(0)).isEmpty());
		assertEquals(0, none.categoryCount());
		assertNull(none.categoryNamed("Bank"));
		MapElements broken = GSON.fromJson("{\"things\":[],\"pois\":5,\"own\":-3,\"icons\":[{\"card\":7}]}", MapElements.class).ready();
		assertTrue(broken.loaded(Collections.emptyList()).isEmpty());
		assertNull("a card index off the list gives no card", broken.getGameIcons().get(0).getCard());
	}

	@Test
	public void everyMapTheGamesOwnLinksLeadToCanBeOpenedWithAClick()
	{
		JsonObject file = GSON.fromJson(new InputStreamReader(com.worldmapplus.data.ToolInputs.stream("map-elements.json"),
			StandardCharsets.UTF_8), JsonObject.class);
		Set<Integer> opened = new HashSet<>();
		List<Hotspot> everything = new ArrayList<>(existing);
		everything.addAll(elements.getIcons());
		for (Hotspot spot : everything)
		{
			if (spot.getTarget() != null)
			{
				opened.add(spot.getTarget().getMapId());
			}
		}
		Set<Integer> missing = new TreeSet<>();
		for (JsonElement element : file.getAsJsonArray("links"))
		{
			JsonArray link = element.getAsJsonArray();
			MapInfo from = catalog.mapFor(link.get(0).getAsInt(), link.get(1).getAsInt());
			MapInfo to = catalog.mapFor(link.get(2).getAsInt(), link.get(3).getAsInt());
			if (from != null && to != null && from.getId() != to.getId() && !opened.contains(to.getId()))
			{
				missing.add(to.getId());
			}
		}
		assertTrue("maps the game links to but no click opens: " + missing, missing.isEmpty());
	}

	@Test
	public void aQuestIconIsNamedAfterTheQuestThatStartsBesideIt()
	{
		int quests = 0;
		int generic = 0;
		int starts = 0;
		for (Hotspot icon : elements.getIcons())
		{
			if ("Quest start".equals(icon.getCard().getSubtitle()))
			{
				quests++;
				assertFalse(icon.getCard().getTitle().equals("Quest"));
				starts += icon.getNeed() != null && icon.getNeed().startsQuest() != null ? 1 : 0;
			}
			if (icon.getCard().getTitle().equals("Quest"))
			{
				generic++;
			}
		}
		assertTrue("named quest icons " + quests, quests > 60);
		assertTrue("generic quest icons left " + generic, generic < quests);
		assertTrue("quest icons that know the quest they start " + starts, starts > 60);
	}

	@Test
	public void theIconsOfEachKindCanBeFoundForFlashing()
	{
		int bank = 1055;
		assertTrue(elements.isCategory(bank));
		assertEquals("Bank", elements.categoryName(bank));
		int banks = 0;
		for (MapElements.Spot spot : elements.spotsOn(MapInfo.SURFACE))
		{
			if (spot.getCategory() == bank)
			{
				banks++;
			}
		}
		assertTrue("banks on the surface " + banks, banks > 50);
		assertFalse(elements.isCategory(1));
		assertTrue(elements.spotsOn(-5).isEmpty());
		assertTrue(elements.drawnCategoryNames().contains("Bank"));
		assertFalse(elements.categoriesNamed("Bank").isEmpty());
	}

	@Test
	public void theKeyEntryNamesAreFoundEvenWhenWordedALittleDifferently()
	{
		Integer bank = elements.categoryNamed("Bank");
		assertNotNull(bank);
		assertEquals(bank, elements.categoryNamed("banks"));
		assertEquals(bank, elements.categoryNamed("  BANK "));
		assertNotNull(elements.categoryNamed("Transportation"));
		assertEquals(elements.categoryNamed("Transportation"), elements.categoryNamed("transportations"));
		assertEquals(null, elements.categoryNamed("xx"));
		assertEquals(null, elements.categoryNamed(null));
	}

	@Test
	public void everyDrawnIconHasACardAndTheIconsAnswerForTheirCards()
	{
		GameIconSet set = new GameIconSet(elements.getGameIcons());
		int covers = 0;
		for (GameIconSet.Spec spec : elements.getGameIcons())
		{
			assertNotNull(spec.getCard());
			assertTrue(set.isCardOfAnIcon(spec.getCard()));
			covers += existing.contains(elements.replacementOf(spec.getCard())) || existing.contains(spec.getCard()) ? 1 : 0;
		}
		assertTrue("icons answered by a card of the plugin's own " + covers, covers > 300);
	}

	// --- what the reader does with the checks the file gives (the rules that make them are in tools/test_build_cards.py) ---------

	private static String level(String skill, int level, String text)
	{
		return "{\"k\":\"s\",\"s\":\"" + skill + "\",\"n\":" + level + ",\"t\":\"" + text + "\"}";
	}

	private static String need(String... ways)
	{
		return "{\"any\":[" + String.join(",", ways) + "]}";
	}

	private static MapElements read(String things)
	{
		return GSON.fromJson("{\"pois\":0,\"own\":0,\"things\":[" + things + "],\"icons\":[{\"m\":0,\"x\":10.5,\"y\":20.5,\"card\":0,"
			+ "\"extras\":[{\"layer\":\"tree\",\"name\":\"Yew tree\",\"detail\":\"Woodcutting level 60\"}],\"n\":"
			+ need("[" + level("WOODCUTTING", 60, "60 Woodcutting") + "]") + "}]}", MapElements.class).ready();
	}

	private static final String CARD = "\"kind\":\"POI\",\"m\":0,\"x\":10.5,\"y\":20.5,\"r\":9,\"card\":{\"title\":\"Dungeon\"},\"floor\":0";

	@Test
	public void whatTheWikiSaysAnIconAsksIsAddedAsANeedAndARow()
	{
		MapElements read = read("{" + CARD + ",\"req\":" + need("[" + level("STRENGTH", 60, "60 Strength") + "]",
			"[" + level("AGILITY", 60, "60 Agility") + "]") + ",\"reqRow\":\"60 Strength or 60 Agility\"}");
		Hotspot icon = read.getIcons().get(0);
		assertEquals(2, icon.getNeed().alternatives().length);
		assertEquals("60 Agility", Needs.describe(icon.getNeed().alternatives()[1]));
		assertEquals("Requires", icon.getCard().getRows().get(0).getLabel());
		assertEquals("60 Strength or 60 Agility", icon.getCard().getRows().get(0).getValue());
		assertSame("the icon's card is the one with the requirement", icon, read.getGameIcons().get(0).getCard());
		assertSame(icon.getNeed(), read.getGameIcons().get(0).getNeed());
		assertNull("the card as made asks nothing", read.needOf(0, null, null));
	}

	@Test
	public void aCardThatAsksNothingGivesTheIconWhatItsMergedPointsAsk()
	{
		MapElements read = read("{" + CARD + "}");
		assertNull(read.getIcons().get(0).getNeed());
		assertTrue("no row", read.getIcons().get(0).getCard().getRows().isEmpty());
		assertEquals("60 Woodcutting", Needs.describe(read.getGameIcons().get(0).getNeed().alternatives()[0]));
		MapElements asks = read("{" + CARD + ",\"need\":" + need("[" + level("AGILITY", 50, "50 Agility") + "]") + "}");
		assertEquals("the card's own need wins", "50 Agility", Needs.describe(asks.getGameIcons().get(0).getNeed().alternatives()[0]));
		assertSame("nothing replaced", asks.getIcons().get(0), asks.replacementOf(asks.getIcons().get(0)));
	}

	@Test
	public void aQuestTheIconStartsIsKeptWithTheWikisNeed()
	{
		MapElements read = read("{" + CARD + ",\"need\":{\"starts\":\"Cook's Assistant\",\"quest\":\"COOKS_ASSISTANT\"},\"req\":"
			+ "{\"any\":[[" + level("COOKING", 10, "10 Cooking") + "]],\"starts\":\"Cook's Assistant\",\"quest\":\"COOKS_ASSISTANT\"}}");
		Hotspot icon = read.getIcons().get(0);
		assertEquals("Cook's Assistant", icon.getNeed().startsQuest());
		assertEquals(Quest.COOKS_ASSISTANT, icon.getNeed().startsQuestId());
		assertEquals("10 Cooking", Needs.describe(icon.getNeed().alternatives()[0]));
		assertTrue("no row text, no row", icon.getCard().getRows().isEmpty());
		assertFalse("the card as made only starts the quest", read.needOf(0, null, null).hasAlternatives());
	}

	@Test
	public void aCoveringCardIsShownWithTheRequirementAndSearchedWithout()
	{
		MapElements read = GSON.fromJson("{\"pois\":1,\"own\":1,\"things\":[{\"kind\":\"SHOP\",\"m\":0,\"x\":10.5,\"y\":20.5,\"r\":11,"
			+ "\"card\":{\"title\":\"Shop\",\"rows\":[{\"label\":\"Axe\",\"value\":\"1 coin\"}]},\"req\":{\"any\":[[{\"k\":\"q\","
			+ "\"q\":\"DRAGON_SLAYER_I\",\"t\":\"Dragon Slayer I\"}]]},\"reqRow\":\"Dragon Slayer I\"}],\"icons\":[{\"card\":0}]}",
			MapElements.class).ready();
		Hotspot made = read.loaded(Collections.emptyList()).get(0);
		assertEquals(1, made.getCard().getRows().size());
		assertNull(made.getNeed());
		Hotspot shown = read.replacementOf(made);
		assertEquals("Requires", shown.getCard().getRows().get(1).getLabel());
		assertSame(shown, read.getGameIcons().get(0).getCard());
		assertEquals("Dragon Slayer I", Needs.describe(shown.getNeed().alternatives()[0]));
	}

	@Test
	public void eachTeleportKnowsTheNeedOfItsOwnWay()
	{
		MapElements read = GSON.fromJson("{\"pois\":1,\"own\":1,\"things\":[{\"kind\":\"TELEPORT\",\"m\":0,\"x\":1.5,\"y\":2.5,\"r\":11,"
			+ "\"card\":{\"title\":\"Teleports here\"},\"layer\":\"teleport\",\"ways\":{\"A\":" + need("[" + level("MAGIC", 25, "25 Magic")
			+ "]") + "}}]}", MapElements.class).ready();
		Hotspot teleport = read.loaded(Collections.emptyList()).get(0);
		assertNull("one way asks nothing, so the place asks nothing", teleport.getNeed());
		assertEquals("25 Magic", Needs.describe(teleport.wayNeed("A").alternatives()[0]));
		assertEquals("25 Magic", Needs.describe(read.needOf(0, "A", null).alternatives()[0]));
		assertNull(teleport.wayNeed("B"));
		assertEquals("teleport", teleport.getCategory());
	}

	@Test
	public void thePointsOfInterestGoWhereTheFileSays()
	{
		MapElements read = GSON.fromJson("{\"pois\":1,\"own\":2,\"things\":[" + "{" + CARD + "},{" + CARD + "},{" + CARD + "}]}",
			MapElements.class).ready();
		Hotspot point = new Hotspot(0, 1, 1, 11, com.worldmapplus.layers.HoverCard.builder("Point").build(), null);
		List<Hotspot> loaded = read.loaded(Collections.singletonList(point));
		assertEquals(3, loaded.size());
		assertSame(point, loaded.get(1));
		assertEquals(1, read.getIcons().size());
	}
}
