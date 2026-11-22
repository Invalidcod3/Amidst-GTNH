package amidst.gtnh.worker;

import java.io.InputStream;
import java.util.Properties;

/** Select a complete audited build, never a mixture of individually accepted class versions. */
final class Rwg2Version {
    final String version;
    final boolean modern;
    final Properties approved;
    private static Rwg2Version current;

    private Rwg2Version(String version, String resource) throws Exception {
        this.version = version;
        modern = version.equals("2.0.9");
        approved = new Properties();
        try (InputStream in = Rwg2Version.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing RWG2 audit: " + resource);
            approved.load(in);
        }
    }

    static synchronized Rwg2Version current() throws Exception {
        if (current == null) {
            byte[] manager = Rwg2Runtime.bytes(Class.forName(Rwg2Runtime.MANAGER));
            Rwg2Version legacy = new Rwg2Version("2.0.6", "rwg2-parallel-sha256.properties");
            Rwg2Version modern = new Rwg2Version("2.0.9", "rwg2-parallel-2.0.9-sha256.properties");
            String key = "rwg2/world/ChunkManagerRealistic.class";
            if (Rwg2Runtime.hash(manager).equals(legacy.approved.getProperty(key)))
                current = legacy;
            else if (Rwg2Runtime.hash(manager).equals(modern.approved.getProperty(key)))
                current = modern;
            else
                throw new IllegalStateException(
                        "Unsupported RWG2 build; audited versions: 2.0.6, 2.0.9");
        }
        return current;
    }

    void verify(Class<?> type) throws Exception {
        verify(type.getName().replace('.', '/') + ".class", Rwg2Runtime.bytes(type));
    }

    /** Optional handshake information; an unknown build must not break unrelated Alpha queries. */
    static String auditedVersion() {
        try {
            return current().version;
        } catch (Exception | LinkageError unsupported) {
            return null;
        }
    }

    void verify(String resource, byte[] bytes) throws Exception {
        Rwg2Runtime.verify(bytes, approved.getProperty(resource));
    }

    static String settingsClass() {
        try {
            return current().modern ? "rwg2.config.SettingsConfig" : "rwg2.config.ConfigRWG";
        } catch (Exception e) {
            throw new IllegalStateException("Cannot select RWG2 generation settings", e);
        }
    }
}
