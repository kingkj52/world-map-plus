package com.worldmapplus.layers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** Where the game's icons are drawn, what the mouse hits, and how the layer treats categories, floors and crowds. */
public class GameIconLayerTest
{
	private static final double[] ZOOMS = {0.1, 0.2, 0.3, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8, 16, 32};
	private static GameIconSprites sprites;

	@BeforeClass
	public static void load()
	{
		sprites = GameIconSprites.load(136);
		assertEquals("the atlas holds a picture for every icon", 136, sprites.size());
	}

	private static GameIconSet.Spec spec(double x, double y, int picture, int floor, int category, int priority)
	{
		return new GameIconSet.Spec(0, x, y, picture, floor, category, priority == 0 ? 0 : GameIconScale.MINOR_PRIORITY, null);
	}

	private static ViewTransform view(double cx, double cy, double zoom)
	{
		return new ViewTransform(cx, cy, zoom, 0, 0, 800, 600);
	}

	@Test
	public void theHitAreaIsCentredOnTheDrawnPictureAndCoversAllOfIt()
	{
		for (int picture = 0; picture < sprites.size(); picture++)
		{
			List<GameIconSet.Spec> one = new ArrayList<>();
			one.add(spec(100.5, 200.5, picture, 0, 0, 0));
			GameIconLayer layer = new GameIconLayer(new GameIconSet(one), sprites);
			for (double zoom : ZOOMS)
			{
				ViewTransform view = view(100.5, 200.5, zoom);
				layer.layout(view, 0, 0);
				if (!GameIconScale.shown(zoom))
				{
					assertEquals(0, layer.drawnCount());
					continue;
				}
				assertEquals("picture " + picture + " at " + zoom, 1, layer.drawnCount());
				// The middle of what is drawn is the middle of the icon, to within a pixel.
				assertEquals(view.screenX(100.5), layer.drawnCenterX(0), 1.0);
				assertEquals(view.screenY(200.5), layer.drawnCenterY(0), 1.0);
				int side = layer.drawnSize();
				BufferedImage drawn = sprites.get(picture, side);
				assertNotNull(drawn);
				assertEquals(side, drawn.getWidth());
				// Every pixel that shows something is inside the hit area, and a pixel just outside the margin is not.
				for (int y = 0; y < side; y++)
				{
					for (int x = 0; x < side; x++)
					{
						if (drawn.getRGB(x, y) >>> 24 != 0)
						{
							assertEquals("pixel " + x + ", " + y + " of picture " + picture + " at " + zoom, 0,
								layer.hit(view, 0, 0, layer.drawnLeft(0) + x + 0.5, layer.drawnTop(0) + y + 0.5));
						}
					}
				}
				double reach = GameIconScale.hitSize(side) / 2.0;
				assertEquals(-1, layer.hit(view, 0, 0, layer.drawnCenterX(0) + reach + 1, layer.drawnCenterY(0)));
				assertEquals(-1, layer.hit(view, 0, 0, layer.drawnCenterX(0), layer.drawnCenterY(0) - reach - 1));
				assertEquals(0, layer.hit(view, 0, 0, layer.drawnCenterX(0) + reach - 0.5, layer.drawnCenterY(0) - reach + 0.5));
			}
		}
	}

	@Test
	public void theHitAreaIsNeverSmallerThanTheTouchSizeFarOut()
	{
		List<GameIconSet.Spec> one = new ArrayList<>();
		one.add(spec(100.5, 200.5, 3, 0, 0, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(one), sprites);
		ViewTransform far = view(100.5, 200.5, 0.2);
		layer.layout(far, 0, 0);
		assertEquals(GameIconScale.pictureSize(0.2, GameIconSprites.SIZE), layer.drawnSize());
		assertTrue(layer.hitSize() >= GameIconScale.MIN_HIT_SIZE);
		assertEquals(0, layer.hit(far, 0, 0, far.screenX(100.5) + 5, far.screenY(200.5) + 5));
	}

	@Test
	public void aRevealedKindIsDrawnAtAnyZoomUntilItEndsAndThenFadesAsBefore()
	{
		List<GameIconSet.Spec> specs = new ArrayList<>();
		// Minor icons (category 7) of which at least some are gone at a far zoom, and a minor icon of another kind.
		for (int i = 0; i < 200; i++)
		{
			specs.add(spec(3000 + i * 40.5, 3200.5, 3, 0, 7, 1));
		}
		specs.add(spec(3000.5, 3300.5, 4, 0, 8, 1));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(specs), sprites);
		ViewTransform far = new ViewTransform(7000, 3250, 0.06, 0, 0, 900, 600);
		layer.layout(far, 0, 0);
		int before = layer.drawnCount();
		layer.reveal(new int[] {7}, System.currentTimeMillis() + 60_000);
		layer.layout(far, 0, 0);
		int revealed = 0;
		for (int slot = 0; slot < layer.drawnCount(); slot++)
		{
			revealed += layer.drawnOn().category(layer.drawnIconIndex(slot)) == 7 ? 1 : 0;
		}
		assertTrue("the revealed kind is drawn: " + revealed + " of 200, before " + before, revealed > before);
		layer.reveal(new int[] {7}, System.currentTimeMillis() - 1);
		layer.layout(far, 0, 0);
		assertEquals(before, layer.drawnCount());
	}

	@Test
	public void aCategoryCanBeSwitchedOffAndOn()
	{
		List<GameIconSet.Spec> two = new ArrayList<>();
		two.add(spec(100.5, 200.5, 3, 0, 4, 0));
		two.add(spec(120.5, 200.5, 4, 0, 5, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(two), sprites);
		ViewTransform view = view(110.5, 200.5, 8);
		layer.layout(view, 0, 0);
		assertEquals(2, layer.drawnCount());
		layer.setCategoryShown(4, false);
		layer.layout(view, 0, 0);
		assertEquals(1, layer.drawnCount());
		assertEquals(5, layer.drawnOn().category(layer.drawnIconIndex(0)));
		assertEquals(-1, layer.hit(view, 0, 0, view.screenX(100.5), view.screenY(200.5)));
		layer.setCategoryShown(4, true);
		layer.layout(view, 0, 0);
		assertEquals(2, layer.drawnCount());
	}

	@Test
	public void anIconOfAnUpperFloorIsDrawnOnItsOwnFloorAndOnTheGroundFloor()
	{
		List<GameIconSet.Spec> two = new ArrayList<>();
		two.add(spec(100.5, 200.5, 3, 0, 0, 0));
		two.add(spec(120.5, 200.5, 4, 1, 0, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(two), sprites);
		ViewTransform view = view(110.5, 200.5, 8);
		layer.layout(view, 0, 0);
		assertEquals("the ground floor shows every icon of the map", 2, layer.drawnCount());
		layer.layout(view, 0, 1);
		assertEquals(1, layer.drawnCount());
		assertEquals(1, layer.drawnOn().floor(layer.drawnIconIndex(0)));
		layer.layout(view, 0, 2);
		assertEquals(0, layer.drawnCount());
	}

	@Test
	public void anUpperFloorIconThatRepeatsALowerOneIsDrawnOnceOnTheGroundFloor()
	{
		List<GameIconSet.Spec> specs = new ArrayList<>();
		// A bank on the ground floor and the same bank in the same place on the first floor, and a quest icon in that place too.
		specs.add(spec(100.5, 200.5, 3, 0, 7, 0));
		specs.add(spec(100.5, 200.5, 3, 1, 7, 0));
		specs.add(spec(100.5, 200.5, 4, 1, 8, 0));
		// The same stairs on floors 1 and 2 only.
		specs.add(spec(130.5, 200.5, 5, 1, 9, 0));
		specs.add(spec(130.5, 200.5, 5, 2, 9, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(specs), sprites);
		ViewTransform view = view(115.5, 200.5, 8);
		layer.layout(view, 0, 0);
		assertEquals(3, layer.drawnCount());
		int[] perFloor = new int[3];
		for (int slot = 0; slot < layer.drawnCount(); slot++)
		{
			perFloor[layer.drawnOn().floor(layer.drawnIconIndex(slot))]++;
		}
		assertEquals("the ground floor's bank only", 1, perFloor[0]);
		assertEquals("the quest icon and the lower floor's stairs", 2, perFloor[1]);
		assertEquals(0, perFloor[2]);
		// Each upper floor still has its own.
		layer.layout(view, 0, 1);
		assertEquals(3, layer.drawnCount());
		layer.layout(view, 0, 2);
		assertEquals(1, layer.drawnCount());
	}

	@Test
	public void anotherMapsIconsAreNotDrawn()
	{
		List<GameIconSet.Spec> one = new ArrayList<>();
		one.add(spec(100.5, 200.5, 3, 0, 0, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(one), sprites);
		layer.layout(view(100.5, 200.5, 8), 5, 0);
		assertEquals(0, layer.drawnCount());
	}

	@Test
	public void whenTwoPicturesOverlapTheOneWithTheNearestMiddleIsHit()
	{
		List<GameIconSet.Spec> two = new ArrayList<>();
		two.add(spec(100.5, 200.5, 3, 0, 0, 0));
		two.add(spec(101.5, 200.5, 4, 0, 1, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(two), sprites);
		// 8 pixels to the tile: the middles are 8 pixels apart and the 19 pixel hit areas overlap by 11.
		ViewTransform view = view(101.0, 200.5, 8);
		layer.layout(view, 0, 0);
		assertEquals(2, layer.drawnCount());
		int nearLeft = layer.hit(view, 0, 0, view.screenX(100.5) + 2, view.screenY(200.5));
		int nearRight = layer.hit(view, 0, 0, view.screenX(101.5) - 2, view.screenY(200.5));
		assertEquals(0, layer.drawnOn().category(layer.drawnIconIndex(nearLeft)));
		assertEquals(1, layer.drawnOn().category(layer.drawnIconIndex(nearRight)));
		// Where only one reaches, that one.
		int onlyRight = layer.hit(view, 0, 0, view.screenX(101.5) + 8, view.screenY(200.5));
		assertEquals(1, layer.drawnOn().category(layer.drawnIconIndex(onlyRight)));
	}

	@Test
	public void aCrowdIsThinnedAndTheImportantCategoriesStay()
	{
		List<GameIconSet.Spec> crowd = new ArrayList<>();
		for (int i = 0; i < 100; i++)
		{
			crowd.add(spec(100.5 + (i % 10) * 0.2, 200.5 + (i / 10) * 0.2, 3, 0, 1, 1));
		}
		crowd.add(spec(101.0, 201.0, 4, 0, 2, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(crowd), sprites);
		ViewTransform view = view(101.0, 201.0, 1.0);
		layer.layout(view, 0, 0);
		assertTrue("drawn " + layer.drawnCount(), layer.drawnCount() < 20);
		boolean important = false;
		for (int slot = 0; slot < layer.drawnCount(); slot++)
		{
			important |= layer.drawnOn().category(layer.drawnIconIndex(slot)) == 2;
		}
		assertTrue("the important icon stays", important);
		// From the native zoom up nothing is thinned out: the game draws them all.
		ViewTransform near = view(101.0, 201.0, 8.0);
		layer.layout(near, 0, 0);
		assertEquals(101, layer.drawnCount());
	}

	@Test
	public void atMostOneIconPerCellIsDrawnWhereTheMapIsCrowded()
	{
		java.util.Random random = new java.util.Random(7);
		List<GameIconSet.Spec> many = new ArrayList<>();
		for (int i = 0; i < 4000; i++)
		{
			many.add(spec(3000 + random.nextDouble() * 400, 3000 + random.nextDouble() * 300, i % 100, 0, i % 9, i % 3 == 0 ? 0 : 1));
		}
		GameIconLayer layer = new GameIconLayer(new GameIconSet(many), sprites);
		for (double zoom : new double[] {0.05, 0.1, 0.25, 0.5, 1.0})
		{
			ViewTransform view = new ViewTransform(3200, 3150, zoom, 0, 0, 1100, 700);
			layer.layout(view, 0, 0);
			int side = layer.drawnSize();
			assertTrue("something is drawn at " + zoom, layer.drawnCount() > 0);
			double need = GameIconScale.footprint(zoom, side) / zoom;
			int cellTiles = need <= 1.0 ? 1 : 1 << (int) Math.ceil(Math.log(need) / Math.log(2) - 1e-9);
			java.util.Set<Long> cells = new java.util.HashSet<>();
			for (int i = 0; i < layer.drawnCount(); i++)
			{
				double x = layer.drawnOn().x(layer.drawnIconIndex(i));
				double y = layer.drawnOn().y(layer.drawnIconIndex(i));
				long cell = ((long) Math.floor(x / cellTiles) << 32) ^ ((long) Math.floor(y / cellTiles) & 0xFFFFFFFFL);
				assertTrue("two icons in one cell at " + zoom, cells.add(cell));
			}
		}
	}

	@Test
	public void panningTheViewNeverChangesWhichIconsAreChosen()
	{
		java.util.Random random = new java.util.Random(11);
		List<GameIconSet.Spec> many = new ArrayList<>();
		for (int i = 0; i < 4000; i++)
		{
			many.add(spec(3000 + random.nextDouble() * 400, 3000 + random.nextDouble() * 300, i % 100, 0, i % 9, i % 3 == 0 ? 0 : 1));
		}
		GameIconLayer layer = new GameIconLayer(new GameIconSet(many), sprites);
		for (double zoom : new double[] {0.1, 0.25, 0.5, 1.0, 2.0})
		{
			java.util.Map<Integer, Boolean> seen = new java.util.HashMap<>();
			for (int step = 0; step < 40; step++)
			{
				ViewTransform view = new ViewTransform(3100 + step * 7.3, 3100 + step * 3.1, zoom, 0, 0, 500, 400);
				layer.layout(view, 0, 0);
				java.util.Set<Integer> drawn = new java.util.HashSet<>();
				for (int i = 0; i < layer.drawnCount(); i++)
				{
					drawn.add(layer.drawnIconIndex(i));
				}
				// Every icon that was fully in view in an earlier step and is in view now must have the same answer.
				for (int index = 0; index < many.size(); index++)
				{
					double sx = view.screenX(layer.drawnOn().x(index));
					double sy = view.screenY(layer.drawnOn().y(index));
					boolean inside = sx > 40 && sy > 40 && sx < 460 && sy < 360;
					if (!inside)
					{
						continue;
					}
					Boolean before = seen.put(index, drawn.contains(index));
					assertTrue("icon " + index + " flickered at zoom " + zoom, before == null || before == drawn.contains(index));
				}
			}
		}
	}

	@Test
	public void theMoreImportantIconTakesTheRoomAndFarOutOnlyTheImportantOnesAreLeft()
	{
		List<GameIconSet.Spec> specs = new ArrayList<>();
		// An unimportant icon a hair nearer the middle than an important one: the important one still wins the place.
		specs.add(spec(3200.5, 3200.5, 3, 0, 1, 1));
		specs.add(spec(3200.6, 3200.5, 4, 0, 2, 0));
		// And one unimportant icon on its own far from them.
		specs.add(spec(3300.5, 3200.5, 5, 0, 3, 1));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(specs), sprites);
		ViewTransform view = new ViewTransform(3250, 3200, 1.0, 0, 0, 800, 600);
		layer.layout(view, 0, 0);
		assertEquals(2, layer.drawnCount());
		assertEquals(2, layer.drawnOn().category(layer.drawnIconIndex(0)));
		assertEquals(3, layer.drawnOn().category(layer.drawnIconIndex(1)));
		ViewTransform far = new ViewTransform(3250, 3200, 0.1, 0, 0, 800, 600);
		layer.layout(far, 0, 0);
		assertEquals(1, layer.drawnCount());
		assertEquals(2, layer.drawnOn().category(layer.drawnIconIndex(0)));
	}

	@Test
	public void theCrowdGridAllocatesNothingOnceItHasGrown()
	{
		java.util.Random random = new java.util.Random(3);
		List<GameIconSet.Spec> many = new ArrayList<>();
		for (int i = 0; i < 3000; i++)
		{
			many.add(spec(3000 + random.nextDouble() * 400, 3000 + random.nextDouble() * 300, i % 100, 0, i % 9, i % 3 == 0 ? 0 : 1));
		}
		GameIconLayer layer = new GameIconLayer(new GameIconSet(many), sprites);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		for (int i = 0; i < 300; i++)
		{
			layer.layout(new ViewTransform(3200 + i % 50, 3150, 0.5, 0, 0, 1100, 700), 0, 0);
		}
		long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
		for (int i = 0; i < 1000; i++)
		{
			layer.layout(new ViewTransform(3200 + i % 50, 3150, 0.5, 0, 0, 1100, 700), 0, 0);
		}
		long used = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
		// The only allocation is the test's own view object, about a hundred bytes a layout.
		assertTrue("allocated " + used + " bytes in 1000 layouts", used < 1000 * 400);
	}

	@Test
	public void theDrawnIconsAreWhereTheDataPutsThemInTheImage()
	{
		List<GameIconSet.Spec> one = new ArrayList<>();
		one.add(spec(100.5, 200.5, 12, 0, 0, 0));
		GameIconLayer layer = new GameIconLayer(new GameIconSet(one), sprites);
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		ViewTransform view = view(100.5, 200.5, 8);
		layer.paint(g, view, new MapInfo(0, "Surface", 0, 0, 4096, 4096, 0, 0), 0);
		g.dispose();
		int side = layer.drawnSize();
		BufferedImage picture = sprites.get(12, side);
		assertNotNull(picture);
		int left = layer.drawnLeft(0);
		int top = layer.drawnTop(0);
		for (int y = 0; y < side; y++)
		{
			for (int x = 0; x < side; x++)
			{
				int want = picture.getRGB(x, y);
				int got = image.getRGB(left + x, top + y);
				for (int shift = 0; shift <= 24; shift += 8)
				{
					// A pixel that is part transparent is blended onto the clear image, which may round a channel by a little.
					int slack = ((want >>> 24) & 0xFF) == 255 ? 0 : 3;
					assertEquals("pixel " + x + ", " + y, (want >>> shift) & 0xFF, (got >>> shift) & 0xFF, slack);
				}
			}
		}
	}

	@Test
	public void theCellsOfAMapAreFoundWithoutTestingAllOfThem()
	{
		List<GameIconSet.Spec> many = new ArrayList<>();
		for (int i = 0; i < 2000; i++)
		{
			many.add(spec(i % 50 * 40 + 0.5, i / 50 * 40 + 0.5, i % 100, 0, i % 7, i % 2));
		}
		GameIconSet set = new GameIconSet(many);
		GameIconSet.MapIcons icons = set.on(0);
		assertEquals(2000, icons.size());
		int[] out = new int[3000];
		assertEquals(2000, icons.query(-1000, -1000, 5000, 5000, out));
		int inside = icons.query(0, 0, 100, 100, out);
		int expected = 0;
		for (int i = 0; i < icons.size(); i++)
		{
			if (icons.x(i) >= 0 && icons.x(i) <= 100 && icons.y(i) >= 0 && icons.y(i) <= 100)
			{
				expected++;
			}
		}
		// The cells that touch the rectangle may hold a few more than it does, never fewer.
		assertTrue(inside >= expected && inside <= expected + 12 * 3);
		assertEquals(0, icons.query(-500, -500, -400, -400, out));
	}
}
