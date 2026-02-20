package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.ExecutionResult;
import com.javaclaw.security.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void marksNonZeroExitAsError() {
        ToolExecutor executor = new ToolExecutor() {
            @Override
            public ExecutionResult execute(String command, String workDir, long timeoutSeconds) {
                return new ExecutionResult("failed", 2);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var tool = new ShellTool(executor);
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("command", "x");

        var result = tool.execute(ctx, input);

        assertTrue(result.isError());
    }

    @Test
    void keepsZeroExitAsSuccess() {
        ToolExecutor executor = new ToolExecutor() {
            @Override
            public ExecutionResult execute(String command, String workDir, long timeoutSeconds) {
                return new ExecutionResult("ok", 0);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var tool = new ShellTool(executor);
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("command", "x");

        var result = tool.execute(ctx, input);

        assertFalse(result.isError());
    }
}
