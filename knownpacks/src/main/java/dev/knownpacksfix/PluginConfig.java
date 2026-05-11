package dev.knownpacksfix;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class PluginConfig {

    private static final String CONFIG_FILE = "config.yml";

    private final int packLimit;
    private final boolean logFields;
    private final boolean debug;

    private PluginConfig(int packLimit, boolean logFields, boolean debug) {
        this.packLimit = packLimit;
        this.logFields = logFields;
        this.debug = debug;
    }

    static PluginConfig load(Path dataDirectory, Logger logger) {
        int packLimit = 1024;
        boolean logFields = true;
        boolean debug = false;

        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve(CONFIG_FILE);

            if (!Files.exists(configPath)) {
                copyDefaultConfig(configPath);
            }

            Map<String, String> values = parseSimpleYaml(configPath);

            if (values.containsKey("pack-limit")) {
                try {
                    int parsed = Integer.parseInt(values.get("pack-limit"));
                    if (parsed > 0) packLimit = parsed;
                } catch (NumberFormatException e) {
                    logger.warn("[KnownPacksFix] Invalid pack-limit in config.yml — using default (1024)");
                }
            }
            if (values.containsKey("log-fields")) {
                logFields = Boolean.parseBoolean(values.get("log-fields"));
            }
            if (values.containsKey("debug")) {
                debug = Boolean.parseBoolean(values.get("debug"));
            }

        } catch (IOException e) {
            logger.warn("[KnownPacksFix] Could not load config.yml, using defaults: {}", e.getMessage());
        }

        return new PluginConfig(packLimit, logFields, debug);
    }

    private static void copyDefaultConfig(Path destination) throws IOException {
        try (InputStream in = PluginConfig.class.getResourceAsStream("/" + CONFIG_FILE)) {
            if (in != null) {
                Files.copy(in, destination);
            }
        }
    }

    // Parses flat "key: value" YAML lines; ignores comments and blank lines.
    private static Map<String, String> parseSimpleYaml(Path path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            map.put(key, value);
        }
        return map;
    }

    public int getPackLimit() {
        return packLimit;
    }

    public boolean isLogFields() {
        return logFields;
    }

    public boolean isDebug() {
        return debug;
    }
}
