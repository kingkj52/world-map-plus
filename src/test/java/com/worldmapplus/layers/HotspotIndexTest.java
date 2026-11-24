package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.view.ViewTransform;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HotspotIndexTest
{
	private static Hotspot spot(int map, double x, double y, int radius, String name)
	{
		return new Hotspot(map, x, y, radius, HoverCard.builder(name).build(), null);
	}

	private static ViewTransform view(double scale)
	{
		return new ViewTransform(3200, 3200, scale, 0, 0, 800, 600);
	}

	@Test
	public void findsAHotspotWithinItsRadius()
	{
		Hotspot a = spot(0, 3210, 3200, 12, "a");
		HotspotIndex index = new HotspotIndex(java.util.Collections.singletonList(a));
		ViewTransform v = view(4.0);
		double px = v.screenX(3210);
		double py = v.screenY(3200);
		assertSame(a, index.find(0, v, px + 5, py - 5));
	}

	@Test
	public void missesOutsideTheRadius()
	{
		Hotspot a = spot(0, 3210, 3200, 12, "a");
		HotspotIndex index = new HotspotIndex(java.util.Collections.singletonList(a));
		ViewTransform v = view(4.0);
		assertNull(index.find(0, v, v.screenX(3210) + 30, v.screenY(3200)));
	}

	@Test
	public void theNearestOfSeveralWins()
	{
		Hotspot a = spot(0, 3210, 3200, 20, "a");
		Hotspot b = spot(0, 3212, 3200, 20, "b");
		List<Hotspot> all = new ArrayList<>();
		all.add(a);
		all.add(b);
		HotspotIndex index = new HotspotIndex(all);
		ViewTransform v = view(4.0);
		assertSame(b, index.find(0, v, v.screenX(3212) + 1, v.screenY(3200)));
		assertSame(a, index.find(0, v, v.screenX(3210) - 1, v.screenY(3200)));
	}

	@Test
	public void hotspotsOnOtherMapsAreIgnored()
	{
		Hotspot a = spot(7, 3210, 3200, 12, "a");
		HotspotIndex index = new HotspotIndex(java.util.Collections.singletonList(a));
		ViewTransform v = view(4.0);
		assertNull(index.find(0, v, v.screenX(3210), v.screenY(3200)));
		assertNotNull(index.find(7, v, v.screenX(3210), v.screenY(3200)));
	}

	@Test
	public void theRadiusIsInPixelsSoItHoldsWhenZoomedOut()
	{
		Hotspot a = spot(0, 3300, 3300, 10, "a");
		HotspotIndex index = new HotspotIndex(java.util.Collections.singletonList(a));
		ViewTransform v = view(0.1);
		assertSame(a, index.find(0, v, v.screenX(3300) + 6, v.screenY(3300)));
	}

	@Test
	public void anOversizedRadiusIsCapped()
	{
		Hotspot a = spot(0, 3210, 3200, 500, "a");
		HotspotIndex index = new HotspotIndex(java.util.Collections.singletonList(a));
		ViewTransform v = view(4.0);
		assertNull(index.find(0, v, v.screenX(3210) + 100, v.screenY(3200)));
	}

	@Test
	public void emptyIndexFindsNothing()
	{
		assertNull(HotspotIndex.empty().find(0, view(4), 400, 300));
		assertEquals(0, HotspotIndex.empty().size());
	}

	@Test
	public void hotspotsInDifferentCellsAreStillFoundAtTheirOwnPosition()
	{
		List<Hotspot> all = new ArrayList<>();
		for (int i = 0; i < 100; i++)
		{
			all.add(spot(0, 3000 + i * 7, 3000 + (i % 13) * 11, 8, "s" + i));
		}
		HotspotIndex index = new HotspotIndex(all);
		ViewTransform v = new ViewTransform(3300, 3050, 6.0, 0, 0, 800, 600);
		for (Hotspot h : all)
		{
			if (v.contains(v.screenX(h.getX()), v.screenY(h.getY())))
			{
				Hotspot found = index.find(0, v, v.screenX(h.getX()), v.screenY(h.getY()));
				assertNotNull(found);
				assertTrue(Math.hypot(found.getX() - h.getX(), found.getY() - h.getY()) < 1.5);
			}
		}
	}
}
