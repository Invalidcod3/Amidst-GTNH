package amidst.gtnh.worker.mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Replays GT5U ore-seed chunk selection and asks the loaded GT registry which
 * ore mix each placement attempt selects. Terrain and stone checks happen
 * later in world generation, so matching seed chunks are deliberately exposed
 * as possible rather than guaranteed veins.
 */
final class OreVeinPredictor {

    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int CHUNK_CENTER = 8;
    private static final int MAX_CANDIDATES = 262144;
    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private Object netherQuery;
    private Object ross128bQuery;
    private Method findRandom;
    private Field worldgenName;
    private int netherChance;
    private int ross128bChance;
    private int attempts;

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long worldSeed,
            int dimension,
            int minX,
            int minZ,
            int width,
            int height) {
        boolean nether = dimension == -1;
        boolean ross128b = dimension == SpaceDimensionSampler.ross128bDimensionId();
        if (!nether && !ross128b) {
            return new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        }
        prepareRuntimeRegistry();

        String target = nether
                ? "ore.mix.molybdenum"
                : "ore.mix.ross128.Roquesit";
        String kind = nether
                ? "NETHER_MOLYBDENUM_VEIN"
                : "ROSS_128B_ARSENOPYRITE_VEIN";
        Object query = nether ? netherQuery : ross128bQuery;
        int chance = nether ? netherChance : ross128bChance;
        boolean equalSpacing = equalSpacingPattern();

        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        int minChunkX = checkedInt(ceilingDiv((long) minX - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        int maxChunkX = checkedInt(Math.floorDiv(maxX - 1L - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        int minChunkZ = checkedInt(ceilingDiv((long) minZ - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        int maxChunkZ = checkedInt(Math.floorDiv(maxZ - 1L - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        long count = ((long) maxChunkX - minChunkX + 1L)
                * ((long) maxChunkZ - minChunkZ + 1L);
        if (count > MAX_CANDIDATES) {
            throw new IllegalArgumentException("structure query covers too many GT ore chunks");
        }

        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!isOreChunk(chunkX, chunkZ, equalSpacing)) {
                    continue;
                }
                long oreSeed = (worldSeed << 16)
                        ^ (((dimension & 0xffL) << 56)
                                | ((chunkX & 0xfffffffL) << 28)
                                | (chunkZ & 0xfffffffL));
                GtnhXstrRandom percentageRandom = new GtnhXstrRandom(oreSeed);
                if (percentageRandom.nextInt(100) >= chance
                        || !selectsTarget(query, oreSeed, target)) {
                    continue;
                }
                result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                        kind,
                        "GT ore seed chunk",
                        chunkX * BLOCKS_PER_CHUNK + CHUNK_CENTER,
                        chunkZ * BLOCKS_PER_CHUNK + CHUNK_CENTER,
                        "POSSIBLE"));
            }
        }
        return result;
    }

    private boolean selectsTarget(Object query, long oreSeed, String target) {
        try {
            for (int attempt = 0; attempt < attempts; attempt++) {
                long selectionSeed = hashStep(FNV_OFFSET_BASIS, oreSeed);
                selectionSeed = hashStep(selectionSeed, attempt);
                Object layer = findRandom.invoke(
                        query,
                        (Random) new GtnhXstrRandom(selectionSeed));
                if (layer != null && target.equals((String) worldgenName.get(layer))) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to select a GT ore mix", e);
        }
    }

    private void prepareRuntimeRegistry() {
        if (netherQuery != null) {
            return;
        }
        try {
            Class<?> queryClass = Class.forName("gregtech.common.worldgen.WorldgenQuery");
            Method veins = queryClass.getMethod("veins");
            Method inDimension = queryClass.getMethod("inDimension", String.class);
            findRandom = queryClass.getMethod("findRandom", Random.class);
            netherQuery = inDimension.invoke(veins.invoke(null), "Nether");
            ross128bQuery = inDimension.invoke(veins.invoke(null), "ross128b");

            Class<?> worldgenClass = Class.forName("gregtech.api.world.GTWorldgen");
            worldgenName = worldgenClass.getField("mWorldGenName");

            netherChance = dimensionChance("Nether");
            ross128bChance = dimensionChance("Ross128b");
            attempts = Class.forName("gregtech.api.enums.GTValues")
                    .getField("oreveinAttempts")
                    .getInt(null);
            if (attempts <= 0) {
                throw new IllegalStateException("GT ore vein attempt count is not positive");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GT ore vein registry is unavailable", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int dimensionChance(String enumName) throws ReflectiveOperationException {
        Class<? extends Enum> dimensionClass =
                (Class<? extends Enum>) Class.forName("galacticgreg.api.enums.DimensionDef");
        Object dimension = Enum.valueOf(dimensionClass, enumName);
        Object definition = dimensionClass.getField("modDimensionDef").get(dimension);
        return definition.getClass().getMethod("getOreVeinChance").invoke(definition) instanceof Integer
                ? ((Integer) definition.getClass()
                        .getMethod("getOreVeinChance")
                        .invoke(definition)).intValue()
                : 0;
    }

    private static boolean equalSpacingPattern() {
        try {
            Object pattern = Class.forName("gregtech.common.GTWorldgenerator")
                    .getMethod("getOregenPattern")
                    .invoke(null);
            return "EQUAL_SPACING".equals(String.valueOf(pattern));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GT ore generation pattern is unavailable", e);
        }
    }

    private static boolean isOreChunk(int chunkX, int chunkZ, boolean equalSpacing) {
        if (equalSpacing) {
            return Math.floorMod(chunkX, 3) == 1 && Math.floorMod(chunkZ, 3) == 1;
        }
        return Math.abs(chunkX) % 3 == 1 && Math.abs(chunkZ) % 3 == 1;
    }

    private static long hashStep(long state, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            state = hashByte(state, (byte) ((value >> shift) & 0xff));
        }
        return state;
    }

    private static long hashStep(long state, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            state = hashByte(state, (byte) ((value >> shift) & 0xff));
        }
        return state;
    }

    private static long hashByte(long state, byte value) {
        return (state ^ value) * FNV_PRIME;
    }

    private static long ceilingDiv(long value, long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static int checkedInt(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "GT ore query coordinate is outside supported range");
        }
        return (int) value;
    }
}
