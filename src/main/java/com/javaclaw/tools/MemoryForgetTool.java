package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.memory.MemoryStore;
import com.javaclaw.security.SecurityPolicy;

public class MemoryForgetTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MemoryStore memoryStore;
    private final SecurityPolicy securityPolicy;

    public MemoryForgetTool(MemoryStore memoryStore, SecurityPolicy securityPolicy) {
        this.memoryStore = memoryStore;
        this.securityPolicy = securityPolicy;
    }

    @Override public String name() { return "memory_forget"; }

    @Override public String description() {
        return "Delete a specific memory by its ID.";
    }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        MAPPER.createObjectNode()
                                .set("memory_id", MAPPER.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "ID of the memory to delete")))
                .set("required", MAPPER.createArrayNode().add("memory_id"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            securityPolicy.checkRateLimit("memory_forget");
            var memoryId = input.path("memory_id").asText("");
            if (memoryId.isBlank()) return new ToolResult("memory_id is required", true);
            memoryStore.forget(memoryId);
            return new ToolResult("Memory deleted: " + memoryId, false);
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
