package com.worldmapplus.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Receiving codes: listed once, parts joined by channel and sender in any order, everything bounded. */
public class ShareInboxTest
{
	private static Share longRoute(int seed)
	{
		List<SharePoint> stops = new ArrayList<>();
		java.util.Random random = new java.util.Random(seed);
		for (int i = 0; i < 28; i++)
		{
			stops.add(new SharePoint(960 + random.nextInt(3072), 2048 + random.nextInt(2176), 0));
		}
		Share route = Share.route(stops, false);
		try
		{
			assertEquals("three messages", 3, ShareCodec.messages(route).size());
		}
		catch (ShareException e)
		{
			throw new AssertionError(e);
		}
		return route;
	}

	private static List<String> codes(Share share) throws ShareException
	{
		List<String> codes = new ArrayList<>();
		for (String message : ShareCodec.messages(share))
		{
			codes.add(ShareCodec.codeIn(message));
		}
		return codes;
	}

	@Test
	public void aPinIsListedOnceAndNothingIsSaved() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		ShareInbox.Reaction first = inbox.receive("p10kgkm86a", "PUBLICCHAT|bob", 1);
		assertEquals("World Map Plus pin at 1249, 3720", first.getLabel());
		inbox.receive("p10kgkm86a", "PUBLICCHAT|ann", 2);
		assertEquals(1, inbox.offers().size());
		assertTrue(inbox.receive("p10kgkm86b", "PUBLICCHAT|bob", 3).isUnreadable());
		assertEquals(1, inbox.offers().size());
	}

	@Test
	public void partsJoinInAnyOrderWithRepeats() throws Exception
	{
		Share route = longRoute(1);
		List<String> parts = codes(route);
		assertEquals(3, parts.size());
		ShareInbox inbox = new ShareInbox();
		ShareInbox.Reaction third = inbox.receive(parts.get(2), "CLAN_CHAT|bob", 1, "line a", true);
		assertEquals("World Map Plus: part 3 of 3 received, waiting for 2 more parts", third.getLabel());
		assertTrue(third.isWaiting());
		assertTrue(inbox.receive(parts.get(2), "CLAN_CHAT|bob", 2, "line b", true).getLabel().contains("waiting for 2 more parts"));
		assertNull("not open before it is whole", inbox.shareOfPart(parts.get(2)));
		assertTrue(inbox.receive(parts.get(0), "CLAN_CHAT|bob", 3, "line c", true).getLabel().endsWith("waiting for 1 more part"));
		assertEquals(0, inbox.offers().size());
		ShareInbox.Reaction done = inbox.receive(parts.get(1), "CLAN_CHAT|bob", 4, "line d", true);
		assertEquals("the label a single message would have", "World Map Plus route of 28 stops", done.getLabel());
		assertFalse(done.isWaiting());
		assertEquals("the earlier parts' lines, to relabel", java.util.Arrays.asList("line a", "line b", "line c"),
			done.getEarlierLines());
		assertEquals(route, inbox.offers().get(0).getShare());
		for (String part : parts)
		{
			assertEquals("every part opens the whole route", route, inbox.shareOfPart(part));
		}
	}

	@Test
	public void thePlayersOwnCodesAreJoinedAndLabelledButNotListed() throws Exception
	{
		List<String> parts = codes(longRoute(7));
		ShareInbox inbox = new ShareInbox();
		inbox.receive("p10kgkm86a", "PUBLICCHAT|*", 1, null, false);
		for (String part : parts)
		{
			inbox.receive(part, "PUBLICCHAT|*", 2, part, false);
		}
		assertEquals(0, inbox.offers().size());
		assertEquals(longRoute(7), inbox.shareOfPart(parts.get(0)));
	}

	@Test
	public void theLinesKeptForOneCodeAreBounded() throws Exception
	{
		List<String> parts = codes(longRoute(8));
		ShareInbox inbox = new ShareInbox();
		for (int i = 0; i < 100; i++)
		{
			inbox.receive(parts.get(0), "PUBLICCHAT|bob", 1, i, true);
		}
		inbox.receive(parts.get(1), "PUBLICCHAT|bob", 2, "b", true);
		ShareInbox.Reaction done = inbox.receive(parts.get(2), "PUBLICCHAT|bob", 3, "c", true);
		assertEquals(ShareInbox.MAX_LINES_PER_CODE, done.getEarlierLines().size());
		assertEquals("the newest are kept", "b", done.getEarlierLines().get(ShareInbox.MAX_LINES_PER_CODE - 1));
	}

	@Test
	public void partsFromAnotherSenderOrChannelAreKeptApart() throws Exception
	{
		List<String> parts = codes(longRoute(2));
		ShareInbox inbox = new ShareInbox();
		inbox.receive(parts.get(0), "CLAN_CHAT|bob", 1);
		inbox.receive(parts.get(1), "CLAN_CHAT|ann", 2);
		inbox.receive(parts.get(2), "PUBLICCHAT|bob", 3);
		assertEquals(0, inbox.offers().size());
		inbox.receive(parts.get(1), "CLAN_CHAT|bob", 4);
		inbox.receive(parts.get(2), "CLAN_CHAT|bob", 5);
		assertEquals(1, inbox.offers().size());
	}

	@Test
	public void twoRoutesSentAtOnceByOnePlayerBothArrive() throws Exception
	{
		List<String> a = codes(longRoute(3));
		List<String> b = codes(longRoute(4));
		ShareInbox inbox = new ShareInbox();
		for (int i = 0; i < 3; i++)
		{
			inbox.receive(a.get(i), "FRIENDSCHAT|bob", i);
			inbox.receive(b.get(2 - i), "FRIENDSCHAT|bob", i);
		}
		assertEquals(2, inbox.offers().size());
	}

	@Test
	public void aMistypedPartIsReportedAndACorrectedOneReplacesIt() throws Exception
	{
		List<String> parts = codes(longRoute(5));
		String bad = parts.get(1).substring(0, 20) + (parts.get(1).charAt(20) == 'a' ? 'b' : 'a') + parts.get(1).substring(21);
		ShareInbox inbox = new ShareInbox();
		inbox.receive(parts.get(0), "PUBLICCHAT|bob", 1);
		inbox.receive(bad, "PUBLICCHAT|bob", 2);
		ShareInbox.Reaction wrong = inbox.receive(parts.get(2), "PUBLICCHAT|bob", 3);
		assertTrue(wrong.isUnreadable());
		assertEquals(0, inbox.offers().size());
		// Sending all three again with the corrected part works.
		inbox.receive(parts.get(0), "PUBLICCHAT|bob", 4);
		inbox.receive(bad, "PUBLICCHAT|bob", 5);
		inbox.receive(parts.get(1), "PUBLICCHAT|bob", 6);
		inbox.receive(parts.get(2), "PUBLICCHAT|bob", 7);
		assertEquals(1, inbox.offers().size());
	}

	@Test
	public void waitingPartsExpireAndAreBounded() throws Exception
	{
		List<String> parts = codes(longRoute(6));
		ShareInbox inbox = new ShareInbox();
		inbox.receive(parts.get(0), "PUBLICCHAT|bob", 0);
		inbox.receive(parts.get(1), "PUBLICCHAT|bob", ShareInbox.PENDING_MILLIS / 2);
		inbox.receive(parts.get(2), "PUBLICCHAT|bob", ShareInbox.PENDING_MILLIS + 1);
		assertEquals("the first parts had gone", 0, inbox.offers().size());
		// Many senders' first parts: only the newest few wait, and the oldest is dropped.
		for (int i = 0; i < ShareInbox.MAX_PENDING + 5; i++)
		{
			inbox.receive(parts.get(0), "PUBLICCHAT|player" + i, 10);
		}
		inbox.receive(parts.get(1), "PUBLICCHAT|player0", 11);
		inbox.receive(parts.get(2), "PUBLICCHAT|player0", 12);
		assertEquals(0, inbox.offers().size());
		String last = "PUBLICCHAT|player" + (ShareInbox.MAX_PENDING + 4);
		inbox.receive(parts.get(1), last, 13);
		inbox.receive(parts.get(2), last, 14);
		assertEquals(1, inbox.offers().size());
	}

	@Test
	public void theListIsBounded() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		for (int i = 0; i < ShareInbox.MAX_OFFERS + 5; i++)
		{
			inbox.receive(ShareCodec.encode(Share.pin(new SharePoint(3200 + i, 3200, 0))), "PUBLICCHAT|bob", i);
		}
		assertEquals(ShareInbox.MAX_OFFERS, inbox.offers().size());
		assertEquals(new SharePoint(3200 + ShareInbox.MAX_OFFERS + 4, 3200, 0), inbox.offers().get(ShareInbox.MAX_OFFERS - 1)
			.getShare().getPoints().get(0));
	}

	@Test
	public void pastedTextIsReadTheSameWay() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		assertEquals("Added the shared pin at 1249, 3720 to Shared with you. Save keeps it.", inbox.receivePasted("!wmp p10kgkm86a", 1));
		assertEquals("That is already in Shared with you.", inbox.receivePasted("p10kgkm86a", 2));
		List<String> messages = ShareCodec.messages(longRoute(7));
		assertEquals("Part 1 of 3 added, paste the other parts (parts 2 and 3 of 3 are missing).", inbox.receivePasted(messages.get(0), 3));
		assertEquals("Parts 1 and 3 of 3 added, paste the next part (part 2 of 3 is missing).", inbox.receivePasted(messages.get(2), 4));
		assertEquals("Added the shared route of 28 stops to Shared with you. Save keeps it.", inbox.receivePasted(messages.get(1), 5));
		assertTrue(inbox.receivePasted("wmp0bcdfghjklmnpq_rstvwxyz", 5).contains("older version"));
		assertTrue(inbox.receivePasted("hello there", 6).contains("does not look like a code"));
		assertEquals("Checksum mismatch near character 10, check what you typed.", inbox.receivePasted("!wmp p10kgkm86b", 7));
		assertEquals(2, inbox.offers().size());
	}

	@Test
	public void aPasteImportsEveryCodeInItAndSavesNothing() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		List<String> route = ShareCodec.messages(longRoute(3));
		String text = "hey, here are my spots:\n> `!wmp p10kgkm86a`\n\"P11JB34J2V\" and the herb run **" + route.get(2) + "** "
			+ route.get(0).toUpperCase() + "\n" + route.get(1).substring(5) + " thanks!";
		ShareInbox.Pasted pasted = inbox.importPasted(text, 1);
		assertEquals("Added 3 shares to Shared with you. Save keeps each.", pasted.getMessage());
		assertEquals(3, pasted.getAdded());
		assertFalse(pasted.hasProblem());
		assertEquals(3, inbox.offers().size());
		assertEquals("pasting again adds nothing", 0, inbox.importPasted(text, 2).getAdded());
	}

	@Test
	public void aPastedErrorSaysWhatIsWrong() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		// The first character after p1 typed wrong: the one place where a change makes it read is named.
		assertEquals("The code does not read: likely mistyped near character 3, check what you typed.",
			inbox.importPasted("!wmp p1zkgkm86a", 1).getMessage());
		assertTrue(inbox.importPasted("p2abcdefghjk", 1).getMessage().contains("newer version"));
		assertTrue(inbox.importPasted("p1abcdefuhjk", 1).getMessage().contains("(u) at character 9"));
		List<String> messages = ShareCodec.messages(longRoute(7));
		String broken = messages.get(1).substring(0, 30) + (messages.get(1).charAt(30) == 'a' ? 'b' : 'a') + messages.get(1).substring(31);
		ShareInbox.Pasted bad = inbox.importPasted(messages.get(0) + "\n" + broken + "\n" + messages.get(2), 2);
		assertTrue(bad.getMessage(), bad.getMessage().contains("The 3 parts do not fit together"));
		assertTrue(bad.hasProblem());
		assertEquals(0, inbox.offers().size());
	}

	@Test
	public void openingMarksAShareAndRemovingForgetsIt() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		Share pin = Share.pin(new SharePoint(1249, 3720, 0));
		ShareInbox.Offer offer = inbox.open(pin, 1);
		assertEquals(offer.getId(), inbox.getOpened());
		assertEquals(Collections.singletonList(offer), inbox.offers());
		assertEquals(offer, inbox.open(pin, 2));
		inbox.remove(offer.getId());
		assertEquals(-1, inbox.getOpened());
		assertFalse(inbox.offers().contains(offer));
	}
}
