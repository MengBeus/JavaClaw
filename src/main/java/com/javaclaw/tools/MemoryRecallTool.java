package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.memory.MemoryStore;

public class MemoryRecallTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MemoryStore memoryStore;

    public MemoryRecallTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override public String name() { return "memory_recall"; }

    @Override public String description() {
        return "Search long-term memory for relevant knowledge. Returns matching memory snippets.";
    }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        MAPPER.createObjectNode()
                                .set("query", MAPPER.createObjectNode().put("type", "string").put("description", "search query"))
                )
                .set("required", MAPPER.createArrayNode().add("query"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        var query = input.path("query").asText("");
        if (query.isBlank()) return new ToolResult("query is required", true);
        var results = memoryStore.recall(query, 5);
        if (results.isEmpty()) return new ToolResult("No memories found.", false);
        var sb = new StringBuilder();
        for (var r : results) {
            sb.append("- [").append(String.format("%.3f", r.score())).append("] ").append(r.content()).append("\n");
        }
        return new ToolResult(sb.toString(), false);
    }
}
