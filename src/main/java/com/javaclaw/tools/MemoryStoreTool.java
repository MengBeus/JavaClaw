package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.memory.MemoryStore;

import java.util.Map;

public class MemoryStoreTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MemoryStore memoryStore;

    public MemoryStoreTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override public String name() { return "memory_store"; }

    @Override public String description() {
        return "Store important information to long-term memory for future recall.";
    }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        MAPPER.createObjectNode()
                                .set("content", MAPPER.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "the knowledge to remember"))
                )
                .set("required", MAPPER.createArrayNode().add("content"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        var content = input.path("content").asText("");
        if (content.isBlank()) return new ToolResult("content is required", true);
        memoryStore.store(content, Map.of("sessionId", ctx.sessionId()));
        return new ToolResult("Stored to memory.", false);
    }
}
