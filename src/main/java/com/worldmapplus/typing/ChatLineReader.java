package com.worldmapplus.typing;

import java.awt.*;
import java.util.function.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;

/**
 * Reads the game's chat input line for the typing guide, once a client tick and only while a guide runs.
 * <p>
 * What is read, all of it plain getters on the client thread: the text being typed (the client string RuneLite's Key
 * Remapping reads, {@code VarClientID.CHATINPUT}), whether a chat area prompt is up (the prompt mode Key Remapping and this
 * plugin's own key guard read), and the input line component's visibility, box and text, to know where the line is and
 * whether it invites the player to press Enter. The typed text is only compared with the message the player asked to type;
 * it is never kept past the next tick, logged, saved or sent. Nothing is written: no variable, no widget, no text; no key is
 * read, nothing is typed or sent.
 * <p>
 * The guide stands down, and nothing of the line is read, while a chat area prompt is up (this plugin's own search, the
 * Group Ironmen companion or any other plugin's prompt, an amount or a name the game asks for), while a dialog or a list of
 * choices has taken the chat's place, while this plugin's own chat search is open, and while the line is hidden.
 */
@Singleton
public class ChatLineReader
{
	/** What the chat line shows when nothing is being typed (Key Remapping's "Press Enter to Chat..."). */
	static final String IDLE_CHAT = "Press Enter to Chat";

	/** Where the facts come from. Asked on the client thread only. */
	public interface Source
	{
		boolean loggedIn();

		/** The text being typed in the chat input line, or null. */
		String typedText();

		/** A chat area prompt (an amount, a name, a plugin's text box) is up. */
		boolean chatPromptUp();

		/** Looks the input line up for the questions that follow. False when it is missing or hidden, or the chat is not the ordinary one. */
		boolean findLine();

		Rectangle lineBounds();

		String lineText();
	}

	@RequiredArgsConstructor
	private static final class ClientSource implements Source
	{
		private final Client client;
		private Widget input;

		@Override
		public boolean loggedIn()
		{
			return client.getGameState() == GameState.LOGGED_IN;
		}

		@Override
		public String typedText()
		{
			return client.getVarcStrValue(VarClientID.CHATINPUT);
		}

		@Override
		public boolean chatPromptUp()
		{
			return client.getVarcIntValue(VarClientID.MESLAYERMODE) != 0;
		}

		@Override
		public boolean findLine()
		{
			input = client.getWidget(InterfaceID.Chatbox.INPUT);
			return input != null && !input.isHidden() && shown(InterfaceID.Chatbox.CHATDISPLAY) && shown(InterfaceID.Chatbox.MES_LAYER_HIDE);
		}

		private boolean shown(int component)
		{
			Widget w = client.getWidget(component);
			return w != null && !w.isHidden();
		}

		@Override
		public Rectangle lineBounds()
		{
			return input.getBounds();
		}

		@Override
		public String lineText()
		{
			return input.getText();
		}
	}

	private final Source source;
	private final TypingGuide guide;
	private final ChatLine line = new ChatLine();
	private BooleanSupplier otherInput = () -> false;

	@Inject
	public ChatLineReader(Client client, TypingGuide guide)
	{
		this(new ClientSource(client), guide);
	}

	/** For tests, which supply their own facts. */
	public ChatLineReader(Source source, TypingGuide guide)
	{
		this.source = source;
		this.guide = guide;
	}

	/** Tells the reader when this plugin's own chat search has the chat area, so the guide stands down then. */
	public void setOtherInput(BooleanSupplier busy)
	{
		otherInput = busy == null ? () -> false : busy;
	}

	/** The line as last read. Client thread only. */
	public ChatLine line()
	{
		return line;
	}

	/** Once a client tick, on the client thread. Reads nothing at all while no guide runs. */
	public void onClientTick()
	{
		line.usable = false;
		if (!guide.isActive())
		{
			return;
		}
		try
		{
			if (!source.loggedIn() || otherInput.getAsBoolean() || source.chatPromptUp() || !source.findLine())
			{
				return;
			}
			guide.update(guide.generation(), source.typedText());
			Rectangle box = source.lineBounds();
			String text = source.lineText();
			if (box == null || box.width <= 0 || box.height <= 0)
			{
				return;
			}
			line.idle = text != null && text.contains(IDLE_CHAT);
			line.x = box.x;
			line.y = box.y;
			line.width = box.width;
			line.height = box.height;
			line.usable = true;
		}
		catch (RuntimeException e)
		{
			// While the client loads an interface a component can be half made. Not knowing draws nothing.
			line.usable = false;
		}
	}
}
