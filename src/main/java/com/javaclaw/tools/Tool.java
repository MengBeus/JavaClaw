package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {
    String name();
    String description();
    JsonNode inputSchema();
    ToolResult execute(ToolContext ctx, JsonNode input);
}
