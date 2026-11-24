package com.worldmapplus.share;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Share codes, version 1: a pin, a route or a set of places as a short text that a player types into the game's chat
 * after {@code !wmp}. The exact layout, the opcodes, the checksum and the test vectors are in docs/SHARE-PROTOCOL.md;
 * this class and that document must agree, and a version 1 code must always decode to the same thing.
 * <p>
 * Reading is strict, because a code comes from anyone: the text alphabet, the version, every count, coordinate, floor
 * and map, the checksum and the padding are checked, and anything else is refused with a {@link ShareException}.
 * Nothing in a code names a class, a file or an address, and nothing is allocated beyond a fixed bound. This class does
 * not depend on RuneLite, so it can be tested on its own.
 */
public final class ShareCodec
{
	/** The chat command every share message starts with. */
	public static final String COMMAND = "!wmp";

	/** The protocol version this class writes and reads. */
	public static final int VERSION = 1;

	/**
	 * The longest chat message made, with {@code !wmp } in front. The game's chat line is commonly 80 characters; four are
	 * left for a channel prefix such as {@code ////}.
	 */
	public static final int MAX_MESSAGE = 76;

	/** The longest code (after {@code !wmp }) that is sent as one message. */
	public static final int MAX_SINGLE = MAX_MESSAGE - COMMAND.length() - 1;

	/** Characters in front of the data of a part: {@code c1} and three for the transfer, the part and the count. */
	static final int PART_HEAD = 5;

	/** The most characters of a code one part carries. */
	public static final int MAX_PART_DATA = MAX_SINGLE - PART_HEAD;

	/** The most messages one code is split into. */
	public static final int MAX_PARTS = 8;

	/** The longest code read at all, in characters. */
	public static final int MAX_CODE = MAX_PARTS * MAX_PART_DATA;

	/** The most points one code holds. */
	public static final int MAX_POINTS = 64;

	/** Crockford's base 32, written in lower case. Reading also takes upper case, and o for 0, i and l for 1. */
	static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

	/** The letter of a part of a code split over several messages. */
	static final char PART = 'c';

	// Record opcodes, for every point after the first. See SHARE-PROTOCOL.md.
	static final int SAME_REGION = 0b00;
	static final int SMALL_STEP = 0b01;
	static final int NEXT_REGION = 0b100;
	static final int MEDIUM_STEP = 0b101;
	static final int ABSOLUTE = 0b110;
	static final int EARLIER = 0b1110;
	static final int FLOOR = 0b1111;

	/** The eight neighbouring regions, as region x and y steps, by their 3 bit number. */
	private static final int[][] NEIGHBOURS = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

	private ShareCodec()
	{
	}

	/** The value of a code character, or -1 when it is not one. */
	static int value(char c)
	{
		if (c >= 128)
		{
			return -1;
		}
		char lower = Character.toLowerCase(c);
		if (lower == 'o')
		{
			return 0;
		}
		if (lower == 'i' || lower == 'l')
		{
			return 1;
		}
		return ALPHABET.indexOf(lower);
	}

	/** A code written the one way this class writes it: lower case, with o, i and l read as 0, 1 and 1. */
	public static String canonical(String code) throws ShareException
	{
		StringBuilder out = new StringBuilder(code.length());
		for (int i = 0; i < code.length(); i++)
		{
			int v = value(code.charAt(i));
			if (v < 0)
			{
				throw new ShareException("The code has a character that is not used in codes: " + code.charAt(i));
			}
			out.append(ALPHABET.charAt(v));
		}
		return out.toString();
	}

	// ------------------------------------------------------------------
	// Writing
	// ------------------------------------------------------------------

	/** The code of a share, without {@code !wmp }. */
	public static String encode(Share share) throws ShareException
	{
		List<SharePoint> points = share.getPoints();
		if (points.isEmpty())
		{
			throw new ShareException("There is nothing to share.");
		}
		if (points.size() > MAX_POINTS)
		{
			throw new ShareException("A code holds at most " + MAX_POINTS + " places.");
		}
		if (share.getKind() == Share.Kind.PIN && points.size() != 1)
		{
			throw new ShareException("A pin code holds one place.");
		}
		boolean maps = false;
		for (SharePoint point : points)
		{
			if (!point.isValid())
			{
				throw new ShareException("A place is outside what a code can hold: " + point);
			}
			maps |= point.hasMap();
		}
		Bits.Writer out = new Bits.Writer();
		out.write(ALPHABET.indexOf(share.getKind().getLetter()), 5);
		out.write(VERSION, 5);
		if (share.getKind() != Share.Kind.PIN)
		{
			out.write(points.size() - 1, 6);
		}
		if (share.getKind() == Share.Kind.ROUTE)
		{
			out.write(share.loops() ? 1 : 0, 1);
		}
		out.write(maps ? 1 : 0, 1);
		for (int i = 0; i < points.size(); i++)
		{
			SharePoint point = points.get(i);
			boolean copied = i > 0 && writeRecord(out, points, i);
			if (i == 0)
			{
				writeAbsolute(out, point);
			}
			if (maps && !copied)
			{
				writeMap(out, point);
			}
		}
		out.write(Bits.crc10(out.toArray(), 0, out.size(), 0), 10);
		while (out.size() % 5 != 0)
		{
			out.write(0, 1);
		}
		byte[] bits = out.toArray();
		StringBuilder text = new StringBuilder(bits.length / 5);
		for (int i = 0; i < bits.length; i += 5)
		{
			text.append(ALPHABET.charAt(bits[i] << 4 | bits[i + 1] << 3 | bits[i + 2] << 2 | bits[i + 3] << 1 | bits[i + 4]));
		}
		return text.toString();
	}

	/** The bits a point costs as each record, in the order ties are broken. -1 where the record cannot be used. */
	static int[] costs(List<SharePoint> points, int i)
	{
		SharePoint a = points.get(i - 1);
		SharePoint b = points.get(i);
		int dx = b.getX() - a.getX();
		int dy = b.getY() - a.getY();
		boolean samePlane = a.getPlane() == b.getPlane();
		// Region x and y are compared apart: a packed region id only holds y below 16384.
		boolean sameRegion = a.getX() >> 6 == b.getX() >> 6 && a.getY() >> 6 == b.getY() >> 6;
		int[] cost = {-1, -1, -1, -1, -1, -1, -1};
		if (samePlane && fits(dx, 4) && fits(dy, 4))
		{
			cost[0] = 2 + 8;
		}
		if (samePlane && sameRegion)
		{
			cost[1] = 2 + 12;
		}
		if (earlier(points, i) >= 0)
		{
			cost[2] = 4 + 6;
		}
		if (samePlane && neighbour(a, b) >= 0)
		{
			cost[3] = 3 + 3 + 12;
		}
		if (samePlane && fits(dx, 8) && fits(dy, 8))
		{
			cost[4] = 3 + 16;
		}
		if (!samePlane && sameRegion)
		{
			cost[5] = 4 + 2 + 12;
		}
		cost[6] = 3 + absoluteBits(b);
		return cost;
	}

	/**
	 * Writes the record of point i (i > 0) in its cheapest form, ties going to the earlier form in {@link #costs}. Every
	 * form gives back exactly the same point, and what the next point is measured from is that point whatever the form,
	 * so the cheapest form for each point on its own is also the cheapest code for the whole list. Returns true when the
	 * record copied an earlier point, map included.
	 */
	private static boolean writeRecord(Bits.Writer out, List<SharePoint> points, int i)
	{
		int[] cost = costs(points, i);
		boolean maps = false;
		for (SharePoint point : points)
		{
			maps |= point.hasMap();
		}
		int best = -1;
		int bestBits = Integer.MAX_VALUE;
		for (int form = 0; form < cost.length; form++)
		{
			if (cost[form] < 0)
			{
				continue;
			}
			// Every form but a copy is followed by the map part, so a copy saves that too.
			int bits = cost[form] + (maps && form != 2 ? mapBits(points.get(i)) : 0);
			if (bits < bestBits)
			{
				best = form;
				bestBits = bits;
			}
		}
		SharePoint a = points.get(i - 1);
		SharePoint b = points.get(i);
		switch (best)
		{
			case 0:
				out.write(SMALL_STEP, 2);
				out.write(b.getX() - a.getX(), 4);
				out.write(b.getY() - a.getY(), 4);
				return false;
			case 1:
				out.write(SAME_REGION, 2);
				out.write(b.localX(), 6);
				out.write(b.localY(), 6);
				return false;
			case 2:
				out.write(EARLIER, 4);
				out.write(earlier(points, i), 6);
				return true;
			case 3:
				out.write(NEXT_REGION, 3);
				out.write(neighbour(a, b), 3);
				out.write(b.localX(), 6);
				out.write(b.localY(), 6);
				return false;
			case 4:
				out.write(MEDIUM_STEP, 3);
				out.write(b.getX() - a.getX(), 8);
				out.write(b.getY() - a.getY(), 8);
				return false;
			case 5:
				out.write(FLOOR, 4);
				out.write(b.getPlane(), 2);
				out.write(b.localX(), 6);
				out.write(b.localY(), 6);
				return false;
			default:
				out.write(ABSOLUTE, 3);
				writeAbsolute(out, b);
				return false;
		}
	}

	private static boolean fits(int value, int width)
	{
		return value >= -(1 << width - 1) && value < 1 << width - 1;
	}

	/** The first point before point i that is the same in every way, map included, before the one just before it. */
	private static int earlier(List<SharePoint> points, int i)
	{
		for (int j = 0; j < i - 1; j++)
		{
			if (points.get(j).equals(points.get(i)))
			{
				return j;
			}
		}
		return -1;
	}

	/** The number of b's region among a's neighbours, or -1 when it is not one of them. */
	private static int neighbour(SharePoint a, SharePoint b)
	{
		int rx = (b.getX() >> 6) - (a.getX() >> 6);
		int ry = (b.getY() >> 6) - (a.getY() >> 6);
		for (int n = 0; n < NEIGHBOURS.length; n++)
		{
			if (NEIGHBOURS[n][0] == rx && NEIGHBOURS[n][1] == ry)
			{
				return n;
			}
		}
		return -1;
	}

	private static boolean wide(SharePoint point)
	{
		return point.getX() >= 1 << 12 || point.getY() >= 1 << 14;
	}

	private static int absoluteBits(SharePoint point)
	{
		return wide(point) ? 2 + 1 + 15 + 15 : 2 + 1 + 12 + 14;
	}

	private static void writeAbsolute(Bits.Writer out, SharePoint point)
	{
		out.write(point.getPlane(), 2);
		boolean wide = wide(point);
		out.write(wide ? 1 : 0, 1);
		out.write(point.getX(), wide ? 15 : 12);
		out.write(point.getY(), wide ? 15 : 14);
	}

	/** The bits of a map part: one for "the tile's own map", or one, a class and a number. */
	private static int mapBits(SharePoint point)
	{
		if (!point.hasMap())
		{
			return 1;
		}
		return mapClass(point.getMap()) == 3 ? 1 + 2 + 15 : 1 + 2 + 8;
	}

	private static int mapClass(int map)
	{
		if (map < 256)
		{
			return 0;
		}
		if (map >= 10_000 && map < 10_256)
		{
			return 1;
		}
		if (map >= 11_000 && map < 11_256)
		{
			return 2;
		}
		return 3;
	}

	private static final int[] MAP_BASE = {0, 10_000, 11_000, 0};

	private static void writeMap(Bits.Writer out, SharePoint point)
	{
		if (!point.hasMap())
		{
			out.write(0, 1);
			return;
		}
		int kind = mapClass(point.getMap());
		out.write(1, 1);
		out.write(kind, 2);
		out.write(point.getMap() - MAP_BASE[kind], kind == 3 ? 15 : 8);
	}

	// ------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------

	/** The bits of a code's characters, five each. */
	private static byte[] bitsOf(String code) throws ShareException
	{
		byte[] bits = new byte[code.length() * 5];
		for (int i = 0; i < code.length(); i++)
		{
			int v = value(code.charAt(i));
			if (v < 0)
			{
				throw new ShareException("The code has a character that is not used in codes: " + code.charAt(i));
			}
			for (int b = 0; b < 5; b++)
			{
				bits[i * 5 + b] = (byte) (v >> 4 - b & 1);
			}
		}
		return bits;
	}

	/** Reads a code (without {@code !wmp }), which must be a whole pin, route or set of places. */
	public static Share decode(String code) throws ShareException
	{
		if (code == null || code.length() < 4)
		{
			throw new ShareException("The code is too short.");
		}
		if (code.length() > MAX_CODE)
		{
			throw new ShareException("The code is too long.");
		}
		byte[] bits = bitsOf(code);
		Bits.Reader in = new Bits.Reader(bits);
		int letter = in.read(5);
		int version = in.read(5);
		if (version != VERSION)
		{
			throw new ShareException(version > VERSION ? "The code was made by a newer version of World Map Plus."
				: "That is not a World Map Plus code.");
		}
		Share.Kind kind = null;
		for (Share.Kind k : Share.Kind.values())
		{
			if (ALPHABET.indexOf(k.getLetter()) == letter)
			{
				kind = k;
			}
		}
		if (kind == null)
		{
			throw new ShareException(ALPHABET.charAt(letter) == PART ? "That is one part of a code sent in several messages."
				: "That is not a kind of code this version knows.");
		}
		int count = kind == Share.Kind.PIN ? 1 : in.read(6) + 1;
		boolean loop = kind == Share.Kind.ROUTE && in.read(1) == 1;
		boolean maps = in.read(1) == 1;
		List<SharePoint> points = new ArrayList<>(count);
		boolean anyMap = false;
		for (int i = 0; i < count; i++)
		{
			SharePoint point;
			boolean copied = false;
			if (i == 0)
			{
				point = readAbsolute(in);
			}
			else
			{
				SharePoint a = points.get(i - 1);
				int opcode = in.read(2);
				if (opcode == SAME_REGION)
				{
					point = local(a, a.getX() >> 6, a.getY() >> 6, a.getPlane(), in);
				}
				else if (opcode == SMALL_STEP)
				{
					point = step(a, in.signed(4), in.signed(4));
				}
				else
				{
					opcode = opcode << 1 | in.read(1);
					if (opcode == NEXT_REGION)
					{
						int[] n = NEIGHBOURS[in.read(3)];
						point = local(a, (a.getX() >> 6) + n[0], (a.getY() >> 6) + n[1], a.getPlane(), in);
					}
					else if (opcode == MEDIUM_STEP)
					{
						point = step(a, in.signed(8), in.signed(8));
					}
					else if (opcode == ABSOLUTE)
					{
						point = readAbsolute(in);
					}
					else
					{
						opcode = opcode << 1 | in.read(1);
						if (opcode == EARLIER)
						{
							int j = in.read(6);
							if (j >= i)
							{
								throw new ShareException("The code points at a place it has not given yet.");
							}
							point = points.get(j);
							copied = true;
						}
						else
						{
							int plane = in.read(2);
							point = local(a, a.getX() >> 6, a.getY() >> 6, plane, in);
						}
					}
				}
			}
			if (maps && !copied)
			{
				point = readMap(in, point);
			}
			anyMap |= point.hasMap();
			points.add(point);
		}
		if (maps && !anyMap)
		{
			throw new ShareException("The code says it names maps and names none.");
		}
		int end = in.position();
		int check = in.read(10);
		if (check != Bits.crc10(bits, 0, end, 0))
		{
			throw new ShareException("The code is damaged or mistyped (its check does not match).");
		}
		if (in.remaining() >= 5)
		{
			throw new ShareException("The code has more in it than it says.");
		}
		while (in.remaining() > 0)
		{
			if (in.read(1) != 0)
			{
				throw new ShareException("The code ends with something that should not be there.");
			}
		}
		return new Share(kind, loop, points);
	}

	/** The longest code whose mistyped character is looked for, so the search stays a few thousand tries. */
	static final int MAX_TYPO_SEARCH = 2 * MAX_SINGLE;

	/**
	 * Where a code that does not read may be mistyped: the positions (from 1) at which changing one character makes the
	 * code read, at most three of them, in order. Empty when no single change makes it read, when more than three places
	 * could, or when the code is too long to search. A pin has one such place almost always; a long code may have more,
	 * since a 10 bit check passes about one change in a thousand by chance.
	 */
	public static List<Integer> typoAt(String code)
	{
		List<Integer> at = new ArrayList<>();
		if (code == null || code.length() < 4 || code.length() > MAX_TYPO_SEARCH || isPart(code))
		{
			return at;
		}
		String text;
		try
		{
			text = canonical(code);
			decode(text);
			return at;
		}
		catch (ShareException e)
		{
			text = canonicalOrNull(code);
		}
		if (text == null)
		{
			return at;
		}
		char[] chars = text.toCharArray();
		// The kind letter and the version are left alone: a change there makes another kind of code, not a fix.
		for (int i = 2; i < chars.length; i++)
		{
			char was = chars[i];
			for (int v = 0; v < ALPHABET.length(); v++)
			{
				char now = ALPHABET.charAt(v);
				if (now == was)
				{
					continue;
				}
				chars[i] = now;
				if (reads(new String(chars)))
				{
					at.add(i + 1);
					break;
				}
			}
			chars[i] = was;
			if (at.size() > 3)
			{
				return new ArrayList<>();
			}
		}
		return at;
	}

	private static String canonicalOrNull(String code)
	{
		try
		{
			return canonical(code);
		}
		catch (ShareException e)
		{
			return null;
		}
	}

	private static boolean reads(String code)
	{
		try
		{
			decode(code);
			return true;
		}
		catch (ShareException e)
		{
			return false;
		}
	}

	private static SharePoint readAbsolute(Bits.Reader in) throws ShareException
	{
		int plane = in.read(2);
		boolean wide = in.read(1) == 1;
		int x = in.read(wide ? 15 : 12);
		int y = in.read(wide ? 15 : 14);
		if (wide && x < 1 << 12 && y < 1 << 14)
		{
			throw new ShareException("The code writes a place in a longer form than it needs.");
		}
		return new SharePoint(x, y, plane);
	}

	private static SharePoint local(SharePoint from, int regionX, int regionY, int plane, Bits.Reader in) throws ShareException
	{
		int x = regionX * 64 + in.read(6);
		int y = regionY * 64 + in.read(6);
		return checked(new SharePoint(x, y, plane));
	}

	private static SharePoint step(SharePoint from, int dx, int dy) throws ShareException
	{
		return checked(new SharePoint(from.getX() + dx, from.getY() + dy, from.getPlane()));
	}

	private static SharePoint checked(SharePoint point) throws ShareException
	{
		if (!point.isValid())
		{
			throw new ShareException("The code holds a place outside every map.");
		}
		return point;
	}

	private static SharePoint readMap(Bits.Reader in, SharePoint point) throws ShareException
	{
		if (in.read(1) == 0)
		{
			return point;
		}
		int kind = in.read(2);
		int map = MAP_BASE[kind] + in.read(kind == 3 ? 15 : 8);
		if (kind == 3 && mapClass(map) != 3)
		{
			throw new ShareException("The code writes a map in a longer form than it needs.");
		}
		return new SharePoint(point.getX(), point.getY(), point.getPlane(), map);
	}

	// ------------------------------------------------------------------
	// Chat messages and parts
	// ------------------------------------------------------------------

	/** The chat messages that carry a share: one, or up to {@link #MAX_PARTS} parts. */
	public static List<String> messages(Share share) throws ShareException
	{
		return split(encode(share));
	}

	/** The chat messages for a code: {@code !wmp <code>} when it fits one message, else its parts. */
	public static List<String> split(String code) throws ShareException
	{
		List<String> messages = new ArrayList<>();
		if (code.length() <= MAX_SINGLE)
		{
			messages.add(COMMAND + " " + code);
			return messages;
		}
		int parts = (code.length() + MAX_PART_DATA - 1) / MAX_PART_DATA;
		if (parts > MAX_PARTS)
		{
			throw new ShareException("That needs more than " + MAX_PARTS + " chat messages. Share fewer stops.");
		}
		int size = (code.length() + parts - 1) / parts;
		int transfer = transferId(code);
		for (int i = 0; i < parts; i++)
		{
			int head = transfer << 6 | i << 3 | parts - 1;
			String data = code.substring(i * size, Math.min(code.length(), (i + 1) * size));
			messages.add(COMMAND + " " + PART + VERSION + ALPHABET.charAt(head >> 10 & 31) + ALPHABET.charAt(head >> 5 & 31)
				+ ALPHABET.charAt(head & 31) + data);
		}
		return messages;
	}

	/**
	 * The 9 bit transfer id of a code: the top 9 bits of the 32 bit FNV-1a hash of its characters in canonical form. A
	 * hash unrelated to the code's own checksum, so it tells codes apart and also checks the joined parts once more.
	 */
	static int transferId(String code) throws ShareException
	{
		String text = canonical(code);
		int hash = 0x811C9DC5;
		for (int i = 0; i < text.length(); i++)
		{
			hash ^= text.charAt(i);
			hash *= 0x01000193;
		}
		return hash >>> 23;
	}

	/** One part of a code sent in several messages. */
	public static final class Part
	{
		private final int transfer;
		private final int index;
		private final int total;
		private final String data;

		Part(int transfer, int index, int total, String data)
		{
			this.transfer = transfer;
			this.index = index;
			this.total = total;
			this.data = data;
		}

		public int getTransfer()
		{
			return transfer;
		}

		/** The part's number, from 0. */
		public int getIndex()
		{
			return index;
		}

		public int getTotal()
		{
			return total;
		}

		/** Its piece of the code, in canonical form. */
		public String getData()
		{
			return data;
		}
	}

	/** True when a code (after {@code !wmp }) is one part of a longer code. */
	public static boolean isPart(String code)
	{
		return code != null && code.length() >= 2 && value(code.charAt(0)) == ALPHABET.indexOf(PART);
	}

	/** Reads one part. */
	public static Part part(String code) throws ShareException
	{
		if (!isPart(code) || code.length() <= PART_HEAD || code.length() > MAX_SINGLE)
		{
			throw new ShareException("That is not a part of a code.");
		}
		String text = canonical(code);
		int version = value(text.charAt(1));
		if (version != VERSION)
		{
			throw new ShareException(version > VERSION ? "The code was made by a newer version of World Map Plus."
				: "That is not a World Map Plus code.");
		}
		int head = value(text.charAt(2)) << 10 | value(text.charAt(3)) << 5 | value(text.charAt(4));
		int index = head >> 3 & 7;
		int total = (head & 7) + 1;
		if (total < 2 || index >= total)
		{
			throw new ShareException("The part is numbered wrongly.");
		}
		return new Part(head >> 6, index, total, text.substring(PART_HEAD));
	}

	/** Joins all the parts of a code, in order, and reads it. The joined code must match the parts' transfer id. */
	public static Share join(List<Part> parts) throws ShareException
	{
		if (parts.isEmpty() || parts.size() != parts.get(0).getTotal())
		{
			throw new ShareException("Some parts are missing.");
		}
		StringBuilder code = new StringBuilder();
		for (int i = 0; i < parts.size(); i++)
		{
			Part part = parts.get(i);
			if (part == null || part.getIndex() != i || part.getTotal() != parts.size()
				|| part.getTransfer() != parts.get(0).getTransfer())
			{
				throw new ShareException("The parts do not belong together.");
			}
			code.append(part.getData());
		}
		String joined = code.toString();
		if (joined.length() <= MAX_SINGLE || transferId(joined) != parts.get(0).getTransfer())
		{
			throw new ShareException("The parts do not fit together. One may be mistyped.");
		}
		Share share = decode(joined);
		if (share.getKind() == Share.Kind.PIN)
		{
			throw new ShareException("The parts do not fit together. One may be mistyped.");
		}
		return share;
	}

	/**
	 * The code in a chat line: the word after {@code !wmp} at the start of the line, ignoring colour tags, in lower case.
	 * Null when the line does not start with the command. The word is not checked here.
	 */
	public static String codeIn(String line)
	{
		if (line == null)
		{
			return null;
		}
		String text = line.length() > 400 ? line.substring(0, 400) : line;
		text = text.replaceAll("<[^>]*>", "").trim();
		if (!text.regionMatches(true, 0, COMMAND, 0, COMMAND.length()) || text.length() <= COMMAND.length()
			|| text.charAt(COMMAND.length()) != ' ')
		{
			return null;
		}
		int start = COMMAND.length();
		while (start < text.length() && text.charAt(start) == ' ')
		{
			start++;
		}
		int end = start;
		while (end < text.length() && end - start <= MAX_SINGLE && Character.isLetterOrDigit(text.charAt(end)))
		{
			end++;
		}
		return end > start ? text.substring(start, end).toLowerCase(Locale.ROOT) : null;
	}
}
