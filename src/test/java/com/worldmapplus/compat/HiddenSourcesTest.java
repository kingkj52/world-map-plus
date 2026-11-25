package com.worldmapplus.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HiddenSourcesTest
{
	@Test
	public void hiddenNamesAreKeptAndToggledAsAList()
	{
		String text = HiddenSources.toggled("", "Golems Don't Die", true);
		text = HiddenSources.toggled(text, "Sailing", true);
		assertTrue(HiddenSources.isHidden(text, "Golems Don't Die"));
		assertTrue(HiddenSources.isHidden(text, "Sailing"));
		text = HiddenSources.toggled(text, "Sailing", false);
		assertFalse(HiddenSources.isHidden(text, "Sailing"));
		assertEquals("Golems Don't Die", text);
		assertTrue(HiddenSources.parse(null).isEmpty());
		assertEquals(2, HiddenSources.parse(" a |b|| ").size());
	}

	@Test
	public void theSettingCannotGrowWithoutEnd()
	{
		StringBuilder many = new StringBuilder();
		for (int i = 0; i < 1000; i++)
		{
			many.append("p").append(i).append('|');
		}
		assertEquals(HiddenSources.MOST, HiddenSources.parse(many.toString()).size());
	}
}
