package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GitToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SecurityPolicy policy() {
        return new SecurityPolicy(tempDir, true, 1000, Set.of());
    }

    @Test
    void rejectsUnsupportedOperation() {
        var tool = new GitTool(policy());
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("operation", "push");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
        assertTrue(result.output().contains("Unsupported"));
    }

    @Test
    void rejectsUnsafeArgs() {
        var tool = new GitTool(policy());
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("operation", "log").put("args", "--oneline; rm -rf /");
        var result = tool.execute(ctx, input);
        assertTrue(result.isError());
        assertTrue(result.output().contains("Unsafe"));
    }

    @Test
    void statusRunsInNonGitDir() {
        var tool = new GitTool(policy());
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("operation", "status");
        var result = tool.execute(ctx, input);
        // non-git dir → git returns error exit code
        assertTrue(result.isError());
    }
}
