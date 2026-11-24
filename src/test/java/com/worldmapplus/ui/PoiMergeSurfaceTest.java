package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.game.TrailTracker;
import com.worldmapplus.layers.GameIconLayer;
import com.worldmapplus.layers.GameIconSet;
import com.worldmapplus.layers.Hotspot;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.layers.NpcLayer;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A point of interest merged into one of the game's icons, through a whole surface: the icon's card names the tree or lists the fish while
 * the layer is on, the icon and its basic card stay when the layer is off, a kind of icon can be switched off on its own, and the
 * layers list offers every kind of icon the plugin draws.
 */
public class PoiMergeSurfaceTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private long now = 3_000_000;

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

	private MapSurface surfaceWith(String poiLayers, String hiddenIcons)
	{
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
		return new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return poiLayers;
			}

			@Override
			public String hiddenGameIcons()
			{
				return hiddenIcons;
			}
		}, services);
	}

	private void frames(MapSurface surface, int count)
	{
		BufferedImage canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
		PlayerPosition player = new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
		for (int i = 0; i < count; i++)
		{
			Graphics2D g = canvas.createGraphics();
			now += 16;
			surface.render(g, BOUNDS, player, new MapSurface.Options().followOnOpen(true).openZoom(4.0), now);
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

	/** The merged point of a name and the icon it sits on. */
	private Hotspot mergedPoint(String name)
	{
		for (Hotspot hotspot : assets.getHotspots().all())
		{
			if (hotspot.isMerged() && hotspot.getCard().getTitle().equals(name))
			{
				return hotspot;
			}
		}
		throw new AssertionError("no merged point called " + name);
	}

	/** Hovers an icon of the surface with the given settings and returns the card it shows, or null. */
	private HoverCard hoverIcon(MapSurface surface, double x, double y)
	{
		frames(surface, 10);
		surface.viewer().getCamera().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), x, y);
		surface.viewer().getZoom().jumpTo(8.0);
		frames(surface, 5);
		ViewTransform view = surface.currentView();
		mouseX = (int) Math.round(view.screenX(x));
		mouseY = (int) Math.round(view.screenY(y));
		frames(surface, 2);
		return surface.lastCard();
	}

	private static String row(HoverCard card, String label)
	{
		for (HoverCard.Row row : card.visibleRows(true))
		{
			if (row.getLabel().equals(label))
			{
				return row.getValue();
			}
		}
		return null;
	}

	@Test
	public void theRareTreeIconNamesTheTreeWhileTheLayerIsOnAndKeepsItsBasicCardWhenItIsOff()
	{
		Hotspot tree = mergedPoint("Blisterwood tree");
		MapSurface on = surfaceWith("tree", "");
		HoverCard named = hoverIcon(on, tree.getX(), tree.getY());
		assertNotNull(named);
		assertEquals("Blisterwood tree", named.getTitle());
		assertEquals("Woodcutting level 62", named.getSubtitle());

		MapSurface off = surfaceWith("", "");
		HoverCard basic = hoverIcon(off, tree.getX(), tree.getY());
		assertNotNull("the icon is still there with its basic card", basic);
		assertEquals("Rare trees", basic.getTitle());
		GameIconLayer layer = assets.getGameIconLayer();
		ViewTransform view = off.currentView();
		layer.layout(view, 0, 0);
		assertTrue("and still drawn", layer.hit(view, 0, 0, view.screenX(tree.getX()), view.screenY(tree.getY())) >= 0);
	}

	@Test
	public void theFishingIconListsTheFishWhileItsLayerIsOn()
	{
		Hotspot spot = mergedPoint("Seers Village");
		MapSurface on = surfaceWith("fishing", "");
		HoverCard card = hoverIcon(on, spot.getX(), spot.getY());
		assertNotNull(card);
		assertEquals("Fishing spot", card.getTitle());
		assertEquals("Salmon", row(card, "Fish"));
		MapSurface off = surfaceWith("", "");
		HoverCard basic = hoverIcon(off, spot.getX(), spot.getY());
		assertNotNull(basic);
		assertEquals(null, row(basic, "Fish"));
	}

	@Test
	public void aKindOfIconCanBeSwitchedOffOnItsOwn()
	{
		Hotspot tree = mergedPoint("Blisterwood tree");
		MapSurface hidden = surfaceWith("tree", "Rare trees");
		frames(hidden, 5);
		HoverCard card = hoverIcon(hidden, tree.getX(), tree.getY());
		GameIconLayer layer = assets.getGameIconLayer();
		ViewTransform view = hidden.currentView();
		layer.layout(view, 0, 0);
		assertEquals("the icon is gone", -1, layer.hit(view, 0, 0, view.screenX(tree.getX()), view.screenY(tree.getY())));
		assertTrue("and so is its card", card == null || !"Blisterwood tree".equals(card.getTitle()));
		// Showing the kind again brings it back on the same surface, whose setting is what the layer follows.
		MapSurface shown = surfaceWith("tree", "");
		HoverCard back = hoverIcon(shown, tree.getX(), tree.getY());
		assertNotNull(back);
		assertEquals("Blisterwood tree", back.getTitle());
	}

	@Test
	public void theLayersListOffersEveryKindOfIconThePluginDraws()
	{
		MapSurface surface = surfaceWith("", "");
		LayerPanel panel = surface.layerPanel();
		java.util.Set<String> kinds = new java.util.HashSet<>();
		for (LayerPanel.Row row : panel.getRows())
		{
			if (row.getKey().startsWith("gameicon:"))
			{
				kinds.add(row.getKey().substring("gameicon:".length()));
			}
		}
		GameIconSet icons = assets.getGameIcons();
		assertTrue(icons.size() > 3000);
		assertEquals(new java.util.HashSet<>(assets.getElements().drawnCategoryNames()), kinds);
		assertTrue(kinds.contains("Rare trees") && kinds.contains("Fishing spot") && kinds.contains("Bank") && kinds.contains("Water source"));
		assertTrue(!kinds.contains("Map icon"));
	}
}
