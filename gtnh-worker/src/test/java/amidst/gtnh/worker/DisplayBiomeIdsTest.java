package amidst.gtnh.worker;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.*;

public class DisplayBiomeIdsTest {
    @Test
    public void entireExtendedRegistryAndAllDisplayScopesAreDisjoint() {
        BitSet occupied = new BitSet();
        occupied.set(0, 65536);
        for (int id :
                new int[] {
                    EndStructurePredictor.END_VOID_BIOME,
                    EndStructurePredictor.CENTRAL_END_BIOME,
                    EndStructurePredictor.INFESTED_FOREST_BIOME,
                    EndStructurePredictor.BURNING_MOUNTAINS_BIOME,
                    EndStructurePredictor.ENCHANTED_ISLAND_BIOME,
                    SpaceDimensionSampler.CERES_DISPLAY_BIOME,
                    SpaceDimensionSampler.ENCELADUS_DISPLAY_BIOME,
                    SpaceDimensionSampler.PROTEUS_DISPLAY_BIOME
                }) reserve(occupied, id);
        for (String key : SpaceDimensionSampler.scopedBiomeDimensionKeys())
            for (int raw = 0; raw < 65536; raw++)
                reserve(occupied, SpaceDimensionSampler.displayBiomeId(key, raw));
        for (String key : AdditionalDimensionBiomes.keys())
            for (int raw = 0; raw < 65536; raw++)
                reserve(occupied, AdditionalDimensionBiomes.displayId(key, raw));
        for (int raw : new int[] {0, 255, 256, 300, 329, 32768, 65534, 65535})
            assertEquals(raw, SpaceDimensionSampler.displayBiomeId("Overworld", raw));
        for (int invalid : new int[] {-1, 65536, Integer.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SpaceDimensionSampler.displayBiomeId("bartworks:ross128b", invalid));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AdditionalDimensionBiomes.displayId("Everglades", invalid));
        }
    }

    @Test
    public void helloCatalogPreservesExtendedRegistryAndDimensionCopies() throws Exception {
        // The Forge test JVM lacks EndlessIDs. Supply its 65,536-entry registry to the
        // actual hello catalog builder, including IDs formerly occupied by display biomes.
        BiomeGenBase[] registry = new BiomeGenBase[65536];
        BiomeGenBase original = BiomeGenBase.getBiomeGenArray()[254];
        Field idField = BiomeGenBase.class.getField("biomeID");
        idField.setAccessible(true);
        // EndlessIDs also expands Forge's biome dictionary. Reproduce that storage here,
        // without loading game mixins or changing the production descriptor path.
        Field dictionaryField = BiomeDictionary.class.getDeclaredField("biomeList");
        dictionaryField.setAccessible(true);
        Object[] originalDictionary = (Object[]) dictionaryField.get(null);
        Object[] dictionary = Arrays.copyOf(originalDictionary, 65536);
        java.lang.reflect.Constructor<?> info =
                originalDictionary
                        .getClass()
                        .getComponentType()
                        .getDeclaredConstructor(BiomeDictionary.Type[].class);
        info.setAccessible(true);
        int[] ids = {255, 256, 300, 329, 3000, 3100, 4096, 8192, 32768, 65534, 65535};
        try {
            dictionaryField.set(null, dictionary);
            for (int id : ids) {
                BiomeGenBase biome = new BiomeGenBase(254) {};
                idField.setInt(biome, id);
                biome.biomeName = "Runtime " + id;
                registry[id] = biome;
                dictionary[id] =
                        info.newInstance(
                                (Object) new BiomeDictionary.Type[] {BiomeDictionary.Type.PLAINS});
            }
            BiomeGenBase[] entries =
                    Arrays.stream(registry).filter(Objects::nonNull).toArray(BiomeGenBase[]::new);
            Object catalog =
                    BiomeWorkerServer.getBiomeDescriptors(
                            registry,
                            Collections.singletonMap("bartworks:ross128b", entries),
                            Collections.singletonMap("Everglades", entries));
            Map<Integer, String> names = new HashMap<>();
            for (JsonElement entry : new Gson().toJsonTree(catalog).getAsJsonArray()) {
                int id = entry.getAsJsonObject().get("id").getAsInt();
                assertNull(
                        "Duplicate wire ID " + id,
                        names.put(id, entry.getAsJsonObject().get("name").getAsString()));
            }
            assertEquals(ids.length * 3 + 8, names.size());
            for (int id : ids) {
                assertEquals("Runtime " + id, names.get(id));
                assertEquals(
                        "Runtime " + id,
                        names.get(SpaceDimensionSampler.displayBiomeId("bartworks:ross128b", id)));
                assertEquals(
                        "Runtime " + id,
                        names.get(AdditionalDimensionBiomes.displayId("Everglades", id)));
            }
            assertEquals("GTNH End Void", names.get(EndStructurePredictor.END_VOID_BIOME));
            assertEquals("Ceres", names.get(SpaceDimensionSampler.CERES_DISPLAY_BIOME));
        } finally {
            dictionaryField.set(null, originalDictionary);
            BiomeGenBase.getBiomeGenArray()[254] = original;
        }
    }

    private static void reserve(BitSet occupied, int id) {
        assertFalse("Display ID collision: " + id, occupied.get(id));
        occupied.set(id);
    }
}
