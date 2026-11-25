package com.worldmapplus.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.bridge.PartnerDouble;
import com.worldmapplus.bridge.Partners;
import com.worldmapplus.pins.Pin;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteRunner;
import com.worldmapplus.pins.RouteStore;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;

/** Sections other plugins put in the side panel, pages of their own, and the buttons bound to an action id. */
public class PartnerPanelTest
{
	private PinStore pins;
	private RouteStore routes;
	private ExternalOverlays external;
	private PartnerDouble partner;
	private PlacesPanel panel;

	@Before
	public void setUp()
	{
		pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		external = new ExternalOverlays();
		partner = new PartnerDouble(external, "helper");
		external.partners().attach(null, null, null, pins, routes, (x, y) -> 0);
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
		panel = new PlacesPanel(pins, routes, runner, new MapActions(new EventBus(), external), external, null, id -> "Gielinor");
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

	private static void layout(Container container)
	{
		container.doLayout();
		for (Component child : container.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}

	private <T extends Component> T find(Class<T> type, String text)
	{
		List<Component> all = new ArrayList<>();
		collect(panel, all);
		for (Component component : all)
		{
			if (type.isInstance(component) && (text == null || component instanceof JLabel && ((JLabel) component).getText() != null
				&& ((JLabel) component).getText().contains(text)))
			{
				return type.cast(component);
			}
		}
		return null;
	}

	@Test
	public void aSectionIsPutAtTheFootOfThePlacesPageWithItsTitleAndReplacedNotDuplicated()
	{
		JLabel mine = new JLabel("partner content");
		java.awt.Dimension alone = panel.getPreferredSize();
		partner.register("section", "info", 1, mine, "tab", "places", "title", "Helper tools");
		panel.refreshNow();
		assertTrue("the section takes room", panel.getPreferredSize().height > alone.height);
		assertNotNull(find(JLabel.class, "partner content"));
		assertTrue(mine.isShowing() || mine.getParent() != null);
		JLabel again = new JLabel("partner content v2");
		partner.register("section", "info", 1, again, "tab", "places", "title", "Helper tools");
		panel.refreshNow();
		List<Component> all = new ArrayList<>();
		collect(panel, all);
		int count = 0;
		for (Component component : all)
		{
			count += component == again ? 1 : 0;
			assertFalse("the replaced component is not in the panel", component == mine);
		}
		assertEquals(1, count);
		partner.goodbye();
		panel.refreshNow();
		assertNull(find(JLabel.class, "partner content v2"));
		assertEquals("and with it gone the panel is as it was", alone, panel.getPreferredSize());
	}

	@Test
	public void aNamedTabIsMadeOnDemandAndTheTabBarGrows()
	{
		JLabel mine = new JLabel("on my own page");
		partner.register("section", "own", 1, mine, "tab", "Helper", "title", "Own page");
		panel.refreshNow();
		PanelTheme.Segments tabs = find(PanelTheme.Segments.class, null);
		tabs.setSize(300, 28);
		tabs.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_PRESSED, 0, 0, 250, 10, 1, false));
		assertEquals("a third tab can be chosen", 2, tabs.chosen());
		partner.goodbye();
		panel.refreshNow();
		assertEquals("the tab goes with its sections and the choice falls back", 1, tabs.chosen());
	}

	@Test
	public void aSectionThatFailsWhileItIsDrawnIsSwitchedOff()
	{
		javax.swing.JComponent broken = new javax.swing.JComponent()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				throw new IllegalStateException("partner bug");
			}
		};
		broken.setPreferredSize(new java.awt.Dimension(50, 20));
		partner.register("section", "broken", 1, broken, "tab", "places", "title", "Broken");
		panel.refreshNow();
		panel.setSize(300, 800);
		for (int i = 0; i < 3; i++)
		{
			layout(panel);
		}
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(300, 800, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < 3; i++)
		{
			panel.paint(image.getGraphics());
		}
		assertTrue(external.partners().slots(Partners.Kind.SECTION)[0].isOff());
		panel.refreshNow();
		assertFalse("and it is taken out of the panel", broken.getParent() != null && broken.getParent().isShowing());
	}

	@Test
	public void aButtonBoundToAnActionNobodyHandlesShowsTheInstallMessage()
	{
		JButton button = new JButton("Share ticked");
		panel.bindAction(button, "share_ticked", Collections::emptyMap);
		button.doClick();
		JLabel status = find(JLabel.class, "Install it from the Plugin Hub");
		assertNotNull("the panel says what is missing", status);
		assertTrue(status.getText().contains("World Map Plus Helper"));
		assertTrue(status.isVisible());
	}

	@Test
	public void aButtonBoundToAHandledActionSendsThePayloadAndSaysNothingMissing()
	{
		AtomicReference<Map<String, Object>> got = new AtomicReference<>();
		partner.register("action", "share_ticked", 1, (Consumer<Map<String, Object>>) got::set);
		JButton button = new JButton("Share ticked");
		long id = pins.add("Bank", "pin", 0, 3200.5, 3200.5, 0).getId();
		panel.bindAction(button, "share_ticked", () -> external.partners().payload(Arrays.asList(id), Collections.emptyList()));
		button.doClick();
		assertEquals(1, ((List<?>) got.get().get("pins")).size());
		assertEquals("Bank", ((Map<?, ?>) ((List<?>) got.get().get("pins")).get(0)).get("name"));
		assertTrue(((List<?>) got.get().get("routes")).isEmpty());
		assertNull(find(JLabel.class, "Install it from the Plugin Hub"));
	}

	// ------------------------------------------------------------------ round 56B: the Share and Import buttons and the top of the panel

	private <T extends Component> List<T> all(Class<T> type)
	{
		List<Component> every = new ArrayList<>();
		collect(panel, every);
		List<T> found = new ArrayList<>();
		for (Component component : every)
		{
			if (type.isInstance(component))
			{
				found.add(type.cast(component));
			}
		}
		return found;
	}

	private JButton button(String text)
	{
		for (JButton button : all(JButton.class))
		{
			if (text.equals(button.getText()))
			{
				return button;
			}
		}
		return null;
	}

	@Test
	public void aSectionOfTheTopTabSitsAboveTheTabsWithNoHeaderOfThePanelsOwn()
	{
		JLabel mine = new JLabel("top content");
		java.awt.Dimension alone = panel.getPreferredSize();
		partner.register("section", "box", 1, mine, "tab", "top", "title", "Never shown");
		panel.refreshNow();
		assertTrue(panel.getPreferredSize().height > alone.height);
		assertNotNull(find(JLabel.class, "top content"));
		java.awt.Container box = mine.getParent();
		assertEquals("the box holds the section and no header of the panel's", 1, box.getComponentCount());
		PanelTheme.Segments tabs = find(PanelTheme.Segments.class, null);
		java.awt.Container header = tabs.getParent();
		assertTrue(javax.swing.SwingUtilities.isDescendingFrom(mine, header));
		java.awt.Component branch = box;
		while (branch.getParent() != header)
		{
			branch = branch.getParent();
		}
		assertTrue("above the tabs", header.getComponentZOrder(branch) < header.getComponentZOrder(tabs));
		partner.goodbye();
		panel.refreshNow();
		assertEquals("with it gone the panel is as it was", alone, panel.getPreferredSize());
	}

	@Test
	public void shareTickedSendsTheTickedPlacesInTheOrderTheyWereTicked()
	{
		AtomicReference<Map<String, Object>> got = new AtomicReference<>();
		partner.register("action", Partners.ACTION_SHARE_PLACES, 1, (Consumer<Map<String, Object>>) got::set);
		long a = pins.add("Bank", "pin", 0, 3200.5, 3200.5, 0).getId();
		long b = pins.add("Shop", "pin", 0, 3210.5, 3210.5, 0).getId();
		panel.onActivate();
		panel.refreshNow();
		JButton share = button("Share ticked");
		assertNotNull(share);
		assertFalse("nothing is ticked, so it is off", share.isEnabled());
		// Ticked in this order, whatever order the list shows them in.
		for (String name : new String[] {"Shop", "Bank"})
		{
			for (javax.swing.JCheckBox box : all(javax.swing.JCheckBox.class))
			{
				if (name.equals(box.getText()))
				{
					box.doClick();
					break;
				}
			}
		}
		share = button("Share 2 ticked");
		assertNotNull(share);
		assertTrue(share.isEnabled());
		share.doClick();
		assertEquals(Arrays.asList(b, a), got.get().get("order"));
		assertEquals(2, ((List<?>) got.get().get("pins")).size());
		assertNull(find(JLabel.class, "Install it from the Plugin Hub"));
		panel.shutDown();
	}

	@Test
	public void aRoutesShareButtonSendsItsStopsAndTheRouteAndImportACodeSendsItsAction()
	{
		AtomicReference<Map<String, Object>> shared = new AtomicReference<>();
		AtomicReference<Map<String, Object>> imported = new AtomicReference<>();
		partner.register("action", Partners.ACTION_SHARE_ROUTE, 1, (Consumer<Map<String, Object>>) shared::set);
		partner.register("action", Partners.ACTION_IMPORT, 1, (Consumer<Map<String, Object>>) imported::set);
		long a = pins.add("Bank", "pin", 0, 3200.5, 3200.5, 0).getId();
		long b = pins.add("Shop", "pin", 0, 3210.5, 3210.5, 0).getId();
		routes.create("Run", Arrays.asList(a, b, a), true);
		panel.onActivate();
		panel.selectRoutes();
		panel.refreshNow();
		JButton share = button("Share");
		assertNotNull(share);
		share.doClick();
		Map<?, ?> route = (Map<?, ?>) ((List<?>) shared.get().get("routes")).get(0);
		assertEquals(Arrays.asList(a, b, a), route.get("stops"));
		assertEquals(true, route.get("loop"));
		assertEquals("the stops' places come with it", 2, ((List<?>) shared.get().get("pins")).size());
		JButton importCode = button("Import a code");
		assertNotNull(importCode);
		importCode.doClick();
		assertNotNull(imported.get());
		assertNull(find(JLabel.class, "Install it from the Plugin Hub"));
		panel.shutDown();
	}

	@Test
	public void withNoHelperEveryShareAndImportButtonSaysWhatIsMissing()
	{
		long a = pins.add("Bank", "pin", 0, 3200.5, 3200.5, 0).getId();
		routes.create("Run", Arrays.asList(a), false);
		panel.onActivate();
		panel.refreshNow();
		button("Import a code").doClick();
		assertNotNull(find(JLabel.class, "World Map Plus Helper"));
		panel.selectRoutes();
		panel.refreshNow();
		button("Share").doClick();
		assertNotNull(find(JLabel.class, "Install it from the Plugin Hub"));
		panel.shutDown();
	}

	@Test
	public void aPartnersStatusLineShowsAtTheTopOfThePanel()
	{
		external.partners().connect(null, panel::showStatus, null, null, null);
		partner.hello("part two");
		partner.state("status", "text", "Copied.");
		assertNotNull(find(JLabel.class, "Copied."));
	}

	@Test
	public void theOneTimeNoticeShowsInThePanelOnlyWhenAFeatureNeedsPartTwoAndItIsMissing()
	{
		panel.refreshNow();
		assertNull(find(JLabel.class, "Install it from the Plugin Hub"));
		external.partners().setNoticeGrace(0);
		external.partners().expectHelper();
		panel.refreshNow();
		assertNotNull(find(JLabel.class, "Install it from the Plugin Hub"));
	}
}
