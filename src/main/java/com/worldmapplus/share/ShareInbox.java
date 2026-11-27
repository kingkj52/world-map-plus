package com.worldmapplus.share;

import java.util.*;
import java.util.regex.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;

/**
 * What other players shared: codes read from chat or from the clipboard, waiting for the player to look at them and
 * save them. Nothing is ever saved from here.
 * <p>
 * Codes come from anyone, so everything is bounded: a few shares wait, a few codes sent in parts are being put
 * together and they expire, the same share is listed once however often it is sent, and parts are only joined with
 * parts from the same channel and sender. Held in memory and gone when the client closes.
 */
@Singleton
public final class ShareInbox
{
	/** Most shares waiting at once. The oldest goes for a new one. */
	static final int MAX_OFFERS = 12;

	/** Most codes being put together from parts at once. */
	static final int MAX_PENDING = 8;

	/** How long the parts of a code are kept waiting for the rest. */
	static final long PENDING_MILLIS = 10 * 60 * 1000L;

	/** Most parts of finished codes remembered, so a right click on any of them opens the whole. */
	static final int MAX_DONE_PARTS = 64;

	/** Most chat lines remembered for one code in parts (each part twice, say), so they can all be relabelled at the end. */
	static final int MAX_LINES_PER_CODE = 2 * ShareCodec.MAX_PARTS;

	/** The key pasted codes are kept under, apart from every chat channel and sender. */
	static final String PASTED = "pasted";

	/** A share waiting to be looked at. */
	@Getter
	@RequiredArgsConstructor
	public static final class Offer
	{
		private final long id;
		private final Share share;
	}

	/** What a code in chat was. */
	@Getter
	public static final class Reaction
	{
		private final boolean unreadable;
		/** A part of a code whose other parts have not all arrived yet. */
		private final boolean waiting;
		/**
		 * The lines the earlier parts of this code came on, handed in with them, when this part finished the code (or showed
		 * that its parts do not fit): they are to show what this line shows. Empty otherwise.
		 */
		private final List<Object> earlierLines;
		/** The whole share the code gave, once it is whole; null while parts wait and when it could not be read. */
		@Nullable
		private Share share;
		/** Why the code could not be read, as a sentence for the player; null when it was read. */
		@Nullable
		private String problem;
		/** For a part: its number from 0, how many parts the code has and how many have arrived; else -1, 0, 0. */
		private int index = -1;
		private int total;
		private int have;

		Reaction(boolean unreadable, boolean waiting, List<Object> earlierLines)
		{
			this.unreadable = unreadable;
			this.waiting = waiting;
			this.earlierLines = earlierLines;
		}
	}

	private static final class Pending
	{
		final ShareCodec.Part[] parts;
		final long since;
		final List<Object> lines = new ArrayList<>();
		int have;

		Pending(int total, long since)
		{
			parts = new ShareCodec.Part[total];
			this.since = since;
		}
	}

	private final List<Offer> offers = new ArrayList<>();
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	private final Map<String, Share> doneParts = new LinkedHashMap<>();
	private long nextId = 1;
	private long opened = -1;
	private int version;

	/**
	 * Reads a code (the word after {@code !wmp}) that arrived from {@code from}, a key for the channel and the sender that is
	 * only compared. {@code line} stands for the chat line the code came on, an object this class only keeps and hands back:
	 * while a code in parts waits for the rest, the lines of its parts are kept with it (at most {@link #MAX_LINES_PER_CODE},
	 * and they go when it is joined or expires), and the part that finishes it returns them in
	 * {@link Reaction#getEarlierLines()}. {@code listed} false (the player's own codes) reads and joins the code without adding
	 * it to Shared with you.
	 */
	public synchronized Reaction receive(String code, String from, long now, @Nullable Object line, boolean listed)
	{
		pending.values().removeIf(p -> now - p.since > PENDING_MILLIS || now < p.since);
		try
		{
			if (ShareCodec.isPart(code))
			{
				return receivePart(ShareCodec.part(code), from, now, line, listed);
			}
			return whole(ShareCodec.decode(code), listed, Collections.emptyList());
		}
		catch (ShareException e)
		{
			Reaction reaction = new Reaction(true, false, Collections.emptyList());
			reaction.problem = e.getMessage();
			return reaction;
		}
	}

	private Reaction whole(Share share, boolean listed, List<Object> earlier)
	{
		if (listed)
		{
			offer(share);
		}
		Reaction reaction = new Reaction(false, false, earlier);
		reaction.share = share;
		return reaction;
	}

	private static Reaction ofPart(Reaction reaction, ShareCodec.Part part, int have)
	{
		reaction.index = part.getIndex();
		reaction.total = part.getTotal();
		reaction.have = have;
		return reaction;
	}

	private Reaction receivePart(ShareCodec.Part part, String from, long now, @Nullable Object line, boolean listed)
	{
		String key = from + "|" + part.getTransfer() + "|" + part.getTotal();
		Pending waiting = pending.get(key);
		Share known = doneParts.get(partKey(part));
		if (waiting == null && known != null)
		{
			// A part of a code already joined, sent again: it says what the whole code says.
			return ofPart(whole(known, listed, Collections.emptyList()), part, part.getTotal());
		}
		if (waiting == null)
		{
			if (pending.size() >= MAX_PENDING)
			{
				pending.remove(pending.keySet().iterator().next());
			}
			waiting = new Pending(part.getTotal(), now);
			pending.put(key, waiting);
		}
		if (waiting.parts[part.getIndex()] == null)
		{
			waiting.have++;
		}
		// A part sent again replaces the one before, so a corrected part can be sent.
		waiting.parts[part.getIndex()] = part;
		if (waiting.have < part.getTotal())
		{
			if (line != null)
			{
				if (waiting.lines.size() >= MAX_LINES_PER_CODE)
				{
					waiting.lines.remove(0);
				}
				waiting.lines.add(line);
			}
			return ofPart(new Reaction(false, true, Collections.emptyList()), part, waiting.have);
		}
		pending.remove(key);
		List<Object> earlier = Collections.unmodifiableList(waiting.lines);
		List<ShareCodec.Part> all = Arrays.asList(waiting.parts);
		Share share;
		try
		{
			share = ShareCodec.join(all);
		}
		catch (ShareException e)
		{
			Reaction reaction = ofPart(new Reaction(true, false, earlier), part, waiting.have);
			reaction.problem = e.getMessage();
			return reaction;
		}
		for (ShareCodec.Part each : all)
		{
			doneParts.remove(partKey(each));
			doneParts.put(partKey(each), share);
		}
		while (doneParts.size() > MAX_DONE_PARTS)
		{
			doneParts.remove(doneParts.keySet().iterator().next());
		}
		return ofPart(whole(share, listed, earlier), part, waiting.have);
	}

	private static String partKey(ShareCodec.Part part)
	{
		return part.getTransfer() + "|" + part.getIndex() + "|" + part.getTotal() + "|" + part.getData();
	}

	/** The whole share a part belongs to, once all its parts have arrived and fit together, or null. */
	@Nullable
	public synchronized Share shareOfPart(String code)
	{
		try
		{
			return doneParts.get(partKey(ShareCodec.part(code)));
		}
		catch (ShareException e)
		{
			return null;
		}
	}

	/** Lists a share, once. Returns its offer. */
	private Offer offer(Share share)
	{
		for (Offer existing : offers)
		{
			if (existing.share.equals(share))
			{
				return existing;
			}
		}
		while (offers.size() >= MAX_OFFERS)
		{
			offers.remove(0);
		}
		Offer offer = new Offer(nextId++, share);
		offers.add(offer);
		version++;
		return offer;
	}

	/** Lists a share if it is not listed yet and marks it as the one opened, for Open in World Map Plus. */
	public synchronized Offer open(Share share)
	{
		Offer offer = offer(share);
		opened = offer.id;
		version++;
		return offer;
	}

	/** The id of the share last opened, or -1. */
	public synchronized long getOpened()
	{
		return opened;
	}

	/** What came of a paste. */
	@Getter
	@RequiredArgsConstructor
	public static final class Pasted
	{
		/** A sentence or a few for the player. */
		private final String message;
		/** How many new shares were listed under Shared with you. */
		private final int added;
		/** True when something in the paste could not be read. */
		private final boolean problem;
	}

	/** The longest pasted text read. */
	public static final int MAX_PASTE = 20_000;

	/** Most codes read from one paste. */
	static final int MAX_CODES = 32;

	/** A line as the Copy buttons give it, or as typed in chat: the command, then one code. */
	private static final Pattern PASTED_LINE = Pattern.compile("(?im)^[ \\t]*!wmp +([a-z0-9]{1,600})\\s*$");

	/**
	 * Reads codes from pasted text through the same reading as chat ({@link #receive}): the lines the Copy buttons give (the whole
	 * code, or its parts one a line, each with {@code !wmp}) or a {@code !wmp} line typed by hand. The parts of a code may come in
	 * any order, in one paste or several (they wait for the rest as chat parts do). Every share read is listed under Shared with
	 * you; nothing is saved.
	 */
	public synchronized Pasted importPasted(@Nullable String text, long now)
	{
		if (text == null || text.trim().isEmpty())
		{
			return new Pasted("Paste a code first.", 0, false);
		}
		List<String> codes = new ArrayList<>();
		Matcher lines = PASTED_LINE.matcher(text.length() > MAX_PASTE ? text.substring(0, MAX_PASTE) : text);
		boolean cut = false;
		while (lines.find())
		{
			cut = codes.size() == MAX_CODES;
			if (cut)
			{
				break;
			}
			codes.add(lines.group(1).toLowerCase(Locale.ROOT));
		}
		if (codes.isEmpty())
		{
			return new Pasted("No code found. Paste the lines as Copy gives them, each starting with " + ShareCodec.COMMAND + ".", 0,
				true);
		}
		int before = version;
		List<String> problems = new ArrayList<>();
		int read = 0;
		Reaction waiting = null;
		for (int i = 0; i < codes.size(); i++)
		{
			Reaction reaction = receive(codes.get(i), PASTED, now, null, true);
			read += reaction.share != null ? 1 : 0;
			waiting = reaction.waiting ? reaction : reaction.share != null && reaction.total > 0 ? null : waiting;
			if (reaction.unreadable)
			{
				problems.add((codes.size() > 1 ? "Code " + (i + 1) + ": " : "") + whyUnreadable(reaction));
			}
		}
		int added = version - before;
		StringBuilder message = new StringBuilder();
		if (added > 0)
		{
			message.append(added == 1 ? "Added 1 share to Shared with you. Save keeps it." : "Added " + added
				+ " shares to Shared with you. Save keeps each.");
		}
		else if (read > 0 && waiting == null && problems.isEmpty())
		{
			message.append("That is already in Shared with you.");
		}
		if (waiting != null)
		{
			message.append(" ").append(waiting.have).append(" of ").append(waiting.total).append(" parts added, paste the rest.");
		}
		for (int i = 0; i < problems.size() && i < 3; i++)
		{
			message.append(" ").append(problems.get(i));
		}
		if (problems.size() > 3)
		{
			message.append(" And ").append(problems.size() - 3).append(" more could not be read.");
		}
		if (cut)
		{
			message.append(" Only the first ").append(MAX_CODES).append(" codes of a paste are read.");
		}
		return new Pasted(message.toString().trim(), added, !problems.isEmpty());
	}

	/** A sentence that says why a pasted code did not read. */
	private static String whyUnreadable(Reaction reaction)
	{
		String problem = reaction.problem == null ? "" : reaction.problem;
		if (problem.contains("newer version"))
		{
			return "That code was made by a newer version of World Map Plus. Update the plugin to read it.";
		}
		if (reaction.total > 0)
		{
			return "The " + reaction.total + " parts do not fit together, so one of them is mistyped. Check each part.";
		}
		if (problem.contains("check does not match"))
		{
			return "Checksum mismatch: a character is mistyped, missing or extra. Check what you typed.";
		}
		return problem.isEmpty() ? "The code could not be read. It may be mistyped or cut short." : problem + " Check what you typed.";
	}

	/** The shares waiting, the newest last. */
	public synchronized List<Offer> offers()
	{
		return new ArrayList<>(offers);
	}

	/** Takes a share off the list, after it was saved or dismissed. */
	public synchronized void remove(long id)
	{
		if (offers.removeIf(o -> o.id == id))
		{
			if (opened == id)
			{
				opened = -1;
			}
			version++;
		}
	}

	/** Changes whenever the list does, so a panel can tell when to redraw. */
	public synchronized int getVersion()
	{
		return version;
	}

	/** Forgets everything, when the plugin stops. */
	public synchronized void clear()
	{
		offers.clear();
		pending.clear();
		doneParts.clear();
		opened = -1;
		version++;
	}
}
