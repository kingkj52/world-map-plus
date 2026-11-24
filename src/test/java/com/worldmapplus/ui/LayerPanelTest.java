package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.layers.PoiCategory;
import com.worldmapplus.prefs.Settings;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LayerPanelTest
{
	private final Map<String, Object> written = new HashMap<>();

	private final Settings settings = new Settings(null)
	{
		@Override
		public void setBoolean(String key, boolean value)
		{
			written.put(key, value);
		}

		@Override
		public void setString(String key, String value)
		{
			written.put(key, value);
		}
	};

	private static List<PoiCategory> categories(int count)
	{
		List<PoiCategory> list = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			list.add(new PoiCategory("c" + i, "Category " + i, "C", Color.RED));
		}
		return list;
	}

	private static WorldMapPlusConfig config(String poiLayers)
	{
		return new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return poiLayers;
			}
		};
	}

	private static final Rectangle ANCHOR = new Rectangle(880, 400, 28, 28);

	@Test
	public void rowsAreTheSettingsThenAHeadingThenEachPointKind()
	{
		LayerPanel panel = new LayerPanel(categories(3));
		List<LayerPanel.Row> rows = panel.getRows();
		assertEquals(8 + 1 + 3, rows.size());
		assertEquals("showNpcs", rows.get(0).getKey());
		assertTrue(rows.get(8).isHeading());
		assertEquals("showPins", rows.get(7).getKey());
		assertEquals("showLabels", rows.get(6).getKey());
		assertEquals("poi:c0", rows.get(9).getKey());
	}

	@Test
	public void noPointKindsMeansNoHeading()
	{
		assertEquals(8, new LayerPanel(new ArrayList<>()).getRows().size());
	}

	@Test
	public void togglingAPointKindRewritesTheLayerList()
	{
		LayerPanel panel = new LayerPanel(categories(3));
		LayerPanel.Row row = panel.getRows().get(10);
		assertEquals("poi:c1", row.getKey());
		WorldMapPlusConfig on = config("c0,c1");
		assertTrue(row.isOn(on));
		row.toggle(settings, on);
		assertEquals("c0", written.get("poiLayers"));

		WorldMapPlusConfig off = config("c0");
		assertFalse(row.isOn(off));
		row.toggle(settings, off);
		assertEquals("c0,c1", written.get("poiLayers"));
	}

	@Test
	public void eachKindOfGameIconHasARowThatHidesAndShowsIt()
	{
		LayerPanel panel = new LayerPanel(categories(2), java.util.Arrays.asList("Bank", "Water source"));
		List<LayerPanel.Row> rows = panel.getRows();
		// Eight settings, the heading and two point kinds, then the heading of the icons and a row each.
		assertEquals(8 + 1 + 2 + 1 + 2, rows.size());
		assertTrue(rows.get(11).isHeading());
		LayerPanel.Row water = rows.get(13);
		assertEquals("gameicon:Water source", water.getKey());
		WorldMapPlusConfig shown = new WorldMapPlusConfig()
		{
			@Override
			public String hiddenGameIcons()
			{
				return "Bank";
			}
		};
		assertTrue("a kind that is not in the hidden list is on", water.isOn(shown));
		assertFalse(rows.get(12).isOn(shown));
		water.toggle(settings, shown);
		assertEquals("Bank|Water source", written.get("hiddenGameIcons"));
		rows.get(12).toggle(settings, shown);
		assertEquals("", written.get("hiddenGameIcons"));
	}

	@Test
	public void togglingASettingFlipsIt()
	{
		LayerPanel panel = new LayerPanel(categories(0));
		LayerPanel.Row wilderness = panel.getRows().get(2);
		assertEquals("showWilderness", wilderness.getKey());
		wilderness.toggle(settings, config(""));
		assertEquals(Boolean.TRUE, written.get("showWilderness"));
	}

	@Test
	public void headingsCannotBeClicked()
	{
		LayerPanel panel = new LayerPanel(categories(3));
		panel.toggle();
		panel.layout(ANCHOR, new Rectangle(0, 0, 900, 700));
		Rectangle b = panel.getBounds();
		int headingY = b.y + LayerPanel.PADDING + LayerPanel.TITLE_HEIGHT + 8 * LayerPanel.ROW_HEIGHT + 4;
		assertNull(panel.hit(b.x + 20, headingY));
		assertNotNull(panel.hit(b.x + 20, headingY + LayerPanel.ROW_HEIGHT));
	}

	@Test
	public void aShortMapFlowsTheRowsIntoMoreColumns()
	{
		LayerPanel panel = new LayerPanel(categories(14));
		panel.toggle();
		panel.layout(ANCHOR, new Rectangle(0, 0, 900, 700));
		int tall = panel.getBounds().width;
		// A short map, with its buttons inside it.
		panel.layout(new Rectangle(880, 230, 28, 28), new Rectangle(0, 0, 900, 260));
		assertTrue(panel.getBounds().width > tall);
		assertTrue(new Rectangle(0, 0, 900, 260).contains(panel.getBounds()));

		// A row in the second column can be hit.
		Rectangle b = panel.getBounds();
		LayerPanel.Row hit = panel.hit(b.x + LayerPanel.COLUMN_WIDTH + 20,
			b.y + LayerPanel.PADDING + LayerPanel.TITLE_HEIGHT + LayerPanel.ROW_HEIGHT + 4);
		assertNotNull(hit);
	}

	@Test
	public void aClosedPanelHasNothingToHit()
	{
		LayerPanel panel = new LayerPanel(categories(2));
		panel.layout(ANCHOR, new Rectangle(0, 0, 900, 700));
		assertNull(panel.hit(700, 400));
		assertFalse(panel.contains(700, 400));
	}
}
