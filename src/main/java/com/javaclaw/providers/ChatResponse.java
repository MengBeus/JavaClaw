package com.javaclaw.providers;

import java.util.List;
import java.util.Map;

public record ChatResponse(
    String content,
    Map<String, Integer> usage,
    List<ToolCallInfo> toolCalls
) {
    public ChatResponse(String content, Map<String, Integer> usage) {
        this(content, usage, List.of());
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
