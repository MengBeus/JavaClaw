package com.javaclaw.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    @SuppressWarnings("unchecked")
    public static List<Skill> loadFrom(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        var yaml = new Yaml();
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .map(p -> {
                    try (var in = Files.newInputStream(p)) {
                        Map<String, Object> raw = yaml.load(in);
                        if (raw == null) return null;
                        var prompt = (String) raw.get("system_prompt");
                        return new Skill(
                            (String) raw.get("name"),
                            (String) raw.get("trigger"),
                            prompt != null && !prompt.isBlank() ? prompt : null,
                            (List<String>) raw.getOrDefault("tools", List.of())
                        );
                    } catch (Exception e) {
                        log.warn("Failed to load skill from {}: {}", p, e.getMessage());
                        return null;
                    }
                })
                .filter(s -> s != null && s.trigger() != null)
                .toList();
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", dir, e.getMessage());
            return List.of();
        }
    }
}
