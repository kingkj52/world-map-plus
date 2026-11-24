package com.worldmapplus.panel;

import com.worldmapplus.pins.Pin;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.pins.SavedRoute;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * A small window for making or changing a route: its name, its stops in order, and whether it loops.
 * Stops are saved places. Nothing here touches the game.
 */
final class RouteEditor
{
	private RouteEditor()
	{
	}

	/**
	 * Shows the editor and saves the result into the route store when the player presses Save.
	 *
	 * @param existing the route being changed, or null to make a new one
	 * @param startingStops the stops a new route begins with
	 */
	static void show(Component parent, PinStore pins, RouteStore routes, @Nullable SavedRoute existing,
		List<Long> startingStops)
	{
		java.awt.Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		JDialog dialog = new JDialog(owner, existing == null ? "New route" : "Edit route",
			java.awt.Dialog.ModalityType.APPLICATION_MODAL);

		JTextField name = new JTextField(existing == null ? "New route" : existing.getName(), 18);
		JCheckBox loop = new JCheckBox("Loop: start again after the last stop", existing != null && existing.isLoop());

		DefaultListModel<Pin> model = new DefaultListModel<>();
		List<Long> ids = existing == null ? startingStops : existing.getStops();
		for (long id : ids)
		{
			Pin pin = pins.get(id);
			if (pin != null)
			{
				model.addElement(pin);
			}
		}
		JList<Pin> list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer((l, pin, index, selected, focus) ->
		{
			JLabel label = PlacesPanel.plainLabel((index + 1) + ".  " + pin.getName() + "  ("
				+ PlaceList.typeLabel(pin.getKind()) + ")");
			label.setOpaque(true);
			label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			label.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
			label.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
			return label;
		});

		JButton up = new JButton("Up");
		JButton down = new JButton("Down");
		JButton remove = new JButton("Remove");
		up.addActionListener(e ->
		{
			int i = list.getSelectedIndex();
			if (i > 0)
			{
				Pin pin = model.remove(i);
				model.add(i - 1, pin);
				list.setSelectedIndex(i - 1);
			}
		});
		down.addActionListener(e ->
		{
			int i = list.getSelectedIndex();
			if (i >= 0 && i < model.size() - 1)
			{
				Pin pin = model.remove(i);
				model.add(i + 1, pin);
				list.setSelectedIndex(i + 1);
			}
		});
		remove.addActionListener(e ->
		{
			int i = list.getSelectedIndex();
			if (i >= 0)
			{
				model.remove(i);
				list.setSelectedIndex(Math.min(i, model.size() - 1));
			}
		});
		JPanel order = new JPanel(new GridLayout(1, 3, 4, 0));
		order.add(up);
		order.add(down);
		order.add(remove);

		JComboBox<Pin> choices = new JComboBox<>(pins.all().toArray(new Pin[0]));
		choices.setRenderer((l, pin, index, selected, focus) ->
		{
			JLabel label = PlacesPanel.plainLabel(pin == null ? "" : pin.getName());
			label.setOpaque(true);
			label.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
			label.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
			return label;
		});
		JButton add = new JButton("Add stop");
		add.addActionListener(e ->
		{
			Pin pin = (Pin) choices.getSelectedItem();
			if (pin != null && model.size() < RouteStore.MAX_STOPS)
			{
				model.addElement(pin);
				list.setSelectedIndex(model.size() - 1);
			}
		});
		JPanel adder = new JPanel(new BorderLayout(4, 0));
		adder.add(choices, BorderLayout.CENTER);
		adder.add(add, BorderLayout.EAST);

		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.add(name, BorderLayout.NORTH);
		top.add(loop, BorderLayout.SOUTH);

		JPanel middle = new JPanel(new BorderLayout(0, 4));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new java.awt.Dimension(320, 190));
		middle.add(scroll, BorderLayout.CENTER);
		JPanel under = new JPanel(new GridLayout(2, 1, 0, 4));
		under.add(order);
		under.add(adder);
		middle.add(under, BorderLayout.SOUTH);

		JButton save = new JButton("Save");
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dialog.dispose());
		save.addActionListener(e ->
		{
			List<Long> stops = new ArrayList<>();
			for (int i = 0; i < model.size(); i++)
			{
				stops.add(model.get(i).getId());
			}
			if (stops.isEmpty())
			{
				JOptionPane.showMessageDialog(dialog, "Add at least one saved place to the route.");
				return;
			}
			boolean saved = existing == null
				? routes.create(name.getText(), stops, loop.isSelected()) != null
				: routes.update(existing.getId(), name.getText(), stops, loop.isSelected());
			if (!saved)
			{
				JOptionPane.showMessageDialog(dialog, "The route could not be saved. The list of routes may be full.");
				return;
			}
			dialog.dispose();
		});
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		buttons.add(cancel);
		buttons.add(save);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(top, BorderLayout.NORTH);
		content.add(middle, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}
}
