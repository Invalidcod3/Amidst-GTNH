package amidst.gtnh.prospecting;

import java.awt.*;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import amidst.AmidstSettings;
import amidst.gtnh.prospecting.ProspectingData.*;

/** Registry-backed choices. A cancelled dialog never changes the active filter. */
public final class ProspectingFilterPanel extends JPanel {
    private record Choice(String id, String label, String materials) {
        @Override public String toString() { return id.isEmpty() ? amidst.i18n.I18n.text(label) : label; }
    }
    final JComboBox<Choice> selection = new JComboBox<>();
    final JSpinner minimum;

    public ProspectingFilterPanel(DimensionInfo dimension, MarkerMode mode, AmidstSettings settings) {
        this(dimension,mode,settings.prospectingFilter.get(),settings.prospectingMinimumFluid.get());
    }
    public ProspectingFilterPanel(DimensionInfo dimension, MarkerMode mode, String selectedId, int minimumFluid) {
        super(new GridBagLayout());
        boolean fluid = mode == MarkerMode.FLUID;
        List<FilterOption> options = dimension == null ? List.of()
                : fluid ? dimension.fluidOptions : dimension.oreOptions;
        Map<String, FilterOption> byId = new LinkedHashMap<>();
        if (options != null) for (FilterOption option : options) {
            if (option != null && option.id != null && !option.id.isEmpty() && option.name != null)
                byId.putIfAbsent(option.id, option);
        }
        Map<String, Integer> names = new HashMap<>();
        byId.values().forEach(o -> names.merge(o.name, 1, Integer::sum));
        selection.addItem(new Choice("", fluid ? "All fluids" : "All ore veins", ""));
        byId.values().stream().sorted(Comparator.comparing((FilterOption o) -> o.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(o -> o.id)).forEach(o -> selection.addItem(new Choice(o.id,
                        o.name + (o.kind == null ? "" : " · " + ProspectingLabels.kind(o.kind))
                                + (names.get(o.name) > 1 && o.kind == null ? " [" + o.id + "]" : ""), o.materials)));
        selection.setEditable(false);
        selection.setMaximumRowCount(16);
        selection.setPreferredSize(new Dimension(360, selection.getPreferredSize().height));
        for (int i = 0; i < selection.getItemCount(); i++) {
            if (selection.getItemAt(i).id.equals(selectedId)) selection.setSelectedIndex(i);
        }
        selection.addActionListener(e -> selection.setToolTipText(((Choice) selection.getSelectedItem()).materials));
        selection.setToolTipText(((Choice) selection.getSelectedItem()).materials);
        minimum = fluid ? new JSpinner(new SpinnerNumberModel(minimumFluid,
                0, Integer.MAX_VALUE, 10)) : null;
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; add(new JLabel(fluid ? "Fluid" : "Ore vein"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; add(selection, c);
        if (fluid) {
            c.gridx = 0; c.gridy++; c.weightx = 0; add(new JLabel("Minimum L/Op"), c);
            c.gridx = 1; add(minimum, c);
            c.gridx = 0; c.gridy++; c.gridwidth = 2;
            add(new JLabel("Hide chunks below this production. 0 = no minimum."), c);
        }
        JButton reset = new JButton("Reset filters");
        reset.addActionListener(e -> { selection.setSelectedIndex(0); if (minimum != null) minimum.setValue(0); });
        c.gridx = 0; c.gridy++; c.gridwidth = 2; c.fill = GridBagConstraints.NONE; add(reset, c);
        amidst.i18n.I18n.localize(this);
    }
    public String selectedId() { return ((Choice) selection.getSelectedItem()).id; }
    public int minimumFluid() throws ParseException { return minimum == null ? 0 : readInteger(minimum); }
    public static int readInteger(JSpinner spinner) throws ParseException {
        JFormattedTextField field = ((JSpinner.DefaultEditor)spinner.getEditor()).getTextField();
        String text = field.getText().trim();
        java.text.ParsePosition position = new java.text.ParsePosition(0);
        var formatter = (javax.swing.text.InternationalFormatter)field.getFormatter();
        Object parsed = formatter.getFormat().parseObject(text,position);
        if (text.isEmpty() || position.getIndex() != text.length() || position.getErrorIndex() >= 0
                || !(parsed instanceof Number number) || number.doubleValue() != number.intValue())
            throw new ParseException("Enter a whole number",position.getIndex());
        spinner.commitEdit(); return (Integer)spinner.getValue();
    }

    public void apply(AmidstSettings settings) throws ParseException {
        int value = minimumFluid();
        settings.prospectingFilter.set(((Choice) selection.getSelectedItem()).id);
        if (minimum != null) settings.prospectingMinimumFluid.set(value);
    }
    public void setResetListener(Runnable listener) {
        for (Component component : getComponents()) if (component instanceof JButton button) button.addActionListener(e -> listener.run());
    }

    public static void show(Component parent, DimensionInfo dimension, MarkerMode mode, AmidstSettings settings) {
        ProspectingFilterPanel panel = new ProspectingFilterPanel(dimension, mode, settings);
        while (JOptionPane.showConfirmDialog(parent, panel, "Filter " + mode,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try { panel.apply(settings); return; }
            catch (ParseException e) {
                JOptionPane.showMessageDialog(parent, amidst.i18n.I18n.text("Enter whole numbers within the allowed range."),
                        "Invalid minimum", JOptionPane.WARNING_MESSAGE);
            }
        }
    }
}
