package com.worldmapplus.panel;

import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.pins.Pin;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteNavigator;
import com.worldmapplus.pins.RouteRunner;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.pins.SavedRoute;
import com.worldmapplus.share.PasteReader;
import com.worldmapplus.share.Share;
import com.worldmapplus.share.ShareBuilder;
import com.worldmapplus.share.ShareCodec;
import com.worldmapplus.share.ShareException;
import com.worldmapplus.share.ShareImport;
import com.worldmapplus.share.ShareInbox;
import com.worldmapplus.typing.TypingGuide;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * The side panel: the player's saved places and routes, with a filter, a type filter and a sort for each,
 * the way Inventory Setups lists its setups, the shares other players sent, and the box that shows a share's
 * messages for the player to type (with the typing guide).
 * <p>
 * Everything here is the plugin's own list. Go asks Shortest Path for a route, Show brings the map to the
 * place, and routes are walked leg by leg by {@link RouteRunner}. The panel has no controls that act in the
 * game. Messages to Shortest Path are posted on the client thread. Every page is drawn with {@link PanelTheme}.
 */
public class PlacesPanel extends PluginPanel
{
	private static final long REFRESH_MILLIS = 400;

	/** The width text wraps to inside a card. */
	private static final int TEXT_WIDTH = 140;

	/** The width text wraps to across the page. Swing's html widths come out wider than asked, so these are below the room. */
	private static final int PAGE_TEXT_WIDTH = 160;

	private final PinStore pins;
	private final RouteStore routes;
	private final RouteRunner runner;
	private final MapActions actions;
	private final ExternalOverlays external;
	private final ClientThread clientThread;
	private final IntFunction<String> areaName;
	private final ShareInbox inbox;
	private final ShareImport importer;
	private final JPanel inboxBody = column();
	private int shownInbox = -1;

	/** The newest saved place and shared set already listed, so one that appears later can glow for a moment. */
	private long newestPin = -1;
	private long flashAfterPin = -1;
	private long newestOffer = -1;
	private long flashAfterOffer = -1;

	private final JLabel status = new JLabel();
	private final JPanel placesBody = column();
	private final JPanel routesBody = column();
	private final PanelTheme.Segments tabs = new PanelTheme.Segments("Places", "Routes");

	private final IconTextField placeFilter = searchField("Filter by name, type or area");
	private final JComboBox<String> typeBox = new JComboBox<>();
	private final JComboBox<PlaceList.Sort> placeSort = new JComboBox<>(PlaceList.Sort.values());
	private final PanelTheme.Pill makeRoute = PanelTheme.pill("New route from ticked places");
	private final PanelTheme.Pill sharePlaces = PanelTheme.pill("Share ticked");
	private final PanelTheme.Pill importCode = PanelTheme.pill("Import a code");
	/** The import box (round 52): a text box to paste codes into, Import, Import clipboard, and what came of it. */
	private final JPanel importBody = column();
	private final javax.swing.JTextArea importText = new HintArea(
		"Paste a code here, from Discord or anywhere. With or without !wmp; the parts of a long code in any order.");
	private final JLabel importStatus = new JLabel();
	private final PanelTheme.Pill importNow = PanelTheme.primary("Import");
	private final PanelTheme.Pill importClipboard = PanelTheme.pill("Import clipboard");
	private final PanelTheme.Pill importClose = PanelTheme.pill("Close");
	private ShareInbox.Pasted lastImport;
	private final JPanel shareBody = column();

	/** The share whose messages are shown for the player to type, or null. */
	private Share sharing;

	/** The messages of the share being shown, and what shows each one's state and progress, for updates in place. */
	private List<String> sharingMessages = new ArrayList<>();
	private final List<JLabel> messageStates = new ArrayList<>();
	private final List<CodeView> messageCodes = new ArrayList<>();
	private JLabel guideLine;
	private boolean guideShownActive;

	/** The client property of a message's state label that holds the message's length. */
	private static final String LENGTH = "wmpLength";

	/** The typing guide, and whether its setting is on. Without one the share box has no guide controls. */
	private TypingGuide guide;
	private BooleanSupplier guideEnabled = () -> false;
	private int shownGuide = -1;
	private final TypingGuide.View guideView = new TypingGuide.View();
	private final byte[] messageMarks = new byte[TypingGuide.MAX_MESSAGE];

	/** Shows a share on the map, and asks Shortest Path for the way to its first point. Set by the plugin. */
	private java.util.function.Consumer<Share> shareViewer = s -> { };
	private java.util.function.Consumer<Share> shareGoer = s -> { };

	private final IconTextField routeFilter = searchField("Filter by route name or by the name of a stop");
	private final JComboBox<RouteList.Sort> routeSort = new JComboBox<>(RouteList.Sort.values());
	private final JCheckBox loopsOnly = PanelTheme.style(new JCheckBox("Loops only"), PanelTheme.SMALL, PanelTheme.TEXT_DIM);
	private final PanelTheme.Pill newRoute = PanelTheme.pill("New route");

	/** How many stops a route card lists before "+ more". */
	static final int FOLDED_STOPS = 3;

	/** The routes whose whole stop list is shown, by id, while the panel is open. */
	private final Set<Long> unfolded = new java.util.HashSet<>();

	/** The places ticked for a new route, in the order they were ticked. */
	private final Set<Long> ticked = new LinkedHashSet<>();

	/** Works out the fastest order of a route, or null when there is none (tests, previews). */
	private com.worldmapplus.pins.RouteOrderer orderer;

	/** The last new order given to a route, so it can be undone while the route still has it. */
	private com.worldmapplus.pins.RouteOrderer.Result lastOrder;

	private final Timer refresher;
	private int shownPins = -1;
	private int shownRoutes = -1;
	private boolean updating;

	public PlacesPanel(PinStore pins, RouteStore routes, RouteRunner runner, MapActions actions,
		ExternalOverlays external, ClientThread clientThread, IntFunction<String> areaName)
	{
		this(pins, routes, runner, actions, external, clientThread, areaName, new ShareInbox(), null);
	}

	public PlacesPanel(PinStore pins, RouteStore routes, RouteRunner runner, MapActions actions,
		ExternalOverlays external, ClientThread clientThread, IntFunction<String> areaName, ShareInbox inbox,
		ShareImport importer)
	{
		super(true);
		this.inbox = inbox;
		this.importer = importer;
		this.pins = pins;
		this.routes = routes;
		this.runner = runner;
		this.actions = actions;
		this.external = external;
		this.clientThread = clientThread;
		this.areaName = areaName;

		setLayout(new BorderLayout(0, PanelTheme.GAP));
		setBackground(PanelTheme.PAGE);
		setBorder(PanelTheme.pad(8, 6, 8, 6));

		status.setFont(PanelTheme.SMALL);
		status.setForeground(PanelTheme.TEXT_DIM);
		status.setVisible(false);

		JPanel places = PanelTheme.clear(new BorderLayout(0, PanelTheme.GAP));
		places.add(placeControls(), BorderLayout.NORTH);
		places.add(placesBody, BorderLayout.CENTER);

		JPanel routePage = PanelTheme.clear(new BorderLayout(0, PanelTheme.GAP));
		routePage.add(routeControls(), BorderLayout.NORTH);
		routePage.add(routesBody, BorderLayout.CENTER);

		CardLayout pages = new ShownPageLayout();
		JPanel display = PanelTheme.clear(pages);
		display.add(places, "places");
		display.add(routePage, "routes");
		tabs.onChoose(i ->
		{
			pages.show(display, i == 0 ? "places" : "routes");
			// The page area is as tall as the page shown, so the panel lays itself out again.
			revalidate();
			repaint();
		});

		JPanel header = column();
		header.add(brand());
		header.add(gap(PanelTheme.GAP));
		header.add(status);
		header.add(shareBody);
		header.add(importBody);
		header.add(inboxBody);
		buildImport();
		header.add(tabs);
		add(header, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		wireControls();
		refresher = new Timer((int) REFRESH_MILLIS, e -> refreshIfChanged());
		refresher.setRepeats(true);
		rebuildAll();
	}

	/** The toolbar icon. */
	public static java.awt.image.BufferedImage icon()
	{
		return PanelIcon.create();
	}

	// ------------------------------------------------------------------
	// Layout
	// ------------------------------------------------------------------

	/**
	 * Pages on top of each other, as tall as the page being shown. A plain card layout is as tall as its tallest page, so
	 * a short Routes page was stretched to the height of a long Places page and the extra height went into its cards.
	 */
	static final class ShownPageLayout extends CardLayout
	{
		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			synchronized (parent.getTreeLock())
			{
				java.awt.Insets insets = parent.getInsets();
				for (Component page : parent.getComponents())
				{
					if (page.isVisible())
					{
						Dimension size = page.getPreferredSize();
						return new Dimension(size.width + insets.left + insets.right, size.height + insets.top + insets.bottom);
					}
				}
				return super.preferredLayoutSize(parent);
			}
		}
	}

	/** A transparent panel that stacks its pieces top to bottom. */
	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/** The panel's title: the plugin's name in the map's gold, with the toolbar icon. */
	private static JComponent brand()
	{
		JLabel title = PanelTheme.label("World Map Plus", PanelTheme.TITLE, PanelTheme.ACCENT);
		title.setIcon(new javax.swing.ImageIcon(PanelIcon.create()));
		title.setIconTextGap(6);
		JLabel sub = PanelTheme.label("Places, routes and shares", PanelTheme.SMALL, PanelTheme.TEXT_FAINT);
		JPanel box = PanelTheme.clear(new BorderLayout(0, 1));
		box.add(title, BorderLayout.NORTH);
		box.add(sub, BorderLayout.SOUTH);
		box.setBorder(PanelTheme.pad(0, 2, 0, 0));
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		return box;
	}

	private JComponent placeControls()
	{
		placeFilter.setToolTipText("Filter by name, type or area");
		typeBox.setToolTipText("Show one type of place");
		placeSort.setToolTipText("Sort the places");
		PanelTheme.style(typeBox);
		PanelTheme.style(placeSort);
		PanelTheme.Card box = new PanelTheme.Card(null, PanelTheme.CARD, null);
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.add(fill(placeFilter));
		box.add(gap(5));
		JPanel pick = PanelTheme.clear(new GridLayout(1, 2, 5, 0));
		pick.add(captioned("Type", typeBox));
		pick.add(captioned("Sort", placeSort));
		box.add(fill(pick));
		box.add(gap(7));
		makeRoute.setToolTipText("Make a route of the ticked places, in the order they were ticked");
		box.add(fill(makeRoute));
		box.add(gap(5));
		sharePlaces.setToolTipText("Make a !wmp code for the ticked places (one place makes a pin code) to type into chat");
		importCode.setToolTipText("Paste a code someone sent you outside the game. Codes that arrive in chat are found without this");
		JPanel shareRow = PanelTheme.clear(new GridLayout(1, 2, 5, 0));
		shareRow.add(sharePlaces);
		shareRow.add(importCode);
		box.add(fill(shareRow));
		return box;
	}

	private JComponent routeControls()
	{
		routeFilter.setToolTipText("Filter by route name or by the name of a stop");
		routeSort.setToolTipText("Sort the routes");
		PanelTheme.style(routeSort);
		PanelTheme.Card box = new PanelTheme.Card(null, PanelTheme.CARD, null);
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.add(fill(routeFilter));
		box.add(gap(5));
		box.add(fill(captioned("Sort", routeSort)));
		box.add(gap(7));
		JPanel row = PanelTheme.clear(new BorderLayout(6, 0));
		row.add(loopsOnly, BorderLayout.WEST);
		row.add(newRoute, BorderLayout.CENTER);
		box.add(fill(row));
		return box;
	}

	/** A field with a small caption above it. */
	private static JComponent captioned(String caption, Component field)
	{
		JPanel box = PanelTheme.clear(new BorderLayout(0, 2));
		box.add(PanelTheme.label(caption, PanelTheme.SMALL, PanelTheme.TEXT_FAINT), BorderLayout.NORTH);
		box.add(field, BorderLayout.CENTER);
		return box;
	}

	/** Lets a piece take the whole width of a column. */
	private static JComponent fill(Component piece)
	{
		JPanel holder = PanelTheme.clear(new BorderLayout());
		holder.add(piece, BorderLayout.CENTER);
		holder.setAlignmentX(Component.LEFT_ALIGNMENT);
		holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, holder.getPreferredSize().height));
		return holder;
	}

	/** The colour of a pin that has not been given one, the same as on the map. */
	private static final Color DEFAULT_PIN = new Color(230, 60, 140);
	private static final Color HIDDEN_PIN = new Color(96, 90, 80);

	private static IconTextField searchField(String tooltip)
	{
		IconTextField field = new IconTextField();
		field.setIcon(IconTextField.Icon.SEARCH);
		field.setPreferredSize(new Dimension(0, 28));
		field.setBackground(PanelTheme.FIELD);
		field.setHoverBackgroundColor(PanelTheme.CARD_HOVER);
		field.setToolTipText(tooltip);
		return field;
	}

	/** A row card with an accent bar of colour down its left edge, that warms under the pointer and glows when it is new. */
	private static FadeRow newRow(Color idle, Color bar, boolean fresh)
	{
		FadeRow row = new FadeRow(new BorderLayout(0, 4), idle, PanelTheme.CARD_HOVER, PanelTheme.ACCENT_DEEP, bar);
		if (fresh)
		{
			row.flash();
		}
		return row;
	}

	/** Warms a row while the pointer is anywhere over it or what it holds. */
	private static void lightOnHover(FadeRow row)
	{
		MouseAdapter listener = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.hover(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				java.awt.Point inRow = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row);
				if (!row.contains(inRow))
				{
					row.hover(false);
				}
			}
		};
		attach(row, listener);
	}

	/** A small pill button, for the rows. */
	private static PanelTheme.Pill small(String text)
	{
		return PanelTheme.pill(text);
	}

	/** Buttons in equal cells, so the rows of a list line up. */
	private static JPanel buttonGrid(int columns, JComponent... buttons)
	{
		JPanel grid = PanelTheme.clear(new GridLayout(0, columns, 4, 4));
		for (JComponent button : buttons)
		{
			grid.add(button);
		}
		return grid;
	}

	private void wireControls()
	{
		DocumentListener typing = new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuildAll();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuildAll();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuildAll();
			}
		};
		placeFilter.getDocument().addDocumentListener(typing);
		routeFilter.getDocument().addDocumentListener(typing);
		typeBox.addActionListener(e -> rebuildAll());
		placeSort.addActionListener(e -> rebuildAll());
		routeSort.addActionListener(e -> rebuildAll());
		loopsOnly.addActionListener(e -> rebuildAll());
		makeRoute.addActionListener(e -> routeFromTicked());
		sharePlaces.addActionListener(e -> shareTicked());
		importCode.addActionListener(e -> showImport(!importBody.isVisible()));
		importNow.addActionListener(e -> importTyped());
		importClipboard.addActionListener(e -> importPasted());
		importClose.addActionListener(e -> showImport(false));
		newRoute.addActionListener(e -> RouteEditor.show(this, pins, routes, null, new ArrayList<>()));
	}

	// ------------------------------------------------------------------
	// Life
	// ------------------------------------------------------------------

	@Override
	public void onActivate()
	{
		refresher.start();
		rebuildAll();
	}

	@Override
	public void onDeactivate()
	{
		refresher.stop();
		routes.setHighlighted(0);
		// Unfolded stop lists are kept while the panel is open and folded again the next time it opens.
		unfolded.clear();
	}

	/** Stops the timer for good, when the plugin shuts down. */
	public void shutDown()
	{
		refresher.stop();
		routes.setHighlighted(0);
	}

	/**
	 * A label that shows its text as it is, even when it starts with an html tag. The setting has to be in
	 * place before the text, because Swing decides how to draw the text when it is set.
	 */
	static JLabel plainLabel(String text)
	{
		JLabel label = new JLabel();
		label.putClientProperty("html.disable", Boolean.TRUE);
		label.setText(text);
		return label;
	}

	/** A tick box that shows its text as it is. See {@link #plainLabel}. */
	static JCheckBox plainCheck(String text, boolean selected)
	{
		JCheckBox box = new JCheckBox();
		box.putClientProperty("html.disable", Boolean.TRUE);
		box.setText(text);
		box.setSelected(selected);
		return box;
	}

	private void refreshIfChanged()
	{
		String problem = runner == null ? null : runner.takeProblem();
		if (problem != null)
		{
			setStatus(problem);
		}
		if (inbox.getVersion() != shownInbox)
		{
			rebuildInbox();
		}
		if (pins.getVersion() != shownPins || routes.getVersion() != shownRoutes)
		{
			rebuildAll();
		}
		if (guide != null && guide.version() != shownGuide)
		{
			showGuideProgress();
		}
	}

	/** Opens the rename box for a saved place, for the map's Rename item. */
	public void editPin(long id)
	{
		SwingUtilities.invokeLater(() ->
		{
			Pin pin = pins.get(id);
			if (pin != null)
			{
				rename(pin);
			}
		});
	}

	// ------------------------------------------------------------------
	// Building the lists
	// ------------------------------------------------------------------

	private void rebuildAll()
	{
		if (updating)
		{
			return;
		}
		updating = true;
		try
		{
			shownPins = pins.getVersion();
			shownRoutes = routes.getVersion();
			List<Pin> all = pins.all();
			ticked.removeIf(id -> pins.get(id) == null);
			rebuildTypeBox(all);
			rebuildPlaces(all);
			rebuildRoutes();
			updateTickedButtons();
			tabs.setCount(0, all.size());
			tabs.setCount(1, routes.all().size());
			rebuildInbox();
		}
		finally
		{
			updating = false;
		}
		revalidate();
		repaint();
	}

	private void updateTickedButtons()
	{
		makeRoute.setEnabled(!ticked.isEmpty());
		makeRoute.setText(ticked.isEmpty() ? "New route from ticked places" : "New route from " + ticked.size() + " ticked");
		sharePlaces.setEnabled(!ticked.isEmpty());
		sharePlaces.setText(ticked.isEmpty() ? "Share ticked" : "Share " + ticked.size() + " ticked");
	}

	private void rebuildTypeBox(List<Pin> all)
	{
		List<String> wanted = new ArrayList<>();
		wanted.add(PlaceList.ALL_TYPES);
		wanted.addAll(PlaceList.typesOf(all));
		boolean same = typeBox.getItemCount() == wanted.size();
		for (int i = 0; same && i < wanted.size(); i++)
		{
			same = wanted.get(i).equals(typeBox.getItemAt(i));
		}
		if (same)
		{
			return;
		}
		Object chosen = typeBox.getSelectedItem();
		typeBox.removeAllItems();
		for (String type : wanted)
		{
			typeBox.addItem(type);
		}
		typeBox.setSelectedItem(wanted.contains(chosen) ? chosen : PlaceList.ALL_TYPES);
	}

	private void rebuildPlaces(List<Pin> all)
	{
		placesBody.removeAll();
		flashAfterPin = newestPin;
		for (Pin each : all)
		{
			newestPin = Math.max(newestPin, each.getId());
		}
		List<Pin> shown = PlaceList.apply(all, placeFilter.getText(), (String) typeBox.getSelectedItem(),
			(PlaceList.Sort) placeSort.getSelectedItem(), areaName);
		placesBody.add(PanelTheme.header("Saved places", shown.size()));
		placesBody.add(gap(2));
		if (shown.isEmpty())
		{
			placesBody.add(note(all.isEmpty()
				? "Nothing saved yet. Right-click the map and choose Add pin here, or Save place on a shop, a person or a search result."
				: "No saved place matches."));
		}
		for (Pin pin : shown)
		{
			placesBody.add(placeRow(pin));
			placesBody.add(gap(5));
		}
	}

	private void rebuildRoutes()
	{
		routesBody.removeAll();
		List<SavedRoute> shown = RouteList.apply(routes.all(), routeFilter.getText(), loopsOnly.isSelected(),
			(RouteList.Sort) routeSort.getSelectedItem(), this::stopName);
		routesBody.add(PanelTheme.header("Saved routes", shown.size()));
		routesBody.add(gap(2));
		if (shown.isEmpty())
		{
			routesBody.add(note(routes.all().isEmpty()
				? "No routes yet. Tick some places and choose New route, or press New route."
				: "No route matches."));
		}
		SavedRoute following = routes.active();
		for (SavedRoute route : shown)
		{
			routesBody.add(routeRow(route, following != null && following.getId() == route.getId()));
			routesBody.add(gap(5));
		}
	}

	private String stopName(long id)
	{
		Pin pin = pins.get(id);
		return pin == null ? "" : pin.getName();
	}

	private static Component gap(int height)
	{
		return javax.swing.Box.createRigidArea(new Dimension(0, height));
	}

	/** A short message in a quiet card, for an empty list. */
	private static JComponent note(String text)
	{
		PanelTheme.Card card = new PanelTheme.Card(new BorderLayout(), PanelTheme.CARD, null);
		card.add(PanelTheme.wrapped(text, PanelTheme.SMALL, PanelTheme.TEXT_DIM, TEXT_WIDTH), BorderLayout.CENTER);
		return card;
	}

	private static String escape(String text)
	{
		return PanelTheme.escape(text);
	}

	private JPanel placeRow(Pin pin)
	{
		Color bar = pin.isHidden() ? HIDDEN_PIN : pin.getColor() == 0 ? DEFAULT_PIN : new Color(pin.getColor());
		FadeRow row = newRow(PanelTheme.CARD, bar, flashAfterPin >= 0 && pin.getId() > flashAfterPin);

		JCheckBox pick = PanelTheme.style(plainCheck(pin.getName(), ticked.contains(pin.getId())), PanelTheme.BODY_BOLD,
			PanelTheme.TEXT);
		pick.setToolTipText(pin.getName());
		pick.addActionListener(e ->
		{
			if (pick.isSelected())
			{
				ticked.add(pin.getId());
			}
			else
			{
				ticked.remove(pin.getId());
			}
			updateTickedButtons();
		});

		JLabel detail = plainLabel(PlaceList.typeLabel(pin.getKind()) + "  ·  " + areaName.apply(pin.getMap())
			+ (pin.isHidden() ? "  ·  hidden" : ""));
		detail.setFont(PanelTheme.SMALL);
		detail.setForeground(PanelTheme.TEXT_DIM);
		detail.setBorder(PanelTheme.pad(0, 20, 0, 0));

		JButton go = small("Go");
		go.setToolTipText("Ask Shortest Path for a route here");
		go.addActionListener(e -> goTo(pin));
		JButton show = small("Show");
		show.setToolTipText("Centre the map here the next time it is open");
		show.addActionListener(e ->
		{
			external.focusOn(RouteNavigator.pointOf(pin), pin.getName());
			setStatus("The map will centre on " + pin.getName() + " when it is open.");
		});
		JButton rename = small("Edit");
		rename.setToolTipText("Change the name, the colour and the size, or hide it on the map");
		rename.addActionListener(e -> rename(pin));
		JButton delete = small("Delete");
		delete.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this, "Delete " + pin.getName() + "?", "Delete saved place",
				JOptionPane.YES_NO_OPTION);
			if (answer == JOptionPane.YES_OPTION)
			{
				pins.remove(pin.getId());
			}
		});

		JPanel top = PanelTheme.clear(new BorderLayout(0, 1));
		top.add(pick, BorderLayout.NORTH);
		top.add(detail, BorderLayout.SOUTH);
		row.add(top, BorderLayout.NORTH);
		row.add(buttonGrid(4, go, show, rename, delete), BorderLayout.SOUTH);
		lightOnHover(row);
		return row;
	}

	private JPanel routeRow(SavedRoute route, boolean following)
	{
		FadeRow row = newRow(following ? PanelTheme.CARD_ACTIVE : PanelTheme.CARD,
			following ? PanelTheme.GOOD : PanelTheme.ACCENT, false);

		JLabel name = PanelTheme.label(route.getName(), PanelTheme.BODY_BOLD, PanelTheme.TEXT);
		name.setToolTipText(route.getName());
		int count = route.getStops().size();
		JLabel detail = PanelTheme.label(count + (count == 1 ? " stop" : " stops") + (following ? "  ·  following" : ""),
			PanelTheme.SMALL, following ? PanelTheme.GOOD : PanelTheme.TEXT_DIM);
		JCheckBox loop = PanelTheme.style(new JCheckBox("Loop", route.isLoop()), PanelTheme.SMALL, PanelTheme.TEXT_DIM);
		loop.setToolTipText("Start again after the last stop");
		loop.addActionListener(e -> routes.update(route.getId(), route.getName(), route.getStops(), loop.isSelected()));

		JPanel facts = PanelTheme.clear(new BorderLayout());
		facts.add(detail, BorderLayout.WEST);
		facts.add(loop, BorderLayout.EAST);
		JPanel top = PanelTheme.clear(new BorderLayout(0, 2));
		top.add(name, BorderLayout.NORTH);
		top.add(facts, BorderLayout.SOUTH);

		List<JComponent> buttons = new ArrayList<>();
		JButton toggle = following ? small("Stop") : PanelTheme.primary("Start");
		toggle.setToolTipText(following ? "Stop following this route" : "Walk this route with Shortest Path, stop by stop");
		toggle.addActionListener(e -> toggle(route, following));
		buttons.add(toggle);
		if (following)
		{
			JButton skip = small("Skip");
			skip.setToolTipText("Move on to the next stop");
			skip.addActionListener(e -> clientThread.invokeLater(runner::skip));
			buttons.add(skip);
		}
		JButton edit = small("Edit");
		edit.addActionListener(e -> RouteEditor.show(this, pins, routes, route, new ArrayList<>()));
		buttons.add(edit);
		JButton share = small("Share");
		share.setToolTipText("Make a !wmp code for this route's stops, in order, to type into chat");
		share.addActionListener(e -> showCode(ShareBuilder.ofRoute(route, pins, this::autoMap)));
		buttons.add(share);
		if (orderer != null && count >= 3)
		{
			JButton fastest = small("Order");
			fastest.setToolTipText("Put the stops in the fastest order");
			fastest.addActionListener(e -> askOrder(route));
			buttons.add(fastest);
		}
		com.worldmapplus.pins.RouteOrderer.Result undoable = lastOrder;
		if (undoable != null && undoable.getRouteId() == route.getId() && route.getStops().equals(undoable.getAfter()))
		{
			JButton undo = small("Undo order");
			undo.setToolTipText("Put the stops back in the order they had before");
			undo.addActionListener(e ->
			{
				if (orderer.undo(undoable))
				{
					lastOrder = null;
					setStatus("The stops of " + route.getName() + " are back in their old order.");
				}
			});
			buttons.add(undo);
		}
		JButton delete = small("Delete");
		delete.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this, "Delete the route " + route.getName() + "?", "Delete route",
				JOptionPane.YES_NO_OPTION);
			if (answer == JOptionPane.YES_OPTION)
			{
				if (following)
				{
					clientThread.invokeLater(actions::clearRoute);
				}
				routes.remove(route.getId());
			}
		});
		buttons.add(delete);

		row.add(top, BorderLayout.NORTH);
		if (count > 0)
		{
			row.add(stopList(route), BorderLayout.CENTER);
		}
		row.add(buttonGrid(3, buttons.toArray(new JComponent[0])), BorderLayout.SOUTH);
		highlightWhileOver(row, route.getId());
		lightOnHover(row);
		return row;
	}

	/**
	 * A route's stops as numbered rows, one line each: the first {@link #FOLDED_STOPS} and "+12 more", which shows them all
	 * (and "Show fewer" folds them again). A list that would hide a single stop is shown whole.
	 */
	private JComponent stopList(SavedRoute route)
	{
		List<Long> stops = route.getStops();
		boolean foldable = stops.size() > FOLDED_STOPS + 1;
		boolean open = !foldable || unfolded.contains(route.getId());
		int shown = open ? stops.size() : FOLDED_STOPS;
		JPanel list = column();
		list.setBorder(PanelTheme.pad(1, 0, 1, 0));
		java.awt.FontMetrics metrics = list.getFontMetrics(PanelTheme.SMALL_BOLD);
		int numberWidth = metrics.stringWidth(String.valueOf(stops.size())) + 4;
		for (int i = 0; i < shown; i++)
		{
			list.add(fill(stopRow(i + 1, stops.get(i), numberWidth)));
		}
		if (foldable)
		{
			String text = open ? "Show fewer" : "+" + (stops.size() - shown) + " more";
			JLabel toggle = PanelTheme.label(text, PanelTheme.SMALL_BOLD, PanelTheme.ACCENT_DEEP);
			toggle.setBorder(PanelTheme.pad(2, numberWidth + 6, 0, 0));
			toggle.setToolTipText(open ? "Show only the first " + FOLDED_STOPS + " stops" : "Show every stop of this route");
			toggle.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			toggle.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					toggleStops(route.getId());
				}
			});
			list.add(fill(toggle));
		}
		return list;
	}

	/** Shows a route's whole stop list, or folds it again. Swing thread; also for tests and pictures. */
	void toggleStops(long routeId)
	{
		if (!unfolded.remove(routeId))
		{
			unfolded.add(routeId);
		}
		rebuildRoutes();
		revalidate();
		repaint();
	}

	/** One stop of a route: its number and the name of its place, cut short with its whole name in the tooltip. */
	private JComponent stopRow(int number, long stop, int numberWidth)
	{
		Pin pin = pins.get(stop);
		JLabel index = PanelTheme.label(String.valueOf(number), PanelTheme.SMALL_BOLD, PanelTheme.ACCENT_DEEP);
		index.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		index.setPreferredSize(new Dimension(numberWidth, index.getPreferredSize().height));
		String text = pin == null ? "A place that is no longer saved" : pin.getName();
		JLabel name = PanelTheme.label(text, PanelTheme.SMALL, pin == null ? PanelTheme.TEXT_FAINT : PanelTheme.TEXT_DIM);
		name.setToolTipText(pin == null ? text : pin.getName() + "  ·  " + areaName.apply(pin.getMap()));
		// Its width comes from the card, so a long name is cut short instead of widening the panel.
		name.setMinimumSize(new Dimension(0, name.getPreferredSize().height));
		name.setPreferredSize(new Dimension(0, name.getPreferredSize().height));
		JPanel row = PanelTheme.clear(new BorderLayout(6, 0));
		row.add(index, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);
		return row;
	}

	/** The route is drawn on the map while the mouse is over its row. */
	private void highlightWhileOver(JPanel row, long routeId)
	{
		MouseAdapter listener = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				routes.setHighlighted(routeId);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				java.awt.Point inRow = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row);
				if (!row.contains(inRow))
				{
					routes.setHighlighted(0);
				}
			}
		};
		attach(row, listener);
	}

	private static void attach(Component component, MouseAdapter listener)
	{
		component.addMouseListener(listener);
		if (component instanceof java.awt.Container)
		{
			for (Component child : ((java.awt.Container) component).getComponents())
			{
				attach(child, listener);
			}
		}
	}

	// ------------------------------------------------------------------
	// What the buttons do
	// ------------------------------------------------------------------

	private void setStatus(String text)
	{
		status.setText("<html><body style='width:" + PAGE_TEXT_WIDTH + "px'>" + escape(text) + "</body></html>");
		status.setBorder(PanelTheme.pad(0, 2, PanelTheme.GAP, 0));
		status.setVisible(true);
	}

	/** Reports a missing Shortest Path in words. Returns true when it can be asked. */
	private boolean shortestPathReady()
	{
		MapActions.Status state = actions.shortestPathStatus();
		if (state == MapActions.Status.MISSING)
		{
			setStatus("Shortest Path is not installed. Add it from the Plugin Hub to get routes.");
			return false;
		}
		if (state == MapActions.Status.DISABLED)
		{
			setStatus("Shortest Path is turned off. Switch it on in the plugin list to get routes.");
			return false;
		}
		return true;
	}

	private void goTo(Pin pin)
	{
		if (!shortestPathReady())
		{
			return;
		}
		setStatus("Route to " + pin.getName() + " sent to Shortest Path.");
		clientThread.invokeLater(() ->
		{
			routes.stopAll();
			actions.routeTo(RouteNavigator.pointOf(pin));
		});
	}

	private void toggle(SavedRoute route, boolean following)
	{
		if (following)
		{
			routes.stopAll();
			clientThread.invokeLater(actions::clearRoute);
			setStatus("Stopped " + route.getName() + ".");
			return;
		}
		// The same start as a route chosen in the map's search (round 48).
		setStatus(com.worldmapplus.pins.RouteStart.start(route, routes, pins, actions.shortestPathStatus()).getMessage());
	}

	/** Starts a saved route as its Start button does, for a route chosen in the map's search. Swing thread. */
	public void startRoute(long routeId)
	{
		SavedRoute route = routes.get(routeId);
		if (route != null)
		{
			toggle(route, false);
		}
	}

	/** Opens a saved route's editor as its Edit button does, for a route right clicked in the map's search. Swing thread. */
	public void editRoute(long routeId)
	{
		SavedRoute route = routes.get(routeId);
		if (route != null)
		{
			RouteEditor.show(this, pins, routes, route, new ArrayList<>());
		}
	}

	/** Lets the plugin hand over what works out the fastest order of a route. Without it the panel offers no Order button. */
	public void setOrderer(@javax.annotation.Nullable com.worldmapplus.pins.RouteOrderer orderer)
	{
		this.orderer = orderer;
		shownRoutes = -1;
	}

	/** Asks how to order a route (keep the first stop, round trip or not), then starts the work on the shared executor. */
	private void askOrder(SavedRoute route)
	{
		if (orderer == null)
		{
			return;
		}
		JCheckBox keepFirst = new JCheckBox("Keep the first stop first", true);
		JCheckBox roundTrip = new JCheckBox("Round trip: come back to the first stop", route.isLoop());
		JPanel choices = new JPanel(new GridLayout(2, 1));
		choices.add(keepFirst);
		choices.add(roundTrip);
		int answer = JOptionPane.showConfirmDialog(this, choices, "Fastest order for " + route.getName(), JOptionPane.OK_CANCEL_OPTION);
		if (answer != JOptionPane.OK_OPTION)
		{
			return;
		}
		boolean started = orderer.order(route.getId(), keepFirst.isSelected(), roundTrip.isSelected(),
			result -> SwingUtilities.invokeLater(() -> ordered(route.getName(), result)));
		setStatus(started ? "Working out the fastest order for " + route.getName() + "..."
			: "An order is already being worked out. Try again in a moment.");
	}

	private void ordered(String name, com.worldmapplus.pins.RouteOrderer.Result result)
	{
		if (result.getProblem() != null)
		{
			setStatus(result.getProblem());
			return;
		}
		long before = Math.round(result.getLengthBefore());
		long after = Math.round(result.getLengthAfter());
		if (!result.isBetter())
		{
			setStatus(name + " is already in the fastest order, about " + before + " tiles.");
			return;
		}
		if (!orderer.apply(result))
		{
			setStatus(name + " changed while its order was worked out, so it was left as it is.");
			return;
		}
		lastOrder = result;
		shownRoutes = -1;
		setStatus("New order for " + name + ": about " + after + " tiles, was " + before + ". Undo order puts it back.");
	}

	private void routeFromTicked()
	{
		List<Long> stops = new ArrayList<>(ticked);
		if (stops.isEmpty())
		{
			return;
		}
		RouteEditor.show(this, pins, routes, null, stops);
		ticked.clear();
		rebuildAll();
	}

	/** Shares the ticked places: one place as a pin code, more as a set of places. */
	private void shareTicked()
	{
		if (ticked.isEmpty())
		{
			return;
		}
		showCode(ShareBuilder.ofIds(new ArrayList<>(ticked), pins, this::autoMap));
	}

	/** The map this version shows a tile on, so a place's own map is only put in a code when it is another one. */
	private int autoMap(int x, int y)
	{
		return importer == null ? com.worldmapplus.map.MapInfo.SURFACE : importer.autoMap(x, y);
	}

	/** Lets the plugin say how a share is shown on the map and how the way to it is asked for. */
	public void setShareHandlers(java.util.function.Consumer<Share> viewer, java.util.function.Consumer<Share> goer)
	{
		shareViewer = viewer == null ? s -> { } : viewer;
		shareGoer = goer == null ? s -> { } : goer;
	}

	/** Lets the plugin hand over the typing guide and its setting. Without them the share box offers no guide. */
	public void setTypingGuide(@javax.annotation.Nullable TypingGuide guide, @javax.annotation.Nullable BooleanSupplier enabled)
	{
		this.guide = guide;
		this.guideEnabled = enabled == null ? () -> false : enabled;
		shownGuide = -1;
	}

	private boolean guideOn()
	{
		return guide != null && guideEnabled.getAsBoolean();
	}

	/** Makes the messages of a share and shows them, each with its length and a Copy button, and starts the typing guide. */
	private void showCode(Share share)
	{
		List<String> messages;
		try
		{
			messages = ShareCodec.messages(share);
		}
		catch (ShareException e)
		{
			sharing = null;
			setStatus(e.getMessage());
			rebuildShare();
			return;
		}
		sharing = share;
		if (guideOn())
		{
			guide.start(messages, 0);
			setStatus("The typing guide shows the code on the chat's typing line. Type it and press Enter.");
		}
		else
		{
			setStatus("Type the code into the game's chat yourself and send it. Players with World Map Plus can open it.");
		}
		rebuildShare();
	}

	/** What a share holds, point by point, so the sender can check it before typing it. */
	static String summary(Share share)
	{
		StringBuilder text = new StringBuilder();
		List<com.worldmapplus.share.SharePoint> points = share.getPoints();
		if (share.getKind() == Share.Kind.PIN)
		{
			com.worldmapplus.share.SharePoint p = points.get(0);
			return "Pin at " + p.describe() + " (region " + p.regionId() + ", local " + p.localX() + ", " + p.localY() + ")";
		}
		text.append(share.getKind() == Share.Kind.ROUTE ? points.size() + " stops in this order" + (share.loops() ? ", then back to 1" : "")
			: points.size() + " places").append(": ");
		for (int i = 0; i < points.size(); i++)
		{
			text.append(i == 0 ? "" : "; ").append(i + 1).append(". ").append(points.get(i).describe());
		}
		return text.toString();
	}

	/** The summary cut to its first few points, for the share box; the whole of it is the tooltip. */
	static String shortSummary(Share share)
	{
		List<com.worldmapplus.share.SharePoint> points = share.getPoints();
		if (share.getKind() == Share.Kind.PIN || points.size() <= 4)
		{
			return summary(share);
		}
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 3; i++)
		{
			text.append(i == 0 ? "" : "; ").append(i + 1).append(". ").append(points.get(i).describe());
		}
		return text.append("; and ").append(points.size() - 3).append(" more").toString();
	}

	/** The share box: what is shared, each message as a grouped code with its state, Copy and Type it, and the guide's controls. */
	private void rebuildShare()
	{
		shareBody.removeAll();
		messageStates.clear();
		messageCodes.clear();
		guideLine = null;
		sharingMessages = new ArrayList<>();
		if (sharing != null)
		{
			try
			{
				sharingMessages = ShareCodec.messages(sharing);
			}
			catch (ShareException e)
			{
				sharing = null;
			}
		}
		if (sharing != null)
		{
			List<String> messages = sharingMessages;
			shareBody.add(PanelTheme.header("Share", messages.size() > 1 ? messages.size() : -1));
			shareBody.add(gap(2));
			PanelTheme.Card card = new PanelTheme.Card(null, PanelTheme.CARD, PanelTheme.ACCENT);
			card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
			String describe = sharing.describe();
			JLabel title = PanelTheme.label(Character.toUpperCase(describe.charAt(0)) + describe.substring(1), PanelTheme.BODY_BOLD,
				PanelTheme.TEXT);
			card.add(fill(title));
			JLabel check = PanelTheme.wrapped(shortSummary(sharing), PanelTheme.SMALL, PanelTheme.TEXT_DIM, TEXT_WIDTH);
			check.setToolTipText(summary(sharing));
			card.add(fill(check));
			card.add(gap(6));
			boolean guided = guideOn();
			guideShownActive = guided && guide.isActive();
			if (guided)
			{
				card.add(fill(guideControls(messages)));
				card.add(gap(6));
			}
			for (int i = 0; i < messages.size(); i++)
			{
				card.add(messageBlock(messages, i, guided));
				card.add(gap(i == messages.size() - 1 ? 4 : 8));
			}
			card.add(fill(PanelTheme.wrapped(messages.size() > 1
				? "Type each message into the chat and press Enter, one after the other. They join in any order."
				: "Type the message into the chat and press Enter. RuneLite plugins cannot type in chat for you.",
				PanelTheme.SMALL, PanelTheme.TEXT_FAINT, TEXT_WIDTH)));
			card.add(gap(6));
			card.add(fill(outsideCopies(messages)));
			card.add(gap(6));
			JButton close = small("Close");
			close.setToolTipText("Close this box, and stop the typing guide if it runs");
			close.addActionListener(e ->
			{
				if (guide != null && guide.isActive() && guide.messages().equals(lowerCase(sharingMessages)))
				{
					guide.stop();
				}
				sharing = null;
				rebuildShare();
			});
			JPanel closeRow = PanelTheme.clear(new BorderLayout());
			closeRow.add(close, BorderLayout.EAST);
			card.add(fill(closeRow));
			shareBody.add(card);
			shareBody.add(gap(8));
			showGuideProgress();
		}
		revalidate();
		repaint();
	}

	/**
	 * Copies for outside the game, where no chat length applies: the whole code as one, which Import a code reads as it
	 * is, and for a code in parts all its messages, one a line, for someone who types them into chat one by one.
	 */
	private JComponent outsideCopies(List<String> messages)
	{
		JButton whole = small("Copy whole code");
		whole.setToolTipText("Copy the whole code as one, to paste in Discord or anywhere. Import a code reads it");
		whole.addActionListener(e ->
		{
			String code = wholeCode();
			setStatus(code == null ? "There is no code to copy." : ShareDialog.copy(code)
				? "Copied the whole code, " + code.length() + " characters. Import a code in the side panel reads it."
				: "The clipboard cannot be used on this computer.");
		});
		if (messages.size() < 2)
		{
			return buttonGrid(1, whole);
		}
		JButton all = small("Copy all parts");
		all.setToolTipText("Copy every message, one a line, each with " + ShareCodec.COMMAND + ", to type into chat one by one");
		all.addActionListener(e -> setStatus(ShareDialog.copy(allParts(messages))
			?"Copied all " + messages.size() + " parts, one a line." : "The clipboard cannot be used on this computer."));
		return buttonGrid(2, whole, all);
	}

	/** Every message of a share, one a line, as Copy all parts copies them. */
	static String allParts(List<String> messages)
	{
		return String.join("\n", messages);
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

	private static List<String> lowerCase(List<String> messages)
	{
		List<String> lower = new ArrayList<>();
		for (String message : messages)
		{
			lower.add(message.toLowerCase(java.util.Locale.ROOT));
		}
		return lower;
	}

	/** The guide's line and its Start, Back, Skip and Stop buttons. */
	private JComponent guideControls(List<String> messages)
	{
		JPanel box = PanelTheme.clear(new BorderLayout(0, 4));
		guideLine = PanelTheme.label(" ", PanelTheme.SMALL_BOLD, PanelTheme.ACCENT);
		box.add(guideLine, BorderLayout.NORTH);
		if (guide.isActive())
		{
			JButton back = small("Back");
			back.setToolTipText("Go back to the message before");
			back.addActionListener(e -> guide.back());
			JButton skip = small("Skip");
			skip.setToolTipText("Go on to the next message without marking this one");
			skip.addActionListener(e -> guide.skip());
			JButton stop = small("Stop");
			stop.setToolTipText("Take the guide off the chat line");
			stop.addActionListener(e ->
			{
				guide.stop();
				rebuildShare();
			});
			box.add(messages.size() > 1 ? buttonGrid(3, back, skip, stop) : buttonGrid(3, stop), BorderLayout.SOUTH);
		}
		else
		{
			JButton start = PanelTheme.primary(messages.size() > 1 ? "Start typing guide at message 1" : "Start typing guide");
			start.setToolTipText("Show the code as see-through letters on the chat's typing line");
			start.addActionListener(e ->
			{
				guide.start(messages, 0);
				rebuildShare();
			});
			box.add(start, BorderLayout.SOUTH);
		}
		return box;
	}

	/** One message: its number, length and state, the grouped code, and its Type it and Copy buttons. */
	private JComponent messageBlock(List<String> messages, int index, boolean guided)
	{
		String message = messages.get(index);
		JPanel block = column();
		JPanel head = PanelTheme.clear(new BorderLayout(4, 0));
		head.add(PanelTheme.label(messages.size() > 1 ? "Message " + (index + 1) + " of " + messages.size() : "Message",
			PanelTheme.SMALL_BOLD, PanelTheme.TEXT_DIM), BorderLayout.WEST);
		JLabel state = PanelTheme.label("", PanelTheme.SMALL, PanelTheme.TEXT_FAINT);
		state.putClientProperty(LENGTH, message.length());
		head.add(state, BorderLayout.EAST);
		block.add(fill(head));
		block.add(gap(3));
		CodeView code = new CodeView(message);
		block.add(code);
		block.add(gap(4));
		JButton copy = small("Copy");
		copy.setToolTipText("Copy this message exactly, for use outside the game");
		copy.addActionListener(e -> setStatus(ShareDialog.copy(message) ? "Copied." : "The clipboard cannot be used on this computer."));
		if (guided)
		{
			JButton type = small("Type it");
			type.setToolTipText("Put the typing guide on this message");
			type.addActionListener(e ->
			{
				if (!guide.isActive() || !guide.messages().equals(lowerCase(messages)))
				{
					guide.start(messages, index);
				}
				else
				{
					guide.jumpTo(index);
				}
				rebuildShare();
			});
			block.add(fill(buttonGrid(2, type, copy)));
		}
		else
		{
			JPanel row = PanelTheme.clear(new BorderLayout());
			row.add(copy, BorderLayout.EAST);
			block.add(fill(row));
		}
		messageStates.add(state);
		messageCodes.add(code);
		return block;
	}

	/** Brings the share box's message states, the guide's line and the typed letters up to date, in place. */
	private void showGuideProgress()
	{
		if (guide == null)
		{
			return;
		}
		shownGuide = guide.version();
		boolean ours = guide.messages().equals(lowerCase(sharingMessages));
		boolean active = guide.isActive() && ours;
		if (sharing != null && guideOn() && guide.isActive() != guideShownActive)
		{
			// The guide started or stopped (perhaps from its card): the controls change, so the box is made again.
			guideShownActive = guide.isActive();
			rebuildShare();
			return;
		}
		guide.fill(guideView);
		for (int i = 0; i < messageStates.size(); i++)
		{
			TypingGuide.State state = ours ? guide.state(i) : TypingGuide.State.NOT_TYPED;
			boolean here = active && guide.current() == i;
			if (here && state == TypingGuide.State.NOT_TYPED)
			{
				state = TypingGuide.State.TYPING;
			}
			JLabel label = messageStates.get(i);
			label.setText(label.getClientProperty(LENGTH) + " chars, " + (state == TypingGuide.State.NOT_TYPED ? "not typed" : state));
			label.setForeground(state == TypingGuide.State.DONE ? PanelTheme.GOOD : here ? PanelTheme.ACCENT : PanelTheme.TEXT_FAINT);
			int count = 0;
			if (here)
			{
				count = Math.max(0, Math.min(guideView.messageLength, guideView.typedLength - guideView.channel));
				for (int j = 0; j < count; j++)
				{
					messageMarks[j] = guideView.marks[j + guideView.channel];
				}
			}
			messageCodes.get(i).setProgress(here, messageMarks, count);
		}
		if (guideLine != null)
		{
			String text;
			if (active)
			{
				text = (guide.count() > 1 ? "Guide on message " + (guide.current() + 1) + " of " + guide.count() : "Guide on the chat line")
					+ ": " + hintWords(guide.hint());
			}
			else if (ours && guide.isFinished())
			{
				text = guide.count() > 1 ? "All " + guide.count() + " messages typed." : "Typed and sent.";
			}
			else
			{
				text = "The guide puts the code on the chat line.";
			}
			guideLine.setText(text);
			guideLine.setForeground(active && guide.hint() == TypingGuide.Hint.TYPO ? PanelTheme.BAD
				: ours && guide.isFinished() ? PanelTheme.GOOD : PanelTheme.ACCENT);
		}
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

	/** The message shown while a share is open: its messages, for tests. */
	List<String> shownMessages()
	{
		try
		{
			return sharing == null ? new ArrayList<>() : ShareCodec.messages(sharing);
		}
		catch (ShareException e)
		{
			return new ArrayList<>();
		}
	}

	/** Shows the Routes page, for tests and pictures. */
	void selectRoutes()
	{
		tabs.choose(1);
	}

	/** Opens the share box for a share as the Share buttons do, for tests and pictures. */
	void openShare(Share share)
	{
		showCode(share);
	}

	/** The code views of the share box, for tests. */
	List<CodeView> shownCodes()
	{
		return new ArrayList<>(messageCodes);
	}

	/** The states shown for the messages, for tests. */
	List<String> shownStates()
	{
		List<String> states = new ArrayList<>();
		for (JLabel label : messageStates)
		{
			states.add(label.getText());
		}
		return states;
	}

	/**
	 * Moves the panel from one theme to another: colours already on built pieces are mapped role by role, and the lists
	 * are built again in the new colours. The theme must already be in use ({@code Look.use}). Interface thread only.
	 */
	public void restyle(com.worldmapplus.theme.Palette from, com.worldmapplus.theme.Palette to)
	{
		Recolour.apply(this, from, to);
		rebuildAll();
		if (sharing != null)
		{
			rebuildShare();
			shownGuide = -1;
		}
		revalidate();
		repaint();
	}

	/** Brings the panel up to date as its timer does, for tests. */
	void refreshNow()
	{
		refreshIfChanged();
	}

	/** Reads codes from the clipboard into Shared with you. The same reading as for codes in chat. */
	private void importPasted()
	{
		String text = ShareDialog.read();
		if (text == null || text.trim().isEmpty())
		{
			showImportResult("The clipboard has no text. Copy a code first.", PanelTheme.BAD);
			return;
		}
		importFrom(text);
	}

	/** Reads the codes typed or pasted into the import box. The box is emptied when everything in it was read. */
	private void importTyped()
	{
		if (importFrom(importText.getText()))
		{
			importText.setText("");
		}
	}

	/** Reads codes into Shared with you, nothing saved, and says what came of it. True when nothing failed to read. */
	private boolean importFrom(String text)
	{
		ShareInbox.Pasted result = inbox.importPasted(text, System.currentTimeMillis());
		lastImport = result;
		showImportResult(result.getMessage(), result.hasProblem() ? PanelTheme.BAD : result.getAdded() > 0 ? PanelTheme.GOOD
			: PanelTheme.ACCENT);
		rebuildInbox();
		return !result.hasProblem();
	}

	private void showImportResult(String text, Color colour)
	{
		importStatus.setText("<html><body style='width:" + TEXT_WIDTH + "px'>" + PanelTheme.escape(text) + "</body></html>");
		importStatus.setForeground(colour);
		importStatus.setVisible(true);
		showImport(true);
	}

	/** Opens or closes the import box. */
	private void showImport(boolean open)
	{
		if (importBody.isVisible() == open)
		{
			return;
		}
		importBody.setVisible(open);
		revalidate();
		repaint();
		if (open)
		{
			importText.requestFocusInWindow();
		}
	}

	/** Makes the import box once; it is shown and hidden from then on. */
	private void buildImport()
	{
		importText.setDocument(new CappedDocument(PasteReader.MAX_CHARS));
		importText.setLineWrap(true);
		importText.setRows(4);
		importText.setFont(PanelTheme.SMALL);
		importText.setForeground(PanelTheme.TEXT);
		importText.setBackground(PanelTheme.FIELD);
		importText.setCaretColor(PanelTheme.ACCENT);
		importText.setBorder(PanelTheme.pad(4, 5, 4, 5));
		importText.setToolTipText("Codes are found among other words: a whole code, a long code in one piece, or its parts");
		javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(importText, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(javax.swing.BorderFactory.createLineBorder(PanelTheme.EDGE));
		scroll.setPreferredSize(new Dimension(0, 70));
		importStatus.setFont(PanelTheme.SMALL);
		importStatus.setVisible(false);
		importNow.setToolTipText("Read the codes in the box into Shared with you. Nothing is saved until you press Save");
		importClipboard.setToolTipText("Read the codes on the clipboard into Shared with you");
		importClose.setToolTipText("Close the import box");
		PanelTheme.Card card = new PanelTheme.Card(null, PanelTheme.CARD, PanelTheme.ACCENT);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		JPanel title = PanelTheme.clear(new BorderLayout(4, 0));
		title.add(PanelTheme.label("Import a code", PanelTheme.BODY_BOLD, PanelTheme.TEXT), BorderLayout.WEST);
		title.add(importClose, BorderLayout.EAST);
		card.add(fill(title));
		card.add(gap(5));
		card.add(fill(scroll));
		card.add(gap(6));
		card.add(fill(buttonGrid(2, importNow, importClipboard)));
		// The status line changes height with its text, so its holder is not held to the height it had when made.
		JPanel said = PanelTheme.clear(new BorderLayout());
		said.setBorder(PanelTheme.pad(5, 0, 0, 0));
		said.add(importStatus, BorderLayout.CENTER);
		said.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(said);
		importBody.add(PanelTheme.header("Import", -1));
		importBody.add(gap(2));
		importBody.add(card);
		importBody.add(gap(8));
		importBody.setVisible(false);
	}

	/** A text box that shows a faint hint while it is empty. */
	static final class HintArea extends javax.swing.JTextArea
	{
		private final String hint;

		HintArea(String hint)
		{
			this.hint = hint;
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			super.paintComponent(g);
			if (getDocument().getLength() > 0)
			{
				return;
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			PanelTheme.smooth(g2);
			g2.setFont(PanelTheme.SMALL);
			g2.setColor(PanelTheme.TEXT_FAINT);
			java.awt.FontMetrics metrics = g2.getFontMetrics();
			java.awt.Insets insets = getInsets();
			int width = Math.max(40, getWidth() - insets.left - insets.right);
			int y = insets.top + metrics.getAscent();
			StringBuilder line = new StringBuilder();
			for (String word : hint.split(" "))
			{
				String next = line.length() == 0 ? word : line + " " + word;
				if (metrics.stringWidth(next) > width && line.length() > 0)
				{
					g2.drawString(line.toString(), insets.left, y);
					y += metrics.getHeight();
					line = new StringBuilder(word);
				}
				else
				{
					line = new StringBuilder(next);
				}
			}
			g2.drawString(line.toString(), insets.left, y);
			g2.dispose();
		}
	}

	/** A document that holds at most a set number of characters, so a huge paste is cut at once. */
	static final class CappedDocument extends javax.swing.text.PlainDocument
	{
		private final int max;

		CappedDocument(int max)
		{
			this.max = max;
		}

		@Override
		public void insertString(int offset, String text, javax.swing.text.AttributeSet attributes)
			throws javax.swing.text.BadLocationException
		{
			if (text == null)
			{
				return;
			}
			int room = max - getLength();
			if (room > 0)
			{
				super.insertString(offset, text.length() > room ? text.substring(0, room) : text, attributes);
			}
		}
	}

	/** Puts text in the import box and presses Import, for tests. */
	ShareInbox.Pasted importForTest(String text)
	{
		importText.setText(text);
		importTyped();
		return lastImport;
	}

	/** The import box's text and its status line, for tests. */
	String importBoxText()
	{
		return importText.getText();
	}

	String importStatusText()
	{
		return importStatus.getText();
	}

	/** Opens the import box, for tests and pictures. */
	void openImport()
	{
		showImport(true);
	}

	/** Called when the player chose Open in World Map Plus on a chat line: the share is listed and marked. */
	public void showOpenedShare()
	{
		rebuildInbox();
		for (ShareInbox.Offer offer : inbox.offers())
		{
			if (offer.getId() == inbox.getOpened())
			{
				setStatus("Opened the shared " + offer.getShare().describe() + ". It is marked on the map. Save keeps it.");
			}
		}
	}

	/** The shares other players sent, each with Show, Go, Save and Dismiss. */
	private void rebuildInbox()
	{
		shownInbox = inbox.getVersion();
		inboxBody.removeAll();
		List<ShareInbox.Offer> offers = inbox.offers();
		flashAfterOffer = newestOffer;
		for (ShareInbox.Offer each : offers)
		{
			newestOffer = Math.max(newestOffer, each.getId());
		}
		if (!offers.isEmpty())
		{
			inboxBody.add(PanelTheme.header("Shared with you", offers.size()));
			inboxBody.add(gap(2));
		}
		for (int i = offers.size() - 1; i >= 0; i--)
		{
			inboxBody.add(offerRow(offers.get(i)));
			inboxBody.add(gap(5));
		}
		if (!offers.isEmpty())
		{
			inboxBody.add(gap(4));
		}
		revalidate();
		inboxBody.repaint();
	}

	private JPanel offerRow(ShareInbox.Offer offer)
	{
		boolean opened = offer.getId() == inbox.getOpened();
		FadeRow row = newRow(opened ? PanelTheme.CARD_ACTIVE : PanelTheme.CARD, PanelTheme.ACCENT,
			opened || flashAfterOffer >= 0 && offer.getId() > flashAfterOffer);
		Share share = offer.getShare();
		String describe = share.describe();
		JLabel title = PanelTheme.label(Character.toUpperCase(describe.charAt(0)) + describe.substring(1), PanelTheme.BODY_BOLD,
			PanelTheme.TEXT);
		title.setToolTipText(summary(share));
		JLabel where = PanelTheme.label(share.getKind() == Share.Kind.PIN ? "Region " + share.getPoints().get(0).regionId()
			: "Starts at " + share.getPoints().get(0).describe(), PanelTheme.SMALL, PanelTheme.TEXT_DIM);
		JButton show = small("Show");
		show.setToolTipText("Mark it on the map and centre the map there. Nothing is saved");
		show.addActionListener(e ->
		{
			shareViewer.accept(share);
			setStatus("The shared " + describe + " is marked on the map.");
		});
		JButton go = small("Go");
		go.setToolTipText(share.getKind() == Share.Kind.ROUTE ? "Ask Shortest Path for the way to the first stop, with your own settings"
			: "Ask Shortest Path for the way there, with your own settings");
		go.addActionListener(e ->
		{
			if (shortestPathReady())
			{
				shareGoer.accept(share);
				setStatus("Asked Shortest Path for the way to " + share.getPoints().get(0).describe() + ".");
			}
		});
		JButton take = PanelTheme.primary("Save");
		take.setToolTipText(share.getKind() == Share.Kind.ROUTE ? "Save it as a route, stops in the same order. Places you have are used again"
			: "Save it to your places with your usual look. Places you have are not saved twice");
		take.addActionListener(e -> saveOffer(offer));
		JButton dismiss = small("Dismiss");
		dismiss.addActionListener(e ->
		{
			inbox.remove(offer.getId());
			external.clearShare();
			rebuildInbox();
		});
		JPanel top = PanelTheme.clear(new BorderLayout(0, 1));
		top.add(title, BorderLayout.NORTH);
		top.add(where, BorderLayout.SOUTH);
		row.add(top, BorderLayout.NORTH);
		row.add(buttonGrid(4, show, go, take, dismiss), BorderLayout.SOUTH);
		lightOnHover(row);
		return row;
	}

	private void saveOffer(ShareInbox.Offer offer)
	{
		if (importer == null)
		{
			setStatus("Saving is not available here.");
			return;
		}
		Share share = offer.getShare();
		String message;
		if (share.getKind() == Share.Kind.ROUTE)
		{
			Object name = JOptionPane.showInputDialog(this, "Name of the route", "Save shared route", JOptionPane.PLAIN_MESSAGE,
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
		setStatus(message);
		if (message.startsWith("Saved"))
		{
			inbox.remove(offer.getId());
			external.clearShare();
		}
		rebuildInbox();
	}

	/** Sizes a pin can be, as percentages of the usual one. */
	private static final int[] SIZES = {70, 100, 140, 180};
	private static final String[] SIZE_NAMES = {"Small", "Normal", "Large", "Larger"};

	/** The window for changing a saved place: its name, colour and size, and whether it shows on the map. */
	private void rename(Pin pin)
	{
		javax.swing.JTextField name = new javax.swing.JTextField(pin.getName(), 18);
		int[] colour = {pin.getColor()};
		JButton pick = new JButton(colour[0] == 0 ? "Usual colour" : "Colour");
		pick.setBackground(colour[0] == 0 ? DEFAULT_PIN : new Color(colour[0]));
		pick.setOpaque(true);
		pick.addActionListener(e ->
		{
			Color chosen = javax.swing.JColorChooser.showDialog(this, "Pin colour",
				colour[0] == 0 ? DEFAULT_PIN : new Color(colour[0]));
			if (chosen != null)
			{
				colour[0] = chosen.getRGB() & 0xFFFFFF;
				pick.setText("Colour");
				pick.setBackground(chosen);
			}
		});
		JButton usual = new JButton("Usual");
		usual.addActionListener(e ->
		{
			colour[0] = 0;
			pick.setText("Usual colour");
			pick.setBackground(DEFAULT_PIN);
		});
		JComboBox<String> size = new JComboBox<>(SIZE_NAMES);
		int nearest = 1;
		for (int i = 0; i < SIZES.length; i++)
		{
			if (Math.abs(SIZES[i] - pin.getSize()) < Math.abs(SIZES[nearest] - pin.getSize()))
			{
				nearest = i;
			}
		}
		size.setSelectedIndex(nearest);
		JCheckBox visible = new JCheckBox("Show on the map", !pin.isHidden());

		JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
		form.add(new JLabel("Name"));
		form.add(name);
		JPanel colours = new JPanel(new BorderLayout(4, 0));
		colours.add(pick, BorderLayout.CENTER);
		colours.add(usual, BorderLayout.EAST);
		form.add(new JLabel("Colour"));
		form.add(colours);
		form.add(new JLabel("Size"));
		form.add(size);
		form.add(visible);
		int answer = JOptionPane.showConfirmDialog(this, form, "Edit saved place", JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE);
		if (answer != JOptionPane.OK_OPTION)
		{
			return;
		}
		pins.rename(pin.getId(), name.getText());
		pins.restyle(pin.getId(), colour[0], SIZES[Math.max(0, size.getSelectedIndex())], !visible.isSelected());
	}
}
