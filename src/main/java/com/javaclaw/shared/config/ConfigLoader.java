package com.javaclaw.shared.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ConfigLoader {

    private static final Path DEFAULT_PATH = Path.of(
        System.getProperty("user.home"), ".javaclaw", "config.yaml"
    );

    public static JavaClawConfig load() {
        return load(DEFAULT_PATH);
    }

    @SuppressWarnings("unchecked")
    public static JavaClawConfig load(Path path) {
        Map<String, Object> raw;
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                raw = new Yaml().load(in);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config: " + path, e);
            }
        } else {
            raw = Map.of();
        }

        var server = (Map<String, Object>) raw.getOrDefault("server", Map.of());
        var providers = (Map<String, Object>) raw.getOrDefault("providers", Map.of());
        var db = (Map<String, Object>) raw.getOrDefault("database", Map.of());
        var keys = (Map<String, Object>) raw.getOrDefault("api-keys", Map.of());
        var sandbox = (Map<String, Object>) raw.getOrDefault("sandbox", Map.of());
        var telegram = (Map<String, Object>) raw.getOrDefault("telegram", Map.of());
        var discord = (Map<String, Object>) raw.getOrDefault("discord", Map.of());
        var mcpServers = (Map<String, Map<String, Object>>) raw.getOrDefault("mcp-servers", Map.of());

        var apiKeys = new java.util.HashMap<String, String>();
        keys.forEach((k, v) -> apiKeys.put(k, String.valueOf(v)));

        return new JavaClawConfig(
            Integer.parseInt(envOrDefault("JAVACLAW_PORT",
                String.valueOf(server.getOrDefault("port", 18789)))),
            (String) providers.getOrDefault("primary", "deepseek-v3"),
            (List<String>) providers.getOrDefault("fallback", List.of()),
            Map.of(
                "url", envOrDefault("JAVACLAW_DB_URL",
                    (String) db.getOrDefault("url", "jdbc:postgresql://localhost:5432/javaclaw")),
                "username", envOrDefault("JAVACLAW_DB_USER",
                    (String) db.getOrDefault("username", "javaclaw")),
                "password", envOrDefault("JAVACLAW_DB_PASS",
                    (String) db.getOrDefault("password", "javaclaw"))
            ),
            apiKeys,
            Boolean.TRUE.equals(sandbox.getOrDefault("allow-native-fallback", false)),
            envOrDefault("JAVACLAW_TELEGRAM_TOKEN",
                (String) telegram.getOrDefault("bot-token", "")),
            envOrDefault("JAVACLAW_DISCORD_TOKEN",
                (String) discord.getOrDefault("bot-token", "")),
            mcpServers
        );
    }

    private static String envOrDefault(String env, String fallback) {
        var val = System.getenv(env);
        return val != null ? val : fallback;
    }
}
