package amidst.gtnh.worker;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

final class WorkerConfig {

    static final int DEFAULT_PORT = 47117;

    final boolean enabled;
    final int port;
    final String token;

    static WorkerConfig load(File file) {
        Configuration config = new Configuration(file);
        config.load();
        boolean configuredEnabled = config.getBoolean(
                "enabled",
                "worker",
                true,
                "Enable the loopback-only Amidst biome worker.");
        int configuredPort = config.getInt(
                "port",
                "worker",
                DEFAULT_PORT,
                1,
                65535,
                "TCP port bound on 127.0.0.1.");
        String configuredToken = config
                .getString("token", "worker", "", "Optional shared token. Recommended on multi-user computers.")
                .trim();
        if (config.hasChanged()) {
            config.save();
        }

        boolean enabled = Boolean.parseBoolean(
                System.getProperty("gtnh.amidst.worker.enabled", Boolean.toString(configuredEnabled)));
        int port = Integer.getInteger("gtnh.amidst.worker.port", configuredPort).intValue();
        String token = System.getProperty("gtnh.amidst.worker.token", configuredToken);
        return new WorkerConfig(enabled, port, token);
    }

    private WorkerConfig(boolean enabled, int port, String token) {
        this.enabled = enabled;
        this.port = port;
        this.token = token == null ? "" : token;
    }
}
