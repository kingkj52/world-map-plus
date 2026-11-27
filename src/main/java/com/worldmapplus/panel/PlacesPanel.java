package com.worldmapplus.panel;

import static com.worldmapplus.theme.Palette.colour;

import com.worldmapplus.bridge.*;
import com.worldmapplus.map.*;
import com.worldmapplus.pins.*;
import com.worldmapplus.share.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.typing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.annotation.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import javax.swing.text.*;
import lombok.*;
import net.runelite.client.callback.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.components.*;

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
	static final int TEXT_WIDTH = 140;

	/** The width text wraps to across the page. Swing's html widths come out wider than asked, so these are below the room. */
	private static final int PAGE_TEXT_WIDTH = 160;

	private final PinStore pins;
	private final RouteStore routes;
	private final RouteRunner runner;
	private final MapActions actions;
	final ExternalOverlays external;
	private final ClientThread clientThread;
	private final IntFunction<String> areaName;
	private final ShareImport importer;
	/** The share box, the import box and Shared with you. */
	final ShareBoxes shares;

	/** The newest saved place already listed, so one that appears later can glow for a moment. */
	private long newestPin = -1;
	private long flashAfterPin = -1;

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
	private final IconTextField routeFilter = searchField("Filter by route name or by the name of a stop");
	private final JComboBox<RouteList.Sort> routeSort = new JComboBox<>(RouteList.Sort.values());
	private final JCheckBox loopsOnly = PanelTheme.style(new JCheckBox("Loops only"), PanelTheme.SMALL, colour("TEXT_DIM"));
	private final PanelTheme.Pill newRoute = PanelTheme.pill("New route");

	/** How many stops a route card lists before "+ more". */
	static final int FOLDED_STOPS = 3;

	/** The routes whose whole stop list is shown, by id, while the panel is open. */
	private final Set<Long> unfolded = new HashSet<>();

	/** The places ticked for a new route, in the order they were ticked. */
	private final Set<Long> ticked = new LinkedHashSet<>();

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
		this.importer = importer;
		shares = new ShareBoxes(this, inbox, importer);
		this.pins = pins;
		this.routes = routes;
		this.runner = runner;
		this.actions = actions;
		this.external = external;
		this.clientThread = clientThread;
		this.areaName = areaName;

		setLayout(new BorderLayout(0, PanelTheme.GAP));
		setBackground(colour("PAGE"));
		setBorder(PanelTheme.pad(8, 6, 8, 6));

		status.setFont(PanelTheme.SMALL);
		status.setForeground(colour("TEXT_DIM"));
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
		header.add(shares.shareBody);
		header.add(shares.importBody);
		header.add(shares.inboxBody);
		header.add(tabs);
		add(header, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		wireControls();
		refresher = new Timer((int) REFRESH_MILLIS, e -> refreshIfChanged());
		refresher.setRepeats(true);
		rebuildAll();
	}

	/** The toolbar icon. */
	public static BufferedImage icon()
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
				Insets insets = parent.getInsets();
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
	static JPanel column()
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
		JLabel title = PanelTheme.label("World Map Plus", PanelTheme.TITLE, colour("ACCENT"));
		title.setIcon(new ImageIcon(PanelIcon.create()));
		title.setIconTextGap(6);
		JLabel sub = PanelTheme.label("Places, routes and shares", PanelTheme.SMALL, colour("TEXT_FAINT"));
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
		PanelTheme.Card box = new PanelTheme.Card(null, colour("CARD"), null);
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
		sharePlaces.setToolTipText("Make a !wmp code for the ticked places to type into chat");
		importCode.setToolTipText("Paste a code sent outside the game. Codes in chat are found without this");
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
		PanelTheme.Card box = new PanelTheme.Card(null, colour("CARD"), null);
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
		box.add(PanelTheme.label(caption, PanelTheme.SMALL, colour("TEXT_FAINT")), BorderLayout.NORTH);
		box.add(field, BorderLayout.CENTER);
		return box;
	}

	/** Lets a piece take the whole width of a column. */
	static JComponent fill(Component piece)
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
		field.setBackground(colour("FIELD"));
		field.setHoverBackgroundColor(colour("CARD_HOVER"));
		field.setToolTipText(tooltip);
		return field;
	}

	/** A row card with an accent bar of colour down its left edge, that warms under the pointer and glows when it is new. */
	static FadeRow newRow(Color idle, Color bar, boolean fresh)
	{
		FadeRow row = new FadeRow(new BorderLayout(0, 4), idle, colour("CARD_HOVER"), colour("ACCENT_DEEP"), bar);
		if (fresh)
		{
			row.flash();
		}
		return row;
	}

	/** Warms a row while the pointer is anywhere over it or what it holds. */
	static void lightOnHover(FadeRow row)
	{
		whileOver(row, row::hover);
	}

	/** Tells when the pointer comes over a row or what it holds, and when it leaves the row. */
	private static void whileOver(JPanel row, Consumer<Boolean> over)
	{
		attach(row, new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				over.accept(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!row.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row)))
				{
					over.accept(false);
				}
			}
		});
	}

	/** A small pill button, for the rows. */
	static PanelTheme.Pill small(String text)
	{
		return PanelTheme.pill(text);
	}

	/** A small pill button with its tip (null for none) and what it does. */
	static JButton small(String text, @Nullable String tip, ActionListener action)
	{
		return act(PanelTheme.pill(text), tip, action);
	}

	/** Gives a button its tip (null for none) and what it does. */
	static JButton act(JButton button, @Nullable String tip, ActionListener action)
	{
		button.setToolTipText(tip);
		button.addActionListener(action);
		return button;
	}

	/** Buttons in equal cells, so the rows of a list line up. */
	static JPanel buttonGrid(int columns, JComponent... buttons)
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
		importCode.addActionListener(e -> shares.showImport(!shares.importBody.isVisible()));
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
		if (pins.getVersion() != shownPins || routes.getVersion() != shownRoutes)
		{
			rebuildAll();
		}
		shares.refresh();
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
			shares.rebuildInbox();
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
				? "Nothing saved yet. Right-click the map and choose Add pin here, or Save place on a card or result."
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

	static Component gap(int height)
	{
		return Box.createRigidArea(new Dimension(0, height));
	}

	/** A short message in a quiet card, for an empty list. */
	private static JComponent note(String text)
	{
		PanelTheme.Card card = new PanelTheme.Card(new BorderLayout(), colour("CARD"), null);
		card.add(PanelTheme.wrapped(text, PanelTheme.SMALL, colour("TEXT_DIM"), TEXT_WIDTH), BorderLayout.CENTER);
		return card;
	}

	private static String escape(String text)
	{
		return PanelTheme.escape(text);
	}

	private JPanel placeRow(Pin pin)
	{
		Color bar = pin.isHidden() ? HIDDEN_PIN : pin.getColor() == 0 ? DEFAULT_PIN : new Color(pin.getColor());
		FadeRow row = newRow(colour("CARD"), bar, flashAfterPin >= 0 && pin.getId() > flashAfterPin);

		JCheckBox pick = PanelTheme.style(plainCheck(pin.getName(), ticked.contains(pin.getId())), PanelTheme.BODY_BOLD,
			colour("TEXT"));
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
		detail.setForeground(colour("TEXT_DIM"));
		detail.setBorder(PanelTheme.pad(0, 20, 0, 0));

		JButton go = small("Go", "Ask Shortest Path for a route here", e -> goTo(pin));
		JButton show = small("Show", "Centre the map here the next time it is open", e ->
		{
			external.focusOn(RouteNavigator.pointOf(pin), pin.getName());
			setStatus("The map will centre on " + pin.getName() + " when it is open.");
		});
		JButton rename = small("Edit", "Change the name, the colour and the size, or hide it on the map", e -> rename(pin));
		JButton delete = small("Delete", null, e ->
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
		FadeRow row = newRow(following ? colour("CARD_ACTIVE") : colour("CARD"),
			following ? colour("GOOD") : colour("ACCENT"), false);

		JLabel name = PanelTheme.label(route.getName(), PanelTheme.BODY_BOLD, colour("TEXT"));
		name.setToolTipText(route.getName());
		int count = route.getStops().size();
		JLabel detail = PanelTheme.label(count + (count == 1 ? " stop" : " stops") + (following ? "  ·  following" : ""),
			PanelTheme.SMALL, following ? colour("GOOD") : colour("TEXT_DIM"));
		JCheckBox loop = PanelTheme.style(new JCheckBox("Loop", route.isLoop()), PanelTheme.SMALL, colour("TEXT_DIM"));
		loop.setToolTipText("Start again after the last stop");
		loop.addActionListener(e -> routes.update(route.getId(), route.getName(), route.getStops(), loop.isSelected()));

		JPanel facts = PanelTheme.clear(new BorderLayout());
		facts.add(detail, BorderLayout.WEST);
		facts.add(loop, BorderLayout.EAST);
		JPanel top = PanelTheme.clear(new BorderLayout(0, 2));
		top.add(name, BorderLayout.NORTH);
		top.add(facts, BorderLayout.SOUTH);

		List<JComponent> buttons = new ArrayList<>();
		buttons.add(act(following ? small("Stop") : PanelTheme.primary("Start"),
			following ? "Stop following this route" : "Walk this route with Shortest Path, stop by stop", e -> toggle(route, following)));
		if (following)
		{
			buttons.add(small("Skip", "Move on to the next stop", e -> clientThread.invokeLater(runner::skip)));
		}
		buttons.add(small("Edit", null, e -> RouteEditor.show(this, pins, routes, route, new ArrayList<>())));
		buttons.add(small("Share", "Make a !wmp code for this route's stops, in order, to type into chat",
			e -> shares.show(ShareBuilder.ofRoute(route, pins, this::autoMap))));
		JButton delete = small("Delete", null, e ->
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
		// The route is drawn on the map while the mouse is over its row.
		whileOver(row, on -> routes.setHighlighted(on ? route.getId() : 0));
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
		FontMetrics metrics = list.getFontMetrics(PanelTheme.SMALL_BOLD);
		int numberWidth = metrics.stringWidth(String.valueOf(stops.size())) + 4;
		for (int i = 0; i < shown; i++)
		{
			list.add(fill(stopRow(i + 1, stops.get(i), numberWidth)));
		}
		if (foldable)
		{
			String text = open ? "Show fewer" : "+" + (stops.size() - shown) + " more";
			JLabel toggle = PanelTheme.label(text, PanelTheme.SMALL_BOLD, colour("ACCENT_DEEP"));
			toggle.setBorder(PanelTheme.pad(2, numberWidth + 6, 0, 0));
			toggle.setToolTipText(open ? "Show only the first " + FOLDED_STOPS + " stops" : "Show every stop of this route");
			toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
		JLabel index = PanelTheme.label(String.valueOf(number), PanelTheme.SMALL_BOLD, colour("ACCENT_DEEP"));
		index.setHorizontalAlignment(SwingConstants.RIGHT);
		index.setPreferredSize(new Dimension(numberWidth, index.getPreferredSize().height));
		String text = pin == null ? "A place that is no longer saved" : pin.getName();
		JLabel name = PanelTheme.label(text, PanelTheme.SMALL, pin == null ? colour("TEXT_FAINT") : colour("TEXT_DIM"));
		name.setToolTipText(pin == null ? text : pin.getName() + "  ·  " + areaName.apply(pin.getMap()));
		// Its width comes from the card, so a long name is cut short instead of widening the panel.
		name.setMinimumSize(new Dimension(0, name.getPreferredSize().height));
		name.setPreferredSize(new Dimension(0, name.getPreferredSize().height));
		JPanel row = PanelTheme.clear(new BorderLayout(6, 0));
		row.add(index, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);
		return row;
	}



	private static void attach(Component component, MouseAdapter listener)
	{
		component.addMouseListener(listener);
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				attach(child, listener);
			}
		}
	}

	// ------------------------------------------------------------------
	// What the buttons do
	// ------------------------------------------------------------------

	void setStatus(String text)
	{
		status.setText("<html><body style='width:" + PAGE_TEXT_WIDTH + "px'>" + escape(text) + "</body></html>");
		status.setBorder(PanelTheme.pad(0, 2, PanelTheme.GAP, 0));
		status.setVisible(true);
	}

	/** Lays the panel out again after a box in it changed. */
	void relayout()
	{
		revalidate();
		repaint();
	}

	/** Reports a missing Shortest Path in words. Returns true when it can be asked. */
	boolean shortestPathReady()
	{
		MapActions.Status state = actions.shortestPathStatus();
		if (state == MapActions.Status.MISSING)
		{
			setStatus("Shortest Path is not installed. Add it from the Plugin Hub to get routes.");
			return false;
		}
		if (state == MapActions.Status.DISABLED)
		{
			setStatus("Shortest Path is turned off. Switch it on to get routes.");
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
		setStatus(RouteStart.start(route, routes, pins, actions.shortestPathStatus()).getMessage());
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
		shares.show(ShareBuilder.ofIds(new ArrayList<>(ticked), pins, this::autoMap));
	}

	/** The map this version shows a tile on, so a place's own map is only put in a code when it is another one. */
	private int autoMap(int x, int y)
	{
		return importer == null ? MapInfo.SURFACE : importer.autoMap(x, y);
	}

	/** Lets the plugin say how a share is shown on the map and how the way to it is asked for. */
	public void setShareHandlers(Consumer<Share> viewer, Consumer<Share> goer)
	{
		shares.viewer = viewer == null ? s -> { } : viewer;
		shares.goer = goer == null ? s -> { } : goer;
	}

	/** Lets the plugin hand over the typing guide and its setting. Without them the share box offers no guide. */
	public void setTypingGuide(@Nullable TypingGuide guide, @Nullable BooleanSupplier enabled)
	{
		shares.setTypingGuide(guide, enabled);
	}

	/** What a share holds, point by point, so the sender can check it before typing it. */
	static String summary(Share share)
	{
		StringBuilder text = new StringBuilder();
		List<SharePoint> points = share.getPoints();
		if (share.getKind() == Share.Kind.PIN)
		{
			SharePoint p = points.get(0);
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

	/** Shows the Routes page, for tests and pictures. */
	void selectRoutes()
	{
		tabs.choose(1);
	}

	/**
	 * Moves the panel from one theme to another: colours already on built pieces are mapped role by role, and the lists
	 * are built again in the new colours. The theme must already be in use ({@code Palette.use}). Interface thread only.
	 */
	public void restyle(Palette from, Palette to)
	{
		Recolour.apply(this, from, to);
		rebuildAll();
		shares.rebuildShare();
		revalidate();
		repaint();
	}

	/** Brings the panel up to date as its timer does, for tests. */
	void refreshNow()
	{
		refreshIfChanged();
	}

	/** Called when the player chose Open in World Map Plus on a chat line: the share is listed and marked. */
	public void showOpenedShare()
	{
		shares.showOpenedShare();
	}

	/** Sizes a pin can be, as percentages of the usual one. */
	private static final int[] SIZES = {70, 100, 140, 180};
	private static final String[] SIZE_NAMES = {"Small", "Normal", "Large", "Larger"};

	/** The window for changing a saved place: its name, colour and size, and whether it shows on the map. */
	private void rename(Pin pin)
	{
		JTextField name = new JTextField(pin.getName(), 18);
		int[] colour = {pin.getColor()};
		JButton pick = new JButton(colour[0] == 0 ? "Usual colour" : "Colour");
		pick.setBackground(colour[0] == 0 ? DEFAULT_PIN : new Color(colour[0]));
		pick.setOpaque(true);
		pick.addActionListener(e ->
		{
			Color chosen = JColorChooser.showDialog(this, "Pin colour",
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
