package com.worldmapplus.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.Test;

/**
 * The prompt's lifetime: opened and closed on the client thread only, closed only while it is still this plugin's own, and
 * reported to the map from whichever thread the chat area uses.
 */
public class ChatSearchTest
{
	private final FakePrompts prompts = new FakePrompts();

	/** Stands in for the client thread: work waits here until the test lets the client thread run. */
	private final Queue<Runnable> clientThread = new ArrayDeque<>();

	private final ChatSearch search = new ChatSearch(prompts, clientThread::add);

	private void clientThreadRuns()
	{
		while (!clientThread.isEmpty())
		{
			clientThread.remove().run();
		}
	}

	@Test
	public void nothingTouchesTheChatAreaUntilTheClientThreadRuns()
	{
		ChatSearch.Session session = search.open("Search the map", "bob");
		assertEquals("asking from another thread only queues the work", 0, prompts.calls());
		clientThreadRuns();
		assertEquals(1, prompts.opens);
		assertEquals("Search the map", prompts.title());
		assertEquals("bob", prompts.initialTexts.get(0));
		search.close(session);
		assertEquals(0, prompts.closes);
		clientThreadRuns();
		assertEquals(1, prompts.closes);
		assertFalse(prompts.isOpen());
	}

	@Test
	public void aCloseAskedBeforeTheOpenHasRunStillComesAfterIt()
	{
		ChatSearch.Session session = search.open("Search the map", "");
		search.close(session);
		clientThreadRuns();
		assertEquals(1, prompts.opens);
		assertEquals(1, prompts.closes);
		assertFalse("the order was kept, so nothing is left open", prompts.isOpen());
		assertTrue(session.isClosed());
	}

	@Test
	public void typingIsHandedOverFromAnyThread() throws Exception
	{
		ChatSearch.Session session = search.open("Search the map", "");
		clientThreadRuns();
		Thread awt = new Thread(() -> prompts.type("iron arrow"));
		awt.start();
		awt.join();
		assertEquals("only the latest text is kept", "iron arrow", session.takeText());
		assertNull("and it is handed over once", session.takeText());
		assertFalse(session.takeDone());
		assertFalse(session.isClosed());
	}

	@Test
	public void enterGivesTheTextThenDoneThenTheCloseAndEscapeJustCloses()
	{
		ChatSearch.Session first = search.open("Search the map", "");
		clientThreadRuns();
		prompts.type("bank");
		prompts.enter();
		assertEquals("bank", first.takeText());
		assertTrue(first.takeDone());
		assertFalse("done is reported once", first.takeDone());
		assertTrue(first.isClosed());
		assertEquals("the chat area closed itself, nothing more is asked of it", 0, prompts.closes);

		ChatSearch.Session second = search.open("Search the map", "bank");
		clientThreadRuns();
		prompts.escape();
		assertTrue(second.isClosed());
		assertFalse(second.takeDone());
		assertEquals(0, prompts.closes);
	}

	@Test
	public void aPromptSomeoneElseOpenedIsNeverClosedFromHere()
	{
		ChatSearch.Session session = search.open("Search the map", "");
		clientThreadRuns();
		prompts.foreignPromptOpens();
		assertTrue("told that the chat area moved on", session.isClosed());

		search.close(session);
		search.closeAll();
		clientThreadRuns();
		assertEquals("no close was asked for", 0, prompts.closes);
		assertTrue("the other plugin's prompt is still there", prompts.isOpen());
		assertEquals("Someone else", prompts.title());
	}

	@Test
	public void aCloseThatReachesTheChatAreaAfterAnotherPluginOpenedItsPromptLeavesThatPrompt()
	{
		search.open("Search the map", "");
		clientThreadRuns();
		prompts.foreignPromptOpens();
		prompts.close();
		assertEquals("the call was made", 1, prompts.closes);
		assertTrue("and the other plugin's prompt is untouched", prompts.isOpen());
		assertEquals("Someone else", prompts.title());
	}

		@Test
	public void aSecondOpenEndsTheFirstAndTheFirstCannotCloseTheSecond()
	{
		ChatSearch.Session first = search.open("Search the map", "a");
		clientThreadRuns();
		ChatSearch.Session second = search.open("Search the map", "b");
		clientThreadRuns();
		assertTrue(first.isClosed());
		assertFalse(second.isClosed());
		assertEquals("b", prompts.value());

		search.close(first);
		clientThreadRuns();
		assertEquals(0, prompts.closes);
		assertTrue(prompts.isOpen());

		search.close(second);
		clientThreadRuns();
		assertEquals(1, prompts.closes);
		assertFalse(prompts.isOpen());
	}

	@Test
	public void closingTwiceAsksOnce()
	{
		ChatSearch.Session session = search.open("Search the map", "");
		clientThreadRuns();
		search.close(session);
		search.close(session);
		search.closeAll();
		clientThreadRuns();
		assertEquals(1, prompts.closes);
	}

	@Test
	public void shuttingDownWithThePromptOpenClosesItAndWithoutOneDoesNothing()
	{
		search.closeAll();
		clientThreadRuns();
		assertEquals("never opened, never touched", 0, prompts.calls());

		ChatSearch.Session session = search.open("Search the map", "x");
		clientThreadRuns();
		assertTrue(prompts.isOpen());
		search.closeAll();
		clientThreadRuns();
		assertFalse(prompts.isOpen());
		assertTrue(session.isClosed());
		assertEquals(1, prompts.closes);

		search.closeAll();
		clientThreadRuns();
		assertEquals("a second shutdown asks for nothing more", 1, prompts.closes);
	}

	@Test
	public void reopeningAfterAnEscapeGivesAFreshPrompt()
	{
		for (int i = 0; i < 20; i++)
		{
			ChatSearch.Session session = search.open("Search the map", "q" + i);
			clientThreadRuns();
			assertEquals("q" + i, prompts.value());
			if (i % 2 == 0)
			{
				prompts.escape();
			}
			else
			{
				search.close(session);
				clientThreadRuns();
			}
			assertFalse(prompts.isOpen());
		}
		assertEquals(20, prompts.opens);
		assertEquals(10, prompts.closes);
	}
}
