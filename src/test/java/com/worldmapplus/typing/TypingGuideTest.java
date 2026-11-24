package com.worldmapplus.typing;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The typing guide's comparison and its rules for moving between the messages of a share. */
public class TypingGuideTest
{
	private static final String PIN = "!wmp p10kgkm86a";
	private static final List<String> PARTS = Arrays.asList("!wmp c1322aaaa", "!wmp c132abbbb", "!wmp c132jcccc");

	private static TypingGuide.View view(TypingGuide guide)
	{
		TypingGuide.View view = new TypingGuide.View();
		guide.fill(view);
		return view;
	}

	private static void type(TypingGuide guide, String line)
	{
		guide.update(guide.generation(), line);
	}

	@Test
	public void rightLettersAreRightAndTheWholeMessageMatches()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList(PIN), 0);
		type(guide, "!wmp p1");
		TypingGuide.View v = view(guide);
		assertEquals(7, v.right);
		assertEquals(0, v.wrong);
		assertEquals(TypingGuide.Hint.GOING, v.hint);
		for (int i = 0; i < 7; i++)
		{
			assertEquals(TypingGuide.RIGHT, v.marks[i]);
		}
		type(guide, PIN);
		assertEquals(TypingGuide.Hint.MATCH, view(guide).hint);
		assertEquals(TypingGuide.State.TYPING, guide.state(0));
	}

	@Test
	public void aWrongLetterIsWrongAndTheFirstOneIsNamed()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList(PIN), 0);
		type(guide, "!wmp p1xkgkm8");
		TypingGuide.View v = view(guide);
		assertEquals(TypingGuide.WRONG, v.marks[7]);
		assertEquals(7, v.firstWrong);
		assertEquals('0', v.expected(v.firstWrong));
		assertEquals(TypingGuide.Hint.TYPO, v.hint);
		assertEquals(1, v.wrong);
		assertEquals(12, v.right);
	}

	@Test
	public void caseNeverMattersAndTheCodesAliasesCountOnlyInTheCode()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList(PIN), 0);
		type(guide, "!WMP P1OKGKM86A");
		TypingGuide.View v = view(guide);
		assertEquals("upper case and o for 0 read the same", TypingGuide.Hint.MATCH, v.hint);
		assertTrue(TypingGuide.same('I', '1', true));
		assertTrue(TypingGuide.same('l', '1', true));
		assertFalse("not in the command", TypingGuide.same('o', '0', false));
		assertFalse(TypingGuide.same('u', 'v', true));
	}

	@Test
	public void lettersPastTheEndAreExtraAndAMissingEndIsNotAMatch()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList(PIN), 0);
		type(guide, PIN + "zz");
		TypingGuide.View v = view(guide);
		assertEquals(2, v.extra);
		assertEquals(TypingGuide.EXTRA, v.marks[PIN.length()]);
		assertEquals(TypingGuide.Hint.TOO_LONG, v.hint);
		type(guide, PIN.substring(0, PIN.length() - 1));
		assertEquals(TypingGuide.Hint.GOING, view(guide).hint);
	}

	@Test
	public void aChannelPrefixIsLeftOutOfTheComparison()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(Collections.singletonList(PIN), 0);
		type(guide, "///" + PIN);
		TypingGuide.View v = view(guide);
		assertEquals(3, v.channel);
		assertEquals(TypingGuide.CHANNEL, v.marks[0]);
		assertEquals(TypingGuide.Hint.MATCH, v.hint);
		type(guide, "/");
		assertEquals("a slash alone is not typing the code", TypingGuide.Hint.START, view(guide).hint);
	}

	@Test
	public void sendingAWholePartMovesOnAndTheLastFinishes()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(PARTS, 0);
		type(guide, PARTS.get(0));
		type(guide, "");
		assertEquals(1, guide.current());
		assertEquals(TypingGuide.State.DONE, guide.state(0));
		TypingGuide.View v = view(guide);
		assertEquals(1, v.part);
		assertEquals(3, v.parts);
		assertEquals(TypingGuide.Hint.START, v.hint);
		type(guide, PARTS.get(1));
		type(guide, "");
		type(guide, PARTS.get(2));
		type(guide, "");
		assertFalse(guide.isActive());
		assertTrue(guide.isFinished());
		assertFalse("the overlay draws nothing", view(guide).active);
	}

	@Test
	public void clearingALineThatWasNotWholeDoesNotMoveOn()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(PARTS, 0);
		type(guide, PARTS.get(0).substring(0, 9));
		type(guide, "");
		assertEquals(0, guide.current());
		type(guide, PARTS.get(0) + "q");
		type(guide, "");
		assertEquals("a line with a letter too many was not the message", 0, guide.current());
	}

	@Test
	public void backSkipAndNextMoveAndAPartDoneEarlierIsPassedOver()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(PARTS, 0);
		assertFalse("no message before the first", guide.back());
		assertTrue(guide.skip());
		assertEquals(1, guide.current());
		assertEquals("skip marks nothing", TypingGuide.State.NOT_TYPED, guide.state(0));
		assertTrue(guide.next());
		assertEquals(TypingGuide.State.DONE, guide.state(1));
		assertEquals(2, guide.current());
		assertFalse("no message after the last", guide.skip());
		assertTrue(guide.next());
		assertEquals("the first is not done, so the guide goes back to it", 0, guide.current());
		assertTrue(guide.back() || guide.current() == 0);
		assertTrue(guide.next());
		assertFalse(guide.isActive());
		assertTrue(guide.isFinished());
	}

	@Test
	public void stopAndRestartAndAnswersFromBeforeAreIgnored()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(PARTS, 0);
		long first = guide.generation();
		guide.stop();
		assertFalse(guide.isActive());
		guide.update(first, PARTS.get(0));
		assertFalse("an update after a stop does nothing", view(guide).active);
		guide.start(PARTS, 2);
		assertEquals(2, guide.current());
		guide.update(first, "!wmp c132j");
		assertEquals("an answer read before the restart is ignored", 0, view(guide).typedLength);
		type(guide, "!wmp c132j");
		assertEquals(10, view(guide).typedLength);
		assertFalse("a stop does not say finished", guide.isFinished());
	}

	@Test
	public void movingToAnotherMessageComparesTheLineAgain()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(PARTS, 0);
		type(guide, "!wmp c132a");
		assertEquals(TypingGuide.Hint.TYPO, view(guide).hint);
		guide.jumpTo(1);
		type(guide, "!wmp c132a");
		assertEquals("the same line is compared with the new message", TypingGuide.Hint.GOING, view(guide).hint);
	}

	@Test
	public void sizesAreBounded()
	{
		TypingGuide guide = new TypingGuide();
		String[] many = new String[12];
		Arrays.fill(many, "!wmp c1abc");
		guide.start(Arrays.asList(many), 30);
		assertEquals(TypingGuide.MAX_MESSAGES, guide.count());
		assertEquals("a first message past the end is the last", TypingGuide.MAX_MESSAGES - 1, guide.current());
		StringBuilder longLine = new StringBuilder();
		for (int i = 0; i < 500; i++)
		{
			longLine.append('z');
		}
		type(guide, longLine.toString());
		assertEquals(TypingGuide.MAX_TYPED, view(guide).typedLength);
		guide.start(Collections.<String>emptyList(), 0);
		assertFalse("an empty share starts nothing", guide.isActive());
	}

	@Test
	public void anUnchangedLineMakesNoNewVersion()
	{
		TypingGuide guide = new TypingGuide();
		guide.start(PARTS, 0);
		type(guide, "!wmp");
		int version = guide.version();
		type(guide, "!wmp");
		assertEquals(version, guide.version());
		type(guide, null);
		assertEquals(TypingGuide.Hint.START, view(guide).hint);
	}
}
