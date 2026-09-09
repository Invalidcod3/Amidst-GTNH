package amidst.gtnh.worker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.minecraftforge.common.config.Configuration;
import cpw.mods.fml.relauncher.FMLInjectionData;

public class WorkerConfigTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private String previousOverride;
    private Field minecraftHomeField;
    private Object previousMinecraftHome;

    @Before
    public void isolateForgeEnvironment() throws Exception {
        // Forge Configuration expects the launcher to initialize this field.
        // Supply an isolated directory without booting Minecraft in unit tests.
        minecraftHomeField = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHomeField.setAccessible(true);
        previousMinecraftHome = minecraftHomeField.get(null);
        minecraftHomeField.set(null, temporary.getRoot());
        previousOverride = System.getProperty("gtnh.amidst.worker.enabled");
        System.clearProperty("gtnh.amidst.worker.enabled");
    }

    @After
    public void restoreForgeEnvironment() throws Exception {
        if (minecraftHomeField != null) {
            minecraftHomeField.set(null, previousMinecraftHome);
        }
        if (previousOverride == null) {
            System.clearProperty("gtnh.amidst.worker.enabled");
        } else {
            System.setProperty("gtnh.amidst.worker.enabled", previousOverride);
        }
    }

    @Test
    public void firstLaunchEnablesWorkerAndPersistsEnabledConfiguration() throws Exception {
        File file = new File(temporary.newFolder(), "amidstgtnhworker.cfg");
        assertFalse(file.exists());
        assertTrue(WorkerConfig.load(file).enabled);
        assertTrue(file.isFile());
        Configuration persisted = new Configuration(file);
        persisted.load();
        assertTrue(persisted.get("worker", "enabled", false).getBoolean());
    }

    @Test
    public void explicitlyDisabledExistingConfigurationIsRespected() throws Exception {
        File file = new File(temporary.newFolder(), "amidstgtnhworker.cfg");
        Configuration configured = new Configuration(file);
        configured.get("worker", "enabled", true).set(false);
        configured.save();
        assertFalse(WorkerConfig.load(file).enabled);
    }
}
