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
		ShareInbox.Reaction first = inbox.receive("p10kgkm86a", "PUBLICCHAT|bob", 1, null, true);
		assertEquals(Share.pin(new SharePoint(1249, 3720, 0)), first.getShare());
		inbox.receive("p10kgkm86a", "PUBLICCHAT|ann", 2, null, true);
		assertEquals(1, inbox.offers().size());
		assertTrue(inbox.receive("p10kgkm86b", "PUBLICCHAT|bob", 3, null, true).isUnreadable());
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
		assertEquals("World Map Plus: part 3 of 3 received, waiting for 2 more parts", ChatText.waiting(third));
		assertTrue(third.isWaiting());
		assertEquals(1, inbox.receive(parts.get(2), "CLAN_CHAT|bob", 2, "line b", true).getHave());
		assertNull("not open before it is whole", inbox.shareOfPart(parts.get(2)));
		assertTrue(ChatText.waiting(inbox.receive(parts.get(0), "CLAN_CHAT|bob", 3, "line c", true)).endsWith("waiting for the next part"));
		assertEquals(0, inbox.offers().size());
		ShareInbox.Reaction done = inbox.receive(parts.get(1), "CLAN_CHAT|bob", 4, "line d", true);
		assertEquals("the share a single message would give", route, done.getShare());
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
		inbox.receive(parts.get(0), "CLAN_CHAT|bob", 1, null, true);
		inbox.receive(parts.get(1), "CLAN_CHAT|ann", 2, null, true);
		inbox.receive(parts.get(2), "PUBLICCHAT|bob", 3, null, true);
		assertEquals(0, inbox.offers().size());
		inbox.receive(parts.get(1), "CLAN_CHAT|bob", 4, null, true);
		inbox.receive(parts.get(2), "CLAN_CHAT|bob", 5, null, true);
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
			inbox.receive(a.get(i), "FRIENDSCHAT|bob", i, null, true);
			inbox.receive(b.get(2 - i), "FRIENDSCHAT|bob", i, null, true);
		}
		assertEquals(2, inbox.offers().size());
	}

	@Test
	public void aMistypedPartIsReportedAndACorrectedOneReplacesIt() throws Exception
	{
		List<String> parts = codes(longRoute(5));
		String bad = parts.get(1).substring(0, 20) + (parts.get(1).charAt(20) == 'a' ? 'b' : 'a') + parts.get(1).substring(21);
		ShareInbox inbox = new ShareInbox();
		inbox.receive(parts.get(0), "PUBLICCHAT|bob", 1, null, true);
		inbox.receive(bad, "PUBLICCHAT|bob", 2, null, true);
		ShareInbox.Reaction wrong = inbox.receive(parts.get(2), "PUBLICCHAT|bob", 3, null, true);
		assertTrue(wrong.isUnreadable());
		assertEquals(0, inbox.offers().size());
		// Sending all three again with the corrected part works.
		inbox.receive(parts.get(0), "PUBLICCHAT|bob", 4, null, true);
		inbox.receive(bad, "PUBLICCHAT|bob", 5, null, true);
		inbox.receive(parts.get(1), "PUBLICCHAT|bob", 6, null, true);
		inbox.receive(parts.get(2), "PUBLICCHAT|bob", 7, null, true);
		assertEquals(1, inbox.offers().size());
	}

	@Test
	public void waitingPartsExpireAndAreBounded() throws Exception
	{
		List<String> parts = codes(longRoute(6));
		ShareInbox inbox = new ShareInbox();
		inbox.receive(parts.get(0), "PUBLICCHAT|bob", 0, null, true);
		inbox.receive(parts.get(1), "PUBLICCHAT|bob", ShareInbox.PENDING_MILLIS / 2, null, true);
		inbox.receive(parts.get(2), "PUBLICCHAT|bob", ShareInbox.PENDING_MILLIS + 1, null, true);
		assertEquals("the first parts had gone", 0, inbox.offers().size());
		// Many senders' first parts: only the newest few wait, and the oldest is dropped.
		for (int i = 0; i < ShareInbox.MAX_PENDING + 5; i++)
		{
			inbox.receive(parts.get(0), "PUBLICCHAT|player" + i, 10, null, true);
		}
		inbox.receive(parts.get(1), "PUBLICCHAT|player0", 11, null, true);
		inbox.receive(parts.get(2), "PUBLICCHAT|player0", 12, null, true);
		assertEquals(0, inbox.offers().size());
		String last = "PUBLICCHAT|player" + (ShareInbox.MAX_PENDING + 4);
		inbox.receive(parts.get(1), last, 13, null, true);
		inbox.receive(parts.get(2), last, 14, null, true);
		assertEquals(1, inbox.offers().size());
	}

	@Test
	public void theListIsBounded() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		for (int i = 0; i < ShareInbox.MAX_OFFERS + 5; i++)
		{
			inbox.receive(ShareCodec.encode(Share.pin(new SharePoint(3200 + i, 3200, 0))), "PUBLICCHAT|bob", i, null, true);
		}
		assertEquals(ShareInbox.MAX_OFFERS, inbox.offers().size());
		assertEquals(new SharePoint(3200 + ShareInbox.MAX_OFFERS + 4, 3200, 0), inbox.offers().get(ShareInbox.MAX_OFFERS - 1)
			.getShare().getPoints().get(0));
	}

	@Test
	public void pastedTextIsReadTheSameWay() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		assertEquals("Added 1 share to Shared with you. Save keeps it.", pasted(inbox, "!wmp p10kgkm86a", 1));
		assertEquals("a typed line in capitals reads too", "That is already in Shared with you.", pasted(inbox, "  !WMP P10KGKM86A ", 2));
		List<String> messages = ShareCodec.messages(longRoute(7));
		assertEquals("1 of 3 parts added, paste the rest.", pasted(inbox, messages.get(0), 3));
		assertEquals("2 of 3 parts added, paste the rest.", pasted(inbox, messages.get(2), 4));
		assertEquals("Added 1 share to Shared with you. Save keeps it.", pasted(inbox, messages.get(1), 5));
		assertTrue(pasted(inbox, "hello there", 6).startsWith("No code found."));
		assertTrue("since round 76 a code without the command is not read", pasted(inbox, "p10kgkm86a", 6).startsWith("No code found."));
		assertEquals("Checksum mismatch: a character is mistyped, missing or extra. Check what you typed.", pasted(inbox, "!wmp p10kgkm86b", 7));
		assertEquals(2, inbox.offers().size());
	}

	private static String pasted(ShareInbox inbox, String text, long now)
	{
		return inbox.importPasted(text, now).getMessage();
	}

	@Test
	public void aPasteImportsEveryCopiedLineInItAndSavesNothing() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		List<String> route = ShareCodec.messages(longRoute(3));
		// Copied lines, Windows line ends, the parts in another order, a blank line, and a code inside other words (not read).
		String text = "!wmp p10kgkm86a\r\n\r\n" + route.get(2) + "\r\n" + route.get(0) + "\nhere is mine: !wmp p11jb34j2v\n" + route.get(1);
		ShareInbox.Pasted pasted = inbox.importPasted(text, 1);
		assertEquals("Added 2 shares to Shared with you. Save keeps each.", pasted.getMessage());
		assertEquals(2, pasted.getAdded());
		assertFalse(pasted.isProblem());
		assertEquals(2, inbox.offers().size());
		assertEquals("pasting again adds nothing", 0, inbox.importPasted(text, 2).getAdded());
		assertEquals("the whole code as Copy whole code gives it", 1, new ShareInbox().importPasted(ShareCodec.COMMAND + " "
			+ ShareCodec.encode(longRoute(3)), 3).getAdded());
	}

	@Test
	public void aPastedErrorSaysWhatIsWrong() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		assertTrue(inbox.importPasted("!wmp p1zkgkm86a", 1).getMessage().endsWith("Check what you typed."));
		assertTrue(inbox.importPasted("!wmp p2abcdefghjk", 1).getMessage().contains("newer version"));
		assertTrue(inbox.importPasted("!wmp p1abcdefuhjk", 1).getMessage().contains("not used in codes: u"));
		List<String> messages = ShareCodec.messages(longRoute(7));
		String broken = messages.get(1).substring(0, 30) + (messages.get(1).charAt(30) == 'a' ? 'b' : 'a') + messages.get(1).substring(31);
		ShareInbox.Pasted bad = inbox.importPasted(messages.get(0) + "\n" + broken + "\n" + messages.get(2), 2);
		assertTrue(bad.getMessage(), bad.getMessage().contains("The 3 parts do not fit together"));
		assertTrue(bad.isProblem());
		assertEquals(0, inbox.offers().size());
	}

	@Test
	public void openingMarksAShareAndRemovingForgetsIt() throws Exception
	{
		ShareInbox inbox = new ShareInbox();
		Share pin = Share.pin(new SharePoint(1249, 3720, 0));
		ShareInbox.Offer offer = inbox.open(pin);
		assertEquals(offer.getId(), inbox.getOpened());
		assertEquals(Collections.singletonList(offer), inbox.offers());
		assertEquals(offer, inbox.open(pin));
		inbox.remove(offer.getId());
		assertEquals(-1, inbox.getOpened());
		assertFalse(inbox.offers().contains(offer));
	}
}
