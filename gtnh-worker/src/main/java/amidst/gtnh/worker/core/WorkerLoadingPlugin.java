package amidst.gtnh.worker.core;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/** Early bootstrap only; do not load the mod or Minecraft classes from here. */
@IFMLLoadingPlugin.Name("Amidst GTNH Paused Worker")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({ "amidst.gtnh.worker.core." })
@IFMLLoadingPlugin.SortingIndex(1001) // After FML's runtime deobfuscation (1000).
public final class WorkerLoadingPlugin implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "amidst.gtnh.worker.core.IntegratedServerTickTransformer" };
    }

    @Override
    public String getModContainerClass() { return null; }

    @Override
    public String getSetupClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() { return null; }
}
