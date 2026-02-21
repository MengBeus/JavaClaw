package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.tools.Tool;
import com.javaclaw.tools.ToolContext;
import com.javaclaw.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts a single MCP Server tool into the JAVAClaw Tool interface.
 */
public class McpToolBridge implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final McpClient client;
    private final McpClient.McpToolDef def;

    public McpToolBridge(McpClient client, McpClient.McpToolDef def) {
        this.client = client;
        this.def = def;
    }

    @Override public String name() { return "mcp_" + client.name() + "_" + def.name(); }
    @Override public String description() { return def.description(); }
    @Override public JsonNode inputSchema() { return def.inputSchema(); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            var result = client.callTool(def.name(), input);
            if (result == null) {
                return new ToolResult("[ERROR] MCP tool returned no result", true);
            }
            return parseResult(result);
        } catch (Exception e) {
            log.warn("MCP tool {} failed: {}", name(), e.getMessage());
            return new ToolResult("[ERROR] " + e.getMessage(), true);
        }
    }

    private ToolResult parseResult(JsonNode result) {
        // MCP tools/call returns { content: [{type, text}...], isError? }
        boolean isError = result.path("isError").asBoolean(false);
        var content = result.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            return new ToolResult("", isError);
        }
        var sb = new StringBuilder();
        for (var item : content) {
            if ("text".equals(item.path("type").asText())) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(item.path("text").asText());
            }
        }
        return new ToolResult(sb.toString(), isError);
    }
}
