package com.javaclaw.shared.model;

import java.util.List;
import java.util.Map;

public record AgentResponse(
    String content,
    List<Map<String, Object>> toolCalls,
    Map<String, Integer> usage
) {}
