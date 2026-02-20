package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.ToolExecutor;

public class ShellTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ToolExecutor executor;

    public ShellTool(ToolExecutor executor) {
        this.executor = executor;
    }

    @Override public String name() { return "shell"; }

    @Override public String description() {
        return "Execute a shell command";
    }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        MAPPER.createObjectNode().set("command",
                                MAPPER.createObjectNode().put("type", "string")))
                .set("required", MAPPER.createArrayNode().add("command"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            var command = input.get("command").asText();
            var output = executor.execute(command, ctx.workDir(), 30);
            boolean isError = output.startsWith("[BLOCKED]") || output.startsWith("[TIMEOUT]");
            return new ToolResult(output, isError);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
