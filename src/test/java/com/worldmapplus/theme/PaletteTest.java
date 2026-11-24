package com.worldmapplus.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The themes are complete, their colours can be told apart, and their text can be read (WCAG contrast). */
public class PaletteTest
{
	/** A text colour on a background, both by role. Translucent backgrounds are judged over the worse of black and white. */
	private static final class Pair
	{
		final Role text;
		final Role back;
		final double needed;

		Pair(Role text, Role back, double needed)
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
		Role[] mapBacks = {Role.PANEL, Role.PANEL_ACTIVE, Role.FIELD_FILL, Role.FIELD_FOCUSED, Role.PLATE, Role.FLOOR_FILL};
		for (Role back : mapBacks)
		{
			pairs.add(new Pair(Role.TEXT, back, 4.5));
		}
		pairs.add(new Pair(Role.TEXT_DIM, Role.PANEL, 4.5));
		pairs.add(new Pair(Role.ACCENT, Role.PANEL, 4.5));
		pairs.add(new Pair(Role.HINT, Role.PANEL, 4.5));
		pairs.add(new Pair(Role.FIELD_DIM, Role.FIELD_FILL, 4.5));
		pairs.add(new Pair(Role.MISSING, Role.FIELD_FILL, 4.5));
		pairs.add(new Pair(Role.TICK_MAP, Role.PANEL, 3.0));
		Role[] panelBacks = {Role.PAGE, Role.CARD, Role.CARD_HOVER, Role.CARD_ACTIVE, Role.FIELD, Role.BUTTON, Role.BUTTON_TOP,
			Role.BUTTON_HOVER};
		for (Role back : panelBacks)
		{
			pairs.add(new Pair(Role.TEXT, back, 4.5));
		}
		pairs.add(new Pair(Role.TEXT_DIM, Role.CARD, 4.5));
		pairs.add(new Pair(Role.TEXT_DIM, Role.PAGE, 4.5));
		pairs.add(new Pair(Role.ACCENT, Role.CARD, 4.5));
		pairs.add(new Pair(Role.ACCENT, Role.PAGE, 4.5));
		pairs.add(new Pair(Role.ACCENT, Role.BADGE, 4.5));
		pairs.add(new Pair(Role.ACCENT, Role.CARD_ACTIVE, 4.5));
		pairs.add(new Pair(Role.GOOD, Role.CARD, 4.5));
		pairs.add(new Pair(Role.BAD, Role.CARD, 4.5));
		pairs.add(new Pair(Role.PRIMARY_TEXT, Role.PRIMARY, 4.5));
		pairs.add(new Pair(Role.PRIMARY_TEXT, Role.PRIMARY_TOP, 4.5));
		pairs.add(new Pair(Role.PRIMARY_TEXT, Role.PRIMARY_HOVER, 4.5));
		return pairs;
	}

	/** Roles that are quiet on purpose, held to the 3 to 1 of large text and interface parts. */
	private static List<Pair> quiet()
	{
		List<Pair> pairs = new ArrayList<>();
		pairs.add(new Pair(Role.TEXT_FAINT, Role.PAGE, 3.0));
		pairs.add(new Pair(Role.TEXT_FAINT, Role.CARD, 3.0));
		pairs.add(new Pair(Role.FOCUS, Role.PAGE, 3.0));
		pairs.add(new Pair(Role.FOCUS, Role.CARD, 3.0));
		pairs.add(new Pair(Role.BORDER, Role.PANEL, 1.5));
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
	static double worst(Palette p, Role text, Role back)
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
			Palette p = Palettes.of(name);
			assertNotNull(p);
			assertEquals(name.toString(), p.name());
			for (Role role : Role.values())
			{
				assertNotNull(name + " " + role, p.get(role));
			}
		}
		assertEquals(4, Palettes.all().length);
	}

	/** The side panel is moved between themes by colour, so no two of its roles may share a colour in one theme. */
	@Test
	public void panelRolesCanBeToldApartByColour()
	{
		Role[] panelRoles = {Role.PAGE, Role.CARD, Role.CARD_HOVER, Role.CARD_ACTIVE, Role.EDGE, Role.FIELD, Role.ACCENT,
			Role.ACCENT_DEEP, Role.TEXT, Role.TEXT_DIM, Role.TEXT_FAINT, Role.GOOD, Role.BAD, Role.BUTTON, Role.BUTTON_TOP,
			Role.BUTTON_HOVER, Role.BUTTON_PRESSED, Role.BUTTON_EDGE, Role.PRIMARY, Role.PRIMARY_TOP, Role.PRIMARY_HOVER,
			Role.PRIMARY_PRESSED, Role.PRIMARY_TEXT, Role.DISABLED_TEXT, Role.BADGE, Role.FOCUS};
		for (Palette p : Palettes.all())
		{
			Set<Color> seen = new java.util.HashSet<>();
			for (Role role : panelRoles)
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
		if (p != Palettes.BRONZE)
		{
			return false;
		}
		return pair.text == Role.FIELD_DIM && pair.back == Role.FIELD_FILL
			|| pair.text == Role.TEXT_FAINT && (pair.back == Role.CARD || pair.back == Role.PAGE);
	}

	@Test
	public void bodyTextReadsAtAAInEveryTheme()
	{
		StringBuilder failures = new StringBuilder();
		for (Palette p : Palettes.all())
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
		Palette p = Palettes.HIGH_CONTRAST;
		EnumSet<Role> behindText = EnumSet.of(Role.PANEL, Role.PANEL_ACTIVE, Role.FIELD_FILL, Role.FIELD_FOCUSED, Role.PLATE,
			Role.FLOOR_FILL, Role.FLOOR_HOVER, Role.BUTTON_HOVER_MAP, Role.CHIP, Role.RESULT_BUTTON, Role.CARD_BUTTON);
		for (Role role : behindText)
		{
			assertEquals("High contrast " + role + " must be solid", 255, p.get(role).getAlpha());
		}
		assertTrue(p.edgeWidth() >= 2f);
		assertTrue("white text on black", worst(p, Role.TEXT, Role.PANEL) >= 15);
	}

	@Test
	public void bronzeKeepsTheOriginalColours()
	{
		Palette p = Palettes.BRONZE;
		assertEquals(new Color(24, 21, 17, 238), p.get(Role.PANEL));
		assertEquals(new Color(255, 214, 120), p.get(Role.ACCENT));
		assertEquals(new Color(235, 222, 190), p.get(Role.TEXT));
		assertEquals(new Color(43, 39, 33), p.get(Role.CARD));
		assertEquals(new Color(30, 28, 25), p.get(Role.PAGE));
		assertEquals(1f, p.edgeWidth(), 0f);
	}
}
