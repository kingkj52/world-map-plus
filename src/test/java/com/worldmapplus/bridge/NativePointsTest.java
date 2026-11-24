package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import org.junit.Test;

public class NativePointsTest
{
	private static final BufferedImage IMAGE = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);

	private final List<WorldMapPoint> points = new ArrayList<>();
	private int reads;

	/** Stands in for a point made by RuneLite's own World Map plugin. */
	static final class TestCorePoint extends WorldMapPoint
	{
		TestCorePoint(WorldPoint worldPoint, BufferedImage image)
		{
			super(worldPoint, image);
		}
	}

	private NativePoints nativePoints()
	{
		return new NativePoints(sink ->
		{
			reads++;
			for (WorldMapPoint point : new ArrayList<>(points))
			{
				sink.accept(point);
			}
		}, "com.worldmapplus.bridge.NativePointsTest$TestCore");
	}

	@Test
	public void aPointAnotherPluginAddedBecomesAMarkerAtItsTile()
	{
		WorldMapPoint point = new WorldMapPoint(new WorldPoint(3222, 3218, 0), IMAGE);
		point.setName("Alice");
		point.setTooltip("Alice | 99/99 hp");
		points.add(point);
		List<ExternalMarker> markers = nativePoints().markers(1000);
		assertEquals(1, markers.size());
		ExternalMarker m = markers.get(0);
		assertEquals(3222.5, m.getX(), 1e-9);
		assertEquals(3218.5, m.getY(), 1e-9);
		assertEquals("Alice", m.getName());
		assertEquals("Alice | 99/99 hp", m.getTooltip());
		assertNotNull(m.getIcon());
		assertEquals(IMAGE.getWidth(), m.getIcon().getWidth());
		assertEquals("a copy, so the sender cannot change it", true, m.getIcon() != IMAGE);
		assertNull(m.getAnchor());
		assertNull(m.getJumpTarget());
	}

	@Test
	public void theWorldMapPluginsOwnPointsAreLeftOutBecauseThisMapDrawsThem()
	{
		points.add(new TestCorePoint(new WorldPoint(3222, 3218, 0), IMAGE));
		points.add(new WorldMapPoint(new WorldPoint(3000, 3000, 0), IMAGE));
		List<ExternalMarker> markers = nativePoints().markers(1000);
		assertEquals(1, markers.size());
		assertEquals(3000.5, markers.get(0).getX(), 1e-9);
	}

	@Test
	public void aSubclassFromAnotherPluginIsKept()
	{
		points.add(new WorldMapPoint(new WorldPoint(1, 1, 0), IMAGE)
		{
		});
		assertEquals(1, nativePoints().markers(1000).size());
	}

	@Test
	public void anAnchorAnEdgeSnapAndAJumpTargetAreCarriedOver()
	{
		WorldMapPoint point = new WorldMapPoint(new WorldPoint(2000, 3000, 0), IMAGE);
		point.setImagePoint(new Point(4, 8));
		point.setSnapToEdge(true);
		point.setJumpOnClick(true);
		point.setTarget(new WorldPoint(2100, 3100, 0));
		points.add(point);
		ExternalMarker m = nativePoints().markers(1000).get(0);
		assertEquals(4, m.getAnchor()[0]);
		assertEquals(8, m.getAnchor()[1]);
		assertTrue(m.isSnapToEdge());
		assertEquals(new WorldPoint(2100, 3100, 0), m.getJumpTarget());

		WorldMapPoint plain = new WorldMapPoint(new WorldPoint(2000, 3000, 0), IMAGE);
		plain.setJumpOnClick(true);
		points.clear();
		points.add(plain);
		assertEquals("with no target it jumps to itself", new WorldPoint(2000, 3000, 0),
			nativePoints().markers(1000).get(0).getJumpTarget());
	}

	@Test
	public void aPointWithNoPlaceIsIgnored()
	{
		points.add(new WorldMapPoint(null, IMAGE));
		assertTrue(nativePoints().markers(1000).isEmpty());
		assertNull(NativePoints.convert(null, 0, NativePoints.CORE_PACKAGE));
	}

	@Test
	public void theManagerIsOnlyAskedOncePerRefreshPeriod()
	{
		points.add(new WorldMapPoint(new WorldPoint(1, 1, 0), IMAGE));
		NativePoints source = nativePoints();
		source.markers(1000);
		source.markers(1050);
		source.markers(1099);
		assertEquals(1, reads);
		points.add(new WorldMapPoint(new WorldPoint(2, 2, 0), IMAGE));
		assertEquals("a new point shows up at the next reading", 2, source.markers(1100).size());
		assertEquals(2, reads);
	}

	@Test
	public void aClockThatGoesBackwardsStillReads()
	{
		points.add(new WorldMapPoint(new WorldPoint(1, 1, 0), IMAGE));
		NativePoints source = nativePoints();
		source.markers(5000);
		source.markers(10);
		assertEquals(2, reads);
	}

	@Test
	public void aManagerCaughtMidChangeKeepsWhatWasShown()
	{
		List<WorldMapPoint> shown = new ArrayList<>();
		shown.add(new WorldMapPoint(new WorldPoint(1, 1, 0), IMAGE));
		boolean[] fail = {false};
		NativePoints source = new NativePoints(sink ->
		{
			if (fail[0])
			{
				throw new java.util.ConcurrentModificationException();
			}
			shown.forEach(sink);
		});
		assertEquals(1, source.markers(1000).size());
		fail[0] = true;
		assertEquals(1, source.markers(2000).size());
	}

	@Test
	public void noMoreThanTheCapAreShown()
	{
		for (int i = 0; i < NativePoints.MAX_POINTS + 50; i++)
		{
			points.add(new WorldMapPoint(new WorldPoint(1000 + i, 1000, 0), IMAGE));
		}
		assertEquals(NativePoints.MAX_POINTS, nativePoints().markers(1000).size());
		assertNotNull(nativePoints());
	}

	/** A point that counts how often its fields are read. */
	static final class CountingPoint extends WorldMapPoint
	{
		static int looks;

		CountingPoint(WorldPoint worldPoint, BufferedImage image)
		{
			super(worldPoint, image);
		}

		@Override
		public BufferedImage getImage()
		{
			looks++;
			return super.getImage();
		}
	}

	@Test
	public void thousandsOfPointsAreNotConvertedPastTheCap()
	{
		CountingPoint.looks = 0;
		for (int i = 0; i < 6000; i++)
		{
			points.add(new CountingPoint(new WorldPoint(1000 + i % 500, 1000 + i / 500, 0), IMAGE));
		}
		NativePoints source = nativePoints();
		List<ExternalMarker> markers = source.markers(1000);
		assertEquals(NativePoints.MAX_POINTS, markers.size());
		assertEquals("a point past the cap is not even asked for its picture", NativePoints.MAX_POINTS, CountingPoint.looks);
		assertEquals("one picture shared by every point is copied once", 1, source.copiesMade());
		assertEquals(1, reads);
	}

	@Test
	public void aPictureIsCopiedOncePerPictureAndNotOnEveryReading()
	{
		BufferedImage[] pictures = new BufferedImage[5];
		for (int i = 0; i < pictures.length; i++)
		{
			pictures[i] = new BufferedImage(8 + i, 8, BufferedImage.TYPE_INT_ARGB);
		}
		for (int i = 0; i < 2000; i++)
		{
			points.add(new WorldMapPoint(new WorldPoint(1000 + i % 300, 1000, 0), pictures[i % pictures.length]));
		}
		NativePoints source = nativePoints();
		List<ExternalMarker> first = source.markers(1000);
		assertEquals(5, source.copiesMade());
		assertTrue("the markers carry copies, never the sender's own picture", first.get(0).getIcon() != pictures[0]);
		for (long at = 1100; at < 2900; at += 100)
		{
			source.markers(at);
		}
		assertEquals("eighteen more readings copy nothing", 5, source.copiesMade());
		source.markers(3100);
		assertEquals("after the reuse time the pictures are copied again, so a changed picture is followed", 10, source.copiesMade());
	}

	@Test
	public void aPictureThatIsChangedInPlaceShowsUpAfterTheReuseTime()
	{
		BufferedImage picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		points.add(new WorldMapPoint(new WorldPoint(1, 1, 0), picture));
		NativePoints source = nativePoints();
		assertEquals(0, source.markers(1000).get(0).getIcon().getRGB(0, 0));
		picture.setRGB(0, 0, 0xFFFF0000);
		assertEquals("the copy is reused for now", 0, source.markers(1200).get(0).getIcon().getRGB(0, 0));
		assertEquals(0xFFFF0000, source.markers(1000 + NativePoints.ICON_REUSE_MILLIS).get(0).getIcon().getRGB(0, 0));
	}

	@Test
	public void theListOfCopiesIsBounded()
	{
		for (int i = 0; i < NativePoints.MAX_ICONS * 3; i++)
		{
			points.add(new WorldMapPoint(new WorldPoint(1000 + i % 300, 1000 + i / 300, 0), new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)));
		}
		NativePoints source = nativePoints();
		for (long at = 1000; at < 1600; at += 100)
		{
			assertEquals(NativePoints.MAX_POINTS, source.markers(at).size());
		}
		assertTrue(source.copiesMade() >= NativePoints.MAX_POINTS);
	}

	@Test
	public void aPictureTooBigForAnIconIsLeftOutAndTheDecisionIsKept()
	{
		BufferedImage big = new BufferedImage(ExternalOverlays.MAX_ICON + 1, 8, BufferedImage.TYPE_INT_ARGB);
		points.add(new WorldMapPoint(new WorldPoint(1, 1, 0), big));
		points.add(new WorldMapPoint(new WorldPoint(2, 2, 0), big));
		NativePoints source = nativePoints();
		List<ExternalMarker> markers = source.markers(1000);
		assertEquals(2, markers.size());
		assertNull(markers.get(0).getIcon());
		assertEquals(1, source.copiesMade());
	}

	/** Made as the Death Indicator makes its point; that plugin is not RuneLite's World Map plugin, so its point is kept. */
	@Test
	public void aDeathIndicatorStylePointIsKeptWithItsAnchorEdgeAndJumpAndTooltip()
	{
		WorldMapPoint death = new WorldMapPoint(new WorldPoint(3226, 3220, 0), null)
		{
		};
		BufferedImage picture = new BufferedImage(20, 32, BufferedImage.TYPE_INT_ARGB);
		death.setSnapToEdge(true);
		death.setJumpOnClick(true);
		death.setImage(picture);
		death.setImagePoint(new Point(10, 32));
		death.setTooltip("Death Location");
		points.add(death);
		TestCorePoint core = new TestCorePoint(new WorldPoint(3000, 3000, 0), IMAGE);
		points.add(core);
		// The real package name, as in production.
		List<ExternalMarker> markers = new NativePoints(sink -> new ArrayList<>(points).forEach(sink)).markers(1000);
		assertEquals(2, markers.size());
		ExternalMarker m = markers.get(0);
		assertEquals(3226.5, m.getX(), 1e-9);
		assertEquals("Death Location", m.getTooltip());
		assertEquals("", m.getName());
		assertTrue(m.isSnapToEdge());
		assertEquals(new WorldPoint(3226, 3220, 0), m.getJumpTarget());
		assertEquals(10, m.getAnchor()[0]);
		assertEquals(32, m.getAnchor()[1]);
		assertEquals(20, m.getIcon().getWidth());
		// Gone when its owner removes it.
		points.clear();
		assertTrue(new NativePoints(sink -> new ArrayList<>(points).forEach(sink)).markers(1000).isEmpty());
	}
}
