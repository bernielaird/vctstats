package dev.bernie.vct.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Settings that stay off GitHub. Read from a gitignored .env file (KEY=value lines),
 * with real environment variables taking priority. See .env.example.
 */
final class LocalConfig {

    private final Map<String, String> values = new HashMap<>();

    LocalConfig(Path envFile) throws IOException {
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq < 0) {
                    continue;
                }
                values.put(trimmed.substring(0, eq).trim(), unquote(trimmed.substring(eq + 1).trim()));
            }
        }
    }

    /** Environment variable if set, otherwise the .env value, otherwise null. */
    String get(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
