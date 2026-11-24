package com.worldmapplus.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import org.junit.Before;
import org.junit.Test;

/**
 * The adapter against the real {@link ChatboxPanelManager}, built by an injector the way RuneLite builds it. Only the text input
 * it hands out and the client thread are stand-ins, so what is checked is what this plugin asks of RuneLite: the title, the
 * starting text, the three callbacks, and that closing goes through the client thread.
 */
public class ChatboxPromptsTest
{
	/** A text input that records how it was set up and draws nothing. */
	private static final class RecordingInput extends ChatboxTextInput
	{
		int builds;

		RecordingInput(ChatboxPanelManager manager, ClientThread clientThread)
		{
			super(manager, clientThread);
		}

		@Override
		public ChatboxTextInput build()
		{
			builds++;
			return this;
		}
	}

	/** Counts the work queued for later instead of running it, so a test decides when the client thread gets to it. */
	private static final class LaterThread extends ClientThread
	{
		final List<Runnable> later = new ArrayList<>();

		@Override
		public void invokeLater(Runnable run)
		{
			later.add(run);
		}

		@Override
		public void invoke(Runnable run)
		{
			run.run();
		}
	}

	private final AtomicReference<RecordingInput> made = new AtomicReference<>();
	private final AtomicInteger scripts = new AtomicInteger();
	private LaterThread clientThread;
	private ChatboxPrompts prompts;
	private ChatboxPanelManager manager;

	@SuppressWarnings("unchecked")
	private static <T> T withoutConstructor(Class<T> type) throws Exception
	{
		Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		field.setAccessible(true);
		Object unsafe = field.get(null);
		return (T) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
	}

	@Before
	public void setUp() throws Exception
	{
		clientThread = new LaterThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("runScript"))
				{
					scripts.incrementAndGet();
				}
				if (method.getReturnType() == boolean.class)
				{
					return method.getName().equals("isClientThread");
				}
				return method.getReturnType().isPrimitive() ? 0 : null;
			});
		KeyManager keyManager = withoutConstructor(KeyManager.class);
		MouseManager mouseManager = withoutConstructor(MouseManager.class);
		// Built without a constructor, so the lists the managers keep their listeners in are not there. A close unregisters.
		giveLists(keyManager, "keyListeners");
		giveLists(mouseManager, "mouseListeners", "mouseWheelListeners");
		com.google.inject.Injector injector = Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				bind(Client.class).toInstance(client);
				bind(ClientThread.class).toInstance(clientThread);
				bind(EventBus.class).toInstance(new EventBus());
				bind(KeyManager.class).toInstance(keyManager);
				bind(MouseManager.class).toInstance(mouseManager);
			}

			@Provides
			@Singleton
			ChatboxTextInput input(ChatboxPanelManager manager)
			{
				RecordingInput input = new RecordingInput(manager, clientThread);
				made.set(input);
				return input;
			}
		});
		prompts = injector.getInstance(ChatboxPrompts.class);
		manager = injector.getInstance(ChatboxPanelManager.class);
	}

	private static void giveLists(Object manager, String... names) throws Exception
	{
		for (String name : names)
		{
			Field field = manager.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(manager, new java.util.concurrent.CopyOnWriteArrayList<>());
		}
	}

	/** Makes a prompt the manager's current input, which is what the manager's own, queued open does when it runs. */
	private void makeCurrent(Object input) throws Exception
	{
		Field field = ChatboxPanelManager.class.getDeclaredField("currentInput");
		field.setAccessible(true);
		field.set(manager, input);
	}

	@Test
	public void theTitleTheStartingTextAndTheCallbacksAreWhatWasAskedFor()
	{
		List<String> changed = new ArrayList<>();
		List<String> done = new ArrayList<>();
		AtomicInteger closed = new AtomicInteger();
		Runnable onClose = closed::incrementAndGet;

		prompts.open("Search the map", "bank", changed::add, done::add, onClose);

		RecordingInput input = made.get();
		assertEquals(1, input.builds);
		assertEquals("Search the map", input.getPrompt());
		assertEquals("bank", input.getValue());
		assertSame(onClose, input.getOnClose());

		input.getOnChanged().accept("bank n");
		assertEquals(List.of("bank n"), changed);
		assertTrue("Enter closes the prompt after reporting", input.getOnDone().test("bank note"));
		assertEquals(List.of("bank note"), done);
		input.getOnClose().run();
		assertEquals(1, closed.get());
	}

	@Test
	public void closingChecksThatThePromptIsStillOursOnTheClientThreadThenClosesIt() throws Exception
	{
		prompts.open("Search the map", "", v -> { }, v -> { }, () -> { });
		makeCurrent(made.get());
		assertEquals("opening is the text input's own business and queues nothing here", 0, clientThread.later.size());

		prompts.close();
		assertEquals("the check is queued for the client thread, and nothing is closed yet", 1, clientThread.later.size());
		assertEquals(0, scripts.get());
		clientThread.later.get(0).run();
		assertEquals("our prompt was current, so RuneLite's close is queued", 2, clientThread.later.size());
		assertEquals(0, scripts.get());
		clientThread.later.get(1).run();
		assertEquals("and that close runs the game's script once, there", 1, scripts.get());
	}

	@Test
	public void aPromptAnotherPluginOpenedBeforeTheCheckRunsIsNotClosed() throws Exception
	{
		prompts.open("Search the map", "", v -> { }, v -> { }, () -> { });
		makeCurrent(made.get());
		prompts.close();
		RecordingInput theirs = new RecordingInput(manager, clientThread);
		makeCurrent(theirs);
		clientThread.later.get(0).run();
		assertEquals("nothing was queued to close it", 1, clientThread.later.size());
		assertEquals(0, scripts.get());
		assertSame("their prompt is still the current one", theirs, manager.getCurrentInput());
	}

	@Test
	public void aPromptThatHasAlreadyGoneIsNotClosedAgain() throws Exception
	{
		prompts.open("Search the map", "", v -> { }, v -> { }, () -> { });
		makeCurrent(made.get());
		prompts.close();
		makeCurrent(null);
		clientThread.later.get(0).run();
		assertEquals(1, clientThread.later.size());
		assertEquals(0, scripts.get());
	}

	@Test
	public void closingWhenNothingWasEverOpenedDoesNothing()
	{
		prompts.close();
		assertEquals(0, clientThread.later.size());
		assertEquals(0, scripts.get());
	}

	@Test
	public void withNoPromptShowingNoClickIsInsideIt()
	{
		assertFalse(prompts.covers(10, 10));
		assertFalse(prompts.covers(-5, 500));
	}
}
