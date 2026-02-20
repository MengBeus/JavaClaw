package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileWriteToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesInsideWorkDir() throws Exception {
        var tool = new FileWriteTool();
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var input = MAPPER.createObjectNode()
                .put("path", "a/b.txt")
                .put("content", "hello");

        var result = tool.execute(ctx, input);

        assertFalse(result.isError());
        assertEquals("hello", Files.readString(tempDir.resolve("a/b.txt")));
    }

    @Test
    void rejectsTraversalWithoutCreatingOutsideDirs() {
        var tool = new FileWriteTool();
        var ctx = new ToolContext(tempDir.toString(), "s1", Set.of());
        var outsideDirName = "escape-" + System.nanoTime();
        var input = MAPPER.createObjectNode()
                .put("path", "../" + outsideDirName + "/pwn.txt")
                .put("content", "x");

        var result = tool.execute(ctx, input);

        assertTrue(result.isError());
        assertFalse(Files.exists(tempDir.getParent().resolve(outsideDirName)));
    }
}
