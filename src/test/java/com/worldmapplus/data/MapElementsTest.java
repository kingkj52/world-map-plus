package com.worldmapplus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.LabelLayer;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** The game's own labels and icon names, read from the bundled file, against the bundled map catalog. */
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
		existing = HotspotLoader.load(GSON, catalog);
		elements = MapElements.load(GSON, catalog, existing);
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
	public void anIconBesideACardThePluginAlreadyHasIsLeftOut()
	{
		for (Hotspot icon : elements.getIcons())
		{
			for (Hotspot other : existing)
			{
				String title = icon.getCard().getTitle();
				String sub = icon.getCard().getSubtitle() == null ? "" : icon.getCard().getSubtitle();
				Hotspot.Kind only = MapElements.kindFor(title);
				if (sub.equals("Dungeon entrance"))
				{
					only = Hotspot.Kind.DOORWAY;
				}
				else if (sub.equals("Transportation") || sub.startsWith("Agility shortcut"))
				{
					only = Hotspot.Kind.TRANSPORT;
				}
				if (icon.getKind() == Hotspot.Kind.POI && other.getCategory() == null && other.getMapId() == icon.getMapId()
				&& (only == null || other.getKind() == only))
				{
					boolean close = Math.abs(other.getX() - icon.getX()) <= 1.4
						&& Math.abs(other.getY() - icon.getY()) <= 1.4;
					assertFalse("an icon at " + icon.getX() + ", " + icon.getY() + " duplicates a card", close);
				}
			}
		}
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
					assertTrue("no preview on " + icon.getCard().getTitle() + " at " + icon.getX() + ", " + icon.getY() + " on "
						+ icon.getMapId(), icon.getCard().hasPreview());
					assertEquals("Click to open this map", icon.getCard().getHint());
				}
			}
		}
		// Most dungeon icons are covered by an entrance card of the plugin, which carries the same link, and are not in this list.
		assertTrue("linked icons " + linked, linked > 15);
	}

	@Test
	public void theHesporiEntranceCanBeFollowed()
	{
		// The Hespori entrance on the surface had a one way twin that hid it.
		Hotspot found = null;
		for (Hotspot spot : existing)
		{
			if (spot.getKind() == Hotspot.Kind.DOORWAY && spot.getMapId() == MapInfo.SURFACE
				&& Math.abs(spot.getX() - 1232.5) < 2
				&& Math.abs(spot.getY() - 3729.5) < 2)
			{
				assertNotNull("followable: " + spot.getCard().getTitle() + " kind " + spot.getKind() + " cat " + spot.getCategory(), spot.getTarget());
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
		assertTrue(new ArrayList<>(none.labelsOn(0)).isEmpty());
	}

	@Test
	public void everyMapTheGamesOwnLinksLeadToCanBeOpenedWithAClick()
	{
		MapElements.File file;
		try (java.io.InputStream in = MapElements.class.getResourceAsStream("/com/worldmapplus/map-elements.json");
			java.io.Reader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
		{
			file = GSON.fromJson(reader, MapElements.File.class);
		}
		catch (java.io.IOException e)
		{
			throw new AssertionError(e);
		}
		java.util.Set<Integer> opened = new java.util.HashSet<>();
		List<Hotspot> everything = new ArrayList<>(existing);
		everything.addAll(elements.getIcons());
		for (Hotspot spot : everything)
		{
			if (spot.getTarget() != null)
			{
				opened.add(spot.getTarget().getMapId());
			}
		}
		java.util.Set<Integer> missing = new java.util.TreeSet<>();
		for (int[] link : file.links)
		{
			MapInfo from = catalog.mapFor(link[0], link[1]);
			MapInfo to = catalog.mapFor(link[2], link[3]);
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
		List<com.worldmapplus.search.SearchEntry> known = SpawnLoader.load(catalog);
		MapElements named = MapElements.load(GSON, catalog, existing, known);
		int quests = 0;
		int generic = 0;
		for (Hotspot icon : named.getIcons())
		{
			if ("Quest start".equals(icon.getCard().getSubtitle()))
			{
				quests++;
				assertFalse(icon.getCard().getTitle().equals("Quest"));
			}
			if (icon.getCard().getTitle().equals("Quest"))
			{
				generic++;
			}
		}
		assertTrue("named quest icons " + quests, quests > 60);
		assertTrue("generic quest icons left " + generic, generic < quests);
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

	private static MapElements.File tiny(String icons)
	{
		return GSON.fromJson("{\"labels\":[{\"t\":\"Falador\",\"x\":2950,\"y\":3380,\"z\":0,\"s\":1,\"c\":16777215}],"
			+ "\"categories\":[\"Dungeon\",\"Transportation\",\"Quest\"],\"categoryIds\":[1062,1104,1056],\"links\":[],"
			+ "\"icons\":{" + icons + "}}", MapElements.File.class);
	}

	private static Hotspot cardAt(MapElements elements, double x, double y)
	{
		for (Hotspot icon : elements.getIcons())
		{
			if (Math.abs(icon.getX() - x) < 0.01 && Math.abs(icon.getY() - y) < 0.01)
			{
				return icon;
			}
		}
		return null;
	}

	@Test
	public void aNoteForAnIconIsUsedWhereNothingRicherIsThere()
	{
		MapElements.File file = tiny("\"0\":[[2940.5,3370.5,1],[2960.5,3370.5,0]]");
		java.util.Map<String, MapElements.Detail> details = new java.util.HashMap<>();
		MapElements.Detail ship = GSON.fromJson("{\"map\":0,\"x\":2940.5,\"y\":3370.5,\"title\":\"Ship to <b>Crandor\","
			+ "\"subtitle\":\"Transportation\",\"rows\":[[\"Fare\",\"free\"]]}", MapElements.Detail.class);
		details.put("0:2940.5:3370.5", ship);
		MapElements elements = MapElements.parse(file, catalog, new ArrayList<>(), new ArrayList<>(), details);
		Hotspot noted = cardAt(elements, 2940.5, 3370.5);
		assertEquals("Ship to bCrandor", noted.getCard().getTitle());
		assertEquals("Transportation", noted.getCard().getSubtitle());
		assertEquals("Fare", noted.getCard().getRows().get(0).getLabel());
		Hotspot plain = cardAt(elements, 2960.5, 3370.5);
		assertEquals("Dungeon", plain.getCard().getTitle());
		assertEquals("Near Falador", plain.getCard().getSubtitle());
	}

	@Test
	public void aCardOfTheRightKindJustOffTheIconIsMovedOntoIt()
	{
		MapElements.File file = tiny("\"0\":[[2940.5,3370.5,0]]");
		HoverCard card = HoverCard.builder("Cave entrance").subtitle("Leads somewhere").build();
		Hotspot door = new Hotspot(0, 2942.5, 3370.5, 11, card, null, new ArrayList<>(), Hotspot.Kind.DOORWAY);
		Hotspot shop = new Hotspot(0, 2941.0, 3370.5, 11, HoverCard.builder("A shop").build(), null, new ArrayList<>(),
			Hotspot.Kind.SHOP);
		MapElements elements = MapElements.parse(file, catalog, java.util.Arrays.asList(door, shop), new ArrayList<>());
		Hotspot moved = cardAt(elements, 2940.5, 3370.5);
		assertEquals("a dungeon icon takes the entrance, not the nearer shop", "Cave entrance", moved.getCard().getTitle());
		assertEquals(Hotspot.Kind.DOORWAY, moved.getKind());
	}

	@Test
	public void anIconInsideADungeonSaysWhichMapItIsOn()
	{
		MapInfo dungeon = null;
		for (MapInfo map : catalog.getMaps())
		{
			if (map.getId() == 12)
			{
				dungeon = map;
			}
		}
		assertNotNull(dungeon);
		double x = dungeon.getCenterX();
		double y = dungeon.getCenterY();
		MapElements.File file = GSON.fromJson("{\"labels\":[],\"categories\":[\"Map link\"],\"categoryIds\":[1063],\"links\":[],"
			+ "\"icons\":{\"12\":[[" + x + "," + y + ",0]]}}", MapElements.File.class);
		MapElements elements = MapElements.parse(file, catalog, new ArrayList<>(), new ArrayList<>());
		assertEquals("In " + dungeon.getName(), cardAt(elements, x, y).getCard().getSubtitle());
	}
}
