package amidst.gtnh.vegetation;

import amidst.gtnh.worker.GtnhBiomeDescriptor;
import amidst.i18n.I18n;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldType;

import java.awt.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

/** A draft selection; only the caller applies a confirmed result to the map. */
public final class VegetationFilterPanel extends JPanel {
    private final VegetationCatalog catalog = VegetationCatalog.get();
    private final List<GtnhBiomeDescriptor> biomes;
    private final WorldType type;
    private final Dimension dimension;
    private final Set<String> selected = new LinkedHashSet<>();
    private final JTextField search = new JTextField(22);
    private final JComboBox<String> category = new JComboBox<>();
    private final JCheckBox all = new JCheckBox(I18n.text("Require all selected plants"));
    private final DefaultListModel<VegetationCatalog.Entry> plantModel = new DefaultListModel<>();
    private final JList<VegetationCatalog.Entry> plants = new JList<>(plantModel);
    private final JTextArea details = new JTextArea(5, 40);
    private final JLabel count = new JLabel();
    private final DefaultTableModel resultModel =
            new DefaultTableModel(new String[] {I18n.text("Biome"), "ID"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private boolean updating;
    private List<GtnhBiomeDescriptor> matches = List.of();

    public VegetationFilterPanel(
            List<GtnhBiomeDescriptor> biomes, WorldType type, Dimension dimension) {
        this(biomes, type, dimension, Selection.empty());
    }

    public VegetationFilterPanel(
            List<GtnhBiomeDescriptor> biomes,
            WorldType type,
            Dimension dimension,
            Selection initial) {
        super(new BorderLayout(8, 8));
        this.biomes = List.copyOf(biomes);
        this.type = type;
        this.dimension = dimension;
        selected.addAll(initial.plantIds());
        all.setSelected(initial.requireAll());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(
                new JLabel(
                        catalog.version()
                                + " · "
                                + catalog.generatorName(type)
                                + " · "
                                + I18n.text(dimension.getDisplayName())));
        top.add(Box.createVerticalStrut(8));
        top.add(
                new JLabel(
                        I18n.text(
                                "Pack defaults; eligible biomes, not plant locations. Soil, light"
                                        + " and chance still apply.")));
        top.add(
                new JLabel(
                        I18n.text(
                                "Modified pack configs are not read by this filter. ! means NOT; +"
                                        + " means AND; / means OR.")));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        controls.add(new JLabel(I18n.text("Search plants:")));
        controls.add(search);
        for (String c : List.of("All categories", "Flowers", "Gardens", "Fruit trees", "Trees"))
            category.addItem(c);
        category.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> l, Object v, int i, boolean s, boolean f) {
                        return super.getListCellRendererComponent(
                                l, I18n.text(String.valueOf(v)), i, s, f);
                    }
                });
        controls.add(category);
        JButton reset = new JButton(I18n.text("Clear plant selection"));
        reset.addActionListener(
                e -> {
                    selected.clear();
                    refreshPlants();
                });
        controls.add(reset);
        top.add(controls);
        top.add(all);
        for (Component component : top.getComponents()) {
            if (component instanceof JComponent child)
                child.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        add(top, BorderLayout.NORTH);
        plants.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        plants.setCellRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> l, Object v, int i, boolean s, boolean f) {
                        var e = (VegetationCatalog.Entry) v;
                        return super.getListCellRendererComponent(
                                l, (selected.contains(e.id()) ? "✓ " : "  ") + e, i, s, f);
                    }
                });
        JTable results = new JTable(resultModel);
        results.setAutoCreateRowSorter(true);
        results.getColumnModel().getColumn(0).setPreferredWidth(210);
        JSplitPane split =
                new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT,
                        new JScrollPane(plants),
                        new JScrollPane(results));
        split.setResizeWeight(0.52);
        split.setDividerLocation(400);
        split.setPreferredSize(new java.awt.Dimension(780, 290));
        add(split, BorderLayout.CENTER);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(count, BorderLayout.NORTH);
        bottom.add(new JScrollPane(details), BorderLayout.CENTER);
        bottom.add(
                new JLabel(
                        I18n.text(
                                "Applying automatically selects matching biomes and enables"
                                        + " highlight. The full biome list stays available.")),
                BorderLayout.SOUTH);
        plants.setToolTipText(
                I18n.text("Ctrl/Shift to select multiple plants. Search keeps hidden selections."));
        add(bottom, BorderLayout.SOUTH);
        plants.addListSelectionListener(
                e -> {
                    if (updating || e.getValueIsAdjusting()) return;
                    for (int i = 0; i < plantModel.size(); i++)
                        selected.remove(plantModel.get(i).id());
                    plants.getSelectedValuesList().forEach(p -> selected.add(p.id()));
                    refreshResults();
                });
        search.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                refreshPlants();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                refreshPlants();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                refreshPlants();
                            }
                        });
        category.addActionListener(e -> refreshPlants());
        all.addActionListener(e -> refreshResults());
        refreshPlants();
    }

    private void refreshPlants() {
        updating = true;
        try {
            plantModel.clear();
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            catalog.entries().stream()
                    .filter(
                            e ->
                                    category.getSelectedIndex() == 0
                                            || e.category().equals(category.getSelectedItem()))
                    .filter(e -> e.searchText().contains(query))
                    .sorted(Comparator.comparing(VegetationCatalog.Entry::name))
                    .forEach(plantModel::addElement);
            for (int i = 0; i < plantModel.size(); i++)
                if (selected.contains(plantModel.get(i).id())) plants.addSelectionInterval(i, i);
        } finally {
            updating = false;
        }
        refreshResults();
    }

    private void refreshResults() {
        matches = catalog.matching(biomes, selected, all.isSelected(), type, dimension);
        resultModel.setRowCount(0);
        // BOP may register raw and transplanted instances with the same name.
        // Keep every ID for map highlighting, but show one readable row per name.
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (var b : matches)
            rows.computeIfAbsent(b.name(), name -> new ArrayList<>()).add(Integer.toString(b.id()));
        rows.forEach(
                (name, ids) ->
                        resultModel.addRow(new Object[] {I18n.text(name), String.join(", ", ids)}));
        count.setText(
                I18n.format(
                        "{0} selected plants · {1} eligible biomes", selected.size(), rows.size()));
        details.setText(
                catalog.entries().stream()
                        .filter(e -> selected.contains(e.id()))
                        .map(e -> e.description(type, dimension))
                        .reduce((a, b) -> a + "\n\n" + b)
                        .orElse(I18n.text("Select vegetation to preview matching biomes.")));
        details.setCaretPosition(0);
        plants.repaint();
    }

    public List<Integer> matchingIds() {
        return matches.stream().map(GtnhBiomeDescriptor::id).toList();
    }

    public record Selection(List<String> plantIds, boolean requireAll) {
        public Selection {
            plantIds = List.copyOf(plantIds);
        }

        public static Selection empty() {
            return new Selection(List.of(), false);
        }
    }

    public Selection selection() {
        return new Selection(List.copyOf(selected), all.isSelected());
    }

    public record Result(boolean clear, List<Integer> biomeIds, Selection selection) {}

    public static Result show(
            Component parent,
            List<GtnhBiomeDescriptor> biomes,
            WorldType type,
            Dimension dimension,
            Selection initial) {
        var panel = new VegetationFilterPanel(biomes, type, dimension, initial);
        String[] options = {
            I18n.text("Apply highlight"), I18n.text("Clear highlight"), I18n.text("Cancel")
        };
        while (true) {
            int answer =
                    JOptionPane.showOptionDialog(
                            parent,
                            panel,
                            I18n.text("Filter biomes by vegetation"),
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            options,
                            options[0]);
            if (answer == 1) return new Result(true, List.of(), Selection.empty());
            if (answer != 0) return null;
            if (!panel.selected.isEmpty())
                return new Result(false, panel.matchingIds(), panel.selection());
            JOptionPane.showMessageDialog(parent, I18n.text("Select at least one plant."));
        }
    }
}
