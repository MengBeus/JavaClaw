package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

public class FileReadTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "file_read"; }

    @Override public String description() {
        return "Read the contents of a file";
    }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        MAPPER.createObjectNode().set("path",
                                MAPPER.createObjectNode().put("type", "string")))
                .set("required", MAPPER.createArrayNode().add("path"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            var base = Path.of(ctx.workDir()).toRealPath();
            var target = base.resolve(input.get("path").asText()).toRealPath();
            if (!target.startsWith(base)) {
                return new ToolResult("Path escapes working directory", true);
            }
            return new ToolResult(Files.readString(target), false);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
