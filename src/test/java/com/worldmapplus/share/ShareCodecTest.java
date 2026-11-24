package com.worldmapplus.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/** Version 1 share codes: golden vectors, exact round trips, damaged and hostile input, and the parts of long codes. */
public class ShareCodecTest
{
	private static SharePoint p(int x, int y, int plane)
	{
		return new SharePoint(x, y, plane);
	}

	private static Share roundTrip(Share share) throws ShareException
	{
		String code = ShareCodec.encode(share);
		Share back = ShareCodec.decode(code);
		assertEquals(code, share, back);
		assertEquals("one canonical form", code, ShareCodec.encode(back));
		return back;
	}

	// ------------------------------------------------------------------
	// Golden vectors. These codes were made by the finished encoder and are in docs/SHARE-PROTOCOL.md. A version 1 code
	// must decode to the same thing for ever: never change a line here to make a test pass.
	// ------------------------------------------------------------------

	private static final Object[][] GOLDEN = {
		{"p10kgkm86a", Share.pin(p(1249, 3720, 0))},
		{"p11jb34j2v", Share.pin(p(3222, 3218, 0))},
		{"p19j434mgz", Share.pin(p(3208, 3220, 2))},
		{"p1h8m470p02d0", Share.pin(new SharePoint(2600, 4320, 0, 10128))},
		{"p1000000aa", Share.pin(p(0, 0, 0))},
		{"p1fzzzzzz9a", Share.pin(p(32767, 32767, 3))},
		{"r120cjrs4g8a2651tg00j7w", Share.route(Arrays.asList(p(3222, 3218, 0), p(3208, 3220, 0), p(3235, 3205, 0),
			p(3214, 3240, 0), p(3200, 3204, 0)), false)},
		{"r11rce4tv129e0969rb2", Share.route(Arrays.asList(p(3185, 3436, 0), p(3170, 3410, 0), p(3185, 3436, 0),
			p(3250, 3420, 0)), true)},
		{"r120cfrs02keb48699gs9ct1c2e2eh37m", Share.route(Arrays.asList(p(3198, 3200, 0), p(3202, 3197, 0), p(3208, 3220, 1),
			p(3237, 9858, 0), p(1249, 3720, 0)), false)},
		{"s118s5hj963a1jr380rc2gsarym", Share.places(Arrays.asList(p(3222, 3218, 0), new SharePoint(3393, 9601, 0, 10000),
			p(3092, 3243, 0)))},
	};

	@Test
	public void goldenVectorsDecodeAndEncodeExactly() throws Exception
	{
		for (Object[] vector : GOLDEN)
		{
			String code = (String) vector[0];
			Share share = (Share) vector[1];
			assertEquals(code, share, ShareCodec.decode(code));
			assertEquals(share.toString(), code, ShareCodec.encode(share));
			assertEquals(Collections.singletonList("!wmp " + code), ShareCodec.messages(share));
		}
	}

	@Test
	public void aPinInRegion4922IsFifteenCharactersOfChat() throws Exception
	{
		SharePoint farmingGuild = p(1249, 3720, 0);
		assertEquals(4922, farmingGuild.regionId());
		assertEquals(33, farmingGuild.localX());
		assertEquals(8, farmingGuild.localY());
		List<String> messages = ShareCodec.messages(Share.pin(farmingGuild));
		assertEquals(Collections.singletonList("!wmp p10kgkm86a"), messages);
		assertEquals(15, messages.get(0).length());
	}

	@Test
	public void goldenLongRouteSplitsIntoThreeParts() throws Exception
	{
		List<SharePoint> big = new ArrayList<>();
		Random r = new Random(7);
		for (int i = 0; i < 30; i++)
		{
			big.add(p(960 + r.nextInt(3072), 2048 + r.nextInt(2176), 0));
		}
		Share route = Share.route(big, false);
		List<String> messages = ShareCodec.messages(route);
		assertEquals(Arrays.asList(
			"!wmp c1322r1egbhgv362fd79sgah1pqc2y0fs71h141er6r0wm62sm66zgf7s9zc6m2eeb1x5j3",
			"!wmp c132a7r8y8mx63j35nqgffhgtc35edf31gr26kr9vcspe15q7x7gs1sxpc4gma8b1fq2k2r",
			"!wmp c132jc6mrw62cs5kzgth9ndw2c0cj71263ncrdmgxfe1wj5nngh319tc6sgbdq0sd3j5sz"), messages);
		for (String message : messages)
		{
			assertTrue(message.length() <= ShareCodec.MAX_MESSAGE);
		}
		List<ShareCodec.Part> parts = new ArrayList<>();
		for (String message : messages)
		{
			parts.add(ShareCodec.part(ShareCodec.codeIn(message)));
		}
		assertEquals(route, ShareCodec.join(parts));
	}

	@Test
	public void theChecksumIsCrc10Atm() throws Exception
	{
		byte[] text = "123456789".getBytes(StandardCharsets.US_ASCII);
		byte[] bits = new byte[text.length * 8];
		for (int i = 0; i < bits.length; i++)
		{
			bits[i] = (byte) (text[i / 8] >> 7 - i % 8 & 1);
		}
		assertEquals("the catalogue's check value of CRC-10/ATM", 0x199, Bits.crc10(bits, 0, bits.length, 0));
	}

	// ------------------------------------------------------------------
	// Round trips
	// ------------------------------------------------------------------

	@Test
	public void everyFloorRegionEdgeChunkEdgeAndExtremeComesBackExactly() throws Exception
	{
		int[] xs = {0, 1, 63, 64, 1215, 1216, 1249, 4095, 4096, 16383, 16384, 32767};
		for (int x : xs)
		{
			for (int y : xs)
			{
				for (int plane = 0; plane < 4; plane++)
				{
					roundTrip(Share.pin(p(x, y, plane)));
				}
			}
		}
		// Neighbours across every region and 8 by 8 chunk edge, in both directions, and every step size.
		List<SharePoint> walk = new ArrayList<>();
		for (int x = 3190; x < 3270; x += 7)
		{
			walk.add(p(x, 3199 + x % 3, 0));
		}
		roundTrip(Share.route(walk, false));
		for (int dx : new int[] {-129, -128, -127, -9, -8, -7, -1, 0, 1, 7, 8, 9, 127, 128, 129, 5000, -5000})
		{
			roundTrip(Share.route(Arrays.asList(p(10000, 10000, 1), p(10000 + dx, 10000 - dx, 1)), false));
		}
	}

	@Test
	public void mapsThatAreNotTheTilesOwnTravelInTheCode() throws Exception
	{
		for (int map : new int[] {0, 52, 255, 256, 9999, 10000, 10183, 10255, 10256, 11000, 11026, 11255, 20000, 32767})
		{
			roundTrip(Share.pin(new SharePoint(2600, 4320, 1, map)));
			roundTrip(Share.places(Arrays.asList(p(3222, 3218, 0), new SharePoint(3223, 3219, 0, map), p(3222, 3218, 0))));
		}
	}

	@Test
	public void orderAndRepeatsAreKept() throws Exception
	{
		List<SharePoint> stops = Arrays.asList(p(3185, 3436, 0), p(3170, 3410, 0), p(3185, 3436, 0), p(3185, 3436, 0),
			p(3110, 3420, 0), p(3170, 3410, 0), p(3185, 3436, 0));
		Share back = roundTrip(Share.route(stops, true));
		assertEquals(stops, back.getPoints());
		assertTrue(back.loops());
	}

	@Test
	public void randomRoutesComeBackExactly() throws Exception
	{
		Random random = new Random(49);
		for (int t = 0; t < 3000; t++)
		{
			int n = 1 + random.nextInt(ShareCodec.MAX_POINTS);
			List<SharePoint> points = new ArrayList<>();
			int x = random.nextInt(32768);
			int y = random.nextInt(32768);
			for (int i = 0; i < n; i++)
			{
				int kind = random.nextInt(8);
				if (kind == 0)
				{
					x = random.nextInt(32768);
					y = random.nextInt(32768);
				}
				else if (kind < 4)
				{
					x = Math.max(0, Math.min(32767, x + random.nextInt(17) - 8));
					y = Math.max(0, Math.min(32767, y + random.nextInt(17) - 8));
				}
				else if (kind < 6)
				{
					x = Math.max(0, Math.min(32767, x + random.nextInt(300) - 150));
				}
				else if (kind == 6 && i > 0)
				{
					points.add(points.get(random.nextInt(i)));
					continue;
				}
				int map = random.nextInt(10) == 0 ? new int[] {3, 10050, 11010, 20000, 32767}[random.nextInt(5)] : -1;
				points.add(new SharePoint(x, y, random.nextInt(4), map));
			}
			roundTrip(random.nextBoolean() ? Share.route(points, random.nextBoolean()) : Share.places(points));
		}
	}

	@Test
	public void theEncoderRefusesWhatACodeCannotHold() throws Exception
	{
		for (SharePoint bad : new SharePoint[] {p(-1, 0, 0), p(0, -1, 0), p(32768, 0, 0), p(0, 32768, 0), p(0, 0, 4),
			p(0, 0, -1), new SharePoint(0, 0, 0, 32768), new SharePoint(0, 0, 0, -2)})
		{
			try
			{
				ShareCodec.encode(Share.pin(bad));
				fail("encoded " + bad);
			}
			catch (ShareException expected)
			{
				// Refused, never cut down to fit.
			}
		}
		List<SharePoint> tooMany = new ArrayList<>();
		for (int i = 0; i <= ShareCodec.MAX_POINTS; i++)
		{
			tooMany.add(p(3200 + i, 3200, 0));
		}
		expectRefused(() -> ShareCodec.encode(Share.route(tooMany, false)));
		expectRefused(() -> ShareCodec.encode(Share.places(Collections.emptyList())));
	}

	@Test
	public void theBenchmarkModelOfTheChosenFormatMatchesTheEncoder() throws Exception
	{
		for (Map.Entry<String, List<int[]>> scenario : ShareBench.scenarios().entrySet())
		{
			List<SharePoint> points = new ArrayList<>();
			for (int[] q : scenario.getValue())
			{
				points.add(p(q[0], q[1], q[2]));
			}
			Share share = points.size() == 1 ? Share.pin(points.get(0)) : Share.route(points, false);
			int header = points.size() == 1 ? 10 + 1 : 10 + 8;
			int bits = header + ShareBench.candidateE(scenario.getValue()) + 10;
			assertEquals(scenario.getKey(), (bits + 4) / 5, ShareCodec.encode(share).length());
		}
	}

	// ------------------------------------------------------------------
	// Damaged and hostile input
	// ------------------------------------------------------------------

	private interface Action
	{
		void run() throws Exception;
	}

	private static void expectRefused(Action action) throws Exception
	{
		try
		{
			action.run();
			fail("not refused");
		}
		catch (ShareException expected)
		{
			// The only way a code is turned down.
		}
	}

	@Test
	public void everySingleMistypedCharacterIsCaught() throws Exception
	{
		for (Object[] vector : GOLDEN)
		{
			String code = (String) vector[0];
			for (int i = 0; i < code.length(); i++)
			{
				for (char c : ShareCodec.ALPHABET.toCharArray())
				{
					if (c == code.charAt(i))
					{
						continue;
					}
					String typo = code.substring(0, i) + c + code.substring(i + 1);
					expectRefused(() -> ShareCodec.decode(typo));
				}
				if (i + 1 < code.length() && code.charAt(i) != code.charAt(i + 1))
				{
					String swapped = code.substring(0, i) + code.charAt(i + 1) + code.charAt(i) + code.substring(i + 2);
					expectRefused(() -> ShareCodec.decode(swapped));
				}
			}
		}
	}

	@Test
	public void cutShortOrLengthenedCodesAreRefused() throws Exception
	{
		for (Object[] vector : GOLDEN)
		{
			String code = (String) vector[0];
			for (int length = 0; length < code.length(); length++)
			{
				String cut = code.substring(0, length);
				expectRefused(() -> ShareCodec.decode(cut));
			}
			for (char c : ShareCodec.ALPHABET.toCharArray())
			{
				expectRefused(() -> ShareCodec.decode(code + c));
			}
		}
	}

	@Test
	public void caseAndCrockfordLookalikesReadTheSame() throws Exception
	{
		assertEquals(Share.pin(p(1249, 3720, 0)), ShareCodec.decode("P10KGKM86A"));
		assertEquals(Share.pin(p(1249, 3720, 0)), ShareCodec.decode("pIOkgkm86a"));
		assertEquals(Share.pin(p(1249, 3720, 0)), ShareCodec.decode("pLokgkm86a"));
		assertEquals("p10kgkm86a", ShareCodec.canonical("PlOKGKM86A"));
		expectRefused(() -> ShareCodec.decode("p10kgkm86u"));
		expectRefused(() -> ShareCodec.decode("p10kgkm8-a"));
		expectRefused(() -> ShareCodec.decode("p10kgkm8éa"));
	}

	@Test
	public void otherVersionsAndKindsAreRefusedWithAReason() throws Exception
	{
		try
		{
			ShareCodec.decode("p20kgkm86a");
			fail();
		}
		catch (ShareException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("newer version"));
		}
		expectRefused(() -> ShareCodec.decode("p00kgkm86a"));
		expectRefused(() -> ShareCodec.decode("x10kgkm86a"));
		try
		{
			ShareCodec.decode("c1w02r1egbhgv362fd79sgah1pqc2y0fs71h141er6r0w");
			fail();
		}
		catch (ShareException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("one part"));
		}
	}

	/** Makes a code from raw bits with a correct checksum, so the checks after it can be reached. */
	private static String forge(String kindAndVersion, int[]... fields)
	{
		Bits.Writer out = new Bits.Writer();
		out.write(ShareCodec.value(kindAndVersion.charAt(0)), 5);
		out.write(ShareCodec.value(kindAndVersion.charAt(1)), 5);
		for (int[] field : fields)
		{
			out.write(field[0], field[1]);
		}
		out.write(Bits.crc10(out.toArray(), 0, out.size(), 0), 10);
		while (out.size() % 5 != 0)
		{
			out.write(0, 1);
		}
		byte[] bits = out.toArray();
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < bits.length; i += 5)
		{
			text.append(ShareCodec.ALPHABET.charAt(bits[i] << 4 | bits[i + 1] << 3 | bits[i + 2] << 2 | bits[i + 3] << 1 | bits[i + 4]));
		}
		return text.toString();
	}

	@Test
	public void codesWithAGoodChecksumAndBadInsidesAreRefused() throws Exception
	{
		// A good pin, as a control.
		assertEquals(Share.pin(p(1249, 3720, 0)), ShareCodec.decode(forge("p1", new int[] {0, 1}, new int[] {0, 2},
			new int[] {0, 1}, new int[] {1249, 12}, new int[] {3720, 14})));
		// The long form where the short one fits.
		expectRefused(() -> ShareCodec.decode(forge("p1", new int[] {0, 1}, new int[] {0, 2}, new int[] {1, 1},
			new int[] {1249, 15}, new int[] {3720, 15})));
		// "Maps follow" with no map named.
		expectRefused(() -> ShareCodec.decode(forge("p1", new int[] {1, 1}, new int[] {0, 2}, new int[] {0, 1},
			new int[] {1249, 12}, new int[] {3720, 14}, new int[] {0, 1})));
		// A map in the long form where a short one fits.
		expectRefused(() -> ShareCodec.decode(forge("p1", new int[] {1, 1}, new int[] {0, 2}, new int[] {0, 1},
			new int[] {1249, 12}, new int[] {3720, 14}, new int[] {1, 1}, new int[] {3, 2}, new int[] {5, 15})));
		// A step off the edge of the world.
		expectRefused(() -> ShareCodec.decode(forge("r1", new int[] {1, 6}, new int[] {0, 1}, new int[] {0, 1},
			new int[] {0, 2}, new int[] {0, 1}, new int[] {2, 12}, new int[] {2, 14},
			new int[] {ShareCodec.SMALL_STEP, 2}, new int[] {-8, 4}, new int[] {0, 4})));
		// A region past the world's last one.
		expectRefused(() -> ShareCodec.decode(forge("r1", new int[] {1, 6}, new int[] {0, 1}, new int[] {0, 1},
			new int[] {0, 2}, new int[] {1, 1}, new int[] {32767, 15}, new int[] {5, 15},
			new int[] {ShareCodec.NEXT_REGION, 3}, new int[] {4, 3}, new int[] {0, 6}, new int[] {0, 6})));
		// A copy of a point that has not been given.
		expectRefused(() -> ShareCodec.decode(forge("r1", new int[] {1, 6}, new int[] {0, 1}, new int[] {0, 1},
			new int[] {0, 2}, new int[] {0, 1}, new int[] {2, 12}, new int[] {2, 14},
			new int[] {ShareCodec.EARLIER, 4}, new int[] {1, 6})));
		// Five bits or more after the checksum, or a padding bit set.
		String good = forge("p1", new int[] {0, 1}, new int[] {0, 2}, new int[] {0, 1}, new int[] {1249, 12}, new int[] {3720, 14});
		expectRefused(() -> ShareCodec.decode(good + "0"));
		String lastSet = good.substring(0, good.length() - 1)
			+ ShareCodec.ALPHABET.charAt(ShareCodec.value(good.charAt(good.length() - 1)) | 1);
		expectRefused(() -> ShareCodec.decode(lastSet));
	}

	@Test
	public void randomTextNeverDoesAnythingButRefuse() throws Exception
	{
		Random random = new Random(4922);
		int accepted = 0;
		for (int t = 0; t < 200_000; t++)
		{
			int length = random.nextInt(80);
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < length; i++)
			{
				text.append(random.nextInt(10) == 0 ? (char) random.nextInt(0x3000) : ShareCodec.ALPHABET.charAt(random.nextInt(32)));
			}
			try
			{
				ShareCodec.decode(text.toString());
				accepted++;
			}
			catch (ShareException expected)
			{
				// Fine.
			}
			try
			{
				ShareCodec.part(text.toString());
			}
			catch (ShareException expected)
			{
				// Fine.
			}
		}
		// A random code passes its checks about once in a thousand, and only when everything else about it reads.
		assertTrue("accepted " + accepted, accepted < 200);
		expectRefused(() -> ShareCodec.decode(String.join("", Collections.nCopies(ShareCodec.MAX_CODE + 1, "0"))));
	}

	// ------------------------------------------------------------------
	// Chat lines and parts
	// ------------------------------------------------------------------

	@Test
	public void theCodeIsFoundAfterTheCommandAndNowhereElse()
	{
		assertEquals("p10kgkm86a", ShareCodec.codeIn("!wmp p10kgkm86a"));
		assertEquals("p10kgkm86a", ShareCodec.codeIn("<col=0000ff>!WMP  P10KGKM86A</col> <col=ef8a1e>(World Map Plus pin)</col>"));
		assertEquals(null, ShareCodec.codeIn("!wmpp10kgkm86a"));
		assertEquals(null, ShareCodec.codeIn("look !wmp p10kgkm86a"));
		assertEquals(null, ShareCodec.codeIn("!wmp"));
		assertEquals(null, ShareCodec.codeIn(null));
	}

	@Test
	public void partsJoinOnlyWhenTheyAllFit() throws Exception
	{
		List<SharePoint> big = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			big.add(p(1000 + i * 70, 2100 + i * 50, i % 4));
		}
		Share route = Share.route(big, true);
		List<String> messages = ShareCodec.messages(route);
		assertTrue(messages.size() > 1 && messages.size() <= ShareCodec.MAX_PARTS);
		List<ShareCodec.Part> parts = new ArrayList<>();
		for (String message : messages)
		{
			assertTrue(message, message.length() <= ShareCodec.MAX_MESSAGE);
			parts.add(ShareCodec.part(ShareCodec.codeIn(message)));
		}
		assertEquals(route, ShareCodec.join(parts));
		List<ShareCodec.Part> missing = new ArrayList<>(parts.subList(1, parts.size()));
		expectRefused(() -> ShareCodec.join(missing));
		List<ShareCodec.Part> swapped = new ArrayList<>(parts);
		Collections.swap(swapped, 0, 1);
		expectRefused(() -> ShareCodec.join(swapped));
		// A mistyped character in any part is caught when they are joined.
		String first = ShareCodec.codeIn(messages.get(0));
		char wrong = first.charAt(10) == 'a' ? 'b' : 'a';
		List<ShareCodec.Part> typo = new ArrayList<>(parts);
		typo.set(0, ShareCodec.part(first.substring(0, 10) + wrong + first.substring(11)));
		expectRefused(() -> ShareCodec.join(typo));
		// A part whose numbers do not make sense.
		expectRefused(() -> ShareCodec.part("c1000abc"));
		expectRefused(() -> ShareCodec.part("c101sabc"));
	}

	@Test
	public void aRouteTooLongForEightMessagesIsRefusedBeforeItIsShown() throws Exception
	{
		List<SharePoint> scattered = new ArrayList<>();
		Random random = new Random(1);
		for (int i = 0; i < ShareCodec.MAX_POINTS; i++)
		{
			scattered.add(new SharePoint(random.nextInt(32768), random.nextInt(32768), random.nextInt(4), 20000 + i));
		}
		expectRefused(() -> ShareCodec.messages(Share.places(scattered)));
		assertFalse(ShareCodec.isPart("p10kgkm86a"));
	}
}
