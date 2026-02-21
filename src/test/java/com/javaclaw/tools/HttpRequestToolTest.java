package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.shared.config.ToolsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SecurityPolicy policy(Set<String> domains) {
        return new SecurityPolicy(tempDir, true, 1000, domains);
    }

    @Test
    void rejectsBlockedDomain() {
        var tool = new HttpRequestTool(
                policy(Set.of("example.com")),
                ToolsConfig.HttpRequestConfig.defaults());
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("url", "https://evil.com/api");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
    }

    @Test
    void rejectsUnsupportedMethod() {
        var tool = new HttpRequestTool(
                policy(Set.of("example.com")),
                ToolsConfig.HttpRequestConfig.defaults());
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode()
                .put("url", "https://example.com")
                .put("method", "TRACE");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
        assertTrue(result.output().contains("Unsupported method"));
    }
}
