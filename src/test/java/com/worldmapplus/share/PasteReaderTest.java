package com.worldmapplus.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Finding codes in pasted text: the code counts, whatever is around it. */
public class PasteReaderTest
{
	private static final String PIN = "p10kgkm86a";

	private static List<String> codes(String text)
	{
		return PasteReader.read(text).getCodes();
	}

	private static Share longRoute(int seed, int stops)
	{
		Random random = new Random(seed);
		List<SharePoint> points = new ArrayList<>();
		for (int i = 0; i < stops; i++)
		{
			points.add(new SharePoint(960 + random.nextInt(3072), 2048 + random.nextInt(2176), 0));
		}
		return Share.route(points, false);
	}

	/** The parts of a code without "!wmp ". */
	private static List<String> parts(Share share) throws ShareException
	{
		List<String> parts = new ArrayList<>();
		for (String message : ShareCodec.messages(share))
		{
			parts.add(message.substring(ShareCodec.COMMAND.length() + 1));
		}
		return parts;
	}

	@Test
	public void aCodeIsFoundWithOrWithoutTheCommandAndAmongOtherThings()
	{
		List<String> one = Collections.singletonList(PIN);
		assertEquals(one, codes(PIN));
		assertEquals(one, codes("!wmp " + PIN));
		assertEquals(one, codes("   \n\t!wmp   " + PIN + "  \r\n"));
		assertEquals(one, codes("\"" + PIN + "\""));
		assertEquals(one, codes("'!wmp " + PIN + "'"));
		assertEquals("markdown", one, codes("```\n!wmp " + PIN + "\n```"));
		assertEquals(one, codes("**`" + PIN + "`**"));
		assertEquals("upper case", one, codes("!WMP P10KGKM86A"));
		assertEquals("the space left out", one, codes("!wmp" + PIN));
		assertEquals("noise words", one, codes("hey here is the farming guild pin " + PIN + " see you there, really"));
		assertEquals("a word with a kind letter and a digit too short to be a code", one, codes("c1 r2 p1 " + PIN));
	}

	@Test
	public void wordsAreNotCodes()
	{
		PasteReader.Found found = PasteReader.read("selling logs, price 10000, simplicity, remembered, c1ty r2d2 p10");
		assertTrue(found.getCodes().isEmpty());
		assertTrue(found.getProblems().isEmpty());
		assertFalse(PasteReader.read(null).getCodes().size() > 0);
	}

	@Test
	public void severalCodesAreAllFoundInOrder() throws Exception
	{
		List<String> route = parts(longRoute(7, 30));
		String text = "pins: !wmp p10kgkm86a, !wmp p11jb34j2v\nroute:\n!wmp " + route.get(0) + "\n!wmp " + route.get(1) + "\n!wmp "
			+ route.get(2);
		List<String> expected = new ArrayList<>(Arrays.asList("p10kgkm86a", "p11jb34j2v"));
		expected.addAll(route);
		assertEquals(expected, codes(text));
	}

	@Test
	public void partsRunTogetherAreSplitInAnyOrder() throws Exception
	{
		List<String> route = parts(longRoute(7, 30));
		assertEquals(route, codes(String.join("", route)));
		List<String> reversed = new ArrayList<>(route);
		Collections.reverse(reversed);
		assertEquals(reversed, codes("!wmp " + String.join("", reversed)));
		// Two parts.
		List<String> two = parts(longRoute(1, 15));
		assertEquals(2, two.size());
		assertEquals(two, codes(two.get(0) + two.get(1)));
		assertEquals(Arrays.asList(two.get(1), two.get(0)), codes(two.get(1) + two.get(0)));
	}

	@Test
	public void partsRunTogetherThatCannotBeSplitSaySo() throws Exception
	{
		List<String> route = parts(longRoute(7, 30));
		// A part missing a character: the lengths no longer add up.
		String damaged = route.get(0) + route.get(1).substring(0, 40) + route.get(1).substring(41) + route.get(2);
		PasteReader.Found found = PasteReader.read(damaged);
		assertTrue(found.getCodes().isEmpty());
		assertEquals(Collections.singletonList("Some parts are run together and could not be split. Put each part on its own line."),
			found.getProblems());
		assertNull(PasteReader.splitRunTogether(route.get(0) + route.get(0) + route.get(2)));
	}

	@Test
	public void aWholeLongCodeIsOneCode() throws Exception
	{
		Share share = longRoute(7, 30);
		String whole = ShareCodec.encode(share);
		assertTrue(whole.length() > ShareCodec.MAX_SINGLE);
		assertEquals(Collections.singletonList(whole), codes("!wmp " + whole));
		assertEquals(share, ShareCodec.decode(codes(whole).get(0)));
	}

	@Test
	public void theAnswersForOtherVersionsAndWrongLetters()
	{
		PasteReader.Found newer = PasteReader.read("!wmp p20kgkm86a");
		assertTrue(newer.getCodes().isEmpty());
		assertTrue(newer.getProblems().get(0).contains("newer version"));
		PasteReader.Found wrong = PasteReader.read("!wmp p10kgum86a");
		assertTrue(wrong.getProblems().get(0), wrong.getProblems().get(0).contains("(u) at character 6"));
		assertTrue(PasteReader.read("wmp0bcdfghjklmnpq_rstvwxyz").hasOld());
	}

	@Test
	public void aPasteIsBounded()
	{
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 100; i++)
		{
			text.append("!wmp ").append(PIN).append('\n');
		}
		PasteReader.Found found = PasteReader.read(text.toString());
		assertEquals(PasteReader.MAX_CODES, found.getCodes().size());
		assertTrue(found.isCut());
		StringBuilder huge = new StringBuilder();
		while (huge.length() < PasteReader.MAX_CHARS + 100)
		{
			huge.append("word ");
		}
		huge.append(PIN);
		assertTrue("nothing past the limit is read", PasteReader.read(huge.toString()).getCodes().isEmpty());
		assertTrue(PasteReader.read(huge.toString()).isCut());
	}

	@Test
	public void thePartsArePiecesOfTheWholeCode() throws Exception
	{
		// SHARE-PROTOCOL.md, "Messages and parts": the data of the parts, in order, is the whole code.
		for (int stops : new int[] {15, 30, 64})
		{
			Share share = longRoute(stops, stops);
			String whole = ShareCodec.encode(share);
			StringBuilder joined = new StringBuilder();
			for (String part : parts(share))
			{
				assertTrue(part.startsWith("c1"));
				joined.append(part.substring(ShareCodec.PART_HEAD));
			}
			assertEquals(whole, joined.toString());
		}
	}
}
