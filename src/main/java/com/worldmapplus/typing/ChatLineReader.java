package com.worldmapplus.typing;

import java.awt.Rectangle;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;

/**
 * Reads the game's chat input line for the typing guide, once a client tick and only while a guide runs.
 * <p>
 * What is read, all of it plain getters on the client thread: the text being typed (the client string RuneLite's Key
 * Remapping reads, {@code VarClientID.CHATINPUT}), whether a chat area prompt is up (the prompt mode Key Remapping and this
 * plugin's own key guard read), and the input line component's visibility, box, text and font, to find where its letters
 * are. The typed text is only compared with the message the player asked to type; it is never kept past the next tick,
 * logged, saved or sent. Nothing is written: no variable, no widget, no text; no key is read, nothing is typed or sent.
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

		/** The width of a text in the line's own font. */
		int textWidth(String text);

		/** The line font's ascent. */
		int ascent();

		/** The line's horizontal and vertical text alignment (0 left or top, 1 centre, 2 right or bottom). */
		int xAlignment();

		int yAlignment();
	}

	private static final class ClientSource implements Source
	{
		private final Client client;
		private Widget input;
		private FontTypeFace font;

		ClientSource(Client client)
		{
			this.client = client;
		}

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
			font = null;
			if (input == null || input.isHidden() || !shown(InterfaceID.Chatbox.CHATDISPLAY) || !shown(InterfaceID.Chatbox.MES_LAYER_HIDE))
			{
				return false;
			}
			font = input.getFont();
			return font != null;
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

		@Override
		public int textWidth(String text)
		{
			return font.getTextWidth(text);
		}

		@Override
		public int ascent()
		{
			return font.getBaseline();
		}

		@Override
		public int xAlignment()
		{
			return input.getXTextAlignment();
		}

		@Override
		public int yAlignment()
		{
			return input.getYTextAlignment();
		}
	}

	private final Source source;
	private final TypingGuide guide;
	private final ChatLine line = new ChatLine();
	private final TypingGuide.View view = new TypingGuide.View();
	private BooleanSupplier otherInput = () -> false;

	// What was measured last, so a tick in which nothing changed measures nothing.
	private String measuredLine;
	private String measuredTyped;
	private int prefixWidth;
	private int lineWidth;

	// The game's cursor: the widest seen while this guide runs, and the width used before one is seen.
	private int cursorSeen;
	private int cursorDefault = -1;

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
		if (!guide.isActive())
		{
			if (line.generation != -1)
			{
				line.clear();
				measuredLine = null;
				measuredTyped = null;
				cursorSeen = 0;
			}
			return;
		}
		long generation = guide.generation();
		line.usable = false;
		try
		{
			if (!source.loggedIn() || otherInput.getAsBoolean() || source.chatPromptUp() || !source.findLine())
			{
				return;
			}
			String typed = source.typedText();
			guide.update(generation, typed);
			if (!guide.fill(view))
			{
				line.clear();
				return;
			}
			measure(typed == null ? "" : typed);
		}
		catch (RuntimeException e)
		{
			// While the client loads an interface a component can be half made. Not knowing draws nothing.
			line.usable = false;
		}
	}

	private void measure(String typed)
	{
		String text = source.lineText();
		int colon = text == null ? -1 : text.indexOf(':');
		if (colon < 0)
		{
			return;
		}
		boolean lineChanged = !text.equals(measuredLine);
		if (lineChanged)
		{
			// The player's name, with any icon of theirs, and the colon and space the game puts after it, as Key Remapping writes them.
			prefixWidth = source.textWidth(text.substring(0, colon) + ": ");
			lineWidth = source.textWidth(text);
			line.idle = text.contains(IDLE_CHAT);
			measuredLine = text;
		}
		if (line.messageVersion != view.messageVersion || line.generation != view.generation)
		{
			String message = new String(view.message, 0, view.messageLength);
			line.messageEdges[0] = 0;
			for (int i = 1; i <= view.messageLength; i++)
			{
				line.messageEdges[i] = source.textWidth(message.substring(0, i));
			}
			line.messageCount = view.messageLength;
			line.messageVersion = view.messageVersion;
			line.generation = view.generation;
		}
		if (!typed.equals(measuredTyped))
		{
			int count = Math.min(TypingGuide.MAX_TYPED, typed.length());
			line.typedEdges[0] = 0;
			for (int i = 1; i <= count; i++)
			{
				line.typedEdges[i] = source.textWidth(typed.substring(0, i));
			}
			line.typedCount = count;
			measuredTyped = typed;
		}
		if (lineChanged)
		{
			measureCursor(typed);
		}
		Rectangle box = source.lineBounds();
		if (box == null || box.width <= 0 || box.height <= 0)
		{
			return;
		}
		line.x = box.x;
		line.y = box.y;
		line.width = box.width;
		line.height = box.height;
		line.textX = box.x + prefixWidth;
		line.baseline = baseline(box.y, box.height, source.ascent(), source.yAlignment());
		// Letters can be put on the game's own only on a line that starts at its left edge, as the chat line does. Otherwise the
		// guide is drawn just above the line.
		line.aligned = source.xAlignment() == 0;
		line.usable = true;
	}

	/**
	 * The room the game's cursor takes after the typed text. The game draws it as part of the line's text, after what was
	 * typed, and it blinks: when it is shown, what the line's text is wider than the name and the typed text is the cursor.
	 * The widest cursor seen while this guide runs is kept, so the guide does not move when the cursor blinks off; until one
	 * is seen, the wider of the game's two cursor marks, {@code *} and {@code _}, is used. A width that cannot be a cursor
	 * (the line holds something else after the typed text) is not taken.
	 */
	private void measureCursor(String typed)
	{
		if (cursorDefault < 0)
		{
			cursorDefault = Math.max(source.textWidth("*"), source.textWidth("_"));
		}
		if (typed.length() <= TypingGuide.MAX_TYPED)
		{
			int tail = lineWidth - prefixWidth - line.typedEdges[line.typedCount];
			if (tail > 0 && tail <= 3 * Math.max(1, cursorDefault))
			{
				cursorSeen = Math.max(cursorSeen, tail);
			}
		}
		line.cursorWidth = cursorSeen > 0 ? cursorSeen : cursorDefault;
	}

	/**
	 * The baseline of a line of text in a box, as the game places one: at the top, the middle or the bottom. The descent is
	 * taken as a quarter of the ascent, which is what the game's plain fonts have.
	 */
	static int baseline(int top, int height, int ascent, int yAlignment)
	{
		int descent = Math.max(1, ascent / 4);
		switch (yAlignment)
		{
			case 0:
				return top + ascent;
			case 2:
				return top + height - descent;
			default:
				return top + (height - ascent - descent) / 2 + ascent;
		}
	}
}
