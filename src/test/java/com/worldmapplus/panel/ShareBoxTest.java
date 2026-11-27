package com.worldmapplus.panel;

import com.google.gson.Gson;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.share.Share;
import com.worldmapplus.share.SharePoint;
import com.worldmapplus.typing.TypingGuide;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.PluginPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The share box: whole codes in groups of four, a state for each message, and the typing guide. */
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
	public void aCodeIsGroupedInFoursWithoutChangingIt()
	{
		String message = "!wmp c1322rlegbhgv362fd79sgah1pqc2y0fs71h141er6r0wm62sm66zgf7s9zc6m2eeb1x5j3";
		JLabel code = (JLabel) ((Container) ShareBoxes.codeCard(message)).getComponent(0);
		assertEquals("the tooltip is the exact message", message, code.getToolTipText());
		String shown = code.getText().replaceAll("<[^>]*>", "").trim();
		assertTrue(shown, shown.startsWith("!wmp c132 2rle gbhg v362 "));
		assertEquals("only spaces between groups are added", message.replace(" ", ""), shown.replace(" ", ""));
		for (String group : shown.substring(5).split(" "))
		{
			assertTrue(group, group.length() <= 4);
		}
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
		SwingUtilities.invokeAndWait(() -> panel.shares.show(longRoute()));
		List<String> messages = panel.shares.shownMessages();
		assertEquals(3, messages.size());
		assertEquals("every message is shown whole", messages, codes(panel.shares.shareBody));
		assertTrue("Share starts the guide on the first message", guide.isActive());
		assertEquals(0, guide.current());
		List<String> states = texts(panel.shares.shareBody, " chars");
		assertTrue(states.get(0), states.get(0).endsWith("typing"));
		assertTrue(states.get(1).endsWith("not typed"));
		assertEquals(1, texts(panel.shares.shareBody, "Stop typing guide").size());

		guide.update(guide.generation(), messages.get(0));
		guide.update(guide.generation(), "");
		SwingUtilities.invokeAndWait(panel::refreshNow);
		states = texts(panel.shares.shareBody, " chars");
		assertTrue(states.get(0), states.get(0).endsWith("done"));
		assertTrue(states.get(1).endsWith("typing"));
		guide.update(guide.generation(), messages.get(1).substring(0, 9) + "#");
		SwingUtilities.invokeAndWait(panel::refreshNow);
		assertEquals(texts(panel.shares.shareBody, "Guide").toString(), 1, texts(panel.shares.shareBody, "Guide on message 2 of 3: typo").size());

		// The card's Stop, on the interface thread of the game: the panel follows.
		guide.stop();
		SwingUtilities.invokeAndWait(panel::refreshNow);
		assertFalse(guide.isActive());
		assertEquals(1, texts(panel.shares.shareBody, "Start typing guide").size());
		assertEquals(3, codes(panel.shares.shareBody).size());

		// With the setting off, Share shows the codes and starts no guide.
		on[0] = false;
		SwingUtilities.invokeAndWait(() -> panel.shares.show(Share.pin(new SharePoint(1249, 3720, 0))));
		assertFalse(guide.isActive());
		assertEquals(Collections.singletonList("!wmp p10kgkm86a"), codes(panel.shares.shareBody));
		assertEquals(0, texts(panel.shares.shareBody, "typing guide").size());
		panel.setSize(PluginPanel.PANEL_WIDTH, 800);
		panel.shutDown();
	}

	/** The texts of the labels and buttons in a part of the panel that hold some words, in order. */
	private static List<String> texts(Component part, String containing)
	{
		List<String> found = new ArrayList<>();
		walk(part, found, c -> c instanceof JLabel ? ((JLabel) c).getText() : c instanceof AbstractButton ? ((AbstractButton) c).getText()
			: null, containing);
		return found;
	}

	/** The messages the code cards stand for: the tooltips that are codes. */
	private static List<String> codes(Component part)
	{
		List<String> found = new ArrayList<>();
		walk(part, found, c -> c instanceof JLabel && ((JLabel) c).getToolTipText() != null
			&& ((JLabel) c).getToolTipText().startsWith("!wmp ") ? ((JLabel) c).getToolTipText() : null, "");
		return found;
	}

	private static void walk(Component part, List<String> found, Function<Component, String> take, String containing)
	{
		String text = take.apply(part);
		if (text != null && text.contains(containing))
		{
			found.add(text);
		}
		if (part instanceof Container)
		{
			for (Component child : ((Container) part).getComponents())
			{
				walk(child, found, take, containing);
			}
		}
	}
}
