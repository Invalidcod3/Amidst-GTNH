package amidst.gtnh.worker;

import net.minecraft.world.biome.BiomeGenBase;
import cpw.mods.fml.common.Loader;

/** Final biome replacements in GTNH ChunkProviderRoss128b.provideChunk. */
final class Ross128bBiomeRules {
    private final int taintId, magicalForestId;
    private final boolean disableMagicalForest;

    Ross128bBiomeRules(int taintId, int magicalForestId, boolean disableMagicalForest) {
        this.taintId = taintId;
        this.magicalForestId = magicalForestId;
        this.disableMagicalForest = disableMagicalForest;
    }

    static Ross128bBiomeRules fromRuntime() {
        if (!Loader.isModLoaded("Thaumcraft")) return new Ross128bBiomeRules(-1, -1, false);
        try {
            Class<?> generator = Class.forName("thaumcraft.common.lib.world.ThaumcraftWorldGenerator");
            BiomeGenBase taint = (BiomeGenBase) generator.getField("biomeTaint").get(null);
            BiomeGenBase magical = (BiomeGenBase) generator.getField("biomeMagicalForest").get(null);
            Object config = Class.forName("bartworks.common.configs.Configuration")
                    .getField("crossModInteractions").get(null);
            boolean disabled = config.getClass().getField("disableMagicalForest").getBoolean(config);
            return new Ross128bBiomeRules(taint.biomeID, magical.biomeID, disabled);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Ross 128b final biome rules are unavailable", e);
        }
    }

    BiomeGenBase apply(BiomeGenBase original) {
        int id = original.biomeID;
        BiomeGenBase result = original;
        if (id == BiomeGenBase.mushroomIsland.biomeID) result = BiomeGenBase.taiga;
        else if (id == BiomeGenBase.mushroomIslandShore.biomeID) result = BiomeGenBase.stoneBeach;
        // The generator checks Thaumcraft against the ORIGINAL ID, after the
        // vanilla substitutions. Preserve this order even for unusual ID collisions.
        if (id == taintId) result = BiomeGenBase.taiga;
        else if (disableMagicalForest && id == magicalForestId) result = BiomeGenBase.birchForest;
        return result;
    }
}
