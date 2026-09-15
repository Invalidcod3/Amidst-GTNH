package amidst.gtnh.worker;

import amidst.gtnh.validation.AccuracyReport;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.MapGenStructureData;
import net.minecraftforge.common.DimensionManager;

import java.util.*;
import java.util.function.IntBinaryOperator;

/** Read-only evidence from this particular loaded world; never persists rejection lists. */
final class AccuracyValidation {
    static final class Job {
        final AccuracyReport result = new AccuracyReport();
        final WorldServer world, overworld;
        final IntBinaryOperator predictedBiome, displayBiome;
        final ProspectingService prospecting;
        final java.util.function.IntFunction<WorldServer> worldLookup;
        List<RoguelikeDungeonPredictor.StructureDescriptor> structures;
        int index;

        Job(
                long seed,
                int dimension,
                int x,
                int z,
                int width,
                int height,
                int step,
                String category,
                String session,
                IntBinaryOperator predictedBiome,
                IntBinaryOperator displayBiome,
                ProspectingService prospecting) {
            this(
                    seed,
                    dimension,
                    x,
                    z,
                    width,
                    height,
                    step,
                    category,
                    session,
                    predictedBiome,
                    displayBiome,
                    prospecting,
                    DimensionManager::getWorld);
        }

        Job(
                long seed,
                int dimension,
                int x,
                int z,
                int width,
                int height,
                int step,
                String category,
                String session,
                IntBinaryOperator predictedBiome,
                IntBinaryOperator displayBiome,
                ProspectingService prospecting,
                java.util.function.IntFunction<WorldServer> worldLookup) {
            if (width < 1
                    || height < 1
                    || width > 512
                    || height > 512
                    || step < 1
                    || step > 16
                    || (long) x + width > 30000000
                    || (long) z + height > 30000000
                    || x < -30000000
                    || z < -30000000
                    || ((width + step - 1) / step) * (long) ((height + step - 1) / step) > 16384)
                throw new IllegalArgumentException(
                        "Validation area must be at most 512 x 512 blocks and 16384 samples");
            if (!Arrays.asList("BIOMES", "STRUCTURES", "ORES").contains(category))
                throw new IllegalArgumentException("Unknown validation category");
            this.worldLookup = worldLookup;
            overworld = worldLookup.apply(0);
            world = worldLookup.apply(dimension);
            if (overworld == null || overworld.getSeed() != seed || world == null)
                throw new IllegalStateException(
                        "Load the matching save and dimension before validation");
            this.predictedBiome = predictedBiome;
            this.displayBiome = displayBiome;
            this.prospecting = prospecting;
            result.seed = seed;
            result.dimension = dimension;
            result.x = x;
            result.z = z;
            result.width = width;
            result.height = height;
            result.step = step;
            result.category = category;
            result.worldSession = session;
            result.createdAt = System.currentTimeMillis();
        }

        void checkWorld() {
            if (worldLookup.apply(0) != overworld || worldLookup.apply(result.dimension) != world)
                throw new IllegalStateException("World changed during validation; run again");
        }

        boolean advance(long deadline) {
            checkWorld();
            if ("STRUCTURES".equals(result.category)) {
                if (structures == null)
                    throw new IllegalStateException("Missing structure predictions");
                while (index < structures.size()) {
                    verifyStructure(structures.get(index++));
                    if (System.nanoTime() >= deadline) return false;
                }
                if (index == structures.size()) {
                    addMissedStructureRecords();
                    index++;
                }
                return true;
            }
            int stride = "ORES".equals(result.category) ? 16 : result.step;
            int startX =
                    "ORES".equals(result.category) ? Math.floorDiv(result.x, 16) * 16 : result.x;
            int startZ =
                    "ORES".equals(result.category) ? Math.floorDiv(result.z, 16) * 16 : result.z;
            int cols = (result.x + result.width - startX + stride - 1) / stride;
            int rows = (result.z + result.height - startZ + stride - 1) / stride;
            do {
                if (index >= cols * rows) return true;
                int x = startX + (index % cols) * stride, z = startZ + (index / cols) * stride;
                index++;
                if ("ORES".equals(result.category)) {
                    if (inside(x + 8, z + 8))
                        prospecting.verifyOre(
                                result.seed, result.dimension, x / 16, z / 16, result);
                } else verifyBiome(x, z);
            } while (System.nanoTime() < deadline);
            return index >= cols * rows;
        }

        private void verifyBiome(int x, int z) {
            if (!world.blockExists(x, 0, z)) {
                result.add("BIOME", x, z, "", "", "UNVERIFIED", "Chunk is not loaded");
                return;
            }
            // getBiomeGenForCoords resolves unknown (255) entries using today's manager.
            // That would turn missing saved evidence into a misleading comparison.
            int raw =
                    world.getChunkFromBlockCoords(x, z).getBiomeArray()[((z & 15) << 4) | (x & 15)]
                            & 255;
            if (raw == 255) {
                result.add(
                        "BIOME", x, z, "", "", "UNVERIFIED", "Loaded chunk has no stored biome ID");
                return;
            }
            int predicted = predictedBiome.applyAsInt(x, z);
            int actual = displayBiome.applyAsInt(raw, 0);
            if (predicted < 0)
                result.add(
                        "BIOME",
                        x,
                        z,
                        "virtual terrain category",
                        String.valueOf(actual),
                        "UNVERIFIED",
                        "This preview classifies terrain; stored biome IDs cannot validate it");
            else
                result.add(
                        "BIOME",
                        x,
                        z,
                        String.valueOf(predicted),
                        String.valueOf(actual),
                        predicted == actual ? "MATCH" : "MISMATCH",
                        "Independent prediction vs loaded chunk biome ID");
        }

        private void verifyStructure(RoguelikeDungeonPredictor.StructureDescriptor s) {
            if (s.kind.contains("ORE")) return;
            String key = recordKey(s.kind);
            if (key == null) {
                String reason = "No reliable generated-structure record is exposed by this mod";
                if ("ROGUELIKE_DUNGEON".equals(s.kind))
                    reason = RoguelikeSiteCheck.check(world, s.x, s.z);
                result.add(s.kind, s.x, s.z, "candidate", "", "UNVERIFIED", reason);
                return;
            }
            NBTTagCompound records = records(key);
            String name =
                    MapGenStructureData.func_143042_b(
                            Math.floorDiv(s.x, 16), Math.floorDiv(s.z, 16));
            boolean present = records != null && records.hasKey(name, 10);
            // A generated start can precede block placement; the report checks starts only.
            if (present)
                result.add(
                        s.kind,
                        s.x,
                        s.z,
                        "start chunk",
                        "recorded start",
                        "MATCH",
                        "Saved structure start; not a block-by-block check");
            else if (records != null && populatedAround(s.x, s.z))
                result.add(
                        s.kind,
                        s.x,
                        s.z,
                        "start chunk",
                        "no recorded start",
                        "MISMATCH",
                        "Surrounding chunks are populated; no start in this save. Recheck after"
                                + " world changes");
            else
                result.add(
                        s.kind,
                        s.x,
                        s.z,
                        "start chunk",
                        "",
                        "UNVERIFIED",
                        "Generation record unavailable or surrounding chunks not populated");
        }

        private NBTTagCompound records(String key) {
            MapGenStructureData data =
                    (MapGenStructureData)
                            world.perWorldStorage.loadData(MapGenStructureData.class, key);
            return data == null ? null : data.func_143041_a();
        }

        private boolean populatedAround(int x, int z) {
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    int bx = (Math.floorDiv(x, 16) + dx) * 16,
                            bz = (Math.floorDiv(z, 16) + dz) * 16;
                    if (!world.blockExists(bx, 0, bz)
                            || !world.getChunkFromBlockCoords(bx, bz).isTerrainPopulated)
                        return false;
                }
            return true;
        }

        private boolean inside(int x, int z) {
            return x >= result.x
                    && z >= result.z
                    && x < result.x + result.width
                    && z < result.z + result.height;
        }

        private void addMissedStructureRecords() {
            // RWG invokes these three MapGen generators. Extra mod records use different rules.
            if (result.dimension != 0) return;
            for (String kind : Arrays.asList("VILLAGE", "MINESHAFT", "STRONGHOLD")) {
                NBTTagCompound records = records(recordKey(kind));
                if (records == null) continue;
                for (int cz = Math.floorDiv(result.z, 16);
                        cz <= Math.floorDiv(result.z + result.height - 1, 16);
                        cz++)
                    for (int cx = Math.floorDiv(result.x, 16);
                            cx <= Math.floorDiv(result.x + result.width - 1, 16);
                            cx++) {
                        if (!records.hasKey(MapGenStructureData.func_143042_b(cx, cz), 10))
                            continue;
                        int offset = "VILLAGE".equals(kind) ? 4 : "MINESHAFT".equals(kind) ? 8 : 0;
                        int x = cx * 16 + offset, z = cz * 16 + offset;
                        if (!inside(x, z)) continue;
                        boolean found = false;
                        for (RoguelikeDungeonPredictor.StructureDescriptor s : structures)
                            if (kind.equals(s.kind)
                                    && Math.floorDiv(s.x, 16) == cx
                                    && Math.floorDiv(s.z, 16) == cz) {
                                found = true;
                                break;
                            }
                        if (!found)
                            result.add(
                                    kind,
                                    x,
                                    z,
                                    "no predicted start",
                                    "recorded start",
                                    "MISMATCH",
                                    "Generated start missing from predictions");
                    }
            }
        }
    }

    static String recordKey(String kind) {
        switch (kind) {
            case "VILLAGE":
                return "Village";
            case "MINESHAFT":
                return "Mineshaft";
            case "STRONGHOLD":
                return "Stronghold";
            default:
                return null;
        }
    }
}
