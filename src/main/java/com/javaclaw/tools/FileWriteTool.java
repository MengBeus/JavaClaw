package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

public class FileWriteTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "file_write"; }

    @Override public String description() {
        return "Write content to a file";
    }

    @Override public JsonNode inputSchema() {
        var props = MAPPER.createObjectNode();
        props.set("path", MAPPER.createObjectNode().put("type", "string"));
        props.set("content", MAPPER.createObjectNode().put("type", "string"));
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties", props)
                .set("required", MAPPER.createArrayNode().add("path").add("content"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            var base = Path.of(ctx.workDir()).toRealPath();
            var target = base.resolve(input.get("path").asText()).normalize();
            var content = input.get("content").asText();
            Files.createDirectories(target.getParent());
            if (!target.getParent().toRealPath().startsWith(base)) {
                return new ToolResult("Path escapes working directory", true);
            }
            Files.writeString(target, content);
            return new ToolResult("Written to " + target, false);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
