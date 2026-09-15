package amidst.gtnh.worker;

import net.minecraft.world.ChunkPosition;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Random;

/** Surface queries shared by runtime and independent-seed samplers. */
interface SurfaceBiomeSampler {

    BiomeGenBase getBiomeAt(int x, int z);

    /** Prediction alone, bypassing loaded chunk data, for accuracy diagnostics. */
    default BiomeGenBase getPredictedBiomeAt(int x, int z) {
        return getBiomeAt(x, z);
    }

    default Prediction describePrediction(int x, int z) {
        return new Prediction(null, -1, -1, -1, getPredictedBiomeAt(x, z).biomeID);
    }

    default String predictionStats() {
        return "chunk-manager";
    }

    /** Diagnostic stages only; omitted from normal tile responses. */
    final class Prediction {
        final String realisticType;
        final int realisticId, baseId, riverId, predictedId;

        Prediction(String type, int realisticId, int baseId, int riverId, int predictedId) {
            this.realisticType = type;
            this.realisticId = realisticId;
            this.baseId = baseId;
            this.riverId = riverId;
            this.predictedId = predictedId;
        }
    }

    boolean isVillageLocationViable(int x, int z);

    float getApproximateSurfaceHeight(int x, int z);

    ChunkPosition findSpawnBiomePosition(Random random);

    default SpawnSearch.Surface getSpawnSurface(int x, int z) {
        throw new UnsupportedOperationException(
                "Surface replay is unavailable for this world generator");
    }
}
