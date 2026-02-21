package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.shared.config.ToolsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BrowserToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SecurityPolicy policy() {
        return new SecurityPolicy(tempDir, true, 1000, Set.of("example.com"));
    }

    @Test
    void rejectsHttpUrl() {
        var tool = new BrowserTool(policy(), ToolsConfig.BrowserConfig.defaults());
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("url", "http://example.com").put("action", "navigate");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
        assertTrue(result.output().contains("HTTPS"));
    }

    @Test
    void rejectsUnsupportedAction() {
        var tool = new BrowserTool(policy(), new ToolsConfig.BrowserConfig(true, Set.of("example.com"), 30));
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("url", "https://example.com").put("action", "click");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
        assertTrue(result.output().contains("Unsupported action"));
    }
}
