package com.worldmapplus.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.pins.Pin;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteRunner;
import com.worldmapplus.pins.RouteStore;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;

/** Builds the side panel without a game and looks at what it lists. */
public class PlacesPanelTest
{
	private PinStore pins;
	private RouteStore routes;
	private PlacesPanel panel;

	@Before
	public void setUp()
	{
		pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		ExternalOverlays external = new ExternalOverlays();
		MapActions actions = new MapActions(new EventBus(), external);
		RouteRunner runner = new RouteRunner(routes, pins, new RouteRunner.Navigator()
		{
			@Override
			public boolean routeTo(Pin stop)
			{
				return true;
			}

			@Override
			public void clear()
			{
			}

			@Override
			public int othersRequests()
			{
				return 0;
			}
		});
		panel = new PlacesPanel(pins, routes, runner, actions, external, null, id -> "Gielinor");
	}

	private static void collect(Component component, List<Component> into)
	{
		into.add(component);
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				collect(child, into);
			}
		}
	}

	/** The names of the places ticked-box rows the panel lists, in order. */
	private List<String> listedPlaces()
	{
		List<Component> all = new ArrayList<>();
		collect(panel, all);
		List<String> names = new ArrayList<>();
		for (Component component : all)
		{
			if (component instanceof JCheckBox && ((JCheckBox) component).getToolTipText() != null
				&& !"Include names".equals(((JCheckBox) component).getText()))
			{
				names.add(((JCheckBox) component).getText());
			}
		}
		return names;
	}

	@Test
	public void listsTheSavedPlacesAndFollowsTheFilter() throws Exception
	{
		pins.add("Seed shop", "shop", 0, 1, 1);
		pins.add("Bank", "pin", 0, 2, 2);
		panel.onActivate();
		assertEquals(Arrays.asList("Bank", "Seed shop"), listedPlaces());

		List<Component> all = new ArrayList<>();
		collect(panel, all);
		net.runelite.client.ui.components.IconTextField filter = null;
		for (Component component : all)
		{
			if (component instanceof net.runelite.client.ui.components.IconTextField
			&& "Filter by name, type or area".equals(((net.runelite.client.ui.components.IconTextField) component).getToolTipText()))
			{
				filter = (net.runelite.client.ui.components.IconTextField) component;
			}
		}
		assertTrue(filter != null);
		net.runelite.client.ui.components.IconTextField field = filter;
		javax.swing.SwingUtilities.invokeAndWait(() -> field.setText("seed"));
		assertEquals(Arrays.asList("Seed shop"), listedPlaces());
		javax.swing.SwingUtilities.invokeAndWait(() -> field.setText(""));
		assertEquals(2, listedPlaces().size());
		panel.shutDown();
	}

	@Test
	public void aRemovedPlaceLeavesTheListOnTheNextRefresh()
	{
		Pin pin = pins.add("Seed shop", "shop", 0, 1, 1);
		panel.onActivate();
		assertEquals(1, listedPlaces().size());
		pins.remove(pin.getId());
		panel.onActivate();
		assertFalse(listedPlaces().contains("Seed shop"));
		panel.shutDown();
	}

	private List<String> labels()
	{
		List<Component> all = new ArrayList<>();
		collect(panel, all);
		List<String> texts = new ArrayList<>();
		for (Component component : all)
		{
			if (component instanceof javax.swing.JLabel)
			{
				texts.add(((javax.swing.JLabel) component).getText());
			}
		}
		return texts;
	}

	@Test
	public void aRouteCardListsItsStopsFoldedAndIsNoTallerThanWhatItHolds() throws Exception
	{
		List<Long> stops = new ArrayList<>();
		for (int i = 0; i < 15; i++)
		{
			stops.add(pins.add("Patch " + (i + 1), "place", 0, 3000 + i, 3000).getId());
		}
		// Many more places than routes: the Places page is much taller than the Routes page.
		for (int i = 0; i < 40; i++)
		{
			pins.add("Extra " + i, "place", 0, 2000 + i, 3000);
		}
		long id = routes.create("Fruit tree run", stops, false).getId();
		panel.onActivate();
		panel.selectRoutes();
		List<String> texts = labels();
		assertTrue(texts.contains("Patch 1") && texts.contains("Patch 3"));
		assertFalse("folded to the first three", texts.contains("Patch 4"));
		assertTrue(texts.contains("+12 more"));

		panel.setSize(net.runelite.client.ui.PluginPanel.PANEL_WIDTH, 2000);
		layoutAll(panel);
		int height = panel.getPreferredSize().height;
		panel.setSize(net.runelite.client.ui.PluginPanel.PANEL_WIDTH, height);
		layoutAll(panel);
		PanelTheme.Card card = cardHolding("Fruit tree run");
		assertTrue("the card is as tall as what it holds: " + card.getHeight() + " against " + card.getPreferredSize().height,
			card.getHeight() <= card.getPreferredSize().height);
		assertTrue("and the page is sized to the Routes page, not the long Places page: " + height, height < 900);

		panel.toggleStops(id);
		texts = labels();
		assertTrue(texts.contains("Patch 15") && texts.contains("Show fewer"));
		panel.toggleStops(id);
		assertFalse(labels().contains("Patch 15"));
		panel.toggleStops(id);
		panel.onDeactivate();
		panel.onActivate();
		assertFalse("folded again the next time the panel opens", labels().contains("Patch 15"));
		panel.shutDown();
	}

	private static void layoutAll(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layoutAll((Container) child);
			}
		}
	}

	private PanelTheme.Card cardHolding(String text)
	{
		List<Component> all = new ArrayList<>();
		collect(panel, all);
		for (Component component : all)
		{
			if (component instanceof javax.swing.JLabel && text.equals(((javax.swing.JLabel) component).getText()))
			{
				for (Container up = component.getParent(); up != null; up = up.getParent())
				{
					if (up instanceof PanelTheme.Card)
					{
						return (PanelTheme.Card) up;
					}
				}
			}
		}
		throw new AssertionError("no card holds " + text);
	}

	@Test
	public void theToolbarIconIsASixteenPixelImage()
	{
		assertEquals(16, PlacesPanel.icon().getWidth());
		assertEquals(16, PlacesPanel.icon().getHeight());
	}

	@Test
	public void aNameThatLooksLikeMarkupIsShownAsText()
	{
		javax.swing.JLabel label = PlacesPanel.plainLabel("<html><b>Bank</b>");
		assertEquals("<html><b>Bank</b>", label.getText());
		assertTrue("no html renderer was made for it",
			label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null);
		javax.swing.JCheckBox box = PlacesPanel.plainCheck("<html><img src='x'>", true);
		assertTrue(box.isSelected());
		assertTrue(box.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null);
		// And an ordinary label does make one, which is what the setting prevents.
		assertTrue(new javax.swing.JLabel("<html><b>Bank</b>")
			.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) != null);
	}

	@Test
	public void aSharedRouteWaitsInTheInboxAndIsSavedOnlyWhenAsked() throws Exception
	{
		com.worldmapplus.share.ShareInbox inbox = new com.worldmapplus.share.ShareInbox();
		com.worldmapplus.share.Share route = com.worldmapplus.share.Share.route(Arrays.asList(
			new com.worldmapplus.share.SharePoint(3226, 3220, 0), new com.worldmapplus.share.SharePoint(3230, 3223, 0),
			new com.worldmapplus.share.SharePoint(3226, 3220, 0)), false);
		inbox.receive(com.worldmapplus.share.ShareCodec.encode(route), "test", 1, null, true);
		pins.add("Mine", "shop", 0, 3226.5, 3220.5, 0);
		PlacesPanel shared = new PlacesPanel(pins, routes, null, new MapActions(new EventBus(), new ExternalOverlays()),
			new ExternalOverlays(), null, id -> "Gielinor", inbox,
			new com.worldmapplus.share.ShareImport(pins, routes, () -> com.worldmapplus.map.MapCatalog.load(new Gson())));
		shared.onActivate();
		assertEquals("nothing is added by itself", 1, pins.all().size());
		assertEquals(0, routes.all().size());

		List<Component> all = new ArrayList<>();
		collect(shared, all);
		boolean save = false;
		for (Component component : all)
		{
			save |= component instanceof javax.swing.JButton && "Save".equals(((javax.swing.JButton) component).getText());
		}
		assertTrue("a Save button is shown for the route", save);
		// The panel's Save asks for a name in a dialog; the importer it calls is the same one.
		com.worldmapplus.share.ShareImport importer = new com.worldmapplus.share.ShareImport(pins, routes,
			() -> com.worldmapplus.map.MapCatalog.load(new Gson()));
		String message = importer.saveRoute(inbox.offers().get(0).getShare(), "From a friend");
		assertTrue(message, message.startsWith("Saved"));
		assertEquals("the place the player had is used again, the new one saved once", 2, pins.all().size());
		assertEquals(1, routes.all().size());
		List<Long> stops = routes.all().get(0).getStops();
		assertEquals(3, stops.size());
		assertEquals("Mine", pins.get(stops.get(0)).getName());
		assertEquals(stops.get(0), stops.get(2));
		assertEquals("Pin at 3230, 3223", pins.get(stops.get(1)).getName());
		assertEquals("the recipient's usual colour", 0, pins.get(stops.get(1)).getColor());
		shared.shutDown();
	}

	private static com.worldmapplus.share.Share scatteredRoute(int stops)
	{
		java.util.Random random = new java.util.Random(7);
		List<com.worldmapplus.share.SharePoint> points = new ArrayList<>();
		for (int i = 0; i < stops; i++)
		{
			points.add(new com.worldmapplus.share.SharePoint(960 + random.nextInt(3072), 2048 + random.nextInt(2176), 0));
		}
		return com.worldmapplus.share.Share.route(points, false);
	}

	private PlacesPanel importPanel(com.worldmapplus.share.ShareInbox inbox)
	{
		return new PlacesPanel(pins, routes, null, new MapActions(new EventBus(), new ExternalOverlays()), new ExternalOverlays(), null,
			id -> "Gielinor", inbox, new com.worldmapplus.share.ShareImport(pins, routes, () -> com.worldmapplus.map.MapCatalog.load(new Gson())));
	}

	@Test
	public void aCodePastedIntoTheImportBoxIsListedAndNothingIsSaved() throws Exception
	{
		com.worldmapplus.share.ShareInbox inbox = new com.worldmapplus.share.ShareInbox();
		PlacesPanel panel = importPanel(inbox);
		panel.shares.showImport(true);
		com.worldmapplus.share.ShareInbox.Pasted result = panel.shares.importForTest("!wmp p10kgkm86a");
		assertEquals(1, result.getAdded());
		assertTrue(panel.shares.importStatusText(), panel.shares.importStatusText().contains("Added 1 share to Shared with you"));
		assertEquals("the box is emptied once everything in it was read", "", panel.shares.importBoxText());
		assertEquals(1, inbox.offers().size());
		assertEquals("nothing is saved until Save", 0, pins.all().size());
		assertEquals(0, routes.all().size());
		panel.shutDown();
	}

	@Test
	public void aCodeInPartsIsPastedPieceByPiece() throws Exception
	{
		com.worldmapplus.share.ShareInbox inbox = new com.worldmapplus.share.ShareInbox();
		PlacesPanel panel = importPanel(inbox);
		List<String> messages = com.worldmapplus.share.ShareCodec.messages(scatteredRoute(15));
		assertEquals(2, messages.size());
		panel.shares.importForTest(messages.get(1));
		assertTrue(panel.shares.importStatusText(), panel.shares.importStatusText().contains("1 of 2 parts added, paste the rest"));
		assertEquals(0, inbox.offers().size());
		panel.shares.importForTest(messages.get(0));
		assertTrue(panel.shares.importStatusText(), panel.shares.importStatusText().contains("Added 1 share"));
		assertEquals(scatteredRoute(15), inbox.offers().get(0).getShare());
		assertEquals(0, pins.all().size());
		panel.shutDown();
	}

	@Test
	public void aCodeThatDoesNotReadStaysInTheBoxWithItsReason() throws Exception
	{
		com.worldmapplus.share.ShareInbox inbox = new com.worldmapplus.share.ShareInbox();
		PlacesPanel panel = importPanel(inbox);
		panel.shares.importForTest("!wmp p10kgkm86b");
		assertTrue(panel.shares.importStatusText(), panel.shares.importStatusText().contains("Checksum mismatch"));
		assertEquals("!wmp p10kgkm86b", panel.shares.importBoxText());
		assertEquals(0, inbox.offers().size());
		panel.shutDown();
	}

	@Test
	public void theWholeCodeAndAllPartsCopiesReadBackAsTheSameShare() throws Exception
	{
		com.worldmapplus.share.Share route = scatteredRoute(30);
		PlacesPanel panel = importPanel(new com.worldmapplus.share.ShareInbox());
		panel.shares.show(route);
		String whole = panel.shares.wholeCode();
		assertEquals("!wmp " + com.worldmapplus.share.ShareCodec.encode(route), whole);
		List<String> messages = panel.shares.shownMessages();
		assertEquals(3, messages.size());
		String all = String.join("\n", messages);
		for (String line : all.split("\n"))
		{
			assertTrue(line, line.startsWith("!wmp c1") && line.length() <= com.worldmapplus.share.ShareCodec.MAX_MESSAGE);
		}
		for (String copy : new String[] {whole, all})
		{
			com.worldmapplus.share.ShareInbox other = new com.worldmapplus.share.ShareInbox();
			assertEquals(1, other.importPasted(copy, 1).getAdded());
			assertEquals(route, other.offers().get(0).getShare());
		}
		panel.shutDown();
	}
}
