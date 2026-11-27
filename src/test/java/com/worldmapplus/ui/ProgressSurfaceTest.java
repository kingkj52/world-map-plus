package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.layers.GameIconLayer;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import com.worldmapplus.unlock.Need;
import com.worldmapplus.unlock.Needs;
import com.worldmapplus.unlock.Unlocks;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The settings that follow the player's progress, through a whole surface: the card says what is missing, a finished quest has no icon
 * and is not found by search, and with the settings off a player with no progress at all sees the map as it always was.
 */
public class ProgressSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
	private int questPoints = 10;
	private int diaryValue = 0;
	private boolean hideQuests;
	private boolean mark;
	private boolean only;
	private int mouseX = -1;
	private int mouseY = -1;
	private MapSurface surface;
	private BufferedImage canvas;
	private Unlocks unlocks;
	private long now = 2_000_000;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor);
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	@Before
	public void setUp()
	{
		canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getMouseCanvasPosition"))
				{
					return new net.runelite.api.Point(mouseX, mouseY);
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? (Object) false : type.isPrimitive() ? (Object) 0 : null;
			});
		ViewMemory memory = new ViewMemory(null)
		{
			@Override
			public void save(String slot, int mapId, double x, double y, double ppt)
			{
			}

			@Override
			public Saved load(String slot)
			{
				return null;
			}
		};
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), new Gson());
		ExternalOverlays external = new ExternalOverlays();
		com.worldmapplus.pins.RouteStore routes = new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(memory, pins, external, new MapActions(new EventBus(), external),
			new Settings(null), null, routes, new com.worldmapplus.pins.PanelLauncher());
		surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return "";
			}

			@Override
			public boolean hideFinishedQuests()
			{
				return hideQuests;
			}

			@Override
			public WorldMapPlusConfig.Unusable unusableIcons()
			{
				return only ? WorldMapPlusConfig.Unusable.LEFT_OUT : mark ? WorldMapPlusConfig.Unusable.DIMMED
					: WorldMapPlusConfig.Unusable.AS_USUAL;
			}
		}, services);
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

			@Override
			public int diaryValue(int varbit)
			{
				return diaryValue;
			}

			@Override
			public int questPoints()
			{
				return questPoints;
			}
		});
		unlocks.want(true, true);
		unlocks.onGameStateChanged(GameState.LOGGED_IN);
		for (int i = 0; i < 20; i++)
		{
			unlocks.onGameTick();
		}
		assets.setUnlocks(unlocks);
	}

	@After
	public void forget()
	{
		assets.setUnlocks(null);
	}

	private static PlayerPosition lumbridge()
	{
		return new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
	}

	private void frames(int count)
	{
		for (int i = 0; i < count; i++)
		{
			Graphics2D g = canvas.createGraphics();
			now += 16;
			surface.render(g, BOUNDS, lumbridge(), new MapSurface.Options().followOnOpen(true).openZoom(4.0), now);
			g.dispose();
			try
			{
				Thread.sleep(2);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	private void lookAt(double x, double y, double zoom)
	{
		frames(10);
		surface.viewer().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), x, y);
		surface.viewer().jumpZoom(zoom);
		frames(5);
	}

	private void hover(double worldX, double worldY)
	{
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(worldX));
		mouseY = (int) Math.round(view.screenY(worldY));
		frames(2);
	}

	/** A surface icon with a need that asks for a level, and nothing else of the kind within a few tiles. */
	private GameIconSet.Spec aLevelIcon()
	{
		GameIconSet.Spec best = null;
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			Need need = spec.getNeed();
			if (spec.getMapId() != assets.getCatalog().surface().getId() || spec.getFloor() != 0 || need == null
				|| !need.hasAlternatives() || need.alternatives().length != 1 || need.alternatives()[0].length != 1
				|| Needs.kind(need.alternatives()[0][0]) != 's'
				|| spec.getX() < 3000 || spec.getX() > 3400 || spec.getY() < 3000 || spec.getY() > 3300)
			{
				continue;
			}
			if (alone(spec))
			{
				best = spec;
				break;
			}
		}
		assertNotNull("an icon that asks for a level near Lumbridge", best);
		return best;
	}

	/** The skill of an icon that asks for one level, from the words of its check ("50 Agility"). */
	private static Skill skillOf(GameIconSet.Spec icon)
	{
		String level = Needs.describe(icon.getNeed().alternatives()[0]);
		return Skill.valueOf(level.substring(level.indexOf(' ') + 1).toUpperCase(java.util.Locale.ROOT));
	}

	private static boolean alone(GameIconSet.Spec spec)
	{
		for (GameIconSet.Spec other : assets.getElements().getGameIcons())
		{
			if (other != spec && other.getMapId() == spec.getMapId() && Math.abs(other.getX() - spec.getX()) < 6
				&& Math.abs(other.getY() - spec.getY()) < 6)
			{
				return false;
			}
		}
		return true;
	}

	private static String missing(HoverCard card)
	{
		for (HoverCard.Row row : card.getRows())
		{
			if (row.getLabel().equals("Missing"))
			{
				return row.getValue();
			}
		}
		return null;
	}

	@Test
	public void theCardSaysWhatIsMissingWhenIconsAreMarkedAndNothingWhenTheyAreNot()
	{
		GameIconSet.Spec icon = aLevelIcon();
		String level = Needs.describe(icon.getNeed().alternatives()[0]);
		Skill skill = skillOf(icon);
		lookAt(icon.getX(), icon.getY(), 8.0);
		hover(icon.getX(), icon.getY());
		assertNotNull(surface.lastCard());
		assertEquals("settings off: no extra row", null, missing(surface.lastCard()));

		mark = true;
		frames(3);
		hover(icon.getX(), icon.getY());
		assertEquals(level, missing(surface.lastCard()));
		GameIconLayer layer = assets.getGameIconLayer();
		ViewTransform view = surface.currentView();
		int slot = layer.hit(view, 0, 0, view.screenX(icon.getX()), view.screenY(icon.getY()));
		assertTrue("the icon is still there and still answers", slot >= 0);
		assertTrue(layer.isLockedAt(slot));

		levels.put(skill, 99);
		unlocks.onStatChanged(skill, 99);
		frames(3);
		hover(icon.getX(), icon.getY());
		assertEquals("a level up takes the row away", null, missing(surface.lastCard()));
		assertFalse(layer.isLockedAt(layer.hit(surface.currentView(), 0, 0, view.screenX(icon.getX()), view.screenY(icon.getY()))));
	}

	@Test
	public void onlyUsableTakesTheIconAwayFromTheMouseToo()
	{
		GameIconSet.Spec icon = aLevelIcon();
		only = true;
		lookAt(icon.getX(), icon.getY(), 8.0);
		ViewTransform view = surface.currentView();
		GameIconLayer layer = assets.getGameIconLayer();
		assertEquals(-1, layer.hit(view, 0, 0, view.screenX(icon.getX()), view.screenY(icon.getY())));
		Skill skill = skillOf(icon);
		levels.put(skill, 99);
		unlocks.onStatChanged(skill, 99);
		frames(3);
		assertTrue(layer.hit(surface.currentView(), 0, 0, view.screenX(icon.getX()), view.screenY(icon.getY())) >= 0);
	}

	@Test
	public void aFinishedQuestIsNotFoundBySearchWhileTheSettingIsOnAndIsAgainWhenItIsOff()
	{
		frames(10);
		type("cook's assistant");
		assertTrue("found by default", hasQuest("Cook's Assistant"));
		quests.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		unlocks.variablesChanged();
		for (int i = 0; i < 20; i++)
		{
			unlocks.onGameTick();
		}
		hideQuests = true;
		frames(3);
		assertFalse("finished and hidden", hasQuest("Cook's Assistant"));
		hideQuests = false;
		frames(3);
		assertTrue("found again with the setting off", hasQuest("Cook's Assistant"));
	}

	/** Round 47: the icon whose card or merged point has this name. */
	private static GameIconSet.Spec iconNamed(String name)
	{
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			if (spec.getCard() != null && name.equals(spec.getCard().getCard().getTitle()))
			{
				return spec;
			}
			if (spec.getExtras() != null)
			{
				for (com.worldmapplus.layers.IconExtra extra : spec.getExtras())
				{
					if (name.equals(extra.getName()))
					{
						return spec;
					}
				}
			}
		}
		throw new AssertionError("no icon named " + name);
	}

	private static String describe(HoverCard card)
	{
		StringBuilder text = new StringBuilder(card.getTitle() + " / " + card.getSubtitle());
		for (HoverCard.Row row : card.getRows())
		{
			text.append(" | ").append(row.getLabel()).append(": ").append(row.getValue());
		}
		return text.toString();
	}

	private static String row(HoverCard card, String label)
	{
		for (HoverCard.Row row : card.getRows())
		{
			if (row.getLabel().equals(label))
			{
				return row.getValue();
			}
		}
		return null;
	}

	@Test
	public void theWikisRequirementIsOnTheCardOfACourseOrDungeonAndItsMissingRowFollowsTheSnapshot()
	{
		// An icon of a kind that had no requirement before round 47, alone in its spot so its own card is the one shown.
		GameIconSet.Spec icon = null;
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			if (spec.getMapId() == assets.getCatalog().surface().getId() && spec.getFloor() == 0 && spec.getNeed() != null
				&& spec.getNeed().hasAlternatives() && row(spec.getCard().getCard(), "Requires") != null
				&& spec.getNeed().alternatives().length == 1 && spec.getNeed().alternatives()[0].length == 1
				&& Needs.kind(spec.getNeed().alternatives()[0][0]) == 's' && alone(spec))
			{
				icon = spec;
				break;
			}
		}
		assertNotNull("a course or dungeon that asks for a level, alone", icon);
		String words = row(icon.getCard().getCard(), "Requires");
		String level = Needs.describe(icon.getNeed().alternatives()[0]);
		Skill skill = skillOf(icon);
		lookAt(icon.getX(), icon.getY(), 8.0);
		hover(icon.getX(), icon.getY());
		assertEquals(describe(surface.lastCard()), words, row(surface.lastCard(), "Requires"));
		assertEquals("settings off: no Missing row", null, missing(surface.lastCard()));
		mark = true;
		frames(3);
		hover(icon.getX(), icon.getY());
		assertEquals(level, missing(surface.lastCard()));
		levels.put(skill, 99);
		unlocks.onStatChanged(skill, 99);
		frames(3);
		hover(icon.getX(), icon.getY());
		assertEquals("a level up takes the row away", null, missing(surface.lastCard()));
		assertEquals("the wiki's words stay", words, row(surface.lastCard(), "Requires"));
	}

	@Test
	public void questPointsAreMissingOnTheResultForTheChampionsChallenge()
	{
		frames(10);
		type("champions' challenge");
		SearchEntry challenge = null;
		for (SearchEntry entry : surface.searchBox().getResults())
		{
			if (entry.getName().equals("Champions' Challenge") && entry.getKind() == SearchKind.DOORWAY
				&& entry.getMapId() == assets.getCatalog().surface().getId())
			{
				challenge = entry;
			}
		}
		StringBuilder seen = new StringBuilder();
		for (SearchEntry entry : surface.searchBox().getResults())
		{
			seen.append(entry.getKind()).append(' ').append(entry.getName()).append(" @").append(entry.getMapId()).append(' ')
				.append(entry.getX()).append(',').append(entry.getY()).append(entry.getNeed() != null ? " need" : "").append("; ");
		}
		assertNotNull("found: " + seen, challenge);
		assertNotNull("need: " + seen, challenge.getNeed());
		assertEquals("32 Quest points", unlocks.shortfall(challenge.getNeed()).text());
		questPoints = 40;
		unlocks.variablesChanged();
		for (int i = 0; i < 20; i++)
		{
			unlocks.onGameTick();
		}
		assertEquals("enough quest points", null, unlocks.shortfall(challenge.getNeed()));
	}

	/** Pixels of the gold "Missing" note in the results list. */
	private int goldInResults()
	{
		Rectangle results = surface.searchBarResults();
		int gold = 0;
		for (int y = Math.max(0, results.y); y < Math.min(canvas.getHeight(), results.y + results.height); y++)
		{
			for (int x = Math.max(0, results.x); x < Math.min(canvas.getWidth(), results.x + results.width); x++)
			{
				int rgb = canvas.getRGB(x, y);
				int r = (rgb >> 16) & 0xff;
				int g = (rgb >> 8) & 0xff;
				int b = rgb & 0xff;
				if (r >= 200 && g >= 150 && g <= 195 && b <= 100)
				{
					gold++;
				}
			}
		}
		return gold;
	}

	@Test
	public void aSearchResultSaysWhatIsMissingLikeTheCard()
	{
		frames(10);
		type("ardougne rooftop course");
		SearchEntry course = null;
		for (SearchEntry entry : surface.searchBox().getResults())
		{
			if (entry.getName().equals("Ardougne Rooftop Course"))
			{
				course = entry;
			}
		}
		assertNotNull("found", course);
		assertNotNull("the result knows the icon's need", course.getNeed());
		assertEquals("90 Agility", unlocks.shortfall(course.getNeed()).text());
		frames(2);
		assertEquals("settings off: no note", 0, goldInResults());
		mark = true;
		frames(2);
		assertTrue("the note is drawn: " + goldInResults(), goldInResults() > 0);
		levels.put(Skill.AGILITY, 99);
		unlocks.onStatChanged(Skill.AGILITY, 99);
		frames(2);
		assertEquals("a level up takes the note away", 0, goldInResults());
	}

	@Test
	public void aTeleportResultHasItsOwnWaysNeedAndADiaryTierIsMissing()
	{
		frames(10);
		type("ardougne cloak kandarin monastery");
		SearchEntry cloak = null;
		for (SearchEntry entry : surface.searchBox().getResults())
		{
			if (entry.getKind() == SearchKind.TELEPORT && entry.getName().equals("Ardougne cloak: Kandarin Monastery"))
			{
				cloak = entry;
			}
		}
		assertNotNull("found", cloak);
		assertNotNull(cloak.getNeed());
		assertEquals("Easy Ardougne Diary", unlocks.shortfall(cloak.getNeed()).text());
		diaryValue = 1;
		unlocks.variablesChanged();
		for (int i = 0; i < 20; i++)
		{
			unlocks.onGameTick();
		}
		assertEquals("done", null, unlocks.shortfall(cloak.getNeed()));
	}

	private void type(String text)
	{
		surface.searchBox().focus();
		surface.searchBox().clear();
		for (char c : text.toCharArray())
		{
			surface.searchBox().type(c);
		}
		frames(2);
	}

	private boolean hasQuest(String name)
	{
		for (SearchEntry entry : surface.searchBox().getResults())
		{
			if (entry.getKind() == SearchKind.QUEST && entry.getName().equals(name))
			{
				return true;
			}
		}
		return false;
	}
}
