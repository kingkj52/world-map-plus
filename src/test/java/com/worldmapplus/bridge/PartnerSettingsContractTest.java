package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.WorldMapPlusConfig;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

/**
 * The settings a partner plugin reads, from the group {@code worldmapplus}, through ConfigManager. Part two has no settings of its own,
 * so these key names (see docs/COMPAT.md, "Settings contract") are part of Plugin API v2: renaming one breaks every partner, and this
 * test fails when someone does.
 */
public class PartnerSettingsContractTest
{
	/** Key name to the type its value is read as. */
	private static Map<String, Class<?>> contract()
	{
		Map<String, Class<?>> keys = new HashMap<>();
		keys.put("shareCodesInChat", boolean.class);
		keys.put("shareReplaceOwnLines", boolean.class);
		keys.put("typingGuide", boolean.class);
		keys.put("typingGuidePosition", null);
		keys.put("theme", null);
		keys.put("animations", null);
		keys.put("sounds", boolean.class);
		keys.put("showOtherPluginPoints", boolean.class);
		keys.put("hiddenMapSources", String.class);
		keys.put("syncGameMap", boolean.class);
		keys.put("showResultsPanel", boolean.class);
		keys.put("showHotspots", boolean.class);
		keys.put("showDropTables", boolean.class);
		keys.put("hideFinishedQuests", boolean.class);
		keys.put("markUnusable", boolean.class);
		keys.put("onlyUsable", boolean.class);
		keys.put("colourQuestsByProgress", boolean.class);
		keys.put("showNpcs", boolean.class);
		keys.put("npcMinZoom", int.class);
		keys.put("npcRadius", int.class);
		keys.put("npcFilter", String.class);
		keys.put("npcNames", boolean.class);
		keys.put("npcNameZoom", int.class);
		keys.put("showRegions", boolean.class);
		keys.put("showGrid", boolean.class);
		keys.put("showWilderness", boolean.class);
		keys.put("showTrail", boolean.class);
		keys.put("trailFade", double.class);
		return keys;
	}

	@Test
	public void theGroupNameIsTheOnePartnersAreTold()
	{
		assertEquals("worldmapplus", WorldMapPlusConfig.GROUP);
		assertEquals(WorldMapPlusConfig.GROUP, Partners.SETTINGS_GROUP);
		assertEquals("worldmapplus", WorldMapPlusConfig.class.getAnnotation(ConfigGroup.class).value());
	}

	@Test
	public void everyContractKeyStillExistsUnderItsName()
	{
		Map<String, Method> byKey = new HashMap<>();
		for (Method method : WorldMapPlusConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				byKey.put(item.keyName(), method);
			}
		}
		for (Map.Entry<String, Class<?>> key : contract().entrySet())
		{
			Method method = byKey.get(key.getKey());
			assertNotNull("settings key " + key.getKey() + " is part of the partner contract and was renamed or removed", method);
			if (key.getValue() != null)
			{
				assertEquals("the type of " + key.getKey(), key.getValue(), method.getReturnType());
			}
			assertTrue(method.getName().equals(key.getKey()));
		}
	}
}
