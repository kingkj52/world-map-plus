package com.worldmapplus.input;

import java.awt.event.*;
import java.util.function.BooleanSupplier;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;

/**
 * Knows whether the game is taking typed text, so a key of this plugin never acts while the player is typing in the game.
 * <p>
 * Two things count. <i>Text</i>: a chat message being typed, a box with the cursor in it (a bank or Grand Exchange search, an amount),
 * a prompt of the chat area (enter an amount or a name), or the game's own world map search. While any of these is up, none of this
 * plugin's keys act. <i>Keys</i>: an interface that takes keys itself, such as an NPC dialog (space and the numbers), a list of
 * choices, the bank pin keypad, or a window like the jewellery box that has key handlers. While one is up, a key that would type a
 * character (no Ctrl, Alt or Meta held) is left alone, and a chord that cannot type anything still works.
 * <p>
 * The same signals RuneLite's own Key Remapping plugin uses to tell when to stand aside are read here: the chat box's key handler,
 * the chat area's dialog and choice windows, the input field that has the focus, and the chat prompt mode. They are read on the
 * client thread once a client tick, and the answer is kept in two flags that the key thread reads. Nothing is written, sent or
 * changed. The scan of open windows for key handlers is done every {@link #RESCAN_TICKS} client ticks and when a window opens or
 * closes, the way the other signals are cheap enough to read every tick.
 */
@Singleton
public class GameTextEntry
{
	/** Client ticks between looks at the open windows for ones that take keys, when none has opened or closed. */
	public static final int RESCAN_TICKS = 5;

	/** What the chat box shows when nothing is being typed in it. */
	static final String IDLE_CHAT = "Press Enter to Chat";

	/** The most children of an open window that are looked at for a key handler. */
	private static final int MAX_CHILDREN = 2000;

	/** Where the facts come from. Asked on the client thread only. */
	public interface Source
	{
		boolean loggedIn();

		/** A box with the cursor in it. */
		boolean inputFieldFocused();

		/** The chat area is showing a prompt, such as an amount or a name to enter. */
		boolean chatPromptUp();

		/** The game's own world map search takes text. */
		boolean worldMapSearching();

		/** A message is being typed into the chat box. */
		boolean chatMessageBeingTyped();

		/** A dialog, a list of choices or the bank pin keypad is up. */
		boolean dialogUp();

		/** A window that is not the chat or a side panel has a key handler. */
		boolean windowTakesKeys();
	}

	@RequiredArgsConstructor
	private static final class ClientSource implements Source
	{
		private final Client client;

		@Override
		public boolean loggedIn()
		{
			return client.getGameState() == GameState.LOGGED_IN;
		}

		@Override
		public boolean inputFieldFocused()
		{
			return client.getFocusedInputFieldWidget() != null;
		}

		@Override
		public boolean chatPromptUp()
		{
			return client.getVarcIntValue(VarClientID.MESLAYERMODE) != 0;
		}

		@Override
		public boolean worldMapSearching()
		{
			return client.getVarcIntValue(VarClientID.WORLDMAP_SEARCHING) == 1;
		}

		@Override
		public boolean chatMessageBeingTyped()
		{
			Widget chat = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
			Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
			if (chat == null || chat.getOnKeyListener() == null || input == null)
			{
				return false;
			}
			// The chat box is the ordinary one (no dialog has taken its place and no report window is up) and shows something other than
			// the line that invites the player to start.
			if (shown(InterfaceID.Chatbox.CHATDISPLAY) && shown(InterfaceID.Chatbox.MES_LAYER_HIDE)
				&& client.getWidget(InterfaceID.Reportabuse.UNIVERSE) == null)
			{
				String text = input.getText();
				return text != null && !text.isEmpty() && !text.contains(IDLE_CHAT);
			}
			return false;
		}

		@Override
		public boolean dialogUp()
		{
			// The chat's message layer or its display is hidden while a dialog or a list of choices has taken their place.
			return !shown(InterfaceID.Chatbox.MES_LAYER_HIDE) || !shown(InterfaceID.Chatbox.CHATDISPLAY)
				|| client.getWidget(InterfaceID.BankpinKeypad.UNIVERSE) != null && shown(InterfaceID.BankpinKeypad.UNIVERSE)
				|| client.getWidget(InterfaceID.Chatmenu.OPTIONS) != null;
		}

		@Override
		public boolean windowTakesKeys()
		{
			for (WidgetNode node : client.getComponentTable())
			{
				if (node.getModalMode() == WidgetModalMode.NON_MODAL)
				{
					continue;
				}
				Widget w;
				for (int child = 0; child < MAX_CHILDREN && (w = client.getWidget(node.getId(), child)) != null; child++)
				{
					if (w.getOnKeyListener() != null && !w.isHidden())
					{
						return true;
					}
				}
			}
			return false;
		}

		private boolean shown(int component)
		{
			Widget w = client.getWidget(component);
			return w != null && !w.isSelfHidden();
		}
	}

	private Source source;

	/** What the key thread reads. */
	private volatile boolean takesText;
	private volatile boolean takesKeys;

	// Client thread only.
	private int ticks;
	private int windowsChanged;
	private boolean windowKeys;

	@Inject
	public GameTextEntry(Client client)
	{
		this(new ClientSource(client));
	}

	/** For tests, which supply their own facts. */
	public GameTextEntry(Source source)
	{
		this.source = source;
	}

	/** Replaces where the facts come from, for tests. */
	public void setSource(Source source)
	{
		this.source = source;
	}

	/** A window opened or closed, so the next client tick looks at the open windows again. Called on the client thread. */
	public void windowsChanged()
	{
		windowsChanged = 2;
	}

	/** Once a client tick, on the client thread. Reads the signals and keeps the answer for the key thread. */
	public void onClientTick()
	{
		boolean text = false;
		boolean keys = false;
		if (source.loggedIn())
		{
			boolean look = windowsChanged > 0;
			if (look)
			{
				windowsChanged--;
			}
			if (look || ++ticks % RESCAN_TICKS == 0)
			{
				windowKeys = safe(source::windowTakesKeys);
			}
			text = safe(source::inputFieldFocused) || safe(source::chatPromptUp) || safe(source::worldMapSearching)
				|| safe(source::chatMessageBeingTyped);
			keys = windowKeys || safe(source::dialogUp) || windowsChanged > 0;
		}
		else
		{
			windowKeys = false;
		}
		takesText = text;
		takesKeys = keys;
	}

	private static boolean safe(BooleanSupplier question)
	{
		try
		{
			return question.getAsBoolean();
		}
		catch (RuntimeException e)
		{
			// While the client is loading a widget can be half made. Not knowing counts as not typing, as nothing is taken from the game.
			return false;
		}
	}

	/** True while text is being typed in the game, as of the last client tick. */
	public boolean takesText()
	{
		return takesText;
	}

	/** True while an interface that takes keys itself is up, as of the last client tick. */
	public boolean takesKeys()
	{
		return takesKeys;
	}

	/**
	 * True when a key of this plugin must be left to the game: text is being typed, or an interface takes keys and this key would type a
	 * character (it is not held with Ctrl, Alt or Meta). Safe from any thread.
	 */
	public boolean blocks(@Nullable KeyEvent event)
	{
		if (event == null)
		{
			return false;
		}
		if (takesText)
		{
			return true;
		}
		return takesKeys && typesACharacter(event);
	}

	/** True for a key that would type a character in a box: it has one and no chord key is held. */
	static boolean typesACharacter(KeyEvent event)
	{
		return event.getKeyChar() != KeyEvent.CHAR_UNDEFINED && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown();
	}
}
