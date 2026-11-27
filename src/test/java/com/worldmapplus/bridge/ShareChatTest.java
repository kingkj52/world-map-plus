package com.worldmapplus.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.share.Share;
import com.worldmapplus.share.ShareCodec;
import com.worldmapplus.share.ShareInbox;
import com.worldmapplus.share.SharePoint;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;

/**
 * The chat end of sharing, driven through RuneLite's real {@link ChatCommandManager}: a line with a code shows what it
 * holds in place of the code (its RuneLite format message, the message itself kept), nothing is saved, and the right
 * click entry is RuneLite's own type and opens the share decoded for the clicked line, found by the line's message id.
 */
public class ShareChatTest
{
	private static final String ME = "Test Player";
	private static final String HL = "<colHIGHLIGHT>World Map Plus<colNORMAL>";

	private ChatCommandManager commands;
	private ShareInbox inbox;
	private ShareChat chat;
	private int refreshed;
	private boolean recognise = true;
	private boolean replaceMine = true;
	private int nextId = 1000;
	private final List<Share> opened = new ArrayList<>();

	/** What the client was asked to put in the menu, and the text of the chat line's message widget. */
	private final List<Map<String, Object>> entries = new ArrayList<>();
	private String lineText;

	@Before
	public void setUp() throws Exception
	{
		Constructor<ChatCommandManager> make = ChatCommandManager.class.getDeclaredConstructor(EventBus.class,
			Class.forName("net.runelite.client.chat.ChatInputManager"), java.util.concurrent.ScheduledExecutorService.class);
		make.setAccessible(true);
		commands = make.newInstance(new EventBus(), null, Executors.newSingleThreadScheduledExecutor());
		inbox = new ShareInbox();
		WorldMapPlusConfig config = new WorldMapPlusConfig()
		{
			@Override
			public WorldMapPlusConfig.ShareCodes shareCodes()
			{
				return !recognise ? WorldMapPlusConfig.ShareCodes.OFF
					: replaceMine ? WorldMapPlusConfig.ShareCodes.FROM_EVERYONE : WorldMapPlusConfig.ShareCodes.FROM_OTHERS;
			}
		};
		chat = new ShareChat(client(), commands, inbox, config);
		chat.start(opened::add, point -> point.getX() == 1249 ? "Farming Guild" : point.getX() == 3222 ? "Lum<bridge>" : null);
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
	}

	private Client client()
	{
		Player me = proxy(Player.class, (p, method, args) -> method.getName().equals("getName") ? ME : null);
		Widget message = proxy(Widget.class, (p, method, args) -> method.getName().equals("getText") ? lineText : null);
		Widget lines = proxy(Widget.class, (p, method, args) ->
		{
			switch (method.getName())
			{
				case "getId":
					return InterfaceID.Chatbox.SCROLLAREA;
				case "getChild":
					// Line 2 of the chat box: its message is dynamic child (2 * 4 + 1).
					return (Integer) args[0] == 9 ? message : null;
				default:
					return null;
			}
		});
		Widget line = proxy(Widget.class, (p, method, args) -> method.getName().equals("getParent") ? lines : null);
		Menu menu = proxy(Menu.class, (p, method, args) ->
		{
			if (method.getName().equals("createMenuEntry"))
			{
				Map<String, Object> entry = new HashMap<>();
				entries.add(entry);
				return proxy(MenuEntry.class, (e, m, a) ->
				{
					entry.put(m.getName(), a == null ? null : a[0]);
					return e;
				});
			}
			return null;
		});
		return proxy(Client.class, (p, method, args) ->
		{
			switch (method.getName())
			{
				case "refreshChat":
					refreshed++;
					return null;
				case "getLocalPlayer":
					return me;
				case "getWidget":
					return (Integer) args[0] == InterfaceID.Chatbox.LINE0 + 2 ? line : null;
				case "getMenu":
					return menu;
				default:
					return null;
			}
		});
	}

	/** A chat line as the game keeps it: the message, the RuneLite format message drawn in its place, and its id. */
	private static final class Line
	{
		int id;
		String value;
		String format;
		int valueSets;
		int formatSets;

		/** What the game draws for this line, as the message widget's text: the format message when set, coloured. */
		String drawn()
		{
			return "<col=0000ff>" + (format != null ? format.replace("<colNORMAL>", "</col>").replace("<colHIGHLIGHT>",
				"<col=ff0000>") : value) + "</col>";
		}
	}

	private Line say(ChatMessageType type, String sender, String text)
	{
		Line line = new Line();
		line.id = nextId++;
		line.value = text;
		MessageNode node = proxy(MessageNode.class, (p, method, args) ->
		{
			switch (method.getName())
			{
				case "setValue":
					line.value = (String) args[0];
					line.valueSets++;
					return null;
				case "setRuneLiteFormatMessage":
					line.format = (String) args[0];
					line.formatSets++;
					return null;
				case "getRuneLiteFormatMessage":
					return line.format;
				case "getValue":
					return line.value;
				case "getId":
					return line.id;
				default:
					return null;
			}
		});
		commands.onChatMessage(new ChatMessage(node, type, sender, text, null, 0));
		return line;
	}

	/** The description a line shows, without colours and the id tags. */
	private static String shown(Line line)
	{
		return line.format == null ? null : net.runelite.client.util.Text.removeTags(line.format.replace("<colHIGHLIGHT>", "")
			.replace("<colNORMAL>", ""));
	}

	private static List<String> route15() throws Exception
	{
		List<SharePoint> stops = new ArrayList<>();
		for (int i = 0; i < 15; i++)
		{
			stops.add(new SharePoint(1000 + i * 190, 2100 + i * 130, i % 2));
		}
		return ShareCodec.messages(Share.route(stops, false));
	}

	private static final String WHOLE_15 = "World Map Plus route: 15 stops, starts at 1000, 2100";

	/** Right clicks the line's Report entry with the line drawn as the game draws it, and clicks the entry if there is one. */
	private Share open(Line line)
	{
		entries.clear();
		opened.clear();
		lineText = line.drawn();
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 2));
		if (entries.isEmpty())
		{
			return null;
		}
		@SuppressWarnings("unchecked")
		Consumer<MenuEntry> click = (Consumer<MenuEntry>) entries.get(0).get("onClick");
		click.accept(null);
		return opened.get(0);
	}

	@Test
	public void theRouteOfFifteenStopsTakesTwoMessages() throws Exception
	{
		assertEquals(2, route15().size());
	}

	@Test
	public void eachKindShowsWhatItHoldsInPlaceOfTheCode() throws Exception
	{
		Line pin = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86a");
		assertEquals("a known name", "World Map Plus pin: Farming Guild, 1249, 3720", shown(pin));
		assertTrue(pin.format, pin.format.startsWith(HL + " pin: Farming Guild, 1249, 3720"));
		Line named = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p11jb34j2v");
		assertTrue("a name's brackets are escaped, so it cannot make tags", named.format.startsWith(HL + " pin: Lum<lt>bridge<gt>, 3222, 3218"));
		Line other = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p19j434mgz");
		assertEquals("no name is guessed", "World Map Plus pin: 3208, 3220, floor 2", shown(other));
		Line route = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp r11rce4tv129e0969rb2");
		assertEquals("World Map Plus route: 4 stops, loops, starts at 3185, 3436", shown(route));
		Line places = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp s118s5hj963a1jr380rc2gsarym");
		assertEquals("World Map Plus places: 3", shown(places));
		Line bad = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86b");
		assertEquals("the code stays where it cannot be read", "World Map Plus code that could not be read: p10kgkm86b", shown(bad));
		assertFalse("no share, so no id tags", bad.format.contains(ShareChat.ID_HIGH));
		assertTrue(pin.format.endsWith(ShareChat.idTags(pin.id)));
	}

	@Test
	public void theMessageItselfIsKeptForHistoryAndOtherPlugins() throws Exception
	{
		Line line = say(ChatMessageType.CLAN_CHAT, "Someone", "!wmp p10kgkm86a");
		assertEquals("!wmp p10kgkm86a", line.value);
		assertEquals(0, line.valueSets);
		assertEquals(1, line.formatSets);
		assertEquals(1, refreshed);
		List<String> messages = route15();
		Line first = say(ChatMessageType.CLAN_CHAT, "Someone", messages.get(0));
		say(ChatMessageType.CLAN_CHAT, "Someone", messages.get(1));
		assertEquals(messages.get(0), first.value);
		assertEquals(0, first.valueSets);
	}

	@Test
	public void thePlayersOwnLinesAreReplacedOnEveryChannelAndNeverListed()
	{
		for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.PUBLICCHAT, ChatMessageType.FRIENDSCHAT,
			ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT, ChatMessageType.MODCHAT})
		{
			Line line = say(type, "<img=2>" + ME, "!wmp p10kgkm86a");
			assertEquals(type.name(), "World Map Plus pin: Farming Guild, 1249, 3720", shown(line));
		}
		// The echo of a private message the player sent names the player it went to.
		Line out = say(ChatMessageType.PRIVATECHATOUT, "Someone", "!wmp p19j434mgz");
		assertEquals("World Map Plus pin: 3208, 3220, floor 2", shown(out));
		assertEquals("the player's own codes are not listed as shared with them", 0, inbox.offers().size());
	}

	@Test
	public void theSettingKeepsThePlayersOwnLinesAsTyped() throws Exception
	{
		replaceMine = false;
		Line mine = say(ChatMessageType.PUBLICCHAT, ME, "!wmp p10kgkm86a");
		assertNull(mine.format);
		assertEquals(0, refreshed);
		List<String> messages = route15();
		Line first = say(ChatMessageType.CLAN_CHAT, ME, messages.get(0));
		Line second = say(ChatMessageType.CLAN_CHAT, ME, messages.get(1));
		assertNull(first.format);
		assertNull(second.format);
		assertEquals(0, inbox.offers().size());
		Line theirs = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p19j434mgz");
		assertEquals("lines of others are replaced still", "World Map Plus pin: 3208, 3220, floor 2", shown(theirs));
	}

	@Test
	public void aPartSaysItWaitsAndTheLastPartRelabelsEveryPart() throws Exception
	{
		List<String> messages = route15();
		Line first = say(ChatMessageType.CLAN_CHAT, ME, messages.get(0));
		assertEquals("World Map Plus: part 1 of 2 received, waiting for the next part", shown(first));
		Line second = say(ChatMessageType.CLAN_CHAT, ME, messages.get(1));
		assertEquals(WHOLE_15, shown(first));
		assertEquals(WHOLE_15, shown(second));
		assertEquals("every part line shows the same single message text", first.format.replace(ShareChat.idTags(first.id), ""),
			second.format.replace(ShareChat.idTags(second.id), ""));
		assertEquals(0, first.valueSets);
	}

	@Test
	public void aPartWithoutTheFirstDoesNotGuessTheKind() throws Exception
	{
		List<String> messages = route15();
		Line second = say(ChatMessageType.CLAN_CHAT, "Someone", messages.get(1));
		assertEquals("World Map Plus: part 2 of 2 received, waiting for the next part", shown(second));
		assertNull("nothing to open yet", open(second));
	}

	@Test
	public void partsJoinInAnyOrderAndAPartTypedTwiceIsRelabelledToo() throws Exception
	{
		// The long route of the protocol's golden vectors, in three messages.
		java.util.Random random = new java.util.Random(7);
		List<SharePoint> stops = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			stops.add(new SharePoint(960 + random.nextInt(3072), 2048 + random.nextInt(2176), 0));
		}
		List<String> messages = ShareCodec.messages(Share.route(stops, false));
		assertEquals(3, messages.size());
		Line third = say(ChatMessageType.FRIENDSCHAT, "Someone", messages.get(2));
		assertTrue(shown(third), shown(third).endsWith("part 3 of 3 received, waiting for 2 more parts"));
		Line again = say(ChatMessageType.FRIENDSCHAT, "Someone", messages.get(2));
		assertTrue(shown(again), shown(again).endsWith("waiting for 2 more parts"));
		Line first = say(ChatMessageType.FRIENDSCHAT, "Someone", messages.get(0));
		assertEquals("World Map Plus: part 1 of 3 received, waiting for the next part", shown(first));
		Line second = say(ChatMessageType.FRIENDSCHAT, "Someone", messages.get(1));
		String whole = "World Map Plus route: 30 stops, starts at " + stops.get(0).describe();
		for (Line line : new Line[] {third, again, first, second})
		{
			assertEquals(whole, shown(line));
			assertEquals(Share.route(stops, false), open(line));
		}
		assertEquals(1, inbox.offers().size());
		// A part sent again after the code is whole says what the whole code says.
		Line late = say(ChatMessageType.FRIENDSCHAT, "Someone", messages.get(1));
		assertEquals(whole, shown(late));
		assertEquals(Share.route(stops, false), open(late));
	}

	@Test
	public void partsOfTwoSendersAndOfThePlayerAreKeptApart() throws Exception
	{
		List<String> messages = route15();
		Line ann = say(ChatMessageType.CLAN_CHAT, "Ann", messages.get(0));
		Line mine = say(ChatMessageType.CLAN_CHAT, ME, messages.get(0));
		Line bob = say(ChatMessageType.CLAN_CHAT, "Bob", messages.get(1));
		for (Line line : new Line[] {ann, mine, bob})
		{
			assertTrue(shown(line), shown(line).contains("waiting for the next part"));
		}
		say(ChatMessageType.CLAN_CHAT, "Ann", messages.get(1));
		assertEquals(WHOLE_15, shown(ann));
		assertTrue("the player's and Bob's parts still wait", shown(mine).contains("waiting") && shown(bob).contains("waiting"));
		say(ChatMessageType.CLAN_CHAT, ME, messages.get(1));
		assertEquals(WHOLE_15, shown(mine));
		assertTrue(shown(bob).contains("waiting"));
	}

	@Test
	public void aRelabelLeavesALineAloneThatSomethingElseChanged() throws Exception
	{
		List<String> messages = route15();
		Line first = say(ChatMessageType.PUBLICCHAT, "Someone", messages.get(0));
		first.format = "another plugin's text";
		say(ChatMessageType.PUBLICCHAT, "Someone", messages.get(1));
		assertEquals("another plugin's text", first.format);
	}

	@Test
	public void aPartThatNeverCompletesKeepsWaitingAndIsForgotten() throws Exception
	{
		List<String> messages = route15();
		Line first = say(ChatMessageType.PUBLICCHAT, "Someone", messages.get(0));
		// More codes in parts than are kept at once push the first out; its line keeps its waiting text.
		for (int i = 0; i < 8; i++)
		{
			List<SharePoint> stops = new ArrayList<>();
			for (int s = 0; s < 30; s++)
			{
				stops.add(new SharePoint(1000 + s * 90 + i, 2100 + s * 60, 0));
			}
			say(ChatMessageType.PUBLICCHAT, "Someone", ShareCodec.messages(Share.route(stops, false)).get(0));
		}
		say(ChatMessageType.PUBLICCHAT, "Someone", messages.get(1));
		assertEquals("World Map Plus: part 1 of 2 received, waiting for the next part", shown(first));
	}

	@Test
	public void whatThePlayerSendsIsNeverHeldOrChanged() throws Exception
	{
		for (String message : route15())
		{
			net.runelite.client.events.ChatboxInput typed = new net.runelite.client.events.ChatboxInput(message, 0, () -> { });
			commands.onChatboxInput(typed);
			assertFalse("the command has no input hook, so the message goes out as typed", typed.isConsumed());
			assertEquals(message, typed.getValue());
		}
		assertEquals(0, refreshed);
	}

	@Test
	public void startingTwiceStillShowsOnce()
	{
		chat.start(opened::add, null);
		Line line = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86a");
		assertEquals(1, line.formatSets);
		assertEquals(1, refreshed);
	}

	@Test
	public void theClickOpensTheLinesOwnShareAfterChatMovesOn() throws Exception
	{
		Line pin = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86a");
		lineText = pin.drawn();
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 2));
		assertEquals(1, entries.size());
		Map<String, Object> entry = entries.get(0);
		assertEquals(ShareChat.OPEN, entry.get("setOption"));
		assertSame(MenuAction.RUNELITE, entry.get("setType"));
		assertFalse("no identifier or parameter that the game would act on", entry.containsKey("setIdentifier")
			|| entry.containsKey("setParam0") || entry.containsKey("setParam1"));
		// More lines come and the chat box is drawn again before the click: the click still opens what the line held.
		say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p11jb34j2v");
		lineText = "something else";
		@SuppressWarnings("unchecked")
		Consumer<MenuEntry> click = (Consumer<MenuEntry>) entry.get("onClick");
		click.accept(null);
		assertEquals(Share.pin(new SharePoint(1249, 3720, 0)), opened.get(0));
		// A refresh draws the line again from its format message; the same line still opens the same share.
		assertEquals(Share.pin(new SharePoint(1249, 3720, 0)), open(pin));
	}

	@Test
	public void twoRoutesThatReadTheSameOpenTheirOwnShare() throws Exception
	{
		List<SharePoint> a = new ArrayList<>();
		List<SharePoint> b = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			a.add(new SharePoint(3200 + i * 3, 3200, 0));
			b.add(new SharePoint(3200 + (i == 0 ? 0 : 40 + i * 5), 3200, 0));
		}
		Line first = say(ChatMessageType.PUBLICCHAT, "Ann", ShareCodec.messages(Share.route(a, false)).get(0));
		Line second = say(ChatMessageType.PUBLICCHAT, "Bob", ShareCodec.messages(Share.route(b, false)).get(0));
		assertEquals("the same words", shown(first), shown(second));
		assertEquals(Share.route(a, false), open(first));
		assertEquals(Share.route(b, false), open(second));
	}

	@Test
	public void theSameCodeFromTwoSendersOpensFromBothLines()
	{
		Line ann = say(ChatMessageType.CLAN_CHAT, "Ann", "!wmp p10kgkm86a");
		Line bob = say(ChatMessageType.PUBLICCHAT, "Bob", "!wmp p10kgkm86a");
		assertNotEquals(ann.format, bob.format);
		assertEquals(Share.pin(new SharePoint(1249, 3720, 0)), open(ann));
		assertEquals(Share.pin(new SharePoint(1249, 3720, 0)), open(bob));
		assertEquals("listed once", 1, inbox.offers().size());
	}

	@Test
	public void theIdTagsCarryAnyMessageId()
	{
		for (int id : new int[] {0, 1, 0xffff, 0x10000, 123_456_789, Integer.MAX_VALUE, -1})
		{
			assertEquals(id, (int) ShareChat.idIn("<col=ff0000>World Map Plus</col> pin: 1, 2" + ShareChat.idTags(id) + "</col>"));
		}
		assertEquals(-1, ShareChat.idIn("!wmp p10kgkm86a"));
		assertEquals(-1, ShareChat.idIn("<col=7e12>"));
		assertEquals(-1, ShareChat.idIn("<col=7e00zz><col=7f0001>"));
	}

	@Test
	public void theRememberedLinesAreBounded()
	{
		Line first = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86a");
		for (int i = 0; i < ShareChat.MAX_LINES; i++)
		{
			say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p11jb34j2v");
		}
		assertNull("the oldest line is forgotten", open(first));
	}

	@Test
	public void everyPartLineOffersTheWholeRouteOnceItIsWhole() throws Exception
	{
		List<String> messages = route15();
		Line first = say(ChatMessageType.CLAN_CHAT, "Someone", messages.get(0));
		assertNull("not before the rest", open(first));
		Line second = say(ChatMessageType.CLAN_CHAT, "Someone", messages.get(1));
		assertEquals(15, open(first).getPoints().size());
		assertEquals(open(first), open(second));
	}

	private MenuEntryAdded rightClick(String option, int component)
	{
		MenuEntry entry = proxy(MenuEntry.class, (p, method, args) ->
		{
			switch (method.getName())
			{
				case "getOption":
					return option;
				case "getTarget":
					return "<col=ffffff>Someone</col>";
				case "getParam1":
					return component;
				case "getType":
					return MenuAction.CC_OP_LOW_PRIORITY;
				default:
					return method.getReturnType() == int.class ? 0 : null;
			}
		});
		return new MenuEntryAdded(entry);
	}

	@Test
	public void aCodeFromAnotherPlayerIsListedNotSavedAndNotOpened()
	{
		say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86a");
		assertEquals(1, refreshed);
		assertEquals("listed, not saved and not opened", 1, inbox.offers().size());
		assertTrue(opened.isEmpty());
	}

	@Test
	public void everyChannelAPlayerCanTypeInIsRead()
	{
		for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.PUBLICCHAT, ChatMessageType.PRIVATECHAT,
			ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT,
			ChatMessageType.MODCHAT, ChatMessageType.MODPRIVATECHAT})
		{
			refreshed = 0;
			Line line = say(type, "Someone", "!wmp p10kgkm86a");
			assertEquals(type.name(), 1, refreshed);
			assertTrue(type.name(), shown(line).startsWith("World Map Plus pin: "));
		}
		assertEquals("the same pin is listed once", 1, inbox.offers().size());
	}

	@Test
	public void otherLinesAndOtherKindsAreLeftAlone()
	{
		Line logs = say(ChatMessageType.PUBLICCHAT, "Someone", "selling logs");
		assertNull(logs.format);
		Line nobody = say(ChatMessageType.PUBLICCHAT, null, "!wmp p10kgkm86a");
		assertNull("a sender that cannot be told", nobody.format);
		Line game = say(ChatMessageType.GAMEMESSAGE, "Someone", "!wmp p10kgkm86a");
		assertNull(game.format);
		assertEquals(0, refreshed);
		assertEquals(0, inbox.offers().size());
	}

	@Test
	public void theSettingTurnsItOffAndStopTakesTheCommandBack()
	{
		recognise = false;
		Line off = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86a");
		assertNull(off.format);
		recognise = true;
		chat.stop();
		Line stopped = say(ChatMessageType.PUBLICCHAT, "Someone", "!wmp p10kgkm86a");
		assertNull(stopped.format);
		assertEquals(0, inbox.offers().size());
		lineText = "!wmp p10kgkm86a";
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 2));
		assertTrue("no entry after stop", entries.isEmpty());
	}

	@Test
	public void aLineThisPluginDidNotChangeStillOffersItsCode()
	{
		// Another plugin set the line's text first, or the line is from before the plugin started: the code is read.
		lineText = "<col=0000ff>!wmp p10kgkm86a</col>";
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 2));
		assertEquals(1, entries.size());
	}

	@Test
	public void noEntryWhereThereIsNoCode()
	{
		lineText = "selling logs";
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 2));
		lineText = "!wmp p10kgkm86b";
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 2));
		lineText = "World Map Plus pin: 1, 2" + ShareChat.idTags(424242);
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 2));
		lineText = "!wmp p10kgkm86a";
		chat.onMenuEntryAdded(rightClick("Add friend", InterfaceID.Chatbox.LINE0 + 2));
		chat.onMenuEntryAdded(rightClick("Report", InterfaceID.Chatbox.LINE0 + 3));
		chat.onMenuEntryAdded(rightClick("Report", 0));
		assertTrue(entries.isEmpty());
	}
}
