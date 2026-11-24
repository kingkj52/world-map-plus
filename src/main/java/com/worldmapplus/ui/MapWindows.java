package com.worldmapplus.ui;

import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerLocator;
import com.worldmapplus.game.TrailTracker;
import com.worldmapplus.layers.NpcLayer;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.panel.PlacesPanel;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import com.worldmapplus.prefs.ViewMemory;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

/**
 * Extra maps in windows of their own, opened by a middle click on a map link, a search result or a step of a route.
 * <p>
 * A window is an ordinary resizable, movable, closable window holding one more {@link MapSurface}, which has its own
 * view, search box, layer choices and menu. Everything that is slow to build is shared with the main map: the
 * imagery and its cache, the search data, the saved places and the labels. A window costs a view and the time to
 * paint it, and it only paints while it can be seen: about thirty times a second while the pointer or the keyboard
 * is in it, a few times a second otherwise, and not at all while minimised or closed.
 * <p>
 * Windows are drawn on the interface thread, away from the game, and read nothing from the game but the player's
 * position as of the last game tick. They show no nearby NPCs, no farming cards and no points from other plugins,
 * which can only be read safely on the client thread. A request for Shortest Path from a window is posted on the
 * client thread. This opens nothing in the game and sends nothing to it.
 */
@Slf4j
@Singleton
public class MapWindows
{
	/** Most windows open at once. */
	static final int MAX_WINDOWS = 6;

	private static final int FAST_MILLIS = 33;
	private static final int SLOW_EVERY = 8;

	private final Client client;
	private final MapAssets assets;
	private final WorldMapPlusConfig config;
	private final PlayerLocator locator;
	private final ClientThread clientThread;
	private final SurfaceServices services;
	private final List<Window> open = new ArrayList<>();
	private int opened;
	private int failures;

	@Inject
	public MapWindows(Client client, MapAssets assets, WorldMapPlusConfig config, PinStore pins, RouteStore routes,
		ExternalOverlays external, MapActions actions, Settings settings, PanelLauncher launcher, PlayerLocator locator,
		ClientThread clientThread)
	{
		this.client = client;
		this.assets = assets;
		this.config = config;
		this.locator = locator;
		this.clientThread = clientThread;
		// What a window is given: the shared saved places and routes, and nothing that has to be read from the game.
		this.services = new SurfaceServices(new QuietNpcLayer(), new NoMemory(), pins, external, actions, settings,
			new TrailTracker(), null, null, routes, launcher);
	}

	/** An NPC layer that draws nothing, since NPCs are read from the client thread. */
	private static final class QuietNpcLayer extends NpcLayer
	{
		@Override
		public void paint(Graphics2D g, ViewTransform view, MapInfo viewed, com.worldmapplus.game.PlayerPosition player)
		{
		}
	}

	/** A view memory that remembers nothing, so a window leaves the saved view of the main map alone. */
	private static final class NoMemory extends ViewMemory
	{
		NoMemory()
		{
			super(null);
		}

		@Override
		public void save(String slot, int mapId, double x, double y, double pixelsPerTile)
		{
		}

		@Override
		public Saved load(String slot)
		{
			return null;
		}
	}

	/** False while the plugin is off, when no window may be opened. */
	private volatile boolean enabled = true;

	/** Turns opening windows on or off with the plugin. Turning it off closes the windows that are open. */
	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
		if (!enabled)
		{
			closeAll();
		}
	}

	/** Opens a map on a place in a new window. Safe to call from any thread. */
	public void open(int mapId, double x, double y, double zoom)
	{
		if (enabled)
		{
			SwingUtilities.invokeLater(() -> create(mapId, x, y, zoom));
		}
	}

	/** How many windows stopped drawing because of an error, for tests. */
	synchronized int failures()
	{
		return failures;
	}

	/** How many windows are open, for tests. */
	synchronized int count()
	{
		return open.size();
	}

	private void create(int mapId, double x, double y, double zoom)
	{
		if (!enabled)
		{
			return;
		}
		MapInfo map = assets.getCatalog().byId(mapId);
		if (map == null || Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(zoom))
		{
			return;
		}
		synchronized (this)
		{
			if (open.size() >= MAX_WINDOWS)
			{
				// Too many to add another, so the newest is brought forward instead.
				open.get(open.size() - 1).frame.toFront();
				return;
			}
		}
		Window window = new Window(map, x, y, Math.max(0.05, Math.min(64, zoom)));
		synchronized (this)
		{
			open.add(window);
		}
		window.show();
	}

	/** Closes every window, when the plugin shuts down. */
	public void closeAll()
	{
		SwingUtilities.invokeLater(() ->
		{
			List<Window> all;
			synchronized (this)
			{
				all = new ArrayList<>(open);
			}
			for (Window window : all)
			{
				window.close();
			}
		});
	}

	private synchronized void forget(Window window)
	{
		open.remove(window);
	}

	/** One window: a frame, the canvas that draws the map, and the timer that keeps it moving. */
	private final class Window
	{
		private final JFrame frame;
		private final Canvas canvas;
		private final MapSurface surface;
		private final Timer timer;
		private int idleTicks;
		private boolean failed;

		Window(MapInfo map, double x, double y, double zoom)
		{
			surface = new MapSurface(client, assets, config, services);
			canvas = new Canvas();
			surface.asWindow(() -> canvas.pointer, task -> clientThread.invokeLater(task));
			surface.setWindowOpener(MapWindows.this::open);
			surface.startAt(map, x, y, zoom);

			opened++;
			frame = new JFrame("World Map Plus: " + map.getName());
			frame.setIconImage(PlacesPanel.icon());
			frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
			frame.setContentPane(canvas);
			frame.setMinimumSize(new Dimension(320, 240));
			frame.pack();
			frame.setLocationByPlatform(true);
			frame.addWindowListener(new WindowAdapter()
			{
				@Override
				public void windowClosed(WindowEvent e)
				{
					timer.stop();
					surface.reset();
					forget(Window.this);
				}

				@Override
				public void windowIconified(WindowEvent e)
				{
					timer.stop();
				}

				@Override
				public void windowDeiconified(WindowEvent e)
				{
					timer.start();
				}
			});
			timer = new Timer(FAST_MILLIS, e -> tick());
		}

		void show()
		{
			frame.setVisible(true);
			timer.start();
		}

		void close()
		{
			frame.dispose();
		}

		/** Paints often while the window is in use and rarely while it is only being looked at. */
		private void tick()
		{
			if (!frame.isShowing())
			{
				return;
			}
			boolean busy = frame.isActive() || canvas.pointer != null || surface.isMoving();
			if (busy || ++idleTicks >= SLOW_EVERY)
			{
				idleTicks = 0;
				canvas.repaint();
			}
		}

		/** The surface that draws the window and takes what is done in it. */
		private final class Canvas extends JPanel
		{
			private volatile Point pointer;

			Canvas()
			{
				setPreferredSize(new Dimension(900, 640));
				setBackground(Color.BLACK);
				setFocusable(true);
				// Tab finishes a search word, so the window must not use it to move between controls.
				setFocusTraversalKeysEnabled(false);
				MouseAdapter mouse = new MouseAdapter()
				{
					@Override
					public void mousePressed(MouseEvent e)
					{
						requestFocusInWindow();
						arm();
						pointer = e.getPoint();
						surface.getInput().mousePressed(e);
						repaint();
					}

					@Override
					public void mouseReleased(MouseEvent e)
					{
						arm();
						pointer = e.getPoint();
						surface.getInput().mouseReleased(e);
						repaint();
					}

					@Override
					public void mouseDragged(MouseEvent e)
					{
						arm();
						pointer = e.getPoint();
						surface.getInput().mouseDragged(e);
						repaint();
					}

					@Override
					public void mouseMoved(MouseEvent e)
					{
						pointer = e.getPoint();
					}

					@Override
					public void mouseEntered(MouseEvent e)
					{
						pointer = e.getPoint();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						pointer = null;
					}

					@Override
					public void mouseWheelMoved(MouseWheelEvent e)
					{
						arm();
						pointer = e.getPoint();
						surface.getInput().mouseWheelMoved(e);
						repaint();
					}
				};
				addMouseListener(mouse);
				addMouseMotionListener(mouse);
				addMouseWheelListener(mouse);
				addKeyListener(new KeyAdapter()
				{
					@Override
					public void keyTyped(KeyEvent e)
					{
						arm();
						surface.getInput().keyTyped(e);
						repaint();
					}

					@Override
					public void keyPressed(KeyEvent e)
					{
						arm();
						surface.getInput().keyPressed(e);
						repaint();
					}

					@Override
					public void keyReleased(KeyEvent e)
					{
						surface.getInput().keyReleased(e);
					}
				});
			}

			/** Says the window is in use, so its input is taken even if it has not been painted for a moment. */
			private void arm()
			{
				surface.getInput().setActive(new Rectangle(0, 0, getWidth(), getHeight()));
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				if (failed)
				{
					return;
				}
				Graphics2D g2 = (Graphics2D) g.create();
				try
				{
					Rectangle bounds = new Rectangle(0, 0, getWidth(), getHeight());
					surface.getInput().setActive(bounds);
					MapSurface.Options options = new MapSurface.Options()
						.memorySlot("window" + opened)
						.followOnOpen(false)
						.resume(true);
					surface.render(g2, bounds, locator.latest(), options, System.currentTimeMillis());
				}
				catch (RuntimeException e)
				{
					// One failed window must not take the interface thread down with it.
					failed = true;
					timer.stop();
					failures++;
					log.warn("World Map Plus: a map window stopped drawing", e);
				}
				finally
				{
					g2.dispose();
				}
			}
		}
	}
}
