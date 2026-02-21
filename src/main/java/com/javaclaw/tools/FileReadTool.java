package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;

import java.nio.file.Files;

public class FileReadTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SecurityPolicy securityPolicy;

    public FileReadTool(SecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

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
            securityPolicy.checkRateLimit("file_read");
            var validated = securityPolicy.validatePath(input.get("path").asText(), true);
            return new ToolResult(Files.readString(validated), false);
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
