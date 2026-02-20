package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileReadToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void readsFileInsideWorkDir() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "content");
        var tool = new FileReadTool();
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("path", "test.txt");

        var result = tool.execute(ctx, input);

        assertFalse(result.isError());
        assertEquals("content", result.output());
    }

    @Test
    void rejectsPathTraversal() {
        var tool = new FileReadTool();
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode().put("path", "../../etc/passwd");

        var result = tool.execute(ctx, input);

        assertTrue(result.isError());
    }
}
