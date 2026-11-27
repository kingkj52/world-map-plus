package com.worldmapplus.share;

import java.util.function.*;
import javax.annotation.*;

/**
 * The words a chat line with a {@code !wmp} code shows on this client in place of the code (round 52): what the code holds,
 * the way RuneLite's Chat Commands plugin shows the answer to {@code !lvl} in place of the command. Plain text, always
 * starting with {@link #HEAD}; the chat end adds the colours. A place is named only when that is known for sure (a place
 * the player has saved at that very tile, or a map other than the surface), never guessed from what is near.
 */
public final class ChatText
{
	/** The words every such line starts with. */
	public static final String HEAD = "World Map Plus";

	/** The longest place name shown. */
	static final int MAX_NAME = 32;

	private ChatText()
	{
	}

	/** The text for a whole code. {@code namer} gives the name of a point when it is known for sure, or null. */
	public static String whole(Share share, @Nullable Function<SharePoint, String> namer)
	{
		SharePoint first = share.getPoints().get(0);
		String name = nameOf(first, namer);
		int count = share.getPoints().size();
		switch (share.getKind())
		{
			case PIN:
				return HEAD + " pin: " + (name == null ? "" : name + ", ") + first.describe();
			case ROUTE:
				return HEAD + " route: " + count + (count == 1 ? " stop" : " stops") + (share.loops() ? ", loops" : "")
					+ ", starts at " + (name == null ? first.describe() : name);
			default:
				return HEAD + " places: " + count;
		}
	}

	/** The text for a line whose code is a part of one whose other parts have not all arrived. */
	public static String waiting(ShareInbox.Reaction reaction)
	{
		int missing = reaction.getTotal() - reaction.getHave();
		return HEAD + ": part " + (reaction.getIndex() + 1) + " of " + reaction.getTotal() + " received, waiting for "
			+ (missing == 1 ? "the next part" : missing + " more parts");
	}

	/** The text for a line whose code could not be read: the code stays, so the sender can check it. */
	public static String unreadable(ShareInbox.Reaction reaction, String code)
	{
		if (reaction.getTotal() > 0)
		{
			return HEAD + ": the " + reaction.getTotal() + " parts do not fit together, one may be mistyped";
		}
		return HEAD + " code that could not be read: " + code;
	}

	@Nullable
	private static String nameOf(SharePoint point, @Nullable Function<SharePoint, String> namer)
	{
		String name = namer == null ? null : namer.apply(point);
		if (name == null)
		{
			return null;
		}
		String text = name.trim();
		if (text.isEmpty())
		{
			return null;
		}
		if (text.length() > MAX_NAME)
		{
			text = text.substring(0, MAX_NAME - 3).trim() + "...";
		}
		return text;
	}
}
