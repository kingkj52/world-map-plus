package com.worldmapplus.share;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Singleton;

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

	/** A share waiting to be looked at. */
	public static final class Offer
	{
		private final long id;
		private final Share share;
		private final long arrived;

		Offer(long id, Share share, long arrived)
		{
			this.id = id;
			this.share = share;
			this.arrived = arrived;
		}

		public long getId()
		{
			return id;
		}

		public Share getShare()
		{
			return share;
		}

		public long getArrived()
		{
			return arrived;
		}
	}

	/** Most chat lines remembered for one code in parts (each part twice, say), so they can all be relabelled at the end. */
	static final int MAX_LINES_PER_CODE = 2 * ShareCodec.MAX_PARTS;

	/** What a code in chat was. */
	public static final class Reaction
	{
		private final String label;
		private final boolean unreadable;
		private final boolean waiting;
		private final List<Object> earlier;
		@Nullable
		private Share share;
		@Nullable
		private String problem;
		@Nullable
		private Share.Kind partKind;
		private int index = -1;
		private int total;
		private int have;

		Reaction(String label, boolean unreadable)
		{
			this(label, unreadable, false, Collections.emptyList());
		}

		Reaction(String label, boolean unreadable, boolean waiting, List<Object> earlier)
		{
			this.label = label;
			this.unreadable = unreadable;
			this.waiting = waiting;
			this.earlier = earlier;
		}

		/** The whole share the code gave, once it is whole; null while parts wait and when it could not be read. */
		@Nullable
		public Share getShare()
		{
			return share;
		}

		/** Why the code could not be read, as a sentence for the player; null when it was read. */
		@Nullable
		public String getProblem()
		{
			return problem;
		}

		/**
		 * For a part: the kind of the whole code when the first part is among those that arrived (its data starts with the
		 * code's kind letter), else null.
		 */
		@Nullable
		public Share.Kind getPartKind()
		{
			return partKind;
		}

		/** For a part: its number from 0, else -1. */
		public int getIndex()
		{
			return index;
		}

		/** For a part: how many parts the code has, else 0. */
		public int getTotal()
		{
			return total;
		}

		/** For a part: how many of the code's parts have arrived so far. */
		public int getHave()
		{
			return have;
		}

		/** A few words to show after the code in chat, on this client only. */
		public String getLabel()
		{
			return label;
		}

		public boolean isUnreadable()
		{
			return unreadable;
		}

		/** A part of a code whose other parts have not all arrived yet. */
		public boolean isWaiting()
		{
			return waiting;
		}

		/**
		 * The lines the earlier parts of this code came on, handed in with them, when this part finished the code (or
		 * showed that its parts do not fit): they are to show this label too. Empty otherwise.
		 */
		public List<Object> getEarlierLines()
		{
			return earlier;
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
	 * Reads a code (the word after {@code !wmp}) that arrived from {@code from}, a key for the channel and the sender that
	 * is only compared. Returns what to show after it.
	 */
	public synchronized Reaction receive(String code, String from, long now)
	{
		return receive(code, from, now, null, true);
	}

	/**
	 * Reads a code as {@link #receive(String, String, long)} does. {@code line} stands for the chat line the code came on,
	 * an object this class only keeps and hands back: while a code in parts waits for the rest, the lines of its parts are
	 * kept with it (at most {@link #MAX_LINES_PER_CODE}, and they go when it is joined or expires), and the part that
	 * finishes it returns them in {@link Reaction#getEarlierLines()}. {@code listed} false (the player's own codes) reads
	 * and joins the code without adding it to Shared with you.
	 */
	public synchronized Reaction receive(String code, String from, long now, @Nullable Object line, boolean listed)
	{
		expire(now);
		try
		{
			if (ShareCodec.isPart(code))
			{
				return receivePart(ShareCodec.part(code), from, now, line, listed);
			}
			Share share = ShareCodec.decode(code);
			if (listed)
			{
				offer(share, now);
			}
			Reaction reaction = new Reaction(labelOf(share), false);
			reaction.share = share;
			return reaction;
		}
		catch (ShareException e)
		{
			Reaction reaction = new Reaction("World Map Plus code that could not be read", true);
			reaction.problem = e.getMessage();
			return reaction;
		}
	}

	/** The kind of a whole code from its first character, or null. */
	@Nullable
	private static Share.Kind kindOf(String data)
	{
		char letter = data.isEmpty() ? 0 : data.charAt(0);
		for (Share.Kind kind : Share.Kind.values())
		{
			if (kind.getLetter() == letter)
			{
				return kind;
			}
		}
		return null;
	}

	private static Reaction partReaction(Reaction reaction, ShareCodec.Part part, int have, @Nullable ShareCodec.Part first)
	{
		reaction.index = part.getIndex();
		reaction.total = part.getTotal();
		reaction.have = have;
		reaction.partKind = first == null ? null : kindOf(first.getData());
		return reaction;
	}

	/** What a whole code says after it in chat, whether it came in one message or in parts. */
	public static String labelOf(Share share)
	{
		return "World Map Plus " + share.describe();
	}

	private Reaction receivePart(ShareCodec.Part part, String from, long now, @Nullable Object line, boolean listed)
		throws ShareException
	{
		String key = from + "|" + part.getTransfer() + "|" + part.getTotal();
		Pending waiting = pending.get(key);
		Share known = doneParts.get(partKey(part));
		if (waiting == null && known != null)
		{
			// A part of a code already joined, sent again: it says what the whole code says.
			if (listed)
			{
				offer(known, now);
			}
			Reaction reaction = partReaction(new Reaction(labelOf(known), false), part, part.getTotal(), null);
			reaction.share = known;
			reaction.partKind = known.getKind();
			return reaction;
		}
		if (waiting == null)
		{
			if (pending.size() >= MAX_PENDING)
			{
				Iterator<String> oldest = pending.keySet().iterator();
				oldest.next();
				oldest.remove();
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
		String which = "part " + (part.getIndex() + 1) + " of " + part.getTotal();
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
			int more = part.getTotal() - waiting.have;
			return partReaction(new Reaction("World Map Plus: " + which + " received, waiting for " + more
				+ (more == 1 ? " more part" : " more parts"), false, true, Collections.emptyList()), part, waiting.have, waiting.parts[0]);
		}
		pending.remove(key);
		List<Object> earlier = Collections.unmodifiableList(waiting.lines);
		List<ShareCodec.Part> all = new ArrayList<>();
		for (ShareCodec.Part each : waiting.parts)
		{
			all.add(each);
		}
		Share share;
		try
		{
			share = ShareCodec.join(all);
		}
		catch (ShareException e)
		{
			Reaction reaction = partReaction(new Reaction("World Map Plus: the " + part.getTotal()
				+ " parts do not fit together, one may be mistyped", true, false, earlier), part, waiting.have, waiting.parts[0]);
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
			Iterator<String> oldest = doneParts.keySet().iterator();
			oldest.next();
			oldest.remove();
		}
		if (listed)
		{
			offer(share, now);
		}
		Reaction reaction = partReaction(new Reaction(labelOf(share), false, false, earlier), part, waiting.have, waiting.parts[0]);
		reaction.share = share;
		reaction.partKind = share.getKind();
		return reaction;
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
	private Offer offer(Share share, long now)
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
		Offer offer = new Offer(nextId++, share, now);
		offers.add(offer);
		version++;
		return offer;
	}

	/** Lists a share if it is not listed yet and marks it as the one opened, for Open in World Map Plus. */
	public synchronized Offer open(Share share, long now)
	{
		Offer offer = offer(share, now);
		opened = offer.id;
		version++;
		return offer;
	}

	/** The id of the share last opened, or -1. */
	public synchronized long getOpened()
	{
		return opened;
	}

	/**
	 * Reads codes from pasted text: every {@code !wmp} message in it, or bare codes. The same reading as for chat. Returns
	 * a sentence about what came of it.
	 */
	public synchronized String receivePasted(@Nullable String text, long now)
	{
		return importPasted(text, now).getMessage();
	}

	/** What came of a paste. */
	public static final class Pasted
	{
		private final String message;
		private final int added;
		private final boolean problem;

		Pasted(String message, int added, boolean problem)
		{
			this.message = message;
			this.added = added;
			this.problem = problem;
		}

		/** A sentence or a few for the player. */
		public String getMessage()
		{
			return message;
		}

		/** How many new shares were listed under Shared with you. */
		public int getAdded()
		{
			return added;
		}

		/** True when something in the paste could not be read. */
		public boolean hasProblem()
		{
			return problem;
		}
	}

	/** The key pasted codes are kept under, apart from every chat channel and sender. */
	static final String PASTED = "pasted";

	/**
	 * Reads codes from pasted text through the same reading as chat ({@link #receive}): whole codes with or without
	 * {@code !wmp}, a long code as one conjoined code, and the parts of a code in any order, in one paste or several (the
	 * parts wait for the rest as chat parts do). Every share read is listed under Shared with you; nothing is saved.
	 * {@link PasteReader} finds the codes.
	 */
	public synchronized Pasted importPasted(@Nullable String text, long now)
	{
		if (text == null || text.trim().isEmpty())
		{
			return new Pasted("Paste a code first.", 0, false);
		}
		PasteReader.Found found = PasteReader.read(text);
		List<String> codes = found.getCodes();
		if (codes.isEmpty() && found.getProblems().isEmpty())
		{
			return new Pasted(found.hasOld()
				? "That is a code from an older version of World Map Plus, which this version does not read. Ask for a new one."
				: "That does not look like a code. A code starts with p1, r1, s1 or c1, with or without " + ShareCodec.COMMAND + ".",
				0, true);
		}
		int before = version;
		List<String> said = new ArrayList<>();
		List<String> problems = new ArrayList<>(found.getProblems());
		List<Share> added = new ArrayList<>();
		int already = 0;
		// The last word on each code in parts, by its key, so a code pasted part by part says what is still missing.
		Map<String, Reaction> parts = new LinkedHashMap<>();
		for (int i = 0; i < codes.size(); i++)
		{
			String code = codes.get(i);
			int listedBefore = version;
			Reaction reaction = receive(code, PASTED, now);
			String which = codes.size() > 1 ? "Code " + (i + 1) + ": " : "";
			if (reaction.getTotal() > 0)
			{
				ShareCodec.Part part = partOrNull(code);
				if (part != null)
				{
					parts.put(part.getTransfer() + "|" + part.getTotal(), reaction);
				}
			}
			if (reaction.isUnreadable())
			{
				problems.add(which + whyUnreadable(code, reaction));
			}
			else if (reaction.getShare() != null)
			{
				if (version != listedBefore)
				{
					added.add(reaction.getShare());
				}
				else
				{
					already++;
				}
			}
		}
		for (Map.Entry<String, Reaction> entry : parts.entrySet())
		{
			Reaction last = entry.getValue();
			if (last.isWaiting())
			{
				said.add(waitingWords(entry.getKey(), last));
			}
		}
		StringBuilder message = new StringBuilder();
		if (added.size() == 1)
		{
			message.append("Added the shared ").append(added.get(0).describe()).append(" to Shared with you. Save keeps it.");
		}
		else if (added.size() > 1)
		{
			message.append("Added ").append(added.size()).append(" shares to Shared with you. Save keeps each.");
		}
		if (already > 0 && added.isEmpty() && said.isEmpty() && problems.isEmpty())
		{
			message.append(already == 1 ? "That is already in Shared with you." : "Those are already in Shared with you.");
		}
		for (String words : said)
		{
			message.append(message.length() > 0 ? " " : "").append(words);
		}
		for (int i = 0; i < problems.size() && i < 3; i++)
		{
			message.append(message.length() > 0 ? " " : "").append(problems.get(i));
		}
		if (problems.size() > 3)
		{
			message.append(" And ").append(problems.size() - 3).append(" more could not be read.");
		}
		if (found.isCut())
		{
			message.append(" Only the first ").append(PasteReader.MAX_CODES).append(" codes of a paste are read.");
		}
		return new Pasted(message.toString().trim(), version - before, !problems.isEmpty());
	}

	@Nullable
	private static ShareCodec.Part partOrNull(String code)
	{
		try
		{
			return ShareCodec.isPart(code) ? ShareCodec.part(code) : null;
		}
		catch (ShareException e)
		{
			return null;
		}
	}

	/** "Part 1 of 2 added, paste the next part (part 2 of 2 is missing)." for a code pasted part by part. */
	private String waitingWords(String transferAndTotal, Reaction last)
	{
		Pending waiting = pending.get(PASTED + "|" + transferAndTotal);
		int total = last.getTotal();
		List<Integer> missing = new ArrayList<>();
		List<Integer> have = new ArrayList<>();
		for (int i = 0; i < total; i++)
		{
			if (waiting == null || waiting.parts[i] == null)
			{
				missing.add(i + 1);
			}
			else
			{
				have.add(i + 1);
			}
		}
		StringBuilder text = new StringBuilder();
		text.append(have.size() == 1 ? "Part " + have.get(0) : "Parts " + join(have)).append(" of ").append(total).append(" added, paste ")
			.append(missing.size() == 1 ? "the next part" : "the other parts").append(" (")
			.append(missing.size() == 1 ? "part " + missing.get(0) : "parts " + join(missing)).append(" of ").append(total)
			.append(missing.size() == 1 ? " is" : " are").append(" missing).");
		return text.toString();
	}

	private static String join(List<Integer> numbers)
	{
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < numbers.size(); i++)
		{
			text.append(i == 0 ? "" : i == numbers.size() - 1 ? " and " : ", ").append(numbers.get(i));
		}
		return text.toString();
	}

	/** A sentence that says as exactly as can be told why a pasted code did not read. */
	private static String whyUnreadable(String code, Reaction reaction)
	{
		String problem = reaction.getProblem() == null ? "" : reaction.getProblem();
		if (problem.contains("newer version"))
		{
			return "That code was made by a newer version of World Map Plus. Update the plugin to read it.";
		}
		if (reaction.getTotal() > 0)
		{
			return "The " + reaction.getTotal() + " parts do not fit together, so one of them is mistyped. Check each part.";
		}
		List<Integer> at = ShareCodec.typoAt(code);
		if (!at.isEmpty() && problem.contains("check does not match"))
		{
			return "Checksum mismatch near character " + join(at).replace(" and ", " or ") + ", check what you typed.";
		}
		if (!at.isEmpty())
		{
			return "The code does not read: likely mistyped near character " + join(at).replace(" and ", " or ") + ", check what you typed.";
		}
		if (problem.contains("check does not match"))
		{
			return "Checksum mismatch: a character is mistyped, missing or extra. Check what you typed.";
		}
		return problem.isEmpty() ? "The code could not be read. It may be mistyped or cut short." : problem + " Check what you typed.";
	}

	private void expire(long now)
	{
		pending.values().removeIf(p -> now - p.since > PENDING_MILLIS || now < p.since);
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
