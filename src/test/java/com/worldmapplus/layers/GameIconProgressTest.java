package com.worldmapplus.layers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.map.MapInfo;
import com.worldmapplus.unlock.Need;
import com.worldmapplus.unlock.Needs;
import com.worldmapplus.unlock.Unlocks;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * What the player's progress does to the game's icons: a finished quest or an icon that cannot be used yet is dimmed with a padlock or
 * left out, and whatever is done, the same icon wins every crowded spot, so nothing jumps when a level goes up.
 */
public class GameIconProgressTest
{
	private static final MapInfo SURFACE = new MapInfo(0, "Surface", 0, 0, 4096, 4096, 0, 0);
	private static GameIconSprites sprites;

	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
	private Unlocks unlocks;
	private GameIconLayer layer;

	@BeforeClass
	public static void load()
	{
		sprites = GameIconSprites.load(136);
	}

	/** Icon 0 needs 90 Agility, 1 starts Priest in Peril, 2 asks nothing, and 3 and 4 share a spot (3 wins it and needs 90 Magic). */
	private static List<GameIconSet.Spec> specs()
	{
		Need agility = Needs.ofWay(Collections.singletonList("90 Agility"), null);
		Need starts = Needs.startOf(net.runelite.api.Quest.PRIEST_IN_PERIL);
		Need magic = Needs.ofWay(Collections.singletonList("90 Magic"), null);
		List<GameIconSet.Spec> list = new ArrayList<>();
		list.add(new GameIconSet.Spec(0, 100.5, 200.5, 3, 0, 4, 0, null, null, agility));
		list.add(new GameIconSet.Spec(0, 140.5, 200.5, 4, 0, 5, 0, null, null, starts));
		list.add(new GameIconSet.Spec(0, 180.5, 200.5, 5, 0, 6, 0, null, null, null));
		list.add(new GameIconSet.Spec(0, 220.5, 200.5, 6, 0, 7, 0, null, null, magic));
		list.add(new GameIconSet.Spec(0, 220.6, 200.6, 7, 0, 8, GameIconScale.MINOR_PRIORITY, null, null, null));
		return list;
	}

	@Before
	public void setUp()
	{
		levels.clear();
		quests.clear();
		levels.put(Skill.AGILITY, 50);
		levels.put(Skill.MAGIC, 50);
		quests.put(Quest.PRIEST_IN_PERIL, QuestState.FINISHED);
		unlocks = new Unlocks(new Unlocks.Source()
		{
			@Override
			public boolean loggedIn()
			{
				return true;
			}

			@Override
			public int realLevel(Skill skill)
			{
				return levels.getOrDefault(skill, 1);
			}

			@Override
			public QuestState questState(Quest quest)
			{
				return quests.getOrDefault(quest, QuestState.NOT_STARTED);
			}
		});
		unlocks.want(true, true);
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		for (int i = 0; i < 20; i++)
		{
			unlocks.onGameTick();
		}
		layer = new GameIconLayer(new GameIconSet(specs()), sprites);
	}

	private static ViewTransform view(double zoom)
	{
		return new ViewTransform(160.5, 200.5, zoom, 0, 0, 800, 600);
	}

	private BufferedImage render(double zoom)
	{
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(40, 90, 40));
		g.fillRect(0, 0, 800, 600);
		layer.paint(g, view(zoom), SURFACE, 0);
		g.dispose();
		return image;
	}

	private static boolean same(BufferedImage a, BufferedImage b)
	{
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return false;
				}
			}
		}
		return true;
	}

	private int[] chosen(double zoom)
	{
		layer.layout(view(zoom), 0, 0);
		int[] out = new int[layer.drawnCount()];
		for (int slot = 0; slot < out.length; slot++)
		{
			out[slot] = layer.drawnIconIndex(slot);
		}
		return out;
	}

	private int slotOf(double x)
	{
		for (int slot = 0; slot < layer.drawnCount(); slot++)
		{
			if (Math.abs(layer.drawnOn().x(layer.drawnIconIndex(slot)) - x) < 0.01)
			{
				return slot;
			}
		}
		return -1;
	}

	@Test
	public void withTheSettingsOffNothingChangesWhatEverThePlayerHasDone()
	{
		layer.setUnlocks(null, false, false, false, false);
		BufferedImage plain = render(4);
		layer.setUnlocks(unlocks, false, false, false, false);
		BufferedImage withProgress = render(4);
		assertTrue("the same picture", same(plain, withProgress));
		for (int slot = 0; slot < layer.drawnCount(); slot++)
		{
			assertEquals(GameIconLayer.NORMAL, layer.lookAt(slot));
		}
		assertFalse(layer.followsProgress());
	}

	@Test
	public void anIconYouCannotUseYetIsDimmedWithAPadlockAndKeepsItsPlace()
	{
		layer.setUnlocks(unlocks, false, false, false, false);
		BufferedImage plain = render(4);
		int[] before = chosen(4);
		layer.setUnlocks(unlocks, false, true, false, false);
		BufferedImage marked = render(4);
		assertArrayEquals("the same icons are chosen", before, chosen(4));
		assertEquals(GameIconLayer.LOCKED, layer.lookAt(slotOf(100.5)));
		assertEquals("an icon that asks nothing is as it was", GameIconLayer.NORMAL, layer.lookAt(slotOf(180.5)));
		assertEquals("a quest start is not an icon to dim", GameIconLayer.NORMAL, layer.lookAt(slotOf(140.5)));
		assertTrue(layer.isLockedAt(slotOf(220.5)));
		assertFalse("the picture changed", same(plain, marked));
		// The icon that asks nothing is drawn exactly as before.
		int side = layer.drawnSize();
		int left = layer.drawnLeft(slotOf(180.5));
		int top = layer.drawnTop(slotOf(180.5));
		for (int y = 0; y < side; y++)
		{
			for (int x = 0; x < side; x++)
			{
				assertEquals(plain.getRGB(left + x, top + y), marked.getRGB(left + x, top + y));
			}
		}
		// And a level up puts the icon back as it was.
		levels.put(Skill.AGILITY, 90);
		unlocks.onStatChanged(Skill.AGILITY, 90);
		assertEquals(GameIconLayer.NORMAL, layer.lookAt(slotOf(100.5)));
		assertArrayEquals(before, chosen(4));
	}

	@Test
	public void theLockedPictureIsOneCachedImageWithAGoldPadlockInItsCorner()
	{
		BufferedImage plain = sprites.get(3, 20, GameIconLayer.NORMAL);
		BufferedImage locked = sprites.get(3, 20, GameIconLayer.LOCKED);
		assertNotNull(locked);
		assertSame("read once and kept", locked, sprites.get(3, 20, GameIconLayer.LOCKED));
		assertEquals(plain.getWidth(), locked.getWidth());
		boolean gold = false;
		for (int y = locked.getHeight() / 2; y < locked.getHeight(); y++)
		{
			for (int x = locked.getWidth() / 2; x < locked.getWidth(); x++)
			{
				gold |= locked.getRGB(x, y) == 0xFFE6BE3C;
			}
		}
		assertTrue("a padlock in the lower right", gold);
		// What was drawn is fainter than the picture was.
		long plainAlpha = 0;
		long lockedAlpha = 0;
		for (int y = 0; y < 8; y++)
		{
			for (int x = 0; x < 8; x++)
			{
				plainAlpha += plain.getRGB(x, y) >>> 24;
				lockedAlpha += locked.getRGB(x, y) >>> 24;
			}
		}
		assertTrue(lockedAlpha < plainAlpha || plainAlpha == 0);
		for (int side = GameIconScale.MIN_SIDE; side <= GameIconScale.MAX_SIDE; side++)
		{
			assertNotNull("a locked picture at every side the zoom draws, " + side, sprites.get(9, side, GameIconLayer.LOCKED));
		}
		assertNull("no side the zoom never draws", sprites.get(9, GameIconScale.MIN_SIDE - 1, GameIconLayer.LOCKED));
		assertNull("no look that is not drawn", sprites.get(9, GameIconScale.MAX_SIDE, 6));
	}

	@Test
	public void showingOnlyWhatYouCanUseLeavesOutTheRestWithoutMovingAnythingElse()
	{
		layer.setUnlocks(unlocks, false, false, false, false);
		int[] before = chosen(4);
		layer.setUnlocks(unlocks, false, false, true, false);
		BufferedImage only = render(4);
		assertArrayEquals("the same icons are chosen, the hidden ones keep their cells", before, chosen(4));
		assertEquals(GameIconLayer.HIDDEN, layer.lookAt(slotOf(100.5)));
		assertEquals(GameIconLayer.HIDDEN, layer.lookAt(slotOf(220.5)));
		ViewTransform v = view(4);
		assertEquals("a hidden icon answers no hover", -1, layer.hit(v, 0, 0, v.screenX(100.5), v.screenY(200.5)));
		assertTrue("an icon you can use does", layer.hit(v, 0, 0, v.screenX(180.5), v.screenY(200.5)) >= 0);
		// Nothing is drawn where the hidden icon is.
		int side = layer.drawnSize();
		int left = (int) Math.round(v.screenX(100.5) - side / 2.0);
		int top = (int) Math.round(v.screenY(200.5) - side / 2.0);
		for (int y = 0; y < side; y++)
		{
			for (int x = 0; x < side; x++)
			{
				assertEquals(0xFF285A28, only.getRGB(left + x, top + y));
			}
		}
	}

	@Test
	public void aHiddenOrDimmedIconNeverLetsAnotherTakeItsSpotInACrowd()
	{
		// Icons 3 and 4 share a spot; 3 wins it by its priority whatever the player can do.
		layer.setUnlocks(unlocks, false, false, false, false);
		int[] plain = chosen(0.25);
		for (boolean[] options : new boolean[][] {{false, true, false}, {false, false, true}, {true, true, true}})
		{
			layer.setUnlocks(unlocks, options[0], options[1], options[2], false);
			assertArrayEquals(plain, chosen(0.25));
		}
		layer.setUnlocks(unlocks, false, false, true, false);
		layer.layout(view(0.25), 0, 0);
		int winner = layer.drawnIconIndex(slotOf(220.5));
		assertEquals("the icon that asks for 90 Magic keeps the spot", 6, layer.drawnOn().picture(winner));
		assertEquals(GameIconLayer.HIDDEN, layer.lookAt(slotOf(220.5)));
	}

	@Test
	public void aFinishedQuestIsHiddenOnItsOwnAndNothingElseIs()
	{
		layer.setUnlocks(unlocks, true, false, false, false);
		render(4);
		assertEquals(GameIconLayer.HIDDEN, layer.lookAt(slotOf(140.5)));
		assertEquals("a level that is missing is not this setting's business", GameIconLayer.NORMAL, layer.lookAt(slotOf(100.5)));
		quests.put(Quest.PRIEST_IN_PERIL, QuestState.NOT_STARTED);
		unlocks.variablesChanged();
		for (int i = 0; i < 20; i++)
		{
			unlocks.onGameTick();
		}
		assertEquals("a quest not finished is shown", GameIconLayer.NORMAL, layer.lookAt(slotOf(140.5)));
	}

	private void questBecomes(QuestState state)
	{
		quests.put(Quest.PRIEST_IN_PERIL, state);
		unlocks.variablesChanged();
		for (int i = 0; i < 20; i++)
		{
			unlocks.onGameTick();
		}
	}

	@Test
	public void aQuestStartCarriesADotForHowFarThePlayerIsAndNothingElseChanges()
	{
		layer.setUnlocks(unlocks, false, false, false, false);
		BufferedImage plain = render(4);
		int[] before = chosen(4);
		layer.setUnlocks(unlocks, false, false, false, true);
		BufferedImage coloured = render(4);
		assertArrayEquals("the same icons are chosen", before, chosen(4));
		assertEquals(GameIconLayer.QUEST_FINISHED, layer.lookAt(slotOf(140.5)));
		assertEquals("an icon that starts no quest is as it was", GameIconLayer.NORMAL, layer.lookAt(slotOf(100.5)));
		int slot = slotOf(140.5);
		int side = layer.drawnSize();
		int x = layer.drawnLeft(slot) + side - 3;
		int y = layer.drawnTop(slot) + side - 3;
		assertEquals("a green dot in the corner", 0xFF3FC24F, coloured.getRGB(x, y));
		assertFalse(same(plain, coloured));
		questBecomes(QuestState.IN_PROGRESS);
		assertEquals(GameIconLayer.QUEST_STARTED, layer.lookAt(slot));
		assertEquals("yellow", 0xFFF2D43A, render(4).getRGB(x, y));
		questBecomes(QuestState.NOT_STARTED);
		assertEquals(GameIconLayer.QUEST_NOT_STARTED, layer.lookAt(slot));
		assertEquals("red", 0xFFE0352B, render(4).getRGB(x, y));
		// The dotted picture is made once and kept.
		int picture = layer.drawnOn().picture(layer.drawnIconIndex(slot));
		assertTrue(sprites.get(picture, side, GameIconLayer.QUEST_NOT_STARTED) == sprites.get(picture, side, GameIconLayer.QUEST_NOT_STARTED));
		// Hiding a finished quest still wins over its dot.
		questBecomes(QuestState.FINISHED);
		layer.setUnlocks(unlocks, true, false, false, true);
		assertEquals(GameIconLayer.HIDDEN, layer.lookAt(slot));
		// Off again, the picture is the plain one.
		layer.setUnlocks(unlocks, false, false, false, false);
		assertTrue(same(plain, render(4)));
	}

	@Test
	public void whenNothingIsKnownEveryIconIsShownAsItAlwaysWas()
	{
		unlocks.onGameStateChanged(GameState.LOGIN_SCREEN);
		layer.setUnlocks(unlocks, true, true, true, false);
		BufferedImage knownNothing = render(4);
		layer.setUnlocks(null, false, false, false, false);
		assertTrue(same(render(4), knownNothing));
	}

	@Test
	public void workingOutTheLooksAllocatesNothingOnceTheArraysHaveGrown()
	{
		java.util.Random random = new java.util.Random(5);
		List<GameIconSet.Spec> many = new ArrayList<>();
		Need agility = Needs.ofWay(Collections.singletonList("90 Agility"), null);
		for (int i = 0; i < 3000; i++)
		{
			many.add(new GameIconSet.Spec(0, 3000 + random.nextDouble() * 400, 3000 + random.nextDouble() * 300, i % 100, 0, i % 9,
				i % 3 == 0 ? 0 : GameIconScale.MINOR_PRIORITY, null, null, i % 2 == 0 ? agility : null));
		}
		GameIconLayer big = new GameIconLayer(new GameIconSet(many), sprites);
		big.setUnlocks(unlocks, true, true, false, false);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		for (int i = 0; i < 300; i++)
		{
			big.hit(new ViewTransform(3200 + i % 50, 3150, 0.5, 0, 0, 1100, 700), 0, 0, 10, 10);
		}
		long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
		for (int i = 0; i < 1000; i++)
		{
			if (i % 100 == 0)
			{
				// A level up is a new version, so the looks are worked out again for the same layout.
				unlocks.onStatChanged(Skill.AGILITY, 50 + i % 2);
			}
			big.hit(new ViewTransform(3200 + i % 50, 3150, 0.5, 0, 0, 1100, 700), 0, 0, 10, 10);
		}
		long used = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
		assertTrue("allocated " + used + " bytes in 1000 layouts and looks", used < 1000 * 400);
		assertNotEquals(0, big.drawnCount());
	}

	@Test
	public void paintingTheSameViewAgainAllocatesNothingBeyondTheDrawingItself()
	{
		layer.setUnlocks(unlocks, true, true, false, false);
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		ViewTransform v = view(4);
		for (int i = 0; i < 50; i++)
		{
			layer.paint(g, v, SURFACE, 0);
		}
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
		for (int i = 0; i < 1000; i++)
		{
			layer.paint(g, v, SURFACE, 0);
		}
		long used = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
		g.dispose();
		// A locked icon is one cached image, drawn like any other: no copy is made for a frame.
		assertTrue("allocated " + used + " bytes in 1000 frames", used < 1000 * 600);
	}
}
