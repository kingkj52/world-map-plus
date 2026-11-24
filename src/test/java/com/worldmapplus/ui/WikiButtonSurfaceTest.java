package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.game.TrailTracker;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.NpcLayer;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The Wiki button on a card and the menu entry that does the same: reachable with a pointer that has to cross a gap to the card, absent
 * where there is no page, and handing a title to the opener and nothing more.
 */
public class WikiButtonSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final JPanel SOURCE = new JPanel();

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private MapSurface surface;
	private BufferedImage canvas;
	private long now = 2_000_000;
	private final List<String> opened = new ArrayList<>();
	private boolean wikiOn = true;

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
		opened.clear();
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
		NpcLayer quiet = new NpcLayer()
		{
			@Override
			public void paint(Graphics2D g, ViewTransform view, MapInfo viewed, PlayerPosition player)
			{
			}
		};
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
		SurfaceServices services = new SurfaceServices(quiet, memory, pins, external, new MapActions(new EventBus(), external),
			new Settings(null), new TrailTracker(), null, null, routes, new com.worldmapplus.pins.PanelLauncher());
		surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return "";
			}

			@Override
			public boolean showWikiButtons()
			{
				return wikiOn;
			}
		}, services);
		surface.wikiOpener(title ->
		{
			opened.add(title);
			return true;
		});
		searched.clear();
		surface.wikiSearcher(name ->
		{
			searched.add(name);
			return true;
		});
	}

	private final java.util.List<String> searched = new java.util.ArrayList<>();

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
		}
	}

	private void lookAt(double x, double y, double zoom)
	{
		frames(10);
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), x, y);
		surface.viewer().getZoom().jumpTo(zoom);
		frames(5);
	}

	private void hover(double worldX, double worldY)
	{
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(worldX));
		mouseY = (int) Math.round(view.screenY(worldY));
		frames(2);
	}

	private void moveTo(int x, int y)
	{
		mouseX = x;
		mouseY = y;
		frames(2);
	}

	private void click(int x, int y, int button)
	{
		surface.getInput().mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, button));
		now += 16;
		surface.getInput().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, button));
		frames(2);
	}

	/** An icon that is alone near Lumbridge, whose card names a wiki page (or, with false, one whose card names none). */
	private GameIconSet.Spec anIcon(boolean withPage)
	{
		for (GameIconSet.Spec spec : assets.getElements().getGameIcons())
		{
			if (spec.getMapId() != assets.getCatalog().surface().getId() || spec.getFloor() != 0 || spec.getCard() == null
				|| spec.getX() < 3000 || spec.getX() > 3400 || spec.getY() < 3000 || spec.getY() > 3300
				|| (spec.getCard().getCard().getWiki() != null) != withPage || spec.getCard().getTarget() != null)
			{
				continue;
			}
			boolean alone = true;
			for (GameIconSet.Spec other : assets.getElements().getGameIcons())
			{
				if (other != spec && other.getMapId() == spec.getMapId() && Math.abs(other.getX() - spec.getX()) < 12
					&& Math.abs(other.getY() - spec.getY()) < 12)
				{
					alone = false;
					break;
				}
			}
			if (alone)
			{
				return spec;
			}
		}
		throw new AssertionError("no such icon near Lumbridge: " + withPage);
	}

	@Test
	public void aCardAboutSomethingWithAPageHasAWikiButtonAndOneWithoutASearchButton()
	{
		GameIconSet.Spec quest = anIcon(true);
		lookAt(quest.getX(), quest.getY(), 8.0);
		hover(quest.getX(), quest.getY());
		assertEquals(quest.getCard().getCard().getWiki(), surface.lastCard().getWiki());
		assertNotNull("a button", surface.wikiButton());
		assertTrue("inside the card", surface.wikiButton().width > 0);
		assertTrue(opened.isEmpty());

		GameIconSet.Spec plain = anIcon(false);
		lookAt(plain.getX(), plain.getY(), 8.0);
		hover(plain.getX(), plain.getY());
		assertNotNull(surface.lastCard());
		assertNull(surface.lastCard().getWiki());
		// No page that is certain to exist: the button searches the wiki for the name, so there is never a missing page.
		assertEquals(surface.lastCard().getTitle(), surface.lastCard().getWikiSearch());
		Rectangle button = surface.wikiButton();
		assertNotNull("a search button where there is no page", button);
		assertEquals(HoverCardPainter.SEARCH_BUTTON_WIDTH, button.width);
		moveTo(button.x + button.width / 2, button.y + button.height / 2);
		click(button.x + button.width / 2, button.y + button.height / 2, MouseEvent.BUTTON1);
		assertTrue("no page was opened", opened.isEmpty());
		assertEquals(java.util.Collections.singletonList(surface.lastCard().getTitle()), searched);
	}

	@Test
	public void thePointerCanCrossTheGapToTheCardAndPressTheButton()
	{
		GameIconSet.Spec quest = anIcon(true);
		String title = quest.getCard().getCard().getWiki();
		lookAt(quest.getX(), quest.getY(), 8.0);
		hover(quest.getX(), quest.getY());
		Rectangle button = surface.wikiButton();
		assertNotNull(button);
		// Off the icon, on the side away from the card: the card is still there, where it was.
		moveTo(mouseX - 40, mouseY - 40);
		assertNotNull("the card is kept for a moment", surface.wikiButton());
		assertEquals(button, surface.wikiButton());
		// On the button.
		moveTo(button.x + button.width / 2, button.y + button.height / 2);
		assertEquals(button, surface.wikiButton());
		click(button.x + button.width / 2, button.y + button.height / 2, MouseEvent.BUTTON1);
		assertEquals(java.util.Collections.singletonList(title), opened);
		// The card stays and a second press opens it again.
		assertNotNull(surface.wikiButton());
	}

	@Test
	public void theCardIsLetGoOfWhenThePointerGoesAwayAndCannotBePressedAfterwards()
	{
		GameIconSet.Spec quest = anIcon(true);
		lookAt(quest.getX(), quest.getY(), 8.0);
		hover(quest.getX(), quest.getY());
		Rectangle button = surface.wikiButton();
		assertNotNull(button);
		// Far away, and after the moment the card is kept for.
		now += MapSurface.HOLD_MILLIS + 300;
		moveTo(BOUNDS.x + BOUNDS.width - 30, BOUNDS.y + BOUNDS.height - 30);
		now += 16;
		frames(2);
		assertNull(surface.wikiButton());
		click(button.x + button.width / 2, button.y + button.height / 2, MouseEvent.BUTTON1);
		assertTrue("nothing opened", opened.isEmpty());
	}

	@Test
	public void aClickOnTheRestOfTheCardOpensNothing()
	{
		GameIconSet.Spec quest = anIcon(true);
		lookAt(quest.getX(), quest.getY(), 8.0);
		hover(quest.getX(), quest.getY());
		Rectangle button = surface.wikiButton();
		moveTo(button.x - 12, button.y + button.height + 6);
		int before = surface.viewer().getMap().getId();
		click(button.x - 12, button.y + button.height + 6, MouseEvent.BUTTON1);
		assertTrue(opened.isEmpty());
		assertEquals("and does not drag the map", before, surface.viewer().getMap().getId());
	}

	@Test
	public void theMenuOfAnIconOffersToOpenItsWikiPage()
	{
		GameIconSet.Spec quest = anIcon(true);
		String title = quest.getCard().getCard().getWiki();
		lookAt(quest.getX(), quest.getY(), 8.0);
		ViewTransform view = surface.currentView();
		int x = (int) Math.round(view.screenX(quest.getX()));
		int y = (int) Math.round(view.screenY(quest.getY()));
		mouseX = x;
		mouseY = y;
		click(x, y, MouseEvent.BUTTON3);
		assertTrue("the menu is open", surface.menu().isOpen());
		ContextMenu.Item item = null;
		for (ContextMenu.Item candidate : surface.menu().getItems())
		{
			if (candidate.getAction() == ContextMenu.Action.OPEN_WIKI)
			{
				item = candidate;
			}
		}
		assertNotNull("an entry to open the wiki page", item);
		assertEquals("Open wiki page: " + title, item.getLabel());
		Rectangle menu = surface.menu().getBounds();
		int[] at = null;
		for (int py = menu.y; py < menu.y + menu.height && at == null; py++)
		{
			if (surface.menu().hit(menu.x + 10, py) == item)
			{
				at = new int[] {menu.x + 10, py};
			}
		}
		assertNotNull(at);
		click(at[0], at[1], MouseEvent.BUTTON1);
		assertEquals(java.util.Collections.singletonList(title), opened);
		assertFalse(surface.menu().isOpen());
	}

	@Test
	public void withTheSettingOffThereIsNoButtonAndNoMenuEntry()
	{
		wikiOn = false;
		GameIconSet.Spec quest = anIcon(true);
		lookAt(quest.getX(), quest.getY(), 8.0);
		hover(quest.getX(), quest.getY());
		assertNotNull(surface.lastCard());
		assertNull(surface.lastCard().getWiki());
		assertNull(surface.wikiButton());
		ViewTransform view = surface.currentView();
		int x = (int) Math.round(view.screenX(quest.getX()));
		int y = (int) Math.round(view.screenY(quest.getY()));
		click(x, y, MouseEvent.BUTTON3);
		for (ContextMenu.Item candidate : surface.menu().getItems())
		{
			assertTrue(candidate.getAction() != ContextMenu.Action.OPEN_WIKI);
			assertTrue(candidate.getAction() != ContextMenu.Action.SEARCH_WIKI);
		}
		// Nor a search button on a card with no page.
		surface.menu().close();
		GameIconSet.Spec plain = anIcon(false);
		lookAt(plain.getX(), plain.getY(), 8.0);
		hover(plain.getX(), plain.getY());
		assertNull(surface.lastCard().getWikiSearch());
		assertNull(surface.wikiButton());
	}

	@Test
	public void theMenuOfAnIconWithNoPageOffersTheWikisSearchInstead()
	{
		GameIconSet.Spec plain = anIcon(false);
		lookAt(plain.getX(), plain.getY(), 8.0);
		ViewTransform view = surface.currentView();
		int x = (int) Math.round(view.screenX(plain.getX()));
		int y = (int) Math.round(view.screenY(plain.getY()));
		mouseX = x;
		mouseY = y;
		click(x, y, MouseEvent.BUTTON3);
		assertTrue(surface.menu().isOpen());
		ContextMenu.Item search = null;
		for (ContextMenu.Item candidate : surface.menu().getItems())
		{
			assertTrue(candidate.getLabel(), candidate.getAction() != ContextMenu.Action.OPEN_WIKI);
			if (candidate.getAction() == ContextMenu.Action.SEARCH_WIKI)
			{
				search = candidate;
			}
		}
		assertNotNull("an entry that searches the wiki instead", search);
		assertTrue(search.getLabel(), search.getLabel().startsWith("Search the wiki for "));
	}

	@Test
	public void aSearchResultsMenuOffersTheWikiPageOfAQuest()
	{
		frames(10);
		surface.searchBox().focus();
		for (char c : "cook's assistant".toCharArray())
		{
			surface.searchBox().type(c);
		}
		frames(30);
		assertFalse(surface.searchBox().getResults().isEmpty());
		Rectangle results = surface.searchBarResults();
		int x = results.x + 30;
		int y = -1;
		for (int py = results.y; py < results.y + results.height; py++)
		{
			if (surface.searchBar().hit(x, py).getRow() == 0)
			{
				y = py;
				break;
			}
		}
		assertTrue("the first result is on screen", y >= 0);
		mouseX = x;
		mouseY = y;
		click(x, y, MouseEvent.BUTTON3);
		assertTrue(surface.menu().isOpen());
		boolean offered = false;
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			offered |= item.getAction() == ContextMenu.Action.OPEN_WIKI;
		}
		assertEquals("the first result is the quest itself", "Cook's Assistant", surface.searchBox().getResults().get(0).getName());
		assertTrue(offered);
	}
}
