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
    void rejectsPathTraversal() throws Exception {
        // Create a file outside workDir (sibling of tempDir)
        var outside = Files.createTempFile(tempDir.getParent(), "secret", ".txt");
        Files.writeString(outside, "sensitive-data");
        try {
            var tool = new FileReadTool();
            var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
            var input = MAPPER.createObjectNode().put("path", "../" + outside.getFileName());

            var result = tool.execute(ctx, input);

            assertTrue(result.isError());
            assertTrue(result.output().contains("Path escapes working directory"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
