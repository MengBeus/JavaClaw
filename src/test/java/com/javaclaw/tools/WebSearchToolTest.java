package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.shared.config.ToolsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SecurityPolicy policy() {
        return new SecurityPolicy(tempDir, true, 1000, Set.of());
    }

    @Test
    void rejectsBlankQuery() {
        var tool = new WebSearchTool(policy(), ToolsConfig.WebSearchConfig.defaults());
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("query", "");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
    }

    @Test
    void rejectsUnsupportedProvider() {
        var config = new ToolsConfig.WebSearchConfig(true, "google", 5, 15);
        var tool = new WebSearchTool(policy(), config);
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("query", "test");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
        assertTrue(result.output().contains("Unsupported search provider"));
    }
}
