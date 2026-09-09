package amidst.gui.export;

import static org.junit.Assert.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import amidst.i18n.*;
import amidst.gtnh.export.GtnhCoordinateType;
import amidst.gtnh.prospecting.ProspectingData.*;
import amidst.mojangapi.world.Dimension;

public class ChineseExportPanelTest {
    @Test public void languageSwitchPreservesIdsValuesAndUserTextAndRendersBothForms() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                I18n.setLanguage(Language.SIMPLIFIED_CHINESE);
                for (Dimension d : Dimension.values()) assertNotEquals(d.getDisplayName(),d.toString());
                for (var t : GtnhCoordinateType.all()) assertNotEquals(t.getDisplayName(),t.toString());
                DimensionInfo dimension = new DimensionInfo(); FilterOption option=new FilterOption(); option.id="oil"; option.name="原油";
                dimension.fluidOptions=List.of(option); dimension.oreOptions=List.of(option);
                for (String mode : List.of("ORES","FLUID")) {
                    var panel = new ProspectingExportPanel(dimension,mode,"oil",150);
                    assertEquals("oil",panel.read().filter().id); assertEquals(1000,panel.read().limit());
                    panel.setSize(panel.getPreferredSize()); layout(panel);
                    BufferedImage image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g=image.createGraphics(); panel.printAll(g); g.dispose();
                    Path output=Path.of("build/reports/i18n"); Files.createDirectories(output);
                    ImageIO.write(image,"png",output.resolve(mode.toLowerCase()+"-zh.png").toFile());
                    ((JSpinner.DefaultEditor)panel.limit.getEditor()).getTextField().setText("10.5");
                    assertThrows(IllegalArgumentException.class,panel::read);
                }
                JPanel panel=new JPanel(); JButton button=new JButton("Export CSV"); JTextField input=new JTextField("World");
                panel.add(button); panel.add(input); I18n.localize(panel);
                assertEquals("导出 CSV",button.getText()); assertEquals("World",input.getText());
                I18n.setLanguage(Language.ENGLISH); I18n.localize(panel);
                assertEquals("Export CSV",button.getText());
                assertTrue(I18n.format("Exported {0} JourneyMap waypoints. Extract the ZIP into the target world's waypoint directory:\n{1}",3,"C:/map.zip").contains("world's"));
            } catch (Exception e) { throw new AssertionError(e); }
            finally { I18n.setLanguage(Language.ENGLISH); }
        });
    }
    private static void layout(Container container) { container.doLayout(); for(Component child:container.getComponents()) if(child instanceof Container nested)layout(nested); }
}
