package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;

import com.javaclaw.approval.DangerousOperation;

@DangerousOperation(reason = "Writes files to disk")
public class FileWriteTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SecurityPolicy securityPolicy;

    public FileWriteTool(SecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

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
            securityPolicy.checkRateLimit("file_write");
            var validated = securityPolicy.validatePath(input.get("path").asText(), false);

            var parent = validated.getParent();
            if (parent != null) Files.createDirectories(parent);

            var content = input.get("content").asText();
            try (var out = Files.newOutputStream(
                    validated,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }

            return new ToolResult("Written to " + validated, false);
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
