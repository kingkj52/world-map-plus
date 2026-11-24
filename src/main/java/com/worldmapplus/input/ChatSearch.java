package com.worldmapplus.input;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;

/**
 * Lets the search be typed into RuneLite's own chat area prompt instead of straight onto the map.
 * <p>
 * Plugins that take letters before the map sees them, such as Key Remapping with its camera keys, never lose them when the
 * chat area is taking the typing, because they stand down then. The cost is that the text is typed below the map and not on
 * it, which is why this is a setting and off by default.
 * <p>
 * One prompt exists at a time, because the chat area shows one. Opening a second, from the other map or from another plugin,
 * ends the first, and the first is told through its {@link Session}. A session is only ever read and changed through its
 * own methods, which are safe from any thread, because the prompt reports typing on the interface thread and closing on the
 * client thread while the map reads both on the client thread when it draws.
 * <p>
 * Every call into the chat area is made on the client thread. The prompt is closed only when it is still this plugin's own, so
 * a prompt some other plugin has opened since is never closed from here. Nothing is sent to the server.
 */
@Singleton
public class ChatSearch
{
	/** What the chat area is asked to do. The plugin's own implementation is {@link ChatboxPrompts}. */
	public interface Prompts
	{
		/** Opens a text prompt. Called on the client thread. */
		void open(String title, String text, Consumer<String> onChanged, Consumer<String> onDone, Runnable onClose);

		/** Closes the prompt this plugin opened if the chat area still has it, never one another plugin opened. Client thread. */
		void close();

		/** True when a point of the game canvas is inside the chat area's prompt. Called on the client thread. */
		boolean covers(int x, int y);
	}

	/** One prompt, from when it was asked for until the chat area let go of it. */
	public static final class Session
	{
		private final AtomicReference<String> text = new AtomicReference<>();
		private final AtomicBoolean done = new AtomicBoolean();
		private final AtomicBoolean closed = new AtomicBoolean();

		private Session()
		{
		}

		/** The text typed since this was last asked, or null when nothing was. */
		@Nullable
		public String takeText()
		{
			return text.getAndSet(null);
		}

		/** True once after Enter was pressed in the prompt. */
		public boolean takeDone()
		{
			return done.getAndSet(false);
		}

		/** True once the prompt is gone: Escape, Enter, another prompt taking its place, or a close from here. */
		public boolean isClosed()
		{
			return closed.get();
		}
	}

	private final Prompts prompts;
	private final Consumer<Runnable> onClientThread;

	/** The prompt this plugin has open, or null. Guarded by this. */
	private Session current;

	@Inject
	public ChatSearch(ChatboxPrompts prompts, ClientThread clientThread)
	{
		this(prompts, clientThread::invoke);
	}

	/** For tests: the prompts to use, and how to run something on the client thread. */
	public ChatSearch(Prompts prompts, Consumer<Runnable> onClientThread)
	{
		this.prompts = prompts;
		this.onClientThread = onClientThread;
	}

	/** Opens the prompt with the text already typed. Any prompt of this plugin that was open is ended. */
	public Session open(String title, String text)
	{
		Session session = new Session();
		Session replaced;
		synchronized (this)
		{
			replaced = current;
			current = session;
		}
		if (replaced != null)
		{
			replaced.closed.set(true);
		}
		onClientThread.accept(() -> prompts.open(title, text,
			value -> session.text.set(value),
			value ->
			{
				session.text.set(value);
				session.done.set(true);
			},
			() ->
			{
				session.closed.set(true);
				synchronized (ChatSearch.this)
				{
					if (current == session)
					{
						current = null;
					}
				}
			}));
		return session;
	}

	/** Closes the prompt of a session, if the chat area still has it. A prompt that has gone, or was replaced, is left alone. */
	public void close(Session session)
	{
		boolean ours;
		synchronized (this)
		{
			ours = current == session;
			if (ours)
			{
				current = null;
			}
		}
		session.closed.set(true);
		if (ours)
		{
			onClientThread.accept(prompts::close);
		}
	}

	/** Closes the prompt if one is open. For plugin shutdown. Does nothing at all when none was ever opened. */
	public void closeAll()
	{
		Session open;
		synchronized (this)
		{
			open = current;
		}
		if (open != null)
		{
			close(open);
		}
	}

	/** True while this plugin's search prompt has the chat area, so the typing guide stands down. Safe from any thread. */
	public synchronized boolean isOpen()
	{
		return current != null;
	}

	/** True when a point of the canvas is inside the chat area's prompt, so a click there does not end the search. */
	public boolean covers(int x, int y)
	{
		return prompts.covers(x, y);
	}
}
