package com.javaclaw.providers;

import java.util.List;
import java.util.Map;

public record ChatRequest(
    String model,
    List<Map<String, Object>> messages,
    double temperature,
    List<Map<String, Object>> tools
) {
    public ChatRequest(String model, List<Map<String, Object>> messages, double temperature) {
        this(model, messages, temperature, null);
    }
}
