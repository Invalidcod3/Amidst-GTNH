package amidst.gtnh.worker;

import java.nio.file.Path;

import amidst.documentation.Immutable;

@Immutable
public record GtnhWorkerSettings(String host, int port, String token, Path biomeColorsFile) {
	public GtnhWorkerSettings(String host, int port, String token) {
		this(host, port, token, null);
	}

	public GtnhWorkerSettings {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("GTNH worker host must not be blank");
		}
		if (port < 1 || port > 65_535) {
			throw new IllegalArgumentException("GTNH worker port is outside the valid TCP range");
		}
		token = token == null ? "" : token;
	}
}
