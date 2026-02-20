package com.javaclaw.providers;

import java.util.List;
import java.util.Map;

public record ChatResponse(
    String model,
    String content,
    Map<String, Integer> usage,
    List<ToolCallInfo> toolCalls
) {
    public ChatResponse(String content, Map<String, Integer> usage) {
        this(null, content, usage, List.of());
    }

    public ChatResponse(String content, Map<String, Integer> usage, List<ToolCallInfo> toolCalls) {
        this(null, content, usage, toolCalls);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
