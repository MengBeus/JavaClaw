package com.javaclaw.providers;

import java.util.List;
import java.util.Map;

public record ChatRequest(
    String model,
    List<Map<String, String>> messages,
    double temperature
) {}
