package com.worldmapplus.panel;

import com.worldmapplus.bridge.*;
import com.worldmapplus.pins.*;
import com.worldmapplus.theme.*;
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
import net.runelite.client.callback.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.components.*;

/**
 * The side panel: the player's saved places and routes, with a filter, a type filter and a sort for each,
 * the way Inventory Setups lists its setups. The Share and Import buttons are this panel's, and what they do belongs to the
 * World Map Plus Helper plugin, which puts its own sections (the share box, Shared with you, the import box) at the top of this
 * panel through the plugin API.
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

	/** The newest saved place already listed, so one that appears later can glow for a moment. */
	private long newestPin = -1;
	private long flashAfterPin = -1;

	private final JLabel status = new JLabel();
	private final JPanel placesBody = column();
	private final JPanel routesBody = column();
	private final PanelTheme.Segments tabs = new PanelTheme.Segments("Places", "Routes");
	private final CardLayout pages = new ShownPageLayout();
	private final JPanel display = PanelTheme.clear(pages);

	/** What other plugins put in the side panel: sections at the foot of each page, and pages of their own (see {@link Partners}). */
	private final Partners partners;
	private final JPanel topExtra = column();
	private final JPanel placesExtra = column();
	private final JPanel routesExtra = column();
	private final List<JPanel> partnerPages = new ArrayList<>();
	private final List<String> pageNames = new ArrayList<>(Arrays.asList("places", "routes"));
	private int shownPartners = -1;

	private final IconTextField placeFilter = searchField("Filter by name, type or area");
	private final JComboBox<String> typeBox = new JComboBox<>();
	private final JComboBox<PlaceList.Sort> placeSort = new JComboBox<>(PlaceList.Sort.values());
	private final PanelTheme.Pill makeRoute = PanelTheme.pill("New route from ticked places");
	private final PanelTheme.Pill sharePlaces = PanelTheme.pill("Share ticked");
	private final PanelTheme.Pill importCode = PanelTheme.pill("Import a code");

	private final IconTextField routeFilter = searchField("Filter by route name or by the name of a stop");
	private final JComboBox<RouteList.Sort> routeSort = new JComboBox<>(RouteList.Sort.values());
	private final JCheckBox loopsOnly = PanelTheme.style(new JCheckBox("Loops only"), PanelTheme.SMALL, PanelTheme.TEXT_DIM);
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
	private int shownOrder = -1;
	private boolean updating;

	public PlacesPanel(PinStore pins, RouteStore routes, RouteRunner runner, MapActions actions,
		ExternalOverlays external, ClientThread clientThread, IntFunction<String> areaName)
	{
		super(true);
		this.pins = pins;
		this.routes = routes;
		this.runner = runner;
		this.actions = actions;
		this.external = external;
		this.partners = external.partners();
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
		places.add(placesExtra, BorderLayout.SOUTH);

		JPanel routePage = PanelTheme.clear(new BorderLayout(0, PanelTheme.GAP));
		routePage.add(routeControls(), BorderLayout.NORTH);
		routePage.add(routesBody, BorderLayout.CENTER);
		routePage.add(routesExtra, BorderLayout.SOUTH);

		display.add(places, "places");
		display.add(routePage, "routes");
		tabs.onChoose(i ->
		{
			pages.show(display, pageNames.get(i));
			// The page area is as tall as the page shown, so the panel lays itself out again.
			revalidate();
			repaint();
		});

		JPanel header = column();
		header.add(brand());
		header.add(gap(PanelTheme.GAP));
		header.add(status);
		header.add(topExtra);
		header.add(tabs);
		add(header, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		wireControls();
		refresher = new Timer((int) REFRESH_MILLIS, e -> refreshIfChanged());
		refresher.setRepeats(true);
		rebuildAll();
		rebuildPartners();
	}

	/**
	 * Puts the sections other plugins registered at the foot of the Places and Routes pages, or on a page of their own for any
	 * other tab name. Interface thread only. A section that fails while it is drawn is reported, and gone at the next look.
	 */
	private void rebuildPartners()
	{
		shownPartners = partners.version();
		topExtra.removeAll();
		placesExtra.removeAll();
		routesExtra.removeAll();
		Map<String, JPanel> named = new LinkedHashMap<>();
		for (Partners.Slot slot : partners.slots(Partners.Kind.SECTION))
		{
			if (slot.isOff())
			{
				continue;
			}
			JPanel box = new JPanel(new BorderLayout(0, 2))
			{
				@Override
				protected void paintChildren(Graphics g)
				{
					try
					{
						super.paintChildren(g);
					}
					catch (RuntimeException e)
					{
						partners.fail(slot, e);
					}
				}
			};
			box.setOpaque(false);
			box.setAlignmentX(Component.LEFT_ALIGNMENT);
			String tab = slot.tab;
			boolean top = Partners.TOP.equalsIgnoreCase(tab);
			if (!top)
			{
				box.add(PanelTheme.header(slot.title, -1), BorderLayout.NORTH);
			}
			box.add(slot.component(), BorderLayout.CENTER);
			JPanel into = top ? topExtra : "places".equalsIgnoreCase(tab) ? placesExtra : "routes".equalsIgnoreCase(tab) ? routesExtra
				: named.computeIfAbsent(tab, t -> column());
			into.add(box);
			if (!top)
			{
				into.add(gap(PanelTheme.GAP));
			}
		}
		// An empty column takes no room, so a panel with no partner is laid out exactly as before.
		topExtra.setVisible(topExtra.getComponentCount() > 0);
		placesExtra.setVisible(placesExtra.getComponentCount() > 0);
		routesExtra.setVisible(routesExtra.getComponentCount() > 0);
		partnerPages.forEach(display::remove);
		partnerPages.clear();
		pageNames.subList(2, pageNames.size()).clear();
		for (Map.Entry<String, JPanel> page : named.entrySet())
		{
			display.add(page.getValue(), page.getKey());
			partnerPages.add(page.getValue());
			pageNames.add(page.getKey());
		}
		tabs.setNames(pageNames.stream().map(n -> n.equals("places") ? "Places" : n.equals("routes") ? "Routes" : n).toArray(String[]::new));
		tabs.choose(tabs.chosen());
	}

	/** Makes a button send an action to the plugins that handle it, with the payload made when it is pressed, or say what is missing. */
	void bindAction(AbstractButton button, String action, Supplier<Map<String, Object>> payload)
	{
		button.addActionListener(e ->
		{
			if (!partners.act(action, payload))
			{
				setStatus(Partners.INSTALL);
			}
		});
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
		title.setIcon(new ImageIcon(PanelIcon.create()));
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
				Point inRow = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row);
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
		bindAction(sharePlaces, Partners.ACTION_SHARE_PLACES, () -> partners.payload(new ArrayList<>(ticked), Collections.emptyList()));
		bindAction(importCode, Partners.ACTION_IMPORT, HashMap::new);
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
		if (partners.version() != shownPartners)
		{
			rebuildPartners();
		}
		if (partners.takeNotice())
		{
			setStatus(Partners.INSTALL);
		}
		if (pins.getVersion() != shownPins || routes.getVersion() != shownRoutes || partners.orderVersion() != shownOrder)
		{
			rebuildAll();
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
			shownOrder = partners.orderVersion();
			List<Pin> all = pins.all();
			ticked.removeIf(id -> pins.get(id) == null);
			rebuildTypeBox(all);
			rebuildPlaces(all);
			rebuildRoutes();
			updateTickedButtons();
			tabs.setCount(0, all.size());
			tabs.setCount(1, routes.all().size());
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

	private static Component gap(int height)
	{
		return Box.createRigidArea(new Dimension(0, height));
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
		bindAction(share, Partners.ACTION_SHARE_ROUTE, () -> partners.payload(route.getStops(), Collections.singletonList(route.getId())));
		buttons.add(share);
		if (count >= 3)
		{
			JButton fastest = small("Order");
			fastest.setToolTipText("Put the stops in the fastest order");
			bindAction(fastest, Partners.ACTION_ORDER, () -> partners.payload(route.getStops(), Collections.singletonList(route.getId())));
			buttons.add(fastest);
		}
		if (partners.undoable(route.getId(), route.getStops()))
		{
			JButton undo = small("Undo order");
			undo.setToolTipText("Put the stops back in the order they had before");
			bindAction(undo, Partners.ACTION_UNDO_ORDER, () -> partners.payload(Collections.emptyList(), Collections.singletonList(route.getId())));
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
		FontMetrics metrics = list.getFontMetrics(PanelTheme.SMALL_BOLD);
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
		JLabel index = PanelTheme.label(String.valueOf(number), PanelTheme.SMALL_BOLD, PanelTheme.ACCENT_DEEP);
		index.setHorizontalAlignment(SwingConstants.RIGHT);
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
				Point inRow = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row);
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

	/** Shows a line on the status line at the top, as a partner plugin asks. Interface thread. */
	public void showStatus(String text)
	{
		setStatus(text);
	}

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

	/** Shows the Routes page, for tests and pictures. */
	void selectRoutes()
	{
		tabs.choose(1);
	}

	/**
	 * Moves the panel from one theme to another: colours already on built pieces are mapped role by role, and the lists
	 * are built again in the new colours. The theme must already be in use ({@code Look.use}). Interface thread only.
	 */
	public void restyle(Palette from, Palette to)
	{
		Recolour.apply(this, from, to);
		rebuildAll();
		revalidate();
		repaint();
	}

	/** Brings the panel up to date as its timer does, for tests. */
	void refreshNow()
	{
		refreshIfChanged();
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
