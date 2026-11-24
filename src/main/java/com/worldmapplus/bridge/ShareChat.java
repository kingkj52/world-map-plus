package com.worldmapplus.bridge;

import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.share.ChatText;
import com.worldmapplus.share.Share;
import com.worldmapplus.share.ShareCodec;
import com.worldmapplus.share.ShareException;
import com.worldmapplus.share.ShareInbox;
import com.worldmapplus.share.SharePoint;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.util.Text;

/**
 * The chat end of sharing: the {@code !wmp} chat command and the right click entry "Open in World Map Plus".
 * <p>
 * A chat message that starts with {@code !wmp} reaches {@link #onCommand} through RuneLite's {@link ChatCommandManager},
 * the one path a share code takes; the plugin has no chat event handler of its own. On this client only, the line then
 * shows a short description of what the code holds in place of the command and the code, the way RuneLite's Chat
 * Commands plugin shows the answer to {@code !lvl}: with {@link MessageNode#setRuneLiteFormatMessage}, which the client
 * draws in place of the message while it builds the chat box. The message itself ({@code getValue}) is never changed,
 * nothing is sent, typed or added to chat, and what the player sends is never changed. A code from another player is
 * listed in the side panel; the player's own is not. A part of a code says it is waiting for the rest, and when the last
 * part arrives every part's line shows the whole code's description.
 * <p>
 * The right click entry follows RuneLite's Chat History plugin, which adds "Copy to clipboard" the same way: on the
 * "Report" entry of a chat line, the text of that line's message widget is read. That text is the description now, so it
 * ends with two empty colour tags that carry the line's message id (they draw nothing); the id finds the share decoded
 * for that very line in a small map kept here. An entry of type {@link MenuAction#RUNELITE} is added, which RuneLite
 * handles and which never goes to the game server. The share is looked up when the entry is made and kept in the
 * click's own closure, so the click opens what that line held even if chat has moved on since.
 */
@Singleton
public final class ShareChat
{
	/**
	 * The kinds of chat line a code is read from: every channel a player can type in, and PRIVATECHATOUT, the echo of a
	 * private message this player sent.
	 */
	private static final Set<ChatMessageType> TYPES = EnumSet.of(ChatMessageType.PUBLICCHAT, ChatMessageType.MODCHAT,
		ChatMessageType.PRIVATECHAT, ChatMessageType.MODPRIVATECHAT, ChatMessageType.PRIVATECHATOUT, ChatMessageType.FRIENDSCHAT,
		ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT);

	/** What the entry says. */
	public static final String OPEN = "Open in World Map Plus";

	/** Most chat lines whose share is remembered for the right click entry. The oldest goes first. */
	static final int MAX_LINES = 256;

	/** The two empty colour tags that end a description and carry its line's message id, high and low 16 bits. */
	static final String ID_HIGH = "<col=7e";
	static final String ID_LOW = "<col=7f";

	private final Client client;
	private final ChatCommandManager commands;
	private final ShareInbox inbox;
	private final WorldMapPlusConfig config;
	private volatile Consumer<Share> opener;
	private volatile Function<SharePoint, String> namer;

	/** The share of each chat line that shows one, by the line's message id, as decoded when the line came. */
	private final Map<Integer, Share> lineShares = new LinkedHashMap<>();

	/** The last message text looked at for the menu and what it held, so a menu rebuilt every frame reads it once. */
	private String lastText;
	private Share lastShare;

	@Inject
	public ShareChat(Client client, ChatCommandManager commands, ShareInbox inbox, WorldMapPlusConfig config)
	{
		this.client = client;
		this.commands = commands;
		this.inbox = inbox;
		this.config = config;
	}

	/** Signs up the {@code !wmp} command. {@code opener} shows a share when the player opens it. */
	public void start(Consumer<Share> opener)
	{
		start(opener, null);
	}

	/**
	 * Signs up the {@code !wmp} command. {@code opener} shows a share when the player opens it; {@code namer} gives the name
	 * of a point when it is known for sure (or null), for the description in chat.
	 */
	private volatile Sounds sounds;

	/** The cues of the Sounds setting: one when a code from another player is whole. */
	public void setSounds(@Nullable Sounds sounds)
	{
		this.sounds = sounds;
	}

	public void start(Consumer<Share> opener, @Nullable Function<SharePoint, String> namer)
	{
		this.opener = opener;
		this.namer = namer;
		commands.registerCommand(ShareCodec.COMMAND, this::onCommand);
	}

	/** Takes the command back. Safe to call more than once. */
	public void stop()
	{
		commands.unregisterCommand(ShareCodec.COMMAND);
		opener = null;
		namer = null;
		synchronized (this)
		{
			lineShares.clear();
			lastText = null;
			lastShare = null;
		}
	}

	/** A chat message that starts with {@code !wmp}. */
	void onCommand(ChatMessage event, String message)
	{
		if (!config.shareCodesInChat() || !TYPES.contains(event.getType()))
		{
			return;
		}
		String sender = senderKey(event);
		String code = ShareCodec.codeIn(message);
		MessageNode node = event.getMessageNode();
		if (sender == null || code == null || node == null || node.getValue() == null)
		{
			return;
		}
		// The player's own codes are joined from their parts and never listed as shared; their lines show the description
		// too unless the player turned that off.
		boolean mine = sender.startsWith(MINE);
		Shown line = mine && !config.shareReplaceOwnLines() ? null : new Shown(node);
		ShareInbox.Reaction reaction = inbox.receive(code, event.getType().name() + "|" + sender, System.currentTimeMillis(),
			line, !mine);
		if (line == null)
		{
			return;
		}
		Share share = reaction.getShare();
		Sounds cues = sounds;
		if (share != null && !mine && cues != null)
		{
			cues.cue(Sounds.Cue.SHARE_COMPLETE);
		}
		String text = share != null ? ChatText.whole(share, namer)
			: reaction.isWaiting() ? ChatText.waiting(reaction) : ChatText.unreadable(reaction, code);
		show(line, text, share);
		for (Object earlier : reaction.getEarlierLines())
		{
			show((Shown) earlier, text, share);
		}
		client.refreshChat();
	}

	/**
	 * A chat line that shows a description of this plugin in place of its message. The description is the line's RuneLite
	 * format message, which the client draws again on every refresh, so it stays; the message itself is kept. A line
	 * whose format message something else has set since is left alone.
	 */
	private static final class Shown
	{
		private final MessageNode node;
		private String set;

		Shown(MessageNode node)
		{
			this.node = node;
		}
	}

	private void show(Shown line, String text, @Nullable Share share)
	{
		String current = line.node.getRuneLiteFormatMessage();
		if (current != null && !current.equals(line.set))
		{
			return;
		}
		int id = line.node.getId();
		String format = markup(text) + (share == null ? "" : idTags(id));
		if (format.equals(current))
		{
			return;
		}
		line.node.setRuneLiteFormatMessage(format);
		line.set = format;
		synchronized (this)
		{
			lineShares.remove(id);
			if (share != null)
			{
				lineShares.put(id, share);
				Iterator<Integer> oldest = lineShares.keySet().iterator();
				while (lineShares.size() > MAX_LINES)
				{
					oldest.next();
					oldest.remove();
				}
			}
		}
	}

	/** The description with RuneLite's chat colours: the plugin's name highlighted, the rest in the usual colour. */
	static String markup(String text)
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(ChatText.HEAD)
			.append(ChatColorType.NORMAL)
			.append(text.substring(ChatText.HEAD.length()))
			.build();
	}

	/** Two colour tags with nothing after them, which draw nothing, carrying a message id. */
	static String idTags(int id)
	{
		return ID_HIGH + hex4(id >>> 16) + ">" + ID_LOW + hex4(id & 0xffff) + ">";
	}

	private static String hex4(int value)
	{
		String hex = Integer.toHexString(value | 0x10000);
		return hex.substring(1);
	}

	/** The message id the id tags at the end of a line's text carry, or -1 when there are none. */
	static long idIn(String text)
	{
		int at = text.lastIndexOf(ID_HIGH);
		int low = at + ID_HIGH.length() + 5;
		if (at < 0 || !text.startsWith(ID_LOW, low) || text.length() < low + ID_LOW.length() + 5
			|| text.charAt(at + ID_HIGH.length() + 4) != '>' || text.charAt(low + ID_LOW.length() + 4) != '>')
		{
			return -1;
		}
		int high = hexValue(text, at + ID_HIGH.length());
		int bottom = hexValue(text, low + ID_LOW.length());
		return high < 0 || bottom < 0 ? -1 : (long) high << 16 | bottom;
	}

	private static int hexValue(String text, int from)
	{
		int value = 0;
		for (int i = from; i < from + 4; i++)
		{
			int digit = Character.digit(text.charAt(i), 16);
			if (digit < 0)
			{
				return -1;
			}
			value = value << 4 | digit;
		}
		return value;
	}

	/** The start of the key of the player's own lines, which no player name can have. */
	private static final String MINE = "*";

	/**
	 * Who sent a line, as a key that is only compared: the sender's name in lower case, or for the player at this client
	 * {@link #MINE} (with the name of whoever a private message went to), or null when it cannot be told. The names are
	 * kept only inside that key, in memory, to keep the parts of one player's code apart from another's.
	 */
	@Nullable
	private String senderKey(ChatMessage event)
	{
		Player me = client.getLocalPlayer();
		String mine = me == null ? null : me.getName();
		String sender = event.getName();
		if (mine == null || sender == null)
		{
			return null;
		}
		String them = Text.toJagexName(Text.removeTags(sender)).toLowerCase();
		if (them.isEmpty())
		{
			return null;
		}
		// The echo of a private message this player sent names the player it went to.
		if (event.getType() == ChatMessageType.PRIVATECHATOUT)
		{
			return MINE + them;
		}
		return them.equals(Text.toJagexName(Text.removeTags(mine)).toLowerCase()) ? MINE : them;
	}

	/** Adds "Open in World Map Plus" to the menu of a chat line that holds a code, as Chat History adds its copy entry. */
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		Consumer<Share> open = opener;
		if (open == null || !config.shareCodesInChat() || !"Report".equals(event.getOption()))
		{
			return;
		}
		int component = event.getActionParam1();
		if (WidgetUtil.componentToInterface(component) != InterfaceID.CHATBOX)
		{
			return;
		}
		Widget line = client.getWidget(component);
		Widget lines = line == null ? null : line.getParent();
		if (lines == null || lines.getId() != InterfaceID.Chatbox.SCROLLAREA)
		{
			return;
		}
		// The static line widget holds the sender; the message is the second of the four dynamic children for that line.
		int index = (WidgetUtil.componentToId(component) - WidgetUtil.componentToId(InterfaceID.Chatbox.LINE0)) * 4 + 1;
		Widget text = index < 0 ? null : lines.getChild(index);
		Share share = text == null ? null : shareIn(text.getText());
		if (share == null)
		{
			return;
		}
		client.getMenu().createMenuEntry(1)
			.setOption(OPEN)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> open.accept(share));
	}

	/**
	 * The share a chat line's text stands for: the one decoded for that line when its text carries a message id, or else
	 * a whole code in the text (a line this plugin did not change), or a part of one whose parts have all arrived. Null
	 * for none.
	 */
	@Nullable
	synchronized Share shareIn(@Nullable String text)
	{
		if (text == null)
		{
			return null;
		}
		long id = idIn(text);
		if (id >= 0)
		{
			return lineShares.get((int) id);
		}
		if (text.equals(lastText))
		{
			return lastShare;
		}
		String code = ShareCodec.codeIn(text);
		Share share = null;
		if (code != null)
		{
			try
			{
				share = ShareCodec.isPart(code) ? inbox.shareOfPart(code) : ShareCodec.decode(code);
			}
			catch (ShareException e)
			{
				share = null;
			}
		}
		// A part whose code is not complete yet is not remembered, so the entry appears once the last part arrives.
		if (code == null || !ShareCodec.isPart(code) || share != null)
		{
			lastText = text;
			lastShare = share;
		}
		return share;
	}
}
