package com.worldmapplus.panel;

import com.google.gson.Gson;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.share.Share;
import com.worldmapplus.share.SharePoint;
import com.worldmapplus.typing.TypingGuide;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.PluginPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The share box: whole codes in groups that fit the side bar, a state for each message, and the typing guide. */
public class ShareBoxTest
{
	/** A route of 30 points scattered over the surface, long enough to need several messages. */
	private static Share longRoute()
	{
		Random random = new Random(7);
		List<SharePoint> points = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			points.add(new SharePoint(960 + random.nextInt(3072), 2048 + random.nextInt(2176), 0));
		}
		return Share.route(points, false);
	}

	@Test
	public void aCodeIsGroupedInFoursAndWrapsAtGroupEdgesWithoutChangingIt()
	{
		String message = "!wmp c1322rlegbhgv362fd79sgah1pqc2y0fs71h141er6r0wm62sm66zgf7s9zc6m2eeb1x5j3";
		CodeView view = new CodeView(message);
		view.setBounds(0, 0, 183, 100);
		assertEquals(message, view.getMessage());
		assertTrue("a 76 character message takes several lines at the side bar's width", view.rows() >= 3);
		assertTrue("and not too many", view.rows() <= 5);
		// Letters of one group are always on one line.
		for (int start = 5; start + 3 < message.length(); start += CodeView.GROUP)
		{
			assertEquals(view.rowOf(start), view.rowOf(start + 3));
		}
		assertEquals("the command and its space stay on the first line", 0, view.rowOf(4));
		CodeView pin = new CodeView("!wmp p10kgkm86a");
		pin.setBounds(0, 0, 183, 40);
		assertEquals("a pin code is one line", 1, pin.rows());
	}

	@Test
	public void theShareBoxShowsEveryMessageAndFollowsTheGuide() throws Exception
	{
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		ExternalOverlays external = new ExternalOverlays();
		PlacesPanel panel = new PlacesPanel(pins, routes, null, new MapActions(new EventBus(), external), external, null,
			id -> "Gielinor");
		TypingGuide guide = new TypingGuide();
		boolean[] on = {true};
		panel.setTypingGuide(guide, () -> on[0]);
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.openShare(longRoute()));
		List<String> messages = panel.shownMessages();
		assertEquals(3, messages.size());
		assertEquals(3, panel.shownCodes().size());
		for (int i = 0; i < 3; i++)
		{
			assertEquals("the whole message, unchanged", messages.get(i), panel.shownCodes().get(i).getMessage());
		}
		assertTrue("Share starts the guide on the first message", guide.isActive());
		assertEquals(0, guide.current());
		assertTrue(panel.shownStates().get(0).endsWith("typing"));
		assertTrue(panel.shownStates().get(1).endsWith("not typed"));

		guide.update(guide.generation(), messages.get(0));
		guide.update(guide.generation(), "");
		javax.swing.SwingUtilities.invokeAndWait(panel::refreshNow);
		assertTrue(panel.shownStates().get(0), panel.shownStates().get(0).endsWith("done"));
		assertTrue(panel.shownStates().get(1).endsWith("typing"));

		// The card's Stop, on the interface thread of the game: the panel follows.
		guide.stop();
		javax.swing.SwingUtilities.invokeAndWait(panel::refreshNow);
		assertFalse(guide.isActive());
		assertEquals(3, panel.shownCodes().size());

		// With the setting off, Share shows the codes and starts no guide.
		on[0] = false;
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.openShare(Share.pin(new SharePoint(1249, 3720, 0))));
		assertFalse(guide.isActive());
		assertEquals(1, panel.shownCodes().size());
		assertEquals("!wmp p10kgkm86a", panel.shownCodes().get(0).getMessage());
		panel.setSize(PluginPanel.PANEL_WIDTH, 800);
		panel.shutDown();
	}
}
