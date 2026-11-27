package com.worldmapplus.panel;

import static com.worldmapplus.theme.Palette.colour;

import com.worldmapplus.share.*;
import com.worldmapplus.typing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.annotation.*;
import javax.swing.*;
import javax.swing.text.*;

/**
 * The sharing boxes at the top of the side panel, built from the panel's own parts (cards, headers, labels and pills of
 * {@link PanelTheme}): the share box with the messages of a share to type, each with its state, Copy and Type it, and the
 * typing guide's Start or Stop; the import box for codes pasted from outside the game; and Shared with you, the shares other
 * players sent, each with Show, Go, Save and Dismiss. A box is made again when what it says changes. Interface thread only.
 */
final class ShareBoxes
{
	private final PlacesPanel panel;
	private final ShareInbox inbox;
	private final ShareImport importer;
	final JPanel shareBody = PlacesPanel.column();
	final JPanel importBody = PlacesPanel.column();
	final JPanel inboxBody = PlacesPanel.column();

	private final JTextArea importText = new JTextArea(new CappedDocument(ShareInbox.MAX_PASTE));
	private final JLabel importStatus = new JLabel();
	private ShareInbox.Pasted lastImport;

	/** The share whose messages are shown, its messages, and what the box said about the guide when it was made. */
	private Share sharing;
	private List<String> messages = new ArrayList<>();
	private String shownGuide = "";
	private int shownInbox = -1;
	/** The newest share already listed, so one that appears later can glow for a moment. */
	private long newestOffer = -1;

	private TypingGuide guide;
	private BooleanSupplier guideEnabled = () -> false;
	Consumer<Share> viewer = s -> { };
	Consumer<Share> goer = s -> { };

	ShareBoxes(PlacesPanel panel, ShareInbox inbox, ShareImport importer)
	{
		this.panel = panel;
		this.inbox = inbox;
		this.importer = importer;
		buildImport();
	}

	/** Lets the plugin hand over the typing guide and its setting. Without them the share box offers no guide. */
	void setTypingGuide(@Nullable TypingGuide guide, @Nullable BooleanSupplier enabled)
	{
		this.guide = guide;
		guideEnabled = enabled == null ? () -> false : enabled;
		shownGuide = "";
	}

	private boolean guideOn()
	{
		return guide != null && guideEnabled.getAsBoolean();
	}

	/** Makes what changed again, on the panel's timer. */
	void refresh()
	{
		if (inbox.getVersion() != shownInbox)
		{
			rebuildInbox();
		}
		if (sharing != null && !guideWords().equals(shownGuide))
		{
			rebuildShare();
		}
	}

	/** Makes the messages of a share, shows them and starts the typing guide on the first. */
	void show(Share share)
	{
		try
		{
			messages = ShareCodec.messages(share);
		}
		catch (ShareException e)
		{
			sharing = null;
			panel.setStatus(e.getMessage());
			rebuildShare();
			return;
		}
		sharing = share;
		if (guideOn())
		{
			guide.start(messages, 0);
		}
		panel.setStatus(guideOn() ? "The typing guide shows the code just above the chat's typing line. Type it and press Enter."
			: "Type the code into the game's chat yourself and send it. Players with World Map Plus can open it.");
		rebuildShare();
	}

	/** True when the guide runs on the messages of this box. */
	private boolean ours()
	{
		List<String> lower = new ArrayList<>();
		for (String message : messages)
		{
			lower.add(message.toLowerCase(Locale.ROOT));
		}
		return guide != null && guide.messages().equals(lower);
	}

	/** What the box says about the guide: its line and each message's state. Changes when the box is to be made again. */
	private String guideWords()
	{
		if (!guideOn())
		{
			return "";
		}
		boolean ours = ours();
		StringBuilder words = new StringBuilder();
		if (guide.isActive() && ours)
		{
			words.append(guide.count() > 1 ? "Guide on message " + (guide.current() + 1) + " of " + guide.count() : "Guide on the chat line")
				.append(": ").append(hintWords(guide.hint()));
		}
		else
		{
			words.append(ours && guide.isFinished() ? guide.count() > 1 ? "All " + guide.count() + " messages typed." : "Typed and sent."
				: "The guide shows the code above the chat line.");
		}
		for (int i = 0; i < messages.size(); i++)
		{
			TypingGuide.State state = ours ? guide.state(i) : TypingGuide.State.NOT_TYPED;
			words.append('|').append(guide.isActive() && ours && guide.current() == i && state == TypingGuide.State.NOT_TYPED
				? TypingGuide.State.TYPING : state);
		}
		return words.toString();
	}

	private static String hintWords(TypingGuide.Hint hint)
	{
		switch (hint)
		{
			case TYPO:
				return "typo";
			case TOO_LONG:
				return "too long";
			case MATCH:
				return "matches, press Enter";
			case GOING:
				return "right so far";
			default:
				return "type it";
		}
	}

	/** The share box: what is shared, the guide's line and button, each message with its state, Type it and Copy, and the copies. */
	void rebuildShare()
	{
		shareBody.removeAll();
		shownGuide = guideWords();
		if (sharing != null)
		{
			shareBody.add(PanelTheme.header("Share", messages.size() > 1 ? messages.size() : -1));
			shareBody.add(PlacesPanel.gap(2));
			PanelTheme.Card card = new PanelTheme.Card(null, colour("CARD"), colour("ACCENT"));
			card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
			String describe = sharing.describe();
			card.add(PlacesPanel.fill(PanelTheme.label(Character.toUpperCase(describe.charAt(0)) + describe.substring(1),
				PanelTheme.BODY_BOLD, colour("TEXT"))));
			JLabel check = PanelTheme.wrapped(shortSummary(sharing), PanelTheme.SMALL, colour("TEXT_DIM"), PlacesPanel.TEXT_WIDTH);
			check.setToolTipText(PlacesPanel.summary(sharing));
			card.add(PlacesPanel.fill(check));
			card.add(PlacesPanel.gap(6));
			String[] words = shownGuide.split("\\|");
			boolean guided = guideOn();
			if (guided)
			{
				boolean running = guide.isActive() && ours();
				card.add(PlacesPanel.fill(PanelTheme.label(words[0], PanelTheme.SMALL_BOLD, running && guide.hint() == TypingGuide.Hint.TYPO
					? colour("BAD") : !running && ours() && guide.isFinished() ? colour("GOOD") : colour("ACCENT"))));
				card.add(PlacesPanel.gap(4));
				card.add(PlacesPanel.fill(PlacesPanel.act(running ? PlacesPanel.small("Stop typing guide") : PanelTheme.primary("Start typing guide"),
					running ? "Take the guide off the chat line" : "Show the code just above the chat's typing line", e -> type(running ? -1 : 0))));
				card.add(PlacesPanel.gap(6));
			}
			for (int i = 0; i < messages.size(); i++)
			{
				card.add(messageBlock(i, guided ? words[i + 1] : null));
				card.add(PlacesPanel.gap(i == messages.size() - 1 ? 4 : 8));
			}
			card.add(PlacesPanel.fill(PanelTheme.wrapped(messages.size() > 1
				? "Type each message into the chat and press Enter, one after the other. They join in any order."
				: "Type the message into the chat and press Enter. RuneLite plugins cannot type in chat for you.",
				PanelTheme.SMALL, colour("TEXT_FAINT"), PlacesPanel.TEXT_WIDTH)));
			card.add(PlacesPanel.gap(6));
			JButton whole = PlacesPanel.small("Copy whole code", "Copy the whole code as one, to paste in Discord or anywhere. Import a code reads it",
				e -> copy(wholeCode(), "Copied the whole code. Import a code in the side panel reads it."));
			JButton all = PlacesPanel.small("Copy all parts", "Copy every message, one a line, each with " + ShareCodec.COMMAND
				+ ", to type into chat one by one", e -> copy(String.join("\n", messages), "Copied all " + messages.size() + " parts, one a line."));
			card.add(PlacesPanel.fill(messages.size() > 1 ? PlacesPanel.buttonGrid(2, whole, all) : PlacesPanel.buttonGrid(1, whole)));
			card.add(PlacesPanel.gap(6));
			JButton close = PlacesPanel.small("Close", "Close this box, and stop the typing guide if it runs", e ->
			{
				if (guide != null && ours())
				{
					guide.stop();
				}
				sharing = null;
				rebuildShare();
			});
			JPanel closeRow = PanelTheme.clear(new BorderLayout());
			closeRow.add(close, BorderLayout.EAST);
			card.add(PlacesPanel.fill(closeRow));
			shareBody.add(card);
			shareBody.add(PlacesPanel.gap(8));
		}
		panel.relayout();
	}

	/** Starts or moves the guide to a message, or stops it for -1. */
	private void type(int index)
	{
		if (index < 0)
		{
			guide.stop();
		}
		else if (!guide.isActive() || !ours() || !guide.jumpTo(index))
		{
			guide.start(messages, index);
		}
		rebuildShare();
	}

	private void copy(@Nullable String text, String done)
	{
		panel.setStatus(text == null ? "There is no code to copy." : ShareDialog.copy(text) ? done
			: "The clipboard cannot be used on this computer.");
	}

	/** One message: its number, length and state, the code in groups of four, and its Type it and Copy buttons. */
	private JComponent messageBlock(int index, @Nullable String state)
	{
		String message = messages.get(index);
		JPanel block = PlacesPanel.column();
		JPanel head = PanelTheme.clear(new BorderLayout(4, 0));
		head.add(PanelTheme.label(messages.size() > 1 ? "Message " + (index + 1) + " of " + messages.size() : "Message",
			PanelTheme.SMALL_BOLD, colour("TEXT_DIM")), BorderLayout.WEST);
		head.add(PanelTheme.label(message.length() + " chars" + (state == null ? "" : ", " + state), PanelTheme.SMALL,
			"done".equals(state) ? colour("GOOD") : "typing".equals(state) ? colour("ACCENT") : colour("TEXT_FAINT")), BorderLayout.EAST);
		block.add(PlacesPanel.fill(head));
		block.add(PlacesPanel.gap(3));
		block.add(PlacesPanel.fill(codeCard(message)));
		block.add(PlacesPanel.gap(4));
		JButton copy = PlacesPanel.small("Copy", "Copy this message exactly, for use outside the game", e -> copy(message, "Copied."));
		if (state == null)
		{
			JPanel row = PanelTheme.clear(new BorderLayout());
			row.add(copy, BorderLayout.EAST);
			block.add(PlacesPanel.fill(row));
			return block;
		}
		block.add(PlacesPanel.fill(PlacesPanel.buttonGrid(2, PlacesPanel.small("Type it", "Put the typing guide on this message", e -> type(index)), copy)));
		return block;
	}

	/**
	 * A message in fixed width letters on a field coloured card: the command dimmed, then the code in groups of four that
	 * wrap at group edges, digits in the accent colour so they stand apart from letters. The tooltip is the exact message.
	 */
	static JComponent codeCard(String message)
	{
		int space = Math.max(0, message.indexOf(' '));
		StringBuilder html = new StringBuilder("<html><body style='width:" + (PlacesPanel.TEXT_WIDTH - 10) + "px'><font color='")
			.append(hex(colour("TEXT_DIM"))).append("'>").append(PanelTheme.escape(message.substring(0, space))).append("</font>");
		String digit = hex(colour("ACCENT"));
		for (int i = space; i < message.length(); i++)
		{
			char c = message.charAt(i);
			if (c == ' ')
			{
				continue;
			}
			html.append((i - space - 1) % 4 == 0 ? " " : "")
				.append(Character.isDigit(c) ? "<font color='" + digit + "'>" + c + "</font>" : PanelTheme.escape(String.valueOf(c)));
		}
		JLabel code = new JLabel(html.append("</body></html>").toString());
		code.setFont(PanelTheme.CODE);
		code.setForeground(colour("TEXT"));
		code.setToolTipText(message);
		PanelTheme.Card card = new PanelTheme.Card(new BorderLayout(), colour("FIELD"), null);
		card.add(code);
		return card;
	}

	private static String hex(Color colour)
	{
		return String.format("#%06x", colour.getRGB() & 0xffffff);
	}

	/** The summary cut to its first few points, for the share box; the whole of it is the tooltip. */
	static String shortSummary(Share share)
	{
		List<SharePoint> points = share.getPoints();
		if (share.getKind() == Share.Kind.PIN || points.size() <= 4)
		{
			return PlacesPanel.summary(share);
		}
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 3; i++)
		{
			text.append(i == 0 ? "" : "; ").append(i + 1).append(". ").append(points.get(i).describe());
		}
		return text.append("; and ").append(points.size() - 3).append(" more").toString();
	}

	/** The share being shown as one code with the command, however long, or null. */
	String wholeCode()
	{
		try
		{
			return sharing == null ? null : ShareCodec.COMMAND + " " + ShareCodec.encode(sharing);
		}
		catch (ShareException e)
		{
			return null;
		}
	}

	/** The messages of the share being shown, for tests. */
	List<String> shownMessages()
	{
		return sharing == null ? new ArrayList<>() : messages;
	}

	/** The import box: a text box to paste codes into, Import, Import clipboard, Close, and what came of it. Made once. */
	private void buildImport()
	{
		importText.setLineWrap(true);
		importText.setRows(4);
		importText.setFont(PanelTheme.SMALL);
		importText.setForeground(colour("TEXT"));
		importText.setBackground(colour("FIELD"));
		importText.setCaretColor(colour("ACCENT"));
		importText.setBorder(PanelTheme.pad(4, 5, 4, 5));
		importText.setToolTipText("Codes are found among other words: a whole code, a long code in one piece, or its parts");
		JScrollPane scroll = new JScrollPane(importText, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(colour("EDGE")));
		scroll.setPreferredSize(new Dimension(0, 70));
		importStatus.setFont(PanelTheme.SMALL);
		JButton now = PlacesPanel.act(PanelTheme.primary("Import"), "Read the codes in the box into Shared with you. Nothing is saved until you press Save", e ->
		{
			if (importFrom(importText.getText()))
			{
				importText.setText("");
			}
		});
		JButton clipboard = PlacesPanel.small("Import clipboard", "Read the codes on the clipboard into Shared with you", e ->
		{
			String text = ShareDialog.read();
			importFrom(text == null ? "" : text);
		});
		JButton close = PlacesPanel.small("Close", null, e -> showImport(false));
		PanelTheme.Card card = new PanelTheme.Card(null, colour("CARD"), colour("ACCENT"));
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		JPanel title = PanelTheme.clear(new BorderLayout(4, 0));
		title.add(PanelTheme.label("Import a code", PanelTheme.BODY_BOLD, colour("TEXT")), BorderLayout.WEST);
		title.add(close, BorderLayout.EAST);
		card.add(PlacesPanel.fill(title));
		card.add(PlacesPanel.fill(PanelTheme.wrapped("Paste the lines a Copy button gives, or type a " + ShareCodec.COMMAND
			+ " line. The parts of a long code can come in any order.", PanelTheme.SMALL, colour("TEXT_FAINT"), PlacesPanel.TEXT_WIDTH)));
		card.add(PlacesPanel.gap(5));
		card.add(PlacesPanel.fill(scroll));
		card.add(PlacesPanel.gap(6));
		card.add(PlacesPanel.fill(PlacesPanel.buttonGrid(2, now, clipboard)));
		// The status line changes height with its text, so its holder is not held to the height it had when made.
		JPanel said = PanelTheme.clear(new BorderLayout());
		said.setBorder(PanelTheme.pad(5, 0, 0, 0));
		said.add(importStatus);
		said.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(said);
		importBody.add(PanelTheme.header("Import", -1));
		importBody.add(PlacesPanel.gap(2));
		importBody.add(card);
		importBody.add(PlacesPanel.gap(8));
		importBody.setVisible(false);
	}

	/** Reads codes into Shared with you, nothing saved, and says what came of it. True when nothing failed to read. */
	private boolean importFrom(String text)
	{
		lastImport = inbox.importPasted(text, System.currentTimeMillis());
		importStatus.setText("<html><body style='width:" + PlacesPanel.TEXT_WIDTH + "px'>" + PanelTheme.escape(lastImport.getMessage())
			+ "</body></html>");
		importStatus.setForeground(lastImport.isProblem() ? colour("BAD") : lastImport.getAdded() > 0 ? colour("GOOD") : colour("ACCENT"));
		showImport(true);
		rebuildInbox();
		return !lastImport.isProblem();
	}

	/** Opens or closes the import box. */
	void showImport(boolean open)
	{
		importBody.setVisible(open);
		panel.relayout();
		if (open)
		{
			importText.requestFocusInWindow();
		}
	}

	/** A document that holds at most a set number of characters, so a huge paste is cut at once. */
	static final class CappedDocument extends PlainDocument
	{
		private final int max;

		CappedDocument(int max)
		{
			this.max = max;
		}

		@Override
		public void insertString(int offset, String text, AttributeSet attributes)
			throws BadLocationException
		{
			int room = max - getLength();
			if (text != null && room > 0)
			{
				super.insertString(offset, text.length() > room ? text.substring(0, room) : text, attributes);
			}
		}
	}

	/** Puts text in the import box and presses Import, for tests. */
	ShareInbox.Pasted importForTest(String text)
	{
		importText.setText(text);
		if (importFrom(text))
		{
			importText.setText("");
		}
		return lastImport;
	}

	String importBoxText()
	{
		return importText.getText();
	}

	String importStatusText()
	{
		return importStatus.getText();
	}

	/** Called when the player chose Open in World Map Plus on a chat line: the share is listed and marked. */
	void showOpenedShare()
	{
		rebuildInbox();
		for (ShareInbox.Offer offer : inbox.offers())
		{
			if (offer.getId() == inbox.getOpened())
			{
				panel.setStatus("Opened the shared " + offer.getShare().describe() + ". It is marked on the map. Save keeps it.");
			}
		}
	}

	/** Shared with you: the shares other players sent, the newest first. */
	void rebuildInbox()
	{
		shownInbox = inbox.getVersion();
		inboxBody.removeAll();
		List<ShareInbox.Offer> offers = inbox.offers();
		long flashAfter = newestOffer;
		if (!offers.isEmpty())
		{
			inboxBody.add(PanelTheme.header("Shared with you", offers.size()));
			inboxBody.add(PlacesPanel.gap(2));
		}
		for (int i = offers.size() - 1; i >= 0; i--)
		{
			newestOffer = Math.max(newestOffer, offers.get(i).getId());
			inboxBody.add(offerRow(offers.get(i), flashAfter >= 0 && offers.get(i).getId() > flashAfter));
			inboxBody.add(PlacesPanel.gap(i == 0 ? 9 : 5));
		}
		panel.relayout();
	}

	private JPanel offerRow(ShareInbox.Offer offer, boolean fresh)
	{
		boolean opened = offer.getId() == inbox.getOpened();
		FadeRow row = PlacesPanel.newRow(opened ? colour("CARD_ACTIVE") : colour("CARD"), colour("ACCENT"), opened || fresh);
		Share share = offer.getShare();
		String describe = share.describe();
		boolean route = share.getKind() == Share.Kind.ROUTE;
		JLabel title = PanelTheme.label(Character.toUpperCase(describe.charAt(0)) + describe.substring(1), PanelTheme.BODY_BOLD,
			colour("TEXT"));
		title.setToolTipText(PlacesPanel.summary(share));
		JLabel where = PanelTheme.label(share.getKind() == Share.Kind.PIN ? "Region " + share.getPoints().get(0).regionId()
			: "Starts at " + share.getPoints().get(0).describe(), PanelTheme.SMALL, colour("TEXT_DIM"));
		JButton show = PlacesPanel.small("Show", "Mark it on the map and centre the map there. Nothing is saved", e ->
		{
			viewer.accept(share);
			panel.setStatus("The shared " + describe + " is marked on the map.");
		});
		JButton go = PlacesPanel.small("Go", "Ask Shortest Path for the way " + (route ? "to the first stop" : "there") + ", with your own settings", e ->
		{
			if (panel.shortestPathReady())
			{
				goer.accept(share);
				panel.setStatus("Asked Shortest Path for the way to " + share.getPoints().get(0).describe() + ".");
			}
		});
		JButton save = PlacesPanel.act(PanelTheme.primary("Save"), route ? "Save it as a route, stops in the same order. Places you have are used again"
			: "Save it to your places with your usual look. Places you have are not saved twice", e -> save(offer));
		JButton dismiss = PlacesPanel.small("Dismiss", null, e -> forget(offer));
		JPanel top = PanelTheme.clear(new BorderLayout(0, 1));
		top.add(title, BorderLayout.NORTH);
		top.add(where, BorderLayout.SOUTH);
		row.add(top, BorderLayout.NORTH);
		row.add(PlacesPanel.buttonGrid(4, show, go, save, dismiss), BorderLayout.SOUTH);
		PlacesPanel.lightOnHover(row);
		return row;
	}

	private void forget(ShareInbox.Offer offer)
	{
		inbox.remove(offer.getId());
		panel.external.clearShare();
		rebuildInbox();
	}

	private void save(ShareInbox.Offer offer)
	{
		if (importer == null)
		{
			panel.setStatus("Saving is not available here.");
			return;
		}
		Share share = offer.getShare();
		String message;
		if (share.getKind() == Share.Kind.ROUTE)
		{
			Object name = JOptionPane.showInputDialog(panel, "Name of the route", "Save shared route", JOptionPane.PLAIN_MESSAGE,
				null, null, "Shared route");
			if (name == null)
			{
				return;
			}
			message = importer.saveRoute(share, name.toString());
		}
		else
		{
			message = importer.savePlaces(share);
		}
		panel.setStatus(message);
		if (message.startsWith("Saved"))
		{
			forget(offer);
		}
	}
}
