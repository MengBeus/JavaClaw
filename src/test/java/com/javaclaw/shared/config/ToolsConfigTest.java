package com.javaclaw.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolsConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsWhenNoToolsSection() throws IOException {
        var cfg = writeAndLoad("");
        var tools = cfg.tools();
        assertEquals(120, tools.security().maxActionsPerHour());
        assertTrue(tools.security().workspaceOnly());
        assertTrue(tools.httpRequest().enabled());
        assertTrue(tools.httpRequest().allowedDomains().isEmpty());
        assertEquals("duckduckgo", tools.webSearch().provider());
    }

    @Test
    void parsesFullConfig() throws IOException {
        var yaml = """
            tools:
              http-request:
                enabled: false
                allowed-domains: ["example.com", "api.test.io"]
                timeout: 60
                max-response-size: 512000
              web-search:
                enabled: false
                provider: brave
                max-results: 3
                timeout: 10
              security:
                max-actions-per-hour: 50
                workspace-only: false
            """;
        var cfg = writeAndLoad(yaml);
        var tools = cfg.tools();
        assertFalse(tools.httpRequest().enabled());
        assertEquals(2, tools.httpRequest().allowedDomains().size());
        assertTrue(tools.httpRequest().allowedDomains().contains("example.com"));
        assertEquals(60, tools.httpRequest().timeoutSeconds());
        assertEquals(512000, tools.httpRequest().maxResponseSize());
        assertFalse(tools.webSearch().enabled());
        assertEquals("brave", tools.webSearch().provider());
        assertEquals(3, tools.webSearch().maxResults());
        assertEquals(50, tools.security().maxActionsPerHour());
        assertFalse(tools.security().workspaceOnly());
    }

    @Test
    void partialConfigFallsBackToDefaults() throws IOException {
        var yaml = """
            tools:
              security:
                max-actions-per-hour: 200
            """;
        var cfg = writeAndLoad(yaml);
        var tools = cfg.tools();
        assertEquals(200, tools.security().maxActionsPerHour());
        // others should be defaults
        assertTrue(tools.httpRequest().enabled());
        assertEquals(5, tools.webSearch().maxResults());
    }

    private JavaClawConfig writeAndLoad(String yaml) throws IOException {
        var file = tempDir.resolve("config.yaml");
        Files.writeString(file, yaml);
        return ConfigLoader.load(file);
    }
}
