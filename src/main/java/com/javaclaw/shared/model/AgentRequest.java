package com.javaclaw.shared.model;

import java.util.Map;

public record AgentRequest(
    String sessionId,
    String message,
    Map<String, Object> context
) {}
