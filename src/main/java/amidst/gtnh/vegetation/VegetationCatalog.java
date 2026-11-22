package amidst.gtnh.vegetation;

import amidst.gtnh.worker.GtnhBiomeDescriptor;
import amidst.i18n.I18n;
import amidst.i18n.Language;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldType;

import com.google.gson.Gson;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned generation eligibility, never a claim that a plant exists at a coordinate. */
public final class VegetationCatalog {
    private static final VegetationCatalog INSTANCE = load();
    private String version;
    private Map<String, String> generators;
    private Map<String, List<String>> eligible;
    private List<Entry> entries;

    private static VegetationCatalog load() {
        try (var stream =
                        Objects.requireNonNull(
                                VegetationCatalog.class.getResourceAsStream(
                                        "/amidst/gtnh/vegetation.json"));
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var result = new Gson().fromJson(reader, VegetationCatalog.class);
            Set<String> ids = new HashSet<>();
            for (Entry entry : result.entries) {
                if (!ids.add(entry.id))
                    throw new IllegalStateException("Duplicate vegetation ID: " + entry.id);
            }
            return result;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static VegetationCatalog get() {
        return INSTANCE;
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public String version() {
        return version;
    }

    public String generatorName(WorldType type) {
        return generators.getOrDefault(generator(type), "Unsupported");
    }

    private static String generator(WorldType type) {
        return type.getSymbolicFieldName();
    }

    public static boolean supports(Dimension dimension) {
        return dimension == Dimension.OVERWORLD
                || dimension == Dimension.NETHER
                || dimension == Dimension.TWILIGHT_FOREST
                || dimension == Dimension.ROSS_128B;
    }

    public boolean eligible(GtnhBiomeDescriptor biome, WorldType type, Dimension dimension) {
        if (!generators.containsKey(generator(type))) return false;
        if (dimension == Dimension.ROSS_128B) {
            // Space-scoped IDs are distinct from raw, same-name overworld biomes.
            // SpaceDimensionSampler.SCOPED_BIOME_DIMENSIONS: Ross is index 9.
            return biome.id() >= 11 * 65536 && biome.id() < 12 * 65536;
        }
        if (biome.id() >= 65536) return false;
        return switch (dimension) {
            case OVERWORLD ->
                    !biome.hasTag("TWILIGHT_FOREST")
                            && !biome.hasTag("NETHER")
                            && eligible.get(generator(type)).contains(biome.name());
            case NETHER -> biome.hasTag("NETHER") || biome.name().equals("Hell");
            case TWILIGHT_FOREST -> biome.hasTag("TWILIGHT_FOREST");
            default -> false;
        };
    }

    public boolean matches(
            Entry entry, GtnhBiomeDescriptor biome, WorldType type, Dimension dimension) {
        return eligible(biome, type, dimension)
                && entry.rules.stream()
                        .anyMatch(rule -> rule.matches(biome, generator(type), dimension.name()));
    }

    public List<GtnhBiomeDescriptor> matching(
            List<GtnhBiomeDescriptor> biomes,
            Collection<String> selected,
            boolean all,
            WorldType type,
            Dimension dimension) {
        if (selected.isEmpty()) return List.of();
        var chosen = entries.stream().filter(e -> selected.contains(e.id)).toList();
        if (chosen.size() != selected.size())
            throw new IllegalArgumentException("Unknown vegetation selection");
        return biomes.stream()
                .filter(b -> eligible(b, type, dimension))
                .filter(
                        b ->
                                all
                                        ? chosen.stream()
                                                .allMatch(e -> matches(e, b, type, dimension))
                                        : chosen.stream()
                                                .anyMatch(e -> matches(e, b, type, dimension)))
                .sorted(
                        Comparator.comparing(GtnhBiomeDescriptor::name)
                                .thenComparingInt(GtnhBiomeDescriptor::id))
                .toList();
    }

    public static final class Entry {
        private String id, name, zhName, category, mod, note, zhNote;
        private List<Rule> rules;

        public String id() {
            return id;
        }

        public String category() {
            return category;
        }

        public String mod() {
            return mod;
        }

        public String name() {
            return I18n.language() == Language.SIMPLIFIED_CHINESE ? zhName : name;
        }

        public String searchText() {
            return (id + " " + name + " " + zhName + " " + mod).toLowerCase(Locale.ROOT);
        }

        public String description(WorldType type, Dimension dimension) {
            var lines = new ArrayList<String>();
            lines.add(name() + " — " + mod);
            if (note != null)
                lines.add(
                        I18n.language() == Language.SIMPLIFIED_CHINESE && zhNote != null
                                ? zhNote
                                : note);
            boolean applicable = false;
            for (Rule r : rules) {
                if (!r.generators.contains(generator(type))
                        || !r.dimensions.contains(dimension.name())) continue;
                applicable = true;
                String condition =
                        r.names != null
                                ? String.join(", ", r.names)
                                : r.tags.stream()
                                        .map(
                                                c ->
                                                        c.isEmpty()
                                                                ? I18n.text(
                                                                        "Any biome; placement"
                                                                            + " conditions still"
                                                                            + " apply")
                                                                : String.join(" + ", c))
                                        .reduce((a, b) -> a + " / " + b)
                                        .orElse("");
                if (r.excludeNames != null)
                    condition +=
                            " " + I18n.text("Except:") + " " + String.join(", ", r.excludeNames);
                lines.add(condition);
            }
            if (!applicable)
                lines.add(I18n.text("No verified generation rule in this dimension/generator."));
            return String.join("\n", new LinkedHashSet<>(lines));
        }

        @Override
        public String toString() {
            return name() + " — " + mod;
        }
    }

    private static final class Rule {
        private List<String> names, excludeNames, generators, dimensions;
        private List<List<String>> tags;
        private String source;

        boolean matches(GtnhBiomeDescriptor b, String generator, String dimension) {
            return generators.contains(generator)
                    && dimensions.contains(dimension)
                    && (excludeNames == null || !excludeNames.contains(b.name()))
                    && (names == null || names.contains(b.name()))
                    && (tags == null
                            || tags.stream()
                                    .anyMatch(
                                            clause ->
                                                    clause.stream()
                                                            .allMatch(
                                                                    t ->
                                                                            t.startsWith("!")
                                                                                    ? !b.hasTag(
                                                                                            t
                                                                                                    .substring(
                                                                                                            1))
                                                                                    : b.hasTag(
                                                                                            t))));
        }
    }
}
