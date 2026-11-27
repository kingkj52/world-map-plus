package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.bridge.NativePoints;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.layers.HoverCard;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.party.PartyMember;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Round 73: what the map makes of other plugins' things through the real surface: the cards of their markers and of the points on the
 * game's map, the menu entries on them (their own, Focus on, Follow a party member), a click on each, following a member, and the
 * answer to a focus, written before the other plugin support was slimmed ({@code golden/other-plugins.txt}). When the environment
 * variable WORLDMAPPLUS_SHOTS names a folder, a picture of each scene is written to its {@code other-plugins} folder too, to compare
 * by eye or byte for byte with an earlier run.
 */
public class OtherPluginsGoldenTest
{
	private static final Rectangle BOUNDS = new Rectangle(100, 80, 900, 600);
	private static final JPanel SOURCE = new JPanel();

	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	private int mouseX = -1;
	private int mouseY = -1;
	private long now = 5_000_000;
	private final BufferedImage canvas = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
	private final List<WorldMapPoint> points = new CopyOnWriteArrayList<>();
	private final StringBuilder out = new StringBuilder();
	private ExternalOverlays external;
	private MapSurface surface;
	private File shots;

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

	private void build()
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
		external = new ExternalOverlays();
		NativePoints nativePoints = new NativePoints(sink -> points.forEach(sink));
		com.worldmapplus.pins.RouteStore routes = new com.worldmapplus.pins.RouteStore(PinStore.Storage.inMemory(), new Gson(), pins);
		SurfaceServices services = new SurfaceServices(memory, pins, external, new MapActions(new EventBus(), external),
			new Settings(null), nativePoints, routes, new com.worldmapplus.pins.PanelLauncher());
		surface = new MapSurface(client, assets, new WorldMapPlusConfig()
		{
			@Override
			public String poiLayers()
			{
				return "";
			}

			@Override
			public boolean showHotspots()
			{
				return false;
			}

			@Override
			public boolean showLabels()
			{
				return false;
			}
		}, services);
	}

	private static WorldMapPoint partyPoint(String name, WorldPoint at)
	{
		try
		{
			Constructor<PartyMember> made = PartyMember.class.getDeclaredConstructor(long.class);
			made.setAccessible(true);
			PartyMember member = made.newInstance(9L);
			set(member, "displayName", name);
			set(member, "coord", at);
			set(member, "loggedIn", true);
			Class<?> type = Class.forName("net.runelite.client.plugins.party.PartyWorldMapPoint");
			Constructor<?> point = type.getDeclaredConstructor(WorldPoint.class, PartyMember.class);
			point.setAccessible(true);
			return (WorldMapPoint) point.newInstance(at, member);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private static void set(Object target, String field, Object value) throws ReflectiveOperationException
	{
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static BufferedImage image(int size, Color colour)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(colour);
		g.fillOval(1, 1, size - 2, size - 2);
		g.dispose();
		return image;
	}

	private void frames(int count)
	{
		for (int i = 0; i < count; i++)
		{
			Graphics2D g = canvas.createGraphics();
			now += 16;
			surface.render(g, BOUNDS, new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0)),
				new MapSurface.Options().followOnOpen(false).openZoom(4.0), now);
			g.dispose();
		}
	}

	private void lookAt(double x, double y)
	{
		frames(5);
		surface.viewer().setFollowing(false);
		surface.viewer().showMap(assets.getCatalog().surface(), x, y);
		surface.viewer().jumpZoom(4.0);
		frames(5);
	}

	private void click(int x, int y, int button)
	{
		surface.getInput().mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, button));
		now += 16;
		surface.getInput().mouseReleased(new MouseEvent(SOURCE, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, button));
		frames(2);
	}

	private int[] screen(double wx, double wy)
	{
		ViewTransform view = surface.currentView();
		return new int[] {(int) Math.round(view.screenX(wx)), (int) Math.round(view.screenY(wy))};
	}

	private void shot(String name) throws IOException
	{
		if (shots != null)
		{
			ImageIO.write(canvas, "png", new File(shots, name + ".png"));
		}
	}

	private void card(String what, double wx, double wy) throws IOException
	{
		int[] at = screen(wx, wy);
		mouseX = at[0];
		mouseY = at[1];
		frames(3);
		HoverCard card = surface.lastCard();
		out.append(what).append(" card: ");
		if (card == null)
		{
			out.append("none\n");
		}
		else
		{
			out.append(card.getTitle()).append(" / ").append(card.getSubtitle()).append(" / hint ").append(card.getHint());
			for (HoverCard.Row row : card.getRows())
			{
				out.append(" / ").append(row.getLabel()).append(" = ").append(row.getValue());
			}
			out.append('\n');
		}
		shot(what.replace(' ', '-') + "-card");
		click(at[0], at[1], MouseEvent.BUTTON3);
		out.append(what).append(" menu:");
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			out.append(" [").append(item.getLabel()).append(']');
		}
		out.append('\n');
		shot(what.replace(' ', '-') + "-menu");
		surface.menu().close();
		mouseX = -1;
		mouseY = -1;
		frames(2);
	}

	private void choose(String label)
	{
		Rectangle menu = surface.menu().getBounds();
		for (ContextMenu.Item item : surface.menu().getItems())
		{
			if (item.getLabel().equals(label))
			{
				for (int py = menu.y; py < menu.y + menu.height; py++)
				{
					if (surface.menu().hit(menu.x + 10, py) == item)
					{
						click(menu.x + 10, py, MouseEvent.BUTTON1);
						return;
					}
				}
			}
		}
		throw new AssertionError("no entry " + label);
	}

	private void camera(String when)
	{
		out.append(when).append(": camera ").append(Math.round(surface.viewer().getX() * 2) / 2.0).append(',')
			.append(Math.round(surface.viewer().getY() * 2) / 2.0).append(" map ").append(surface.viewer().getMap().getName())
			.append(" following ").append(surface.following()).append('\n');
	}

	private void replies()
	{
		for (ExternalOverlays.Reply reply : external.drainReplies(System.currentTimeMillis()))
		{
			out.append("reply ").append(reply.getName()).append(' ').append(new java.util.TreeMap<>(reply.getData())).append('\n');
		}
	}

	private void send(String name, Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2)
		{
			data.put((String) keyValues[i], keyValues[i + 1]);
		}
		external.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, name, data));
	}

	@Test
	public void otherPluginsThingsOnTheMapAreAsBeforeTheSlimming() throws IOException
	{
		String folder = System.getenv("WORLDMAPPLUS_SHOTS");
		if (folder != null && !folder.isEmpty())
		{
			shots = new File(folder, "other-plugins");
			shots.mkdirs();
		}
		build();
		points.add(partyPoint("Zezima", new WorldPoint(3200, 3200, 0)));
		WorldMapPoint quest = new WorldMapPoint(new WorldPoint(3215, 3210, 0), image(16, new Color(40, 160, 255)));
		quest.setName("Cook's Assistant");
		quest.setTooltip("Quest Helper\nTalk to the Cook\nBring an egg");
		quest.setImagePoint(new Point(8, 16));
		points.add(quest);
		WorldMapPoint golem = new WorldMapPoint(new WorldPoint(3230, 3195, 0), image(12, new Color(200, 80, 40)));
		golem.setTooltip("Golem\nRespawns soon");
		golem.setJumpOnClick(true);
		golem.setTarget(new WorldPoint(3300, 3300, 0));
		points.add(golem);
		WorldMapPoint far = new WorldMapPoint(new WorldPoint(3500, 3500, 0), image(14, new Color(255, 220, 60)));
		far.setName("Death");
		far.setSnapToEdge(true);
		points.add(far);
		send("marker", "id", "spot", "source", "gim", "point", new WorldPoint(3190, 3215, 0), "name", "Group storage", "tooltip",
			"Open the group's storage\nTwo rows\nThree rows", "clickable", true, "hint", "Open the bank", "menu",
			Arrays.asList("Route here", "Hide"));
		send("marker", "id", "plain", "source", "gim", "point", new WorldPoint(3205, 3225, 0), "color", new Color(0, 255, 120));
		send("marker", "id", "menu", "source", "gim", "point", new WorldPoint(3240, 3220, 0), "name", "Only a menu", "menu",
			Arrays.asList("Ping"));
		send("line", "id", "path", "source", "gim", "points", Arrays.asList(new WorldPoint(3190, 3190, 0), new WorldPoint(3200, 3230, 0),
			new WorldPoint(3240, 3230, 0)), "color", new Color(255, 0, 200), "label", "Group path");
		external.onPluginMessage(new PluginMessage("shortestpath", "transports", new HashMap<String, Object>()
		{
			{
				put("origin", Arrays.asList(new WorldPoint(3210, 3190, 0)));
				put("destination", Arrays.asList(new WorldPoint(3245, 3205, 0)));
				put("displayInfo", Arrays.asList("Fairy ring"));
			}
		}));
		lookAt(3215.5, 3210.5);
		frames(10);
		shot("all");
		out.append("markers shown: ").append(surface.shownMarkers().size()).append('\n');

		card("party member", 3200.5, 3200.5);
		card("quest point", 3215.5, 3210.5);
		card("jump point", 3230.5, 3195.5);
		card("clickable marker", 3190.5, 3215.5);
		card("plain marker", 3205.5, 3225.5);
		card("menu marker", 3240.5, 3220.5);
		card("empty ground", 3222.5, 3240.5);

		int[] at = screen(3190.5, 3215.5);
		click(at[0], at[1], MouseEvent.BUTTON1);
		replies();
		click(at[0], at[1], MouseEvent.BUTTON3);
		choose("Hide");
		replies();

		at = screen(3230.5, 3195.5);
		mouseX = at[0];
		mouseY = at[1];
		frames(2);
		click(at[0], at[1], MouseEvent.BUTTON1);
		frames(30);
		camera("after a click on the jump point");

		lookAt(3200.5, 3200.5);
		at = screen(3200.5, 3200.5);
		click(at[0], at[1], MouseEvent.BUTTON3);
		choose("Follow Zezima");
		camera("following");
		points.remove(0);
		points.add(0, partyPoint("Zezima", new WorldPoint(3240, 3230, 0)));
		frames(60);
		camera("after the member walked");
		shot("following");
		at = screen(3240.5, 3230.5);
		click(at[0], at[1], MouseEvent.BUTTON3);
		choose("Stop following Zezima");
		camera("stopped");

		send("focus", "point", new WorldPoint(3222, 3218, 0), "zoom", 6, "name", "Lumbridge", "id", "f-1");
		frames(30);
		camera("after a focus");
		replies();
		send("focus", "point", new WorldPoint(1, 1, 0), "id", "f-2", "exact", true);
		frames(5);
		camera("after a focus nowhere");
		replies();

		String text = out.toString();
		Path actual = Paths.get("build", "other-plugins-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "other-plugins.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String[] a = expected.split("\n", -1);
		String[] b = text.split("\n", -1);
		for (int i = 0; i < Math.min(a.length, b.length); i++)
		{
			assertEquals("line " + (i + 1), a[i], b[i]);
		}
		assertEquals("lines", a.length, b.length);
	}
}
