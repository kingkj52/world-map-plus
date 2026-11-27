package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.input.ViewerInput;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.MapViewer;
import java.awt.AWTEvent;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.swing.JPanel;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Round 75: what the map's input does, recorded before the zoom and drag code of the three surfaces was made one path.
 * <p>
 * Each sequence (wheel turns slow, fast and fractional, drags slow and flung, clicks and double clicks on the map and on the zoom
 * buttons, keys held and typed, the floating map's click-through switched, maps closed and opened again) is replayed on a fresh
 * surface set up the way each host sets it up (the window over the world map and the floating map; round 76 folded the extra map windows into the floating map, so their part of the recording went), with a
 * clock the test moves, and every frame's camera centre, zoom shown, zoom aimed at and following is written down, with whether each
 * event was taken from the game. {@code golden/input-replay.txt} was written by the code before round 75 changed anything; the same
 * sequences must give the same lines, numbers within {@link #TOLERANCE}. What is found now is written to
 * {@code build/input-replay-actual.txt}.
 */
public class InputReplayTest
{
	/** Numbers may differ by this much from the recording, in tiles and in pixels per tile. */
	static final double TOLERANCE = 1e-6;

	private static final JPanel SOURCE = new JPanel();
	private static ScheduledExecutorService executor;
	private static MapAssets assets;

	@BeforeClass
	public static void assemble()
	{
		executor = Executors.newScheduledThreadPool(2);
		assets = new MapAssets(new Gson(), executor);
		// The data loads on first use, at once; reading it here keeps the first sequence from timing anything else.
		assets.getElements();
	}

	@AfterClass
	public static void shutDown()
	{
		executor.shutdownNow();
	}

	enum Host
	{
		MAIN, FLOATING
	}

	/** Plays events and frames on one surface the way its host does, and writes down what happened. */
	private static final class Driver
	{
		final Host host;
		final StringBuilder out;
		final MapSurface surface;
		final Rectangle bounds;
		final BufferedImage canvas = new BufferedImage(1300, 900, BufferedImage.TYPE_INT_RGB);
		final long step;
		long now = 5_000_000;
		int mouseX = -1;
		int mouseY = -1;
		boolean clickThrough = true;
		boolean shown = true;
		int frame;

		Driver(Host host, StringBuilder out)
		{
			this.host = host;
			this.out = out;
			Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
				(proxy, method, args) ->
				{
					if (method.getName().equals("getMouseCanvasPosition"))
					{
						return new net.runelite.api.Point(mouseX, mouseY);
					}
					Class<?> type = method.getReturnType();
					if (type == boolean.class)
					{
						return false;
					}
					if (type == int.class || type == long.class || type == double.class || type == float.class)
					{
						return 0;
					}
					return null;
				});
			Gson gson = new Gson();
			PinStore pins = new PinStore(PinStore.Storage.inMemory(), gson);
			ExternalOverlays external = new ExternalOverlays();
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
			SurfaceServices services = new SurfaceServices(memory, pins, external, new MapActions(new EventBus(), external),
				new Settings(null)
				{
					@Override
					public void setBoolean(String key, boolean value)
					{
					}
				}, null, new RouteStore(PinStore.Storage.inMemory(), gson, pins), new PanelLauncher());
			surface = new MapSurface(client, assets, new WorldMapPlusConfig()
			{
			}, services);
			switch (host)
			{
				case MAIN:
					bounds = new Rectangle(100, 80, 900, 600);
					step = 20;
					break;
				default:
					bounds = HudLayout.compute(new Rectangle(4, 4, 1200, 800), WorldMapPlusConfig.HudSize.MEDIUM.percent(),
						WorldMapPlusConfig.HudCorner.TOP_LEFT);
					step = 20;
					break;
			}
		}

		ViewerInput input()
		{
			return surface.getInput();
		}

		MapSurface.Options options()
		{
			switch (host)
			{
				case MAIN:
					// As the window over the world map with the default settings: open at the player and follow, carry on after a short gap.
					return new MapSurface.Options().memorySlot(ViewMemory.WORLD_MAP).followOnOpen(true).startAtLastView(false)
						.resume(true).keepClear(Collections.singletonList(new Rectangle(970, 84, 24, 24)));
				default:
					// As the floating map with the default settings, with one of the game's panels over its lower left corner.
					return new MapSurface.Options().memorySlot(ViewMemory.HUD).openZoom(3).followOnOpen(true).resume(false)
						.alpha(0.65f).interactive(!clickThrough).controlFOverMapOnly(true)
						.passInput(clickThrough ? Collections.emptyList()
							: Collections.singletonList(new Rectangle(bounds.x, bounds.y + bounds.height - 60, 90, 60)))
						.showFrame(true);
			}
		}

		/** One frame, as the host draws it, and a line saying where the map is. */
		void frame()
		{
			now += step;
			frame++;
			if (!shown)
			{
				if (host == Host.MAIN)
				{
					surface.suspend();
				}
				else
				{
					surface.hide();
				}
				out.append(String.format(Locale.ROOT, "  f%d hidden\n", frame));
				return;
			}
			Graphics2D g = canvas.createGraphics();
			surface.render(g, bounds, player(), options(), now);
			g.dispose();
			MapViewer viewer = surface.viewer();
			out.append(String.format(Locale.ROOT, "  f%d x=%.6f y=%.6f z=%.6f to=%.6f%s%s\n", frame, viewer.getX(),
				viewer.getY(), viewer.pixelsPerTile(), viewer.targetZoom(),
				viewer.isFollowing() ? " follow" : "", viewer.getMap() == null ? "" : " map " + viewer.getMap().getId()));
		}

		void frames(int count)
		{
			for (int i = 0; i < count; i++)
			{
				frame();
			}
		}

		private static PlayerPosition player()
		{
			return new PlayerPosition(3222.5, 3218.5, new WorldPoint(3222, 3218, 0));
		}

		private void note(String what, AWTEvent event, boolean consumed)
		{
			out.append(String.format(Locale.ROOT, "  %s%s\n", what, consumed ? " taken" : " left"));
		}

		void wheel(int x, int y, double rotation)
		{
			mouseX = x;
			mouseY = y;
			MouseWheelEvent event = new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, now, 0, x, y, x, y, 0, false,
				MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, (int) Math.signum(rotation), rotation);
			input().mouseWheelMoved(event);
			note(String.format(Locale.ROOT, "wheel %d,%d %.2f", x, y, rotation), event, event.isConsumed());
		}

		private MouseEvent mouse(int id, int x, int y, int button, int modifiers, int clicks)
		{
			return new MouseEvent(SOURCE, id, now, modifiers, x, y, clicks, false, button);
		}

		void press(int x, int y, int button)
		{
			press(x, y, button, 0, 1);
		}

		void press(int x, int y, int button, int modifiers, int clicks)
		{
			mouseX = x;
			mouseY = y;
			MouseEvent event = mouse(MouseEvent.MOUSE_PRESSED, x, y, button, modifiers, clicks);
			input().mousePressed(event);
			note("press " + x + "," + y + " b" + button, event, event.isConsumed());
		}

		void drag(int x, int y)
		{
			mouseX = x;
			mouseY = y;
			MouseEvent event = mouse(MouseEvent.MOUSE_DRAGGED, x, y, MouseEvent.NOBUTTON, 0, 0);
			input().mouseDragged(event);
			note("drag " + x + "," + y, event, event.isConsumed());
		}

		void release(int x, int y, int button)
		{
			release(x, y, button, 0, 1);
		}

		void release(int x, int y, int button, int modifiers, int clicks)
		{
			mouseX = x;
			mouseY = y;
			MouseEvent event = mouse(MouseEvent.MOUSE_RELEASED, x, y, button, modifiers, clicks);
			input().mouseReleased(event);
			MouseEvent click = mouse(MouseEvent.MOUSE_CLICKED, x, y, button, modifiers, clicks);
			input().mouseClicked(click);
			note("release " + x + "," + y + " b" + button + (click.isConsumed() ? " click taken" : " click left"), event,
				event.isConsumed());
		}

		/** A press, a short wait and a release where it went down. */
		void click(int x, int y, int button)
		{
			press(x, y, button);
			now += 40;
			release(x, y, button);
		}

		void move(int x, int y)
		{
			mouseX = x;
			mouseY = y;
		}

		void keyDown(int code, char c, int modifiers)
		{
			KeyEvent event = new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, now, modifiers, code, c);
			input().keyPressed(event);
			note("key down " + code + " mods " + modifiers + " held " + input().isExpandHeld(), event, event.isConsumed());
			if (c != KeyEvent.CHAR_UNDEFINED)
			{
				KeyEvent typed = new KeyEvent(SOURCE, KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, c);
				input().keyTyped(typed);
				note("typed " + (int) c, typed, typed.isConsumed());
			}
		}

		void keyUp(int code, char c, int modifiers)
		{
			KeyEvent event = new KeyEvent(SOURCE, KeyEvent.KEY_RELEASED, now, modifiers, code, c);
			input().keyReleased(event);
			note("key up " + code + " held " + input().isExpandHeld(), event, event.isConsumed());
		}

		void key(int code, char c, int modifiers)
		{
			keyDown(code, c, modifiers);
			keyUp(code, c, modifiers);
		}

		void search()
		{
			out.append(String.format(Locale.ROOT, "  search focused=%b text=%s\n", surface.searchBox().isFocused(),
				surface.searchBox().getText()));
		}

		Rectangle button(MapButtons.Button which)
		{
			return surface.buttons().boundsOf(which);
		}

		int cx()
		{
			return bounds.x + bounds.width / 2;
		}

		int cy()
		{
			return bounds.y + bounds.height / 2;
		}
	}

	/** Every sequence, by name. Each runs on a fresh surface of each host it is given for. */
	private static Map<String, Consumer<Driver>> sequences()
	{
		Map<String, Consumer<Driver>> all = new LinkedHashMap<>();
		all.put("slow wheel in, one notch at a time", d ->
		{
			d.frames(3);
			for (int i = 0; i < 6; i++)
			{
				d.wheel(d.cx() + 60, d.cy() - 40, -1);
				d.frames(20);
			}
		});
		all.put("fast wheel in, building momentum", d ->
		{
			d.frames(3);
			for (int i = 0; i < 12; i++)
			{
				d.wheel(d.cx() - 80, d.cy() + 30, -1);
				d.now += 25 - d.step;
				d.frame();
			}
			d.frames(25);
		});
		all.put("touchpad wheel out in small steps", d ->
		{
			d.frames(3);
			for (int i = 0; i < 20; i++)
			{
				d.wheel(d.cx() + 20, d.cy() + 20, 0.3);
				if (i % 2 == 1)
				{
					d.frame();
				}
			}
			d.frames(25);
		});
		all.put("wheel in and out with a pause", d ->
		{
			d.frames(3);
			for (int i = 0; i < 3; i++)
			{
				d.wheel(d.cx(), d.cy(), -1);
				d.frame();
			}
			d.frames(12);
			for (int i = 0; i < 3; i++)
			{
				d.wheel(d.cx() + 100, d.cy() + 50, 2);
				d.frame();
			}
			d.frames(20);
		});
		all.put("wheel at the zoom limits", d ->
		{
			d.frames(3);
			for (int i = 0; i < 40; i++)
			{
				d.wheel(d.cx(), d.cy(), 3);
				d.frame();
			}
			d.frames(10);
			for (int i = 0; i < 60; i++)
			{
				d.wheel(d.cx() - 30, d.cy(), -3);
				d.frame();
			}
			d.frames(10);
		});
		all.put("slow drag, rest, release", d ->
		{
			d.frames(3);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			d.frame();
			for (int i = 1; i <= 20; i++)
			{
				d.drag(d.cx() - i * 5, d.cy() + i * 3);
				d.frame();
			}
			d.frames(8);
			d.release(d.cx() - 100, d.cy() + 60, MouseEvent.BUTTON1);
			d.frames(20);
		});
		all.put("flung drag carries on", d ->
		{
			d.frames(3);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			for (int i = 1; i <= 5; i++)
			{
				d.now += 10;
				d.drag(d.cx() + i * 30, d.cy() - i * 12);
			}
			d.release(d.cx() + 150, d.cy() - 60, MouseEvent.BUTTON1);
			d.frames(40);
		});
		all.put("drag while the zoom eases, then the wheel while it coasts", d ->
		{
			d.frames(3);
			d.wheel(d.cx(), d.cy(), -1);
			d.wheel(d.cx(), d.cy(), -1);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			d.frame();
			for (int i = 1; i <= 6; i++)
			{
				d.drag(d.cx() + i * 20, d.cy());
				d.frame();
			}
			d.release(d.cx() + 120, d.cy(), MouseEvent.BUTTON1);
			d.frames(3);
			d.wheel(d.cx() + 120, d.cy(), 1);
			d.frames(30);
		});
		all.put("a press that moves less than a drag is a click", d ->
		{
			d.frames(3);
			d.press(d.cx() + 7, d.cy() + 9, MouseEvent.BUTTON1);
			d.drag(d.cx() + 9, d.cy() + 10);
			d.frame();
			d.release(d.cx() + 9, d.cy() + 10, MouseEvent.BUTTON1);
			d.frames(10);
		});
		all.put("double click on the map", d ->
		{
			d.frames(3);
			d.press(d.cx() - 50, d.cy() - 50, MouseEvent.BUTTON1, 0, 1);
			d.now += 30;
			d.release(d.cx() - 50, d.cy() - 50, MouseEvent.BUTTON1, 0, 1);
			d.frame();
			d.press(d.cx() - 50, d.cy() - 50, MouseEvent.BUTTON1, 0, 2);
			d.now += 30;
			d.release(d.cx() - 50, d.cy() - 50, MouseEvent.BUTTON1, 0, 2);
			d.frames(15);
		});
		all.put("zoom buttons, double clicked in and once out", d ->
		{
			d.frames(3);
			Rectangle in = d.button(MapButtons.Button.ZOOM_IN);
			Rectangle out = d.button(MapButtons.Button.ZOOM_OUT);
			d.click((int) in.getCenterX(), (int) in.getCenterY(), MouseEvent.BUTTON1);
			d.frame();
			d.click((int) in.getCenterX(), (int) in.getCenterY(), MouseEvent.BUTTON1);
			d.frames(20);
			d.click((int) out.getCenterX(), (int) out.getCenterY(), MouseEvent.BUTTON1);
			d.frames(20);
			d.wheel((int) in.getCenterX(), (int) in.getCenterY(), -1);
			d.frames(5);
		});
		all.put("drag away from the player, then Centre on me", d ->
		{
			d.frames(3);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			for (int i = 1; i <= 10; i++)
			{
				d.drag(d.cx() + i * 15, d.cy() + i * 10);
				d.frame();
			}
			d.release(d.cx() + 150, d.cy() + 100, MouseEvent.BUTTON1);
			d.frames(10);
			Rectangle me = d.button(MapButtons.Button.LOCATE);
			d.click((int) me.getCenterX(), (int) me.getCenterY(), MouseEvent.BUTTON1);
			d.frames(30);
		});
		all.put("right and middle drags do not move the map", d ->
		{
			d.frames(3);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON3);
			for (int i = 1; i <= 5; i++)
			{
				d.drag(d.cx() + i * 20, d.cy());
				d.frame();
			}
			d.release(d.cx() + 100, d.cy(), MouseEvent.BUTTON3);
			d.frames(3);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON2);
			for (int i = 1; i <= 5; i++)
			{
				d.drag(d.cx(), d.cy() + i * 20);
				d.frame();
			}
			d.release(d.cx(), d.cy() + 100, MouseEvent.BUTTON2);
			d.frames(3);
		});
		all.put("a drag that leaves the map keeps going", d ->
		{
			d.frames(3);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			for (int i = 1; i <= 12; i++)
			{
				d.drag(d.cx() + i * 60, d.cy());
				d.frame();
			}
			d.release(d.cx() + 720, d.cy(), MouseEvent.BUTTON1);
			d.frames(10);
			d.wheel(d.bounds.x + d.bounds.width + 30, d.cy(), -1);
			d.press(d.bounds.x + d.bounds.width + 30, d.cy(), MouseEvent.BUTTON1);
			d.release(d.bounds.x + d.bounds.width + 30, d.cy(), MouseEvent.BUTTON1);
			d.frames(3);
		});
		all.put("keys: Shift held, Ctrl and F, typing, Escape", d ->
		{
			d.move(d.cx(), d.cy());
			d.frames(3);
			d.keyDown(KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, InputEvent.SHIFT_DOWN_MASK);
			d.frames(2);
			d.keyUp(KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, 0);
			d.key(KeyEvent.VK_W, 'w', 0);
			d.keyDown(KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
			d.keyDown(KeyEvent.VK_F, (char) 6, InputEvent.CTRL_DOWN_MASK);
			d.keyUp(KeyEvent.VK_F, (char) 6, InputEvent.CTRL_DOWN_MASK);
			d.keyUp(KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, 0);
			d.frames(2);
			d.search();
			for (char c : "bank".toCharArray())
			{
				d.key(KeyEvent.getExtendedKeyCodeForChar(c), c, 0);
			}
			d.frames(2);
			d.search();
			d.wheel(d.cx(), d.cy() + 100, -1);
			d.frames(10);
			d.key(KeyEvent.VK_ESCAPE, (char) 27, 0);
			d.frames(2);
			d.search();
			d.key(KeyEvent.VK_ESCAPE, (char) 27, 0);
			d.frames(2);
			d.search();
			d.key(KeyEvent.VK_ESCAPE, (char) 27, 0);
			d.frames(2);
		});
		all.put("closed and opened again", d ->
		{
			d.frames(3);
			d.wheel(d.cx(), d.cy(), -2);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			for (int i = 1; i <= 6; i++)
			{
				d.drag(d.cx() + i * 15, d.cy());
				d.frame();
			}
			d.shown = false;
			d.frames(5);
			d.drag(d.cx() + 120, d.cy());
			d.release(d.cx() + 120, d.cy(), MouseEvent.BUTTON1);
			d.now += 2_000;
			d.shown = true;
			d.frames(20);
		});
		return all;
	}

	/** Sequences only the floating map has: its click-through switched, as the hotkey and the setting switch it. */
	private static Map<String, Consumer<Driver>> floatingSequences()
	{
		Map<String, Consumer<Driver>> all = new LinkedHashMap<>();
		all.put("click-through on: everything reaches the game", d ->
		{
			d.clickThrough = true;
			d.frames(3);
			d.wheel(d.cx(), d.cy(), -1);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			d.drag(d.cx() + 40, d.cy());
			d.release(d.cx() + 40, d.cy(), MouseEvent.BUTTON1);
			d.keyDown(KeyEvent.VK_F, (char) 6, InputEvent.CTRL_DOWN_MASK);
			d.frames(5);
			d.search();
		});
		all.put("click-through switched off, used, and on again mid drag", d ->
		{
			d.clickThrough = true;
			d.frames(3);
			d.clickThrough = false;
			d.frames(2);
			d.wheel(d.cx(), d.cy(), -1);
			d.frames(10);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			for (int i = 1; i <= 4; i++)
			{
				d.drag(d.cx() - i * 20, d.cy());
				d.frame();
			}
			d.clickThrough = true;
			d.frames(2);
			d.drag(d.cx() - 100, d.cy());
			d.release(d.cx() - 100, d.cy(), MouseEvent.BUTTON1);
			d.frames(3);
			d.wheel(d.cx(), d.cy(), -1);
			d.frames(3);
			d.clickThrough = false;
			d.frames(2);
			d.press(d.bounds.x + 20, d.bounds.y + d.bounds.height - 20, MouseEvent.BUTTON1);
			d.release(d.bounds.x + 20, d.bounds.y + d.bounds.height - 20, MouseEvent.BUTTON1);
			d.wheel(d.bounds.x + 20, d.bounds.y + d.bounds.height - 20, -1);
			d.frames(3);
		});
		all.put("hidden and shown again starts at the player", d ->
		{
			d.clickThrough = false;
			d.frames(3);
			d.wheel(d.cx(), d.cy(), -2);
			d.press(d.cx(), d.cy(), MouseEvent.BUTTON1);
			d.drag(d.cx() + 60, d.cy());
			d.release(d.cx() + 60, d.cy(), MouseEvent.BUTTON1);
			d.frames(10);
			d.shown = false;
			d.frames(3);
			d.shown = true;
			d.frames(10);
		});
		return all;
	}

	@Test
	public void inputGivesTheSameViewsAsBeforeRound75() throws IOException
	{
		StringBuilder out = new StringBuilder("# round 75 input replay: per frame the camera centre, zoom shown and aimed at, following\n");
		for (Host host : Host.values())
		{
			Map<String, Consumer<Driver>> all = new LinkedHashMap<>(sequences());
			if (host == Host.FLOATING)
			{
				all.putAll(floatingSequences());
			}
			for (Map.Entry<String, Consumer<Driver>> sequence : all.entrySet())
			{
				out.append(host).append(": ").append(sequence.getKey()).append('\n');
				Driver driver = new Driver(host, out);
				if (host == Host.FLOATING && !floatingSequences().containsKey(sequence.getKey()))
				{
					// The shared sequences are played with the floating map taking the mouse.
					driver.clickThrough = false;
				}
				sequence.getValue().accept(driver);
			}
		}
		String text = out.toString();
		Path actual = Paths.get("build", "input-replay-actual.txt");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path golden = Paths.get("src", "test", "resources", "golden", "input-replay.txt");
		Assume.assumeTrue("no golden file yet", Files.exists(golden));
		String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
		compare(expected, text);
	}

	/** Line by line; words must be equal and numbers within {@link #TOLERANCE}. */
	static void compare(String expected, String actual)
	{
		String[] a = expected.split("\n", -1);
		String[] b = actual.split("\n", -1);
		List<String> context = new ArrayList<>();
		for (int i = 0; i < Math.min(a.length, b.length); i++)
		{
			if (!a[i].startsWith("  "))
			{
				context.clear();
				context.add(a[i]);
			}
			assertTrue("line " + (i + 1) + " in " + context + ": expected <" + a[i] + "> but was <" + b[i] + ">", same(a[i], b[i]));
		}
		assertEquals("lines", a.length, b.length);
	}

	private static boolean same(String a, String b)
	{
		if (a.equals(b))
		{
			return true;
		}
		String[] x = a.split("[ =]");
		String[] y = b.split("[ =]");
		if (x.length != y.length)
		{
			return false;
		}
		for (int i = 0; i < x.length; i++)
		{
			if (x[i].equals(y[i]))
			{
				continue;
			}
			try
			{
				if (Math.abs(Double.parseDouble(x[i]) - Double.parseDouble(y[i])) > TOLERANCE)
				{
					return false;
				}
			}
			catch (NumberFormatException e)
			{
				return false;
			}
		}
		return true;
	}
}
