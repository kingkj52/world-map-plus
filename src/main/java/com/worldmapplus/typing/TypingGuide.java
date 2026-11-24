package com.worldmapplus.typing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The typing guide for share codes: which message of a share the player is typing, how what they have typed so far compares
 * with it, and when to move on to the next part. Plain logic with no RuneLite types, so it is tested on its own.
 * <p>
 * The player types every message by hand; this class only ever compares. It is fed the text of the game's chat input line
 * once a client tick ({@link #update}), told by the side panel or the guide's card to start, go back, skip or stop, and read
 * by the overlay every frame through {@link #fill}, which copies into a holder the overlay keeps, so a frame makes nothing.
 * Every method is synchronized: the panel and the card's clicks are on the interface thread, the updates and the drawing on
 * the client thread.
 * <p>
 * Rules. The comparison starts at the first character of the line, after a channel prefix of up to four slashes (the game's
 * way of sending to a chat channel). Case does not matter, because the command is read in lower case and the code case
 * insensitively; in the code itself {@code o} counts as {@code 0} and {@code i} or {@code l} as {@code 1}, which is how the
 * code is read. A message is done when the line held it exactly and then became empty (it was sent), or when the player
 * presses Next. Then the guide moves on to the next part that is not done, and stops after the last.
 */
@javax.inject.Singleton
public final class TypingGuide
{
	/** The most messages a guide holds: the most parts a code is sent in. */
	public static final int MAX_MESSAGES = 8;

	/** The most characters of a message, and the most typed characters that are compared (the rest are left out). */
	public static final int MAX_MESSAGE = 96;
	public static final int MAX_TYPED = 120;

	/** How a typed character compares with the message. */
	public static final byte UNTYPED = 0;
	public static final byte RIGHT = 1;
	public static final byte WRONG = 2;
	public static final byte EXTRA = 3;
	/** One of the slashes that send a line to a chat channel, left out of the comparison. */
	public static final byte CHANNEL = 4;

	/** The most slashes of a channel prefix. */
	static final int MAX_CHANNEL = 4;

	/** Where in a message the code starts, after {@code !wmp} and a space. Aliases count only from here. */
	static final int CODE_START = 5;

	/** What the guide says about the line. */
	public enum Hint
	{
		/** Nothing typed yet. */
		START,
		/** Right so far. */
		GOING,
		/** A character is wrong. */
		TYPO,
		/** Past the end of the message. */
		TOO_LONG,
		/** The whole message, exactly: ready to send. */
		MATCH
	}

	/** Where a message is. */
	public enum State
	{
		NOT_TYPED("not typed"),
		TYPING("typing"),
		DONE("done");

		private final String label;

		State(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** What the overlay draws, copied by {@link #fill}. Made once by the overlay and reused, so drawing makes nothing. */
	public static final class View
	{
		public final char[] message = new char[MAX_MESSAGE];
		public int messageLength;
		public final char[] typed = new char[MAX_TYPED];
		public final byte[] marks = new byte[MAX_TYPED];
		public int typedLength;
		/** The slashes of a channel prefix at the start of the line. */
		public int channel;
		public int right;
		public int wrong;
		public int extra;
		/** The position in the message of the first wrong character, or -1. */
		public int firstWrong = -1;
		public Hint hint = Hint.START;
		/** The message being typed, from 0, and how many there are. */
		public int part;
		public int parts;
		public long generation;
		public boolean active;
		/** The version of the message itself, so a measure of it can be kept until the message changes. */
		public int messageVersion;

		/** The character the message wants at a position of the message, or 0 past its end. */
		public char expected(int at)
		{
			return at >= 0 && at < messageLength ? message[at] : 0;
		}
	}

	private List<String> messages = Collections.emptyList();
	private State[] states = new State[0];
	private int current;
	private boolean active;
	private boolean finished;
	private long generation;
	private int version;
	private int messageVersion;

	/** The last line compared, and what it gave. Null forces the next update to compare again. */
	private String lastTyped;
	private boolean lastComplete;
	private final byte[] marks = new byte[MAX_TYPED];
	private final char[] typed = new char[MAX_TYPED];
	private int typedLength;
	private int channel;
	private int right;
	private int wrong;
	private int extra;
	private int firstWrong = -1;
	private Hint hint = Hint.START;

	/**
	 * Starts a guide for the messages of a share, at message {@code first}. Messages are copied; at most {@link #MAX_MESSAGES},
	 * each cut to {@link #MAX_MESSAGE} characters and lower cased. An empty list stops any guide.
	 */
	public synchronized void start(List<String> list, int first)
	{
		List<String> copy = new ArrayList<>();
		for (String message : list)
		{
			if (copy.size() == MAX_MESSAGES)
			{
				break;
			}
			if (message != null && !message.isEmpty())
			{
				String cut = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
				copy.add(cut.toLowerCase(java.util.Locale.ROOT));
			}
		}
		generation++;
		version++;
		if (copy.isEmpty())
		{
			stopNow(false);
			return;
		}
		messages = Collections.unmodifiableList(copy);
		states = new State[copy.size()];
		java.util.Arrays.fill(states, State.NOT_TYPED);
		current = Math.max(0, Math.min(copy.size() - 1, first));
		active = true;
		finished = false;
		resetLine();
	}

	/** Stops the guide. The overlay draws nothing from the next frame. */
	public synchronized void stop()
	{
		stopNow(false);
	}

	private void stopNow(boolean done)
	{
		if (active || done)
		{
			generation++;
			version++;
		}
		active = false;
		finished = done;
		resetLine();
	}

	/** Goes back one message. False when this is the first or no guide runs. */
	public synchronized boolean back()
	{
		if (!active || current == 0)
		{
			return false;
		}
		moveTo(current - 1);
		return true;
	}

	/** Goes on to the next message without marking this one. False when this is the last or no guide runs. */
	public synchronized boolean skip()
	{
		if (!active || current >= messages.size() - 1)
		{
			return false;
		}
		moveTo(current + 1);
		return true;
	}

	/** Marks this message done and goes on to the next one that is not done, or finishes. False when no guide runs. */
	public synchronized boolean next()
	{
		if (!active)
		{
			return false;
		}
		markDoneAndAdvance();
		return true;
	}

	/** Jumps to a message, for the side panel's Type it button. Starts nothing when no guide runs. */
	public synchronized boolean jumpTo(int index)
	{
		if (!active || index < 0 || index >= messages.size())
		{
			return false;
		}
		moveTo(index);
		return true;
	}

	private void moveTo(int index)
	{
		current = index;
		version++;
		resetLine();
	}

	private void resetLine()
	{
		messageVersion++;
		lastTyped = null;
		lastComplete = false;
		typedLength = 0;
		channel = 0;
		right = 0;
		wrong = 0;
		extra = 0;
		firstWrong = -1;
		hint = Hint.START;
	}

	private void markDoneAndAdvance()
	{
		states[current] = State.DONE;
		for (int step = 1; step <= states.length; step++)
		{
			int at = (current + step) % states.length;
			if (states[at] != State.DONE)
			{
				moveTo(at);
				return;
			}
		}
		stopNow(true);
	}

	/**
	 * Compares the chat input line with the message, once a client tick. {@code expected} is the generation the caller read
	 * before it read the line: an update from before a start, a stop or a restart is ignored. Null counts as an empty line.
	 * Nothing is made when the line has not changed.
	 */
	public synchronized void update(long expected, String line)
	{
		if (!active || expected != generation)
		{
			return;
		}
		String text = line == null ? "" : line;
		if (text.equals(lastTyped))
		{
			return;
		}
		boolean wasComplete = lastComplete;
		lastTyped = text;
		compare(messages.get(current), text);
		lastComplete = hint == Hint.MATCH;
		version++;
		if (text.isEmpty() && wasComplete)
		{
			// The line held the whole message and was emptied: it was sent.
			markDoneAndAdvance();
			return;
		}
		if (typedLength > channel && states[current] == State.NOT_TYPED)
		{
			states[current] = State.TYPING;
		}
	}

	/** Fills {@link #typed}, {@link #marks} and the counts for a line against a message. */
	private void compare(String message, String text)
	{
		typedLength = Math.min(MAX_TYPED, text.length());
		channel = 0;
		while (channel < typedLength && channel < MAX_CHANNEL && text.charAt(channel) == '/')
		{
			channel++;
		}
		right = 0;
		wrong = 0;
		extra = 0;
		firstWrong = -1;
		for (int i = 0; i < typedLength; i++)
		{
			char c = text.charAt(i);
			typed[i] = c;
			if (i < channel)
			{
				marks[i] = CHANNEL;
				continue;
			}
			int at = i - channel;
			if (at >= message.length())
			{
				marks[i] = EXTRA;
				extra++;
			}
			else if (same(c, message.charAt(at), at >= CODE_START))
			{
				marks[i] = RIGHT;
				right++;
			}
			else
			{
				marks[i] = WRONG;
				wrong++;
				if (firstWrong < 0)
				{
					firstWrong = at;
				}
			}
		}
		if (typedLength == channel)
		{
			hint = Hint.START;
		}
		else if (wrong > 0)
		{
			hint = Hint.TYPO;
		}
		else if (extra > 0)
		{
			hint = Hint.TOO_LONG;
		}
		else if (right == message.length())
		{
			hint = Hint.MATCH;
		}
		else
		{
			hint = Hint.GOING;
		}
	}

	/** True when a typed character is the one the message wants, as the code is read (case and the code's aliases). */
	static boolean same(char typed, char wanted, boolean inCode)
	{
		char c = Character.toLowerCase(typed);
		if (c == wanted)
		{
			return true;
		}
		if (!inCode)
		{
			return false;
		}
		return wanted == '0' && c == 'o' || wanted == '1' && (c == 'i' || c == 'l');
	}

	/** Copies what the overlay draws into its holder. Returns false, with {@code view.active} false, when no guide runs. */
	public synchronized boolean fill(View view)
	{
		view.active = active;
		view.generation = generation;
		if (!active)
		{
			return false;
		}
		String message = messages.get(current);
		view.messageLength = message.length();
		message.getChars(0, message.length(), view.message, 0);
		view.typedLength = typedLength;
		System.arraycopy(typed, 0, view.typed, 0, typedLength);
		System.arraycopy(marks, 0, view.marks, 0, typedLength);
		view.channel = channel;
		view.right = right;
		view.wrong = wrong;
		view.extra = extra;
		view.firstWrong = firstWrong;
		view.hint = hint;
		view.part = current;
		view.parts = messages.size();
		view.messageVersion = messageVersion;
		return true;
	}

	public synchronized boolean isActive()
	{
		return active;
	}

	/** True once the last message was done, until the next start. */
	public synchronized boolean isFinished()
	{
		return finished;
	}

	/** The generation: it changes on every start and stop, so an answer from before one can be told apart. */
	public synchronized long generation()
	{
		return generation;
	}

	/** Changes whenever anything the panel shows changes. */
	public synchronized int version()
	{
		return version;
	}

	public synchronized int current()
	{
		return current;
	}

	public synchronized int count()
	{
		return messages.size();
	}

	/** The messages of the guide that runs or ran last. */
	public synchronized List<String> messages()
	{
		return messages;
	}

	/** Where a message is; a guide that is not running reports every message as not typed, or done after it finished. */
	public synchronized State state(int index)
	{
		if (index < 0 || index >= states.length)
		{
			return State.NOT_TYPED;
		}
		return states[index];
	}

	/** The hint for the line as last compared. */
	public synchronized Hint hint()
	{
		return hint;
	}
}
