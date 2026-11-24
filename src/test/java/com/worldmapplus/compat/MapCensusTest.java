package com.worldmapplus.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.bridge.ExternalMarker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

public class MapCensusTest
{
	private static final MapCensus.PluginInfo GOLEMS = new MapCensus.PluginInfo("Golems Don't Die",
		"com.golemsdontdie.GolemsDontDiePlugin", true);
	private static final MapCensus.PluginInfo QUEST = new MapCensus.PluginInfo("Quest Helper",
		"com.questhelper.QuestHelperPlugin", true);
	private static final MapCensus.PluginInfo SAILING = new MapCensus.PluginInfo("Sailing",
		"net.runelite.client.plugins.sailing.SailingPlugin", true);
	private static final MapCensus.PluginInfo OFF = new MapCensus.PluginInfo("Mahogany Homes",
		"net.mahogany.MahoganyHomesPlugin", false);
	private static final MapCensus.PluginInfo OURS = new MapCensus.PluginInfo("World Map Plus",
		"com.worldmapplus.WorldMapPlusPlugin", true);

	private final List<MapCensus.OverlayInfo> overlays = new ArrayList<>();
	private List<MapCensus.PluginInfo> plugins = new ArrayList<>(Arrays.asList(GOLEMS, QUEST, OFF, OURS));

	private MapCensus census()
	{
		return new MapCensus(() -> plugins, overlays::forEach, "com.worldmapplus");
	}

	private static ExternalMarker point(String className)
	{
		return ExternalMarker.fromGameMap(className, 3200.5, 3200.5, "");
	}

	@Test
	public void pointsAreAttributedToThePluginWhosePackageTheirClassIsIn()
	{
		MapCensus census = census();
		MapCensus.Snapshot snapshot = census.take(0, true, Arrays.asList(
			point("com.golemsdontdie.GolemMapPoints$CellPoint"), point("com.golemsdontdie.GolemMapPoints$CellPoint"),
			point("net.runelite.client.ui.overlay.worldmap.WorldMapPoint")));
		MapCensus.Entry golems = null;
		MapCensus.Entry others = null;
		for (MapCensus.Entry entry : snapshot.getEntries())
		{
			if (entry.getName().equals("Golems Don't Die"))
			{
				golems = entry;
			}
			if (entry.getName().equals(MapCensus.UNKNOWN))
			{
				others = entry;
			}
		}
		assertNotNull(golems);
		assertEquals(2, golems.getPoints());
		assertNotNull(others);
		assertEquals(1, others.getPoints());
		assertTrue(snapshot.needsGameMap());
		assertEquals("Golems Don't Die", census.ownerOf("com.golemsdontdie.Whatever"));
		assertEquals(MapCensus.UNKNOWN, census.ownerOf("org.nobody.Point"));
	}

	@Test
	public void aPluginThatIsOffOrIsThisOneIsNeverNamed()
	{
		MapCensus census = census();
		census.take(0, true, Collections.emptyList());
		assertEquals(MapCensus.UNKNOWN, census.ownerOf("net.mahogany.MahoganyHomesWorldPoint"));
		assertEquals(MapCensus.UNKNOWN, census.ownerOf("com.worldmapplus.bridge.Anything"));
	}

	@Test
	public void theClosestPackageWinsWhenTwoPluginsShareARoot()
	{
		plugins.add(new MapCensus.PluginInfo("Golems Extra", "com.golemsdontdie.extra.ExtraPlugin", true));
		MapCensus census = census();
		census.take(0, true, Collections.emptyList());
		assertEquals("Golems Extra", census.ownerOf("com.golemsdontdie.extra.Thing"));
		assertEquals("Golems Don't Die", census.ownerOf("com.golemsdontdie.Thing"));
	}

	@Test
	public void anOverlayHookedToTheWorldMapIsFoundButOneForTheMinimapIsNot()
	{
		overlays.add(new MapCensus.OverlayInfo("Quest Helper", "com.questhelper.overlays.QuestHelperMinimapOverlay",
			Collections.emptyList(), "ALWAYS_ON_TOP"));
		overlays.add(new MapCensus.OverlayInfo("Golems Don't Die", "com.golemsdontdie.GolemMinimapOverlay",
			Collections.emptyList(), "ALWAYS_ON_TOP"));
		overlays.add(new MapCensus.OverlayInfo("Sailing", "com.sailing.SailingMapOverlay",
			Collections.singletonList(InterfaceID.WORLDMAP << 16 | 0xffff), "MANUAL"));
		plugins.add(new MapCensus.PluginInfo("Sailing", "com.sailing.SailingPlugin", true));
		MapCensus.Snapshot snapshot = census().take(0, true, Collections.emptyList());
		boolean sailing = false;
		boolean golems = false;
		for (MapCensus.Entry entry : snapshot.getEntries())
		{
			sailing |= entry.getName().equals("Sailing") && entry.isHooksMap();
			golems |= entry.getName().equals("Golems Don't Die");
		}
		assertTrue(sailing);
		assertFalse("a minimap overlay says nothing about the world map", golems);
		assertFalse("an overlay and no points does not need the game map kept with this one", snapshot.needsGameMap());
	}

	@Test
	public void aPluginKnownToDrawOnTheGameMapItselfIsReportedWithWhatItDraws()
	{
		MapCensus.Snapshot snapshot = census().take(0, true, Collections.emptyList());
		MapCensus.Entry drawer = snapshot.firstDrawer();
		assertNotNull(drawer);
		assertEquals("Quest Helper", drawer.getName());
		assertTrue(drawer.getDrawsOnMap().contains("lines"));
	}

	@Test
	public void anAlwaysOnTopWorldMapOverlayMarksItsPluginAsDrawing()
	{
		plugins.add(new MapCensus.PluginInfo("Port Tasks 2", "com.nucleon.porttasks2.PortTasksPlugin", true));
		overlays.add(new MapCensus.OverlayInfo("Port Tasks 2", "com.nucleon.porttasks2.PortTasksMapOverlay",
			Collections.emptyList(), "ALWAYS_ON_TOP"));
		overlays.add(new MapCensus.OverlayInfo("Port Tasks 2", "com.nucleon.porttasks2.PortTasksMiniMapOverlay",
			Collections.emptyList(), "ALWAYS_ON_TOP"));
		MapCensus.Snapshot snapshot = census().take(0, true, Collections.emptyList());
		boolean found = false;
		for (MapCensus.Entry entry : snapshot.getEntries())
		{
			found |= entry.getName().equals("Port Tasks 2") && entry.drawsItself();
		}
		assertTrue(found);
	}

	@Test
	public void aPluginThatHadPointsEarlierStillNeedsTheGameMapWhenItHasNoneNow()
	{
		MapCensus census = census();
		assertFalse("nothing has had points yet", census.take(0, true, Collections.emptyList()).needsGameMap());
		assertFalse(census.hasHadPoints());
		MapCensus.Snapshot first = census.take(2000, true, Collections.singletonList(point("com.golemsdontdie.GolemMapPoints$CellPoint")));
		assertTrue(first.hasPointsNow());
		MapCensus.Snapshot later = census.take(4000, true, Collections.emptyList());
		assertFalse("it has no points at this new place", later.hasPointsNow());
		assertTrue("but it had some elsewhere, so the game map is still kept with this one", later.needsGameMap());
		assertTrue(census.hasHadPoints());
		assertTrue("also after the map was closed and opened again", census.take(6000, false, Collections.emptyList()).getEntries().isEmpty());
		assertTrue(census.take(8000, true, Collections.emptyList()).needsGameMap());
	}

	@Test
	public void aPluginThatIsSwitchedOffNoLongerCounts()
	{
		MapCensus census = census();
		census.take(0, true, Collections.singletonList(point("com.golemsdontdie.GolemMapPoints$CellPoint")));
		assertTrue(census.hasHadPoints());
		plugins = new ArrayList<>(Arrays.asList(QUEST, OURS));
		assertFalse(census.take(5000, true, Collections.emptyList()).needsGameMap());
		assertFalse(census.hasHadPoints());
	}

	@Test
	public void pointsThatAreNotShownAreStillRememberedAndAnOverlayAloneIsNot()
	{
		MapCensus census = census();
		census.take(0, true, Collections.emptyList());
		census.remember(Collections.emptyList());
		assertFalse(census.hasHadPoints());
		census.remember(Collections.singletonList(point("com.golemsdontdie.GolemMapPoints$CellPoint")));
		assertTrue(census.hasHadPoints());
		assertTrue(census.take(2000, true, Collections.emptyList()).needsGameMap());
	}

	@Test
	public void nothingIsReadAndNothingFoundWhileInactive()
	{
		int[] calls = new int[1];
		MapCensus census = new MapCensus(() ->
		{
			calls[0]++;
			return plugins;
		}, sink ->
		{
			calls[0]++;
		}, "com.worldmapplus");
		MapCensus.Snapshot snapshot = census.take(0, false, Collections.singletonList(point("com.golemsdontdie.X")));
		assertTrue(snapshot.getEntries().isEmpty());
		assertFalse(snapshot.needsGameMap());
		assertEquals("the lists are not even read", 0, calls[0]);
	}

	@Test
	public void theListsAreReadAboutOnceASecondNoMatterHowOftenItIsAsked()
	{
		int[] calls = new int[1];
		MapCensus census = new MapCensus(() ->
		{
			calls[0]++;
			return plugins;
		}, sink ->
		{
		}, "com.worldmapplus");
		for (int i = 0; i < 100; i++)
		{
			census.take(i * 10, true, Collections.emptyList());
		}
		assertTrue("read " + calls[0] + " times in a second", calls[0] <= 2);
		census.take(5000, true, Collections.emptyList());
		assertTrue(calls[0] >= 2);
	}

	@Test
	public void aListThatChangesUnderItIsNotAnError()
	{
		MapCensus census = new MapCensus(() ->
		{
			throw new java.util.ConcurrentModificationException();
		}, sink ->
		{
		}, "com.worldmapplus");
		assertNull(census.take(0, true, Collections.emptyList()).firstDrawer());
	}

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
