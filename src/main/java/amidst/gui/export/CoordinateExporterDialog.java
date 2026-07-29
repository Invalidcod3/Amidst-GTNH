package amidst.gui.export;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import amidst.gtnh.export.GtnhCoordinate;
import amidst.gtnh.export.GtnhCoordinateFiles;
import amidst.gtnh.export.GtnhCoordinateLocator;
import amidst.gtnh.export.GtnhCoordinateType;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

public final class CoordinateExporterDialog {
	private static final int DIRECT_IMPORT_LIMIT = 2_000;

	private final World world;
	private final JDialog dialog;
	private final JComboBox<Dimension> dimensionBox;
	private final JComboBox<GtnhCoordinateType> typeBox;
	private final JTextField x1Field;
	private final JTextField z1Field;
	private final JTextField x2Field;
	private final JTextField z2Field;
	private final JButton csvButton;
	private final JButton journeyMapButton;
	private final JButton importButton;
	private final JButton cancelButton;
	private final JProgressBar progressBar;
	private final JLabel statusLabel;

	private SwingWorker<Integer, Void> task;

	public CoordinateExporterDialog(
			Component parent,
			World world,
			CoordinatesInWorld firstCorner,
			CoordinatesInWorld secondCorner) {
		this.world = world;
		this.dialog = createDialog(parent);
		this.dimensionBox = new JComboBox<>(selectableDimensions());
		this.typeBox = new JComboBox<>();
		this.x1Field = coordinateField(firstCorner.getX());
		this.z1Field = coordinateField(firstCorner.getY());
		this.x2Field = coordinateField(secondCorner.getX());
		this.z2Field = coordinateField(secondCorner.getY());
		this.csvButton = new JButton("Export CSV");
		this.journeyMapButton = new JButton("Export JourneyMap");
		this.importButton = new JButton("Import into Game");
		this.cancelButton = new JButton("Cancel");
		this.progressBar = new JProgressBar();
		this.statusLabel = new JLabel("Range endpoints are inclusive Minecraft X/Z coordinates.");
		initialize();
	}

	public void show() {
		dialog.setVisible(true);
	}

	private void initialize() {
		dimensionBox.addActionListener(event -> refreshTypes());
		csvButton.addActionListener(event -> exportCsv());
		journeyMapButton.addActionListener(event -> exportJourneyMap());
		importButton.addActionListener(event -> importIntoGame());
		cancelButton.addActionListener(event -> cancelAndClose());
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				cancelAndClose();
			}
		});
		progressBar.setIndeterminate(true);
		progressBar.setVisible(false);
		refreshTypes();

		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.insets = new Insets(5, 7, 5, 7);
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1.0;
		addRow(form, constraints, 0, "Dimension:", dimensionBox);
		addRow(form, constraints, 1, "Coordinate type:", typeBox);
		addPositionRow(form, constraints, 2, "pos1:", x1Field, z1Field);
		addPositionRow(form, constraints, 3, "pos2:", x2Field, z2Field);

		JPanel state = new JPanel(new BorderLayout(8, 4));
		state.add(statusLabel, BorderLayout.CENTER);
		state.add(progressBar, BorderLayout.SOUTH);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(csvButton);
		buttons.add(journeyMapButton);
		buttons.add(importButton);
		buttons.add(cancelButton);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(form, BorderLayout.CENTER);
		content.add(state, BorderLayout.SOUTH);

		dialog.add(content, BorderLayout.CENTER);
		dialog.add(buttons, BorderLayout.SOUTH);
		dialog.getRootPane().setDefaultButton(csvButton);
		dialog.pack();
		dialog.setMinimumSize(dialog.getSize());
		dialog.setLocationRelativeTo(dialog.getOwner());
	}

	private void refreshTypes() {
		Dimension dimension = (Dimension) dimensionBox.getSelectedItem();
		List<GtnhCoordinateType> types = dimension == null
				? List.of()
				: GtnhCoordinateType.forDimension(dimension);
		typeBox.setModel(new DefaultComboBoxModel<>(
				types.toArray(GtnhCoordinateType[]::new)));
	}

	private void exportCsv() {
		Selection selection = readSelection();
		if (selection == null) {
			return;
		}
		Path file = chooseSaveFile("csv", "CSV coordinate file (*.csv)");
		if (file == null) {
			return;
		}
		runTask("Locating coordinates and writing CSV...", () -> {
			List<GtnhCoordinate> coordinates = locate(selection);
			prepareParent(file);
			GtnhCoordinateFiles.writeCsv(file, coordinates);
			return coordinates.size();
		}, count -> JOptionPane.showMessageDialog(
				dialog,
				"Exported " + count + " coordinates to:\n" + file.toAbsolutePath(),
				"Coordinate Export",
				JOptionPane.INFORMATION_MESSAGE));
	}

	private void exportJourneyMap() {
		Selection selection = readSelection();
		if (selection == null) {
			return;
		}
		Path file = chooseSaveFile("zip", "JourneyMap waypoint archive (*.zip)");
		if (file == null) {
			return;
		}
		runTask("Locating coordinates and writing JourneyMap archive...", () -> {
			List<GtnhCoordinate> coordinates = locate(selection);
			prepareParent(file);
			GtnhCoordinateFiles.writeJourneyMapZip(
					file,
					coordinates,
					selection.type,
					world.getGtnhDimensionId(selection.dimension));
			return coordinates.size();
		}, count -> JOptionPane.showMessageDialog(
				dialog,
				"Exported "
						+ count
						+ " JourneyMap waypoints.\n"
						+ "Extract the ZIP into the target world's JourneyMap waypoint directory:\n"
						+ file.toAbsolutePath(),
				"JourneyMap Export",
				JOptionPane.INFORMATION_MESSAGE));
	}

	private void importIntoGame() {
		Selection selection = readSelection();
		if (selection == null) {
			return;
		}
		if (!world.supportsJourneyMapImport()) {
			showError("Importing into the game requires a connected GTNH worker.");
			return;
		}
		runTask("Locating coordinates and importing them into JourneyMap...", () -> {
			List<GtnhCoordinate> coordinates = locate(selection);
			if (coordinates.size() > DIRECT_IMPORT_LIMIT) {
				throw new IllegalArgumentException(
						"Direct import found "
								+ coordinates.size()
								+ " waypoints; the per-operation limit is "
								+ DIRECT_IMPORT_LIMIT
								+ ". Reduce the range or export a JourneyMap ZIP.");
			}
			return world.importJourneyMapWaypoints(
					selection.dimension,
					GtnhCoordinateFiles.toWaypoints(coordinates, selection.type));
		}, count -> JOptionPane.showMessageDialog(
				dialog,
				"Imported " + count + " waypoints into JourneyMap.",
				"JourneyMap Import",
				JOptionPane.INFORMATION_MESSAGE));
	}

	private List<GtnhCoordinate> locate(Selection selection) {
		return GtnhCoordinateLocator.locate(
				world,
				selection.type,
				selection.x1,
				selection.z1,
				selection.x2,
				selection.z2);
	}

	private Selection readSelection() {
		Dimension dimension = (Dimension) dimensionBox.getSelectedItem();
		GtnhCoordinateType type = (GtnhCoordinateType) typeBox.getSelectedItem();
		if (dimension == null || type == null) {
			showError("Select a dimension and coordinate type.");
			return null;
		}
		try {
			return new Selection(
					dimension,
					type,
					parseCoordinate(x1Field, "pos1 X"),
					parseCoordinate(z1Field, "pos1 Z"),
					parseCoordinate(x2Field, "pos2 X"),
					parseCoordinate(z2Field, "pos2 Z"));
		} catch (IllegalArgumentException e) {
			showError(e.getMessage());
			return null;
		}
	}

	private Path chooseSaveFile(String extension, String description) {
		GtnhCoordinateType type = (GtnhCoordinateType) typeBox.getSelectedItem();
		Dimension dimension = (Dimension) dimensionBox.getSelectedItem();
		String baseName = safeName(dimension + "-" + type);
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(description);
		chooser.setAcceptAllFileFilterUsed(false);
		chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
		chooser.setSelectedFile(new java.io.File(baseName + "." + extension));
		if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) {
			return null;
		}
		Path file = ensureExtension(chooser.getSelectedFile().toPath(), extension);
		if (Files.exists(file)) {
			if (!Files.isRegularFile(file)) {
				showError("The selected path is not a regular file:\n" + file);
				return null;
			}
			int answer = JOptionPane.showConfirmDialog(
					dialog,
					"Replace the existing file?\n" + file.toAbsolutePath(),
					"Replace File",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE);
			if (answer != JOptionPane.YES_OPTION) {
				return null;
			}
		}
		return file;
	}

	private void runTask(
			String status,
			ExceptionalIntSupplier operation,
			java.util.function.IntConsumer onSuccess) {
		setBusy(true, status);
		task = new SwingWorker<>() {
			@Override
			protected Integer doInBackground() throws Exception {
				return operation.getAsInt();
			}

			@Override
			protected void done() {
				setBusy(false, "Range endpoints are inclusive Minecraft X/Z coordinates.");
				if (isCancelled()) {
					return;
				}
				try {
					onSuccess.accept(get());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					AmidstLogger.warn(cause);
					showError(cause.getMessage() == null
							? cause.getClass().getSimpleName()
							: cause.getMessage());
				}
			}
		};
		task.execute();
	}

	private void setBusy(boolean busy, String status) {
		dimensionBox.setEnabled(!busy);
		typeBox.setEnabled(!busy);
		x1Field.setEnabled(!busy);
		z1Field.setEnabled(!busy);
		x2Field.setEnabled(!busy);
		z2Field.setEnabled(!busy);
		csvButton.setEnabled(!busy);
		journeyMapButton.setEnabled(!busy);
		importButton.setEnabled(!busy);
		progressBar.setVisible(busy);
		statusLabel.setText(status);
		dialog.pack();
	}

	private void cancelAndClose() {
		if (task != null && !task.isDone()) {
			task.cancel(true);
		}
		dialog.dispose();
	}

	private static void prepareParent(Path file) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	private static int parseCoordinate(JTextField field, String label) {
		try {
			int value = Integer.parseInt(field.getText().trim());
			if (value < -30_000_000 || value > 30_000_000) {
				throw new IllegalArgumentException(
						label + " must be between -30,000,000 and 30,000,000.");
			}
			return value;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + " must be a whole number.");
		}
	}

	private static Path ensureExtension(Path file, String extension) {
		String text = file.toString();
		return text.toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)
				? file
				: Path.of(text + "." + extension);
	}

	private static String safeName(String value) {
		return value.toLowerCase(java.util.Locale.ROOT)
				.replaceAll("[^a-z0-9._-]+", "-")
				.replaceAll("(^-+|-+$)", "");
	}

	private static JTextField coordinateField(long value) {
		JTextField field = new JTextField(Long.toString(value), 12);
		field.setHorizontalAlignment(JTextField.RIGHT);
		return field;
	}

	private static Dimension[] selectableDimensions() {
		Dimension[] preferredOrder = {
				Dimension.OVERWORLD,
				Dimension.NETHER,
				Dimension.END,
				Dimension.MOON,
				Dimension.MARS,
				Dimension.ASTEROIDS,
				Dimension.CERES,
				Dimension.IO,
				Dimension.ENCELADUS,
				Dimension.PROTEUS,
				Dimension.PLUTO,
				Dimension.MEHEN_BELT,
				Dimension.ROSS_128B,
				Dimension.BARNARDA_C,
				Dimension.DEEP_DARK,
				Dimension.ANUBIS,
				Dimension.HORUS,
				Dimension.TWILIGHT_FOREST
		};
		return Arrays.stream(preferredOrder)
				.filter(dimension -> !GtnhCoordinateType.forDimension(dimension).isEmpty())
				.toArray(Dimension[]::new);
	}

	private static JDialog createDialog(Component parent) {
		Window owner = SwingUtilities.getWindowAncestor(parent);
		JDialog result = new JDialog(
				owner,
				"Export Coordinates",
				Dialog.ModalityType.APPLICATION_MODAL);
		result.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		result.setLayout(new BorderLayout());
		return result;
	}

	private static void addRow(
			JPanel panel,
			GridBagConstraints constraints,
			int row,
			String label,
			Component component) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.weightx = 0.0;
		panel.add(new JLabel(label), constraints);
		constraints.gridx = 1;
		constraints.gridwidth = 4;
		constraints.weightx = 1.0;
		panel.add(component, constraints);
		constraints.gridwidth = 1;
	}

	private static void addPositionRow(
			JPanel panel,
			GridBagConstraints constraints,
			int row,
			String label,
			JTextField xField,
			JTextField zField) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.weightx = 0.0;
		panel.add(new JLabel(label), constraints);
		constraints.gridx = 1;
		panel.add(new JLabel("["), constraints);
		constraints.gridx = 2;
		constraints.weightx = 1.0;
		panel.add(xField, constraints);
		constraints.gridx = 3;
		constraints.weightx = 0.0;
		panel.add(new JLabel("], ["), constraints);
		constraints.gridx = 4;
		constraints.weightx = 1.0;
		panel.add(zField, constraints);
		constraints.gridx = 5;
		constraints.weightx = 0.0;
		panel.add(new JLabel("]"), constraints);
	}

	private void showError(String message) {
		JOptionPane.showMessageDialog(
				dialog,
				message,
				"Coordinate Export Error",
				JOptionPane.ERROR_MESSAGE);
	}

	@FunctionalInterface
	private interface ExceptionalIntSupplier {
		int getAsInt() throws Exception;
	}

	private record Selection(
			Dimension dimension,
			GtnhCoordinateType type,
			int x1,
			int z1,
			int x2,
			int z2) {
	}
}
