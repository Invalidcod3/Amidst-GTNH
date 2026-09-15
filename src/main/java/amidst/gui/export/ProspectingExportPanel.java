package amidst.gui.export;

import java.awt.*;
import java.text.ParseException;
import javax.swing.*;
import amidst.gtnh.export.ProspectingExport;
import amidst.gtnh.prospecting.*;
import amidst.gtnh.prospecting.ProspectingData.*;
import amidst.i18n.I18n;

public final class ProspectingExportPanel extends JPanel {
    private final ProspectingFilterPanel filter;
    private final boolean fluid;
    private final JLabel limitLabel = new JLabel("Maximum coordinates");
    private final JLabel orderHint = new JLabel("Stops at the limit; scan order is not distance order.");
    final JSpinner minY = new JSpinner(new SpinnerNumberModel(0,0,255,1));
    final JSpinner maxY = new JSpinner(new SpinnerNumberModel(255,0,255,1));
    final JSpinner limit = new JSpinner(new SpinnerNumberModel(1000,1,10000,100));
    final JCheckBox recorded = new JCheckBox("Recorded veins only");
    final JCheckBox depleted = new JCheckBox("Include depleted veins");
    final JComboBox<String> points = new JComboBox<>(new String[]{"Best chunk per fluid field","All matching fluid chunks"});
    public ProspectingExportPanel(DimensionInfo dimension, String mode, String selectedId, int minimum) {
        super(new BorderLayout(4,4)); fluid = mode.equals("FLUID");
        filter = new ProspectingFilterPanel(dimension,fluid ? MarkerMode.FLUID : MarkerMode.ORES,selectedId,minimum);
        filter.setResetListener(() -> {
            minY.setValue(0); maxY.setValue(255); limit.setValue(1000); recorded.setSelected(false); depleted.setSelected(false); points.setSelectedIndex(0);
        });
        add(filter,BorderLayout.NORTH);
        JPanel options = new JPanel(new GridLayout(0,2,8,6));
        if (fluid) {
            points.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus) {
                    return super.getListCellRendererComponent(list,I18n.text(String.valueOf(value)),index,selected,focus);
                }
            });
            options.add(new JLabel("Fluid points")); options.add(points);
        } else {
            options.add(new JLabel("Minimum Y")); options.add(minY);
            options.add(new JLabel("Maximum Y")); options.add(maxY);
            options.add(recorded); options.add(depleted);
        }
        options.add(limitLabel); options.add(limit);
        add(options,BorderLayout.CENTER);
        add(orderHint,BorderLayout.SOUTH);
        I18n.localize(this);
    }
    public ProspectingExport.Options read() {
        try {
            QueryFilter query = new QueryFilter(); query.id = filter.selectedId(); query.minimumFluid = filter.minimumFluid();
            query.minY = ProspectingFilterPanel.readInteger(minY); query.maxY = ProspectingFilterPanel.readInteger(maxY);
            int maximum = ProspectingFilterPanel.readInteger(limit);
            if (query.minY > query.maxY) throw new IllegalArgumentException(I18n.text("Minimum Y must not exceed maximum Y."));
            query.recordedOnly = recorded.isSelected(); query.includeDepleted = depleted.isSelected();
            return new ProspectingExport.Options(query,fluid && points.getSelectedIndex()==1,maximum);
        } catch (ParseException e) { throw new IllegalArgumentException(I18n.text("Enter whole numbers within the allowed range."),e); }
    }
    public void setControlsEnabled(boolean enabled) { setEnabled(this,enabled); }
    public void setRadial(boolean radial) {
        limit.setVisible(!radial); limitLabel.setVisible(!radial); orderHint.setVisible(!radial);
    }
    private static void setEnabled(Container container,boolean enabled) {
        for (Component component : container.getComponents()) {
            component.setEnabled(enabled); if (component instanceof Container child) setEnabled(child,enabled);
        }
    }
}
