package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class ProspectingDimensionsTest {
    public static class Config {
        public String Dimension;
        Config(String selector) { Dimension = selector; }
    }
    public static class Body {
        final int id; final String name; final Class<?> provider;
        Body(int id, String name, Class<?> provider) { this.id = id; this.name = name; this.provider = provider; }
        public int getDimensionID() { return id; }
        public String getName() { return name; }
        public Class<?> getWorldProvider() { return provider; }
    }
    @Test public void resolvesUnloadedWorldsUsingActualGtPrecedenceAndCaseSensitiveProviders() throws Exception {
        Map<String, Config> configs = new LinkedHashMap<>();
        Config moon = new Config("Moon"), direct = new Config("-777"), fallback = new Config("Default");
        configs.put("Moon", moon); configs.put("-777", direct); configs.put("Default", fallback);
        assertSame(moon, ProspectingDimensions.resolveFluid(configs, new int[]{-1,1}, -28, "world.WorldProviderMoon"));
        assertSame(direct, ProspectingDimensions.resolveFluid(configs, new int[0], -777, "world.WorldProviderMoon"));
        assertNull(ProspectingDimensions.resolveFluid(configs, new int[]{-777}, -777, "world.WorldProviderMoon"));
        assertSame(fallback, ProspectingDimensions.resolveFluid(configs, new int[0], -28, "world.WorldProvidermoon"));
        configs.remove("Default");
        assertNull(ProspectingDimensions.resolveFluid(configs, new int[0], -30, "world.WorldProviderAsteroids"));
    }
    @Test public void discoversPlanetsWithoutCreatingProvidersAndKeepsConfiguredIds() throws Exception {
        Map<Integer, ProspectingDimensions.Candidate> result = new TreeMap<>();
        // These classes cannot be instantiated as providers; discovery must only read their identity.
        ProspectingDimensions.addBodies(result, Arrays.asList(new Body(-777, "moon", String.class),
                new Body(-1024, "barnarda4", Integer.class), new Body(0, "decorative-star", null)));
        assertEquals(2, result.size());
        assertEquals("moon", result.get(-777).name);
        assertEquals("java.lang.Integer", result.get(-1024).providerClass);
    }
}
