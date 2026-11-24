package com.worldmapplus.input;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;

/**
 * Mouse input for the viewer.
 * <p>
 * Events are only consumed inside the rectangle the viewer is currently drawing in, and a press
 * that started inside keeps being consumed until its release, so a drag that wanders outside the
 * map does not leak half a gesture to the game. With nothing showing the rectangle is null and
 * every event passes through untouched.
 * <p>
 * Keys arrive the only way RuneLite offers them to a plugin, through its key manager, which gives each registered listener the
 * event in the order they registered and stops at the first that consumes it. So a key another plugin has taken before this one
 * is registered never reaches the search bar. Keys are only taken while the search bar has the keyboard (and for Ctrl+F while
 * the map is showing, which also starts taking keys at once, so the control character that Ctrl+F types and the keys typed before
 * the next frame do not reach the game), and a release is taken only when its press was, so the game never sees half a key.
 * <p>
 * While the search has the keyboard every key is taken, including function keys and chords, until Escape or a click away from
 * the map, which is how a hotkey of this plugin or another is kept from firing while the player types. A claim that the map
 * does not renew within 300 ms lapses by itself, so a map that stopped drawing cannot keep the keyboard.
 * <p>
 * Nothing here acts on the client. Events are copied into a queue for the render thread.
 */
public class ViewerInput extends MouseAdapter implements MouseWheelListener, KeyListener
{
	/** The key code the queue carries for the request to start a search. Not a real key. */
	public static final int KEY_FOCUS_SEARCH = -1;
	public static final int KEY_SELECT_ALL = -2;

	private final InputQueue queue = new InputQueue();

	/**
	 * How long input stays taken without the overlay saying again that it is still showing. An
	 * overlay is not asked to draw while its interface is closed, so it cannot say it has stopped.
	 * Letting the claim lapse means a closed map can never go on swallowing clicks or keys.
	 */
	static final long FRESH_NANOS = 300_000_000L;

	/** Where the viewer is drawing, in canvas pixels, or null when it is not showing. */
	/**
	 * Parts of the map area that belong to the game, such as its close button. Events there are never taken,
	 * so the game's own controls keep working while this map covers the rest.
	 */
	private volatile java.util.List<Rectangle> keepClear = java.util.Collections.emptyList();

	private volatile Rectangle active;
	private volatile long activeStamp;
	private volatile long captureStamp;
	private LongSupplier clock = System::nanoTime;

	/** Set when a pass-through mode is on: events are recorded and never consumed. */
	private volatile boolean passThrough;

	private volatile boolean pressInside;

	/** Whether the press that began the gesture in progress was taken, so its drag and release are treated alike. */
	private volatile boolean pressTaken = true;

	/**
	 * While this map is lined up with the game's own, the game moves and zooms it, so a click that is not on one of this map's
	 * own controls is left for the game. This says which points are this map's. Null when not lined up, and everything inside
	 * the map is then this map's.
	 */
	private volatile java.util.function.BiPredicate<Integer, Integer> takes;

	/** True when Ctrl+F is taken only while the pointer is over the map, as on the floating map. */
	private volatile boolean controlFNeedsPointer;

	/** Whether the pointer was over the map at the last frame. */
	private volatile boolean pointerOver;

	/**
	 * Says whether the game is taking typed text, so Ctrl+F is left to it. Asked on the key thread, so it must only read what the client
	 * thread has already worked out. Nothing is taken from the game before this is asked.
	 */
	private volatile java.util.function.Predicate<KeyEvent> textGuard = event -> false;

	/** True while Shift is down, which expands hover cards to show everything. */
	private volatile boolean expandHeld;

	/** True while the search field has the keyboard. */
	private volatile boolean captureKeys;

	/** True while a popup is open over the map, when the Escape key alone is taken. */
	private volatile boolean captureEscape;
	private volatile long escapeStamp;

	/** True while the search is typed into the chat area, when only presses away from the map are watched. */
	private volatile boolean watchClicks;
	private volatile long watchStamp;

	/**
	 * The key codes whose press this input took and whose release has not come yet. The game never saw those presses, so it
	 * must not see their releases, and it must see the release of every other key, such as a Ctrl that was already held when
	 * the search began, or it would think the key was still down.
	 */
	private final Set<Integer> taken = ConcurrentHashMap.newKeySet();

	public ViewerInput()
	{
	}

	/** Sets what tells that the game is taking text, so the chord that starts a search is not taken then. Null removes it. */
	public void setTextGuard(java.util.function.Predicate<KeyEvent> guard)
	{
		this.textGuard = guard == null ? event -> false : guard;
	}

	/** Set every frame by the surface: whether Ctrl+F is taken only while {@link #setPointerOver the pointer is over the map}. */
	public void setControlFNeedsPointer(boolean needs)
	{
		this.controlFNeedsPointer = needs;
	}

	/** Set every frame by the surface: whether the pointer is over the map. */
	public void setPointerOver(boolean over)
	{
		this.pointerOver = over;
	}

	/** Whether the expand key is down. Only reads the key. It never consumes it. */
	public boolean isExpandHeld()
	{
		return expandHeld;
	}

	/**
	 * Set by the overlay while the search field has the keyboard. Only then are key events taken
	 * from the game. At every other time keys pass through untouched.
	 */
	public void setCaptureKeys(boolean capture)
	{
		this.captureKeys = capture;
		if (capture)
		{
			captureStamp = clock.getAsLong();
		}
	}

	private boolean capturing()
	{
		return captureKeys && fresh(captureStamp);
	}

	/**
	 * Set by the surface every frame while the layers list or the right click menu is popped up over the map. Only the Escape key
	 * is taken then, so it puts the popup away, and the rest of the keyboard stays the game's. The claim lapses if it is not
	 * renewed, like the search's.
	 */
	public void setCaptureEscape(boolean capture)
	{
		this.captureEscape = capture;
		if (capture)
		{
			escapeStamp = clock.getAsLong();
		}
	}

	private boolean escaping()
	{
		return captureEscape && fresh(escapeStamp);
	}

	/**
	 * Set by the overlay while the search is being typed into the chat area. No key is taken then, because the chat area gets
	 * them itself. A press away from the map is still reported, with where it was, so the search can end as it does when typed
	 * on the map directly.
	 */
	public void setWatchClicks(boolean watch)
	{
		this.watchClicks = watch;
		if (watch)
		{
			watchStamp = clock.getAsLong();
		}
	}

	private boolean watchingClicks()
	{
		return watchClicks && fresh(watchStamp);
	}

	@Override
	public void keyTyped(KeyEvent event)
	{
		if (capturing())
		{
			queue.add(MapInput.typed(event.getKeyChar(), System.currentTimeMillis()));
			event.consume();
		}
		else if (escaping() && event.getKeyChar() == KeyEvent.VK_ESCAPE)
		{
			// The character Escape types goes with its press.
			event.consume();
		}
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
		if (event.getKeyCode() == KeyEvent.VK_SHIFT)
		{
			expandHeld = true;
		}
		if (capturing())
		{
			boolean selectAll = event.isControlDown() && event.getKeyCode() == KeyEvent.VK_A;
			queue.add(MapInput.key(selectAll ? KEY_SELECT_ALL : event.getKeyCode(), System.currentTimeMillis()));
			taken.add(event.getKeyCode());
			event.consume();
		}
		else if (claiming() && (!controlFNeedsPointer || pointerOver) && event.isControlDown() && event.getKeyCode() == KeyEvent.VK_F
			&& !textGuard.test(event))
		{
			// Ctrl+F while the map is showing starts a search. The key is taken from here on, in this same event: the
			// character the chord types (control F) arrives next and is taken with it, and so is anything typed before the
			// next frame says whether the search has the keyboard. A frame that finds the search did not start hands the
			// keys back, and so does a claim that is not renewed.
			queue.add(MapInput.key(KEY_FOCUS_SEARCH, System.currentTimeMillis()));
			taken.add(event.getKeyCode());
			setCaptureKeys(true);
			event.consume();
		}
		else if (escaping() && event.getKeyCode() == KeyEvent.VK_ESCAPE)
		{
			queue.add(MapInput.key(KeyEvent.VK_ESCAPE, System.currentTimeMillis()));
			taken.add(event.getKeyCode());
			event.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent event)
	{
		if (event.getKeyCode() == KeyEvent.VK_SHIFT)
		{
			expandHeld = false;
		}
		if (taken.remove(event.getKeyCode()))
		{
			event.consume();
		}
	}

	/**
	 * The game window lost the keyboard, so releases will not arrive. Forgets what was held, so no key stays down in this
	 * input and none of its later releases is taken for a press that never was.
	 */
	@Override
	public void focusLost()
	{
		taken.clear();
		expandHeld = false;
	}

	/** Replaces the clock, so a test can let time pass without waiting. */
	void setClock(LongSupplier clock)
	{
		this.clock = clock;
	}

	private boolean fresh(long stamp)
	{
		return clock.getAsLong() - stamp < FRESH_NANOS;
	}

	/** True while the viewer is showing and has said so recently. */
	private boolean claiming()
	{
		return active != null && fresh(activeStamp);
	}

	public InputQueue getQueue()
	{
		return queue;
	}

	/** Called every frame by the overlay. Null stops all consuming. */
	public void setActive(Rectangle bounds)
	{
		this.active = bounds == null ? null : new Rectangle(bounds);
		if (bounds != null)
		{
			activeStamp = clock.getAsLong();
		}
		if (bounds == null)
		{
			pressInside = false;
		}
	}

	/** Sets the places inside the map that are left to the game. Called every frame. */
	public void setKeepClear(java.util.List<Rectangle> areas)
	{
		this.keepClear = areas == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(areas);
	}

	public void setPassThrough(boolean passThrough)
	{
		this.passThrough = passThrough;
	}

	/** Sets which points inside the map are this map's own while it is lined up with the game's, or null when it is not. */
	public void setTakes(java.util.function.BiPredicate<Integer, Integer> takes)
	{
		this.takes = takes;
	}

	/**
	 * True when this map takes a mouse event that is inside it. Always, unless it is lined up with the game's map, when only
	 * its own controls are, and a right click held with Ctrl, which opens this map's menu in place of the game's.
	 */
	private boolean mine(MouseEvent event)
	{
		java.util.function.BiPredicate<Integer, Integer> test = takes;
		if (test == null)
		{
			return true;
		}
		return test.test(event.getX(), event.getY())
			|| event.getButton() == MouseEvent.BUTTON3 && event.isControlDown();
	}


	private boolean inside(MouseEvent event)
	{
		Rectangle bounds = active;
		if (bounds == null || !claiming() || !bounds.contains(event.getPoint()))
		{
			return false;
		}
		for (Rectangle area : keepClear)
		{
			if (area.contains(event.getPoint()))
			{
				return false;
			}
		}
		return true;
	}

	private MouseEvent take(MouseEvent event)
	{
		if (!passThrough)
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (!inside(event))
		{
			if (claiming())
			{
				// The game takes the click, whether it is on a control inside the map or beside it, such as the map
				// key. This map only notes where it was, so it can follow what the click did.
				queue.add(MapInput.gamePress(event.getX(), event.getY(), event.getWhen()));
			}
			if (capturing() || watchingClicks())
			{
				// A click somewhere else, such as the chat box, means the search is over. The click itself
				// goes on to the game untouched.
				queue.add(MapInput.outsidePress(event.getX(), event.getY(), event.getWhen()));
			}
			return event;
		}
		pressInside = true;
		pressTaken = mine(event);
		queue.add(MapInput.press(event.getX(), event.getY(), event.getButton(), event.getWhen(), pressTaken));
		return pressTaken ? take(event) : event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		if (!pressInside)
		{
			return event;
		}
		queue.add(MapInput.drag(event.getX(), event.getY(), event.getWhen(), pressTaken));
		return pressTaken ? take(event) : event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if (!pressInside)
		{
			return event;
		}
		pressInside = false;
		queue.add(MapInput.release(event.getX(), event.getY(), event.getButton(), event.getWhen(), pressTaken));
		return pressTaken ? take(event) : event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		// The press and release already carried everything, so the game must not also see a click.
		return inside(event) && pressTaken ? take(event) : event;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		if (!inside(event))
		{
			return event;
		}
		boolean mine = mine(event);
		queue.add(MapInput.wheel(event.getX(), event.getY(), event.getPreciseWheelRotation(), event.getWhen(), mine));
		if (mine && !passThrough)
		{
			event.consume();
		}
		return event;
	}
}
