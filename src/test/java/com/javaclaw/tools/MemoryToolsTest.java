package com.javaclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.memory.MemoryResult;
import com.javaclaw.memory.MemoryStore;
import com.javaclaw.security.SecurityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SecurityPolicy policy() {
        return new SecurityPolicy(tempDir, true, 1000, Set.of());
    }

    @Test
    void storeToolWritesToMemory() throws Exception {
        var stored = new ArrayList<String>();
        var store = stubStore(stored, List.of());
        var tool = new MemoryStoreTool(store, policy());
        var input = MAPPER.readTree("{\"content\":\"remember this\"}");
        var result = tool.execute(new ToolContext(tempDir.toString(), "s1", Set.of()), input);
        assertFalse(result.isError());
        assertEquals(1, stored.size());
        assertTrue(stored.get(0).contains("remember this"));
    }

    @Test
    void storeToolRejectsBlankContent() throws Exception {
        var tool = new MemoryStoreTool(stubStore(new ArrayList<>(), List.of()), policy());
        var input = MAPPER.readTree("{\"content\":\"\"}");
        var result = tool.execute(new ToolContext(tempDir.toString(), "s1", Set.of()), input);
        assertTrue(result.isError());
    }

    @Test
    void recallToolReturnsResults() throws Exception {
        var results = List.of(new MemoryResult("1", "found it", 0.5, Map.of()));
        var tool = new MemoryRecallTool(stubStore(new ArrayList<>(), results), policy());
        var input = MAPPER.readTree("{\"query\":\"find\"}");
        var result = tool.execute(new ToolContext(tempDir.toString(), "s1", Set.of()), input);
        assertFalse(result.isError());
        assertTrue(result.output().contains("found it"));
    }

    @Test
    void recallToolReturnsEmptyMessage() throws Exception {
        var tool = new MemoryRecallTool(stubStore(new ArrayList<>(), List.of()), policy());
        var input = MAPPER.readTree("{\"query\":\"nothing\"}");
        var result = tool.execute(new ToolContext(tempDir.toString(), "s1", Set.of()), input);
        assertFalse(result.isError());
        assertTrue(result.output().contains("No memories"));
    }

    private MemoryStore stubStore(List<String> stored, List<MemoryResult> recallResults) {
        return new MemoryStore() {
            @Override public void store(String content, Map<String, Object> metadata) { stored.add(content); }
            @Override public List<MemoryResult> recall(String query, int topK) { return recallResults; }
            @Override public void forget(String memoryId) {}
        };
    }
}
