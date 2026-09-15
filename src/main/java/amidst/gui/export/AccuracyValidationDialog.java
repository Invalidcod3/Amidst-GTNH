package amidst.gui.export;

import amidst.gtnh.validation.AccuracyReport;
import amidst.gtnh.validation.AccuracyReportFiles;
import amidst.gui.main.viewer.ViewerFacade;
import amidst.i18n.I18n;
import amidst.mojangapi.world.Dimension;

import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** User-selected, bounded verification of the currently loaded save. */
public final class AccuracyValidationDialog {
    private final ViewerFacade viewer;
    private final JDialog dialog;
    private final JTextField x = new JTextField(9), z = new JTextField(9);
    private final JSpinner width = new JSpinner(new SpinnerNumberModel(128, 1, 512, 16));
    private final JSpinner height = new JSpinner(new SpinnerNumberModel(128, 1, 512, 16));
    private final JSpinner step = new JSpinner(new SpinnerNumberModel(4, 1, 16, 1));
    private final JCheckBox biomes = new JCheckBox("Biomes", true),
            structures = new JCheckBox("Structures", true),
            ores = new JCheckBox("Ores", true);
    private final JButton run = new JButton("Start validation"),
            json = new JButton("Save JSON report"),
            csv = new JButton("Save CSV report");
    private final JLabel status =
            new JLabel(
                    "Validation reads loaded chunks and generation records. Unavailable evidence is"
                            + " not a pass.");
    private final DefaultTableModel summary =
            table("Category", "Matched", "Differences", "Unverified", "Accuracy");
    private final DefaultTableModel details =
            table("Category", "Kind", "X", "Z", "Predicted", "Recorded", "Result", "Explanation");
    private final JPanel form = new JPanel(new GridLayout(0, 2, 8, 6));
    private List<AccuracyReport> reports = List.of();
    private SwingWorker<List<AccuracyReport>, String> task;
    private final Dimension dimension;

    public AccuracyValidationDialog(ViewerFacade viewer) {
        this.viewer = viewer;
        dimension = viewer.getSettings().dimension.get();
        dialog =
                new JDialog(
                        SwingUtilities.getWindowAncestor(viewer.getComponent()),
                        I18n.text("Accuracy validation"),
                        Dialog.ModalityType.MODELESS);
        var corner = viewer.getVisibleTopLeft();
        x.setText(Long.toString(corner.getX()));
        z.setText(Long.toString(corner.getY()));
        row("Dimension:", new JLabel(dimension.toString()));
        row("Start X:", x);
        row("Start Z:", z);
        row("Width (blocks):", width);
        row("Height (blocks):", height);
        row("Biome sample spacing:", step);
        JPanel categories = new JPanel(new FlowLayout(FlowLayout.LEFT));
        categories.add(biomes);
        categories.add(structures);
        categories.add(ores);
        row("Validate:", categories);
        JTable detailTable = new JTable(details);
        detailTable.setAutoCreateRowSorter(true);
        detailTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < details.getColumnCount(); i++)
            detailTable
                    .getColumnModel()
                    .getColumn(i)
                    .setPreferredWidth(i == 7 ? 580 : i == 4 || i == 5 ? 150 : 90);
        JPanel output = new JPanel(new BorderLayout(8, 8));
        JTable totals = new JTable(summary);
        totals.setPreferredScrollableViewportSize(new java.awt.Dimension(800, 72));
        output.add(new JScrollPane(totals), BorderLayout.NORTH);
        output.add(new JScrollPane(detailTable), BorderLayout.CENTER);
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(form, BorderLayout.CENTER);
        top.add(status, BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(run);
        buttons.add(json);
        buttons.add(csv);
        JButton cancel = new JButton("Cancel / Close");
        buttons.add(cancel);
        JPanel body = new JPanel(new BorderLayout(10, 10));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        body.add(top, BorderLayout.NORTH);
        body.add(output, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        json.setEnabled(false);
        csv.setEnabled(false);
        run.addActionListener(e -> start());
        json.addActionListener(e -> save(false));
        csv.addActionListener(e -> save(true));
        cancel.addActionListener(e -> close());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        close();
                    }
                });
        I18n.localize(dialog);
        dialog.setSize(1050, 700);
        dialog.setLocationRelativeTo(viewer.getComponent());
    }

    private static DefaultTableModel table(String... names) {
        return new DefaultTableModel(java.util.Arrays.stream(names).map(I18n::text).toArray(), 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
    }

    private void row(String label, JComponent field) {
        form.add(new JLabel(label));
        form.add(field);
    }

    public void show() {
        dialog.setVisible(true);
    }

    private void close() {
        if (task != null) task.cancel(true);
        dialog.dispose();
    }

    private void start() {
        if (task != null && !task.isDone()) return;
        try {
            int bx = Integer.parseInt(x.getText().trim()),
                    bz = Integer.parseInt(z.getText().trim());
            width.commitEdit();
            height.commitEdit();
            step.commitEdit();
            int w = (Integer) width.getValue(),
                    h = (Integer) height.getValue(),
                    s = (Integer) step.getValue();
            if (bx < -30000000
                    || bz < -30000000
                    || (long) bx + w > 30000000
                    || (long) bz + h > 30000000
                    || ((w + s - 1) / s) * (long) ((h + s - 1) / s) > 16384)
                throw new IllegalArgumentException(
                        I18n.text("Area exceeds world bounds or 16384 biome samples."));
            List<String> categories = new ArrayList<>();
            if (biomes.isSelected()) categories.add("BIOMES");
            if (structures.isSelected()) categories.add("STRUCTURES");
            if (ores.isSelected()) categories.add("ORES");
            if (categories.isEmpty())
                throw new IllegalArgumentException(I18n.text("Select at least one category."));
            reports = List.of();
            summary.setRowCount(0);
            details.setRowCount(0);
            busy(true);
            task =
                    new SwingWorker<>() {
                        @Override
                        protected List<AccuracyReport> doInBackground() throws Exception {
                            List<AccuracyReport> result = new ArrayList<>();
                            String session = null;
                            for (String category : categories) {
                                if (isCancelled())
                                    throw new java.util.concurrent.CancellationException();
                                publish(category);
                                var report =
                                        viewer.getWorld()
                                                .validateAccuracy(
                                                        dimension, bx, bz, w, h, s, category,
                                                        session);
                                session = report.worldSession;
                                result.add(report);
                            }
                            return result;
                        }

                        @Override
                        protected void process(List<String> values) {
                            status.setText(
                                    I18n.text("Validating:")
                                            + " "
                                            + category(values.get(values.size() - 1)));
                        }

                        @Override
                        protected void done() {
                            if (!dialog.isDisplayable()) return;
                            busy(false);
                            try {
                                reports = get();
                                render();
                                json.setEnabled(true);
                                csv.setEnabled(true);
                            } catch (java.util.concurrent.CancellationException ignored) {
                                status.setText(I18n.text("Cancelled"));
                            } catch (Exception e) {
                                Throwable cause = e.getCause() == null ? e : e.getCause();
                                status.setText(
                                        I18n.text("Validation failed")
                                                + ": "
                                                + I18n.text(cause.getMessage()));
                            }
                        }
                    };
            task.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    dialog,
                    I18n.text(e.getMessage()),
                    I18n.text("Invalid input"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void busy(boolean busy) {
        run.setEnabled(!busy);
        json.setEnabled(false);
        csv.setEnabled(false);
        enable(form, !busy);
    }

    private static void enable(Container c, boolean value) {
        for (Component child : c.getComponents()) {
            child.setEnabled(value);
            if (child instanceof Container nested) enable(nested, value);
        }
    }

    private static String category(String key) {
        return I18n.text(
                switch (key) {
                    case "BIOMES" -> "Biomes";
                    case "ORES" -> "Ores";
                    default -> "Structures";
                });
    }

    private void render() {
        int omitted = 0;
        for (var r : reports) {
            long verified = (long) r.matched + r.mismatched;
            summary.addRow(
                    new Object[] {
                        category(r.category),
                        r.matched,
                        r.mismatched,
                        r.unverified,
                        verified == 0
                                ? "—"
                                : String.format(
                                        java.util.Locale.ROOT,
                                        "%.2f%%",
                                        100.0 * r.matched / verified)
                    });
            for (var e : r.details)
                details.addRow(
                        new Object[] {
                            category(r.category),
                            e.kind,
                            e.x,
                            e.z,
                            e.predicted,
                            e.actual,
                            I18n.text(e.status),
                            I18n.text(e.reason)
                        });
            omitted += r.omittedDetails;
        }
        status.setText(
                I18n.format(
                        "Validation complete. {0} detail rows omitted; totals include all samples."
                                + " Results apply to this save at this time.",
                        omitted));
    }

    private void save(boolean asCsv) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(
                new java.io.File(asCsv ? "accuracy-report.csv" : "accuracy-report.json"));
        if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path)
                && JOptionPane.showConfirmDialog(
                                dialog,
                                I18n.text("Replace existing file?"),
                                I18n.text("Save report"),
                                JOptionPane.YES_NO_OPTION)
                        != JOptionPane.YES_OPTION) return;
        try {
            String contents =
                    asCsv ? AccuracyReportFiles.csv(reports) : AccuracyReportFiles.json(reports);
            Files.writeString(path, contents, StandardCharsets.UTF_8);
            status.setText(I18n.text("Report saved") + ": " + path);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    dialog, e.getMessage(), I18n.text("Save failed"), JOptionPane.ERROR_MESSAGE);
        }
    }
}
