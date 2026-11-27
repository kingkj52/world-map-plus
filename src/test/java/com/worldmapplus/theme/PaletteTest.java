package com.worldmapplus.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The themes are complete, their colours can be told apart, and their text can be read (WCAG contrast). */
public class PaletteTest
{
	/** A text colour on a background, both by role. Translucent backgrounds are judged over the worse of black and white. */
	private static final class Pair
	{
		final String text;
		final String back;
		final double needed;

		Pair(String text, String back, double needed)
		{
			this.text = text;
			this.back = back;
			this.needed = needed;
		}
	}

	/** Body text and the labels people read, 4.5 to 1 (WCAG AA). */
	private static List<Pair> body()
	{
		List<Pair> pairs = new ArrayList<>();
		String[] mapBacks = {"PANEL", "PANEL_ACTIVE", "FIELD_FILL", "FIELD_FOCUSED", "PLATE", "FLOOR_FILL"};
		for (String back : mapBacks)
		{
			pairs.add(new Pair("TEXT", back, 4.5));
		}
		pairs.add(new Pair("TEXT_DIM", "PANEL", 4.5));
		pairs.add(new Pair("ACCENT", "PANEL", 4.5));
		pairs.add(new Pair("HINT", "PANEL", 4.5));
		pairs.add(new Pair("FIELD_DIM", "FIELD_FILL", 4.5));
		pairs.add(new Pair("MISSING", "FIELD_FILL", 4.5));
		pairs.add(new Pair("TICK_MAP", "PANEL", 3.0));
		String[] panelBacks = {"PAGE", "CARD", "CARD_HOVER", "CARD_ACTIVE", "FIELD", "BUTTON", "BUTTON_TOP",
			"BUTTON_HOVER"};
		for (String back : panelBacks)
		{
			pairs.add(new Pair("TEXT", back, 4.5));
		}
		pairs.add(new Pair("TEXT_DIM", "CARD", 4.5));
		pairs.add(new Pair("TEXT_DIM", "PAGE", 4.5));
		pairs.add(new Pair("ACCENT", "CARD", 4.5));
		pairs.add(new Pair("ACCENT", "PAGE", 4.5));
		pairs.add(new Pair("ACCENT", "BADGE", 4.5));
		pairs.add(new Pair("ACCENT", "CARD_ACTIVE", 4.5));
		pairs.add(new Pair("GOOD", "CARD", 4.5));
		pairs.add(new Pair("BAD", "CARD", 4.5));
		pairs.add(new Pair("PRIMARY_TEXT", "PRIMARY", 4.5));
		pairs.add(new Pair("PRIMARY_TEXT", "PRIMARY_TOP", 4.5));
		pairs.add(new Pair("PRIMARY_TEXT", "PRIMARY_HOVER", 4.5));
		return pairs;
	}

	/** Roles that are quiet on purpose, held to the 3 to 1 of large text and interface parts. */
	private static List<Pair> quiet()
	{
		List<Pair> pairs = new ArrayList<>();
		pairs.add(new Pair("TEXT_FAINT", "PAGE", 3.0));
		pairs.add(new Pair("TEXT_FAINT", "CARD", 3.0));
		pairs.add(new Pair("FOCUS", "PAGE", 3.0));
		pairs.add(new Pair("FOCUS", "CARD", 3.0));
		pairs.add(new Pair("BORDER", "PANEL", 1.5));
		return pairs;
	}

	static double channel(int value)
	{
		double c = value / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	static double luminance(int r, int g, int b)
	{
		return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
	}

	/** Lays {@code colour} over a grey level and returns its luminance. */
	static double over(Color colour, int under)
	{
		double a = colour.getAlpha() / 255.0;
		int r = (int) Math.round(colour.getRed() * a + under * (1 - a));
		int g = (int) Math.round(colour.getGreen() * a + under * (1 - a));
		int b = (int) Math.round(colour.getBlue() * a + under * (1 - a));
		return luminance(r, g, b);
	}

	static double ratio(double one, double other)
	{
		double hi = Math.max(one, other);
		double lo = Math.min(one, other);
		return (hi + 0.05) / (lo + 0.05);
	}

	/** The worst contrast of a text colour on a background over any grey the map can put behind a translucent fill. */
	static double worst(Palette p, String text, String back)
	{
		Color fg = p.get(text);
		Color bg = p.get(back);
		double worst = Double.MAX_VALUE;
		for (int under : new int[] {0, 255})
		{
			double backLum = over(bg, under);
			// The text is laid on the finished background; a translucent text colour is laid over it too.
			int br = (int) Math.round(bg.getRed() * (bg.getAlpha() / 255.0) + under * (1 - bg.getAlpha() / 255.0));
			int bgc = (int) Math.round(bg.getGreen() * (bg.getAlpha() / 255.0) + under * (1 - bg.getAlpha() / 255.0));
			int bb = (int) Math.round(bg.getBlue() * (bg.getAlpha() / 255.0) + under * (1 - bg.getAlpha() / 255.0));
			double fa = fg.getAlpha() / 255.0;
			double textLum = luminance((int) Math.round(fg.getRed() * fa + br * (1 - fa)),
				(int) Math.round(fg.getGreen() * fa + bgc * (1 - fa)), (int) Math.round(fg.getBlue() * fa + bb * (1 - fa)));
			worst = Math.min(worst, ratio(textLum, backLum));
		}
		return worst;
	}

	@Test
	public void everyThemeNamesEveryRoleAndTheNamesMatch()
	{
		for (ThemeName name : ThemeName.values())
		{
			Palette p = Palette.of(name);
			assertNotNull(p);
			assertEquals(name.toString(), p.name());
			assertEquals(Palette.all()[0].names(), p.names());
			for (String role : p.names())
			{
				assertNotNull(name + " " + role, p.get(role));
			}
		}
		assertEquals(4, Palette.all().length);
	}

	/** The side panel is moved between themes by colour, so no two of its roles may share a colour in one theme. */
	@Test
	public void panelRolesCanBeToldApartByColour()
	{
		String[] panelRoles = {"PAGE", "CARD", "CARD_HOVER", "CARD_ACTIVE", "EDGE", "FIELD", "ACCENT",
			"ACCENT_DEEP", "TEXT", "TEXT_DIM", "TEXT_FAINT", "GOOD", "BAD", "BUTTON", "BUTTON_TOP",
			"BUTTON_HOVER", "BUTTON_PRESSED", "BUTTON_EDGE", "PRIMARY", "PRIMARY_TOP", "PRIMARY_HOVER",
			"PRIMARY_PRESSED", "PRIMARY_TEXT", "DISABLED_TEXT", "BADGE", "FOCUS"};
		for (Palette p : Palette.all())
		{
			Set<Color> seen = new java.util.HashSet<>();
			for (String role : panelRoles)
			{
				assertTrue(p.name() + " " + role + " repeats a colour", seen.add(p.get(role)));
			}
		}
	}

	/**
	 * Bronze is the look the plugin has always had and must not change, so the pairs it has always had below the mark are
	 * named here and nothing else may be: the search field's placeholder, and the quiet captions on cards.
	 */
	private static boolean bronzeException(Palette p, Pair pair)
	{
		if (p != Palette.all()[0])
		{
			return false;
		}
		return pair.text.equals("FIELD_DIM") && pair.back.equals("FIELD_FILL")
			|| pair.text.equals("TEXT_FAINT") && (pair.back.equals("CARD") || pair.back.equals("PAGE"));
	}

	@Test
	public void bodyTextReadsAtAAInEveryTheme()
	{
		StringBuilder failures = new StringBuilder();
		for (Palette p : Palette.all())
		{
			for (Pair pair : body())
			{
				double got = worst(p, pair.text, pair.back);
				if (got < pair.needed && !bronzeException(p, pair))
				{
					failures.append(String.format("%s: %s on %s is %.2f, needs %.1f%n", p.name(), pair.text, pair.back, got, pair.needed));
				}
			}
			for (Pair pair : quiet())
			{
				double got = worst(p, pair.text, pair.back);
				if (got < pair.needed && !bronzeException(p, pair))
				{
					failures.append(String.format("%s: %s on %s is %.2f, needs %.1f%n", p.name(), pair.text, pair.back, got, pair.needed));
				}
			}
		}
		assertEquals("\n" + failures, "", failures.toString());
	}

	@Test
	public void highContrastHasNoTranslucencyBehindText()
	{
		Palette p = Palette.of(ThemeName.HIGH_CONTRAST);
		List<String> behindText = java.util.Arrays.asList("PANEL", "PANEL_ACTIVE", "FIELD_FILL", "FIELD_FOCUSED", "PLATE",
			"FLOOR_FILL", "FLOOR_HOVER", "BUTTON_HOVER_MAP", "CHIP", "RESULT_BUTTON", "CARD_BUTTON");
		for (String role : behindText)
		{
			assertEquals("High contrast " + role + " must be solid", 255, p.get(role).getAlpha());
		}
		assertTrue(p.edgeWidth() >= 2f);
		assertTrue("white text on black", worst(p, "TEXT", "PANEL") >= 15);
	}

	@Test
	public void bronzeKeepsTheOriginalColours()
	{
		Palette p = Palette.of(ThemeName.BRONZE);
		assertEquals(new Color(24, 21, 17, 238), p.get("PANEL"));
		assertEquals(new Color(255, 214, 120), p.get("ACCENT"));
		assertEquals(new Color(235, 222, 190), p.get("TEXT"));
		assertEquals(new Color(43, 39, 33), p.get("CARD"));
		assertEquals(new Color(30, 28, 25), p.get("PAGE"));
		assertEquals(1f, p.edgeWidth(), 0f);
	}

	/**
	 * Every colour the code asks for by name ({@code colour("...")}, {@code get("...")} on a palette) is a name every theme has, so a
	 * misspelt name cannot draw nothing.
	 */
	@Test
	public void everyNameTheCodeAsksIsInEveryTheme() throws java.io.IOException
	{
		// A call, and every name in capitals written in it: colour("TEXT"), colour(on ? "ACCENT" : "TEXT"), p.get("PAGE").
		java.util.regex.Pattern call = java.util.regex.Pattern.compile("\\b(?:colour|p\\.get|to\\.get)\\(([^()]*)\\)");
		java.util.regex.Pattern asked = java.util.regex.Pattern.compile("\"([A-Z_]{2,})\"");
		Set<String> names = new java.util.TreeSet<>();
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(java.nio.file.Paths.get("src", "main", "java")))
		{
			for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator)
			{
				java.util.regex.Matcher m = call.matcher(new String(java.nio.file.Files.readAllBytes(file),
					java.nio.charset.StandardCharsets.UTF_8));
				while (m.find())
				{
					for (java.util.regex.Matcher name = asked.matcher(m.group(1)); name.find(); )
					{
						names.add(name.group(1));
					}
				}
			}
		}
		assertTrue("the code asks for colours by name", names.size() > 40);
		assertTrue("names in a choice are read too", names.contains("PRIMARY_PRESSED") && names.contains("BUTTON_PRESSED"));
		for (Palette p : Palette.all())
		{
			for (String name : names)
			{
				assertNotNull(p.name() + " has no " + name, p.get(name));
			}
		}
	}
}
