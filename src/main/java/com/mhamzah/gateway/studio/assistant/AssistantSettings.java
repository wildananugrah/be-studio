package com.mhamzah.gateway.studio.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Connection settings of the Studio assistant: {@code AI_BASE_URL}, {@code AI_AUTH_KEY}, {@code AI_MODEL}, the
 * optional {@code AI_EFFORT} (low / medium / high / xhigh / max) and the optional {@code AI_AUTH_TYPE}: how the key is
 * sent, {@code api-key} ({@code x-api-key} header, Anthropic API keys), {@code bearer} ({@code Authorization: Bearer},
 * e.g. OpenRouter and most gateways) or {@code auto} (default: api-key for {@code sk-ant-api} keys, else bearer).
 * Each comes from the environment (OS variables, system properties, application config) first, then from the
 * {@code .env} file. {@code baseUrl} null means the
 * SDK default ({@code https://api.anthropic.com}); the endpoint must speak the Anthropic Messages API.
 */
public record AssistantSettings(String baseUrl, String authKey, String model, String effort, String authType,
        String source) {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";

    /** True when the key goes in {@code Authorization: Bearer}, false for {@code x-api-key}. */
    public boolean bearer() {
        if ("bearer".equalsIgnoreCase(authType)) {
            return true;
        }
        if ("api-key".equalsIgnoreCase(authType)) {
            return false;
        }
        return authKey != null && !authKey.startsWith("sk-ant-api");
    }

    public boolean configured() {
        return authKey != null;
    }

    public static AssistantSettings load(Environment environment, Path envFile) {
        Map<String, String> file = readEnvFile(envFile);
        String key = value("AI_AUTH_KEY", environment, file);
        String model = value("AI_MODEL", environment, file);
        String source = environment.getProperty("AI_AUTH_KEY") != null ? "environment"
                : file.containsKey("AI_AUTH_KEY") ? envFile.toString() : "not set";
        return new AssistantSettings(value("AI_BASE_URL", environment, file), key,
                model == null ? DEFAULT_MODEL : model, value("AI_EFFORT", environment, file),
                value("AI_AUTH_TYPE", environment, file), source);
    }

    private static String value(String name, Environment environment, Map<String, String> file) {
        String v = environment.getProperty(name);
        if (v == null || v.isBlank()) {
            v = file.get(name);
        }
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** {@code KEY=value} lines; {@code #} comments, an {@code export } prefix and surrounding quotes are allowed. */
    static Map<String, String> readEnvFile(Path file) {
        Map<String, String> out = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).strip();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                } else {
                    int comment = value.indexOf(" #");
                    if (comment >= 0) {
                        value = value.substring(0, comment).strip();
                    }
                }
                out.put(name, value);
            }
        } catch (IOException e) {
            // unreadable .env: behave as if it were absent
        }
        return out;
    }

    /** Never print the key. */
    @Override
    public String toString() {
        return "AssistantSettings[baseUrl=" + baseUrl + ", model=" + model + ", effort=" + effort
                + ", key=" + (authKey == null ? "missing"
                        : "set, sent as " + (bearer() ? "Authorization: Bearer" : "x-api-key"))
                + ", source=" + source + "]";
    }
}
