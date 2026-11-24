package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class HudPanelsTest
{
	private static final Rectangle MAP = new Rectangle(0, 0, 600, 400);

	@Test
	public void aPanelOverACornerOfTheMapIsLeftToTheGame()
	{
		Rectangle inventory = new Rectangle(450, 150, 190, 260);
		List<Rectangle> kept = HudOverlay.panelsThatAreNotTheWholeMap(Arrays.asList(inventory), MAP);
		assertEquals(1, kept.size());
		assertEquals(inventory, kept.get(0));
	}

	@Test
	public void aLayerThatCoversTheWholeMapIsNotAPanelOrTheMapCouldNeverBeClicked()
	{
		Rectangle layer = new Rectangle(-10, -10, 700, 500);
		Rectangle mostOfIt = new Rectangle(0, 0, 600, 250);
		List<Rectangle> kept = HudOverlay.panelsThatAreNotTheWholeMap(Arrays.asList(layer, mostOfIt), MAP);
		assertTrue(kept.isEmpty());
	}

	@Test
	public void aPanelBesideTheMapIsKeptHarmlessly()
	{
		Rectangle beside = new Rectangle(700, 0, 100, 400);
		assertFalse(HudOverlay.panelsThatAreNotTheWholeMap(Arrays.asList(beside), MAP).isEmpty());
	}

	@Test
	public void theSettingChangesTheFloatingMapAtOnce()
	{
		HudState state = new HudState(new com.worldmapplus.WorldMapPlusConfig()
		{
		});
		assertTrue("starts click-through by default", state.isClickThrough());
		state.setClickThrough(false);
		assertFalse(state.isClickThrough());
		state.toggleClickThrough();
		assertTrue(state.isClickThrough());
	}
}
