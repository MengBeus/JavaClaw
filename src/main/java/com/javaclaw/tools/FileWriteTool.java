package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
            if (!target.startsWith(base)) {
                return new ToolResult("Path escapes working directory", true);
            }

            var parent = target.getParent();
            if (parent == null || !parent.startsWith(base)) {
                return new ToolResult("Path escapes working directory", true);
            }

            // Ensure the nearest existing ancestor is still rooted in workDir.
            var existingAncestor = parent;
            while (existingAncestor != null && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
                existingAncestor = existingAncestor.getParent();
            }
            if (existingAncestor == null || !existingAncestor.toRealPath().startsWith(base)) {
                return new ToolResult("Path escapes working directory", true);
            }

            // Reject writing through an existing symlink target.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
                return new ToolResult("Target is a symlink", true);
            }

            // Create parent directories only after all boundary checks.
            Files.createDirectories(parent);
            if (!parent.toRealPath().startsWith(base)) {
                return new ToolResult("Path escapes working directory", true);
            }

            var content = input.get("content").asText();
            try (var out = Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }

            return new ToolResult("Written to " + target, false);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
